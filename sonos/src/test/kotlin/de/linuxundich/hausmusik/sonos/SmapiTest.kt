// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.sonos

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmapiTest {
    private val bonob = MusicService(246, "bonob", "http://nas:4534/ws/sonos", null, "AppLink", 5, null)

    @Test
    fun `service list keeps MService entries with their policy`() {
        val xml = """<Services><Service Id="246" Name="bonob" Uri="http://nas:4534/ws/sonos" SecureUri="http://nas:4534/ws/sonos" ContainerType="MService">
            <Policy Auth="AppLink" PollInterval="1200"/><Presentation><PresentationMap Uri="http://nas:4534/sonos/presentationMap.xml"/></Presentation></Service>
            <Service Id="303" Name="Sonos Radio" Uri="x" ContainerType="SoundLab"><Policy Auth="Anonymous"/></Service></Services>"""
        val list = MusicService.parseList(xml)
        assertEquals(1, list.size)
        assertEquals(62983, list[0].type)
        assertTrue(list[0].linkable)
        assertEquals("http://nas:4534/sonos/presentationMap.xml", list[0].presentationMapUri)
    }

    @Test
    fun `ids are escaped the way Sonos writes them`() {
        assertEquals("track%3a7d9e.mp3", SmapiClient.escapeId("track:7d9e.mp3"))
        assertEquals("album%3aBj%c3%b6rk%20x", SmapiClient.escapeId("album:Björk x"))
    }

    @Test
    fun `account serial comes from the household's own URIs`() {
        val uris = sequenceOf(null, "x-sonos-http:a?sid=9&flags=1&sn=3", "x-sonos-http:track%3a1.mp3?sid=246&flags=8224&sn=7")
        assertEquals("7", SmapiClient.accountSerial(246, uris))
        assertEquals("3", SmapiClient.accountSerial(9, uris))
        assertEquals(null, SmapiClient.accountSerial(2, uris))
    }

    @Test
    fun `tracks play directly by URI, albums as a container`() {
        val track = SmapiItem("track:1", "track", "Ice Floe", "Tina Tux", mimeType = "audio/flac")
        val d = SmapiClient.toDidl(bonob, "7", track, "svc:246:track:1")
        assertEquals("x-sonos-http:track%3a1.flac?sid=246&flags=8224&sn=7", d.uri)
        assertFalse(d.isContainer)
        val album = SmapiClient.toDidl(bonob, "7", SmapiItem("album:4", "album", "Pinguinzoo", canPlay = true, canEnumerate = true), "svc:246:album:4")
        assertEquals("x-rincon-cpcontainer:1004206calbum%3a4", album.uri)
        assertTrue(album.isContainer)
        assertTrue(album.resMd!!.contains("""<item id="1004206calbum%3a4""""))
        val folder = SmapiClient.toDidl(bonob, "7", SmapiItem("albums", "albumList", "Albums", canEnumerate = true), "svc:246:albums")
        assertEquals(null, folder.uri)
    }

    @Test
    fun `a refreshed token is stored and the call repeated`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(500).body("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
                <faultcode>s:Client.TokenRefreshRequired</faultcode><faultstring>TokenRefreshRequired</faultstring>
                <detail><ns:refreshAuthTokenResult xmlns:ns="http://www.sonos.com/Services/1.1"><ns:authToken>new</ns:authToken><ns:privateKey>k2</ns:privateKey></ns:refreshAuthTokenResult></detail>
                </s:Fault></s:Body></s:Envelope>""").build())
            server.enqueue(MockResponse.Builder().body("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><getMetadataResponse xmlns="http://www.sonos.com/Services/1.1">
                <getMetadataResult><index>0</index><count>1</count><total>1</total><mediaCollection><id>albums</id><itemType>albumList</itemType><title>Albums</title></mediaCollection></getMetadataResult>
                </getMetadataResponse></s:Body></s:Envelope>""").build())
            server.start()
            var stored: SmapiCredentials? = null
            val c = SmapiClient(OkHttpClient(), bonob.copy(uri = server.url("/ws").toString()), SmapiCredentials("dev", "hh", "old", "k1")) { stored = it }
            assertEquals(listOf("Albums"), c.metadata("root").items.map { it.title })
            assertEquals("new", stored?.token)
            assertEquals("k2", stored?.key)
            server.takeRequest()
            assertTrue(server.takeRequest().body!!.utf8().contains("<token>new</token>"))
        }
    }

    @Test
    fun `an expired login is reported as unauthorized`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(500).body("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
                <faultcode>s:Client.LoginUnauthorized</faultcode><faultstring>no</faultstring></s:Fault></s:Body></s:Envelope>""").build())
            server.start()
            val c = SmapiClient(OkHttpClient(), bonob.copy(uri = server.url("/ws").toString()), SmapiCredentials("dev", "hh", "old"))
            val fault = runCatching { c.metadata("root") }.exceptionOrNull() as SmapiFault
            assertTrue(fault.unauthorized)
        }
    }

    @Test
    fun `Spotify's not-yet-linked answer keeps the link waiting`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(500).body("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
                <faultcode>s:Server</faultcode><faultstring>NOT_LINKED_RETRY</faultstring>
                <detail><SonosError>5</SonosError></detail></s:Fault></s:Body></s:Envelope>""").build())
            server.start()
            val c = SmapiClient(OkHttpClient(), bonob.copy(uri = server.url("/ws").toString()), SmapiCredentials("dev", "hh"))
            assertEquals(null, c.finishLink(SmapiLink("https://x", "code", false, null)))
        }
    }

    @Test
    fun `a speaker cover path leads back to the service item`() {
        val cover = "/getaa?s=1&u=x-sonos-http%3atrack%253a1WYwAsuWKRKKrw9AV6w9KY.mp3%3fsid%3d246%26flags%3d8224%26sn%3d7"
        val uri = SmapiClient.uriOfCover(cover)
        assertEquals("x-sonos-http:track%3a1WYwAsuWKRKKrw9AV6w9KY.mp3?sid=246&flags=8224&sn=7", uri)
        assertEquals(246 to "track:1WYwAsuWKRKKrw9AV6w9KY", SmapiClient.itemOf(uri))
        assertEquals(null, SmapiClient.itemOf("x-file-cifs://nas/music/a.mp3"))
        assertEquals(null, SmapiClient.uriOfCover("http://cdn.example/a.jpg"))
    }

    @Test
    fun `search categories keep their name and the ID to search with`() {
        val map = """<Presentation><PresentationMap type="Search"><Match><SearchCategories>
            <Category id="artists" mappedId="search:artist"/><Category id="albums"/><CustomCategory stringId="Playlists" mappedId="search:playlist"/>
            </SearchCategories></Match></PresentationMap></Presentation>"""
        assertEquals(
            listOf(SearchCategory("artists", "search:artist"), SearchCategory("albums", "albums"), SearchCategory("Playlists", "search:playlist")),
            SmapiClient.parseSearchCategories(map),
        )
        assertEquals(
            "https://example.org/pm.xml",
            SmapiClient.presentationMapFromManifest("""{"schemaVersion":"1.0","presentationMap":{"version":3,"uri":"https://example.org/pm.xml"},"strings":{"uri":"x"}}"""),
        )
    }
}
