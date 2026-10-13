// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import android.util.Log
import de.linuxundich.hausmusik.BuildConfig
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** What is known about an artist. [summary] is a Wikipedia extract and needs the attribution in [summaryUrl]. */
data class ArtistDetails(
    val name: String,
    val type: String?,
    val country: String?,
    val begin: String?,
    val end: String?,
    val disambiguation: String?,
    val genres: List<String>,
    val summary: String?,
    val summaryUrl: String?,
    val musicBrainzUrl: String,
)

/** What is known about an album (a MusicBrainz release group). */
data class AlbumDetails(
    val title: String,
    val artist: String?,
    val type: String?,
    val firstRelease: String?,
    val genres: List<String>,
    val summary: String?,
    val summaryUrl: String?,
    val musicBrainzUrl: String,
)

@Serializable
private data class MbLifeSpan(val begin: String? = null, val end: String? = null, val ended: Boolean? = null)

@Serializable
private data class MbTag(val name: String = "", val count: Int = 0)

@Serializable
private data class MbArtist(
    val id: String = "",
    val name: String = "",
    val type: String? = null,
    val country: String? = null,
    val score: Int = 0,
    val disambiguation: String? = null,
    @SerialName("life-span") val lifeSpan: MbLifeSpan? = null,
    val genres: List<MbTag> = emptyList(),
    val tags: List<MbTag> = emptyList(),
    val relations: List<MbRelation> = emptyList(),
)

@Serializable
private data class MbUrl(val resource: String = "")

@Serializable
private data class MbRelation(val type: String = "", val url: MbUrl? = null)

@Serializable
private data class MbArtistSearch(val artists: List<MbArtist> = emptyList())

@Serializable
private data class MbCreditArtist(val name: String = "")

@Serializable
private data class MbCredit(val name: String = "", val joinphrase: String = "", val artist: MbCreditArtist? = null)

@Serializable
private data class MbGroup(
    val id: String = "",
    val title: String = "",
    val score: Int = 0,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
    @SerialName("first-release-date") val firstRelease: String? = null,
    @SerialName("artist-credit") val credits: List<MbCredit> = emptyList(),
    val genres: List<MbTag> = emptyList(),
    val tags: List<MbTag> = emptyList(),
    val relations: List<MbRelation> = emptyList(),
)

@Serializable
private data class MbGroupSearch(@SerialName("release-groups") val groups: List<MbGroup> = emptyList())

@Serializable
private data class WdSitelink(val title: String = "")

@Serializable
private data class WdEntity(val sitelinks: Map<String, WdSitelink> = emptyMap())

@Serializable
private data class WdResponse(val entities: Map<String, WdEntity> = emptyMap())

@Serializable
private data class WikiSummary(
    val type: String? = null,
    val extract: String? = null,
    @SerialName("content_urls") val urls: WikiUrls? = null,
)

@Serializable
private data class WikiUrls(val desktop: WikiPage? = null)

@Serializable
private data class WikiPage(val page: String? = null)

/**
 * Background on the artist or album that plays, from free sources: facts from
 * MusicBrainz (CC0), a short text from Wikipedia (CC BY-SA) when its page is
 * linked through Wikidata. Only used when the user switched it on: the
 * artist's and album's names go to musicbrainz.org, and, if the Wikipedia
 * text is on too, an id to wikidata.org and a page title to wikipedia.org.
 * Nothing else leaves the home network. Answers are kept until the app ends,
 * and MusicBrainz is asked at most once a second, as its rules demand.
 */
