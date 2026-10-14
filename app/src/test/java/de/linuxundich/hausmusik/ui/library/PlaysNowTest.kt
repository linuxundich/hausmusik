// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.library

import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.NowPlaying
import de.linuxundich.hausmusik.sonos.SourceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaysNowTest {
    private fun now(title: String, artist: String?, album: String?, station: String? = null, kind: SourceKind = SourceKind.Queue) =
        NowPlaying(title, artist, album, station, null, null, null, kind, null)

    private fun item(title: String, cls: String, creator: String? = null, container: Boolean = false, uri: String? = null) =
        DidlObject(id = title, parentId = "", isContainer = container, title = title, upnpClass = cls, creator = creator, uri = uri)

    @Test
    fun `a station by its name`() {
        val swr3 = item("SWR3", "object.item.audioItem.audioBroadcast", uri = "x-rincon-mp3radio://swr3")
        assertTrue(swr3.playsNow(now("Wer wenn nicht wir", null, null, station = "SWR3", kind = SourceKind.Radio)))
        assertFalse(swr3.playsNow(now("x", null, null, station = "Bayern 3", kind = SourceKind.Radio)))
    }

    @Test
    fun `an album by title and artist, a track by title and artist`() {
        val album = item("100th Window", "object.container.album.musicAlbum", "Massive Attack", container = true)
        val track = item("Future Proof", "object.item.audioItem.musicTrack", "Massive Attack")
        val playing = now("Future Proof", "Massive Attack", "100th Window")
        assertTrue(album.playsNow(playing))
        assertTrue(track.playsNow(playing))
        assertFalse(item("Future Proof", "object.item.audioItem.musicTrack", "Someone Else").playsNow(playing))
        assertFalse(track.playsNow(null))
    }

    @Test
    fun `nothing from the library while radio plays`() {
        val track = item("SWR3", "object.item.audioItem.musicTrack")
        assertFalse(track.playsNow(now("SWR3", null, null, station = "SWR3", kind = SourceKind.Radio)))
    }
}
