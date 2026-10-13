package de.linuxundich.hausmusik.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class Settings @Inject constructor(private val store: DataStore<Preferences>) {
    private val manualHostKey = stringPreferencesKey("manual_host")
    private val lastGroupKey = stringPreferencesKey("last_room_uuid")

    /** "192.168.0.59" or "10.0.2.2:1401". Used when the SSDP search finds nothing. */
    val manualHost: Flow<String?> = store.data.map { it[manualHostKey] }

    suspend fun manualHost(): String? = manualHost.first()

    suspend fun setManualHost(host: String?) = store.edit {
        if (host.isNullOrBlank()) it.remove(manualHostKey) else it[manualHostKey] = host.trim()
    }

    private val lyricsKey = booleanPreferencesKey("lyrics_enabled")

    /** Lyrics from LRCLIB; off until the user agrees, since it sends track names to lrclib.net. */
    val lyricsEnabled: Flow<Boolean> = store.data.map { it[lyricsKey] ?: false }

    suspend fun setLyricsEnabled(on: Boolean) = store.edit { it[lyricsKey] = on }

    private val debugCallbackKey = stringPreferencesKey("debug_callback")

    /**
     * Debug builds only: "host:port" the speakers should send events to, e.g.
     * "127.0.0.1:3400" for the fake household with `adb forward tcp:3400 tcp:3400`.
     */
    suspend fun debugCallback(): String? = store.data.first()[debugCallbackKey]

    suspend fun setDebugCallback(value: String?) = store.edit {
        if (value.isNullOrBlank()) it.remove(debugCallbackKey) else it[debugCallbackKey] = value.trim()
    }

    private val lastRoomNameKey = stringPreferencesKey("last_room_name")

    /** Name of the room shown last, for the offline mask after a cold start. */
    val lastRoomName: Flow<String?> = store.data.map { it[lastRoomNameKey] }

    suspend fun setLastRoomName(name: String) = store.edit { if (it[lastRoomNameKey] != name) it[lastRoomNameKey] = name }

    /** The room the app showed last; any member UUID of the group. */
    suspend fun lastRoom(): String? = store.data.first()[lastGroupKey]

    suspend fun setLastRoom(uuid: String) = store.edit { it[lastGroupKey] = uuid }
}
