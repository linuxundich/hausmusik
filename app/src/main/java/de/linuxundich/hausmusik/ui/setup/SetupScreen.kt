// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.setup

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.linuxundich.hausmusik.R
import androidx.compose.foundation.layout.Row
import de.linuxundich.hausmusik.data.Connection
import de.linuxundich.hausmusik.ui.common.isWide
import de.linuxundich.hausmusik.ui.theme.AppIcons

@Composable
private fun Centered(maxWidth: androidx.compose.ui.unit.Dp = 420.dp, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        // Scrolls in landscape, where the text and the buttons are taller than the screen.
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = maxWidth), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}

/** Shown before the local network permission is granted (Android 17+). */
@Composable
fun PermissionScreen(denied: Boolean, onRequest: () -> Unit) = Centered {
    val context = LocalContext.current
    Icon(AppIcons.Speaker, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(if (denied) R.string.permission_denied else R.string.permission_body), textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Button(onClick = onRequest) { Text(stringResource(R.string.permission_allow)) }
    if (denied) {
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text(stringResource(R.string.permission_open_settings)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectScreen(connection: Connection, onConnect: (String) -> Unit, onSearchAgain: () -> Unit) {
    if (connection !is Connection.NotFound) {
        Centered {
            LoadingIndicator(Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.setup_searching), style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    var host by rememberSaveable { mutableStateOf(connection.manualHost.orEmpty()) }
    val explanation: @Composable () -> Unit = {
        Icon(AppIcons.Speaker, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_not_found_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_not_found_body), textAlign = TextAlign.Center)
        connection.manualHost?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_tried_host, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    val form: @Composable () -> Unit = {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.setup_host_label)) },
            placeholder = { Text(stringResource(R.string.setup_host_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onConnect(host) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onConnect(host) }, enabled = host.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_connect)) }
        OutlinedButton(onClick = onSearchAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_search_again)) }
    }
    if (isWide()) {
        // Landscape: the explanation beside the form, so the buttons are not below the edge.
        Centered(maxWidth = 860.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { explanation() }
                Column(Modifier.weight(1f)) { form() }
            }
        }
    } else {
        Centered {
            explanation()
            Spacer(Modifier.height(24.dp))
            form()
        }
    }
}

/**
 * No Wi-Fi: the phone is on mobile data (or offline). Says so plainly, offers
 * the Wi-Fi settings and promises what happens next: Hausmusik reconnects by
 * itself.
 */
@Composable
fun OfflineScreen(lastRoom: String?, hasManualHost: Boolean) = Centered {
    val context = LocalContext.current
    Icon(AppIcons.WifiOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.offline_body), textAlign = TextAlign.Center)
    if (hasManualHost) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.offline_manual_failed), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = {
        val panel = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        context.startActivity(Intent(panel))
    }) { Text(stringResource(R.string.offline_wifi_settings)) }
    Spacer(Modifier.height(16.dp))
    Text(
        lastRoom?.let { stringResource(R.string.offline_back_room, it) } ?: stringResource(R.string.offline_back),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )
}

/** Thin band over the Stage while the connection is being rebuilt. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReconnectingBanner(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shape = MaterialTheme.shapes.large, modifier = modifier) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.reconnecting), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The speakers stopped answering while the app was in use. The app keeps
 * searching on its own; this says so instead of replacing everything with the
 * setup screen. "Address" opens it for whoever wants to enter another one.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LostBanner(onChangeAddress: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shape = MaterialTheme.shapes.large, modifier = modifier) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
            Spacer(Modifier.size(12.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f, fill = false)) {
                Text(stringResource(R.string.lost_title), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.lost_body), style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.TextButton(onClick = onChangeAddress) {
                Text(stringResource(R.string.lost_address), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}
