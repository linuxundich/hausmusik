// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.VideoMode
import de.linuxundich.hausmusik.sonos.NowPlaying
import de.linuxundich.hausmusik.ui.common.formatTime
import de.linuxundich.hausmusik.ui.common.isWide
import de.linuxundich.hausmusik.ui.common.sheetDragHandle
import de.linuxundich.hausmusik.ui.common.sheetMaxWidth
import de.linuxundich.hausmusik.ui.theme.AppIcons
import de.linuxundich.hausmusik.ui.theme.CoverColors

private sealed interface VideoUi {
    data object Loading : VideoUi
    data object None : VideoUi
    data object Failed : VideoUi
    data class Found(val id: String) : VideoUi
}

data class MusicVideoActions(
    val find: suspend (String, String?) -> Result<String?>,
    val onEnable: () -> Unit,
    /** The playing position in ms right now, ticking while music plays. */
    val position: () -> Long,
    /** Opens the video at the given second; with `pause` the speakers stop first. */
    val openVideo: (id: String, startSeconds: Long, pause: Boolean) -> Unit,
    val search: () -> Unit,
)

/**
 * The official video of the playing song, opened in the YouTube app at the
 * point the speakers play. Hausmusik shows no YouTube content itself: no
 * player, no thumbnail (YouTube's terms; and nothing goes to Google until the
 * user taps). Off until the user agrees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicVideoSheet(now: NowPlaying, enabled: Boolean, mode: VideoMode, colors: CoverColors, actions: MusicVideoActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f), contentColor = colors.content,
        sheetMaxWidth = sheetMaxWidth(), dragHandle = sheetDragHandle(),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.music_video), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (isWide()) 12.dp else 0.dp))
            if (!enabled) {
                Text(stringResource(R.string.music_video_consent_body), color = colors.content.copy(alpha = 0.85f))
                Button(onClick = actions.onEnable, colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) {
                    Text(stringResource(R.string.music_video_consent_yes))
                }
            } else {
                val state by produceState<VideoUi>(VideoUi.Loading, now.title, now.artist, attempt) {
                    value = VideoUi.Loading
                    value = actions.find(now.title, now.artist).fold({ if (it == null) VideoUi.None else VideoUi.Found(it) }, { VideoUi.Failed })
                }
                when (val s = state) {
                    VideoUi.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                    VideoUi.Failed -> {
                        Text(stringResource(R.string.music_video_failed), color = colors.content.copy(alpha = 0.8f))
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.details_retry), color = colors.accent) }
                    }
                    VideoUi.None -> NotFound(now, colors, actions)
                    is VideoUi.Found -> Found(now, s.id, mode, colors, actions)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Found(now: NowPlaying, id: String, mode: VideoMode, colors: CoverColors, actions: MusicVideoActions) {
    // The start point follows the music while the sheet is open.
    val position by produceState(actions.position()) {
        while (true) {
            kotlinx.coroutines.delay(500)
            value = actions.position()
        }
    }
    var pause by rememberSaveable(mode) { mutableStateOf(mode == VideoMode.Pause) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(colors.subtle),
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Video, null, tint = colors.accent, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.size(14.dp))
        Column {
            Text(stringResource(R.string.music_video_official, now.title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(now.artist, stringResource(R.string.music_video_source)).joinToString(" · "), fontSize = 13.sp, color = colors.content.copy(alpha = 0.7f))
        }
    }
    // The speakers keep playing: start a little ahead, the YouTube app takes a moment to open.
    val start = if (pause) position / 1000 else position / 1000 + LEAD_S
    val at = formatTime(start * 1000)
    if (mode == VideoMode.Ask) {
        Choice(stringResource(R.string.music_video_keep, at), stringResource(R.string.music_video_keep_hint), !pause, colors) { pause = false }
        Choice(stringResource(R.string.music_video_pause), stringResource(R.string.music_video_pause_hint), pause, colors) { pause = true }
    }
    Button(
        onClick = { actions.openVideo(id, start, pause) },
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(AppIcons.OpenExternal, null, Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(stringResource(R.string.music_video_open, at), fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = actions.search) { Text(stringResource(R.string.music_video_other), color = colors.accent) }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.music_video_premium), fontSize = 12.sp, color = colors.content.copy(alpha = 0.6f))
    }
}

@Composable
private fun NotFound(now: NowPlaying, colors: CoverColors, actions: MusicVideoActions) {
    Text(stringResource(R.string.music_video_none_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.music_video_none_body, now.title, now.artist.orEmpty()), fontSize = 14.sp, color = colors.content.copy(alpha = 0.8f))
    Button(
        onClick = actions.search,
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(AppIcons.OpenExternal, null, Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(stringResource(R.string.music_video_search, listOfNotNull(now.artist, now.title).joinToString(" ")), fontWeight = FontWeight.Bold, maxLines = 1)
    }
    Text(stringResource(R.string.music_video_search_hint), fontSize = 13.sp, color = colors.content.copy(alpha = 0.6f))
}

@Composable
private fun Choice(label: String, hint: String, selected: Boolean, colors: CoverColors, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.content.copy(alpha = 0.6f)))
        Spacer(Modifier.size(12.dp))
        Column {
            Text(label, fontSize = 15.sp)
            Text(hint, fontSize = 12.sp, color = colors.content.copy(alpha = 0.6f))
        }
    }
}

private const val LEAD_S = 2L
