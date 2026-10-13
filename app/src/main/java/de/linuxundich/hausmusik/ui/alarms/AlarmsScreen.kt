// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import de.linuxundich.hausmusik.ui.common.isWide
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.sonos.Alarm
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.Recurrence
import de.linuxundich.hausmusik.sonos.SonosPlayer
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Monday first; values are Sonos day numbers (Sunday = 0). */
private val weekOrder = listOf(1, 2, 3, 4, 5, 6, 0)

private fun dayName(sonosDay: Int, locale: Locale): String =
    DayOfWeek.of(if (sonosDay == 0) 7 else sonosDay).getDisplayName(TextStyle.SHORT, locale).trimEnd('.')

@Composable
fun recurrenceLabel(r: Recurrence): String {
    val locale = LocalConfiguration.current.locales[0]
    return when {
        r.days.isEmpty() -> stringResource(R.string.alarm_once)
        r.days.size == 7 -> stringResource(R.string.alarm_daily)
        r.days == Recurrence.WEEKDAYS -> "${dayName(1, locale)}–${dayName(5, locale)}"
        else -> weekOrder.filter { it in r.days }.joinToString(", ") { dayName(it, locale) }
    }
}

private fun time(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlarmsScreen(vm: AlarmsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val household by vm.household.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Alarm?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    val rooms = household.allPlayers
    val wide = isWide()
    fun newAlarm() {
        editing = Alarm(
            id = null, startMinutes = 7 * 60, durationMinutes = 60, recurrence = Recurrence(Recurrence.WEEKDAYS),
            enabled = true, roomUuid = household.selected?.id ?: rooms.first().uuid,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alarms)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    // Landscape: a floating button would sit on the cards, so it moves up here.
                    if (wide && rooms.isNotEmpty()) TextButton(onClick = ::newAlarm) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.alarm_add), Modifier.padding(start = 4.dp))
                    }
                },
            )
        },
        floatingActionButton = {
            if (!wide && rooms.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = ::newAlarm,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.alarm_add)) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                AlarmsState.Loading -> LoadingIndicator(Modifier.align(Alignment.Center))
                AlarmsState.Error -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.library_error))
                    Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.retry)) }
                }
                is AlarmsState.Loaded -> if (s.alarms.isEmpty()) {
                    Text(
                        stringResource(R.string.alarms_empty), Modifier.align(Alignment.Center).widthIn(max = 480.dp).padding(32.dp),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, if (wide) 16.dp else 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(s.alarms, key = { it.id ?: "" }) { alarm ->
                        AlarmCard(
                            alarm,
                            roomName = rooms.firstOrNull { it.uuid == alarm.roomUuid }?.roomName ?: "?",
                            sourceName = sourceName(alarm, sources),
                            onToggle = { vm.save(alarm.copy(enabled = it)) },
                            onEdit = { editing = alarm },
                            onDuplicate = { editing = alarm.copy(id = null) },
                            onDelete = { vm.delete(alarm) },
                        )
                    }
                }
            }
        }
    }

    editing?.let { alarm ->
        AlarmEditor(
            alarm, rooms, sources,
            onSave = { vm.save(it); editing = null },
            onDelete = if (alarm.id != null) ({ vm.delete(alarm); editing = null }) else null,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun sourceName(alarm: Alarm, sources: List<DidlObject>): String =
    if (alarm.usesChime) stringResource(R.string.alarm_chime)
    else sources.firstOrNull { it.uri == alarm.programUri }?.title ?: stringResource(R.string.alarm_other_source)

@Composable
private fun AlarmCard(
    alarm: Alarm,
    roomName: String,
    sourceName: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    time(alarm.startMinutes), fontSize = 40.sp, fontWeight = FontWeight.Medium,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Text(recurrenceLabel(alarm.recurrence), style = MaterialTheme.typography.bodyMedium)
                Text("$roomName · $sourceName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.queue_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.alarm_duplicate)) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.alarm_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private val durations = listOf(15, 30, 60, 120)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditor(
    initial: Alarm,
    rooms: List<SonosPlayer>,
    sources: List<DidlObject>,
    onSave: (Alarm) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var alarm by remember { mutableStateOf(initial) }
    var pickTime by remember { mutableStateOf(false) }
    val wide = isWide()
    val dark = isSystemInDarkTheme()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog has its own window: give it status bar icons that fit the theme.
        val view = LocalView.current
        LaunchedEffect(dark) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !dark
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (initial.id == null) R.string.alarm_new else R.string.alarm_edit)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                    actions = {
                        TextButton(onClick = { onSave(alarm) }) {
                            Text(stringResource(R.string.save))
                        }
                    },
                )
            },
        ) { padding ->
            val timeAndDays: @Composable () -> Unit = {
                // A large time that opens the clock dial; a text field would pull up the keyboard at once.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { pickTime = true }) {
                        Text(time(alarm.startMinutes), fontSize = 64.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Section(stringResource(R.string.alarm_days))
                val locale = LocalConfiguration.current.locales[0]
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    weekOrder.forEach { d ->
                        FilterChip(
                            selected = d in alarm.recurrence.days,
                            onClick = {
                                val days = alarm.recurrence.days.let { if (d in it) it - d else it + d }
                                alarm = alarm.copy(recurrence = Recurrence(days))
                            },
                            label = { Text(dayName(d, locale)) },
                        )
                    }
                }
                Text(recurrenceLabel(alarm.recurrence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section(stringResource(R.string.alarm_room))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rooms.forEach { r ->
                        FilterChip(selected = r.uuid == alarm.roomUuid, onClick = { alarm = alarm.copy(roomUuid = r.uuid) }, label = { Text(r.roomName) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.alarm_linked), Modifier.weight(1f))
                    Switch(checked = alarm.includeLinkedZones, onCheckedChange = { alarm = alarm.copy(includeLinkedZones = it) })
                }
            }
            val whatAndHow: @Composable () -> Unit = {
                Section(stringResource(R.string.alarm_source))
                SourceRow(stringResource(R.string.alarm_chime), alarm.usesChime) {
                    alarm = alarm.copy(programUri = Alarm.BUZZER, programMetadata = "")
                }
                sources.forEach { src ->
                    SourceRow(src.title, alarm.programUri == src.uri) {
                        alarm = alarm.copy(programUri = src.uri!!, programMetadata = src.resMd.orEmpty())
                    }
                }

                Section(stringResource(R.string.alarm_volume, alarm.volume))
                Slider(value = alarm.volume.toFloat(), onValueChange = { alarm = alarm.copy(volume = it.toInt()) }, valueRange = 0f..100f)

                Section(stringResource(R.string.alarm_duration))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    durations.forEach { m ->
                        FilterChip(
                            selected = alarm.durationMinutes == m,
                            onClick = { alarm = alarm.copy(durationMinutes = m) },
                            label = { Text(stringResource(R.string.sleep_minutes, m)) },
                        )
                    }
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alarm_delete)) }
                }
                Spacer(Modifier.height(32.dp))
            }
            if (wide) {
                // Landscape: when and where on the left, what and how loud on the right.
                Row(Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { timeAndDays() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { whatAndHow() }
                }
            } else {
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    timeAndDays()
                    whatAndHow()
                }
            }
        }
    }
    if (pickTime) {
        // TimePickerDialog switches to the side-by-side layout in landscape; a
        // plain AlertDialog would cut the dial off there.
        val clock = rememberTimePickerState(alarm.startMinutes / 60, alarm.startMinutes % 60, is24Hour = true)
        TimePickerDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(stringResource(R.string.alarm_time)) },
            confirmButton = {
                TextButton(onClick = { alarm = alarm.copy(startMinutes = clock.hour * 60 + clock.minute); pickTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.cancel)) } },
        ) { TimePicker(state = clock) }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SourceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}
