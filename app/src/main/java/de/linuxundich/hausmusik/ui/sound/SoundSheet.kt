// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.sound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.ui.common.isWide
import de.linuxundich.hausmusik.ui.common.sheetDragHandle
import de.linuxundich.hausmusik.ui.common.sheetMaxWidth
import de.linuxundich.hausmusik.data.SoundSettings
import de.linuxundich.hausmusik.sonos.SonosPlayer
import de.linuxundich.hausmusik.ui.theme.CoverColors

data class SoundActions(
    val load: suspend (SonosPlayer) -> Result<SoundSettings>,
    val setBass: (SonosPlayer, Int) -> Unit,
    val setTreble: (SonosPlayer, Int) -> Unit,
    val setLoudness: (SonosPlayer, Boolean) -> Unit,
)

/** Bass, treble and loudness for every speaker of the selected group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSheet(players: List<SonosPlayer>, colors: CoverColors, actions: SoundActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f), contentColor = colors.content,
        sheetMaxWidth = sheetMaxWidth(), dragHandle = sheetDragHandle(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.sound), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (isWide()) 12.dp else 0.dp))
            if (isWide()) {
                // Landscape: speakers side by side, two per row.
                players.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        pair.forEach { p -> Column(Modifier.weight(1f)) { PlayerSound(p, colors, actions) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                players.forEach { p -> PlayerSound(p, colors, actions) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlayerSound(player: SonosPlayer, colors: CoverColors, actions: SoundActions) {
    var sound by remember(player.uuid) { mutableStateOf<SoundSettings?>(null) }
    LaunchedEffect(player.uuid) { sound = actions.load(player).getOrNull() }
    Text(player.roomName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    val s = sound ?: return
    val sliderColors = SliderDefaults.colors(
        thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.subtle,
        // The step dots in the cover colors too, not Material's default blue.
        activeTickColor = colors.onAccent.copy(alpha = 0.6f), inactiveTickColor = colors.content.copy(alpha = 0.35f),
    )
    ToneSlider(stringResource(R.string.sound_bass), s.bass, sliderColors) { v ->
        sound = s.copy(bass = v)
        actions.setBass(player, v)
    }
    ToneSlider(stringResource(R.string.sound_treble), s.treble, sliderColors) { v ->
        sound = s.copy(treble = v)
        actions.setTreble(player, v)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sound_loudness), Modifier.weight(1f))
        Switch(
            checked = s.loudness,
            onCheckedChange = { sound = s.copy(loudness = it); actions.setLoudness(player, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToneSlider(label: String, value: Int, colors: androidx.compose.material3.SliderColors, onChange: (Int) -> Unit) {
    // Tone runs from -10 to +10: the active part of the track starts at 0 in the middle.
    val state = remember { SliderState(value.toFloat(), 19, -10f..10f) }
    // Follow the speaker (another app, a reset); while dragging nothing else changes it.
    LaunchedEffect(value) { state.value = value.toFloat() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp))
        Slider(
            state = state,
            onValueChange = { state.value = it },
            // Sends only when the finger lets go: tone changes are rare and every step is audible.
            onValueChangeFinished = { onChange(Math.round(state.value)) },
            colors = colors,
            track = { SliderDefaults.CenteredTrack(it, colors = colors) },
            modifier = Modifier.weight(1f),
        )
        Text("%+d".format(Math.round(state.value)), Modifier.width(36.dp), fontSize = 13.sp)
    }
}
