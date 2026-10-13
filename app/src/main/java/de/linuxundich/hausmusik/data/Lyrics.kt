package de.linuxundich.hausmusik.data

import de.linuxundich.hausmusik.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class LyricLine(val timeMs: Long, val text: String)

/** Time-synced lines if LRCLIB has them, otherwise plain text. */
data class Lyrics(val synced: List<LyricLine>, val plain: String?) {
    val isSynced: Boolean get() = synced.isNotEmpty()

    companion object {
        private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

        /** Parses LRC text. A line may carry several time stamps; metadata tags are skipped. */
        fun parseLrc(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { line ->
            val stamps = stamp.findAll(line).toList()
            if (stamps.isEmpty()) return@flatMap emptySequence()
            val text = line.substring(stamps.last().range.last + 1).trim()
            stamps.asSequence().map { m ->
                val (min, sec, frac) = m.destructured
                val fracMs = when (frac.length) {
                    0 -> 0
                    1 -> frac.toInt() * 100
                    2 -> frac.toInt() * 10
                    else -> frac.take(3).toInt()
                }
                LyricLine((min.toLong() * 60 + sec.toLong()) * 1000 + fracMs, text)
            }
        }.sortedBy { it.timeMs }.toList()
    }
}

@Serializable
private data class LrclibTrack(
    val trackName: String? = null,
    val artistName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * Lyrics from LRCLIB (lrclib.net), a free and open lyrics database. Only
 * used when the user switched it on: title, artist, album and duration of the
 * playing track go to lrclib.net, nothing else.
 */
@Singleton
class LyricsRepository(private val http: OkHttpClient, private val base: HttpUrl) {
    @Inject constructor(http: OkHttpClient) : this(http, "https://lrclib.net/".toHttpUrl())

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Result<Lyrics?>>()

    suspend fun find(title: String, artist: String?, album: String?, durationMs: Long?): Result<Lyrics?> {
        val key = listOf(title, artist, album, durationMs?.div(1000)).joinToString("|")
        cache[key]?.let { return it }
        val result = runCatching { withContext(Dispatchers.IO) { lookup(title, artist, album, durationMs) } }
        if (result.isSuccess) cache[key] = result
        return result
    }

    private fun lookup(title: String, artist: String?, album: String?, durationMs: Long?): Lyrics? {
        // Exact match first (needs duration); then the search, closest duration wins.
        if (artist != null && durationMs != null) {
            val url = base.newBuilder().addPathSegments("api/get")
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .apply { if (album != null) addQueryParameter("album_name", album) }
                .addQueryParameter("duration", (durationMs / 1000).toString())
                .build()
            get(url)?.let { return json.decodeFromString<LrclibTrack>(it).toLyrics() }
        }
        val search = base.newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", title)
            .apply { if (artist != null) addQueryParameter("artist_name", artist) }
            .build()
        val hits = get(search)?.let { json.decodeFromString<List<LrclibTrack>>(it) }.orEmpty()
        val best = hits.filter { it.syncedLyrics != null || it.plainLyrics != null }
            .minByOrNull { h -> if (durationMs == null || h.duration == null) 0.0 else kotlin.math.abs(h.duration - durationMs / 1000.0) }
        return best?.toLyrics()
    }

    private fun get(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", "Hausmusik/${BuildConfig.VERSION_NAME}").build()
        http.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw java.io.IOException("LRCLIB: HTTP ${r.code}")
            return r.body.string()
        }
    }

    private fun LrclibTrack.toLyrics(): Lyrics? {
        if (instrumental) return null
        val lines = syncedLyrics?.let(Lyrics::parseLrc).orEmpty()
        if (lines.isEmpty() && plainLyrics.isNullOrBlank()) return null
        return Lyrics(lines, plainLyrics)
    }
}
