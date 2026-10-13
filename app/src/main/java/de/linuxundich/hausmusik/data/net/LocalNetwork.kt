// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * The Wi-Fi (or Ethernet) network the speakers live in. Android may route
 * traffic over mobile data even while Wi-Fi is connected (a Pixel does when it
 * rates the mobile network as better), and then 192.168.x.y is unreachable.
 * Everything that talks to the speakers is therefore bound to this network.
 */
@Singleton
class LocalNetwork @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val networks = ConcurrentHashMap.newKeySet<Network>()

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            cm?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { networks += network }
                override fun onLost(network: Network) { networks -= network }
            })
        }
    }

    /** The local network, or null on mobile data only. */
    @Suppress("DEPRECATION") // allNetworks covers the moment before the callback has fired
    val current: Network?
        get() = networks.firstOrNull() ?: cm?.allNetworks?.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }

    /** Our IPv4 address in the local network, for the event callbacks. */
    fun address(): InetAddress? = current?.let { n ->
        cm?.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }

    /** Sockets on the local network; falls back to the default route without one (e.g. VPN). */
    val socketFactory: SocketFactory = object : SocketFactory() {
        private fun f(): SocketFactory = current?.socketFactory ?: getDefault()
        override fun createSocket(): Socket = f().createSocket()
        override fun createSocket(host: String?, port: Int): Socket = f().createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            f().createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress?, port: Int): Socket = f().createSocket(host, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            f().createSocket(address, port, localAddress, localPort)
    }
}
