package de.linuxundich.hausmusik.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.QueueMode

/**
 * Asked before anything replaces a non-empty queue. In Gnomos, "play all"
 * once wiped a streaming queue that could not be restored.
 */
@Composable
fun PlayDialog(title: String, room: String, queueLength: Int, onChoose: (QueueMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.play_dialog_title, title)) },
        text = { Text(pluralStringResource(R.plurals.play_dialog_body, queueLength, room, queueLength)) },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onChoose(QueueMode.ReplaceAndPlay) }) { Text(stringResource(R.string.play_now_replace)) }
                TextButton(onClick = { onChoose(QueueMode.PlayNext) }) { Text(stringResource(R.string.play_next)) }
                TextButton(onClick = { onChoose(QueueMode.Append) }) { Text(stringResource(R.string.play_append)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private val sleepOptions = listOf<Int?>(null, 15, 30, 45, 60, 90)

@Composable
fun SleepTimerDialog(onChoose: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_title)) },
        text = {
            Column {
                sleepOptions.forEach { m ->
                    TextButton(onClick = { onChoose(m) }) {
                        Text(if (m == null) stringResource(R.string.sleep_off) else stringResource(R.string.sleep_minutes, m))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
