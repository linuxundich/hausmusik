// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

import java.io.BufferedInputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The state variables of an event body (`e:propertyset`), values unescaped.
 * Most events only serve as triggers; ContentDirectory's carry the list
 * versions Hausmusik uses for its cache.
 */
fun parseEventProperties(body: String): Map<String, String> = runCatching {
    Xml.parse(body).findAll("property").flatMap { it.children() }.associate { it.localName0 to it.textContent }
}.getOrDefault(emptyMap())

/** One NOTIFY a player sent. The body is kept raw; callers mostly use it as a trigger. */
data class GenaNotify(val sid: String, val seq: Long?, val body: String)

/**
 * A tiny HTTP server that receives UPnP event notifications (NOTIFY).
 * Sonos sends small requests with Content-Length, one per connection.
 */
class GenaServer(private val scope: CoroutineScope) {
    private val _events = MutableSharedFlow<GenaNotify>(extraBufferCapacity = 64)
    val events: SharedFlow<GenaNotify> = _events

    private var socket: ServerSocket? = null
    private var job: Job? = null

    /** Port the server listens on, or null if it is not running. */
    val port: Int? get() = socket?.localPort

    /** Starts listening on [port] (0 = any free port) and returns the actual port. */
    fun start(port: Int = 0): Int {
        socket?.let { return it.localPort }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        socket = server
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try { server.accept() } catch (_: IOException) { break }
                launch { handle(client) }
            }
        }
        return server.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
        job = null
    }

    private suspend fun handle(client: Socket) = withContext(Dispatchers.IO) {
        client.use { s ->
            s.soTimeout = 5000
            val input = BufferedInputStream(s.getInputStream())
            val request = runCatching { readRequest(input) }.getOrNull()
            val ok = request != null && request.method == "NOTIFY" && request.headers["sid"] != null
            val status = if (ok) "200 OK" else "400 Bad Request"
            s.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            if (ok) {
                _events.emit(GenaNotify(request!!.headers.getValue("sid"), request.headers["seq"]?.toLongOrNull(), request.body))
            }
        }
    }

    internal data class RawRequest(val method: String, val headers: Map<String, String>, val body: String)

    internal companion object {
        fun readRequest(input: BufferedInputStream): RawRequest {
            fun line(): String {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0 || c == '\n'.code) break
                    if (c != '\r'.code) sb.append(c.toChar())
                }
                return sb.toString()
            }
            val first = line()
            val method = first.substringBefore(' ')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val l = line()
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            return RawRequest(method, headers, String(body, 0, read, Charsets.UTF_8))
        }
    }
}

/** An active subscription: renew it before [timeoutSeconds] run out. */
data class GenaSubscription(val sid: String, val timeoutSeconds: Int)

/** SUBSCRIBE, renew and UNSUBSCRIBE against a player's event URLs. */
class GenaClient(private val http: OkHttpClient) {

    suspend fun subscribe(baseUrl: HttpUrl, service: SonosService, callbackUrl: String, timeoutSeconds: Int = 1800): GenaSubscription =
        exchange(
            Request.Builder().url(baseUrl.resolve(service.eventPath)!!)
                .method("SUBSCRIBE", null)
                .header("CALLBACK", "<$callbackUrl>")
                .header("NT", "upnp:event")
                .header("TIMEOUT", "Second-$timeoutSeconds")
                .build(),
            timeoutSeconds,
        )

    suspend fun renew(baseUrl: HttpUrl, service: SonosService, sid: String, timeoutSeconds: Int = 1800): GenaSubscription =
        exchange(
            Request.Builder().url(baseUrl.resolve(service.eventPath)!!)
                .method("SUBSCRIBE", null)
                .header("SID", sid)
                .header("TIMEOUT", "Second-$timeoutSeconds")
                .build(),
            timeoutSeconds,
        )

    suspend fun unsubscribe(baseUrl: HttpUrl, service: SonosService, sid: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(baseUrl.resolve(service.eventPath)!!).method("UNSUBSCRIBE", null).header("SID", sid).build())
                    .await().close()
            }
        }
    }

    private suspend fun exchange(request: Request, requested: Int): GenaSubscription = withContext(Dispatchers.IO) {
        http.newCall(request).await().use { r ->
            if (!r.isSuccessful) throw IOException("${request.method} ${request.url.encodedPath}: HTTP ${r.code}")
            val sid = r.header("SID") ?: throw IOException("no SID")
            val timeout = r.header("TIMEOUT")?.substringAfter("Second-")?.toIntOrNull() ?: requested
            GenaSubscription(sid, timeout)
        }
    }

    companion object {
        /**
         * The local address the phone uses to reach [speaker]. A connected UDP
         * socket sends nothing but tells which interface the route goes through.
         */
        fun localAddressFor(speaker: HttpUrl): InetAddress? = runCatching {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName(speaker.host), speaker.port)
                s.localAddress.takeUnless { it.isAnyLocalAddress }
            }
        }.getOrNull()
    }
}
