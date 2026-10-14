// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.sound

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.sonos.SonosPlayer
import de.linuxundich.hausmusik.ui.common.MessageHost
import de.linuxundich.hausmusik.ui.common.isWide
import de.linuxundich.hausmusik.ui.common.sheetDragHandle
import de.linuxundich.hausmusik.ui.common.sheetMaxWidth
import de.linuxundich.hausmusik.ui.theme.AppIcons
import de.linuxundich.hausmusik.ui.theme.CoverColors

data class VolumeActions(
    val onGroup: (Int) -> Unit,
    val onMember: (SonosPlayer, Int) -> Unit,
    val onMute: (SonosPlayer, Boolean) -> Unit,
)

/**
 * The volume of every room in the group (design B
 * of the mockups): all rooms together
 * on top, then one slider and a mute button per room. Opened from the
 * speaker icon next to the stage's volume slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeSheet(group: GroupState, colors: CoverColors, actions: VolumeActions, snackbar: SnackbarHostState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f), contentColor = colors.content,
        sheetMaxWidth = sheetMaxWidth(), dragHandle = sheetDragHandle(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.volume), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (isWide()) 12.dp else 0.dp))
            val members = group.group.members
            if (members.size > 1) {
                Text(stringResource(R.string.volume_all_rooms), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
                VolumeSlider(group.groupVolume, colors, stringResource(R.string.volume_all_rooms), actions.onGroup)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.subtle)
            }
            // Landscape: rooms side by side, two per row, so sliders keep a usable length
            // and the last one does not end up at the gesture bar.
            val perRow = if (isWide()) 2 else 1
            members.chunked(perRow).forEach { row ->
              Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
               row.forEach { p ->
                Column(Modifier.weight(1f)) {
                val muted = group.memberMutes[p.uuid] == true
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.roomName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { actions.onMute(p, !muted) },
                        colors = if (muted) IconButtonDefaults.filledIconButtonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                        else IconButtonDefaults.iconButtonColors(contentColor = colors.content),
                    ) {
                        Icon(
                            if (muted) AppIcons.VolumeOff else AppIcons.VolumeUp,
                            contentDescription = stringResource(if (muted) R.string.volume_unmute else R.string.volume_mute, p.roomName),
                        )
                    }
                }
                VolumeSlider(group.memberVolumes[p.uuid] ?: 0, colors, p.roomName) { actions.onMember(p, it) }
                }
               }
               repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
              }
            }
            Spacer(Modifier.height(24.dp))
        }
        MessageHost(snackbar)
    }
}

/** Live while dragging, like the stage's slider; the number shows the value. */
@Composable
private fun VolumeSlider(value: Int, colors: CoverColors, label: String, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = dragging ?: value.toFloat(),
            onValueChange = { dragging = it; onChange(it.toInt()) },
            onValueChangeFinished = { dragging = null },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.subtle),
            modifier = Modifier.weight(1f),
        )
        Text("${dragging?.toInt() ?: value}", Modifier.width(36.dp).padding(start = 8.dp), fontSize = 13.sp)
    }
}
