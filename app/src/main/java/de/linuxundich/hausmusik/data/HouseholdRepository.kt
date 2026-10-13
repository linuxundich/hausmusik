package de.linuxundich.hausmusik.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.linuxundich.hausmusik.di.AppScope
import de.linuxundich.hausmusik.sonos.Alarm
import de.linuxundich.hausmusik.sonos.AlarmClient
import de.linuxundich.hausmusik.sonos.BrowseResult
import de.linuxundich.hausmusik.sonos.Didl
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.LibrarySearch
import de.linuxundich.hausmusik.sonos.NowPlaying
import de.linuxundich.hausmusik.sonos.PlayMode
import de.linuxundich.hausmusik.sonos.SoapClient
import de.linuxundich.hausmusik.sonos.SonosPlayer
import de.linuxundich.hausmusik.sonos.SonosPlayerClient
import de.linuxundich.hausmusik.sonos.SsdpDiscovery
import de.linuxundich.hausmusik.sonos.playsDirectly
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How a queue-based item should be added. */
enum class QueueMode { ReplaceAndPlay, PlayNext, Append }

/**
 * Keeps the state of the household and sends commands.
 *
 * Phase 0/1: state is polled while the app is in the foreground (once a second
 * for the selected group, every few seconds for the rest). GENA events replace
 * most of the polling later; GetPositionInfo stays the source of truth for the
 * current track either way.
 */
