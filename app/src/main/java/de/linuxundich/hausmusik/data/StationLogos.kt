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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class RadioBrowserStation(val name: String = "", val favicon: String = "", val url: String = "", val votes: Int = 0)

@Serializable
private data class CachedLogo(val savedAt: Long, val url: String? = null)

/**
 * Logos of radio stations: Sonos has none for stations saved by their stream
 * address. radio-browser.info, a free community directory, knows many by that
 * address. Only used when switched on: the stream address and the station name
 * go to radio-browser.info. Answers stay on the phone (a month, a week for none).
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
        this(http, settings, repo, scope, "https://de1.api.radio-browser.info/".toHttpUrl(), File(context.cacheDir, "station-logos"))

    private val http = http.newBuilder().socketFactory(SocketFactory.getDefault())
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, String>() // stream -> logo, "" for none
    private val asking = ConcurrentHashMap.newKeySet<String>()

    init {
        // The household asks while it reads a station; a miss is looked up in the background.
        repo.stationLogo = ::logoFor
    }

    /** The logo if known now; otherwise null, and the household is read again once it is found. */
    private fun logoFor(streamUri: String, name: String?): String? {
        val key = streamKey(streamUri) ?: return null
        memory[key]?.let { return it.ifEmpty { null } }
        if (asking.add(key)) scope.launch {
            try {
                if (!settings.stationLogosEnabled.first()) return@launch
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
        return pick(byUrl + byName, name)
    }

    private fun get(url: HttpUrl): List<RadioBrowserStation> {
        val req = Request.Builder().url(url).header("User-Agent", "Hausmusik/${BuildConfig.VERSION_NAME}").build()
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
