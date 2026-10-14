// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationLogosTest {
    @Test
    fun `stream key drops the scheme, the query and a trailing slash`() {
        assertEquals("liveradio.swr.de/sw282p3/swr3/play.mp3", StationLogos.streamKey("x-rincon-mp3radio://liveradio.swr.de/sw282p3/swr3/play.mp3"))
        assertEquals("stream.example/live", StationLogos.streamKey("x-rincon-mp3radio://stream.example/live/?ref=sonos"))
        assertNull(StationLogos.streamKey("x-rincon-queue:RINCON_1#0"))
        assertNull(StationLogos.streamKey("x-rincon-stream:RINCON_1"))
    }

    @Test
    fun `the station of the same name wins over another station on the same stream`() {
        val entries = listOf(
            RadioBrowserStation("Radio Salü", "https://salue.de/favicon96.png", votes = 900),
            RadioBrowserStation("SWR3", "https://swr3.de/icon.ico", votes = 20),
            RadioBrowserStation("SWR3", "https://swr3.de/apple-touch-icon.png", votes = 10),
            RadioBrowserStation("SWR3 Visual", "", votes = 5000),
        )
        assertEquals("https://swr3.de/apple-touch-icon.png", StationLogos.pick(entries, "SWR3"))
    }

    @Test
    fun `without a name the most votes win, and nothing without a picture`() {
        assertEquals("https://b/x.png", StationLogos.pick(listOf(RadioBrowserStation("A", "https://a/x.png", votes = 1), RadioBrowserStation("B", "https://b/x.png", votes = 9)), null))
        assertNull(StationLogos.pick(listOf(RadioBrowserStation("A", "", votes = 9)), "A"))
    }

    @Test
    fun `page icons with their sizes, touch icons count as 180`() {
        val html = """<head><link rel="icon" href="/favicon.ico">
            <link rel="apple-touch-icon" href="/touch.png">
            <link rel='icon' type='image/png' sizes='192x192' href='/icon-192.png'>
            <meta name="smartbanner:icon-google" content="/app-icon.webp">
            <link rel="manifest" href="/site.webmanifest"></head>"""
        val icons = StationLogos.pageIcons(html)
        assertEquals(listOf("/touch.png" to 180, "/icon-192.png" to 192, "/app-icon.webp" to 256), icons)
        assertEquals("/site.webmanifest", StationLogos.pageManifest(html))
    }

    @Test
    fun `manifest icons with sizes`() {
        val json = """{"name":"SWR3","icons":[{"src":"\/icons\/a.png","sizes":"48x48"},{"type":"image/png","sizes":"512x512","src":"/icons/b.png"}]}"""
        assertEquals(listOf("/icons/a.png" to 48, "/icons/b.png" to 512), StationLogos.manifestIcons(json))
    }
}
