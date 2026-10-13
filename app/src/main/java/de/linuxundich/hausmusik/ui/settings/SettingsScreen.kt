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

data class SettingsState(val manualHost: String?, val connectedTo: String?, val lyricsEnabled: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    onConnect: (String?) -> Unit,
    onLyrics: (Boolean) -> Unit,
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
