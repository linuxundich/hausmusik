// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcesTest {
    private val available = listOf(Sources.MUSIC, "svc:246", "svc:9", Sources.RADIO)

    @Test
    fun `nothing arranged yet keeps the natural order, all on`() {
        assertEquals(available.map { SourcePref(it, true) }, Sources.arrange(available, emptyList()))
    }

    @Test
    fun `the user's order first, a newly linked service after it, a gone one dropped`() {
        val prefs = listOf(SourcePref("svc:9", true), SourcePref(Sources.RADIO, false), SourcePref("svc:77", true), SourcePref(Sources.MUSIC, true))
        assertEquals(
            listOf(SourcePref("svc:9", true), SourcePref(Sources.RADIO, false), SourcePref(Sources.MUSIC, true), SourcePref("svc:246", true)),
            Sources.arrange(available, prefs),
        )
    }

    @Test
    fun `stored as text, and the old local music switch carries over`() {
        val prefs = listOf(SourcePref("svc:9", true), SourcePref(Sources.MUSIC, false))
        assertEquals(prefs, Sources.decode(Sources.encode(prefs), null))
        assertEquals(listOf(SourcePref(Sources.MUSIC, false)), Sources.decode(null, false))
        assertEquals(emptyList<SourcePref>(), Sources.decode(null, true))
    }
}
