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
}
