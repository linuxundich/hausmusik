package de.linuxundich.hausmusik.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.ui.common.Cover
import de.linuxundich.hausmusik.ui.common.formatTime
import de.linuxundich.hausmusik.ui.theme.AppIcons

private fun LibraryTab.label() = when (this) {
    LibraryTab.Favorites -> R.string.library_favorites
    LibraryTab.Albums -> R.string.library_albums
    LibraryTab.Radio -> R.string.library_radio
    LibraryTab.Playlists -> R.string.library_playlists
}

/** Library: favorites, albums of the music share, radio, Sonos playlists. */
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
    // Loaded on first display, not in the ViewModel's init: the ViewModel may
    // exist before any room is known.
    LaunchedEffect(Unit) { vm.load(tab.objectId) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        bottomBar = { if (group != null) MiniPlayer(group, onMiniPlayer, onPlayPause) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryTab.entries) { t ->
                    FilterChip(selected = t == tab, onClick = { vm.select(t) }, label = { Text(stringResource(t.label())) })
                }
            }
            ItemList(lists[tab.objectId], grid = tab != LibraryTab.Radio, artUrl = vm::artUrl, onRetry = { vm.load(tab.objectId, force = true) }) { item ->
                if (item.isContainer && tab != LibraryTab.Favorites) onOpenContainer(item) else onPlay(item)
            }
        }
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
    onPlay: (DidlObject) -> Unit,
    onMiniPlayer: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    LaunchedEffect(container.id) { vm.load(container.id) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(container.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        bottomBar = { if (group != null) MiniPlayer(group, onMiniPlayer, onPlayPause) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(vm.artUrl(container.albumArtUri), Modifier.size(120.dp), corner = 16.dp,
                    placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(container.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                    container.creator?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Button(onClick = { onPlay(container) }, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(AppIcons.Play, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.play), Modifier.padding(start = 6.dp))
                    }
                }
            }
            ItemList(lists[container.id], grid = false, artUrl = vm::artUrl, onRetry = { vm.load(container.id, force = true) }, onClick = onPlay)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ItemList(state: ListState?, grid: Boolean, artUrl: (String?) -> String?, onRetry: () -> Unit, onClick: (DidlObject) -> Unit) {
    when (state) {
        null, ListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        ListState.Error -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.library_error))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.retry)) }
        }
        is ListState.Loaded -> when {
            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.library_empty)) }
            grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    Column(Modifier.clickable { onClick(item) }) {
                        Cover(
                            artUrl(item.albumArtUri), Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp,
                            placeholderColor = MaterialTheme.colorScheme.surfaceVariant,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            icon = if (item.isRadio) AppIcons.Radio else if (item.isContainer) AppIcons.Album else AppIcons.Playlist,
                        )
                        Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        (item.creator ?: item.description)?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.items, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onClick(item) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.originalTrackNumber != null) {
                            Text("${item.originalTrackNumber}", modifier = Modifier.size(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Cover(artUrl(item.albumArtUri), Modifier.size(44.dp), corner = 8.dp,
                                placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                icon = if (item.isRadio) AppIcons.Radio else AppIcons.Album)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.creator?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                        if (item.durationMs != null) Text(formatTime(item.durationMs), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(group: GroupState, onOpen: () -> Unit, onPlayPause: () -> Unit) {
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).padding(bottom = 16.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(group.artUrl, Modifier.size(44.dp), corner = 10.dp,
                placeholderColor = MaterialTheme.colorScheme.surfaceVariant, iconColor = MaterialTheme.colorScheme.onSurfaceVariant, icon = AppIcons.Speaker)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(group.nowPlaying?.title ?: stringResource(R.string.nothing_playing), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(group.group.displayName, fontSize = 12.sp, maxLines = 1)
            }
            FilledIconButton(onClick = onPlayPause, enabled = group.nowPlaying != null) {
                Icon(if (group.isPlaying) AppIcons.Pause else AppIcons.Play, stringResource(if (group.isPlaying) R.string.pause else R.string.play))
            }
        }
    }
}
