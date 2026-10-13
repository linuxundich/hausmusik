// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Finds Sonos players with an SSDP M-SEARCH. Players answer by unicast, so a
 * plain DatagramSocket is enough. On Android the caller must hold a
 * WifiManager.MulticastLock while this runs, or the search may get no answer.
 */
object SsdpDiscovery {
    private const val ST = "urn:schemas-upnp-org:device:ZonePlayer:1"
    private val group: InetAddress by lazy { InetAddress.getByName("239.255.255.250") }

    /** Returns the base URLs (`http://ip:1400/`) of every player that answered. */
    suspend fun search(
        timeoutMs: Int = 2500,
        repeats: Int = 2,
        /** Lets Android bind the socket to the Wi-Fi network. */
        configure: (DatagramSocket) -> Unit = {},
    ): Set<HttpUrl> = withContext(Dispatchers.IO) {
        val message = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: $ST\r\n\r\n"
            ).toByteArray()
        val found = linkedSetOf<HttpUrl>()
        DatagramSocket().use { socket ->
            configure(socket)
            socket.soTimeout = 300
            repeat(repeats) { socket.send(DatagramPacket(message, message.size, group, 1900)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parseLocation(String(packet.data, 0, packet.length))?.let { found += it }
            }
        }
        found
    }

    internal fun parseLocation(response: String): HttpUrl? {
        if (!response.contains("Sonos", ignoreCase = true) && !response.contains("ZonePlayer")) return null
        val line = response.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) } ?: return null
        return line.substringAfter(':').trim().toHttpUrlOrNull()?.resolve("/")
    }
}
