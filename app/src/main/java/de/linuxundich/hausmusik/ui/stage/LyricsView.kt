// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.stage

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import de.linuxundich.hausmusik.ui.theme.AppIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.Lyrics
import de.linuxundich.hausmusik.ui.theme.CoverColors
import kotlinx.coroutines.delay

/** Lines fade out at the top and bottom instead of being cut off. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object None : LyricsUi
    data object Failed : LyricsUi
    /** [offsetMs]: how much later than LRCLIB's times the lines run, set by the user for this track. */
    data class Found(val lyrics: Lyrics, val offsetMs: Int = 0, val onOffset: (Int) -> Unit = {}) : LyricsUi
}

/** Position of the group now, extrapolated between polls while it plays. */
@Composable
fun rememberPosition(group: GroupState): Long {
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(group.isPlaying) {
        while (group.isPlaying) {
            clock = System.currentTimeMillis()
            delay(250)
        }
    }
    val base = group.nowPlaying?.positionMs ?: 0
    val elapsed = if (group.isPlaying && group.positionReadAt > 0) (clock - group.positionReadAt).coerceAtLeast(0) else 0
    val duration = group.nowPlaying?.durationMs
    return (base + elapsed).let { if (duration != null && duration > 0) it.coerceAtMost(duration) else it }
}

/** Takes the place of the cover: the current line lights up, a tap on a line jumps there. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsView(group: GroupState, lyrics: LyricsUi, colors: CoverColors, onSeek: (Long) -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
        when (lyrics) {
            LyricsUi.Loading -> LoadingIndicator(color = colors.accent)
            LyricsUi.None, LyricsUi.Failed -> Text(
                stringResource(if (lyrics == LyricsUi.None) R.string.lyrics_none else R.string.lyrics_failed),
                color = colors.content.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
            )
            is LyricsUi.Found -> {
                // Times that do not fit can be swapped for the plain text.
                var plainOnly by rememberSaveable(lyrics.lyrics) { mutableStateOf(false) }
                if (lyrics.lyrics.isSynced && !plainOnly) {
                    SyncedLyrics(group, lyrics, colors, onSeek, onPlain = lyrics.lyrics.plain?.let { { plainOnly = true } })
                } else {
                    Text(
                        lyrics.lyrics.plain ?: lyrics.lyrics.synced.joinToString("\n") { it.text },
                        color = colors.content, fontSize = 17.sp, lineHeight = 26.sp,
                        modifier = Modifier.fillMaxSize().fadingEdges().verticalScroll(rememberScrollState()).padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A tap on a line jumps there; a long press says "this line is being sung now"
 * and shifts all times to match. The shift bar shows while the times look wrong
 * (see [Lyrics.suspect]), a shift is set, or after a long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedLyrics(group: GroupState, found: LyricsUi.Found, colors: CoverColors, onSeek: (Long) -> Unit, onPlain: (() -> Unit)?) {
    val position = rememberPosition(group)
    val lines = found.lyrics.synced
    val offset = found.offsetMs
    val current = lines.indexOfLast { it.timeMs + offset <= position }
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var adjusting by rememberSaveable(found.lyrics) { mutableStateOf(false) }
    LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
    if (found.lyrics.suspect || offset != 0 || adjusting) {
        OffsetBar(found, colors, onPlain)
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f).fadingEdges(), contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(lines) { i, line ->
            val color by animateColorAsState(
                when {
                    i == current -> colors.content
                    i < current -> colors.content.copy(alpha = 0.35f)
                    else -> colors.content.copy(alpha = 0.55f)
                },
                label = "line",
            )
            Text(
                line.text.ifBlank { "♪" },
                color = color,
                fontSize = 22.sp,
                lineHeight = 30.sp,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClick = { onSeek((line.timeMs + offset).coerceAtLeast(0)) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            adjusting = true
                            found.onOffset(roundOffset(position - line.timeMs))
                        },
                        onLongClickLabel = stringResource(R.string.lyrics_sync_here),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
    }
}

/** Shift to 100 ms, within ±10 minutes: a long press is not more precise than that. */
internal fun roundOffset(ms: Long): Int = ((ms.coerceIn(-600_000, 600_000) + if (ms >= 0) 50 else -50) / 100 * 100).toInt()

@Composable
private fun OffsetBar(found: LyricsUi.Found, colors: CoverColors, onPlain: (() -> Unit)?) {
    val offset = found.offsetMs
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.subtle).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (found.lyrics.suspect && offset == 0) {
            Text(
                stringResource(R.string.lyrics_suspect), fontSize = 13.sp, textAlign = TextAlign.Center,
                color = colors.content.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { found.onOffset(offset - STEP_MS) }) {
                Icon(AppIcons.Remove, stringResource(R.string.lyrics_earlier), tint = colors.content)
            }
            val seconds = String.format(java.util.Locale.getDefault(), "%.1f", kotlin.math.abs(offset) / 1000.0)
            Text(
                when {
                    offset > 0 -> stringResource(R.string.lyrics_offset_later, seconds)
                    offset < 0 -> stringResource(R.string.lyrics_offset_earlier, seconds)
                    else -> stringResource(R.string.lyrics_offset_none)
                },
                fontSize = 14.sp, color = colors.content, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = { found.onOffset(offset + STEP_MS) }) {
                Icon(AppIcons.Add, stringResource(R.string.lyrics_later), tint = colors.content)
            }
        }
        Row {
            if (offset != 0) TextButton(onClick = { found.onOffset(0) }) { Text(stringResource(R.string.lyrics_offset_reset), color = colors.accent) }
            if (onPlain != null) TextButton(onClick = onPlain) { Text(stringResource(R.string.lyrics_plain), color = colors.accent) }
        }
    }
}

private const val STEP_MS = 500
