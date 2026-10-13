// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.linuxundich.hausmusik.data.ArtistImages
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.data.Settings
import de.linuxundich.hausmusik.data.history.HistoryDao
import de.linuxundich.hausmusik.data.services.MusicServices
import de.linuxundich.hausmusik.data.services.ServiceIds
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
    /** Music services linked through SMAPI. */
    Services("svc:", false),
    /** Local, not a Sonos container. */
    History("history", false),
}

sealed interface ListState {
    data object Loading : ListState
    data object Error : ListState
    data class Loaded(val items: List<DidlObject>) : ListState
}

/** Linking a music service: the browser page is open, Hausmusik waits for the confirmation. */
sealed interface LinkState {
    val name: String
    data class Starting(override val name: String) : LinkState
    data class Waiting(override val name: String, val url: String, val code: String?) : LinkState
    data class Done(override val name: String) : LinkState
    data class Failed(override val name: String) : LinkState
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
    private val artistImages: ArtistImages,
    private val settings: Settings,
    private val services: MusicServices,
) : ViewModel() {
    private val _link = MutableStateFlow<LinkState?>(null)
    val link: StateFlow<LinkState?> = _link.asStateFlow()
    private var linkJob: kotlinx.coroutines.Job? = null

    /** Starts linking the service behind a `link:<id>` entry. */
    fun startLink(item: DidlObject) {
        val serviceId = item.id.removePrefix("link:").toIntOrNull() ?: return
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            _link.value = LinkState.Starting(item.title)
            val ok = runCatching {
                val pending = services.startLink(serviceId)
                _link.value = LinkState.Waiting(item.title, pending.link.regUrl, pending.link.linkCode.takeIf { pending.link.showCode })
                services.awaitLink(pending)
            }.onFailure { android.util.Log.w("Library", "linking ${item.title} failed", it) }.getOrDefault(false)
            _link.value = if (ok) LinkState.Done(item.title) else LinkState.Failed(item.title)
            if (ok) {
                load(ServiceIds.OVERVIEW, force = true)
                if (_lists.value.containsKey(ServiceIds.MORE)) load(ServiceIds.MORE, force = true)
            }
        }
    }

    fun dismissLink() {
        linkJob?.cancel()
        _link.value = null
    }

    fun unlink(container: DidlObject) = viewModelScope.launch {
        val (sid, _) = ServiceIds.parse(container.id) ?: return@launch
        services.unlink(sid)
        load(ServiceIds.OVERVIEW, force = true)
    }

    val artistImagesEnabled: StateFlow<Boolean> = settings.artistImagesEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val artistHintDone: StateFlow<Boolean> = settings.artistImagesHintDone.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    suspend fun artistImage(name: String): String? = artistImages.forArtist(name)
    fun enableArtistImages() = viewModelScope.launch { settings.setArtistImagesEnabled(true) }
    fun dismissArtistHint() = viewModelScope.launch { settings.dismissArtistImagesHint() }

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

    /**
     * Loads any container: a tab, an album, an artist, a genre. A stored copy
     * shows at once; Sonos is only asked again when the list's version (music
     * share, favorites, playlists, radio) differs from the stored one.
     */
    fun load(objectId: String, force: Boolean = false) {
        if (!force && _lists.value[objectId] is ListState.Loaded) return
        if (objectId.startsWith(ServiceIds.OVERVIEW)) { loadService(objectId); return }
        viewModelScope.launch {
            val cached = if (force) null else repo.cachedList(objectId)
            if (cached != null) _lists.update { it + (objectId to ListState.Loaded(cached.items)) }
            if (cached?.current == true) return@launch
            if (cached == null) _lists.update { it + (objectId to ListState.Loading) }
            val result = repo.loadAndCache(objectId).onFailure { android.util.Log.w("Library", "browse $objectId failed", it) }
            result.onSuccess { items -> _lists.update { it + (objectId to ListState.Loaded(items)) } }
                .onFailure { if (cached == null) _lists.update { it + (objectId to ListState.Error) } }
        }
    }

    /** Service lists are not cached: services have no list versions to compare. */
    private fun loadService(objectId: String) = viewModelScope.launch {
        if (_lists.value[objectId] !is ListState.Loaded) _lists.update { it + (objectId to ListState.Loading) }
        runCatching { if (objectId == ServiceIds.OVERVIEW) services.overview() else services.browse(objectId) }
            .onSuccess { items -> _lists.update { it + (objectId to ListState.Loaded(items)) } }
            .onFailure { e ->
                android.util.Log.w("Library", "service $objectId failed", e)
                _lists.update { it + (objectId to ListState.Error) }
            }
    }

    init {
        // A list changed elsewhere (new favorite, re-indexed share): reload what is loaded.
        viewModelScope.launch {
            var last = repo.libraryVersions.value
            repo.libraryVersions.collect { now ->
                val changed = now.keys.filter { last.containsKey(it) && last[it] != now[it] }
                last = now
                if (changed.isEmpty()) return@collect
                _lists.value.keys.filter { id -> changed.any { key -> affects(key, id) } }.forEach { load(it, force = true) }
            }
        }
    }

    private fun affects(key: String, objectId: String) = when (key) {
        "ShareListUpdateID" -> objectId.startsWith("A:")
        "FavoritesUpdateID" -> objectId.startsWith("FV:")
        "SavedQueuesUpdateID" -> objectId.startsWith("SQ:")
        "RadioFavoritesUpdateID" -> objectId.startsWith("R:")
        else -> false
    }

    fun artUrl(uri: String?): String? = repo.artUrl(uri)
}
