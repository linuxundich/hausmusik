// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.stage

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.sonos.RepeatMode
import de.linuxundich.hausmusik.sonos.SourceKind
import de.linuxundich.hausmusik.ui.common.Cover
import de.linuxundich.hausmusik.ui.common.formatTime
import de.linuxundich.hausmusik.ui.theme.AppIcons
import de.linuxundich.hausmusik.ui.theme.CoverColors

data class StageActions(
    val onRooms: () -> Unit,
    val onLibrary: () -> Unit,
    val onQueue: () -> Unit,
    val onTimer: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onVolume: (Int) -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    val onAlarms: () -> Unit,
    val onSound: () -> Unit,
    val onSettings: () -> Unit,
    val onLyrics: () -> Unit,
    /** "23 min" while a sleep timer runs. */
    val timerLabel: String? = null,
)

/** The Stage: what plays in the selected room, large and in the cover's colors. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StageScreen(
    group: GroupState,
    colors: CoverColors,
    actions: StageActions,
    lyrics: LyricsUi? = null,
) {
    val swipeThreshold = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -swipeThreshold) actions.onLibrary() },
                ) { _, dy -> total += dy }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (maxWidth > maxHeight) {
                // Landscape: cover on the left, everything else on the right.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val square = Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)
                    if (lyrics != null) LyricsView(group, lyrics, colors, actions.onSeek, square) else StageCover(group, colors, square)
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { RoomChip(group.group.displayName, colors, actions.onRooms) }
                            IconAction(AppIcons.Queue, stringResource(R.string.queue), colors, actions.onQueue)
                            IconAction(AppIcons.Library, stringResource(R.string.library), colors, actions.onLibrary)
                            IconAction(AppIcons.Lyrics, stringResource(R.string.lyrics), colors, actions.onLyrics, on = lyrics != null)
                            IconAction(AppIcons.Timer, actions.timerLabel ?: stringResource(R.string.sleep_timer), colors, actions.onTimer)
                            StageMenu(colors, actions)
                        }
                        Spacer(Modifier.weight(1f))
                        StageControls(group, colors, actions)
                        Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RoomChip(group.group.displayName, colors, actions.onRooms)
                        Box(Modifier.align(Alignment.CenterEnd).padding(top = 8.dp)) { StageMenu(colors, actions) }
                    }
                    Spacer(Modifier.weight(0.4f))
                    val square = Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f)
                    if (lyrics != null) LyricsView(group, lyrics, colors, actions.onSeek, square) else StageCover(group, colors, square)
                    Spacer(Modifier.height(24.dp))
                    StageControls(group, colors, actions)
                    Spacer(Modifier.weight(0.6f))
                    BottomActions(colors, actions, lyricsShown = lyrics != null)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun StageCover(group: GroupState, colors: CoverColors, modifier: Modifier) {
    Cover(
        url = group.artUrl,
        modifier = modifier.shadow(24.dp, RoundedCornerShape(24.dp)),
        corner = 24.dp,
        placeholderColor = colors.subtle,
        iconColor = colors.content.copy(alpha = 0.5f),
        icon = if (group.nowPlaying?.kind == SourceKind.Radio) AppIcons.Radio else AppIcons.Speaker,
    )
}

@Composable
private fun StageControls(group: GroupState, colors: CoverColors, actions: StageActions) {
    val now = group.nowPlaying
    Column(Modifier.fillMaxWidth()) {
        Text(
            now?.title ?: stringResource(R.string.nothing_playing),
            color = colors.content, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val sub = when {
            now == null -> stringResource(R.string.queue_empty_hint)
            now.kind == SourceKind.Radio -> listOfNotNull(now.station, now.artist).joinToString(" · ")
            else -> listOfNotNull(now.artist, now.album).joinToString(" · ")
        }
        Text(sub, color = colors.content.copy(alpha = 0.72f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        if (now?.durationMs != null && now.durationMs!! > 0) {
            Progress(group, colors, actions.onSeek)
        } else {
            Spacer(Modifier.height(36.dp))
        }
        Spacer(Modifier.height(8.dp))
        Transport(group, colors, actions)
        Spacer(Modifier.height(12.dp))
        VolumeRow(group.groupVolume, colors, actions.onVolume)
    }
}

@Composable
private fun StageMenu(colors: CoverColors, actions: StageActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.queue_more), tint = colors.content.copy(alpha = 0.85f)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.alarms)) }, onClick = { open = false; actions.onAlarms() })
            DropdownMenuItem(text = { Text(stringResource(R.string.sound)) }, onClick = { open = false; actions.onSound() })
            DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { open = false; actions.onSettings() })
        }
    }
}

@Composable
private fun IconAction(icon: ImageVector, label: String, colors: CoverColors, onClick: () -> Unit, on: Boolean = false) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = if (on) colors.accent else colors.content.copy(alpha = 0.85f)) }
}

@Composable
private fun BottomActions(colors: CoverColors, actions: StageActions, lyricsShown: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        BottomAction(AppIcons.Queue, stringResource(R.string.queue), colors, actions.onQueue)
        BottomAction(AppIcons.Library, stringResource(R.string.library), colors, actions.onLibrary)
        BottomAction(AppIcons.Lyrics, stringResource(R.string.lyrics), colors, actions.onLyrics, on = lyricsShown)
        BottomAction(AppIcons.Timer, actions.timerLabel ?: stringResource(R.string.sleep_timer), colors, actions.onTimer)
    }
}

@Composable
private fun RoomChip(name: String, colors: CoverColors, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.subtle,
        contentColor = colors.content,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Speaker, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("  ▾", fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Progress(group: GroupState, colors: CoverColors, onSeek: (Long) -> Unit) {
    val now = group.nowPlaying ?: return
    val duration = now.durationMs ?: return
    // Between polls the position is extrapolated so the bar moves smoothly.
    val position = rememberPosition(group)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    val fraction = dragFraction ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    val canSeek = group.actions.canSeek
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(canSeek, duration) {
                    if (!canSeek) return@pointerInput
                    width = size.width.toFloat()
                    detectTapGestures { o -> onSeek((o.x / width * duration).toLong()) }
                }
                .pointerInput(canSeek, duration) {
                    if (!canSeek) return@pointerInput
                    width = size.width.toFloat()
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { onSeek((it * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ -> dragFraction = (change.position.x / width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.Center,
        ) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.subtle,
                amplitude = { if (group.isPlaying) 1f else 0f },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragFraction?.let { (it * duration).toLong() } ?: position
            Text(formatTime(shown), color = colors.content.copy(alpha = 0.6f), fontSize = 12.sp)
            Text(formatTime(duration), color = colors.content.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun Transport(group: GroupState, colors: CoverColors, actions: StageActions) {
    val isRadio = group.nowPlaying?.kind == SourceKind.Radio
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SmallToggle(AppIcons.Shuffle, stringResource(R.string.shuffle), group.playMode.shuffle, colors, enabled = !isRadio, onClick = actions.onShuffle)
        RoundButton(AppIcons.SkipPrevious, stringResource(R.string.previous), colors, enabled = !isRadio, onClick = actions.onPrevious)
        // Expressive: the play button morphs from a circle (paused) to a rounded square (playing).
        val corner by animateDpAsState(if (group.isPlaying) 26.dp else 36.dp, label = "corner")
        Surface(
            onClick = actions.onPlayPause,
            shape = RoundedCornerShape(corner),
            color = colors.accent,
            contentColor = colors.onAccent,
            modifier = Modifier.size(width = 96.dp, height = 72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (group.isPlaying) AppIcons.Pause else AppIcons.Play,
                    contentDescription = stringResource(if (group.isPlaying) R.string.pause else R.string.play),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        RoundButton(AppIcons.SkipNext, stringResource(R.string.next), colors, enabled = group.actions.canNext, onClick = actions.onNext)
        SmallToggle(
            if (group.playMode.repeat == RepeatMode.One) AppIcons.RepeatOne else AppIcons.Repeat,
            stringResource(R.string.repeat), group.playMode.repeat != RepeatMode.Off, colors, enabled = !isRadio, onClick = actions.onRepeat,
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, colors: CoverColors, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, color = colors.subtle,
        contentColor = colors.content.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun SmallToggle(icon: ImageVector, label: String, on: Boolean, colors: CoverColors, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon, contentDescription = label,
            tint = when {
                !enabled -> colors.content.copy(alpha = 0.25f)
                on -> colors.accent
                else -> colors.content.copy(alpha = 0.6f)
            },
        )
    }
}

@Composable
private fun VolumeRow(volume: Int, colors: CoverColors, onVolume: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.VolumeDown, contentDescription = null, tint = colors.content.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        Slider(
            value = dragging ?: volume.toFloat(),
            onValueChange = {
                dragging = it
                onVolume(it.toInt())
            },
            onValueChangeFinished = { dragging = null },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.subtle,
            ),
        )
        Icon(AppIcons.VolumeUp, contentDescription = stringResource(R.string.volume), tint = colors.content.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, colors: CoverColors, onClick: () -> Unit, on: Boolean = false) {
    val tint = if (on) colors.accent else colors.content.copy(alpha = 0.85f)
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(label, color = tint, fontSize = 12.sp)
        }
    }
}

