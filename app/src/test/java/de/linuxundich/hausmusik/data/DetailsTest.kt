// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DetailsTest {
    private val server = MockWebServer()
    private lateinit var settings: Settings
    private lateinit var details: DetailsRepository

    @Before
    fun setUp() {
        server.start()
        val file = File.createTempFile("details", ".preferences_pb").apply { delete() }
        settings = Settings(PreferenceDataStoreFactory.create { file })
        details = DetailsRepository(
            OkHttpClient(), settings,
            server.url("/ws/2/"), server.url("/w/api.php"), { lang -> server.url("/wiki/$lang/") },
            language = { "de" }, minGapMs = 0, retryMs = 0,
        )
    }

    @After
    fun tearDown() = server.close()

    private fun reply(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private val artistSearch = """{"artists":[
        {"id":"abc-1","name":"Tina Tux Tribute","score":100},
        {"id":"abc-2","name":"Tina Tux","type":"Group","country":"DE","score":98,"disambiguation":"Pinguin-Pop"}]}"""
    private val artistFull = """{"id":"abc-2","name":"Tina Tux","life-span":{"begin":"2010-05-01","ended":false},
        "genres":[{"name":"indie pop","count":3},{"name":"synth-pop","count":9}],
        "relations":[{"type":"official homepage","url":{"resource":"https://e/"}},
                     {"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q42"}}]}"""
    private val sitelinks = """{"entities":{"Q42":{"sitelinks":{"dewiki":{"title":"Tina Tux"},"enwiki":{"title":"Tina Tux (band)"}}}}}"""
    private val wikiSummary = """{"type":"standard","extract":"Tina Tux ist eine Pinguin-Band.","content_urls":{"desktop":{"page":"https://de.wikipedia.org/wiki/Tina_Tux"}},
        "thumbnail":{"source":"https://upload.wikimedia.org/tina.jpg"}}"""

    @Test
    fun `nothing is asked while switched off`() = runTest {
        assertTrue(details.artist("Tina Tux").isFailure)
        assertTrue(details.album("Kernel Panic Ballads", "Tina Tux").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `artist facts and the Wikipedia text in the phone's language`() = runTest {
        settings.setDetailsEnabled(true)
        reply(artistSearch); reply(artistFull); reply(sitelinks); reply(wikiSummary)
        val a = details.artist("tina tux").getOrThrow()!!
        assertEquals("Tina Tux", a.name)
        assertEquals("Group", a.type)
        assertEquals("DE", a.country)
        assertEquals("2010-05-01", a.begin)
        assertNull(a.end)
        assertEquals(listOf("Synth-pop", "Indie pop"), a.genres)
        assertEquals("Tina Tux ist eine Pinguin-Band.", a.summary)
        assertEquals("https://de.wikipedia.org/wiki/Tina_Tux", a.summaryUrl)
        assertEquals("https://musicbrainz.org/artist/abc-2", a.musicBrainzUrl)
        assertEquals("https://upload.wikimedia.org/tina.jpg", a.imageUrl)

        val search = server.takeRequest()
        assertTrue(search.target.startsWith("/ws/2/artist?fmt=json&query=artist%3A%22tina%20tux%22"))
        assertTrue(search.headers["User-Agent"]!!.startsWith("Hausmusik/"))
        assertEquals("/ws/2/artist/abc-2?fmt=json&inc=genres%2Burl-rels", server.takeRequest().target.replace("+", "%2B"))
        assertTrue(server.takeRequest().target.contains("ids=Q42"))
        assertEquals("/wiki/de/Tina%20Tux", server.takeRequest().target)
    }

    @Test
    fun `Wikipedia off means no request to Wikidata or Wikipedia`() = runTest {
        settings.setDetailsEnabled(true)
        settings.setDetailsWikipediaEnabled(false)
        reply(artistSearch); reply(artistFull)
        val a = details.artist("Tina Tux").getOrThrow()!!
        assertNull(a.summary)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `falls back to English when the phone's wiki has no page`() = runTest {
        settings.setDetailsEnabled(true)
        reply(artistSearch); reply(artistFull)
        reply("""{"entities":{"Q42":{"sitelinks":{"enwiki":{"title":"Tina Tux (band)"}}}}}""")
        reply("""{"type":"standard","extract":"Tina Tux is a penguin band.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Tina_Tux_(band)"}}}""")
        assertEquals("Tina Tux is a penguin band.", details.artist("Tina Tux").getOrThrow()!!.summary)
        server.takeRequest(); server.takeRequest(); server.takeRequest()
        assertEquals("/wiki/en/Tina%20Tux%20(band)", server.takeRequest().target)
    }

    @Test
    fun `a failing Wikipedia still leaves the facts`() = runTest {
        settings.setDetailsEnabled(true)
        reply(artistSearch); reply(artistFull); reply(sitelinks)
        server.enqueue(MockResponse.Builder().code(503).build())
        val a = details.artist("Tina Tux").getOrThrow()
        assertNotNull(a)
        assertNull(a!!.summary)
        assertEquals("Group", a.type)
    }

    @Test
    fun `a similar name is not the artist`() = runTest {
        settings.setDetailsEnabled(true)
        reply("""{"artists":[{"id":"x","name":"Tina Tux Tribute","score":100}]}""")
        assertNull(details.artist("Tina Tux").getOrThrow())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `answers are kept, failures are not`() = runTest {
        settings.setDetailsEnabled(true)
        settings.setDetailsWikipediaEnabled(false)
        server.enqueue(MockResponse.Builder().code(500).build())
        assertTrue(details.artist("Tina Tux").isFailure)
        reply(artistSearch); reply(artistFull)
        assertNotNull(details.artist("Tina Tux").getOrThrow())
        assertNotNull(details.artist("The Tina Tux ").getOrThrow())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a busy MusicBrainz is asked once more`() = runTest {
        settings.setDetailsEnabled(true)
        settings.setDetailsWikipediaEnabled(false)
        server.enqueue(MockResponse.Builder().code(503).build())
        reply(artistSearch); reply(artistFull)
        assertNotNull(details.artist("Tina Tux").getOrThrow())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `two busy answers in a row are a failure`() = runTest {
        settings.setDetailsEnabled(true)
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(details.artist("Tina Tux").isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `compilations have no artist page`() = runTest {
        settings.setDetailsEnabled(true)
        assertNull(details.artist("Various Artists").getOrThrow())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `album facts from the release group`() = runTest {
        settings.setDetailsEnabled(true)
        reply("""{"release-groups":[{"id":"rg-1","title":"Kernel Panic Ballads","score":100,"primary-type":"Album","first-release-date":"2021-03-12"}]}""")
        reply("""{"id":"rg-1","title":"Kernel Panic Ballads","artist-credit":[{"name":"Penny Guin"}],
            "tags":[{"name":"folk","count":2}],"relations":[]}""")
        val a = details.album("Kernel Panic Ballads", "Penny Guin").getOrThrow()!!
        assertEquals("Album", a.type)
        assertEquals("2021-03-12", a.firstRelease)
        assertEquals("Penny Guin", a.artist)
        assertEquals(listOf("Folk"), a.genres)
        assertEquals("https://coverartarchive.org/release-group/rg-1/front-250", a.imageUrl)
        assertNull(a.summary)
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().target.contains("query=releasegroup%3A%22Kernel%20Panic%20Ballads%22%20AND%20artist%3A%22Penny%20Guin%22"))
    }

    @Test
    fun `line breaks inside a paragraph become spaces`() {
        assertEquals("Eins zwei. Drei vier.\n\nNeuer Absatz.", DetailsRepository.tidy("Eins\nzwei.\r\n Drei  vier.\n\n\n  \nNeuer Absatz.\n"))
        assertEquals("", DetailsRepository.tidy(" \n \n "))
    }

    @Test
    fun `answers survive on disk, also "nothing found"`() = runTest {
        settings.setDetailsEnabled(true)
        settings.setDetailsWikipediaEnabled(false)
        val dir = java.nio.file.Files.createTempDirectory("details").toFile()
        fun repo() = DetailsRepository(
            OkHttpClient(), settings, server.url("/ws/2/"), server.url("/w/api.php"), { lang -> server.url("/wiki/$lang/") },
            language = { "de" }, minGapMs = 0, retryMs = 0, cacheDir = dir,
        )
        reply(artistSearch); reply(artistFull)
        reply("""{"artists":[]}""")
        assertEquals("Group", repo().artist("Tina Tux").getOrThrow()!!.type)
        assertNull(repo().artist("Nobody Known").getOrThrow())
        assertEquals(3, server.requestCount)
        // A new instance (a new app start) answers from the files.
        val again = repo()
        assertEquals("Group", again.artist("Tina Tux").getOrThrow()!!.type)
        assertNull(again.artist("Nobody Known").getOrThrow())
        assertEquals(3, server.requestCount)
        again.clear()
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `quotes cannot break out of the search phrase`() {
        assertEquals("""say \"hi\" \\o/""", DetailsRepository.lucene("""say "hi" \o/"""))
    }
}
