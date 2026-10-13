package de.linuxundich.hausmusik.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.data.history.HistoryDao
import de.linuxundich.hausmusik.data.history.HistoryEntry
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.LibrarySearch
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab(val objectId: String, val grid: Boolean) {
    Favorites("FV:2", true),
    Artists("A:ALBUMARTIST", false),
    Albums("A:ALBUM", true),
    Genres("A:GENRE", false),
    Radio("R:0/0", false),
    Playlists("SQ:", true),
    /** Local, not a Sonos container. */
    History("history", false),
}

sealed interface ListState {
    data object Loading : ListState
    data object Error : ListState
    data class Loaded(val items: List<DidlObject>) : ListState
}

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data object Error : SearchState
    data class Done(val result: LibrarySearch) : SearchState
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: HouseholdRepository,
    private val historyDao: HistoryDao,
) : ViewModel() {
    val history: StateFlow<List<HistoryEntry>> = historyDao.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearHistory() = viewModelScope.launch { historyDao.clear() }

    private val _tab = MutableStateFlow(LibraryTab.Favorites)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    private val _lists = MutableStateFlow<Map<String, ListState>>(emptyMap())
    val lists: StateFlow<Map<String, ListState>> = _lists.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Searches once the user pauses typing; two characters at least. */
    val search: StateFlow<SearchState> = _query
        .debounce(300)
        .flatMapLatest { q ->
            flow {
                if (q.trim().length < 2) {
                    emit(SearchState.Idle)
                } else {
                    emit(SearchState.Loading)
                    emit(repo.search(q).fold({ SearchState.Done(it) }, { SearchState.Error }))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchState.Idle)

    fun setQuery(q: String) {
        _query.value = q
    }

    fun select(tab: LibraryTab) {
        _tab.value = tab
        if (tab != LibraryTab.History && _lists.value[tab.objectId] !is ListState.Loaded) load(tab.objectId)
    }

    /** Loads any container: a tab, an album, an artist, a genre. Results stay cached for the session. */
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
