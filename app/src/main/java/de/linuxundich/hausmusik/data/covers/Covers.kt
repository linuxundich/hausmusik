// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data.covers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.di.AppScope
import de.linuxundich.hausmusik.sonos.SourceKind
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * One request per cover URL at a time: stage, palette, widget and media
 * notification ask for the same picture at once, and an S1 answers one
 * request after another. Later callers wait for the first and get its bytes.
 */
class SingleFlight : Interceptor {
    private class Fetched(val code: Int, val type: okhttp3.MediaType?, val bytes: ByteArray)

    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Fetched>>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") return chain.proceed(request)
        val key = request.url.toString()
        val mine = CompletableFuture<Fetched>()
        val running = inFlight.putIfAbsent(key, mine)
        val fetched = if (running != null) {
            try {
                running.get(45, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            } catch (e: Exception) {
                throw IOException(e)
            }
        } else {
            try {
                chain.proceed(request).use { r -> Fetched(r.code, r.body.contentType(), r.body.bytes()) }.also { mine.complete(it) }
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(key, mine)
            }
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(fetched.code).message("")
            .body(fetched.bytes.toResponseBody(fetched.type)).build()
    }
}

/**
 * Loads the next tracks' covers into the disk cache while the current one
 * plays, so skipping shows the new cover at once.
 */
@Singleton
class CoverPrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val household: HouseholdRepository,
    @AppScope private val scope: CoroutineScope,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            household.state
                .map { h -> h.selected?.let { g -> Triple(g.id, g.nowPlaying?.takeIf { it.kind == SourceKind.Queue }?.queuePosition, g.queueLength) } }
                .distinctUntilChanged()
                .debounce(800)
                .collect { key ->
                    val (_, position, length) = key ?: return@collect
                    if (position == null || length <= position) return@collect
                    runCatching { prefetch(position, minOf(AHEAD, length - position)) }
                }
        }
    }

    private suspend fun prefetch(position: Int, count: Int) {
        // The current cover goes first; the next ones follow quietly.
        delay(1500)
        val c = household.selectedCoordinator() ?: return
        // Queue positions start at 1; the browse index at 0, so `position` is the next track.
        val next = c.browse("Q:0", start = position, count = count).items
        // First ask the services for their own covers (runs in the background), then load.
        next.forEach { household.coverFor(c, it.albumArtUri) }
        delay(2000)
        for (item in next) {
            val url = household.coverFor(c, item.albumArtUri) ?: continue
            context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).build(),
            )
        }
    }

    companion object {
        private const val AHEAD = 3
    }
}

/** Media notification artwork through Coil: same client, cache and single flight as the app. */
@UnstableApi
class CoilBitmapLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val f = SettableFuture.create<Bitmap>()
        scope.launch(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(data, 0, data.size)?.let { f.set(it) } ?: f.setException(IOException("not an image"))
        }
        return f
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val f = SettableFuture.create<Bitmap>()
        scope.launch {
            val r = context.imageLoader.execute(ImageRequest.Builder(context).data(uri.toString()).size(512).allowHardware(false).build())
            (r as? SuccessResult)?.image?.toBitmap()?.let { f.set(it) } ?: f.setException(IOException("no cover"))
        }
        return f
    }
}
