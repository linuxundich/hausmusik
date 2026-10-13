package de.linuxundich.hausmusik.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.linuxundich.hausmusik.R
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
    val onPlayPause: (String) -> Unit,
    val onGroupVolume: (String, Int) -> Unit,
    val onMemberVolume: (String, SonosPlayer, Int) -> Unit,
    val onMembership: (SonosPlayer, Boolean) -> Unit,
    val onPauseAll: () -> Unit,
)

/**
 * All rooms at a glance. Upper part: every group with its own volume and
 * play button; tapping one shows it on the Stage. Lower part: which rooms
 * play along with the selected room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsSheet(household: Household, colors: CoverColors, actions: RoomActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val container = lerp(colors.backgroundBottom, colors.backgroundTop, 0.35f)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = container, contentColor = colors.content) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rooms), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (household.groups.any { it.isPlaying }) {
                    TextButton(onClick = actions.onPauseAll) { Text(stringResource(R.string.rooms_pause_all), color = colors.accent) }
                }
            }
            Spacer(Modifier.height(8.dp))
            household.groups.forEach { g ->
                GroupCard(g, g.id == household.selected?.id, colors, actions)
                Spacer(Modifier.height(8.dp))
            }
            val selected = household.selected ?: return@Column
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.room_plays_with, selected.group.coordinator.roomName),
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            )
            Text(stringResource(R.string.rooms_together_hint), fontSize = 12.sp, color = colors.content.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))
            household.allPlayers.filter { it.uuid != selected.id }.forEach { p ->
                val member = selected.group.members.any { it.uuid == p.uuid }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = member,
                        onCheckedChange = { actions.onMembership(p, it) },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent, uncheckedColor = colors.content.copy(alpha = 0.6f)),
                    )
                    Text(p.roomName, modifier = Modifier.weight(0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (member) {
                        VolumeSlider(selected.memberVolumes[p.uuid] ?: 0, colors, Modifier.weight(0.6f)) {
                            actions.onMemberVolume(selected.id, p, it)
                        }
                    } else {
                        Spacer(Modifier.weight(0.6f))
                    }
                }
            }
            if (selected.group.members.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
                    Text(selected.group.coordinator.roomName, modifier = Modifier.weight(0.4f), maxLines = 1)
                    VolumeSlider(selected.memberVolumes[selected.id] ?: 0, colors, Modifier.weight(0.6f)) {
                        actions.onMemberVolume(selected.id, selected.group.coordinator, it)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupCard(g: GroupState, selected: Boolean, colors: CoverColors, actions: RoomActions) {
    Surface(
        onClick = { actions.onSelect(g.id) },
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent,
        contentColor = colors.content,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(
                    g.artUrl, Modifier.size(48.dp), corner = 10.dp, placeholderColor = colors.subtle,
                    iconColor = colors.content.copy(alpha = 0.5f),
                    icon = if (g.nowPlaying?.kind == SourceKind.Radio) AppIcons.Radio else AppIcons.Speaker,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(g.group.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val state = when {
                        g.nowPlaying == null -> stringResource(R.string.rooms_idle)
                        g.transport == TransportState.Paused || g.transport == TransportState.Stopped ->
                            "${g.nowPlaying.station ?: g.nowPlaying.title} · ${stringResource(R.string.rooms_paused)}"
                        else -> listOfNotNull(g.nowPlaying.title, g.nowPlaying.artist).joinToString(" · ")
                    }
                    Text(state, fontSize = 12.sp, color = colors.content.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { actions.onPlayPause(g.id) }, enabled = g.nowPlaying != null) {
                    Icon(
                        if (g.isPlaying) AppIcons.Pause else AppIcons.Play,
                        contentDescription = stringResource(if (g.isPlaying) R.string.pause else R.string.play),
                        tint = if (g.nowPlaying != null) colors.accent else colors.content.copy(alpha = 0.3f),
                    )
                }
            }
            VolumeSlider(g.groupVolume, colors, Modifier.fillMaxWidth()) { actions.onGroupVolume(g.id, it) }
        }
    }
}

@Composable
private fun VolumeSlider(value: Int, colors: CoverColors, modifier: Modifier, onChange: (Int) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(AppIcons.VolumeDown, null, tint = colors.content.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).height(32.dp),
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.subtle),
        )
        Text("$value", fontSize = 12.sp, color = colors.content.copy(alpha = 0.7f), modifier = Modifier.padding(end = 4.dp))
    }
}
