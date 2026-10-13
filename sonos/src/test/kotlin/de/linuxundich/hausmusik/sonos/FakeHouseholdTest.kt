// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.sonos

import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The client against tools/fake-sonos/fake_sonos.py: a second, independent
 * implementation of the protocol. Each test gets a fresh household.
 */
class FakeHouseholdTest {
    private lateinit var process: Process
    private val soap = SoapClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
    private val base = 1541

    private fun url(i: Int): HttpUrl = "http://127.0.0.1:${base + i}/".toHttpUrl()
    private val wohnzimmer get() = SonosPlayerClient(url(0), soap)
    private val kueche get() = SonosPlayerClient(url(1), soap)
    private val arbeitszimmer get() = SonosPlayerClient(url(2), soap)
    private val bad get() = SonosPlayerClient(url(3), soap)

    @Before
    fun start() {
        val script = System.getProperty("fakeSonos")?.let(::File)
        assumeTrue("fake_sonos.py not found", script?.exists() == true)
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }
        assumeTrue("python3 not found", python != null)
        process = ProcessBuilder(python, "-I", script!!.path, "--bind", "127.0.0.1", "--advertise", "127.0.0.1", "--port-base", "$base")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket("127.0.0.1", base + 3).close() }.isSuccess) return
            Thread.sleep(50)
        }
        error("fake household did not start")
    }

    @After
    fun stop() {
        if (::process.isInitialized) {
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `topology lists three groups with Wohnzimmer and Kueche together`() = runBlocking {
        val groups = wohnzimmer.zoneGroups()
        assertEquals(listOf("Arbeitszimmer", "Bad", "Wohnzimmer + Küche"), groups.map { it.displayName })
        assertEquals(base + 1, groups.last().members[1].baseUrl.port)
    }

    @Test
    fun `play and pause`() = runBlocking {
        val c = arbeitszimmer
        assertEquals(TransportState.Paused, c.transportState())
        c.play()
        assertEquals(TransportState.Playing, c.transportState())
        c.pause()
        assertEquals(TransportState.Paused, c.transportState())
    }

    @Test
    fun `radio has no next and reports station and stream`() = runBlocking {
        val c = arbeitszimmer
        val now = NowPlaying.from(c.positionInfo(), c.mediaInfo())!!
        assertEquals(SourceKind.Radio, now.kind)
        assertEquals("Radio Tuxfunk", now.station)
        assertEquals("Uptime", now.title)
        assertEquals("Penny Guin", now.artist)
        assertFalse(c.transportActions().canNext)
        try {
            c.next()
            error("next on radio must fail")
        } catch (e: SoapFault) {
            assertEquals(701, e.upnpErrorCode)
        }
    }

    @Test
    fun `queue track with metadata and absolute cover`() = runBlocking {
        val c = wohnzimmer
        val now = NowPlaying.from(c.positionInfo(), c.mediaInfo())!!
        assertEquals(SourceKind.Queue, now.kind)
        assertEquals("Kernel Panic Ballads", now.album)
        assertEquals(1, now.queuePosition)
        assertEquals("http://127.0.0.1:${base}/getaa?u=album0", c.absoluteArtUrl(now.albumArtUri))
        assertTrue(c.transportActions().canNext)
        c.next()
        assertEquals(2, c.positionInfo().trackNumber)
    }

    @Test
    fun `member follows its coordinator`() = runBlocking {
        val m = kueche.mediaInfo()
        assertEquals(SourceKind.GroupMember, SourceKind.of("", m.currentUri.orEmpty()))
    }

    @Test
    fun `group volume scales members proportionally`() = runBlocking {
        assertEquals(24, wohnzimmer.groupVolume()) // 30 and 18
        wohnzimmer.setGroupVolume(48)
        assertEquals(60, wohnzimmer.volume())
        assertEquals(36, kueche.volume())
    }

    @Test
    fun `join and leave a group`() = runBlocking {
        val wz = wohnzimmer.zoneGroups().first { it.coordinator.roomName == "Wohnzimmer" }.coordinator
        bad.joinGroup(wz.uuid)
        assertEquals("Wohnzimmer + 2", wohnzimmer.zoneGroups().first { it.coordinator.uuid == wz.uuid }.displayName)
        bad.leaveGroup()
        assertTrue(wohnzimmer.zoneGroups().any { it.displayName == "Bad" })
    }

    @Test
    fun `coordinator leaving hands the group over`() = runBlocking {
        wohnzimmer.leaveGroup()
        val names = arbeitszimmer.zoneGroups().map { it.displayName }
        assertTrue(names.toString(), "Küche" in names && "Wohnzimmer" in names)
        assertEquals(TransportState.Playing, kueche.transportState())
    }

    @Test
    fun `queue editing`() = runBlocking {
        val c = wohnzimmer
        fun titles(r: List<DidlObject>) = r.map { it.title }
        val before = titles(c.browseAll("Q:0"))
        assertEquals(5, before.size)
        c.removeTrackFromQueue(3)
        assertEquals(before - before[2], titles(c.browseAll("Q:0")))
        c.reorderQueue(4, 1, 2) // last one right behind the first
        assertEquals(listOf(before[0], before[4], before[1], before[3]), titles(c.browseAll("Q:0")))
        val first = c.addToQueue("x-rincon-playlist:X#A:ALBUM/1", asNext = true)
        assertEquals(2, first)
        assertEquals("Pacman Syncs", c.browseAll("Q:0")[1].title)
        val id = c.saveQueue("Test")
        assertTrue(id.startsWith("SQ:"))
        assertTrue(c.browseAll("SQ:").any { it.title == "Test" })
        c.removeAllTracksFromQueue()
        assertEquals(0, c.browse("Q:0").totalMatches)
    }

    @Test
    fun `moving tracks keeps the playing track`() = runBlocking {
        val c = wohnzimmer
        c.seekTrack(4)
        val playing = c.positionInfo().track!!.title
        val before = c.browseAll("Q:0").map { it.title }
        // Down: track 2 to position 4 means InsertBefore 5 in the old numbering.
        c.reorderQueue(2, 1, 5)
        val after = c.browseAll("Q:0").map { it.title }
        assertEquals(listOf(before[0], before[2], before[3], before[1], before[4]), after)
        assertEquals(playing, c.positionInfo().track!!.title)
        assertEquals(3, c.positionInfo().trackNumber)
        c.removeTrackFromQueue(1)
        assertEquals(playing, c.positionInfo().track!!.title)
    }

    @Test
    fun `replace queue and play an album`() = runBlocking {
        val c = arbeitszimmer
        val uuid = c.zoneGroups().first { it.coordinator.roomName == "Arbeitszimmer" }.coordinator.uuid
        val album = c.browseAll("A:ALBUM").first { it.title == "Hutmacher" }
        c.removeAllTracksFromQueue()
        c.addToQueue(album.uri!!)
        c.useQueue(uuid)
        c.seekTrack(1)
        c.play()
        val now = NowPlaying.from(c.positionInfo(), c.mediaInfo())!!
        assertEquals(SourceKind.Queue, now.kind)
        assertEquals("Red Hat Rag", now.title)
        assertEquals(TransportState.Playing, c.transportState())
    }

    @Test
    fun `search, artists and genres`() = runBlocking {
        val c = wohnzimmer
        val r = c.search("arch")
        assertEquals(listOf("Arch Ibald Trio"), r.artists.map { it.title })
        assertTrue(r.albums.isEmpty())
        val songs = c.search("serenade").tracks
        assertEquals(listOf("Segfault Serenade"), songs.map { it.title })
        assertEquals("Penny Guin", songs.single().creator)
        assertTrue(c.search("nichts dergleichen").isEmpty)
        val artist = c.browseAll("A:ALBUMARTIST").first { it.title == "Tina Tux" }
        assertEquals(listOf("Pinguinzoo"), c.browseAll(artist.id).map { it.title })
        val genres = c.browseAll("A:GENRE")
        assertEquals(listOf("Folk", "Jazz", "Pop", "Rock"), genres.map { it.title })
        assertEquals(2, c.browseAll(genres.first { it.title == "Rock" }.id).size)
        // Queueing an artist adds all their tracks.
        c.removeAllTracksFromQueue()
        c.addToQueue(artist.uri!!)
        assertEquals(5, c.browse("Q:0").totalMatches)
    }

    @Test
    fun `play mode and sleep timer`() = runBlocking {
        val c = wohnzimmer
        c.setPlayMode(PlayMode(shuffle = true, repeat = RepeatMode.All))
        assertEquals(PlayMode(true, RepeatMode.All), c.playMode())
        c.configureSleepTimer(15 * 60_000L)
        val left = c.remainingSleepTimerMs()!!
        assertTrue(left in 14 * 60_000L..15 * 60_000L)
        c.configureSleepTimer(null)
        assertEquals(null, c.remainingSleepTimerMs())
    }

    @Test
    fun `tone settings`() = runBlocking {
        val c = bad
        c.setBass(-4)
        c.setTreble(3)
        c.setLoudness(false)
        assertEquals(-4, c.bass())
        assertEquals(3, c.treble())
        assertFalse(c.loudness())
    }

    @Test
    fun `alarms create update delete`() = runBlocking {
        val alarms = AlarmClient(url(0), soap)
        assertEquals(2, alarms.list().size)
        val id = alarms.create(Alarm(null, 7 * 60 + 15, 30, Recurrence(setOf(1, 3, 5)), true, "RINCON_FAKE000000401400"))
        val created = alarms.list().first { it.id == id }
        assertEquals(setOf(1, 3, 5), created.recurrence.days)
        assertEquals(7 * 60 + 15, created.startMinutes)
        alarms.update(created.copy(enabled = false, volume = 33))
        val updated = alarms.list().first { it.id == id }
        assertFalse(updated.enabled)
        assertEquals(33, updated.volume)
        alarms.delete(id)
        assertEquals(2, alarms.list().size)
    }

    @Test
    fun `library events carry list versions`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = GenaServer(scope)
        val port = server.start()
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000) { server.events.first { "SavedQueuesUpdateID" in parseEventProperties(it.body) } }
            }
            GenaClient(OkHttpClient()).subscribe(url(0), SonosService.ContentDirectory, "http://127.0.0.1:$port/cd")
            val before = parseEventProperties(first.await().body)
            assertEquals("RINCON_FAKE000000101400,1", before["ShareListUpdateID"])
            val next = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000) {
                    server.events.first { parseEventProperties(it.body)["SavedQueuesUpdateID"] != before["SavedQueuesUpdateID"] }
                }
            }
            wohnzimmer.saveQueue("Neu")
            assertEquals("RINCON_FAKE000000101400,2", parseEventProperties(next.await().body)["SavedQueuesUpdateID"])
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `events arrive after a change`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = GenaServer(scope)
        val port = server.start()
        try {
            val sub = GenaClient(OkHttpClient()).subscribe(url(3), SonosService.RenderingControl, "http://127.0.0.1:$port/ev")
            assertNotNull(sub.sid)
            // The initial NOTIFY may already be on its way; wait for one that follows the change.
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000) { server.events.first { it.sid == sub.sid } }
            }
            bad.setVolume(40)
            assertEquals(sub.sid, event.await().sid)
            val renewed = GenaClient(OkHttpClient()).renew(url(3), SonosService.RenderingControl, sub.sid)
            assertEquals(sub.sid, renewed.sid)
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `bonob links, browses, searches and queues an album`() = runBlocking {
        val services = wohnzimmer.musicServices()
        val bonob = services.single { it.name == "bonob" }
        assertEquals(listOf("bonob", "Spotify", "Deezer", "Tunebob"), services.filter { it.linkable }.map { it.name })
        val http = OkHttpClient()
        val smapi = SmapiClient(http, bonob, SmapiCredentials(wohnzimmer.serialNumber(), wohnzimmer.householdId()))

        val link = smapi.startLink()
        assertEquals(null, smapi.finishLink(link))
        http.newCall(okhttp3.Request.Builder().url(link.regUrl).build()).execute().close() // the user confirms in the browser
        val creds = smapi.finishLink(link)!!
        assertEquals("fake-bonob-token", creds.token)

        assertEquals(listOf("Artists", "Albums"), smapi.metadata("root").items.map { it.title })
        val albums = smapi.metadataAll("albums")
        assertEquals(7, albums.total)
        val tracks = smapi.metadata(albums.items[4].id).items
        assertEquals(listOf("Ice Floe", "Waddle", "Fish Supper", "Colony", "Snow Crash"), tracks.map { it.title })
        assertEquals(listOf("Pinguinzoo"), smapi.search("albums", "pinguin").items.map { it.title })
        assertEquals(listOf("artists", "albums", "tracks"), smapi.searchCategories())

        val sn = SmapiClient.accountSerial(bonob.id, wohnzimmer.browseAll("FV:2").asSequence().map { it.uri })
        assertEquals("7", sn)
        val track = SmapiClient.toDidl(bonob, sn!!, tracks[0], "svc")
        assertEquals("x-sonos-http:track%3a17.mp3?sid=246&flags=8224&sn=7", track.uri)
        val album = SmapiClient.toDidl(bonob, sn, albums.items[4], "svc")
        assertTrue(album.resMd!!.contains("SA_RINCON62983_X_#Svc62983-0-Token"))
        val c = arbeitszimmer
        c.removeAllTracksFromQueue()
        c.addToQueue(album.uri!!, album.resMd!!)
        c.addToQueue(track.uri!!, track.resMd!!)
        assertEquals(6, c.browseAll("Q:0").size)
    }

    @Test
    fun `TuneIn browses without sign-in and its stations play directly`() = runBlocking {
        val tunein = wohnzimmer.musicServices().single { it.name == "TuneIn" }
        assertTrue(tunein.anonymous)
        val smapi = SmapiClient(OkHttpClient(), tunein, SmapiCredentials(wohnzimmer.serialNumber(), wohnzimmer.householdId()))
        val stations = smapi.metadata("root").items
        assertEquals(listOf("Radio Tuxfunk", "Kernel FM"), stations.map { it.title })
        val d = SmapiClient.toDidl(tunein, "0", stations[0], "svc")
        assertEquals("x-sonosapi-stream:s1000?sid=254&flags=8224&sn=0", d.uri)
        assertTrue(d.playsDirectly)
        assertTrue(d.resMd!!.contains("F00092020s1000"))
    }
}
