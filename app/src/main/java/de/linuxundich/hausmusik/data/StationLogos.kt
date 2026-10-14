// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.linuxundich.hausmusik.BuildConfig
import de.linuxundich.hausmusik.di.AppScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class RadioBrowserStation(val name: String = "", val favicon: String = "", val url: String = "", val votes: Int = 0, val homepage: String = "")

@Serializable
private data class CachedLogo(val savedAt: Long, val url: String? = null)

/**
 * Logos of radio stations: Sonos has none for stations saved by their stream
 * address. radio-browser.info, a free community directory, knows most stations
 * by that address, with their homepage, but its pictures are often tiny
 * favicons. So, best first:
 * 1. the logo Wikidata keeps on Wikimedia Commons (with its license there,
 *    often a vector drawing, rendered at 512 px), only for an item whose
 *    official website is the station's homepage;
 * 2. the icons the station publishes on its homepage for apps and bookmarks
 *    (touch icon, web manifest, app banner), at 96 px or more;
 * 3. radio-browser's own picture.
 * Only used when switched on: stream address and station name go to
 * radio-browser.info, the homepage address to wikidata.org, and the homepage
 * itself is read. Answers stay on the phone (a month, a week for none).
 */
@Singleton
class StationLogos(
    http: OkHttpClient,
    private val settings: Settings,
    private val repo: HouseholdRepository,
    private val scope: CoroutineScope,
    private val endpoint: HttpUrl,
    private val cacheDir: File?,
) {
    @Inject constructor(http: OkHttpClient, settings: Settings, repo: HouseholdRepository, @AppScope scope: CoroutineScope, @ApplicationContext context: Context) :
        this(http, settings, repo, scope, "https://de1.api.radio-browser.info/".toHttpUrl(), File(context.cacheDir, "station-logos-2"))

    /** Wikidata's query service; replaced in tests. */
    internal var wikidata: HttpUrl = "https://query.wikidata.org/sparql".toHttpUrl()

    private val http = http.newBuilder().socketFactory(SocketFactory.getDefault())
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, String>() // stream -> logo, "" for none
    private val asking = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var enabled = false

    init {
        // The household asks while it reads a station; a miss is looked up in the background.
        repo.stationLogo = ::logoFor
        // Switched off: no logo at all, not even a stored one; every room is read again either way.
        scope.launch {
            settings.stationLogosEnabled.collect { on ->
                val changed = on != enabled
                enabled = on
                if (changed) repo.refreshAll()
            }
        }
    }

    /** The logo if known now; otherwise null, and the household is read again once it is found. */
    private fun logoFor(streamUri: String, name: String?): String? {
        if (!enabled) return null
        val key = streamKey(streamUri) ?: return null
        memory[key]?.let { return it.ifEmpty { null } }
        if (asking.add(key)) scope.launch {
            try {
                val found = fromDisk(key) ?: runCatching { withContext(Dispatchers.IO) { lookup(key, name) } }
                    .onFailure { Log.w(TAG, "logo lookup failed", it) }
                    .getOrNull()?.let { CachedLogo(System.currentTimeMillis(), it).also { c -> toDisk(key, c) } }
                    ?: return@launch
                memory[key] = found.url.orEmpty()
                if (found.url != null) repo.refreshAll()
            } finally {
                asking.remove(key)
            }
        }
        return null
    }

    /** Returns null for "none" too; the wrapper stores that as a miss. */
    internal fun lookup(stream: String, name: String?): String? {
        val byUrl = listOf("https://", "http://").flatMap { scheme ->
            get(endpoint.newBuilder().addPathSegments("json/stations/byurl").addQueryParameter("url", scheme + stream).build())
        }
        val byName = if (byUrl.none { it.favicon.isNotBlank() } && !name.isNullOrBlank()) {
            get(endpoint.newBuilder().addPathSegments("json/stations/search").addQueryParameter("name", name)
                .addQueryParameter("limit", "20").addQueryParameter("hidebroken", "true").build())
                .filter { ArtistImages.normalize(it.name) == ArtistImages.normalize(name) }
        } else emptyList()
        val entries = byUrl + byName
        // The homepage the directory names for this stream (the same-named station's first).
        val n = name?.let(ArtistImages::normalize)
        val homepage = entries.sortedByDescending { n != null && ArtistImages.normalize(it.name) == n }
            .firstNotNullOfOrNull { it.homepage.takeIf { h -> h.startsWith("http") }?.toHttpUrlOrNull() }
        if (homepage != null) {
            runCatching { commonsLogo(homepage) }.onFailure { Log.w(TAG, "wikidata failed", it) }.getOrNull()?.let { return it }
            runCatching { homepageIcon(homepage) }.onFailure { Log.w(TAG, "homepage failed", it) }.getOrNull()?.let { return it }
        }
        return pick(entries, name)
    }

    /** The logo of the Wikidata item whose official website (P856) is [homepage]'s site, as a 512 px picture. */
    internal fun commonsLogo(homepage: HttpUrl): String? {
        val host = homepage.host.removePrefix("www.")
        val forms = listOf(host, "www.$host").flatMap { h -> listOf("http://$h", "https://$h", "http://$h/", "https://$h/") }
            .joinToString(" ") { "<$it>" }
        val query = "SELECT ?logo WHERE { VALUES ?web { $forms } ?s wdt:P856 ?web; wdt:P154 ?logo. } LIMIT 1"
        val url = wikidata.newBuilder().addQueryParameter("format", "json").addQueryParameter("query", query).build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/sparql-results+json").build())
            .execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("Wikidata: HTTP ${r.code}") else r.body.string() }
        val logo = Regex("\"logo\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return null
        // Special:FilePath renders any picture, an SVG too, at the asked width.
        return logo.replace("http://", "https://") + "?width=512"
    }

    /** The biggest icon the homepage declares for apps and bookmarks, if 96 px or more. */
    internal fun homepageIcon(homepage: HttpUrl): String? {
        val (base, html) = http.newCall(Request.Builder().url(homepage).header("User-Agent", BROWSER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.request.url to r.body.source().let { src -> src.request(MAX_PAGE); src.buffer.clone().readUtf8() }
        }
        val icons = pageIcons(html).toMutableList()
        // A web manifest lists app icons with their sizes.
        pageManifest(html)?.let { m -> base.resolve(m) }?.let { m ->
            runCatching {
                http.newCall(Request.Builder().url(m).header("User-Agent", BROWSER_AGENT).build()).execute().use { r ->
                    if (r.isSuccessful) icons += manifestIcons(r.body.string()).map { (src, size) -> base.resolve(m.toString())!!.resolve(src).toString() to size }
                }
            }
        }
        return icons.filter { it.second >= MIN_ICON }.maxByOrNull { it.second }?.first?.let { base.resolve(it)?.toString() }
    }

    private fun get(url: HttpUrl): List<RadioBrowserStation> {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("radio-browser: HTTP ${r.code}")
            json.decodeFromString<List<RadioBrowserStation>>(r.body.string())
        }
    }

    private suspend fun fromDisk(key: String): CachedLogo? = withContext(Dispatchers.IO) {
        val f = cacheFile(key) ?: return@withContext null
        val c = runCatching { json.decodeFromString<CachedLogo>(f.readText()) }.getOrNull() ?: return@withContext null
        c.takeIf { System.currentTimeMillis() - it.savedAt < if (it.url == null) NONE_MS else FOUND_MS }
    }

    private suspend fun toDisk(key: String, c: CachedLogo) = withContext(Dispatchers.IO) {
        val f = cacheFile(key) ?: return@withContext
        runCatching { f.parentFile?.mkdirs(); f.writeText(json.encodeToString(CachedLogo.serializer(), c)) }
    }

    private fun cacheFile(key: String): File? = cacheDir?.let { dir ->
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, "$hash.json")
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        memory.clear()
        cacheDir?.deleteRecursively()
    }

    companion object {
        private const val TAG = "StationLogos"
        private const val FOUND_MS = 30L * 24 * 3600 * 1000
        private const val NONE_MS = 7L * 24 * 3600 * 1000

        private const val MIN_ICON = 96
        private const val MAX_PAGE = 512L * 1024
        private val USER_AGENT = "Hausmusik/${BuildConfig.VERSION_NAME} (https://github.com/linuxundich/hausmusik)"
        // Some station sites answer bots with an empty page.
        private const val BROWSER_AGENT = "Mozilla/5.0 (Android; Mobile) Hausmusik"

        private fun attrs(tag: String): Map<String, String> =
            Regex("([a-zA-Z:-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*')").findAll(tag)
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim('"', '\'') }

        /** Largest side of "180x180" or "192x192 512x512"; null without sizes. */
        private fun size(sizes: String?): Int? = sizes?.split(' ')?.mapNotNull { it.lowercase().substringBefore('x').toIntOrNull() }?.maxOrNull()

        /**
         * Icons a page declares, with their size: touch icons (180 px unless
         * said), icons with sizes, app banner and tile images (taken as large).
         */
        internal fun pageIcons(html: String): List<Pair<String, Int>> {
            val out = mutableListOf<Pair<String, Int>>()
            Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val a = attrs(m.value)
                val rel = a["rel"]?.lowercase() ?: return@forEach
                val href = a["href"]?.takeIf { it.isNotBlank() } ?: return@forEach
                when {
                    "apple-touch-icon" in rel -> out += href to (size(a["sizes"]) ?: 180)
                    rel.split(' ').contains("icon") -> size(a["sizes"])?.let { out += href to it }
                }
            }
            Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val a = attrs(m.value)
                val key = (a["name"] ?: a["property"])?.lowercase() ?: return@forEach
                val content = a["content"]?.takeIf { it.isNotBlank() } ?: return@forEach
                if (key.startsWith("smartbanner:icon") || key == "msapplication-tileimage") out += content to 256
            }
            return out
        }

        internal fun pageManifest(html: String): String? =
            Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { attrs(it.value) }
                .firstOrNull { it["rel"]?.lowercase() == "manifest" }?.get("href")

        internal fun manifestIcons(json: String): List<Pair<String, Int>> =
            Regex("\\{[^{}]*\"src\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}").findAll(json).mapNotNull { m ->
                val sizes = Regex("\"sizes\"\\s*:\\s*\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
                size(sizes)?.let { m.groupValues[1].replace("\\/", "/") to it }
            }.toList()

        /** "x-rincon-mp3radio://host/path" → "host/path"; null for anything that is not a plain stream. */
        internal fun streamKey(uri: String): String? {
            val rest = uri.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
            if (!uri.startsWith("x-rincon-mp3radio:") && !uri.startsWith("http") && !uri.startsWith("aac:") && !uri.startsWith("x-sonosapi-hls-static:")) return null
            return rest.substringBefore('?').trimEnd('/')
        }

        /**
         * The best logo among the entries: a station of the same name first (the
         * same stream is sometimes listed under another station), then the most
         * votes; real pictures before .ico files.
         */
        internal fun pick(entries: List<RadioBrowserStation>, name: String?): String? {
            val n = name?.let(ArtistImages::normalize)
            return entries.filter { it.favicon.startsWith("http") }
                .sortedWith(
                    compareByDescending<RadioBrowserStation> { n != null && ArtistImages.normalize(it.name).let { s -> s == n || s.startsWith(n) } }
                        .thenBy { it.favicon.endsWith(".ico", ignoreCase = true) }
                        .thenByDescending { it.votes },
                )
                .firstOrNull()?.favicon
        }
    }
}
