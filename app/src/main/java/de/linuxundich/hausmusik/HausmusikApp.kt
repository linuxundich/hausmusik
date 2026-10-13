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

    /** Covers come from the speakers, so Coil shares the app's HTTP client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .build()
}
