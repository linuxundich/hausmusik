// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.linuxundich.hausmusik.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** What to do with the speakers when the video opens in the YouTube app. */
enum class VideoMode { Ask, KeepPlaying, Pause }

@Serializable
private data class CachedVideo(val savedAt: Long, val id: String? = null)

/**
 * Finds the official music video of a song: Wikidata keeps YouTube video IDs
 * (property P1651) for many songs. Only used when switched on: title and artist
 * go to query.wikidata.org, nothing else. The video itself plays in the YouTube
 * app (see [watchUrl]); Hausmusik never embeds or downloads it, as YouTube's
 * terms demand. Answers are kept on the phone: a month for an ID, a week for none.
 */
@Singleton
class MusicVideos(
    http: OkHttpClient,
    private val settings: Settings,
    private val endpoint: HttpUrl,
    private val cacheDir: File?,
) {
    @Inject constructor(http: OkHttpClient, settings: Settings, @ApplicationContext context: Context) : this(
        http, settings, "https://query.wikidata.org/sparql".toHttpUrl(), File(context.cacheDir, "videos"),
    )

    // Wikidata is on the internet: unlike the speakers, it takes the default route.
    private val http = http.newBuilder().socketFactory(SocketFactory.getDefault())
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, String>() // key -> ID, "" for none

    /** The YouTube video ID, null if Wikidata knows none. Failure: the network or Wikidata failed. */
    suspend fun find(title: String, artist: String?): Result<String?> {
        if (!settings.musicVideosEnabled.first()) return Result.failure(IllegalStateException("Music videos are switched off"))
        val t = title.trim()
        val a = artist?.trim().orEmpty()
        if (t.isEmpty() || a.isEmpty()) return Result.success(null)
        val key = ArtistImages.normalize(cleanTitle(t)) + "|" + ArtistImages.normalize(a)
        memory[key]?.let { return Result.success(it.ifEmpty { null }) }
        fromDisk(key)?.let { memory[key] = it.id.orEmpty(); return Result.success(it.id) }
        val result = runCatching { withContext(Dispatchers.IO) { lookup(t, a) } }
        result.onSuccess { id ->
            memory[key] = id.orEmpty()
            toDisk(key, CachedVideo(System.currentTimeMillis(), id))
        }.onFailure { Log.w(TAG, "video lookup failed", it) }
        return result
    }

    internal suspend fun lookup(title: String, artist: String): String? {
        val base = (if (BuildConfig.DEBUG) settings.debugDetailsBase() else null)?.let { "http://$it/sparql".toHttpUrl() } ?: endpoint
        val url = base.newBuilder().addQueryParameter("format", "json").addQueryParameter("query", query(title, artist)).build()
        val body = http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Hausmusik/${BuildConfig.VERSION_NAME} (https://github.com/linuxundich/hausmusik)")
                .header("Accept", "application/sparql-results+json").build(),
        ).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("Wikidata: HTTP ${r.code}") else r.body.string() }
        val bindings = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray.orEmpty()
        return bindings.firstNotNullOfOrNull { (it as? JsonObject)?.get("yt")?.jsonObject?.get("value")?.jsonPrimitive?.content }
            ?.takeIf { ID.matches(it) }
    }

    private suspend fun fromDisk(key: String): CachedVideo? = withContext(Dispatchers.IO) {
        val f = cacheFile(key) ?: return@withContext null
        val c = runCatching { json.decodeFromString<CachedVideo>(f.readText()) }.getOrNull() ?: return@withContext null
        c.takeIf { System.currentTimeMillis() - it.savedAt < if (it.id == null) NONE_MS else FOUND_MS }
    }

    private suspend fun toDisk(key: String, c: CachedVideo) = withContext(Dispatchers.IO) {
        val f = cacheFile(key) ?: return@withContext
        runCatching { f.parentFile?.mkdirs(); f.writeText(json.encodeToString(CachedVideo.serializer(), c)) }
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
        private const val TAG = "MusicVideos"
        private const val FOUND_MS = 30L * 24 * 3600 * 1000
        private const val NONE_MS = 7L * 24 * 3600 * 1000
        private val ID = Regex("[A-Za-z0-9_-]{11}")

        /**
         * Songs with a YouTube ID (P1651) whose English or German name is [title]
         * and whose performer (P175) is called [artist]. Exact names first: label
         * lookups are fast; Wikidata names are case-sensitive, so the title is
         * also tried as typed and capitalized.
         */
        internal fun query(title: String, artist: String): String {
            fun lit(s: String, lang: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"@" + lang
            // As played ("Major Tom (Völlig losgelöst)" is the real name) and without additions.
            val titles = listOf(title, cleanTitle(title)).flatMap { listOf(it, it.replaceFirstChar { c -> c.titlecase() }) }.distinct().filter { it.isNotEmpty() }
            val titleValues = titles.flatMap { t -> listOf(lit(t, "en"), lit(t, "de")) }.joinToString(" ")
            val artistValues = listOf(lit(artist, "en"), lit(artist, "de")).joinToString(" ")
            return "SELECT ?yt WHERE { VALUES ?t { $titleValues } VALUES ?a { $artistValues } " +
                "?item rdfs:label ?t; wdt:P1651 ?yt; wdt:P175 ?p. ?p rdfs:label ?a. } LIMIT 3"
        }

        /**
         * Without what streaming services add to a title: "Song - Remastered 2011",
         * "Song (Live)", "Song [feat. X]" all become "Song".
         */
        internal fun cleanTitle(title: String): String = title
            .replace(Regex("\\s+-\\s+.*(remaster|version|edit|live|mix|mono|stereo|single|radio).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]\\s*"), " ")
            .trim()

        /** Opens in the YouTube app if installed, else in the browser, at [startSeconds]. */
        fun watchUrl(id: String, startSeconds: Long): String =
            "https://www.youtube.com/watch?v=$id" + if (startSeconds > 0) "&t=${startSeconds}s" else ""

        fun searchUrl(title: String, artist: String?): String =
            "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(listOfNotNull(artist, cleanTitle(title)).joinToString(" "), "UTF-8")
    }
}
