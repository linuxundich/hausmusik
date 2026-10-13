// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.sonos.Alarm
import de.linuxundich.hausmusik.sonos.Didl
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.playsDirectly
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AlarmsState {
    data object Loading : AlarmsState
    data object Error : AlarmsState
    data class Loaded(val alarms: List<Alarm>) : AlarmsState
}

@HiltViewModel
class AlarmsViewModel @Inject constructor(private val repo: HouseholdRepository) : ViewModel() {
    private val _state = MutableStateFlow<AlarmsState>(AlarmsState.Loading)
    val state: StateFlow<AlarmsState> = _state.asStateFlow()

    /**
     * What an alarm can play besides the chime: radio favorites (streams that start
     * directly), albums among the favorites and Sonos playlists. Albums and
     * playlists are containers the speakers play through the queue, as the
     * Sonos app does for alarms; service content (Spotify, bonob) is left out.
     */
    private val _sources = MutableStateFlow<List<AlarmSource>>(emptyList())
    val sources: StateFlow<List<AlarmSource>> = _sources.asStateFlow()
    private var allAlbums: List<DidlObject>? = null

    val household = repo.state

    fun load() {
        viewModelScope.launch {
            _state.value = repo.alarms().fold({ AlarmsState.Loaded(it) }, { AlarmsState.Error })
            if (_sources.value.isEmpty()) {
                val favorites = repo.browseAll("FV:2").getOrDefault(emptyList()).filter { it.uri != null }
                val playlists = repo.browseAll("SQ:").getOrDefault(emptyList()).filter { it.uri != null }
                _sources.value = favorites.filter { it.playsDirectly }.map { AlarmSource(it, AlarmSource.Kind.Radio) } +
                    favorites.filter { !it.playsDirectly && isLocalContainer(it.uri!!) }.map { AlarmSource(it, AlarmSource.Kind.Album) } +
                    playlists.map { AlarmSource(it, AlarmSource.Kind.Playlist) }
            }
        }
    }

    /** Albums on the music share whose title or artist contains [query]; the list is read once. */
    suspend fun findAlbums(query: String): List<DidlObject> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val all = allAlbums ?: repo.browseAll("A:ALBUM").getOrDefault(emptyList()).filter { it.uri != null }.also { if (it.isNotEmpty()) allAlbums = it }
        return all.filter { q in it.title.lowercase() || q in it.creator.orEmpty().lowercase() }.take(20)
    }

    fun save(alarm: Alarm, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ok = repo.saveAlarm(alarm).isSuccess
        load()
        onDone(ok)
    }

    fun delete(alarm: Alarm) = viewModelScope.launch {
        alarm.id?.let { repo.deleteAlarm(it) }
        load()
    }
}

data class AlarmSource(val item: DidlObject, val kind: Kind) {
    enum class Kind { Radio, Album, Playlist }
}

/** Music share albums and Sonos playlists; service containers need an account the alarm does not carry. */
internal fun isLocalContainer(uri: String) = uri.startsWith("x-rincon-playlist:") || uri.startsWith("file:///jffs/settings/savedqueues.rsq#")

/** What an alarm sends as metadata for [item]: the favorite's own DIDL, or a minimal one. */
internal fun alarmMetadata(item: DidlObject) = item.resMd ?: Didl.minimal(item.id, item.parentId, item.title, item.upnpClass)
