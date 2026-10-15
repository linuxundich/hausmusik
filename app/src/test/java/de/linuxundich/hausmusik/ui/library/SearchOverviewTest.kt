// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.library

import de.linuxundich.hausmusik.sonos.DidlObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchOverviewTest {
    private fun artist(id: String, t: String) = DidlObject(id, "", true, t, "object.container.person.musicArtist")
    private fun album(id: String, t: String) = DidlObject(id, "", true, t, "object.container.album.musicAlbum", creator = "Metallica")
    private fun track(id: String, t: String) = DidlObject(id, "", false, t, "object.item.audioItem.musicTrack", creator = "Metallica")

    private val sections = listOf(
        SearchSection("svc:246", "bonob", listOf(
            SearchGroup("artists", listOf(artist("b1", "Metallica"))),
            SearchGroup("albums", listOf(album("b2", "Reload"), album("b3", "Metallica"))),
        ), loading = false),
        SearchSection("svc:9", "Spotify", listOf(
            SearchGroup("artists", listOf(artist("s1", "Metallica"), artist("s2", "AC/DC"))),
            SearchGroup("tracks", listOf(track("s3", "Nothing Else Matters"))),
        ), loading = false),
    )

    @Test
    fun `the artist named like the search is the top hit, known in both sources`() {
        val d = arrangeSearch(sections, "metallica")
        assertEquals("b1", d.top!!.item.id)
        assertEquals(listOf("Spotify"), d.top!!.alsoIn)
        // The merged artist is not shown twice; AC/DC is not "metallica", so it goes to the similar ones.
        assertEquals(null, d.byKind.firstOrNull { it.first == HitKind.Artist })
        assertEquals(listOf("s2"), d.byKind.first { it.first == HitKind.Similar }.second.map { it.item.id })
    }

    @Test
    fun `kinds in a fixed order, the sources' order within a kind`() {
        val d = arrangeSearch(sections, "metallica")
        assertEquals(listOf(HitKind.Album, HitKind.Track, HitKind.Similar), d.byKind.map { it.first })
        assertEquals(listOf("b2", "b3"), d.byKind.first { it.first == HitKind.Album }.second.map { it.item.id })
    }

    @Test
    fun `an album named like the search wins when no artist is`() {
        val d = arrangeSearch(sections, "reload")
        assertEquals("b2", d.top!!.item.id)
    }

    @Test
    fun `an album in a service's track results counts as an album`() {
        assertEquals(HitKind.Album, hitKind("tracks", album("x", "Reload")))
        assertEquals(HitKind.Track, hitKind("tracks", track("y", "One")))
    }

    @Test
    fun `a service artist plays its top tracks, else its albums`() {
        fun c(id: String, t: String, cls: String, uri: String?) = DidlObject(id, "", true, t, cls, uri = uri)
        val spotify = listOf(
            c("r", "AC/DC Radio", "object.item.audioItem.audioBroadcast", "x-sonosapi-radio:r"),
            c("t", "Top Tracks", "object.container.playlistContainer", "x-rincon-cpcontainer:t"),
            c("a", "Power Up", "object.container.album.musicAlbum", "x-rincon-cpcontainer:a"),
        )
        val top = de.linuxundich.hausmusik.data.services.pickArtistPlayback(spotify)!!
        assertEquals(de.linuxundich.hausmusik.data.services.ArtistPlayback.Kind.TopTracks, top.kind)
        assertEquals(listOf("t"), top.items.map { it.id })
        val bonob = listOf(
            c("a1", "Kill 'Em All", "object.container.album.musicAlbum", "x-rincon-cpcontainer:a1"),
            c("a2", "Reload", "object.container.album.musicAlbum", "x-rincon-cpcontainer:a2"),
            c("x", "Not playable", "object.container.album.musicAlbum", null),
        )
        assertEquals(listOf("a1", "a2"), de.linuxundich.hausmusik.data.services.pickArtistPlayback(bonob)!!.items.map { it.id })
        assertEquals(null, de.linuxundich.hausmusik.data.services.pickArtistPlayback(emptyList()))
    }
}
