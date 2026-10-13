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
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import de.linuxundich.hausmusik.data.services.displayName
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

/** One kind of hit within a source: [kind] is "artists", "albums", "tracks" or a service's own category. */
data class SearchGroup(val kind: String, val items: List<DidlObject>)

/**
 * The hits of one source: the music share ([service] null) or a linked
 * service. Each source shows as soon as it answered; a slow one keeps
 * [loading] meanwhile.
 */
data class SearchSection(
    val key: String,
    val service: String?,
    val groups: List<SearchGroup> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

sealed interface SearchState {
    data object Idle : SearchState
    data class Results(val sections: List<SearchSection>) : SearchState {
        val done: Boolean get() = sections.none { it.loading }
        val empty: Boolean get() = done && sections.all { it.groups.isEmpty() }
    }
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
        if (_lists.value.containsKey(ServiceIds.MORE)) load(ServiceIds.MORE, force = true)
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
                    emitAll(searchEverywhere(q.trim()))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchState.Idle)

    /** The music share and every linked service at once; each section fills in when its source answers. */
    private fun searchEverywhere(q: String) = kotlinx.coroutines.flow.channelFlow {
        val linked = runCatching { services.linked() }.getOrDefault(emptyList())
        val sections = java.util.concurrent.ConcurrentHashMap<String, SearchSection>()
        val order = listOf("share") + linked.map { "svc:${it.id}" }
        sections["share"] = SearchSection("share", null)
        linked.forEach { sections["svc:${it.id}"] = SearchSection("svc:${it.id}", it.displayName) }
        val lock = kotlinx.coroutines.sync.Mutex()
        suspend fun publish(key: String, change: (SearchSection) -> SearchSection) = lock.withLock {
            sections[key] = change(sections.getValue(key))
            send(SearchState.Results(order.map { sections.getValue(it) }))
        }
        send(SearchState.Results(order.map { sections.getValue(it) }))
        launch {
            repo.search(q).fold(
                { r ->
                    publish("share") {
                        it.copy(loading = false, groups = listOf(
                            SearchGroup("artists", r.artists), SearchGroup("albums", r.albums), SearchGroup("tracks", r.tracks),
                        ).filter { g -> g.items.isNotEmpty() })
                    }
                },
                { publish("share") { it.copy(loading = false, failed = true) } },
            )
        }
        linked.forEach { svc ->
            launch {
                runCatching { services.search(svc.id, q) }.fold(
                    { r -> publish("svc:${svc.id}") { it.copy(loading = false, groups = r.map { (k, items) -> SearchGroup(k, items) }) } },
                    { e ->
                        android.util.Log.w("Library", "search in ${svc.name} failed", e)
                        publish("svc:${svc.id}") { it.copy(loading = false, failed = true) }
                    },
                )
            }
        }
    }

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
