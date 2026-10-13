package de.linuxundich.hausmusik.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {

    @Test
    fun `topology of an S1 household with all rooms in one group`() {
        val state = Fixtures.result("GetZoneGroupState", "GetZoneGroupState.xml").getValue("ZoneGroupState")
        val groups = Topology.parse(state)
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals("Arbeitszimmer", g.coordinator.roomName)
        assertEquals(listOf("Arbeitszimmer", "Bad", "Schlafzimmer", "Wohnzimmer"), g.members.map { it.roomName })
        assertEquals("Arbeitszimmer + 3", g.displayName)
        assertEquals(1, g.coordinator.softwareGeneration)
        assertEquals(1400, g.coordinator.baseUrl.port)
        assertEquals("/", g.coordinator.baseUrl.encodedPath)
    }

    @Test
    fun `device description`() {
        val d = DeviceDescription.parse(Fixtures.load("device_description-101.xml"))
        assertEquals("Wohnzimmer", d.roomName)
        assertEquals("ZP120", d.modelNumber)
        assertTrue(d.uuid.startsWith("RINCON_"))
    }

    @Test
    fun `position info of a streaming track`() {
        val r = Fixtures.result("GetPositionInfo", "GetPositionInfo-59.xml")
        val track = Didl.parse(r["TrackMetaData"]).single()
        assertEquals("Afraid No More", track.title)
        assertEquals("Bukahara", track.creator)
        assertEquals("Canaries in a Coal Mine", track.album)
        assertEquals(213_000L, parseDuration(r["TrackDuration"]))
        assertTrue(track.albumArtUri!!.startsWith("/getaa?"))
    }

    @Test
    fun `now playing from the queue`() {
        val p = Fixtures.result("GetPositionInfo", "GetPositionInfo-59.xml")
        val m = Fixtures.result("GetMediaInfo", "GetMediaInfo-59.xml")
        val now = NowPlaying.from(
            PositionInfo(p["Track"]!!.toInt(), parseDuration(p["TrackDuration"]), parseDuration(p["RelTime"]), p["TrackURI"], Didl.parse(p["TrackMetaData"]).firstOrNull()),
            MediaInfo(m["NrTracks"]!!.toInt(), m["CurrentURI"], Didl.parse(m["CurrentURIMetaData"]).firstOrNull()),
        )!!
        assertEquals(SourceKind.Queue, now.kind)
        assertEquals("Afraid No More", now.title)
        assertEquals(1, now.queuePosition)
    }

    @Test
    fun `group member reports its coordinator`() {
        val m = Fixtures.result("GetMediaInfo", "GetMediaInfo-101.xml")
        assertEquals(SourceKind.GroupMember, SourceKind.of("", m["CurrentURI"].orEmpty()))
    }

    @Test
    fun `transport info, actions and play mode`() {
        assertEquals(TransportState.Playing, TransportState.parse(Fixtures.result("GetTransportInfo", "GetTransportInfo-101.xml")["CurrentTransportState"]))
        val actions = TransportActions.parse(Fixtures.result("GetCurrentTransportActions", "GetCurrentTransportActions-59.xml")["Actions"])
        assertTrue(actions.canNext)
        assertTrue(actions.canSeek)
        assertFalse(actions.canPrevious)
        assertEquals(PlayMode.Normal, PlayMode.parse(Fixtures.result("GetTransportSettings", "GetTransportSettings-59.xml")["PlayMode"]))
    }

    @Test
    fun `play mode round trip`() {
        for (v in listOf("NORMAL", "REPEAT_ALL", "REPEAT_ONE", "SHUFFLE_NOREPEAT", "SHUFFLE", "SHUFFLE_REPEAT_ONE")) {
            assertEquals(v, PlayMode.parse(v).sonosValue)
        }
    }

    @Test
    fun `favorites carry resMD and description`() {
        val r = Fixtures.result("Browse", "Browse-favorites.xml")
        val favs = Didl.parse(r["Result"])
        assertEquals(3, favs.size)
        assertEquals(38, r["TotalMatches"]!!.toInt())
        val first = favs.first()
        assertEquals("Alles Zu Seiner Zeit", first.title)
        assertEquals("ALBUM", first.description)
        assertNotNull(first.resMd)
        assertTrue(first.resMd!!.startsWith("<DIDL-Lite"))
    }

    @Test
    fun `albums are containers with album art`() {
        val albums = Didl.parse(Fixtures.result("Browse", "Browse-albums.xml")["Result"])
        assertTrue(albums.all { it.isContainer })
        assertEquals("Molotov", albums.first().creator)
        assertTrue(albums.first().albumArtUri!!.contains("x-file-cifs"))
    }

    @Test
    fun `radio stations`() {
        val radio = Didl.parse(Fixtures.result("Browse", "Browse-radio.xml")["Result"])
        assertTrue(radio.all { it.isRadio })
    }

    @Test
    fun `empty alarm list is not an error`() {
        val r = Fixtures.result("ListAlarms", "ListAlarms.xml")
        assertEquals("", r["CurrentAlarmList"]!!.trim().removePrefix("<Alarms>").removeSuffix("</Alarms>"))
    }

    @Test
    fun `stream content split`() {
        assertEquals("Bukahara" to "Afraid No More", NowPlaying.splitStreamContent("Bukahara - Afraid No More"))
        assertEquals(null to "Nachrichten", NowPlaying.splitStreamContent("Nachrichten"))
        assertEquals(null to null, NowPlaying.splitStreamContent("ZPSTR_CONNECTING"))
    }

    @Test
    fun `durations`() {
        assertEquals(3_723_500L, parseDuration("1:02:03.500"))
        assertNull(parseDuration("NOT_IMPLEMENTED"))
        assertEquals("0:03:33", formatDuration(213_000))
    }

    @Test
    fun `ssdp location`() {
        val reply = "HTTP/1.1 200 OK\r\nLOCATION: http://10.0.0.11:1400/xml/device_description.xml\r\nSERVER: Linux UPnP/1.0 Sonos/57.23\r\n\r\n"
        assertEquals("http://10.0.0.11:1400/", SsdpDiscovery.parseLocation(reply).toString())
    }
}
