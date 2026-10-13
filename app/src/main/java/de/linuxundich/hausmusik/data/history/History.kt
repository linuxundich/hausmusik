// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One track or station that played in a room. */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playedAt: Long,
    val room: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val station: String?,
    val artUrl: String?,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntry)

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT :limit")
    fun recent(limit: Int = 300): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 1")
    suspend fun last(): HistoryEntry?

    @Query("DELETE FROM history")
    suspend fun clear()

    /** Keeps the table small: everything beyond the newest [keep] entries goes. */
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 1000)
}

/**
 * A library list as Sonos returned it, with the list version it belongs to.
 * The items are stored as JSON: the lists are only ever read as a whole.
 */
@Entity(tableName = "cached_list", primaryKeys = ["household", "objectId"])
data class CachedList(
    val household: String,
    val objectId: String,
    /** ShareListUpdateID, FavoritesUpdateID … at the time of loading; null if unknown. */
    val version: String?,
    val savedAt: Long,
    val json: String,
)

@Dao
interface CachedListDao {
    @Query("SELECT * FROM cached_list WHERE household = :household AND objectId = :objectId")
    suspend fun get(household: String, objectId: String): CachedList?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(list: CachedList)

    @Query("DELETE FROM cached_list")
    suspend fun clear()
}

@Database(
    entities = [HistoryEntry::class, CachedList::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2)],
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun cachedLists(): CachedListDao

    companion object {
        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build()
    }
}
