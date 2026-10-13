// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

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
import javax.net.SocketFactory

data class LyricLine(val timeMs: Long, val text: String)

/**
 * Time-synced lines if LRCLIB has them, otherwise plain text. [suspect]: the
 * times probably belong to another version of the song (a live album often
 * carries the studio timing), so the view offers to adjust them.
 */
data class Lyrics(val synced: List<LyricLine>, val plain: String?, val suspect: Boolean = false) {
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
internal data class LrclibTrack(
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
class LyricsRepository(
    private val http: OkHttpClient,
    private val lrclib: HttpUrl,
    /** Debug builds: the fake household instead of lrclib.net (`-e details host:port`). */
    private val debugBase: suspend () -> String? = { null },
) {
    /** LRCLIB is on the internet: unlike the speakers, it takes the default route. */
    @Inject constructor(http: OkHttpClient, settings: Settings) : this(
        http.newBuilder().socketFactory(SocketFactory.getDefault()).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build(),
        "https://lrclib.net/".toHttpUrl(),
        { if (BuildConfig.DEBUG) settings.debugDetailsBase() else null },
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Result<Lyrics?>>()

    suspend fun find(title: String, artist: String?, album: String?, durationMs: Long?): Result<Lyrics?> {
        val key = listOf(title, artist, album, durationMs?.div(1000)).joinToString("|")
        cache[key]?.let { return it }
        val base = debugBase()?.let { "http://$it/".toHttpUrl() } ?: lrclib
        val result = runCatching { withContext(Dispatchers.IO) { lookup(base, title, artist, album, durationMs) } }
        if (result.isSuccess) cache[key] = result
        return result
    }

    private fun lookup(base: HttpUrl, title: String, artist: String?, album: String?, durationMs: Long?): Lyrics? {
        // Exact match first (needs duration); then the search.
        var exact: Lyrics? = null
        if (artist != null && durationMs != null) {
            val url = base.newBuilder().addPathSegments("api/get")
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .apply { if (album != null) addQueryParameter("album_name", album) }
                .addQueryParameter("duration", (durationMs / 1000).toString())
                .build()
            val hit = get(url)?.let { json.decodeFromString<LrclibTrack>(it) }
            if (hit != null) {
                // Times that fit are the best there is; doubtful ones may have a better match in the search.
                exact = hit.toLyrics(durationMs) ?: return null // instrumental
                if (!exact.suspect) return exact
            }
        }
        val search = base.newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", title)
            .apply { if (artist != null) addQueryParameter("artist_name", artist) }
            .build()
        val hits = get(search)?.let { json.decodeFromString<List<LrclibTrack>>(it) }.orEmpty()
            .filter { it.syncedLyrics != null || it.plainLyrics != null }
        val best = hits.minByOrNull { score(it, durationMs) }?.toLyrics(durationMs)
        return when {
            exact == null -> best
            best != null && best.isSynced && !best.suspect -> best
            else -> exact
        }
    }

    private fun LrclibTrack.toLyrics(durationMs: Long?): Lyrics? = toLyrics()?.let { l ->
        l.copy(suspect = suspect(l, durationMs, duration))
    }

    private fun get(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", "Hausmusik/${BuildConfig.VERSION_NAME}").build()
        http.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw java.io.IOException("LRCLIB: HTTP ${r.code}")
            return r.body.string()
        }
    }

    companion object {
        /**
         * Lower is better: how far the entry's length is from the song's, plus a
         * penalty when lines come after the song's end. How long the song goes on
         * after its last line says nothing: guitar solos and outros of over a
         * minute are common ("Hit the Lights": 1:44 after the last line).
         */
        internal fun score(hit: LrclibTrack, durationMs: Long?): Double {
            if (durationMs == null) return 0.0
            val songS = durationMs / 1000.0
            var s = hit.duration?.let { kotlin.math.abs(it - songS) } ?: 30.0
            val last = hit.syncedLyrics?.let(Lyrics::parseLrc)?.lastOrNull { it.text.isNotBlank() }?.timeMs?.div(1000.0)
            if (last != null) {
                if (last > songS + 2) s += 1000.0
            } else {
                s += 20.0 // plain text only: synced lines are worth a few seconds of length
            }
            return s
        }

        /**
         * Times that probably belong to another version (see [Lyrics.suspect]): the
         * entry is of another length, or lines come after the song's end. Times
         * shifted within an entry of the right length cannot be told from the
         * data; the user shifts them by hand.
         */
        internal fun suspect(l: Lyrics, durationMs: Long?, entryS: Double?): Boolean {
            if (!l.isSynced || durationMs == null) return false
            val songS = durationMs / 1000.0
            if (entryS != null && kotlin.math.abs(entryS - songS) > 5) return true
            val last = l.synced.lastOrNull { it.text.isNotBlank() }?.timeMs?.div(1000.0) ?: return false
            return last > songS + 2
        }
    }

    private fun LrclibTrack.toLyrics(): Lyrics? {
        if (instrumental) return null
        val lines = syncedLyrics?.let(Lyrics::parseLrc).orEmpty()
        if (lines.isEmpty() && plainLyrics.isNullOrBlank()) return null
        return Lyrics(lines, plainLyrics)
    }
}
