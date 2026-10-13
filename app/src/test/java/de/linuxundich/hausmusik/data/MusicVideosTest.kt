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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusicVideosTest {
    private val server = MockWebServer()
    private lateinit var settings: Settings
    private lateinit var dir: File

    @Before
    fun setUp() {
        server.start()
        val file = File.createTempFile("videos", ".preferences_pb").apply { delete() }
        settings = Settings(PreferenceDataStoreFactory.create { file })
        dir = java.nio.file.Files.createTempDirectory("videos").toFile()
    }

    @After
    fun tearDown() = server.close()

    private fun videos() = MusicVideos(OkHttpClient(), settings, server.url("/sparql"), dir)
    private fun answer(vararg ids: String) = server.enqueue(MockResponse.Builder().body(
        """{"results":{"bindings":[${ids.joinToString(",") { """{"yt":{"type":"literal","value":"$it"}}""" }}]}}""",
    ).build())

    @Test
    fun `nothing is asked while switched off`() = runTest {
        assertTrue(videos().find("Bohemian Rhapsody", "Queen").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `finds the ID and keeps it on disk`() = runTest {
        settings.setMusicVideosEnabled(true)
        answer("fJ9rUzIMcZQ")
        assertEquals("fJ9rUzIMcZQ", videos().find("Bohemian Rhapsody - Remastered 2011", "Queen").getOrThrow())
        val q = server.takeRequest().url.queryParameter("query")!!
        assertTrue(q.contains("\"Bohemian Rhapsody - Remastered 2011\"@en"))
        assertTrue(q.contains("\"Bohemian Rhapsody\"@de"))
        assertTrue(q.contains("\"Queen\"@en"))
        // A new instance answers from the file, also for the plain title.
        assertEquals("fJ9rUzIMcZQ", videos().find("Bohemian Rhapsody", "Queen").getOrThrow())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `none found is an answer, a failure is not kept`() = runTest {
        settings.setMusicVideosEnabled(true)
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(videos().find("Sara", "Provinz").isFailure)
        answer()
        assertNull(videos().find("Sara", "Provinz").getOrThrow())
        assertNull(videos().find("Sara", "Provinz").getOrThrow())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `anything that is not a YouTube ID is ignored`() = runTest {
        settings.setMusicVideosEnabled(true)
        answer("https://evil.example/x")
        assertNull(videos().find("Song", "Band").getOrThrow())
    }

    @Test
    fun `titles lose what streaming services add, not their real brackets`() {
        assertEquals("Bohemian Rhapsody", MusicVideos.cleanTitle("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("Song", MusicVideos.cleanTitle("Song (feat. Someone)"))
        assertEquals("Song", MusicVideos.cleanTitle("Song [Live]"))
        // Still asked as played: the bracket can be the real name.
        val q = MusicVideos.query("Major Tom (Völlig losgelöst)", "Peter Schilling")
        assertTrue(q.contains("\"Major Tom (Völlig losgelöst)\"@de"))
        assertTrue(q.contains("\"Major Tom\"@de"))
    }

    @Test
    fun `quotes cannot break out of the query`() {
        assertTrue(MusicVideos.query("Say \"hi\"", "A\\B").contains("\"Say \\\"hi\\\"\"@en"))
    }

    @Test
    fun `links open at the given second`() {
        assertEquals("https://www.youtube.com/watch?v=fJ9rUzIMcZQ&t=98s", MusicVideos.watchUrl("fJ9rUzIMcZQ", 98))
        assertEquals("https://www.youtube.com/watch?v=fJ9rUzIMcZQ", MusicVideos.watchUrl("fJ9rUzIMcZQ", 0))
        assertEquals("https://www.youtube.com/results?search_query=Provinz+Sara", MusicVideos.searchUrl("Sara (Live)", "Provinz"))
    }
}
