// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.services.ServiceIds
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.ui.common.AppMenu
import de.linuxundich.hausmusik.ui.details.DetailsTarget
import de.linuxundich.hausmusik.ui.details.LocalItemDetails
import de.linuxundich.hausmusik.ui.theme.AppIcons

/** What can happen to a library entry besides playing it; provided by the library screens. */
private class ItemActions(
    val isFavorite: (DidlObject) -> Boolean,
    val toggleFavorite: (DidlObject) -> Unit,
    val removeFromPlaylist: (String, Int) -> Unit,
    val open: (ItemDialog) -> Unit,
)

/** A dialog the item menu opens; kept across a rotation by the item's ID. */
private sealed interface ItemDialog {
    data class AddToPlaylist(val item: DidlObject) : ItemDialog
    data class Rename(val playlist: DidlObject) : ItemDialog
    data class Delete(val playlist: DidlObject) : ItemDialog
}

private val LocalItemActions = staticCompositionLocalOf<ItemActions?> { null }

private val sonosPlaylist = Regex("SQ:\\d+")

/** Gives the library's entries their long-press menu and shows the dialogs it opens. */
@Composable
internal fun ProvideItemActions(vm: LibraryViewModel, snackbar: androidx.compose.material3.SnackbarHostState, content: @Composable () -> Unit) {
    val resources = LocalResources.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(resources.getString(it)) } }
    // The favorites decide "add" or "remove"; load them even when another tab shows.
    LaunchedEffect(vm) { vm.load(LibraryTab.Favorites.objectId) }
    val lists by vm.lists.collectAsStateWithLifecycle()
    val favoriteUris = remember(lists) {
        (lists[LibraryTab.Favorites.objectId] as? ListState.Loaded)?.items.orEmpty().mapNotNull { it.uri }.toSet()
    }
    var dialog by remember { mutableStateOf<ItemDialog?>(null) }
    val actions = remember(favoriteUris) {
        ItemActions(
            isFavorite = { it.id.startsWith("FV:2/") || it.uri in favoriteUris },
            toggleFavorite = vm::toggleFavorite,
            removeFromPlaylist = { id, pos -> vm.removeFromPlaylist(id, pos) },
            open = { dialog = it },
        )
    }
    CompositionLocalProvider(LocalItemActions provides actions, content = content)
    when (val d = dialog) {
        is ItemDialog.AddToPlaylist -> AddToPlaylistDialog(vm, d.item) { dialog = null }
        is ItemDialog.Rename -> RenameDialog(d.playlist, onRename = { vm.renamePlaylist(d.playlist, it); dialog = null }) { dialog = null }
        is ItemDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text(stringResource(R.string.playlist_delete)) },
            text = { Text(stringResource(R.string.playlist_delete_confirm, d.playlist.title)) },
            confirmButton = {
                TextButton(
                    onClick = { vm.deletePlaylist(d.playlist); dialog = null },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> {}
    }
}

/**
 * A library entry: a tap plays or opens it, a long press shows its menu (details,
 * Sonos favorite, playlist actions). Entries without any action only react to taps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemMenuBox(item: DidlObject, position: Int?, onClick: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val actions = LocalItemActions.current
    val details = LocalItemDetails.current
    val target = remember(item) { DetailsTarget.of(item) }
    val isLink = item.upnpClass == ServiceIds.LINK_CLASS || item.id.startsWith("link:")
    val isInput = item.id.startsWith("linein:")
    val canFavorite = actions != null && item.uri != null && !isLink && !isInput
    val canAddToPlaylist = actions != null && item.uri != null && !isLink && !isInput && !item.isRadio
    val isPlaylist = actions != null && sonosPlaylist.matches(item.id)
    val inPlaylist = actions != null && position != null && sonosPlaylist.matches(item.parentId)
    val any = (details != null && target != null) || canFavorite || canAddToPlaylist || isPlaylist || inPlaylist
    var menu by remember { mutableStateOf(false) }
    Box {
        content(
            if (!any) Modifier.clickable(onClick = onClick)
            else Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.item_menu)),
        )
        if (any) AppMenu(menu, { menu = false }) {
            if (details != null && target != null) Item(AppIcons.Info, stringResource(R.string.details)) { menu = false; details(target) }
            if (canFavorite) {
                val fav = actions!!.isFavorite(item)
                Item(if (fav) AppIcons.FavoriteBorder else AppIcons.Favorite, stringResource(if (fav) R.string.favorite_remove else R.string.favorite_add)) {
                    menu = false; actions.toggleFavorite(item)
                }
            }
            if (canAddToPlaylist) Item(AppIcons.PlaylistAdd, stringResource(R.string.playlist_add_to)) { menu = false; actions!!.open(ItemDialog.AddToPlaylist(item)) }
            if (isPlaylist) {
                Item(AppIcons.Edit, stringResource(R.string.playlist_rename)) { menu = false; actions!!.open(ItemDialog.Rename(item)) }
                Item(Icons.Filled.Delete, stringResource(R.string.playlist_delete)) { menu = false; actions!!.open(ItemDialog.Delete(item)) }
            }
            if (inPlaylist) {
                Item(Icons.Filled.Delete, stringResource(R.string.playlist_remove_track)) { menu = false; actions!!.removeFromPlaylist(item.parentId, position!!) }
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(vm: LibraryViewModel, item: DidlObject, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadPlaylists() }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var newTitle by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.PlaylistAdd, null) },
        title = { Text(stringResource(R.string.playlist_add_title, item.title)) },
        text = {
            Column {
                val list = playlists
                if (list == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(list, key = { it.id }) { p ->
                            ListItem(
                                headlineContent = { Text(p.title) },
                                leadingContent = { Icon(AppIcons.Playlist, null) },
                                modifier = Modifier.clickable { vm.addToPlaylist(item, p.id, null); onDismiss() },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newTitle, onValueChange = { newTitle = it }, singleLine = true,
                    label = { Text(stringResource(R.string.playlist_new)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = newTitle.isNotBlank(), onClick = { vm.addToPlaylist(item, null, newTitle); onDismiss() }) {
                Text(stringResource(R.string.playlist_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RenameDialog(playlist: DidlObject, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable(playlist.id) { mutableStateOf(playlist.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Edit, null) },
        title = { Text(stringResource(R.string.playlist_rename)) },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && title != playlist.title, onClick = { onRename(title) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
