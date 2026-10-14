// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.stage

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import de.linuxundich.hausmusik.ui.common.AppMenu
import de.linuxundich.hausmusik.ui.common.menuColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
    /** Background on the playing artist and album. */
    val onDetails: () -> Unit = {},
    /** Crossfade between tracks, a switch in the menu. */
    val crossfadeOn: Boolean = false,
    /** The song's music video in the YouTube app. */
    val onMusicVideo: () -> Unit = {},
    val onCrossfade: () -> Unit = {},
    /** "23 min" while a sleep timer runs. */
    val timerLabel: String? = null,
    /** The speaker icon left of the slider: volume of each room. */
    val onRoomVolumes: () -> Unit = {},
    /** Mutes or unmutes a single room (not used for groups: see VolumeRow). */
    val onMute: (Boolean) -> Unit = {},
)

/** Commands the speakers have not confirmed yet: the play button, or anything else. */
data class StageBusy(val play: Boolean = false, val other: Boolean = false)

/**
 * True once [flag] has been set for [after] ms: quick answers show nothing,
 * so the indicators do not flicker on every tap.
 */
@Composable
private fun delayed(flag: Boolean, after: Long = 300): Boolean {
    val shown by produceState(false, flag) {
        if (flag) {
            kotlinx.coroutines.delay(after)
            value = true
        } else {
            value = false
        }
    }
    return shown
}

/** A short wave under the room while the speakers have not answered yet. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WaitingWave(visible: Boolean, colors: CoverColors, modifier: Modifier = Modifier) {
    // Always takes its height, so nothing jumps when it appears.
    Box(modifier.size(width = 160.dp, height = 10.dp), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(visible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = colors.accent,
                trackColor = Color.Transparent,
            )
        }
    }
}

/** The Stage: what plays in the selected room, large and in the cover's colors. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StageScreen(
    group: GroupState,
    colors: CoverColors,
    actions: StageActions,
    lyrics: LyricsUi? = null,
    busy: StageBusy = StageBusy(),
    effects: de.linuxundich.hausmusik.data.Effects = de.linuxundich.hausmusik.data.Effects(),
) {
    // The playful extras, each switchable, and all still with the system's "Remove animations".
    val still = rememberAnimationsOff()
    val lightOn = effects.stageLight && !still
    val lit = rememberStageLit(group.isPlaying)
    val house = if (lightOn) 1f - lit else 0f
    val tempo = if (effects.tapAlong && !still) remember(group.nowPlaying?.title, group.nowPlaying?.artist) { TapTempo() } else null
    val pulse = rememberPulse(tempo?.beatMs ?: 0L, group.isPlaying)
    var lastTap by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val haptic = rememberTapHaptic()
    val fx = CoverFx(
        tempo = tempo, pulse = pulse, lastTap = lastTap,
        turntable = effects.turntable && !still && turntableFits(group.nowPlaying?.kind),
        onTap = { lastTap = System.currentTimeMillis(); haptic() },
    )
    val playBusy = delayed(busy.play)
    val otherBusy = delayed(busy.other)
    val swipeThreshold = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(lerp(colors.backgroundTop, HouseTop, house), lerp(colors.backgroundBottom, HouseBottom, house))))
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -swipeThreshold) actions.onLibrary() },
                ) { _, dy -> total += dy }
            },
    ) {
        if (lightOn) StageLights(colors, lit, pulse)
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (maxWidth > maxHeight) {
                // Landscape: cover on the left, everything else on the right.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Lyrics take half the width (longer lines), the cover stays square.
                    if (lyrics != null) {
                        LyricsView(group, lyrics, colors, actions.onSeek, Modifier.fillMaxHeight().weight(0.85f))
                    } else {
                        StageCover(group, colors, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true), fx)
                    }
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                RoomChip(group.group.displayName, colors, actions.onRooms)
                                WaitingWave(otherBusy, colors, Modifier.padding(start = 16.dp, top = 4.dp))
                            }
                            IconAction(AppIcons.Queue, stringResource(R.string.queue), colors, actions.onQueue)
                            IconAction(AppIcons.Library, stringResource(R.string.library), colors, actions.onLibrary)
                            IconAction(AppIcons.Lyrics, stringResource(R.string.lyrics), colors, actions.onLyrics, on = lyrics != null)
                            if (actions.timerLabel != null) {
                                // A running timer shows its time left, as in portrait.
                                TextButton(onClick = actions.onTimer) {
                                    Icon(AppIcons.Timer, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                                    Text(actions.timerLabel, color = colors.accent, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                                }
                            } else {
                                IconAction(AppIcons.Timer, stringResource(R.string.sleep_timer), colors, actions.onTimer)
                            }
                            StageMenu(colors, actions)
                        }
                        Spacer(Modifier.weight(1f))
                        StageControls(group, colors, actions, playBusy)
                        Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // The menu sits on the right: keep its width free on both sides, so a long
                        // room name ("Arbeitszimmer + Wohnzimmer") is shortened instead of running under it.
                        Column(Modifier.padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            RoomChip(group.group.displayName, colors, actions.onRooms)
                            WaitingWave(otherBusy, colors, Modifier.padding(top = 4.dp))
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp)) { StageMenu(colors, actions) }
                    }
                    Spacer(Modifier.weight(0.4f))
                    val square = Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f)
                    if (lyrics != null) LyricsView(group, lyrics, colors, actions.onSeek, square) else StageCover(group, colors, square, fx)
                    Spacer(Modifier.height(24.dp))
                    StageControls(group, colors, actions, playBusy)
                    Spacer(Modifier.weight(0.6f))
                    BottomActions(colors, actions, lyricsShown = lyrics != null)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** What the cover does beyond showing itself: see StageEffects.kt. */
