// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data.covers

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class SingleFlightTest {
    @Test
    fun `four callers of one cover cause one request`() = runBlocking {
        MockWebServer().use { server ->
            // Slow like an S1 fetching a Spotify cover.
            server.enqueue(MockResponse.Builder().body("JPEGDATA").headersDelay(400, TimeUnit.MILLISECONDS).build())
            server.start()
            val http = OkHttpClient.Builder().addInterceptor(SingleFlight()).build()
            val url = server.url("/getaa?s=1&u=x")
            val bodies = (1..4).map {
                async(Dispatchers.IO) { http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } }
            }.awaitAll()
            assertEquals(List(4) { "JPEGDATA" }, bodies)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a later request after the first finished fetches again`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("A").build())
            server.enqueue(MockResponse.Builder().body("B").build())
            server.start()
            val http = OkHttpClient.Builder().addInterceptor(SingleFlight()).build()
            val url = server.url("/x")
            assertEquals("A", http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() })
            assertEquals("B", http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() })
        }
    }
}
