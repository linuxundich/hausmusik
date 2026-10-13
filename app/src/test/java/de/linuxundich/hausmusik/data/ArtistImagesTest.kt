// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.linuxundich.hausmusik.data.history.HistoryDatabase
import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtistImagesTest {
    private val server = MockWebServer()
    private lateinit var db: HistoryDatabase
    private lateinit var settings: Settings
    private lateinit var images: ArtistImages

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        val file = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        settings = Settings(PreferenceDataStoreFactory.create { file })
        images = ArtistImages(OkHttpClient(), db.artistImages(), settings, server.url("/"))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun artist(name: String, pic: String) = DeezerArtist(name, picture_big = pic)

    @Test
    fun `exact name wins over the top hit`() {
        val hits = listOf(artist("Tina Tux Tribute", "https://e/1.jpg"), artist("Tina Tux", "https://e/2.jpg"))
        assertEquals("https://e/2.jpg", ArtistImages.pick("tina tux", hits))
    }

    @Test
    fun `article, accents and ampersand do not matter`() {
        assertEquals("https://e/b.jpg", ArtistImages.pick("Beatles", listOf(artist("The Beatles", "https://e/b.jpg"))))
        assertEquals("https://e/m.jpg", ArtistImages.pick("Motorhead", listOf(artist("Motörhead", "https://e/m.jpg"))))
        assertEquals("https://e/s.jpg", ArtistImages.pick("Simon and Garfunkel", listOf(artist("Simon & Garfunkel", "https://e/s.jpg"))))
    }

    @Test
    fun `an unrelated top hit and the placeholder count as none`() {
        assertNull(ArtistImages.pick("Pinguin Quartett", listOf(artist("Penguin Cafe", "https://e/p.jpg"))))
        assertNull(ArtistImages.pick("Tina Tux", listOf(artist("Tina Tux", "https://e-cdns-images.dzcdn.net/images/artist//500x500.jpg"))))
    }

    @Test
    fun `nothing is asked while switched off`() = runTest {
        assertNull(images.forArtist("Tina Tux"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `asks once, then answers from the database`() = runTest {
        settings.setArtistImagesEnabled(true)
        server.enqueue(MockResponse.Builder().body("""{"data":[{"name":"Tina Tux","picture_big":"https://e/t.jpg"}],"total":1}""").build())
        assertEquals("https://e/t.jpg", images.forArtist("Tina Tux"))
        assertEquals("/search/artist?q=Tina%20Tux&limit=5", server.takeRequest().target)
        val again = ArtistImages(OkHttpClient(), db.artistImages(), settings, server.url("/"))
        assertEquals("https://e/t.jpg", again.forArtist("tina tux "))
        assertNull(images.forArtist("Various Artists"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failed request is not stored`() = runTest {
        settings.setArtistImagesEnabled(true)
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().body("""{"data":[{"name":"Tina Tux","picture_big":"https://e/t.jpg"}]}""").build())
        assertNull(images.forArtist("Tina Tux"))
        assertEquals("https://e/t.jpg", images.forArtist("Tina Tux"))
    }
}
