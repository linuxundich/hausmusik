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

/** A Deezer picture for an artist name (lower case); url null means "none found". */
@Entity(tableName = "artist_image")
data class ArtistImage(
    @PrimaryKey val name: String,
    val url: String?,
    val fetchedAt: Long,
)

@Dao
interface ArtistImageDao {
    @Query("SELECT * FROM artist_image WHERE name = :name")
    suspend fun get(name: String): ArtistImage?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(image: ArtistImage)

    @Query("DELETE FROM artist_image")
    suspend fun clear()
}

/**
 * A music service account Hausmusik linked itself (bonob, Spotify, …), per
 * Sonos household. [sn] is the account serial the household uses in its own
 * URIs for this service, once known.
 */
@Entity(tableName = "service_account", primaryKeys = ["household", "serviceId"])
data class ServiceAccount(
    val household: String,
    val serviceId: Int,
    val name: String,
    val deviceId: String,
    val token: String,
    val key: String?,
    val sn: String?,
    val linkedAt: Long,
)

@Dao
interface ServiceAccountDao {
    @Query("SELECT * FROM service_account WHERE household = :household ORDER BY name")
    fun forHousehold(household: String): kotlinx.coroutines.flow.Flow<List<ServiceAccount>>

    @Query("SELECT * FROM service_account WHERE household = :household AND serviceId = :serviceId")
    suspend fun get(household: String, serviceId: Int): ServiceAccount?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(account: ServiceAccount)

    @Query("DELETE FROM service_account WHERE household = :household AND serviceId = :serviceId")
    suspend fun delete(household: String, serviceId: Int)
}

@Database(
    entities = [HistoryEntry::class, CachedList::class, ArtistImage::class, ServiceAccount::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        androidx.room.AutoMigration(from = 1, to = 2),
        androidx.room.AutoMigration(from = 2, to = 3),
        androidx.room.AutoMigration(from = 3, to = 4),
    ],
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun cachedLists(): CachedListDao
    abstract fun artistImages(): ArtistImageDao
    abstract fun serviceAccounts(): ServiceAccountDao

    companion object {
        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build()
    }
}
