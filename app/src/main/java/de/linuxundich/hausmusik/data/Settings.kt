// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    private val lyricsOffsetsKey = stringPreferencesKey("lyrics_offsets")
    private val offsetsJson = kotlinx.serialization.json.Json

    /** How much later (ms) the lyrics of a track run than LRCLIB's times say; set by the user, per track. */
    val lyricsOffsets: Flow<Map<String, Int>> = store.data.map { p ->
        p[lyricsOffsetsKey]?.let { runCatching { offsetsJson.decodeFromString<Map<String, Int>>(it) }.getOrNull() }.orEmpty()
    }

    suspend fun setLyricsOffset(track: String, ms: Int) = store.edit { p ->
        val now = p[lyricsOffsetsKey]?.let { runCatching { offsetsJson.decodeFromString<Map<String, Int>>(it) }.getOrNull() }.orEmpty()
        // The newest last; the oldest go beyond 500 tracks.
        val next = (now - track).let { if (ms == 0) it else it + (track to ms) }.entries.toList().takeLast(500).associate { it.key to it.value }
        p[lyricsOffsetsKey] = offsetsJson.encodeToString<Map<String, Int>>(next)
    }

    private val lyricsKey = booleanPreferencesKey("lyrics_enabled")

    /** Lyrics from LRCLIB; off until the user agrees, since it sends track names to lrclib.net. */
    val lyricsEnabled: Flow<Boolean> = store.data.map { it[lyricsKey] ?: false }

    suspend fun setLyricsEnabled(on: Boolean) = store.edit { it[lyricsKey] = on }

    private val artistImagesKey = booleanPreferencesKey("artist_images_enabled")
    private val artistHintKey = booleanPreferencesKey("artist_images_hint_done")

    /** Artist pictures from Deezer; off until the user agrees, since it sends artist names to deezer.com. */
    val artistImagesEnabled: Flow<Boolean> = store.data.map { it[artistImagesKey] ?: false }
    val artistImagesHintDone: Flow<Boolean> = store.data.map { it[artistHintKey] ?: false }

    suspend fun setArtistImagesEnabled(on: Boolean) = store.edit { it[artistImagesKey] = on; it[artistHintKey] = true }
    suspend fun dismissArtistImagesHint() = store.edit { it[artistHintKey] = true }

    private val detailsKey = booleanPreferencesKey("details_enabled")
    private val detailsWikipediaKey = booleanPreferencesKey("details_wikipedia_enabled")

    /**
     * Background on the playing artist and album from MusicBrainz; off until the
     * user agrees, since it sends artist and album names to musicbrainz.org.
     */
    val detailsEnabled: Flow<Boolean> = store.data.map { it[detailsKey] ?: false }

    /** Adds a Wikipedia text (through Wikidata) to the facts; only matters while [detailsEnabled]. */
    val detailsWikipediaEnabled: Flow<Boolean> = store.data.map { it[detailsWikipediaKey] ?: true }

    suspend fun setDetailsEnabled(on: Boolean) = store.edit { it[detailsKey] = on }
    suspend fun setDetailsWikipediaEnabled(on: Boolean) = store.edit { it[detailsWikipediaKey] = on }

    private val videosKey = booleanPreferencesKey("music_videos_enabled")
    private val videoModeKey = stringPreferencesKey("music_video_mode")

    /** Music videos through Wikidata and the YouTube app; off until the user agrees (titles go to wikidata.org). */
    val musicVideosEnabled: Flow<Boolean> = store.data.map { it[videosKey] ?: false }
    val musicVideoMode: Flow<VideoMode> = store.data.map { p -> VideoMode.entries.firstOrNull { it.name == p[videoModeKey] } ?: VideoMode.Ask }

    suspend fun setMusicVideosEnabled(on: Boolean) = store.edit { it[videosKey] = on }
    suspend fun setMusicVideoMode(mode: VideoMode) = store.edit { it[videoModeKey] = mode.name }

    private val localMusicKey = booleanPreferencesKey("local_music_enabled")

    /**
     * The music share of the Sonos system ("My music"). Off for households
     * that listen through services only (or browse their share through bonob):
     * the library and the search leave it out.
     */
    val localMusicEnabled: Flow<Boolean> = store.data.map { it[localMusicKey] ?: true }

    suspend fun setLocalMusicEnabled(on: Boolean) = store.edit { it[localMusicKey] = on }

    private val askReplaceKey = booleanPreferencesKey("ask_before_replace")

    /** Ask before "Play now" replaces a queue; the user can turn it off in the dialog. */
    val askBeforeReplace: Flow<Boolean> = store.data.map { it[askReplaceKey] ?: true }

    suspend fun setAskBeforeReplace(on: Boolean) = store.edit { it[askReplaceKey] = on }

    private val stageLightKey = booleanPreferencesKey("fx_stage_light")
    private val tapAlongKey = booleanPreferencesKey("fx_tap_along")
    private val turntableKey = booleanPreferencesKey("fx_turntable")
    private val nightStageKey = booleanPreferencesKey("fx_night_stage")

    /** The playful extras, each one switchable; the light and tapping along are on from the start. */
    val effects: Flow<Effects> = store.data.map { p ->
        Effects(p[stageLightKey] ?: true, p[tapAlongKey] ?: true, p[turntableKey] ?: false, p[nightStageKey] ?: false)
    }

    suspend fun setEffects(e: Effects) = store.edit {
        it[stageLightKey] = e.stageLight; it[tapAlongKey] = e.tapAlong; it[turntableKey] = e.turntable; it[nightStageKey] = e.nightStage
    }

    private val everywhereKey = androidx.datastore.preferences.core.stringSetPreferencesKey("everywhere_excluded")

    /** Rooms (player UUIDs) that "Everywhere" in the rooms sheet leaves out. */
    val everywhereExcluded: Flow<Set<String>> = store.data.map { it[everywhereKey] ?: emptySet() }

    suspend fun setEverywhereExcluded(uuids: Set<String>) = store.edit { it[everywhereKey] = uuids }

    private val debugDetailsKey = stringPreferencesKey("debug_details_base")

    /** Debug builds only: "host:port" serving fake MusicBrainz, Wikidata and Wikipedia answers (the fake household). */
    suspend fun debugDetailsBase(): String? = store.data.first()[debugDetailsKey]

    suspend fun setDebugDetailsBase(value: String?) = store.edit {
        if (value.isNullOrBlank()) it.remove(debugDetailsKey) else it[debugDetailsKey] = value.trim()
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

    private val lastRoomNameKey = stringPreferencesKey("last_room_name")

    /** Name of the room shown last, for the offline mask after a cold start. */
    val lastRoomName: Flow<String?> = store.data.map { it[lastRoomNameKey] }

    suspend fun setLastRoomName(name: String) = store.edit { if (it[lastRoomNameKey] != name) it[lastRoomNameKey] = name }

    private val volumeCapKey = intPreferencesKey("debug_volume_cap")

    /**
     * Debug builds only: highest volume the app may set on any speaker, for
     * tests on real speakers. Null means no cap.
     */
    val debugVolumeCap: Flow<Int?> = store.data.map { it[volumeCapKey] }

    suspend fun setDebugVolumeCap(cap: Int?) = store.edit { if (cap == null) it.remove(volumeCapKey) else it[volumeCapKey] = cap.coerceIn(0, 100) }

    /** The room the app showed last; any member UUID of the group. */
    suspend fun lastRoom(): String? = store.data.first()[lastGroupKey]

    suspend fun setLastRoom(uuid: String) = store.edit { it[lastGroupKey] = uuid }
}

/**
 * The playful extras on the stage. [stageLight]: lights in the cover's colors,
 * house lights in a pause. [tapAlong]: tapping the cover in time makes the light
 * pulse. [turntable]: a record turns beside the cover. [nightStage]: the
 * screensaver while charging.
 */
data class Effects(
    val stageLight: Boolean = true,
    val tapAlong: Boolean = true,
    val turntable: Boolean = false,
    val nightStage: Boolean = false,
)
