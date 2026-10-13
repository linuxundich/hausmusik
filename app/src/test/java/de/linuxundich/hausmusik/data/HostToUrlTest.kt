package de.linuxundich.hausmusik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostToUrlTest {
    @Test
    fun `adds the Sonos port when none is given`() {
        assertEquals("http://192.168.0.59:1400/", HouseholdRepository.hostToUrl("192.168.0.59").toString())
    }

    @Test
    fun `keeps a given port and ignores scheme and slashes`() {
        assertEquals("http://10.0.2.2:1401/", HouseholdRepository.hostToUrl(" http://10.0.2.2:1401/ ").toString())
    }

    @Test
    fun `empty input is no address`() {
        assertNull(HouseholdRepository.hostToUrl("  "))
    }
}
