// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.services.ServiceIds
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.history.HistoryEntry
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.ui.common.Cover
import de.linuxundich.hausmusik.ui.common.isWide
import de.linuxundich.hausmusik.ui.common.formatTime
import de.linuxundich.hausmusik.ui.theme.AppIcons

private fun LibraryTab.label() = when (this) {
    LibraryTab.Favorites -> R.string.library_favorites
    LibraryTab.Artists -> R.string.library_artists
    LibraryTab.Genres -> R.string.library_genres
    LibraryTab.Albums -> R.string.library_albums
    LibraryTab.Radio -> R.string.library_radio
    LibraryTab.Playlists -> R.string.library_playlists
    LibraryTab.Services -> R.string.library_services
    LibraryTab.History -> R.string.library_history
}

/** Library: favorites, albums of the music share, radio, Sonos playlists. */
/** Lets list rows ask for an artist picture without threading the ViewModel through every call. */
val LocalArtistImage = androidx.compose.runtime.staticCompositionLocalOf<suspend (String) -> String?> { { null } }

@Composable
private fun ProvideArtistImages(vm: LibraryViewModel, snackbar: SnackbarHostState, content: @Composable () -> Unit) {
    // A new lambda when the switch flips, so rows already on screen ask again.
    val on by vm.artistImagesEnabled.collectAsStateWithLifecycle()
    val fetch: suspend (String) -> String? = androidx.compose.runtime.remember(on) { { name -> vm.artistImage(name) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalArtistImage provides fetch) { ProvideItemActions(vm, snackbar, content) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    group: GroupState?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenContainer: (DidlObject) -> Unit,
    onPlay: (DidlObject) -> Unit,
    onMiniPlayer: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // Loaded on first display, not in the ViewModel's init: the ViewModel may
    // exist before any room is known.
    LaunchedEffect(Unit) { if (tab != LibraryTab.History) vm.load(tab.objectId) }
    val history by vm.history.collectAsStateWithLifecycle()
    BackHandler(searching) { searching = false; vm.setQuery("") }
    val focus = remember { FocusRequester() }
    val wide = isWide()
    val linkState by vm.link.collectAsStateWithLifecycle()
    linkState?.let { LinkDialog(it, onRestart = vm::restartLink, onDismiss = vm::dismissLink) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    } else if (wide) {
                        // Landscape: the tabs share the bar with the title to save a row.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.library))
                            LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(LibraryTab.entries) { t ->
                                    FilterChip(selected = t == tab, onClick = { vm.select(t) }, label = { Text(stringResource(t.label())) })
                                }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.library))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (searching) { searching = false; vm.setQuery("") } else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (wide && group != null) CompactMiniPlayer(group, onMiniPlayer, onPlayPause)
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
                    else if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) }
                },
            )
        },
        // Landscape: height is scarce, so the player sits in the top bar instead (CompactMiniPlayer).
        bottomBar = { if (group != null && !wide) MiniPlayer(group, onMiniPlayer, onPlayPause) },
        // Messages show as the app-wide pill at the top (MessageHost in the root).
    ) { padding ->
        ProvideArtistImages(vm, snackbar) {
            Column(Modifier.padding(padding)) {
                if (searching) {
                    SearchResults(search, vm::artUrl, onOpenContainer, onPlay)
                    return@Column
                }
                if (!wide) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LibraryTab.entries) { t ->
                        FilterChip(selected = t == tab, onClick = { vm.select(t) }, label = { Text(stringResource(t.label())) })
                    }
                }
                val imagesOn by vm.artistImagesEnabled.collectAsStateWithLifecycle()
                val hintDone by vm.artistHintDone.collectAsStateWithLifecycle()
                if (tab == LibraryTab.Artists && !imagesOn && !hintDone) {
                    ArtistImagesHint(onEnable = vm::enableArtistImages, onDismiss = vm::dismissArtistHint)
                }
                if (tab == LibraryTab.History) {
                    // Tap: search the library for the track, since Sonos cannot replay a history entry by itself.
                    HistoryList(history, onClear = vm::clearHistory) { e ->
                        searching = true
                        vm.setQuery(e.title)
                    }
                    return@Column
                }
                if (tab == LibraryTab.Services) ServicesHint()
                val reachable by vm.speakersReachable.collectAsStateWithLifecycle()
                // Each tab keeps its own scroll position, also while another tab shows.
                tabStates.SaveableStateProvider(tab.name) {
                ItemList(
                    lists[tab.objectId], grid = tab.grid, artUrl = vm::artUrl,
                    offline = !reachable && !tab.objectId.startsWith(ServiceIds.OVERVIEW),
                    onRetry = { vm.load(tab.objectId, force = true) },
                ) { item ->
                    when {
                        item.upnpClass == ServiceIds.LINK_CLASS -> vm.startLink(item)
                        item.isContainer && tab != LibraryTab.Favorites -> onOpenContainer(item)
                        else -> onPlay(item)
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun ServicesHint() {
    Text(
        stringResource(R.string.services_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** From then on the link dialog suggests a new sign-in instead of the old page. */
private const val STALE_AFTER_MS = 2 * 60_000L

/** Opens the service's sign-in page and waits until the user confirmed it there. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinkDialog(state: LinkState, onRestart: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun open(url: String) = runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
    if (state is LinkState.Waiting) LaunchedEffect(state.url) { open(state.url) }
    // After a while the page may show an error (Sonos' Spotify page answers 500 once the
    // code expired); then a new sign-in helps, opening the old page again does not.
    val stale by androidx.compose.runtime.produceState(false, state) {
        value = false // produceState keeps the old value when the state changes
        if (state is LinkState.Waiting) {
            kotlinx.coroutines.delay((state.since + STALE_AFTER_MS - System.currentTimeMillis()).coerceAtLeast(0))
            value = true
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (state is LinkState.Done) Icons.Filled.Check else AppIcons.Link, null) },
        title = { Text(stringResource(R.string.link_title, state.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is LinkState.Starting -> LoadingIndicator(Modifier.size(40.dp))
                    is LinkState.Waiting -> {
                        Text(stringResource(R.string.link_waiting, state.name))
                        state.code?.let { Text(stringResource(R.string.link_code, it), style = MaterialTheme.typography.titleMedium) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(32.dp))
                            Text(stringResource(R.string.link_polling), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        if (stale) Text(stringResource(R.string.link_stale), style = MaterialTheme.typography.bodySmall)
                    }
                    is LinkState.Done -> Text(stringResource(R.string.link_done, state.name))
                    is LinkState.Failed -> Text(stringResource(R.string.link_failed, state.name))
                }
            }
        },
        confirmButton = {
            when (state) {
                is LinkState.Waiting -> if (stale) {
                    androidx.compose.material3.TextButton(onClick = onRestart) { Text(stringResource(R.string.link_restart)) }
                } else {
                    androidx.compose.material3.TextButton(onClick = { open(state.url) }) { Text(stringResource(R.string.link_open_again)) }
                }
                is LinkState.Failed -> androidx.compose.material3.TextButton(onClick = onRestart) { Text(stringResource(R.string.link_restart)) }
                is LinkState.Done -> androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
                else -> {}
            }
        },
        dismissButton = {
            if (state is LinkState.Starting || state is LinkState.Waiting) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            } else if (state is LinkState.Failed) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
    )
}

@Composable
private fun ArtistImagesHint(onEnable: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.artist_images_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.artist_images_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.artist_images_no)) }
                Button(onClick = onEnable) { Text(stringResource(R.string.artist_images_yes)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchResults(
    state: SearchState,
    artUrl: (String?) -> String?,
    onOpenContainer: (DidlObject) -> Unit,
    onPlay: (DidlObject) -> Unit,
) {
    when (state) {
        SearchState.Idle -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
            Text(stringResource(R.string.search_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is SearchState.Results -> if (state.empty) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) { Text(stringResource(R.string.search_nothing)) }
        } else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            // Only label the sources when there is more than the music share.
            val labelled = state.sections.size > 1
            state.sections.forEach { section ->
                if (labelled) item(key = "s/${section.key}") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            section.service ?: stringResource(R.string.search_share),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        if (section.loading) LoadingIndicator(Modifier.size(24.dp))
                    }
                }
                if (!section.loading && section.groups.isEmpty()) item(key = "e/${section.key}") {
                    Text(
                        stringResource(if (section.failed) R.string.search_source_failed else R.string.search_nothing),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                section.groups.forEach { group ->
                    item(key = "h/${section.key}/${group.kind}") {
                        Text(
                            searchGroupLabel(group.kind), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    // Services may list one item twice (bonob does): the position keeps keys unique.
                    itemsIndexed(group.items, key = { i, it -> "${section.key}/${group.kind}/$i/${it.id}" }) { _, item ->
                        ItemRow(item, artUrl) { if (item.isContainer) onOpenContainer(item) else onPlay(item) }
                    }
                }
            }
            if (state.sections.size == 1 && !state.done) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
        }
    }
}

/** "artists", "search:album", "Playlists" … as a heading. */
@Composable
private fun searchGroupLabel(kind: String): String {
    val k = kind.lowercase().substringAfter(':')
    return when {
        k.startsWith("artist") -> stringResource(R.string.library_artists)
        k.startsWith("album") -> stringResource(R.string.library_albums)
        k.startsWith("track") || k.startsWith("song") -> stringResource(R.string.search_tracks)
        k.startsWith("playlist") -> stringResource(R.string.library_playlists)
        k.startsWith("podcast") -> stringResource(R.string.search_podcasts)
        else -> kind.substringAfter(':').replaceFirstChar { it.uppercase() }
    }
}

/** Tracks of an album or playlist, with a button that plays all of them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerScreen(
    vm: LibraryViewModel,
    container: DidlObject,
    group: GroupState?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenContainer: (DidlObject) -> Unit,
    onPlay: (DidlObject) -> Unit,
    onMiniPlayer: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    val wideBar = isWide()
    // Local containers play by their ID; a service's own folders (its root, "Albums") cannot be played.
    val playable = container.uri != null || !container.id.startsWith(ServiceIds.OVERVIEW)
    val linkState by vm.link.collectAsStateWithLifecycle()
    linkState?.let { LinkDialog(it, onRestart = vm::restartLink, onDismiss = vm::dismissLink) }
    LaunchedEffect(container.id) { vm.load(container.id) }
    ProvideArtistImages(vm, snackbar) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(container.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        // Landscape: the play button lives up here, always in reach.
                        // Only a service Hausmusik linked can be unlinked; TuneIn needs no account.
                    // In a menu and confirmed: up here it sat where other apps put "Done".
                    if (container.upnpClass == ServiceIds.LINKED_CLASS) {
                        var menu by remember { mutableStateOf(false) }
                        var confirm by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.queue_more)) }
                            androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.service_unlink_menu)) },
                                    onClick = { menu = false; confirm = true },
                                )
                            }
                        }
                        if (confirm) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { confirm = false },
                                title = { Text(stringResource(R.string.service_unlink_title, container.title)) },
                                text = { Text(stringResource(R.string.service_unlink_body)) },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = { confirm = false; vm.unlink(container); onBack() }) {
                                        Text(stringResource(R.string.service_unlink))
                                    }
                                },
                                dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
                            )
                        }
                    }
                    val details = de.linuxundich.hausmusik.ui.details.LocalItemDetails.current
                    val target = remember(container) { de.linuxundich.hausmusik.ui.details.DetailsTarget.of(container) }
                    if (details != null && target != null) IconButton(onClick = { details(target) }) { Icon(AppIcons.Info, stringResource(R.string.details)) }
                    if (wideBar && group != null) CompactMiniPlayer(group, onMiniPlayer, onPlayPause)
                    if (wideBar && playable) Button(onClick = { onPlay(container) }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(AppIcons.Play, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.play), Modifier.padding(start = 6.dp))
                        }
                    },
                )
            },
            bottomBar = { if (group != null && !wideBar) MiniPlayer(group, onMiniPlayer, onPlayPause) },
            // Messages show as the app-wide pill at the top (MessageHost in the root).
        ) { padding ->
            val person = container.upnpClass.contains("person")
            val artistImage = LocalArtistImage.current
            val picture by produceState<String?>(null, container.title, person, artistImage) { if (person) value = artistImage(container.title) }
            val cover: @Composable (Modifier) -> Unit = { m ->
                Cover(picture ?: vm.artUrl(container.albumArtUri), m, corner = if (person) 100.dp else 16.dp,
                    placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = if (person) Icons.Filled.Person else AppIcons.Album)
            }
            val info: @Composable () -> Unit = {
                Text(container.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                container.creator?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (!wideBar && playable) Button(onClick = { onPlay(container) }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(AppIcons.Play, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.play), Modifier.padding(start = 6.dp))
                }
            }
            val state = lists[container.id]
            val allContainers = (state as? ListState.Loaded)?.items?.let { it.isNotEmpty() && it.all { i -> i.isContainer } } == true
            // Inside an album, track numbers say more than the same cover over and over.
            val isAlbum = container.upnpClass.contains("album")
            val list: @Composable () -> Unit = {
                val reachable by vm.speakersReachable.collectAsStateWithLifecycle()
                ItemList(
                    state, grid = allContainers, artUrl = vm::artUrl, numbered = isAlbum,
                    offline = !reachable && !container.id.startsWith(ServiceIds.OVERVIEW),
                    onRetry = { vm.load(container.id, force = true) },
                ) { item ->
                    when {
                        item.upnpClass == ServiceIds.LINK_CLASS -> vm.startLink(item)
                        item.isContainer -> onOpenContainer(item)
                        else -> onPlay(item)
                    }
                }
            }
            if (isWide()) {
                // Landscape: cover and play button on the left, the tracks get the full height.
                // The cover fills its column; the list brings its own 16 dp, so both gaps come to 24 dp.
                Row(Modifier.padding(padding).padding(start = 24.dp)) {
                    Column(Modifier.width(152.dp).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                        cover(Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(12.dp))
                        info()
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) { list() }
                }
            } else {
                Column(Modifier.padding(padding)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        cover(Modifier.size(120.dp))
                        Column(Modifier.padding(start = 16.dp).weight(1f)) { info() }
                    }
                    list()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ItemList(
    state: ListState?,
    grid: Boolean,
    artUrl: (String?) -> String?,
    numbered: Boolean = false,
    /** The speakers are out of reach: a list that is not there yet waits for them, and says so. */
    offline: Boolean = false,
    onRetry: () -> Unit,
    onClick: (DidlObject) -> Unit,
) {
    if (offline && state !is ListState.Loaded) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(AppIcons.WifiOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.library_offline), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            Text(
                stringResource(R.string.library_offline_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
            )
        }
        return
    }
    when (state) {
        null, ListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                // The first query of a list can take Sonos half a minute; say so after a few seconds.
                var slow by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(4000); slow = true }
                if (slow) Text(
                    stringResource(R.string.library_slow), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }
        }
        ListState.Error -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.library_error))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.retry)) }
        }
        is ListState.Loaded -> when {
            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.library_empty)) }
            grid -> LazyVerticalGrid(
                // Landscape shows fewer rows; smaller covers fit almost two of them.
                columns = GridCells.Adaptive(if (isWide()) 96.dp else 112.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    val playing = item.playsNow(LocalNowPlaying.current)
                    ItemMenuBox(item, null, { onClick(item) }) { menuModifier -> Column(menuModifier) {
                        Box {
                            Cover(
                                artUrl(item.albumArtUri), Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp,
                                placeholderColor = MaterialTheme.colorScheme.surfaceVariant,
                                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                icon = if (item.isRadio) AppIcons.Radio else if (item.isContainer) AppIcons.Album else AppIcons.Playlist,
                            )
                            // A badge on what plays now.
                            if (playing) Box(
                                Modifier.align(Alignment.BottomStart).padding(6.dp).size(28.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Icon(AppIcons.VolumeUp, stringResource(R.string.playing_now), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                        }
                        Text(
                            item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                            color = if (playing) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                        )
                        (item.creator ?: item.description)?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } }
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                // Services often send no track numbers; then the position in the album stands in.
                itemsIndexed(state.items, key = { _, it -> it.id }) { i, item -> ItemRow(item, artUrl, numbered, i + 1) { onClick(item) } }
            }
        }
    }
}

@Composable
private fun ItemRow(item: DidlObject, artUrl: (String?) -> String?, numbered: Boolean = false, position: Int? = null, onClick: () -> Unit) {
    val person = item.upnpClass.contains("person")
    val artistImage = LocalArtistImage.current
    val picture by produceState<String?>(null, item.title, person, artistImage) { if (person) value = artistImage(item.title) }
    ItemMenuBox(item, position, onClick) { menuModifier -> Row(
        Modifier.fillMaxWidth().then(menuModifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val number = item.originalTrackNumber ?: position.takeIf { !item.isContainer }
        // What plays now stands out, as in the queue.
        val playing = item.playsNow(LocalNowPlaying.current)
        if (numbered && number != null) {
            if (playing) Icon(AppIcons.VolumeUp, stringResource(R.string.playing_now), Modifier.width(28.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
            else Text("$number", modifier = Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Cover(
                picture ?: artUrl(item.albumArtUri), Modifier.size(44.dp), corner = if (person) 22.dp else 8.dp,
                placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = when {
                    item.upnpClass == ServiceIds.LINK_CLASS -> Icons.Filled.Add
                    item.id.startsWith("linein:") -> AppIcons.Input
                    item.isRadio -> AppIcons.Radio
                    item.upnpClass.contains("person") -> Icons.Filled.Person
                    item.isContainer -> AppIcons.Album
                    else -> AppIcons.Playlist
                },
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing && !(numbered && number != null)) {
                    Icon(AppIcons.VolumeUp, stringResource(R.string.playing_now), Modifier.padding(end = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                    fontWeight = if (playing) FontWeight.SemiBold else null,
                )
            }
            (if (item.upnpClass == ServiceIds.LINK_CLASS) item.description
            else if (numbered) item.creator else listOfNotNull(item.creator, item.album).joinToString(" · ")).takeIf { !it.isNullOrEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (item.durationMs != null) Text(formatTime(item.durationMs), style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
private fun HistoryList(entries: List<HistoryEntry>, onClear: () -> Unit, onClick: (HistoryEntry) -> Unit) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
            Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val time = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    val day = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onClear) { Text(stringResource(R.string.history_clear)) }
            }
        }
        var lastDay: String? = null
        entries.forEach { e ->
            val d = day.format(java.util.Date(e.playedAt))
            if (d != lastDay) {
                lastDay = d
                item(key = "d$d") {
                    Text(d, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                }
            }
            item(key = e.id) {
                Row(
                    Modifier.fillMaxWidth().clickable { onClick(e) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(e.artUrl, Modifier.size(44.dp), corner = 8.dp, placeholderColor = MaterialTheme.colorScheme.surfaceVariant,
                        iconColor = MaterialTheme.colorScheme.onSurfaceVariant, icon = if (e.station != null) AppIcons.Radio else AppIcons.Album)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(e.artist ?: e.station, e.room).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    Text(time.format(java.util.Date(e.playedAt)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The mini player for the top bar in landscape: cover, title and play button in one pill. */
@Composable
private fun CompactMiniPlayer(group: GroupState, onOpen: () -> Unit, onPlayPause: () -> Unit) {
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(horizontal = 4.dp).widthIn(max = 260.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(group.artUrl, Modifier.size(32.dp), corner = 16.dp,
                placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = if (group.nowPlaying?.kind == de.linuxundich.hausmusik.sonos.SourceKind.Radio) AppIcons.Radio else AppIcons.Speaker)
            val starting = LocalStarting.current
            Text(
                starting?.let { stringResource(R.string.starting, it) } ?: group.nowPlaying?.title ?: stringResource(R.string.nothing_playing),
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp),
            )
            IconButton(onClick = { if (starting == null) onPlayPause() }, enabled = group.nowPlaying != null) {
                if (starting != null) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(if (group.isPlaying) AppIcons.Pause else AppIcons.Play, stringResource(if (group.isPlaying) R.string.pause else R.string.play))
            }
        }
    }
}

@Composable
private fun MiniPlayer(group: GroupState, onOpen: () -> Unit, onPlayPause: () -> Unit) {
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(group.artUrl, Modifier.size(44.dp), corner = 10.dp,
                placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = if (group.nowPlaying?.kind == de.linuxundich.hausmusik.sonos.SourceKind.Radio) AppIcons.Radio else AppIcons.Speaker)
            // A started queue can take seconds: say so at once instead of showing the old title.
            val starting = LocalStarting.current
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    starting?.let { stringResource(R.string.starting, it) } ?: group.nowPlaying?.title ?: stringResource(R.string.nothing_playing),
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(group.group.displayName, fontSize = 12.sp, maxLines = 1)
            }
            // Full color while starting, so the spinner is seen; taps wait.
            FilledIconButton(onClick = { if (starting == null) onPlayPause() }, enabled = group.nowPlaying != null) {
                if (starting != null) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(if (group.isPlaying) AppIcons.Pause else AppIcons.Play, stringResource(if (group.isPlaying) R.string.pause else R.string.play))
            }
        }
    }
}

/** The title "Play now" is starting, provided by the root; null when nothing is starting. */
val LocalStarting = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** What plays in the selected room, for marking it in lists; provided by the root. */
val LocalNowPlaying = androidx.compose.runtime.compositionLocalOf<de.linuxundich.hausmusik.sonos.NowPlaying?> { null }

/**
 * Whether this entry is what plays now. Sonos tells the playing track by its
 * names only, so the names decide: a station by its name, an album by title
 * and artist, a track by title and artist.
 */
internal fun DidlObject.playsNow(now: de.linuxundich.hausmusik.sonos.NowPlaying?): Boolean {
    if (now == null) return false
    fun n(s: String?) = s?.let(de.linuxundich.hausmusik.data.ArtistImages::normalize).orEmpty()
    fun sameArtist() = creator == null || now.artist == null || n(creator) == n(now.artist)
    return when {
        isRadio || now.kind == de.linuxundich.hausmusik.sonos.SourceKind.Radio ->
            isRadio && now.station != null && n(title) == n(now.station)
        upnpClass.contains("musicAlbum") -> now.album != null && n(title) == n(now.album) && sameArtist()
        !isContainer -> n(title) == n(now.title) && sameArtist()
        else -> false
    }
}
