// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.ui.common.VolumeSlider
import de.linuxundich.hausmusik.ui.common.isWide
import de.linuxundich.hausmusik.ui.common.sheetDragHandle
import de.linuxundich.hausmusik.ui.common.sheetMaxWidth
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.Household
import de.linuxundich.hausmusik.sonos.SonosPlayer
import de.linuxundich.hausmusik.sonos.SourceKind
import de.linuxundich.hausmusik.sonos.TransportState
import de.linuxundich.hausmusik.ui.common.Cover
import de.linuxundich.hausmusik.ui.theme.AppIcons
import de.linuxundich.hausmusik.ui.theme.CoverColors

data class RoomActions(
    val onSelect: (String) -> Unit,
    /** Drop of one group onto another; returns a message to show, with undo. */
    val onMerge: suspend (GroupState, GroupState) -> (suspend () -> Unit)?,
    val onPlayPause: (String) -> Unit,
    val onGroupVolume: (String, Int) -> Unit,
    val onMemberVolume: (String, SonosPlayer, Int) -> Unit,
    val onMembership: (SonosPlayer, Boolean) -> Unit,
    val onPauseAll: () -> Unit,
    /** Every room plays along with the selected one (except those left out in the settings). */
    val onEverywhere: () -> Unit = {},
)

/**
 * All rooms at a glance. Upper part: every group with its own volume and
 * play button; tapping one shows it on the Stage. Lower part: which rooms
 * play along with the selected room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsSheet(household: Household, colors: CoverColors, actions: RoomActions, snackbar: SnackbarHostState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val container = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = container, contentColor = colors.content,
        sheetMaxWidth = sheetMaxWidth(), dragHandle = sheetDragHandle(),
    ) {
        Box {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(top = if (isWide()) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rooms), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (household.groups.size > 1) {
                    TextButton(onClick = actions.onEverywhere) { Text(stringResource(R.string.everywhere), color = colors.accent) }
                }
                if (household.groups.any { it.isPlaying }) {
                    TextButton(onClick = actions.onPauseAll) { Text(stringResource(R.string.rooms_pause_all), color = colors.accent) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (isWide()) {
                // Landscape: tiles on the left, who plays along on the right.
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.6f).verticalScroll(rememberScrollState())) {
                        RoomTiles(household, colors, actions, snackbar, perRow = 3)
                        Spacer(Modifier.height(16.dp))
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        PlaysWith(household, colors, actions)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RoomTiles(household, colors, actions, snackbar)
                    Spacer(Modifier.height(12.dp))
                    PlaysWith(household, colors, actions)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        de.linuxundich.hausmusik.ui.common.MessageHost(snackbar)
        }
    }
}

/** Which rooms play along with the selected one: a checkbox and a volume per room. */
@Composable
private fun PlaysWith(household: Household, colors: CoverColors, actions: RoomActions) {
    val selected = household.selected ?: return
    Text(
        if (selected.group.members.size > 1) stringResource(R.string.rooms_play_together)
        else stringResource(R.string.room_plays_with, selected.group.coordinator.roomName),
        fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
    )
    Text(stringResource(R.string.rooms_together_hint), fontSize = 12.sp, color = colors.content.copy(alpha = 0.6f))
    Spacer(Modifier.height(4.dp))
    // The leading room too while others play along: unticking it hands the music to them.
    val leaderTickable = selected.group.members.size > 1
    household.allPlayers.filter { leaderTickable || it.uuid != selected.id }.forEach { p ->
        val member = selected.group.members.any { it.uuid == p.uuid }
        val muted = selected.memberMutes[p.uuid] == true
        // Box and name are one target: tapping the name ticks the box too.
        Row(
            Modifier.fillMaxWidth().toggleable(value = member, role = Role.Checkbox, onValueChange = { actions.onMembership(p, it) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = member,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent, uncheckedColor = colors.content.copy(alpha = 0.6f)),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), // the row is the target
            )
            Text(p.roomName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (member && muted) {
                Icon(AppIcons.VolumeOff, contentDescription = stringResource(R.string.volume_muted), tint = colors.accent, modifier = Modifier.padding(end = 8.dp).size(18.dp))
            }
        }
        // As in Sonos' own app: the slider gets the full width below the name, close
        // under it so four rooms and more still fit on the screen. (The group's
        // slider is on its tile above.)
        if (member) {
            ListVolume(selected.memberVolumes[p.uuid] ?: 0, colors, Modifier.padding(start = 14.dp), dimmed = muted) {
                actions.onMemberVolume(selected.id, p, it)
            }
        }
    }
}

/** A room list's slider: full width, its number after it. */
@Composable
private fun ListVolume(value: Int, colors: CoverColors, modifier: Modifier = Modifier, dimmed: Boolean = false, onChange: (Int) -> Unit) {
    VolumeSlider(
        value, colors, modifier.fillMaxWidth().height(36.dp), dimmed = dimmed,
        trailing = {
            Text(
                "$it", fontSize = 14.sp, color = colors.content.copy(alpha = 0.75f), textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp).padding(end = 4.dp),
            )
        },
        onChange = onChange,
    )
}

/**
 * Every group as a tile, two per row (three in landscape). A tap shows the group on the Stage; a
 * long press lifts the tile, and dropping it on another tile lets those rooms
 * play together.
 */
