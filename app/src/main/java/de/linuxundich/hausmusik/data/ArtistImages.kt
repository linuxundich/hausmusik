// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import de.linuxundich.hausmusik.BuildConfig
import de.linuxundich.hausmusik.data.history.ArtistImage
import de.linuxundich.hausmusik.data.history.ArtistImageDao
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class DeezerArtist(val name: String = "", val picture_big: String? = null, val picture_medium: String? = null)

@Serializable
internal data class DeezerSearch(val data: List<DeezerArtist> = emptyList())

/**
 * Artist pictures from Deezer's public API, as many music apps do. Only when
 * the user switched them on: artist names go to api.deezer.com, nothing else.
 * Results (also "none found") are stored, so every name is asked once a month
 * at most.
 */
@Singleton
class ArtistImages(
    private val http: OkHttpClient,
    private val dao: ArtistImageDao,
    private val settings: Settings,
    private val base: HttpUrl,
) {
    @Inject constructor(http: OkHttpClient, dao: ArtistImageDao, settings: Settings) : this(
        // Deezer is on the internet: unlike the speakers, it takes the default route.
        http.newBuilder().socketFactory(SocketFactory.getDefault()).build(),
        dao, settings, "https://api.deezer.com/".toHttpUrl(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, String>() // key -> url, "" for none
    private val gate = Semaphore(3) // Deezer allows about 50 requests in 5 s

    /** The picture URL for [name], or null if switched off or none exists. */
    suspend fun forArtist(name: String): String? {
        if (name.isBlank() || normalize(name) in COLLECTIONS || !settings.artistImagesEnabled.first()) return null
        val key = name.trim().lowercase()
        memory[key]?.let { return it.ifEmpty { null } }
        val stored = dao.get(key)
        if (stored != null && (stored.url != null || System.currentTimeMillis() - stored.fetchedAt < RETRY_MS)) {
            memory[key] = stored.url.orEmpty()
            return stored.url
        }
        val url = gate.withPermit { runCatching { lookup(name.trim()) }.getOrElse { return null } }
        dao.put(ArtistImage(key, url, System.currentTimeMillis()))
        memory[key] = url.orEmpty()
        return url
    }

    internal suspend fun lookup(name: String): String? = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments("search/artist").addQueryParameter("q", name).addQueryParameter("limit", "5").build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", "Hausmusik/${BuildConfig.VERSION_NAME}").build()).execute()
            .use { r -> if (!r.isSuccessful) throw java.io.IOException("Deezer: HTTP ${r.code}") else r.body.string() }
        pick(name, json.decodeFromString<DeezerSearch>(body).data)
    }

    companion object {
        private const val RETRY_MS = 30L * 24 * 3600 * 1000

        /** Compilation "artists" have no face; asking Deezer would return some random band. */
        internal val COLLECTIONS = setOf("various artists", "various", "verschiedene interpreten", "diverse", "va", "unknown artist", "unbekannter interpret", "soundtrack")

        /** Lower case, without a leading "the", accents and punctuation: "The Beatles" = "beatles". */
        internal fun normalize(name: String): String =
            java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace("&", " and ")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
                .removePrefix("the ")

        /**
         * The same name (after [normalize]) first, otherwise the top hit if it at
         * least starts alike; Deezer's grey placeholder counts as none.
         */
        internal fun pick(name: String, artists: List<DeezerArtist>): String? {
            val wanted = normalize(name)
            val best = artists.firstOrNull { normalize(it.name) == wanted }
                ?: artists.firstOrNull()?.takeIf { normalize(it.name).startsWith(wanted) || wanted.startsWith(normalize(it.name)) }
            val picture = best?.picture_big ?: best?.picture_medium ?: return null
            return picture.takeUnless { it.contains("/images/artist//") }
        }
    }
}
