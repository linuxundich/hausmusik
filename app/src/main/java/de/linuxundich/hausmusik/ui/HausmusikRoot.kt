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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import de.linuxundich.hausmusik.ui.theme.AppIcons
import androidx.compose.material3.Icon
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
import de.linuxundich.hausmusik.ui.details.DetailsTarget
import de.linuxundich.hausmusik.ui.details.LocalItemDetails
import de.linuxundich.hausmusik.ui.stage.StageActions
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import de.linuxundich.hausmusik.ui.stage.StageScreen
import de.linuxundich.hausmusik.ui.theme.rememberCoverColors

@Serializable
private sealed interface Screen {
    @Serializable data object Stage : Screen
    @Serializable data object Library : Screen
    @Serializable data class Container(val item: DidlObject) : Screen
    @Serializable data object Alarms : Screen
    @Serializable data object Settings : Screen
    @Serializable data object Licenses : Screen
}

/** The screen stack survives a rotation (and the system ending the app in the background). */
private val stackSaver = Saver<SnapshotStateList<Screen>, String>(
    save = { Json.encodeToString(ListSerializer(Screen.serializer()), it.toList()) },
    restore = { saved -> mutableStateListOf<Screen>().apply { addAll(Json.decodeFromString(ListSerializer(Screen.serializer()), saved)) } },
)

/** The artist or album whose details are open, kept across a rotation. */
private val detailsSaver = Saver<MutableState<DetailsTarget?>, String>(
    save = { state -> state.value?.let { Json.encodeToString(DetailsTarget.serializer(), it) } },
    restore = { mutableStateOf(Json.decodeFromString(DetailsTarget.serializer(), it)) },
)

/** The item waiting for "play now, next or add", kept across a rotation like the dialog. */
private val pendingSaver = Saver<MutableState<DidlObject?>, String>(
    save = { state -> state.value?.let { Json.encodeToString(DidlObject.serializer(), it) } },
    restore = { mutableStateOf(Json.decodeFromString(DidlObject.serializer(), it)) },
)

