// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.sonos

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class SoapClientTest {
    @get:Rule val server = MockWebServerRule()

    private fun client() = SonosPlayerClient(server.server.url("/"), SoapClient(OkHttpClient()))

    @Test
    fun `sends a well-formed envelope with ordered arguments`() = runTest {
        server.server.enqueue(MockResponse.Builder().body(Fixtures.load("GetVolume-59.xml")).build())
        val volume = client().volume()
        val request = server.server.takeRequest()
        assertEquals("/MediaRenderer/RenderingControl/Control", request.url.encodedPath)
        assertEquals("\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"", request.headers["SOAPACTION"])
        val body = request.body!!.utf8()
        assertTrue(body.indexOf("<InstanceID>0</InstanceID>") < body.indexOf("<Channel>Master</Channel>"))
        assertTrue(volume in 0..100)
    }

    @Test
    fun `escapes argument values`() = runTest {
        server.server.enqueue(MockResponse.Builder().body("""<s:Envelope xmlns:s="x"><s:Body><u:SetAVTransportURIResponse/></s:Body></s:Envelope>""").build())
        client().setUri("x-rincon-mp3radio://a?b=1&c=2", Didl.minimal("R:0/0/1", "R:0/0", "A & B", "object.item.audioItem.audioBroadcast"))
        val body = server.server.takeRequest().body!!.utf8()
        assertTrue(body.contains("b=1&amp;c=2"))
        assertTrue(body.contains("&lt;dc:title&gt;A &amp;amp; B&lt;/dc:title&gt;"))
    }

    @Test
    fun `soap fault carries the UPnP error code`() = runTest {
        server.server.enqueue(
            MockResponse.Builder().code(500).body(
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
            ).build(),
        )
        try {
            client().next()
            fail("expected a fault")
        } catch (e: SoapFault) {
            assertEquals(701, e.upnpErrorCode)
        }
    }

    @Test
    fun `an empty page is asked again once, then the list counts as incomplete`() = runTest {
        val page = Fixtures.load("Browse-albums.xml") // 3 items, TotalMatches 1061
        val empty = page.replace(Regex("<Result>.*</Result>"), "<Result></Result>")
        server.server.enqueue(MockResponse.Builder().body(page).build())
        server.server.enqueue(MockResponse.Builder().body(empty).build())
        server.server.enqueue(MockResponse.Builder().body(empty).build())
        val listing = client().browseComplete("A:ALBUM", pageSize = 3)
        assertEquals(3, listing.items.size)
        assertEquals(false, listing.complete)
        assertEquals(3, server.server.requestCount)
    }
}
