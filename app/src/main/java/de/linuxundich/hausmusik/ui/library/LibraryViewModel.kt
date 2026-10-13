package de.linuxundich.hausmusik.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.sonos.DidlObject
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab(val objectId: String) {
    Favorites("FV:2"), Albums("A:ALBUM"), Radio("R:0/0"), Playlists("SQ:")
}

sealed interface ListState {
    data object Loading : ListState
    data object Error : ListState
    data class Loaded(val items: List<DidlObject>) : ListState
}

@HiltViewModel
class LibraryViewModel @Inject constructor(private val repo: HouseholdRepository) : ViewModel() {
    private val _tab = MutableStateFlow(LibraryTab.Favorites)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    private val _lists = MutableStateFlow<Map<String, ListState>>(emptyMap())
    val lists: StateFlow<Map<String, ListState>> = _lists.asStateFlow()


    fun select(tab: LibraryTab) {
        _tab.value = tab
        if (_lists.value[tab.objectId] !is ListState.Loaded) load(tab.objectId)
    }

    /** Loads any container: a tab, an album, a playlist. Results stay cached for the session. */
    fun load(objectId: String, force: Boolean = false) {
        if (!force && _lists.value[objectId] is ListState.Loaded) return
        _lists.update { it + (objectId to ListState.Loading) }
        viewModelScope.launch {
            val result = repo.browseAll(objectId).onFailure { android.util.Log.w("Library", "browse $objectId failed", it) }
            _lists.update { it + (objectId to result.fold({ ListState.Loaded(it) }, { ListState.Error })) }
        }
    }

    fun artUrl(uri: String?): String? = repo.artUrl(uri)
}
