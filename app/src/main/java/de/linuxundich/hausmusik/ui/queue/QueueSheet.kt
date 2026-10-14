// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import de.linuxundich.hausmusik.ui.theme.AppIcons
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
import de.linuxundich.hausmusik.ui.common.menuColors
import de.linuxundich.hausmusik.ui.common.AppMenu
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import de.linuxundich.hausmusik.ui.common.isWide
import de.linuxundich.hausmusik.ui.common.sheetDragHandle
import de.linuxundich.hausmusik.ui.common.sheetMaxWidth
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
    /** Moves the track at [from] so that it ends up at position [to] (both 1-based). */
    val move: suspend (from: Int, to: Int) -> Result<Unit>,
    val moveToNext: suspend (Int) -> Result<Unit>,
    val clear: suspend () -> Result<Unit>,
    val save: suspend (String) -> Result<Unit>,
)

/**
 * The queue of the selected room. Tap plays, swipe to the left removes, a long
 * press offers "play next". The handle on the right reorders by dragging.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun QueueSheet(
    group: GroupState,
    colors: CoverColors,
    artUrl: (String?) -> String?,
    actions: QueueActions,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf<List<DidlObject>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(group.id, group.queueLength, version) { items = actions.load().getOrDefault(emptyList()) }
    fun edit(op: suspend () -> Result<Unit>) = scope.launch { op(); version++ }
    // Removing waits for the undo: the row disappears at once, Sonos only hears of it
    // when the message is gone (or the sheet closes). A removed streaming track could
    // not be put back with its service data otherwise.
    var hidden by remember { mutableStateOf(setOf<String>()) }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    // Queue IDs are positions ("Q:0/5") and shift; the URI and title stay with the track.
    fun identity(t: DidlObject) = "${t.uri}|${t.title}"
    fun removeWithUndo(t: DidlObject) {
        val key = identity(t)
        // A second swipe ends the first one's wait, so the two never overlap for long.
        snackbar.currentSnackbarData?.dismiss()
        hidden = hidden + key
        scope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar(
                    res.getString(R.string.queue_removed, t.title), res.getString(R.string.undo),
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                ) == androidx.compose.material3.SnackbarResult.ActionPerformed
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    // Where it is now: an earlier removal may have moved it up.
                    val position = items?.indexOfFirst { identity(it) == key }?.takeIf { it >= 0 }?.plus(1)
                    if (!undone && position != null) actions.remove(position)
                    hidden = hidden - key
                    version++
                }
            }
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf<String?>(null) }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = group.nowPlaying?.queuePosition.takeIf { group.nowPlaying?.kind == SourceKind.Queue }
    val list = rememberLazyListState()
    LaunchedEffect(items != null) { if (current != null && current > 2) list.scrollToItem(current - 2) }
    val container = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = container, contentColor = colors.content,
        // A list reads badly across 920 dp: title left, length far right.
        sheetMaxWidth = if (isWide()) 640.dp else sheetMaxWidth(), dragHandle = sheetDragHandle(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = if (isWide()) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.queue_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val n = items?.size ?: group.queueLength
                Text(pluralStringResource(R.plurals.queue_count, n, n), fontSize = 12.sp, color = colors.content.copy(alpha = 0.6f))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !items.isNullOrEmpty()) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.queue_more), tint = colors.content)
                }
                AppMenu(menuOpen, { menuOpen = false }, menuColors(colors)) {
                    Item(AppIcons.Save, stringResource(R.string.queue_save)) { menuOpen = false; saveName = "" }
                    Item(AppIcons.DeleteSweep, stringResource(R.string.queue_clear)) { menuOpen = false; confirmClear = true }
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
        // Dragging by the handle reorders a local copy; Sonos gets one move on drop.
        var order by remember(loaded) { mutableStateOf(loaded.mapIndexed { i, t -> (i + 1) to t }) }
        var dragKey by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        // The dragged row swaps places with the row under its center.
        fun reorderUnderFinger(nr: Int) {
            val items = list.layoutInfo.visibleItemsInfo
            val from = order.indexOfFirst { it.first == nr }
            val me = items.firstOrNull { it.index == from } ?: return
            val center = me.offset + dragOffset + me.size / 2
            val target = items.firstOrNull { it.index != from && center.toInt() in it.offset..(it.offset + it.size) } ?: return
            order = order.toMutableList().apply { add(target.index, removeAt(from)) }
            dragOffset += me.offset - target.offset
        }
        // Near the top or bottom edge the list scrolls by itself, faster the closer
        // the row gets, so a track can travel further than one screen.
        val density = androidx.compose.ui.platform.LocalDensity.current
        // The list runs on below the screen edge (under the gesture bar): measure its
        // visible bottom from the window instead of trusting the viewport.
        var listTop by remember { mutableFloatStateOf(0f) }
        val windowHeight = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
        val navBar = WindowInsets.navigationBars.getBottom(density)
        LaunchedEffect(dragKey) {
            val nr = dragKey ?: return@LaunchedEffect
            val edge = with(density) { 64.dp.toPx() }
            val maxStep = with(density) { 12.dp.toPx() }
            while (true) {
                androidx.compose.runtime.withFrameNanos { }
                val info = list.layoutInfo
                val me = info.visibleItemsInfo.firstOrNull { it.index == order.indexOfFirst { o -> o.first == nr } } ?: continue
                val top = me.offset + dragOffset
                val bottom = top + me.size
                val visibleEnd = minOf(info.viewportEndOffset.toFloat(), windowHeight - navBar - listTop)
                val step = when {
                    top < info.viewportStartOffset + edge -> -maxStep * ((info.viewportStartOffset + edge - top) / edge).coerceIn(0f, 1f)
                    bottom > visibleEnd - edge -> maxStep * ((bottom - visibleEnd + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (step == 0f) continue
                val used = list.scrollBy(step)
                // The row moved with the list; keep it under the finger.
                dragOffset += used
                reorderUnderFinger(nr)
            }
        }
        Box {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { listTop = it.positionInWindow().y },
        ) {
            itemsIndexed(order, key = { _, (nr, t) -> "$version/$nr/${t.id}" }) { index, (nr, t) ->
                // Waiting for its undo: keeps its place (positions stay right), shows nothing.
                if (identity(t) in hidden) return@itemsIndexed
                val dragging = dragKey == nr
                QueueRow(
                    t, index + 1, playing = nr == current, colors, artUrl,
                    onClick = { actions.playTrack(index + 1) },
                    onRemove = { removeWithUndo(t) },
                    onPlayNext = { edit { actions.moveToNext(index + 1) } },
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { if (dragging) { translationY = dragOffset; shadowElevation = 16f } },
                    handle = Modifier.pointerInput(nr) {
                        detectDragGestures(
                            onDragStart = { dragKey = nr; dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                reorderUnderFinger(nr)
                            },
                            onDragEnd = {
                                val from = nr
                                val to = order.indexOfFirst { it.first == nr } + 1
                                dragKey = null
                                dragOffset = 0f
                                if (from != to) edit { actions.move(from, to) }
                            },
                            onDragCancel = {
                                dragKey = null
                                dragOffset = 0f
                                order = loaded.mapIndexed { i, t -> (i + 1) to t }
                            },
                        )
                    },
                )
            }
        }
        de.linuxundich.hausmusik.ui.common.MessageHost(snackbar)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(AppIcons.DeleteSweep, null) },
            title = { Text(stringResource(R.string.queue_clear)) },
            text = { Text(stringResource(R.string.queue_clear_confirm, group.group.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmClear = false; edit { actions.clear() } },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    saveName?.let { name ->
        AlertDialog(
            onDismissRequest = { saveName = null },
            icon = { Icon(AppIcons.Save, null) },
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
    modifier: Modifier = Modifier,
    handle: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val dismiss = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismiss,
        modifier = modifier,
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
                // The local library of an S1 gives no length; nothing beats a dash there.
                t.durationMs?.let { Text(formatTime(it), fontSize = 12.sp, color = colors.content.copy(alpha = 0.5f)) }
                Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.DragHandle, stringResource(R.string.queue_move), tint = colors.content.copy(alpha = 0.6f))
                }
            }
            AppMenu(menu, { menu = false }, menuColors(colors)) {
                Item(AppIcons.PlayNext, stringResource(R.string.queue_play_next), enabled = !playing) { menu = false; onPlayNext() }
                val details = de.linuxundich.hausmusik.ui.details.LocalItemDetails.current
                val target = remember(t) { de.linuxundich.hausmusik.ui.details.DetailsTarget.of(t) }
                if (details != null && target != null) Item(AppIcons.Info, stringResource(R.string.details)) { menu = false; details(target) }
                Item(Icons.Filled.Delete, stringResource(R.string.queue_remove)) { menu = false; onRemove() }
            }
        }
    }
}
