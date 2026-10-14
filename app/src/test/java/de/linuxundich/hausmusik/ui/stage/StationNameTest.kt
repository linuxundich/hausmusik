// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Test

class StationNameTest {
    @Test
    fun `stream details leave the name`() {
        assertEquals("afk M94.5", stationDisplayName("afk M94.5 (MP3 HQ )"))
        assertEquals("SWR 3", stationDisplayName("SWR 3 (128 kbits mp3)"))
        assertEquals("SWR3", stationDisplayName("SWR3 - 96K AAC"))
        assertEquals("Energy Hit Music Only!", stationDisplayName("Energy Hit Music Only!"))
        assertEquals("Radio (Kultur)", stationDisplayName("Radio (Kultur)"))
    }
}
