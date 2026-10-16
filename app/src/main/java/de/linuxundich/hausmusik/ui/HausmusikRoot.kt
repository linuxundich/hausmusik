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
import de.linuxundich.hausmusik.ui.stage.StageSidePanel
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
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
    var connectOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(household.connection) { if (household.connection !is Connection.NotFound) connectOpen = false }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var timerOpen by rememberSaveable { mutableStateOf(false) }
    var soundOpen by rememberSaveable { mutableStateOf(false) }
    var volumeOpen by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable(saver = detailsSaver) { mutableStateOf<DetailsTarget?>(null) }
    var videoOpen by rememberSaveable { mutableStateOf(false) }
    val videosOn by vm.musicVideosEnabled.collectAsStateWithLifecycle()
    val stationLogosOn by vm.stationLogosEnabled.collectAsStateWithLifecycle()
    val favoritesList by vm.favorites.collectAsStateWithLifecycle()
    LaunchedEffect(household.connection) { if (household.connection == Connection.Connected) vm.loadFavorites() }
    val videoMode by vm.musicVideoMode.collectAsStateWithLifecycle()
    var lyricsConsent by rememberSaveable { mutableStateOf(false) }
    val lyricsEnabled by vm.lyricsEnabled.collectAsStateWithLifecycle()
    val detailsOn by vm.detailsEnabled.collectAsStateWithLifecycle()
    val detailsWikipedia by vm.detailsWikipedia.collectAsStateWithLifecycle()
    val everywhereExcluded by vm.everywhereExcluded.collectAsStateWithLifecycle()
    val stageBusy by vm.stageBusy.collectAsStateWithLifecycle()
    val effects by vm.effects.collectAsStateWithLifecycle()
    val artistImagesOn by vm.artistImagesEnabled.collectAsStateWithLifecycle()
    val now = selected.nowPlaying
    val lyricsKey = now?.let { listOf(it.title, it.artist, it.album, it.durationMs) }
    val fetchedLyrics by produceState<LyricsUi?>(null, showLyrics, lyricsEnabled, lyricsKey) {
        value = null
        if (showLyrics && lyricsEnabled && now != null) {
            value = LyricsUi.Loading
            value = vm.lyrics(now)
        }
    }
    val lyricsOffsets by vm.lyricsOffsets.collectAsStateWithLifecycle()
    val lyrics = (fetchedLyrics as? LyricsUi.Found)?.let { found ->
        val track = now?.let(vm::lyricsTrack) ?: return@let found
        found.copy(offsetMs = lyricsOffsets[track] ?: 0, onOffset = { vm.setLyricsOffset(track, it) })
    } ?: fetchedLyrics
    var pendingPlay by rememberSaveable(saver = pendingSaver) { mutableStateOf<DidlObject?>(null) }
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }

    val colors = rememberCoverColors(selected.artUrl)

    val askBeforeReplace by vm.askBeforeReplace.collectAsStateWithLifecycle()
    val sourcePrefs by vm.sourcePrefs.collectAsStateWithLifecycle()
    val linkedServices by vm.linkedServices.collectAsStateWithLifecycle()
    val sources = de.linuxundich.hausmusik.data.Sources.arrange(
        listOf(de.linuxundich.hausmusik.data.Sources.MUSIC) + linkedServices.map { de.linuxundich.hausmusik.data.Sources.service(it.first) } +
            de.linuxundich.hausmusik.data.Sources.RADIO,
        sourcePrefs,
    ).map { p -> Triple(p.key, linkedServices.firstOrNull { de.linuxundich.hausmusik.data.Sources.service(it.first) == p.key }?.second, p.on) }
    fun requestPlay(item: DidlObject) {
        if (!item.playsDirectly && selected.queueLength > 0) {
            if (askBeforeReplace) { pendingPlay = item; return }
            // Not asked any more: say what happened, the queue is gone.
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.queue_replaced, selected.group.displayName)) }
        }
        vm.play(item, QueueMode.ReplaceAndPlay)
    }

    // Every screen on the stack keeps its state (scroll positions above all) until it is
    // left for good: back from an album lands where the list was, not at its top.
    val screenStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val stackKeys = stack.mapIndexed { i, s -> screenKey(i, s) }
    var shownKeys by remember { mutableStateOf(stackKeys) }
    LaunchedEffect(stackKeys) {
        (shownKeys - stackKeys.toSet()).forEach(screenStates::removeState)
        shownKeys = stackKeys
    }

    val stageActions = StageActions(
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
        onMute = { m -> vm.setMemberMute(selected.id, selected.group.coordinator, m) },
        favorite = now?.favoriteItem?.let { item -> vm.favoriteOf(item, favoritesList) != null },
        onFavorite = { now?.favoriteItem?.let(vm::toggleNowPlayingFavorite) },
        onSettings = { stack.add(Screen.Settings) },
        onDetails = { now?.let { details = DetailsTarget.of(it) } },
        crossfadeOn = selected.crossfade,
        onMusicVideo = { videoOpen = true },
        onCrossfade = { vm.setCrossfade(!selected.crossfade) },
        onLyrics = { if (lyricsEnabled) showLyrics = !showLyrics else lyricsConsent = true },
        timerLabel = selected.sleepRemainingMs?.let { ms ->
            stringResource(R.string.sleep_minutes, ((ms + 59_999) / 60_000).toInt())
        },
    )
    // Landscape on a wide screen: the library opens beside a slim stage, so choosing
    // music and seeing it play need no switch between screens.
    val beside = de.linuxundich.hausmusik.ui.common.isWide() && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    val starting by vm.starting.collectAsStateWithLifecycle()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalItemDetails provides { details = it },
        de.linuxundich.hausmusik.ui.library.LocalStarting provides starting,
        de.linuxundich.hausmusik.ui.library.LocalNowPlaying provides selected.nowPlaying,
        de.linuxundich.hausmusik.ui.library.LocalOpenRooms provides { roomsOpen = true },
    ) {
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = stack.last() to stackKeys.last(),
        transitionSpec = {
            if (targetState.first is Screen.Stage) fadeIn() togetherWith slideOutVertically { it / 3 } + fadeOut()
            else slideInVertically { it / 3 } + fadeIn() togetherWith fadeOut()
        },
        label = "screen",
    ) { (screen, key) ->
        screenStates.SaveableStateProvider(key) {
        when (screen) {
            Screen.Stage -> StageScreen(
                selected, colors,
                stageActions,
                lyrics = if (showLyrics && lyricsEnabled) lyrics ?: LyricsUi.Loading else null,
                busy = stageBusy,
                effects = effects,
            )
            Screen.Library -> BesideStage(beside, { StageSidePanel(selected, colors, stageActions, stageBusy) }) {
                LibraryScreen(
                    library, selected, snackbar,
                    onBack = { stack.removeAt(stack.lastIndex) },
                    onOpenContainer = { stack.add(Screen.Container(it)) },
                    onPlay = ::requestPlay,
                    onMiniPlayer = { stack.removeRange(1, stack.size) },
                    onPlayPause = vm::playPause,
                )
            }
            Screen.Alarms -> AlarmsScreen(hiltViewModel(), onBack = { stack.removeAt(stack.lastIndex) })
            Screen.Settings -> { LaunchedEffect(Unit) { vm.loadLinkedServices() }; SettingsScreen(
                SettingsState(
                    manualHost, selected.group.coordinator.baseUrl.host, lyricsEnabled, artistImagesOn, detailsOn, detailsWikipedia,
                    rooms = household.allPlayers.filter { !it.isInvisible }.map { it.uuid to it.roomName }, everywhereExcluded = everywhereExcluded,
                    musicVideos = videosOn, videoMode = videoMode, effects = effects, askBeforeReplace = askBeforeReplace, sources = sources, stationLogos = stationLogosOn,
                ),
                onConnect = { vm.connectTo(it); stack.removeRange(1, stack.size) },
                onLyrics = vm::setLyricsEnabled,
                onArtistImages = vm::setArtistImagesEnabled,
                onDetails = vm::setDetailsEnabled,
                onDetailsWikipedia = vm::setDetailsWikipedia,
                onEverywhereExcluded = vm::setEverywhereExcluded,
                onMusicVideos = vm::setMusicVideosEnabled,
                onVideoMode = vm::setMusicVideoMode,
                onEffects = { vm.setEffects(it) },
                onAskBeforeReplace = { vm.setAskBeforeReplace(it) },
                onSources = { vm.setSourcePrefs(it) },
                onStationLogos = { vm.setStationLogosEnabled(it) },
                onLicenses = { stack.add(Screen.Licenses) },
                onClearCache = vm::clearListCache,
                onBack = { stack.removeAt(stack.lastIndex) },
            ) }
            Screen.Licenses -> LicensesScreen(onBack = { stack.removeAt(stack.lastIndex) })
            is Screen.Container -> BesideStage(beside, { StageSidePanel(selected, colors, stageActions, stageBusy) }) {
                ContainerScreen(
                    library, screen.item, selected, snackbar,
                    onBack = { stack.removeAt(stack.lastIndex) },
                    onOpenContainer = { stack.add(Screen.Container(it)) },
                    onPlay = ::requestPlay,
                    onMiniPlayer = { stack.removeRange(1, stack.size) },
                    onPlayPause = vm::playPause,
                )
            }
        }
        }
    }

    // One pill at the top for every screen; open sheets place their own host above this one.
    de.linuxundich.hausmusik.ui.common.MessageHost(snackbar, onDark = stack.last() is Screen.Stage)
    if (household.connection == Connection.Reconnecting || household.connection == Connection.Searching) {
        ReconnectingBanner(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    }
    if (household.connection is Connection.NotFound) {
        de.linuxundich.hausmusik.ui.setup.LostBanner(
            onChangeAddress = { connectOpen = true },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp),
        )
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
                onFocus = vm::select,
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
            onAlwaysReplace = { vm.setAskBeforeReplace(false); vm.play(item, QueueMode.ReplaceAndPlay); pendingPlay = null },
        )
    }
    // Lost the household while running: the app stays (it keeps trying, the banner says so);
    // the connect screen only on request, to change the address.
    if (connectOpen && household.connection is Connection.NotFound) {
        BackHandler { connectOpen = false }
        ConnectScreen(household.connection, { connectOpen = false; vm.connect(it) }, { connectOpen = false; vm.searchAgain() })
    }
    } // LocalItemDetails: sheets and dialogs open details too
}

/** Where a screen sits on the stack and what it shows: the key its saved state lives under. */
private fun screenKey(depth: Int, screen: Screen): String = "$depth:" + when (screen) {
    is Screen.Container -> "container:" + screen.item.id
    else -> screen::class.simpleName.orEmpty()
}

/** The slim stage on the left, [content] (library, album) on the right; or only [content]. */
@Composable
private fun BesideStage(beside: Boolean, stage: @Composable () -> Unit, content: @Composable () -> Unit) {
    if (!beside) { content(); return }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(360.dp).fillMaxHeight()) { stage() }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            androidx.compose.runtime.CompositionLocalProvider(de.linuxundich.hausmusik.ui.library.LocalStageBeside provides true) { content() }
        }
    }
}
