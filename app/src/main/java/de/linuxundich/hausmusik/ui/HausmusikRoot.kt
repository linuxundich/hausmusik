// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.linuxundich.hausmusik.data.Connection
import de.linuxundich.hausmusik.data.QueueMode
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.playsDirectly
import de.linuxundich.hausmusik.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import de.linuxundich.hausmusik.ui.alarms.AlarmsScreen
import de.linuxundich.hausmusik.ui.settings.LicensesScreen
import de.linuxundich.hausmusik.ui.settings.SettingsScreen
import de.linuxundich.hausmusik.ui.settings.SettingsState
import de.linuxundich.hausmusik.ui.stage.LyricsUi
import de.linuxundich.hausmusik.ui.sound.SoundActions
import de.linuxundich.hausmusik.ui.sound.SoundSheet
import androidx.compose.ui.res.stringResource
import de.linuxundich.hausmusik.ui.common.PlayDialog
import de.linuxundich.hausmusik.ui.common.SleepTimerDialog
import de.linuxundich.hausmusik.ui.library.ContainerScreen
import de.linuxundich.hausmusik.ui.library.LibraryScreen
import de.linuxundich.hausmusik.ui.library.LibraryViewModel
import de.linuxundich.hausmusik.ui.queue.QueueActions
import de.linuxundich.hausmusik.ui.queue.QueueSheet
import de.linuxundich.hausmusik.ui.rooms.RoomActions
import de.linuxundich.hausmusik.ui.rooms.RoomsSheet
import de.linuxundich.hausmusik.ui.setup.ConnectScreen
import de.linuxundich.hausmusik.ui.setup.OfflineScreen
import de.linuxundich.hausmusik.ui.setup.ReconnectingBanner
import androidx.compose.foundation.layout.statusBarsPadding
import de.linuxundich.hausmusik.ui.stage.StageActions
import de.linuxundich.hausmusik.ui.stage.StageScreen
import de.linuxundich.hausmusik.ui.theme.rememberCoverColors

private sealed interface Screen {
    data object Stage : Screen
    data object Library : Screen
    data class Container(val item: DidlObject) : Screen
    data object Alarms : Screen
    data object Settings : Screen
    data object Licenses : Screen
}