@Singleton
class DetailsRepository(
    http: OkHttpClient,
    private val settings: Settings,
    private val musicBrainz: HttpUrl,
    private val wikidata: HttpUrl,
    private val wikipedia: (String) -> HttpUrl,
    private val language: () -> String,
    private val minGapMs: Long,
    private val retryMs: Long = 2_500,
) {
    @Inject constructor(http: OkHttpClient, settings: Settings) : this(
        http, settings,
        "https://musicbrainz.org/ws/2/".toHttpUrl(),
        "https://www.wikidata.org/w/api.php".toHttpUrl(),
        { lang -> "https://$lang.wikipedia.org/api/rest_v1/page/summary/".toHttpUrl() },
        { Locale.getDefault().language },
        1_100,
    )

    // These services are on the internet: unlike the speakers, they take the default route.
    private val http = http.newBuilder().socketFactory(SocketFactory.getDefault())
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val artists = ConcurrentHashMap<String, Result<ArtistDetails?>>()
    private val albums = ConcurrentHashMap<String, Result<AlbumDetails?>>()
    private val gate = Mutex()
    private var lastMb = 0L

    /** Null: nothing found. Failure: the network or a service failed (worth another try). */
    suspend fun artist(name: String): Result<ArtistDetails?> {
        if (name.isBlank() || ArtistImages.normalize(name) in ArtistImages.COLLECTIONS) return Result.success(null)
        val key = ArtistImages.normalize(name) + "|" + wikiOn()
        artists[key]?.let { return it }
        val result = runCatching { withContext(Dispatchers.IO) { lookupArtist(name.trim()) } }
        if (result.isSuccess) artists[key] = result else Log.w(TAG, "artist lookup failed", result.exceptionOrNull())
        return result
    }

    suspend fun album(title: String, artist: String?): Result<AlbumDetails?> {
        if (title.isBlank()) return Result.success(null)
        val key = ArtistImages.normalize(title) + "|" + ArtistImages.normalize(artist.orEmpty()) + "|" + wikiOn()
        albums[key]?.let { return it }
        val result = runCatching { withContext(Dispatchers.IO) { lookupAlbum(title.trim(), artist?.trim()) } }
        if (result.isSuccess) albums[key] = result else Log.w(TAG, "album lookup failed", result.exceptionOrNull())
        return result
    }

    private suspend fun wikiOn() = settings.detailsWikipediaEnabled.first()

    private suspend fun requireOn() {
        if (!settings.detailsEnabled.first()) throw IllegalStateException("Background info is switched off")
    }

    internal suspend fun lookupArtist(name: String): ArtistDetails? {
        requireOn()
        val found = json.decodeFromString<MbArtistSearch>(
            mb("artist", "query" to "artist:\"${lucene(name)}\"", "limit" to "5"),
        ).artists
        val wanted = ArtistImages.normalize(name)
        val hit = found.filter { ArtistImages.normalize(it.name) == wanted && it.score >= 80 }.maxByOrNull { it.score } ?: return null
        val full = json.decodeFromString<MbArtist>(mb("artist/${hit.id}", "inc" to "genres+url-rels"))
        val (summary, url) = if (wikiOn()) wikipediaSummary(full.relations) else null to null
        return ArtistDetails(
            name = hit.name, type = hit.type, country = hit.country,
            begin = full.lifeSpan?.begin, end = if (full.lifeSpan?.ended == true) full.lifeSpan.end else null,
            disambiguation = hit.disambiguation?.ifBlank { null },
            genres = topNames(full.genres.ifEmpty { full.tags }),
            summary = summary, summaryUrl = url,
            musicBrainzUrl = "https://musicbrainz.org/artist/${hit.id}",
        )
    }

    internal suspend fun lookupAlbum(title: String, artist: String?): AlbumDetails? {
        requireOn()
        val query = buildString {
            append("releasegroup:\"${lucene(title)}\"")
            if (!artist.isNullOrBlank()) append(" AND artist:\"${lucene(artist)}\"")
        }
        val found = json.decodeFromString<MbGroupSearch>(mb("release-group", "query" to query, "limit" to "5")).groups
        val wanted = ArtistImages.normalize(title)
        val hit = found.filter { ArtistImages.normalize(it.title) == wanted && it.score >= 80 }.maxByOrNull { it.score } ?: return null
        val full = json.decodeFromString<MbGroup>(mb("release-group/${hit.id}", "inc" to "genres+url-rels+artist-credits"))
        val (summary, url) = if (wikiOn()) wikipediaSummary(full.relations) else null to null
        val type = (listOfNotNull(hit.primaryType) + hit.secondaryTypes).joinToString(" + ").ifBlank { null }
        return AlbumDetails(
            title = hit.title,
            artist = full.credits.ifEmpty { hit.credits }.joinToString("") { it.name + it.joinphrase }.ifBlank { null },
            type = type, firstRelease = hit.firstRelease?.ifBlank { null },
            genres = topNames(full.genres.ifEmpty { full.tags }),
            summary = summary, summaryUrl = url,
            musicBrainzUrl = "https://musicbrainz.org/release-group/${hit.id}",
        )
    }

    /**
     * One MusicBrainz request, never closer than [minGapMs] to the one before.
     * When MusicBrainz is busy (503) or limits us (429) it is asked once more
     * after [retryMs], which is what its rules suggest.
     */
    private suspend fun mb(path: String, vararg params: Pair<String, String>): String = gate.withLock {
        val url = musicBrainz.newBuilder().addPathSegments(path).addQueryParameter("fmt", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        try {
            repeat(2) { attempt ->
                val wait = lastMb + (if (attempt == 0) minGapMs else retryMs) - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                try {
                    return@withLock get(url)
                } catch (e: HttpException) {
                    if (attempt == 1 || e.code !in listOf(429, 503)) throw e
                } finally {
                    lastMb = System.currentTimeMillis()
                }
            }
            throw java.io.IOException("unreachable")
        } finally {
            lastMb = System.currentTimeMillis()
        }
    }

    private class HttpException(val code: Int, host: String) : java.io.IOException("$host: HTTP $code")

    private fun get(url: HttpUrl): String = http.newCall(
        Request.Builder().url(url)
            .header("User-Agent", "Hausmusik/${BuildConfig.VERSION_NAME} (https://github.com/linuxundich/hausmusik)")
            .header("Accept", "application/json").build(),
    ).execute().use { r ->
        if (!r.isSuccessful) throw HttpException(r.code, url.host) else r.body.string()
    }

    /**
     * The Wikipedia extract and page URL for the Wikidata item MusicBrainz links,
     * in the phone's language if that wiki has a page, otherwise in English.
     * A missing page is not an error: the facts are still shown.
     */
    private fun wikipediaSummary(relations: List<MbRelation>): Pair<String?, String?> = runCatching {
        val id = relations.firstOrNull { it.type == "wikidata" }?.url?.resource?.substringAfterLast('/')?.takeIf { Regex("Q\\d+").matches(it) }
            ?: return@runCatching null to null
        val lang = language().lowercase().takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        val wikis = listOf(lang, "en").distinct()
        val links = json.decodeFromString<WdResponse>(
            get(
                wikidata.newBuilder().addQueryParameter("action", "wbgetentities").addQueryParameter("ids", id)
                    .addQueryParameter("props", "sitelinks").addQueryParameter("sitefilter", wikis.joinToString("|") { "${it}wiki" })
                    .addQueryParameter("format", "json").build(),
            ),
        ).entities[id]?.sitelinks.orEmpty()
        for (w in wikis) {
            val title = links["${w}wiki"]?.title ?: continue
            val s = json.decodeFromString<WikiSummary>(get(wikipedia(w).newBuilder().addPathSegment(title).build()))
            val text = tidy(s.extract.orEmpty())
            if (s.type == "disambiguation" || text.isEmpty()) continue
            return@runCatching text to (s.urls?.desktop?.page ?: "https://$w.wikipedia.org/wiki/${java.net.URLEncoder.encode(title, "UTF-8")}")
        }
        null to null
    }.getOrDefault(null to null)

    companion object {
        private const val TAG = "Details"

        /** The three most voted genres (or tags), first letter upper case. */
        private fun topNames(tags: List<MbTag>): List<String> =
            tags.sortedByDescending { it.count }.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(3)
                .map { g -> g.replaceFirstChar { it.titlecase() } }

        /**
         * Wikipedia extracts break lines inside sentences (and sometimes put every
         * sentence on its own line): a single line break becomes a space, only
         * blank lines stay as paragraph breaks.
         */
        internal fun tidy(text: String): String = text.replace("\r", "").replace('\u00A0', ' ')
            .split(Regex("\\n\\s*\\n"))
            .map { it.replace(Regex("\\s*\\n\\s*"), " ").replace(Regex(" {2,}"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

        /** Quotes and backslashes would end a phrase in MusicBrainz's search syntax. */
        internal fun lucene(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
