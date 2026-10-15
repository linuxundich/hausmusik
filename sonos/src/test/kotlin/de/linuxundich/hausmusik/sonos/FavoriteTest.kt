// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteTest {
    @Test
    fun `a local album becomes a container with the default desc, as the Sonos app stores it`() {
        val album = DidlObject(
            id = "A:ALBUM/Blue", parentId = "A:ALBUM", isContainer = true, title = "Blue",
            upnpClass = "object.container.album.musicAlbum", uri = "x-rincon-playlist:RINCON_1#A:ALBUM/Blue",
        )
        val md = Didl.favoriteMetadata(album, null)
        assertTrue(md, "<container id=\"A:ALBUM/Blue\"" in md)
        assertTrue(md, "<upnp:class>object.container.album.musicAlbum</upnp:class>" in md)
        assertTrue(md, ">RINCON_AssociatedZPUDN</desc>" in md)
    }

    @Test
    fun `a service item keeps its own desc, a now-playing track gets the service's`() {
        val withDesc = DidlObject(id = "x", parentId = "", isContainer = false, title = "T", upnpClass = "object.item.audioItem.musicTrack",
            resMd = "<DIDL-Lite><item><desc id=\"cdudn\">SA_RINCON2311_X_#Svc2311-0-Token</desc></item></DIDL-Lite>")
        assertEquals(withDesc.resMd, Didl.favoriteMetadata(withDesc, "ignored"))
        val track = withDesc.copy(id = "", resMd = null)
        val md = Didl.favoriteMetadata(track, "SA_RINCON2311_X_#Svc2311-0-Token")
        assertTrue(md, "<item id=\"-1\"" in md)
        assertTrue(md, ">SA_RINCON2311_X_#Svc2311-0-Token</desc>" in md)
    }

    @Test
    fun `a station is favored by its stream, a queue track by its track`() {
        val radio = NowPlaying.from(
            PositionInfo(1, null, null, "x-rincon-mp3radio://swr3.example/live", DidlObject("-1", "-1", false, "SWR3", "object.item", streamContent = "A - B")),
            MediaInfo(1, "x-rincon-mp3radio://swr3.example/live", DidlObject("R:0/0/3", "R:0/0", false, "SWR3", "object.item.audioItem.audioBroadcast")),
        )!!
        assertEquals("x-rincon-mp3radio://swr3.example/live", radio.favoriteItem!!.uri)
        assertEquals("SWR3", radio.favoriteItem!!.title)
        val track = NowPlaying.from(
            PositionInfo(2, 200_000, 10_000, "x-file-cifs://nas/a.mp3", DidlObject("Q:0/2", "Q:0", false, "Song", "object.item.audioItem.musicTrack", creator = "X")),
            MediaInfo(9, "x-rincon-queue:RINCON_1#0", null),
        )!!
        assertEquals("x-file-cifs://nas/a.mp3", track.favoriteItem!!.uri)
        assertFalse(track.favoriteItem!!.isContainer)
        val lineIn = NowPlaying.from(PositionInfo(0, null, null, "x-rincon-stream:RINCON_1", null), MediaInfo(0, "x-rincon-stream:RINCON_1", null))
        assertNull(lineIn?.favoriteItem)
    }
}
