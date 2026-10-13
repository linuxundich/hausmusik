package de.linuxundich.hausmusik.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.sonos.Alarm
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

    /** Favorites an alarm can play: streams that start directly, like radio. */
    private val _sources = MutableStateFlow<List<DidlObject>>(emptyList())
    val sources: StateFlow<List<DidlObject>> = _sources.asStateFlow()

    val household = repo.state

    fun load() {
        viewModelScope.launch {
            _state.value = repo.alarms().fold({ AlarmsState.Loaded(it) }, { AlarmsState.Error })
            if (_sources.value.isEmpty()) {
                _sources.value = repo.browseAll("FV:2").getOrDefault(emptyList()).filter { it.playsDirectly && it.uri != null }
            }
        }
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
