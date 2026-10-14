// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.linuxundich.hausmusik.ui.theme.AppIcons
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.QueueMode

/**
 * Asked before anything replaces a non-empty queue. In Gnomos, "play all"
 * once wiped a streaming queue that could not be restored.
 */
@Composable
fun PlayDialog(title: String, room: String, queueLength: Int, onChoose: (QueueMode) -> Unit, onDismiss: () -> Unit, onAlwaysReplace: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Play, null) },
        title = { Text(stringResource(R.string.play_dialog_title, title), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(pluralStringResource(R.plurals.play_dialog_body, queueLength, room, queueLength))
                ChoiceGroup(
                    listOf(
                        Choice(AppIcons.Play, stringResource(R.string.play_now_replace)) { onChoose(QueueMode.ReplaceAndPlay) },
                        Choice(AppIcons.PlayNext, stringResource(R.string.play_next)) { onChoose(QueueMode.PlayNext) },
                        Choice(AppIcons.PlaylistAdd, stringResource(R.string.play_append)) { onChoose(QueueMode.Append) },
                    ),
                )
                // For those who always replace: one tap less from now on (Settings bring the question back).
                if (onAlwaysReplace != null) {
                    TextButton(onClick = onAlwaysReplace) { Text(stringResource(R.string.play_always_replace)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val sleepOptions = listOf<Int?>(null, 15, 30, 45, 60, 90)

@Composable
fun SleepTimerDialog(onChoose: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Timer, null) },
        title = { Text(stringResource(R.string.sleep_title), textAlign = TextAlign.Center) },
        text = {
            ChoiceGroup(
                sleepOptions.map { m ->
                    Choice(null, if (m == null) stringResource(R.string.sleep_off) else stringResource(R.string.sleep_minutes, m)) { onChoose(m) }
                },
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
