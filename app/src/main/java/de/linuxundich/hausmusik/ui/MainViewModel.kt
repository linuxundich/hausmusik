// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.ui.stage.StageBusy
import de.linuxundich.hausmusik.data.BusyKind
import de.linuxundich.hausmusik.data.DetailsRepository
import de.linuxundich.hausmusik.data.LyricsRepository
import de.linuxundich.hausmusik.data.Settings
import de.linuxundich.hausmusik.sonos.NowPlaying
import de.linuxundich.hausmusik.ui.stage.LyricsUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import de.linuxundich.hausmusik.data.QueueMode
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.PlayMode
import de.linuxundich.hausmusik.sonos.RepeatMode
import de.linuxundich.hausmusik.sonos.SonosPlayer
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: HouseholdRepository,
    private val settings: Settings,
    private val lyricsRepo: LyricsRepository,
    private val detailsRepo: DetailsRepository,
    private val videos: de.linuxundich.hausmusik.data.MusicVideos,
    private val stationLogos: de.linuxundich.hausmusik.data.StationLogos,
    private val musicServices: de.linuxundich.hausmusik.data.services.MusicServices,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    init {
        // Launcher shortcuts with the favorites, once the household is there.
        viewModelScope.launch {
            repo.state.first { it.selected != null }
            repo.browseAll("FV:2").onSuccess { de.linuxundich.hausmusik.system.CommandActivity.publishShortcuts(context, it) }
        }
    }

    val lyricsEnabled: StateFlow<Boolean> = settings.lyricsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val lastRoomName: StateFlow<String?> = settings.lastRoomName.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val manualHost: StateFlow<String?> = settings.manualHost.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setLyricsEnabled(on: Boolean) = viewModelScope.launch { settings.setLyricsEnabled(on) }
    val artistImagesEnabled: StateFlow<Boolean> = settings.artistImagesEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setArtistImagesEnabled(on: Boolean) = viewModelScope.launch { settings.setArtistImagesEnabled(on) }

    val detailsEnabled: StateFlow<Boolean> = settings.detailsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val detailsWikipedia: StateFlow<Boolean> = settings.detailsWikipediaEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setDetailsEnabled(on: Boolean) = viewModelScope.launch { settings.setDetailsEnabled(on) }
    fun setDetailsWikipedia(on: Boolean) = viewModelScope.launch { settings.setDetailsWikipediaEnabled(on) }
    val musicVideosEnabled: StateFlow<Boolean> = settings.musicVideosEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val musicVideoMode: StateFlow<de.linuxundich.hausmusik.data.VideoMode> =
        settings.musicVideoMode.stateIn(viewModelScope, SharingStarted.Eagerly, de.linuxundich.hausmusik.data.VideoMode.Ask)
    fun setMusicVideosEnabled(on: Boolean) = viewModelScope.launch { settings.setMusicVideosEnabled(on) }
    fun setMusicVideoMode(mode: de.linuxundich.hausmusik.data.VideoMode) = viewModelScope.launch { settings.setMusicVideoMode(mode) }
    suspend fun findVideo(title: String, artist: String?) = videos.find(title, artist)

    /** Where the selected room's track is right now, counting on since the speakers were last asked. */
    fun livePositionMs(): Long {
        val g = household.value.selected ?: return 0
        val base = g.nowPlaying?.positionMs ?: 0
        val elapsed = if (g.isPlaying && g.positionReadAt > 0) (System.currentTimeMillis() - g.positionReadAt).coerceAtLeast(0) else 0
        return base + elapsed
    }

    fun pauseSelected() = run { repo.command(BusyKind.PlayPause) { it.pause() } }

    fun setCrossfade(on: Boolean) = viewModelScope.launch { repo.setCrossfade(on) }

    fun joinEverywhere() = viewModelScope.launch {
        repo.joinEverywhere().fold(
            { n -> _messages.send(if (n == 0) R.string.everywhere_none else R.string.everywhere_done) },
            { _messages.send(R.string.item_failed) },
        )
    }

    val everywhereExcluded: StateFlow<Set<String>> = settings.everywhereExcluded.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    fun setEverywhereExcluded(uuids: Set<String>) = viewModelScope.launch { settings.setEverywhereExcluded(uuids) }

    suspend fun artistDetails(name: String) = detailsRepo.artist(name)
    suspend fun albumDetails(title: String, artist: String?) = detailsRepo.album(title, artist)

    val stationLogosEnabled: StateFlow<Boolean> = settings.stationLogosEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setStationLogosEnabled(on: Boolean) = viewModelScope.launch {
        settings.setStationLogosEnabled(on)
        if (on) repo.refreshAll()
    }

    private val _favorites = kotlinx.coroutines.flow.MutableStateFlow<List<DidlObject>>(emptyList())

    /** The Sonos favorites, to show whether what plays is one of them. */
    val favorites: StateFlow<List<DidlObject>> = _favorites

    fun loadFavorites() = viewModelScope.launch { repo.browseAll("FV:2").onSuccess { _favorites.value = it } }

    /**
     * The favorite that holds [item]: the same stream or track, or for a station
     * one of the same name (SWR3 saved through TuneIn plays the same as its stream).
     */
    fun favoriteOf(item: DidlObject?, favorites: List<DidlObject> = _favorites.value): DidlObject? {
        item ?: return null
        favorites.firstOrNull { it.uri != null && it.uri == item.uri }?.let { return it }
        if (!item.isRadio) return null
        val name = de.linuxundich.hausmusik.data.ArtistImages.normalize(item.title)
        return favorites.firstOrNull { it.isRadio && de.linuxundich.hausmusik.data.ArtistImages.normalize(it.title) == name }
    }

    /** Adds what plays now to the Sonos favorites, or takes it out again (the heart on the stage). */
    fun toggleNowPlayingFavorite(item: DidlObject) = viewModelScope.launch {
        val fav = favoriteOf(item)
        val result = if (fav != null) repo.removeFavorite(fav.id) else repo.addFavorite(item)
        _messages.send(
            when {
                result.isFailure -> R.string.item_failed
                fav != null -> R.string.favorite_removed
                else -> R.string.favorite_added
            },
        )
        loadFavorites()
    }

    val sourcePrefs: StateFlow<List<de.linuxundich.hausmusik.data.SourcePref>> =
        settings.librarySources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _linkedServices = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<Int, String>>>(emptyList())

    /** The linked music services (ID, name), for arranging the library's sources in Settings. */
    val linkedServices: StateFlow<List<Pair<Int, String>>> = _linkedServices

    fun loadLinkedServices() = viewModelScope.launch {
        // The same services the library shows as sources: linked ones and those without an account (TuneIn).
        runCatching { musicServices.overview() }.onSuccess { l ->
            _linkedServices.value = l.filter {
                it.isContainer && it.id != de.linuxundich.hausmusik.data.services.ServiceIds.MORE &&
                    it.upnpClass != de.linuxundich.hausmusik.data.services.ServiceIds.LINK_CLASS
            }.mapNotNull { d -> de.linuxundich.hausmusik.data.services.ServiceIds.parse(d.id)?.first?.let { it to d.title } }
        }
    }

    fun setSourcePrefs(list: List<de.linuxundich.hausmusik.data.SourcePref>) = viewModelScope.launch { settings.setLibrarySources(list) }

    val askBeforeReplace: StateFlow<Boolean> = settings.askBeforeReplace.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setAskBeforeReplace(on: Boolean) = viewModelScope.launch { settings.setAskBeforeReplace(on) }

    val effects: StateFlow<de.linuxundich.hausmusik.data.Effects> =
        settings.effects.stateIn(viewModelScope, SharingStarted.Eagerly, de.linuxundich.hausmusik.data.Effects())

    /** Also switches the screensaver on or off in the system's list, so it only shows up there when wanted. */
    fun setEffects(e: de.linuxundich.hausmusik.data.Effects) = viewModelScope.launch {
        settings.setEffects(e)
        de.linuxundich.hausmusik.system.NightStageDream.setAvailable(context, e.nightStage)
    }

    val lyricsOffsets: StateFlow<Map<String, Int>> = settings.lyricsOffsets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    fun setLyricsOffset(track: String, ms: Int) = viewModelScope.launch { settings.setLyricsOffset(track, ms) }

    /** The track a lyrics offset belongs to: the same song on another album keeps its own. */
    fun lyricsTrack(now: NowPlaying): String = de.linuxundich.hausmusik.data.lyricsTrackKey(now)

    suspend fun lyrics(now: NowPlaying): LyricsUi =
        lyricsRepo.find(now.title, now.artist, now.album, now.durationMs).fold(
            { if (it == null) LyricsUi.None else LyricsUi.Found(it) },
            { LyricsUi.Failed },
        )

    fun clearListCache() = viewModelScope.launch {
        repo.clearListCache()
        detailsRepo.clear()
        videos.clear()
        stationLogos.clear()
        _messages.send(R.string.cache_cleared)
    }

    fun connectTo(host: String?) = viewModelScope.launch { repo.connectTo(host?.ifBlank { null }) }


    val household = repo.state

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private fun run(@StringRes failure: Int = R.string.error_command, block: suspend () -> Result<Unit>) {
        viewModelScope.launch { block().onFailure { _messages.send(failure) } }
    }

    fun playPause() = run {
        val playing = household.value.selected?.isPlaying == true
        repo.command(BusyKind.PlayPause) { if (playing) it.pause() else it.play() }
    }

    fun playPause(groupId: String) = run {
        val playing = household.value.groups.firstOrNull { it.id == groupId }?.isPlaying == true
        repo.commandFor(groupId, BusyKind.PlayPause) { if (playing) it.pause() else it.play() }
    }

    /** What the selected room is still waiting for, for the stage's indicators. */
    val stageBusy: StateFlow<StageBusy> = kotlinx.coroutines.flow.combine(repo.busy, household) { busy, h ->
        val id = h.selected?.id
        StageBusy(
            play = busy.any { it.kind == BusyKind.PlayPause && it.groupId == id },
            other = busy.any { it.kind == BusyKind.Other && (it.groupId == id || it.groupId == null) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StageBusy())

    fun next() = run { repo.command { it.next() } }

    /** Like Gnomos: a few seconds into a track, "previous" restarts it. */
    fun previous() = run {
        val now = household.value.selected?.nowPlaying
        val restart = (now?.positionMs ?: 0) > 4000 || household.value.selected?.actions?.canPrevious == false
        repo.command { if (restart) it.seekTo(0) else it.previous() }
    }

    fun seek(ms: Long) = run { repo.command { it.seekTo(ms) } }

    fun setGroupVolume(groupId: String, v: Int) = repo.requestGroupVolume(groupId, v)

    fun setMemberVolume(groupId: String, player: SonosPlayer, v: Int) = repo.requestMemberVolume(groupId, player, v)

    fun setMemberMute(groupId: String, player: SonosPlayer, mute: Boolean) = run { repo.setMemberMute(groupId, player, mute) }

    fun select(groupId: String) = repo.select(groupId)

    fun setMembership(player: SonosPlayer, member: Boolean) {
        val groupId = household.value.selected?.id ?: return
        run { repo.setMembership(player, groupId, member) }
    }

    /** Merges [source] into [target]; returns an undo action, or null when it failed. */
    suspend fun merge(source: GroupState, target: GroupState): (suspend () -> Unit)? =
        repo.mergeGroups(source, target).fold(
            { moved -> { repo.separate(moved).onFailure { _messages.send(R.string.error_command) } } },
            { _messages.send(R.string.error_command); null },
        )

    fun pauseAll() = run {
        runCatching {
            household.value.groups.filter { it.isPlaying }.forEach { g -> repo.commandFor(g.id) { it.pause() }.getOrThrow() }
        }
    }

    fun toggleShuffle() = run {
        val m = household.value.selected?.playMode ?: PlayMode.Normal
        repo.command { it.setPlayMode(m.copy(shuffle = !m.shuffle)) }
    }

    fun cycleRepeat() = run {
        val m = household.value.selected?.playMode ?: PlayMode.Normal
        val next = when (m.repeat) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        repo.command { it.setPlayMode(m.copy(repeat = next)) }
    }

    fun setSleepTimer(minutes: Int?) = run { repo.command { it.configureSleepTimer(minutes?.let { m -> m * 60_000L }) } }

    suspend fun sound(player: SonosPlayer) = repo.sound(player)
    fun setBass(player: SonosPlayer, v: Int) = run { repo.setBass(player, v) }
    fun setTreble(player: SonosPlayer, v: Int) = run { repo.setTreble(player, v) }
    fun setLoudness(player: SonosPlayer, on: Boolean) = run { repo.setLoudness(player, on) }

    private val _starting = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** What "Play now" is starting: a long queue can take seconds before music plays. */
    val starting: StateFlow<String?> = _starting

    fun play(item: DidlObject, mode: QueueMode) = viewModelScope.launch {
        if (mode == QueueMode.ReplaceAndPlay) _starting.value = item.title
        repo.play(item, mode)
            .also { if (mode == QueueMode.ReplaceAndPlay) _starting.value = null }
            .onSuccess {
                when (mode) {
                    QueueMode.PlayNext -> _messages.send(R.string.added_next)
                    QueueMode.Append -> _messages.send(R.string.added_to_queue)
                    QueueMode.ReplaceAndPlay -> Unit
                }
            }
            .onFailure {
                android.util.Log.w("Hausmusik", "play ${item.id} (${item.uri}) failed", it)
                _messages.send(R.string.error_command)
            }
    }

    fun playQueueTrack(trackNumber: Int) = run { repo.playQueueTrack(trackNumber) }

    suspend fun loadQueue(): Result<List<DidlObject>> = repo.browseAll("Q:0", limit = 1000)

    suspend fun removeFromQueue(nr: Int) = repo.removeFromQueue(nr).also { r -> r.onFailure { _messages.send(R.string.error_command) } }
    suspend fun moveInQueue(from: Int, to: Int) = repo.moveInQueue(from, to).also { r -> r.onFailure { _messages.send(R.string.error_command) } }
    suspend fun moveToNext(nr: Int) = repo.moveToNext(nr).also { r -> r.onFailure { _messages.send(R.string.error_command) } }
    suspend fun clearQueue() = repo.clearQueue().also { r -> r.onFailure { _messages.send(R.string.error_command) } }
    suspend fun saveQueue(title: String) = repo.saveQueue(title).also { r ->
        r.onSuccess { _messages.send(R.string.queue_saved) }.onFailure { _messages.send(R.string.error_command) }
    }

    fun connect(host: String) = viewModelScope.launch {
        repo.connectTo(host)
    }

    fun searchAgain() = repo.refreshNow()
}
