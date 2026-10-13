// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import de.linuxundich.hausmusik.data.history.HistoryRecorder
import de.linuxundich.hausmusik.widget.WidgetUpdater
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

@HiltAndroidApp
class HausmusikApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var history: HistoryRecorder
    @Inject lateinit var covers: de.linuxundich.hausmusik.data.covers.CoverPrefetcher
    // Created at start so its cover lookup is in place before the first read.
    @Inject lateinit var services: de.linuxundich.hausmusik.data.services.MusicServices

    override fun onCreate() {
        super.onCreate()
        widgetUpdater.start()
        history.start()
        covers.start()
    }

    /**
     * Covers come from the speakers, so Coil uses the app's HTTP client (bound
     * to the home network), with more patience: for a Spotify track an S1
     * fetches the picture from the internet first, about 4 s, and handles
     * requests one after another. Stage, palette, widget and notification ask
     * at the same time, so the 8 s of the control client ran out. Requests
     * for the same cover share one fetch, three per speaker at once, and a
     * disk cache keeps each cover after the first fetch.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val covers = http.newBuilder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 3 })
            .addInterceptor(de.linuxundich.hausmusik.data.covers.SingleFlight())
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { covers })) }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("covers").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
