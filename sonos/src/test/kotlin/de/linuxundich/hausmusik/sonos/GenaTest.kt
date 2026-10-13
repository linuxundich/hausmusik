// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.sonos

import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GenaTest {
    @get:Rule val server = MockWebServerRule()

    @Test
    fun `receives a NOTIFY and answers 200`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gena = GenaServer(scope)
        val port = gena.start()
        try {
            val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { gena.events.first() } }
            val body = "<e:propertyset><e:property><LastChange>x</LastChange></e:property></e:propertyset>"
            val reply = Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write(
                    ("NOTIFY /ev HTTP/1.1\r\nHOST: 127.0.0.1\r\nCONTENT-TYPE: text/xml\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\n" +
                        "SID: uuid:RINCON_1_sub0000000001\r\nSEQ: 3\r\nCONTENT-LENGTH: ${body.length}\r\n\r\n$body").toByteArray(),
                )
                s.getInputStream().bufferedReader().readLine()
            }
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            val n = received.await()
            assertEquals("uuid:RINCON_1_sub0000000001", n.sid)
            assertEquals(3L, n.seq)
            assertEquals(body, n.body)
        } finally {
            gena.stop()
            scope.cancel()
        }
    }

    @Test
    fun `parses ContentDirectory event properties`() {
        val body = """<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
            <e:property><SystemUpdateID>685</SystemUpdateID></e:property>
            <e:property><ShareListUpdateID>RINCON_1,2355</ShareListUpdateID></e:property>
            <e:property><FavoritesUpdateID>RINCON_2,300</FavoritesUpdateID></e:property>
            </e:propertyset>"""
        val p = parseEventProperties(body)
        assertEquals("RINCON_1,2355", p["ShareListUpdateID"])
        assertEquals("RINCON_2,300", p["FavoritesUpdateID"])
        assertEquals(emptyMap<String, String>(), parseEventProperties("kein xml"))
    }

    @Test
    fun `subscribe sends callback and reads SID and timeout`() = runBlocking {
        server.server.enqueue(MockResponse.Builder().addHeader("SID", "uuid:sub-1").addHeader("TIMEOUT", "Second-600").build())
        val sub = GenaClient(OkHttpClient()).subscribe(server.server.url("/"), SonosService.AVTransport, "http://10.0.0.5:3400/ev")
        val req = server.server.takeRequest()
        assertEquals("SUBSCRIBE", req.method)
        assertEquals("/MediaRenderer/AVTransport/Event", req.url.encodedPath)
        assertEquals("<http://10.0.0.5:3400/ev>", req.headers["CALLBACK"])
        assertEquals(GenaSubscription("uuid:sub-1", 600), sub)
    }
}
