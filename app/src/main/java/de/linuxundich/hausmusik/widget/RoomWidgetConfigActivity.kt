// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.system.HouseholdAccess
import de.linuxundich.hausmusik.ui.theme.HausmusikTheme
import kotlinx.coroutines.launch

/** Asked when the room widget is placed (or reconfigured): which room, and optionally which favorite. */
@OptIn(ExperimentalMaterial3Api::class)
class RoomWidgetConfigActivity : ComponentActivity() {
    private data class Choices(val rooms: List<String>, val favorites: List<String>)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out leaves no half-made widget behind.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        setContent {
            HausmusikTheme {
                val choices by produceState<Choices?>(null) {
                    value = HouseholdAccess.withSelected(applicationContext) { household, _ ->
                        Choices(
                            household.state.value.allPlayers.filter { !it.isInvisible }.map { it.roomName }.sorted(),
                            household.browseAll("FV:2").getOrDefault(emptyList()).map { it.title },
                        )
                    } ?: Choices(emptyList(), emptyList())
                }
                var room by rememberSaveable { mutableStateOf<String?>(null) }
                var favorite by rememberSaveable { mutableStateOf<String?>(null) }
                Scaffold(
                    topBar = { TopAppBar(title = { Text(stringResource(R.string.room_widget_label)) }) },
                    bottomBar = {
                        Button(
                            onClick = { save(widgetId, room!!, favorite) }, enabled = room != null,
                            modifier = Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth(),
                        ) { Text(stringResource(R.string.room_widget_done)) }
                    },
                ) { padding ->
                    val c = choices
                    if (c == null) {
                        LinearProgressIndicator(Modifier.padding(padding).padding(16.dp).fillMaxWidth())
                    } else if (c.rooms.isEmpty()) {
                        Text(stringResource(R.string.room_widget_no_speakers), Modifier.padding(padding).padding(16.dp))
                    } else {
                        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                            item { Section(stringResource(R.string.room_widget_room)) }
                            items(c.rooms) { r -> Choice(r, room == r) { room = r } }
                            item { Section(stringResource(R.string.room_widget_what)) }
                            item { Choice(stringResource(R.string.room_widget_play_pause), favorite == null) { favorite = null } }
                            items(c.favorites) { f -> Choice(f, favorite == f) { favorite = f } }
                        }
                    }
                }
            }
        }
    }

    private fun save(widgetId: Int, room: String, favorite: String?) = lifecycleScope.launch {
        val glanceId = GlanceAppWidgetManager(this@RoomWidgetConfigActivity).getGlanceIdBy(widgetId)
        updateAppWidgetState(this@RoomWidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                this[RoomWidget.ROOM] = room
                if (favorite != null) this[RoomWidget.FAVORITE] = favorite else remove(RoomWidget.FAVORITE)
            }
        }
        RoomWidget().update(this@RoomWidgetConfigActivity, glanceId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@androidx.compose.runtime.Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    )
}
