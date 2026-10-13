// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryDaoTest {
    private lateinit var db: HistoryDatabase
    private lateinit var dao: HistoryDao

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.history()
    }

    @After
    fun close() = db.close()

    private fun entry(t: Long, title: String) = HistoryEntry(playedAt = t, room = "Bad", title = title, artist = "Tina Tux", album = null, station = null, artUrl = null)

    @Test
    fun `newest first`() = runTest {
        dao.insert(entry(1, "Ice Floe"))
        dao.insert(entry(3, "Colony"))
        dao.insert(entry(2, "Waddle"))
        assertEquals(listOf("Colony", "Waddle", "Ice Floe"), dao.recent().first().map { it.title })
        assertEquals("Colony", dao.last()?.title)
    }

    @Test
    fun `trim keeps the newest and clear removes all`() = runTest {
        (1..10L).forEach { dao.insert(entry(it, "T$it")) }
        dao.trim(keep = 3)
        assertEquals(listOf("T10", "T9", "T8"), dao.recent().first().map { it.title })
        dao.clear()
        assertEquals(0, dao.recent().first().size)
    }
}