@Composable
fun HausmusikRoot(vm: MainViewModel = hiltViewModel()) {
    val household by vm.household.collectAsStateWithLifecycle()
    val library: LibraryViewModel = hiltViewModel()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(resources.getString(it)) } }

    val selected = household.selected
    val manualHost by vm.manualHost.collectAsStateWithLifecycle()
    // No Wi-Fi: one clear mask over everything, whatever was shown before.
    val lastRoomName by vm.lastRoomName.collectAsStateWithLifecycle()
    if (household.connection == Connection.NoLocalNetwork) {
        OfflineScreen(lastRoom = selected?.group?.displayName ?: lastRoomName, hasManualHost = manualHost != null)
        return
    }
    if (selected == null) {
        ConnectScreen(household.connection, vm::connect, vm::searchAgain)
        return
    }

    val stack = remember { mutableStateListOf<Screen>(Screen.Stage) }
    var roomsOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
    var soundOpen by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var lyricsConsent by remember { mutableStateOf(false) }
    val lyricsEnabled by vm.lyricsEnabled.collectAsStateWithLifecycle()
    val now = selected.nowPlaying
    val lyricsKey = now?.let { listOf(it.title, it.artist, it.album, it.durationMs) }
    val lyrics by produceState<LyricsUi?>(null, showLyrics, lyricsEnabled, lyricsKey) {
        value = null
        if (showLyrics && lyricsEnabled && now != null) {
            value = LyricsUi.Loading
            value = vm.lyrics(now)
        }
    }
    var pendingPlay by remember { mutableStateOf<DidlObject?>(null) }
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }

    val colors = rememberCoverColors(selected.artUrl)

    fun requestPlay(item: DidlObject) {
        if (!item.playsDirectly && selected.queueLength > 0) pendingPlay = item
        else vm.play(item, QueueMode.ReplaceAndPlay)
    }

    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = {
            if (targetState is Screen.Stage) fadeIn() togetherWith slideOutVertically { it / 3 } + fadeOut()
            else slideInVertically { it / 3 } + fadeIn() togetherWith fadeOut()
        },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Stage -> StageScreen(
                selected, colors,
                StageActions(
                    onRooms = { roomsOpen = true },
                    onLibrary = { stack.add(Screen.Library) },
                    onQueue = { queueOpen = true },
                    onTimer = { timerOpen = true },
                    onPlayPause = vm::playPause,
                    onNext = vm::next,
                    onPrevious = vm::previous,
                    onSeek = vm::seek,
                    onVolume = { vm.setGroupVolume(selected.id, it) },
                    onShuffle = vm::toggleShuffle,
                    onRepeat = vm::cycleRepeat,
                    onAlarms = { stack.add(Screen.Alarms) },
                    onSound = { soundOpen = true },
                    onSettings = { stack.add(Screen.Settings) },
                    onLyrics = { if (lyricsEnabled) showLyrics = !showLyrics else lyricsConsent = true },
                    timerLabel = selected.sleepRemainingMs?.let { ms ->
                        stringResource(R.string.sleep_minutes, ((ms + 59_999) / 60_000).toInt())
                    },
                ),
                lyrics = if (showLyrics && lyricsEnabled) lyrics ?: LyricsUi.Loading else null,
            )
            Screen.Library -> LibraryScreen(
                library, selected, snackbar,
                onBack = { stack.removeAt(stack.lastIndex) },
                onOpenContainer = { stack.add(Screen.Container(it)) },
                onPlay = ::requestPlay,
                onMiniPlayer = { stack.removeRange(1, stack.size) },
                onPlayPause = vm::playPause,
            )
            Screen.Alarms -> AlarmsScreen(hiltViewModel(), onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Settings -> SettingsScreen(
                SettingsState(manualHost, selected.group.coordinator.baseUrl.host, lyricsEnabled),
                onConnect = { vm.connectTo(it); stack.removeRange(1, stack.size) },
                onLyrics = vm::setLyricsEnabled,
                onLicenses = { stack.add(Screen.Licenses) },
                onBack = { stack.removeAt(stack.lastIndex) },
            )
            Screen.Licenses -> LicensesScreen(onBack = { stack.removeAt(stack.lastIndex) })
            is Screen.Container -> ContainerScreen(
                library, screen.item, selected, snackbar,
                onBack = { stack.removeAt(stack.lastIndex) },
                onOpenContainer = { stack.add(Screen.Container(it)) },
                onPlay = ::requestPlay,
                onMiniPlayer = { stack.removeRange(1, stack.size) },
                onPlayPause = vm::playPause,
            )
        }
    }

    if (stack.last() is Screen.Stage) {
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }
    if (household.connection == Connection.Reconnecting || household.connection == Connection.Searching) {
        ReconnectingBanner(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    }
    }

    if (roomsOpen) {
        RoomsSheet(
            household, colors,
            RoomActions(
                onSelect = { vm.select(it); roomsOpen = false },
                onMerge = { source, target -> vm.merge(source, target) },
                onPlayPause = vm::playPause,
                onGroupVolume = vm::setGroupVolume,
                onMemberVolume = vm::setMemberVolume,
                onMembership = vm::setMembership,
                onPauseAll = vm::pauseAll,
            ),
            onDismiss = { roomsOpen = false },
        )
    }
    if (queueOpen) {
        QueueSheet(
            selected, colors,
            artUrl = library::artUrl,
            actions = QueueActions(
                load = { vm.loadQueue() },
                playTrack = { vm.playQueueTrack(it) },
                remove = { vm.removeFromQueue(it) },
                move = { from, to -> vm.moveInQueue(from, to) },
                moveToNext = { vm.moveToNext(it) },
                clear = { vm.clearQueue() },
                save = { vm.saveQueue(it) },
            ),
            snackbar = snackbar,
            onDismiss = { queueOpen = false },
        )
    }
    if (lyricsConsent) {
        AlertDialog(
            onDismissRequest = { lyricsConsent = false },
            title = { Text(stringResource(R.string.lyrics_consent_title)) },
            text = { Text(stringResource(R.string.lyrics_consent_body)) },
            confirmButton = {
                TextButton(onClick = { vm.setLyricsEnabled(true); showLyrics = true; lyricsConsent = false }) {
                    Text(stringResource(R.string.lyrics_consent_yes))
                }
            },
            dismissButton = { TextButton(onClick = { lyricsConsent = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (soundOpen) {
        SoundSheet(
            selected.group.members, colors,
            SoundActions(load = { vm.sound(it) }, setBass = vm::setBass, setTreble = vm::setTreble, setLoudness = vm::setLoudness),
            onDismiss = { soundOpen = false },
        )
    }
    if (timerOpen) {
        SleepTimerDialog(onChoose = { vm.setSleepTimer(it); timerOpen = false }, onDismiss = { timerOpen = false })
    }
    pendingPlay?.let { item ->
        PlayDialog(
            item.title, selected.group.displayName, selected.queueLength,
            onChoose = { vm.play(item, it); pendingPlay = null },
            onDismiss = { pendingPlay = null },
        )
    }
    if (household.connection is Connection.NotFound) {
        // Lost the household while running: fall back to the connect screen.
        ConnectScreen(household.connection, vm::connect, vm::searchAgain)
    }
}