@Singleton
class HouseholdRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val soap: SoapClient,
    private val settings: Settings,
    private val events: SonosEvents,
    @AppScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(Household())
    val state: StateFlow<Household> = _state.asStateFlow()

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var triggerJob: Job? = null
    private val owners = mutableSetOf<String>()
    @Volatile private var entry: HttpUrl? = null

    // Set by events, consumed by the loop.
    private val pendingGroups = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pendingTopology = false

    fun client(player: SonosPlayer) = SonosPlayerClient(player.baseUrl, soap)
    private fun client(url: HttpUrl) = SonosPlayerClient(url, soap)

    /**
     * Keeps the household up to date while at least one owner needs it: the
     * visible activity, or the playback service while music plays.
     */
    @Synchronized
    fun acquire(owner: String) {
        owners += owner
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
        triggerJob = scope.launch { events.triggers.collect(::onTrigger) }
    }

    @Synchronized
    fun release(owner: String) {
        owners -= owner
        if (owners.isNotEmpty()) return
        loop?.cancel()
        triggerJob?.cancel()
        loop = null
        triggerJob = null
        scope.launch { events.stop() }
    }

    @Synchronized
    fun isOwnedBy(owner: String): Boolean = owner in owners

    /** Polls again right away, e.g. after a command or "search again". */
    fun refreshNow() {
        wakeUp.trySend(Unit)
    }

    /** Uses [host] from now on (or SSDP again if null) and forgets the current household. */
    suspend fun connectTo(host: String?) {
        settings.setManualHost(host)
        // Stop the loop first: a poll in flight would otherwise still use the
        // old household and subscribe to it again.
        val restart = synchronized(this) {
            loop?.cancel()
            loop = null
            owners.isNotEmpty()
        }
        events.stop()
        entry = null
        pendingGroups.clear()
        _state.value = Household()
        if (restart) synchronized(this) { if (loop?.isActive != true) loop = scope.launch { run() } }
    }

    private fun onTrigger(t: SonosTrigger) {
        when (t) {
            SonosTrigger.Topology -> pendingTopology = true
            is SonosTrigger.Transport, is SonosTrigger.Volume -> {
                val uuid = if (t is SonosTrigger.Transport) t.playerUuid else (t as SonosTrigger.Volume).playerUuid
                _state.value.groups.firstOrNull { g -> g.group.members.any { it.uuid == uuid } }?.let { pendingGroups += it.id }
            }
        }
        refreshNow()
    }

    private suspend fun run() {
        var failures = 0
        var lastTopology = 0L
        var lastSelected = 0L
        var lastOthers = 0L
        while (true) {
            if (entry == null) {
                _state.update { it.copy(connection = Connection.Searching) }
                entry = findEntry()
                if (entry == null) {
                    _state.update { it.copy(connection = Connection.NotFound(settings.manualHost())) }
                    wakeUp.receive()
                    continue
                }
                lastTopology = 0L
                lastOthers = 0L
            }
            // With events, polling is only a safety net; the progress bar extrapolates.
            val evented = events.active
            val now = System.currentTimeMillis()
            try {
                if (pendingTopology || now - lastTopology > (if (evented) 60_000 else 5_000)) {
                    pendingTopology = false
                    refreshTopology()
                    lastTopology = now
                    val players = _state.value.allPlayers
                    scope.launch { runCatching { events.sync(players) } }
                }
                val ids = mutableSetOf<String>()
                ids += pendingGroups.toList().also { pendingGroups.removeAll(it.toSet()) }
                val selectedId = _state.value.selected?.id
                if (selectedId != null && now - lastSelected > (if (evented) 10_000 else 1_000)) {
                    ids += selectedId
                    lastSelected = now
                }
                if (now - lastOthers > (if (evented) 30_000 else 3_000)) {
                    ids += _state.value.groups.map { it.id }
                    lastOthers = now
                }
                if (ids.isNotEmpty()) refreshGroups(ids)
                failures = 0
                _state.update { it.copy(connection = Connection.Connected) }
            } catch (e: IOException) {
                Log.w(TAG, "poll failed", e)
                if (++failures >= 3) {
                    entry = null
                    failures = 0
                    continue
                }
            }
            withTimeoutOrNull(1000) { wakeUp.receive() }
        }
    }

    /** A manual address wins; otherwise SSDP. Returns a player that answered GetZoneGroupState. */
    private suspend fun findEntry(): HttpUrl? {
        settings.manualHost()?.let { host ->
            val url = hostToUrl(host) ?: return@let
            if (runCatching { client(url).zoneGroups() }.isSuccess) return url
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("hausmusik-ssdp")?.apply { setReferenceCounted(false) }
        return try {
            lock?.acquire()
            val found = runCatching { SsdpDiscovery.search() }.getOrDefault(emptySet())
            found.firstOrNull { runCatching { client(it).zoneGroups() }.isSuccess }
        } finally {
            lock?.release()
        }
    }

    private suspend fun refreshTopology() {
        val e = entry ?: throw IOException("not connected")
        val groups = client(e).zoneGroups()
        val lastRoom = settings.lastRoom()
        _state.update { old ->
            val byId = old.groups.associateBy { it.id }
            val newGroups = groups.map { g -> byId[g.coordinator.uuid]?.copy(group = g) ?: GroupState(g) }
            val selected = old.selectedId?.takeIf { id -> groups.any { it.coordinator.uuid == id } }
                ?: groups.firstOrNull { g -> g.members.any { it.uuid == lastRoom } }?.coordinator?.uuid
                ?: groups.firstOrNull()?.coordinator?.uuid
            old.copy(groups = newGroups, selectedId = selected)
        }
        // New groups have no state yet, changed groups need their member volumes.
        pendingGroups += _state.value.groups.filter { g ->
            g.positionReadAt == 0L || g.group.members.map { it.uuid }.toSet() != g.memberVolumes.keys && g.memberVolumes.isNotEmpty()
        }.map { it.id }
    }

    private suspend fun refreshGroups(ids: Set<String>) = coroutineScope {
        val current = _state.value
        val selectedId = current.selected?.id
        val jobs = current.groups.filter { it.id in ids }.map { gs ->
            async {
                runCatching { readGroup(gs, full = gs.id == selectedId) }
                    .onFailure { if (gs.id == selectedId) throw it }
                    .getOrNull()
            }
        }
        val updated = jobs.awaitAll().filterNotNull().associateBy { it.id }
        _state.update { s ->
            s.copy(groups = s.groups.map { g ->
                val u = updated[g.id] ?: return@map g
                // A volume the user is dragging right now wins over what the speaker reported a moment ago.
                if (volumeInFlight.containsKey(g.id)) u.copy(group = g.group, groupVolume = g.groupVolume) else u.copy(group = g.group)
            })
        }
    }

    private suspend fun readGroup(gs: GroupState, full: Boolean): GroupState = coroutineScope {
        val c = client(gs.group.coordinator)
        val transport = async { c.transportState() }
        val position = async { c.positionInfo() }
        val media = async { c.mediaInfo() }
        val groupVolume = async { c.groupVolume() }
        val now = NowPlaying.from(position.await(), media.await())
        var next = gs.copy(
            transport = transport.await(),
            nowPlaying = now,
            artUrl = c.absoluteArtUrl(now?.albumArtUri),
            queueLength = media.await().numberOfTracks,
            positionReadAt = System.currentTimeMillis(),
        )
        if (full) {
            val actions = async { c.transportActions() }
            val mode = async { runCatching { c.playMode() }.getOrDefault(PlayMode.Normal) }
            val volumes = gs.group.members.map { m -> async { m.uuid to client(m).volume() } }
            val sleep = async { runCatching { c.remainingSleepTimerMs() }.getOrNull()?.takeIf { it > 0 } }
            next = next.copy(
                actions = actions.await(), playMode = mode.await(), memberVolumes = volumes.awaitAll().toMap(),
                sleepRemainingMs = sleep.await(),
            )
        }
        next.copy(groupVolume = groupVolume.await())
    }

    // Volume: the slider moves at once; Sonos gets at most one request per
    // 120 ms per target, always the latest value (learned in Gnomos).

    private val volumeInFlight = ConcurrentHashMap<String, Int>()
    private val volumeSenders = ConcurrentHashMap<String, Job>()

    private fun throttledVolume(key: String, value: Int, send: suspend (Int) -> Unit) {
        volumeInFlight[key] = value
        if (volumeSenders[key]?.isActive == true) return
        volumeSenders[key] = scope.launch {
            var sent: Int? = null
            while (true) {
                val v = volumeInFlight[key] ?: break
                if (v == sent) break
                runCatching { send(v) }
                sent = v
                delay(120)
            }
            volumeInFlight.remove(key)
            pendingGroups += key.substringBefore('/')
            refreshNow()
        }
    }

    fun requestGroupVolume(groupId: String, volume: Int) {
        val v = volume.coerceIn(0, 100)
        _state.update { s -> s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(groupVolume = v) else it }) }
        val coordinator = _state.value.groups.firstOrNull { it.id == groupId }?.group?.coordinator ?: return
        throttledVolume(groupId, v) { client(coordinator).setGroupVolume(it) }
    }

    fun requestMemberVolume(groupId: String, player: SonosPlayer, volume: Int) {
        val v = volume.coerceIn(0, 100)
        _state.update { s ->
            s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(memberVolumes = it.memberVolumes + (player.uuid to v)) else it })
        }
        throttledVolume("$groupId/${player.uuid}", v) { client(player).setVolume(it) }
    }

    /** For the hardware volume buttons: one step up or down for the selected group. */
    fun adjustSelectedVolume(delta: Int) {
        val g = _state.value.selected ?: return
        requestGroupVolume(g.id, g.groupVolume + delta)
    }

    // Commands

    fun select(groupId: String) {
        _state.update { it.copy(selectedId = groupId) }
        scope.launch { settings.setLastRoom(groupId) }
        refreshNow()
    }

    private fun selectedCoordinator(): SonosPlayerClient? = _state.value.selected?.let { client(it.group.coordinator) }

    /** Runs [block] against the selected coordinator and polls right after. */
    suspend fun command(block: suspend (SonosPlayerClient) -> Unit): Result<Unit> {
        val id = _state.value.selected?.id ?: return Result.failure(IOException("no room"))
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        // Read the group again right away instead of waiting for its event.
        return runCatching { block(c) }.also { pendingGroups += id; refreshNow() }
    }

    suspend fun commandFor(groupId: String, block: suspend (SonosPlayerClient) -> Unit): Result<Unit> {
        val g = _state.value.groups.firstOrNull { it.id == groupId } ?: return Result.failure(IOException("no room"))
        return runCatching { block(client(g.group.coordinator)) }.also { pendingGroups += groupId; refreshNow() }
    }

    /** Lets [player] play along with the group [groupId], or leave it. */
    suspend fun setMembership(player: SonosPlayer, groupId: String, member: Boolean): Result<Unit> =
        runCatching {
            if (member) client(player).joinGroup(groupId) else client(player).leaveGroup()
        }.also {
            pendingTopology = true
            refreshNow()
        }

    /**
     * Lets every room of [source] play along with [target]. Returns the moved
     * players so the UI can undo the move.
     */
    suspend fun mergeGroups(source: GroupState, target: GroupState): Result<List<SonosPlayer>> = runCatching {
        source.group.members.forEach { client(it).joinGroup(target.id) }
        source.group.members
    }.also {
        pendingTopology = true
        refreshNow()
    }

    /** Takes the given players out of their groups again (undo of [mergeGroups]). */
    suspend fun separate(players: List<SonosPlayer>): Result<Unit> = runCatching {
        players.forEach { client(it).leaveGroup() }
    }.also {
        pendingTopology = true
        refreshNow()
    }

    // Content

    suspend fun browse(objectId: String, start: Int = 0, count: Int = 100): Result<BrowseResult> {
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return runCatching { c.browse(objectId, start, count) }
    }

    suspend fun browseAll(objectId: String, limit: Int = 3000): Result<List<DidlObject>> {
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return runCatching { c.browseAll(objectId, limit = limit) }
    }

    suspend fun search(term: String): Result<LibrarySearch> {
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return runCatching { c.search(term) }
    }

    fun artUrl(uri: String?): String? = selectedCoordinator()?.absoluteArtUrl(uri)

    /**
     * Plays a favorite, a station, an album or a track in the selected room.
     * Queue items respect [mode]; ReplaceAndPlay clears the queue first, so the
     * UI asks before it uses that mode on a non-empty queue.
     */
    suspend fun play(item: DidlObject, mode: QueueMode): Result<Unit> = command { c ->
        val coordinatorUuid = _state.value.selected!!.id
        if (item.playsDirectly) {
            val metadata = item.resMd ?: Didl.minimal(item.id, item.parentId, item.title, item.upnpClass)
            c.setUri(item.uri!!, metadata)
            c.play()
            return@command
        }
        val metadata = item.resMd ?: Didl.minimal(item.id, item.parentId, item.title, item.upnpClass)
        when (mode) {
            QueueMode.ReplaceAndPlay -> {
                c.removeAllTracksFromQueue()
                c.addToQueue(item.uri!!, metadata)
                c.useQueue(coordinatorUuid)
                c.seekTrack(1)
                c.play()
            }
            QueueMode.PlayNext -> c.addToQueue(item.uri!!, metadata, asNext = true)
            QueueMode.Append -> c.addToQueue(item.uri!!, metadata)
        }
    }

    /** Jumps to a queue position and plays, switching back to the queue if radio was on. */
    suspend fun playQueueTrack(trackNumber: Int): Result<Unit> = command { c ->
        val gs = _state.value.selected!!
        if (gs.nowPlaying?.kind != de.linuxundich.hausmusik.sonos.SourceKind.Queue) c.useQueue(gs.id)
        c.seekTrack(trackNumber)
        c.play()
    }

    // Alarms: stored in the household, any player answers.

    private fun alarmClient(): AlarmClient? = entry?.let { AlarmClient(it, soap) }

    suspend fun alarms(): Result<List<Alarm>> =
        alarmClient()?.let { c -> runCatching { c.list() } } ?: Result.failure(IOException("not connected"))

    /** Creates the alarm if it has no ID yet, otherwise updates it. */
    suspend fun saveAlarm(alarm: Alarm): Result<Unit> = alarmClient()?.let { c ->
        runCatching { if (alarm.id == null) c.create(alarm) else c.update(alarm) }.map { }
    } ?: Result.failure(IOException("not connected"))

    suspend fun deleteAlarm(id: String): Result<Unit> =
        alarmClient()?.let { c -> runCatching { c.delete(id) } } ?: Result.failure(IOException("not connected"))

    // Sound settings, per speaker.

    suspend fun sound(player: SonosPlayer): Result<SoundSettings> = runCatching {
        coroutineScope {
            val c = client(player)
            val bass = async { c.bass() }
            val treble = async { c.treble() }
            val loudness = async { c.loudness() }
            SoundSettings(bass.await(), treble.await(), loudness.await())
        }
    }

    suspend fun setBass(player: SonosPlayer, value: Int) = runCatching { client(player).setBass(value) }
    suspend fun setTreble(player: SonosPlayer, value: Int) = runCatching { client(player).setTreble(value) }
    suspend fun setLoudness(player: SonosPlayer, on: Boolean) = runCatching { client(player).setLoudness(on) }

    // Queue editing (selected group)

    suspend fun removeFromQueue(trackNumber: Int): Result<Unit> = command { it.removeTrackFromQueue(trackNumber) }

    /** Moves a queued track right behind the one that plays now. */
    suspend fun moveToNext(trackNumber: Int): Result<Unit> = command { c ->
        val current = _state.value.selected?.nowPlaying?.queuePosition ?: 0
        if (trackNumber != current && trackNumber != current + 1) c.reorderQueue(trackNumber, 1, current + 1)
    }

    suspend fun clearQueue(): Result<Unit> = command { it.removeAllTracksFromQueue() }

    suspend fun saveQueue(title: String): Result<Unit> = command { it.saveQueue(title) }

    companion object {
        private const val TAG = "Household"

        /** "192.168.0.59" → http://192.168.0.59:1400/, "10.0.2.2:1401" keeps its port. */
        fun hostToUrl(host: String): HttpUrl? {
            val h = host.trim().removePrefix("http://").trimEnd('/')
            if (h.isEmpty()) return null
            val withPort = if (h.contains(':')) h else "$h:1400"
            return "http://$withPort/".toHttpUrlOrNull()
        }
    }
}
