package de.linuxundich.hausmusik.ui.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.SourceKind
import de.linuxundich.hausmusik.ui.common.Cover
import de.linuxundich.hausmusik.ui.common.formatTime
import de.linuxundich.hausmusik.ui.theme.CoverColors
import kotlinx.coroutines.launch

data class QueueActions(
    val load: suspend () -> Result<List<DidlObject>>,
    val playTrack: (Int) -> Unit,
    val remove: suspend (Int) -> Result<Unit>,
    val moveToNext: suspend (Int) -> Result<Unit>,
    val clear: suspend () -> Result<Unit>,
    val save: suspend (String) -> Result<Unit>,
)

/**
 * The queue of the selected room. Tap plays, swipe to the left removes, a long
 * press offers "play next". Dragging to reorder follows later.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun QueueSheet(
    group: GroupState,
    colors: CoverColors,
    artUrl: (String?) -> String?,
    actions: QueueActions,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf<List<DidlObject>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(group.id, group.queueLength, version) { items = actions.load().getOrDefault(emptyList()) }
    fun edit(op: suspend () -> Result<Unit>) = scope.launch { op(); version++ }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf<String?>(null) }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = group.nowPlaying?.queuePosition.takeIf { group.nowPlaying?.kind == SourceKind.Queue }
    val list = rememberLazyListState()
    LaunchedEffect(items != null) { if (current != null && current > 2) list.scrollToItem(current - 2) }
    val container = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = container, contentColor = colors.content) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.queue_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val n = items?.size ?: group.queueLength
                Text(pluralStringResource(R.plurals.queue_count, n, n), fontSize = 12.sp, color = colors.content.copy(alpha = 0.6f))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !items.isNullOrEmpty()) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.queue_more), tint = colors.content)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_save)) }, onClick = { menuOpen = false; saveName = "" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_clear)) }, onClick = { menuOpen = false; confirmClear = true })
                }
            }
        }
        if (group.nowPlaying != null && group.nowPlaying.kind != SourceKind.Queue) {
            Text(stringResource(R.string.queue_not_active), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        val loaded = items
        if (loaded == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { LoadingIndicator(color = colors.accent) }
            return@ModalBottomSheet
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(loaded, key = { i, t -> "$version/$i/${t.id}" }) { i, t ->
                val nr = i + 1
                QueueRow(
                    t, nr, playing = nr == current, colors, artUrl,
                    onClick = { actions.playTrack(nr) },
                    onRemove = { edit { actions.remove(nr) } },
                    onPlayNext = { edit { actions.moveToNext(nr) } },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.queue_clear_confirm, group.group.displayName)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; edit { actions.clear() } }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    saveName?.let { name ->
        AlertDialog(
            onDismissRequest = { saveName = null },
            title = { Text(stringResource(R.string.queue_save)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { saveName = it }, singleLine = true, label = { Text(stringResource(R.string.queue_save_name)) })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { saveName = null; scope.launch { actions.save(name.trim()) } }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { saveName = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    t: DidlObject,
    nr: Int,
    playing: Boolean,
    colors: CoverColors,
    artUrl: (String?) -> String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onPlayNext: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val dismiss = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFB3261E)).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Filled.Delete, stringResource(R.string.queue_remove), tint = Color.White) }
        },
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (playing) lerp(colors.backgroundBottom, colors.accent, 0.25f) else lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f))
                    .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (playing) "▶" else "$nr", modifier = Modifier.width(28.dp), fontSize = 12.sp,
                    color = if (playing) colors.accent else colors.content.copy(alpha = 0.5f),
                )
                Cover(artUrl(t.albumArtUri), Modifier.size(40.dp), corner = 8.dp, placeholderColor = colors.subtle, iconColor = colors.content.copy(alpha = 0.4f))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.creator.orEmpty(), fontSize = 12.sp, color = colors.content.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(formatTime(t.durationMs), fontSize = 12.sp, color = colors.content.copy(alpha = 0.5f))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, enabled = !playing, onClick = { menu = false; onPlayNext() })
                DropdownMenuItem(text = { Text(stringResource(R.string.queue_remove)) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}