@Composable
private fun RoomTiles(household: Household, colors: CoverColors, actions: RoomActions, snackbar: SnackbarHostState, perRow: Int = 2) {
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var hover by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        household.groups.chunked(perRow).forEach { row ->
            // The row of the lifted tile draws above the rows after it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.zIndex(if (row.any { it.id == dragged }) 1f else 0f)) {
                row.forEach { g ->
                    val isDragged = dragged == g.id
                    GroupTile(
                        g, selected = g.id == household.selected?.id, dropTarget = hover == g.id, lifted = isDragged, colors = colors, actions = actions,
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { bounds[g.id] = it.boundsInRoot() }
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                if (isDragged) {
                                    translationX = offset.x
                                    translationY = offset.y
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                    shadowElevation = 24f
                                    rotationZ = -2f
                                }
                            }
                            .pointerInput(g.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragged = g.id
                                        offset = Offset.Zero
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        offset += delta
                                        val center = bounds[g.id]?.center?.plus(offset)
                                        hover = center?.let { c -> bounds.entries.firstOrNull { it.key != g.id && it.value.contains(c) }?.key }
                                    },
                                    onDragEnd = {
                                        val target = hover?.let { id -> household.groups.firstOrNull { it.id == id } }
                                        dragged = null
                                        hover = null
                                        offset = Offset.Zero
                                        if (target != null) scope.launch {
                                            val undo = actions.onMerge(g, target) ?: return@launch
                                            val result = snackbar.showSnackbar(
                                                resources.getString(R.string.rooms_merged, g.group.displayName, target.group.coordinator.roomName),
                                                actionLabel = resources.getString(R.string.undo),
                                                duration = SnackbarDuration.Long,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) undo()
                                        }
                                    },
                                    onDragCancel = {
                                        dragged = null
                                        hover = null
                                        offset = Offset.Zero
                                    },
                                )
                            },
                    )
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (household.groups.size > 1) {
            Text(stringResource(R.string.rooms_drag_hint), fontSize = 12.sp, color = colors.content.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun GroupTile(
    g: GroupState,
    selected: Boolean,
    dropTarget: Boolean,
    lifted: Boolean,
    colors: CoverColors,
    actions: RoomActions,
    modifier: Modifier,
) {
    Surface(
        onClick = { actions.onSelect(g.id) },
        shape = RoundedCornerShape(20.dp),
        color = when {
            // A lifted tile must hide what it is dragged over.
            lifted -> lerp(colors.backgroundBottom, colors.backgroundTop, 0.6f)
            dropTarget -> colors.accent.copy(alpha = 0.35f)
            selected -> colors.accent.copy(alpha = 0.18f)
            else -> colors.subtle.copy(alpha = 0.08f)
        },
        contentColor = colors.content,
        // The room the stage controls stands out clearly, not only by a lighter fill.
        border = if (dropTarget || selected) BorderStroke(2.dp, colors.accent) else null,
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                de.linuxundich.hausmusik.ui.common.NowPlayingCover(
                    g.artUrl, g.nowPlaying?.takeIf { it.kind == SourceKind.Radio }?.station, Modifier.size(44.dp), corner = 10.dp,
                    placeholderColor = colors.subtle, iconColor = colors.content.copy(alpha = 0.5f), icon = AppIcons.Speaker, small = true,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.onPlayPause(g.id) }, enabled = g.nowPlaying != null) {
                    Icon(
                        if (g.isPlaying) AppIcons.Pause else AppIcons.Play,
                        contentDescription = stringResource(if (g.isPlaying) R.string.pause else R.string.play),
                        tint = if (g.nowPlaying != null) colors.accent else colors.content.copy(alpha = 0.3f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // Two lines: "Arbeitszimmer + Wohnzimmer" must not end in "Wohn…".
            Text(g.group.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val state = when {
                g.nowPlaying == null -> stringResource(R.string.rooms_idle)
                !g.isPlaying -> "${g.nowPlaying.station ?: g.nowPlaying.title} · ${stringResource(R.string.rooms_paused)}"
                else -> listOfNotNull(g.nowPlaying.title, g.nowPlaying.artist).joinToString(" · ")
            }
            Text(state, fontSize = 12.sp, color = colors.content.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val allMuted = g.group.members.isNotEmpty() && g.group.members.all { g.memberMutes[it.uuid] == true }
            // As on the stage: with muted rooms, the volume of the audible ones.
            val volume = de.linuxundich.hausmusik.data.audibleVolume(g.memberVolumes, g.memberMutes) ?: g.groupVolume
            RoomVolume(volume, colors, Modifier.fillMaxWidth(), muted = allMuted, compact = true) { actions.onGroupVolume(g.id, it) }
        }
    }
}

@Composable
private fun RoomVolume(value: Int, colors: CoverColors, modifier: Modifier, muted: Boolean = false, compact: Boolean = false, onChange: (Int) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // A muted room shows it here; un-muting stays in the volume sheet, one tap away from the stage.
        Icon(
            if (muted) AppIcons.VolumeOff else AppIcons.VolumeDown,
            contentDescription = if (muted) stringResource(R.string.volume_muted) else null,
            tint = if (muted) colors.accent else colors.content.copy(alpha = 0.6f),
            modifier = Modifier.size(if (compact) 16.dp else 20.dp),
        )
        VolumeSlider(
            value, colors, Modifier.weight(1f), compact = compact,
            trailing = {
                Text(
                    "$it", fontSize = if (compact) 12.sp else 15.sp, color = colors.content.copy(alpha = 0.7f), textAlign = TextAlign.End,
                    modifier = Modifier.width(if (compact) 28.dp else 40.dp).padding(end = 4.dp),
                )
            },
            onChange = onChange,
        )
    }
}
