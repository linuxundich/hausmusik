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

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao

    companion object {
        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build()
    }
}
