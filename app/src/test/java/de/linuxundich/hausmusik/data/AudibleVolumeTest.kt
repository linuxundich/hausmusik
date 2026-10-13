// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudibleVolumeTest {
    private val volumes = mapOf("wz" to 16, "az" to 18, "bad" to 10)

    @Test
    fun `a muted speaker does not count`() {
        assertEquals(14, audibleVolume(volumes, mapOf("wz" to true)))
    }

    @Test
    fun `none or all muted leaves it to Sonos`() {
        assertNull(audibleVolume(volumes, emptyMap()))
        assertNull(audibleVolume(volumes, mapOf("wz" to false, "az" to false, "bad" to false)))
        assertNull(audibleVolume(volumes, mapOf("wz" to true, "az" to true, "bad" to true)))
    }
}
