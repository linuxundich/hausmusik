package de.linuxundich.hausmusik.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    private val debugCallbackKey = stringPreferencesKey("debug_callback")

    /**
     * Debug builds only: "host:port" the speakers should send events to, e.g.
     * "127.0.0.1:3400" for the fake household with `adb forward tcp:3400 tcp:3400`.
     */
    suspend fun debugCallback(): String? = store.data.first()[debugCallbackKey]

    suspend fun setDebugCallback(value: String?) = store.edit {
        if (value.isNullOrBlank()) it.remove(debugCallbackKey) else it[debugCallbackKey] = value.trim()
    }

    /** The room the app showed last; any member UUID of the group. */
    suspend fun lastRoom(): String? = store.data.first()[lastGroupKey]

    suspend fun setLastRoom(uuid: String) = store.edit { it[lastGroupKey] = uuid }
}
