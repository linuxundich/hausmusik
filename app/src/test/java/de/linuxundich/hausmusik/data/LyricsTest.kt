package de.linuxundich.hausmusik.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LyricsTest {
    @get:Rule val server = MockWebServerRule()

    private fun repo() = LyricsRepository(OkHttpClient(), server.server.url("/"))

    @Test
    fun `parses LRC with fractions, several stamps and tags`() {
        val lines = Lyrics.parseLrc(
            """
            [ar:Penny Guin]
            [00:12.34] First line
            [00:05.5]Earlier
            [01:02.123][01:30.00] Twice
            no stamp here
            [00:20.00]
            """.trimIndent(),
        )
        assertEquals(listOf(5_500L, 12_340L, 20_000L, 62_123L, 90_000L), lines.map { it.timeMs })
        assertEquals(listOf("Earlier", "First line", "", "Twice", "Twice"), lines.map { it.text })
    }

    @Test
    fun `exact match with synced lyrics`() = runTest {
        server.server.enqueue(
            MockResponse.Builder().body("""{"trackName":"Uptime","duration":196,"instrumental":false,"plainLyrics":"a\nb","syncedLyrics":"[00:01.00] a\n[00:02.00] b"}""").build(),
        )
        val lyrics = repo().find("Uptime", "Penny Guin", "Kernel Panic Ballads", 196_000).getOrThrow()!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf("a", "b"), lyrics.synced.map { it.text })
        val req = server.server.takeRequest()
        assertEquals("/api/get", req.url.encodedPath)
        assertEquals("196", req.url.queryParameter("duration"))
        assertEquals("Penny Guin", req.url.queryParameter("artist_name"))
        assertTrue(req.headers["User-Agent"]!!.startsWith("Hausmusik/"))
    }

    @Test
    fun `falls back to the search and picks the closest length`() = runTest {
        server.server.enqueue(MockResponse.Builder().code(404).body("{}").build())
        server.server.enqueue(
            MockResponse.Builder().body(
                """[{"trackName":"Uptime","duration":300,"plainLyrics":"long"},{"trackName":"Uptime","duration":197,"plainLyrics":"right"}]""",
            ).build(),
        )
        val lyrics = repo().find("Uptime", "Penny Guin", null, 196_000).getOrThrow()!!
        assertEquals("right", lyrics.plain)
        server.server.takeRequest() // api/get, 404
        assertEquals("/api/search", server.server.takeRequest().url.encodedPath)
    }

    @Test
    fun `instrumental and nothing found give null and are cached`() = runTest {
        server.server.enqueue(MockResponse.Builder().body("""{"trackName":"Rawhide","instrumental":true}""").build())
        val r = repo()
        assertNull(r.find("Rawhide", "Fedora Hutmacher", null, 164_000).getOrThrow())
        assertNull(r.find("Rawhide", "Fedora Hutmacher", null, 164_000).getOrThrow())
        assertEquals(1, server.server.requestCount)
    }

    @Test
    fun `server error is a failure, not empty lyrics`() = runTest {
        server.server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(repo().find("Init 0", "Penny Guin", null, 242_000).isFailure)
    }
}
