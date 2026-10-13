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

    override fun onCreate() {
        super.onCreate()
        widgetUpdater.start()
        history.start()
    }

    /**
     * Covers come from the speakers, so Coil uses the app's HTTP client (bound
     * to the home network), with more patience: for a Spotify track an S1
     * fetches the picture from the internet first, about 4 s, and handles
     * requests one after another. Stage, palette, widget and notification ask
     * at the same time, so the 8 s of the control client ran out. Two requests
     * per speaker at once, and a disk cache so each cover is fetched once.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val covers = http.newBuilder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 2 })
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
