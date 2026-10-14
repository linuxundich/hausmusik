// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.linuxundich.hausmusik.BuildConfig
import de.linuxundich.hausmusik.R

data class SettingsState(val manualHost: String?, val connectedTo: String?, val lyricsEnabled: Boolean, val artistImagesEnabled: Boolean = false, val detailsEnabled: Boolean = false, val detailsWikipedia: Boolean = true,
    /** Visible rooms (UUID to name) and those "Everywhere" leaves out. */
    val rooms: List<Pair<String, String>> = emptyList(), val everywhereExcluded: Set<String> = emptySet(),
    val musicVideos: Boolean = false, val videoMode: de.linuxundich.hausmusik.data.VideoMode = de.linuxundich.hausmusik.data.VideoMode.Ask,
    val effects: de.linuxundich.hausmusik.data.Effects = de.linuxundich.hausmusik.data.Effects(),
    val askBeforeReplace: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onConnect: (String?) -> Unit,
    onLyrics: (Boolean) -> Unit,
    onArtistImages: (Boolean) -> Unit,
    onDetails: (Boolean) -> Unit = {},
    onDetailsWikipedia: (Boolean) -> Unit = {},
    onEverywhereExcluded: (Set<String>) -> Unit = {},
    onMusicVideos: (Boolean) -> Unit = {},
    onVideoMode: (de.linuxundich.hausmusik.data.VideoMode) -> Unit = {},
    onEffects: (de.linuxundich.hausmusik.data.Effects) -> Unit = {},
    onAskBeforeReplace: (Boolean) -> Unit = {},
    onLicenses: () -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
) {
    var host by rememberSaveable(state.manualHost) { mutableStateOf(state.manualHost.orEmpty()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        // Readable width in landscape: settings are text and fields, not a canvas.
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_speakers), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(if (state.manualHost == null) R.string.settings_found_auto else R.string.settings_found_manual, state.connectedTo ?: "–"),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = host, onValueChange = { host = it }, singleLine = true,
                label = { Text(stringResource(R.string.setup_host_label)) },
                placeholder = { Text(stringResource(R.string.setup_host_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConnect(host) }, enabled = host.isNotBlank()) { Text(stringResource(R.string.setup_connect)) }
                OutlinedButton(onClick = { host = ""; onConnect(null) }) { Text(stringResource(R.string.settings_search_auto)) }
            }
            if (state.rooms.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.settings_everywhere), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_everywhere_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.rooms.forEach { (uuid, name) ->
                        val out = uuid in state.everywhereExcluded
                        androidx.compose.material3.FilterChip(
                            selected = out,
                            onClick = { onEverywhereExcluded(if (out) state.everywhereExcluded - uuid else state.everywhereExcluded + uuid) },
                            label = { Text(name) },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.settings_playing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            EffectSwitch(R.string.settings_ask_replace, R.string.settings_ask_replace_body, state.askBeforeReplace, onAskBeforeReplace)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.cache), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.cache_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClearCache) { Text(stringResource(R.string.cache_clear)) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.lyrics), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_lyrics), Modifier.weight(1f))
                Switch(checked = state.lyricsEnabled, onCheckedChange = onLyrics)
            }
            Text(stringResource(R.string.lyrics_consent_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.artist_images_setting), Modifier.weight(1f))
                Switch(checked = state.artistImagesEnabled, onCheckedChange = onArtistImages)
            }
            Text(stringResource(R.string.artist_images_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.details_setting), Modifier.weight(1f))
                Switch(checked = state.detailsEnabled, onCheckedChange = onDetails)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.details_setting_wikipedia), Modifier.weight(1f),
                    color = if (state.detailsEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Switch(checked = state.detailsWikipedia, onCheckedChange = onDetailsWikipedia, enabled = state.detailsEnabled)
            }
            Text(stringResource(R.string.details_consent_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.music_video_setting), Modifier.weight(1f))
                Switch(checked = state.musicVideos, onCheckedChange = onMusicVideos)
            }
            Text(stringResource(R.string.music_video_setting_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.musicVideos) {
                Text(stringResource(R.string.music_video_mode), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        de.linuxundich.hausmusik.data.VideoMode.KeepPlaying to R.string.music_video_mode_keep,
                        de.linuxundich.hausmusik.data.VideoMode.Pause to R.string.music_video_mode_pause,
                        de.linuxundich.hausmusik.data.VideoMode.Ask to R.string.music_video_mode_ask,
                    ).forEach { (m, label) ->
                        androidx.compose.material3.FilterChip(selected = state.videoMode == m, onClick = { onVideoMode(m) }, label = { Text(stringResource(label)) })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.fx_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val fx = state.effects
            EffectSwitch(R.string.fx_stage_light, R.string.fx_stage_light_body, fx.stageLight) { onEffects(fx.copy(stageLight = it)) }
            EffectSwitch(R.string.fx_tap_along, R.string.fx_tap_along_body, fx.tapAlong) { onEffects(fx.copy(tapAlong = it)) }
            EffectSwitch(R.string.fx_turntable, R.string.fx_turntable_body, fx.turntable) { onEffects(fx.copy(turntable = it)) }
            EffectSwitch(R.string.fx_night_stage, R.string.fx_night_stage_body, fx.nightStage) { onEffects(fx.copy(nightStage = it)) }
            if (fx.nightStage) {
                val context = androidx.compose.ui.platform.LocalContext.current
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)) }
                }) { Text(stringResource(R.string.fx_night_stage_setup)) }
            }
            Text(stringResource(R.string.fx_still_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_copyright), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.settings_license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_noson), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onLicenses) { Text(stringResource(R.string.licenses)) }
        }
        }
    }
}

@Composable
private fun EffectSwitch(title: Int, body: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title))
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 12.dp))
    }
}