@Composable
fun HausmusikRoot(vm: MainViewModel = hiltViewModel()) {
    val household by vm.household.collectAsStateWithLifecycle()
    val library: LibraryViewModel = hiltViewModel()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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

    val stack = rememberSaveable(saver = stackSaver) { mutableStateListOf<Screen>(Screen.Stage) }
    var roomsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var timerOpen by rememberSaveable { mutableStateOf(false) }
    var soundOpen by rememberSaveable { mutableStateOf(false) }
    var volumeOpen by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable(saver = detailsSaver) { mutableStateOf<DetailsTarget?>(null) }
    var videoOpen by rememberSaveable { mutableStateOf(false) }
    val videosOn by vm.musicVideosEnabled.collectAsStateWithLifecycle()
    val videoMode by vm.musicVideoMode.collectAsStateWithLifecycle()
    var lyricsConsent by rememberSaveable { mutableStateOf(false) }
    val lyricsEnabled by vm.lyricsEnabled.collectAsStateWithLifecycle()
    val detailsOn by vm.detailsEnabled.collectAsStateWithLifecycle()
    val detailsWikipedia by vm.detailsWikipedia.collectAsStateWithLifecycle()
    val everywhereExcluded by vm.everywhereExcluded.collectAsStateWithLifecycle()
    val stageBusy by vm.stageBusy.collectAsStateWithLifecycle()
    val artistImagesOn by vm.artistImagesEnabled.collectAsStateWithLifecycle()
    val now = selected.nowPlaying
    val lyricsKey = now?.let { listOf(it.title, it.artist, it.album, it.durationMs) }
    val lyrics by produceState<LyricsUi?>(null, showLyrics, lyricsEnabled, lyricsKey) {
        value = null
        if (showLyrics && lyricsEnabled && now != null) {
            value = LyricsUi.Loading
            value = vm.lyrics(now)
        }
    }
    var pendingPlay by rememberSaveable(saver = pendingSaver) { mutableStateOf<DidlObject?>(null) }
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }

    val colors = rememberCoverColors(selected.artUrl)

    fun requestPlay(item: DidlObject) {
        if (!item.playsDirectly && selected.queueLength > 0) pendingPlay = item
        else vm.play(item, QueueMode.ReplaceAndPlay)
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalItemDetails provides { details = it }) {
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
                    onRoomVolumes = { volumeOpen = true },
                    onSettings = { stack.add(Screen.Settings) },
                    onDetails = { now?.let { details = DetailsTarget.of(it) } },
                    crossfadeOn = selected.crossfade,
                    onMusicVideo = { videoOpen = true },
                    onCrossfade = { vm.setCrossfade(!selected.crossfade) },
                    onLyrics = { if (lyricsEnabled) showLyrics = !showLyrics else lyricsConsent = true },
                    timerLabel = selected.sleepRemainingMs?.let { ms ->
                        stringResource(R.string.sleep_minutes, ((ms + 59_999) / 60_000).toInt())
                    },
                ),
                lyrics = if (showLyrics && lyricsEnabled) lyrics ?: LyricsUi.Loading else null,
                busy = stageBusy,
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
                SettingsState(
                    manualHost, selected.group.coordinator.baseUrl.host, lyricsEnabled, artistImagesOn, detailsOn, detailsWikipedia,
                    rooms = household.allPlayers.filter { !it.isInvisible }.map { it.uuid to it.roomName }, everywhereExcluded = everywhereExcluded,
                    musicVideos = videosOn, videoMode = videoMode,
                ),
                onConnect = { vm.connectTo(it); stack.removeRange(1, stack.size) },
                onLyrics = vm::setLyricsEnabled,
                onArtistImages = vm::setArtistImagesEnabled,
                onDetails = vm::setDetailsEnabled,
                onDetailsWikipedia = vm::setDetailsWikipedia,
                onEverywhereExcluded = vm::setEverywhereExcluded,
                onMusicVideos = vm::setMusicVideosEnabled,
                onVideoMode = vm::setMusicVideoMode,
                onLicenses = { stack.add(Screen.Licenses) },
                onClearCache = vm::clearListCache,
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

    // One pill at the top for every screen; open sheets place their own host above this one.
    de.linuxundich.hausmusik.ui.common.MessageHost(snackbar, onDark = stack.last() is Screen.Stage)
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
                onEverywhere = vm::joinEverywhere,
                onPauseAll = vm::pauseAll,
            ),
            snackbar = snackbar,
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
    details?.let { target ->
        run {
            de.linuxundich.hausmusik.ui.details.DetailsSheet(
                target, detailsOn, colors,
                de.linuxundich.hausmusik.ui.details.DetailsActions(
                    loadArtist = { vm.artistDetails(it) },
                    loadAlbum = { title, artist -> vm.albumDetails(title, artist) },
                    onEnable = { vm.setDetailsEnabled(true) },
                ),
                onDismiss = { details = null },
            )
        }
    }
    if (videoOpen) {
        val playing = selected.nowPlaying
        if (playing == null) {
            videoOpen = false
        } else {
            val context = androidx.compose.ui.platform.LocalContext.current
            fun open(url: String) {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }.onFailure { scope.launch { snackbar.showSnackbar(resources.getString(R.string.music_video_no_app)) } }
            }
            de.linuxundich.hausmusik.ui.stage.MusicVideoSheet(
                playing, videosOn, videoMode, colors,
                de.linuxundich.hausmusik.ui.stage.MusicVideoActions(
                    find = { title, artist -> vm.findVideo(title, artist) },
                    onEnable = { vm.setMusicVideosEnabled(true) },
                    position = vm::livePositionMs,
                    openVideo = { id, start, pause ->
                        if (pause) vm.pauseSelected()
                        open(de.linuxundich.hausmusik.data.MusicVideos.watchUrl(id, start))
                        videoOpen = false
                    },
                    search = {
                        open(de.linuxundich.hausmusik.data.MusicVideos.searchUrl(playing.title, playing.artist))
                        videoOpen = false
                    },
                ),
                onDismiss = { videoOpen = false },
            )
        }
    }
    if (lyricsConsent) {
        AlertDialog(
            onDismissRequest = { lyricsConsent = false },
            icon = { Icon(AppIcons.Lyrics, null) },
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
    if (volumeOpen) {
        de.linuxundich.hausmusik.ui.sound.VolumeSheet(
            selected, colors,
            de.linuxundich.hausmusik.ui.sound.VolumeActions(
                onGroup = { vm.setGroupVolume(selected.id, it) },
                onMember = { p, v -> vm.setMemberVolume(selected.id, p, v) },
                onMute = { p, m -> vm.setMemberMute(selected.id, p, m) },
            ),
            snackbar = snackbar,
            onDismiss = { volumeOpen = false },
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
    } // LocalItemDetails: sheets and dialogs open details too
}