private data class CoverFx(
    val tempo: TapTempo? = null,
    val pulse: Float = 0f,
    val lastTap: Long = 0L,
    val turntable: Boolean = false,
    val onTap: () -> Unit = {},
)

@Composable
private fun StageCover(group: GroupState, colors: CoverColors, modifier: Modifier, fx: CoverFx = CoverFx()) {
    val icon = if (group.nowPlaying?.kind == SourceKind.Radio) AppIcons.Radio else AppIcons.Speaker
    // A station without a logo: its name on a color of its own, not the same radio for every station.
    val station = group.nowPlaying?.takeIf { it.kind == SourceKind.Radio && group.artUrl == null }?.station
    val cover = @Composable { m: Modifier ->
        if (station != null) {
            StationPlaceholder(station, m.tapAlong(fx.tempo, fx.onTap).shadow(24.dp, RoundedCornerShape(24.dp)), 24.dp)
        } else Cover(
            url = group.artUrl,
            modifier = m.tapAlong(fx.tempo, fx.onTap)
                .graphicsLayer { val s = 1f + 0.03f * fx.pulse; scaleX = s; scaleY = s }
                .shadow(24.dp, RoundedCornerShape(24.dp)),
            corner = 24.dp,
            placeholderColor = colors.subtle,
            iconColor = colors.content.copy(alpha = 0.5f),
            icon = icon,
            keepPrevious = true,
        )
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (fx.turntable) {
            CoverOnTurntable(
                group.isPlaying, colors, Modifier.fillMaxSize(),
                label = { m -> Cover(group.artUrl, m, corner = 200.dp, placeholderColor = colors.accent, iconColor = colors.onAccent, icon = icon) },
                cover = cover,
            )
        } else {
            cover(Modifier.fillMaxSize())
        }
        if (fx.tempo != null) TapNote(fx.tempo, fx.lastTap, colors, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}

@Composable
private fun StageControls(group: GroupState, colors: CoverColors, actions: StageActions, playBusy: Boolean, compact: Boolean = false) {
    val now = group.nowPlaying
    Column(Modifier.fillMaxWidth()) {
        Text(
            now?.title ?: stringResource(R.string.nothing_playing),
            color = colors.content, fontSize = if (compact) 19.sp else 24.sp, fontWeight = FontWeight.Bold,
            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
        )
        val sub = when {
            now == null -> stringResource(R.string.queue_empty_hint)
            now.kind == SourceKind.Radio -> listOfNotNull(now.station, now.artist).joinToString(" · ")
            else -> listOfNotNull(now.artist, now.album).joinToString(" · ")
        }
        Text(
            sub, color = colors.content.copy(alpha = 0.72f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            // Artist and album open their background info; a radio station has none.
            modifier = if (now != null && now.kind != SourceKind.Radio && (now.artist != null || now.album != null)) {
                Modifier.clickable(onClickLabel = stringResource(R.string.details), onClick = actions.onDetails)
            } else Modifier,
        )
        Spacer(Modifier.height(if (compact) 6.dp else 16.dp))
        if (now?.durationMs != null && now.durationMs!! > 0) {
            Progress(group, colors, actions.onSeek)
        } else {
            Spacer(Modifier.height(if (compact) 12.dp else 36.dp))
        }
        Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
        Transport(group, colors, actions, playBusy)
        Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
        VolumeRow(group, colors, actions)
    }
}

@Composable
private fun StageMenu(colors: CoverColors, actions: StageActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.queue_more), tint = colors.content.copy(alpha = 0.85f)) }
        // Grouped by what they act on: the track, the room, the app.
        AppMenu(open, { open = false }, menuColors(colors)) {
            Header(stringResource(R.string.menu_track))
            Item(AppIcons.Info, stringResource(R.string.details)) { open = false; actions.onDetails() }
            Item(AppIcons.Video, stringResource(R.string.music_video)) { open = false; actions.onMusicVideo() }
            Header(stringResource(R.string.menu_room))
            Item(AppIcons.Tune, stringResource(R.string.sound)) { open = false; actions.onSound() }
            Toggle(AppIcons.Crossfade, stringResource(R.string.crossfade), actions.crossfadeOn) { open = false; actions.onCrossfade() }
            Header(stringResource(R.string.menu_app))
            Item(AppIcons.Alarm, stringResource(R.string.alarms)) { open = false; actions.onAlarms() }
            Item(Icons.Filled.Settings, stringResource(R.string.settings)) { open = false; actions.onSettings() }
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
            // The arrow stays on the line even when a long name is cut.
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("  ▾", fontSize = 12.sp, maxLines = 1)
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Transport(group: GroupState, colors: CoverColors, actions: StageActions, playBusy: Boolean) {
    val waitingLabel = stringResource(R.string.waiting_for_speakers)
    val isRadio = group.nowPlaying?.kind == SourceKind.Radio
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        // Radio has no shuffle, repeat or tracks to skip: those buttons give way (their
        // places stay, so play keeps the middle) instead of standing there greyed out.
        if (isRadio) Spacer(Modifier.size(48.dp))
        else SmallToggle(AppIcons.Shuffle, stringResource(R.string.shuffle), group.playMode.shuffle, colors, enabled = true, onClick = actions.onShuffle)
        // The pressed skip button waits until the next title is there, not only the room chip.
        var skipping by remember(group.nowPlaying?.title) { mutableStateOf<Int?>(null) }
        LaunchedEffect(skipping) { if (skipping != null) { kotlinx.coroutines.delay(10_000); skipping = null } }
        if (isRadio) Spacer(Modifier.size(56.dp))
        else RoundButton(AppIcons.SkipPrevious, stringResource(R.string.previous), colors, enabled = true, busy = skipping == -1) { skipping = -1; actions.onPrevious() }
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
                // Waiting for the speakers: the morphing indicator takes the icon's place.
                if (playBusy) {
                    LoadingIndicator(
                        Modifier.size(44.dp).semantics { contentDescription = waitingLabel },
                        color = colors.onAccent,
                    )
                } else {
                    Icon(
                        if (group.isPlaying) AppIcons.Pause else AppIcons.Play,
                        contentDescription = stringResource(if (group.isPlaying) R.string.pause else R.string.play),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
        if (isRadio) Spacer(Modifier.size(56.dp))
        else RoundButton(AppIcons.SkipNext, stringResource(R.string.next), colors, enabled = group.actions.canNext, busy = skipping == 1) { skipping = 1; actions.onNext() }
        if (isRadio) Spacer(Modifier.size(48.dp))
        else SmallToggle(
            if (group.playMode.repeat == RepeatMode.One) AppIcons.RepeatOne else AppIcons.Repeat,
            stringResource(R.string.repeat), group.playMode.repeat != RepeatMode.Off, colors, enabled = true, onClick = actions.onRepeat,
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, colors: CoverColors, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, color = colors.subtle,
        contentColor = colors.content.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
            // A ring around the icon while the speakers have not changed the title yet.
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(52.dp), color = colors.accent, strokeWidth = 2.5.dp)
        }
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
private fun VolumeRow(group: GroupState, colors: CoverColors, actions: StageActions) {
    val members = group.group.members
    val single = members.size == 1
    val muted = members.filter { group.memberMutes[it.uuid] == true }
    val allMuted = muted.size == members.size
    // With muted rooms in the group: the volume of the audible ones, as the slider moves them.
    val volume = de.linuxundich.hausmusik.data.audibleVolume(group.memberVolumes, group.memberMutes) ?: group.groupVolume
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.toInt() ?: volume
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (single) {
                // One tap mutes, as on any player; the state shows in the icon.
                val name = group.group.coordinator.roomName
                IconButton(onClick = { actions.onMute(!allMuted) }) {
                    Icon(
                        if (allMuted) AppIcons.VolumeOff else AppIcons.VolumeUp,
                        contentDescription = stringResource(if (allMuted) R.string.volume_unmute else R.string.volume_mute, name),
                        tint = if (allMuted) colors.accent else colors.content.copy(alpha = 0.85f), modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                // A group: the rooms and their volumes. No group mute here: unmuting would
                // wake a room muted on purpose.
                IconButton(onClick = actions.onRoomVolumes) {
                    Icon(
                        if (allMuted) AppIcons.VolumeOff else AppIcons.Speaker, contentDescription = stringResource(R.string.volume_rooms),
                        tint = if (muted.isNotEmpty()) colors.accent else colors.content.copy(alpha = 0.85f), modifier = Modifier.size(22.dp),
                    )
                }
            }
            Slider(
                value = dragging ?: volume.toFloat(),
                onValueChange = {
                    dragging = it
                    actions.onVolume(it.toInt())
                },
                onValueChangeFinished = { dragging = null },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).alpha(if (allMuted) 0.4f else 1f),
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.subtle,
                ),
            )
            Text(
                "$shown", color = colors.content.copy(alpha = if (allMuted) 0.45f else 0.85f), fontSize = 15.sp,
                textAlign = TextAlign.End, modifier = Modifier.width(34.dp),
            )
        }
        // Silence must not look like music: say which rooms are muted, and undo it in one tap.
        if (muted.isNotEmpty()) {
            val text = when {
                single -> stringResource(R.string.stage_muted_single, members.first().roomName)
                allMuted -> stringResource(R.string.stage_muted_all)
                else -> pluralStringResource(R.plurals.stage_muted_some, muted.size, muted.joinToString(", ") { it.roomName })
            }
            Text(
                text, color = colors.accent, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { if (single) actions.onMute(false) else actions.onRoomVolumes() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                textAlign = TextAlign.Center,
            )
        }
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


/**
 * The stage beside the library (wide landscape): room, a small cover and the
 * full controls in a slim column. Scrolls when the height is short.
 */
@Composable
fun StageSidePanel(group: GroupState, colors: CoverColors, actions: StageActions, busy: StageBusy = StageBusy()) {
    val playBusy = delayed(busy.play)
    val otherBusy = delayed(busy.other)
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom))),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val station = group.nowPlaying?.takeIf { it.kind == SourceKind.Radio && group.artUrl == null }?.station
                if (station != null) StationPlaceholder(station, Modifier.size(64.dp).shadow(12.dp, RoundedCornerShape(14.dp)), 14.dp, small = true)
                else Cover(
                    url = group.artUrl, modifier = Modifier.size(64.dp).shadow(12.dp, RoundedCornerShape(14.dp)), corner = 14.dp,
                    placeholderColor = colors.subtle, iconColor = colors.content.copy(alpha = 0.5f),
                    icon = if (group.nowPlaying?.kind == SourceKind.Radio) AppIcons.Radio else AppIcons.Speaker, keepPrevious = true,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    RoomChip(group.group.displayName, colors, actions.onRooms)
                    WaitingWave(otherBusy, colors, Modifier.padding(start = 16.dp, top = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            StageControls(group, colors, actions, playBusy, compact = true)
        }
    }
}

/**
 * A station without a logo: its name, large, on a gradient in a color drawn
 * from the name, so each station looks like itself. A small one shows the
 * initials only.
 */
@Composable
fun StationPlaceholder(name: String, modifier: Modifier, corner: androidx.compose.ui.unit.Dp, small: Boolean = false) {
    val hue = (Math.floorMod(name.trim().lowercase().hashCode(), 360)).toFloat()
    val top = Color.hsv(hue, 0.55f, 0.62f)
    val bottom = Color.hsv((hue + 35f) % 360f, 0.65f, 0.32f)
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        if (small) {
            val initials = name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
            Text(initials, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(AppIcons.Radio, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    name, color = Color.White, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
