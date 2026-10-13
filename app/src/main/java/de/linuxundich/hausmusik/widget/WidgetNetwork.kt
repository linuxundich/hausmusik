// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lets Android wake the widget when a Wi-Fi or Ethernet network comes up,
 * even with the app not running. Such a request fires once and is then
 * released by the system (after 5 s), so it is only made while there is no
 * home network: registered while one exists, it would fire at once. Android
 * only reports arrivals this way; a lost network is seen by the running app,
 * on the next tap or by the widget's periodic update.
 */
object WidgetNetwork {
    private fun request() = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .build()

    // Mutable: the system adds the network to the intent.
    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, NetworkChangeReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Wake the widget when a home network appears. Registering again replaces the earlier request. */
    fun wakeOnLocalNetwork(context: Context) {
        if (hasLocalNetwork(context)) return
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(request(), pending(context)) }
            .onFailure { android.util.Log.w("WidgetNetwork", "cannot watch the network", it) }
    }

    fun unwatch(context: Context) {
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(pending(context)) }
    }

    /** True with a Wi-Fi or Ethernet network, where the speakers can be. */
    fun hasLocalNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION") // allNetworks also sees a Wi-Fi that is not the default network
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
    }
}

/** Redraws the widget when a home network may have appeared. */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                PlayerWidget.refresh(context.applicationContext)
            } finally {
                done.finish()
            }
        }
    }
}
