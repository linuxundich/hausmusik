package de.linuxundich.hausmusik.ui.stage

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
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

sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object None : LyricsUi
    data object Failed : LyricsUi
    data class Found(val lyrics: Lyrics) : LyricsUi
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
            is LyricsUi.Found -> if (lyrics.lyrics.isSynced) {
                SyncedLyrics(group, lyrics.lyrics, colors, onSeek)
            } else {
                Text(
                    lyrics.lyrics.plain.orEmpty(), color = colors.content, fontSize = 17.sp, lineHeight = 26.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(group: GroupState, lyrics: Lyrics, colors: CoverColors, onSeek: (Long) -> Unit) {
    val position = rememberPosition(group)
    val lines = lyrics.synced
    val current = lines.indexOfLast { it.timeMs <= position }
    val list = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
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
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSeek(line.timeMs) }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
