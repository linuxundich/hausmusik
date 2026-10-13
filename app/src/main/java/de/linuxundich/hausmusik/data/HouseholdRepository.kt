package de.linuxundich.hausmusik.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.linuxundich.hausmusik.di.AppScope
import de.linuxundich.hausmusik.sonos.BrowseResult
import de.linuxundich.hausmusik.sonos.Didl
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.NowPlaying
import de.linuxundich.hausmusik.sonos.PlayMode
import de.linuxundich.hausmusik.sonos.SoapClient
import de.linuxundich.hausmusik.sonos.SonosPlayer
import de.linuxundich.hausmusik.sonos.SonosPlayerClient
import de.linuxundich.hausmusik.sonos.SsdpDiscovery
import de.linuxundich.hausmusik.sonos.ZoneGroup
import de.linuxundich.hausmusik.sonos.playsDirectly
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
    @AppScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(Household())
    val state: StateFlow<Household> = _state.asStateFlow()

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    @Volatile private var entry: HttpUrl? = null
    private var topology: List<ZoneGroup> = emptyList()

    fun client(player: SonosPlayer) = SonosPlayerClient(player.baseUrl, soap)
    private fun client(url: HttpUrl) = SonosPlayerClient(url, soap)

    /** Starts polling. Called when the UI becomes visible. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** Polls again right away, e.g. after a command or "search again". */
    fun refreshNow() {
        wakeUp.trySend(Unit)
    }

    /** Uses [host] from now on (or SSDP again if null) and forgets the current household. */
    suspend fun connectTo(host: String?) {
        settings.setManualHost(host)
        entry = null
        _state.value = Household()
        refreshNow()
    }

    private suspend fun run() {
        var tick = 0
        var failures = 0
        while (true) {
            if (entry == null) {
                _state.update { it.copy(connection = Connection.Searching) }
                entry = findEntry()
                if (entry == null) {
                    _state.update { it.copy(connection = Connection.NotFound(settings.manualHost())) }
                    wakeUp.receive()
                    continue
                }
                tick = 0
            }
            try {
                if (tick % 5 == 0) refreshTopology()
                refreshGroups(includeOthers = tick % 3 == 0)
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
            tick++
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
        val groups = client(entry!!).zoneGroups()
        topology = groups
        val lastRoom = settings.lastRoom()
        _state.update { old ->
            val byId = old.groups.associateBy { it.id }
            val newGroups = groups.map { g -> byId[g.coordinator.uuid]?.copy(group = g) ?: GroupState(g) }
            val selected = old.selectedId?.takeIf { id -> groups.any { it.coordinator.uuid == id } }
                ?: groups.firstOrNull { g -> g.members.any { it.uuid == lastRoom } }?.coordinator?.uuid
                ?: groups.firstOrNull()?.coordinator?.uuid
            old.copy(groups = newGroups, selectedId = selected)
        }
    }

    private suspend fun refreshGroups(includeOthers: Boolean) = coroutineScope {
        val current = _state.value
        val selectedId = current.selected?.id
        val jobs = current.groups.filter { includeOthers || it.id == selectedId }.map { gs ->
            async {
                runCatching { readGroup(gs, full = gs.id == selectedId) }
                    .onFailure { if (gs.id == selectedId) throw it }
                    .getOrNull()
            }
        }
        val updated = jobs.awaitAll().filterNotNull().associateBy { it.id }
        _state.update { s -> s.copy(groups = s.groups.map { updated[it.id]?.copy(group = it.group) ?: it }) }
    }

    private suspend fun readGroup(gs: GroupState, full: Boolean): GroupState = coroutineScope {
        val c = client(gs.group.coordinator)
        val transport = async { c.transportState() }
        val position = async { c.positionInfo() }
        val media = async { c.mediaInfo() }
        val now = NowPlaying.from(position.await(), media.await())
        var next = gs.copy(
            transport = transport.await(),
            nowPlaying = now,
            artUrl = c.absoluteArtUrl(now?.albumArtUri),
            queueLength = media.await().numberOfTracks,
            positionReadAt = System.currentTimeMillis(),
        )
        val groupVolume = async { c.groupVolume() }
        if (full) {
            val actions = async { c.transportActions() }
            val mode = async { runCatching { c.playMode() }.getOrDefault(PlayMode.Normal) }
            val volumes = gs.group.members.map { m -> async { m.uuid to client(m).volume() } }
            next = next.copy(actions = actions.await(), playMode = mode.await(), memberVolumes = volumes.awaitAll().toMap())
        }
        next.copy(groupVolume = groupVolume.await())
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
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return runCatching { block(c) }.also { refreshNow() }
    }

    suspend fun commandFor(groupId: String, block: suspend (SonosPlayerClient) -> Unit): Result<Unit> {
        val g = _state.value.groups.firstOrNull { it.id == groupId } ?: return Result.failure(IOException("no room"))
        return runCatching { block(client(g.group.coordinator)) }.also { refreshNow() }
    }

    fun setGroupVolumeOptimistic(groupId: String, volume: Int) =
        _state.update { s -> s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(groupVolume = volume) else it }) }

    fun setMemberVolumeOptimistic(groupId: String, uuid: String, volume: Int) =
        _state.update { s ->
            s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(memberVolumes = it.memberVolumes + (uuid to volume)) else it })
        }

    suspend fun setMemberVolume(player: SonosPlayer, volume: Int) = runCatching { client(player).setVolume(volume) }

    /** Lets [player] play along with the group [groupId], or leave it. */
    suspend fun setMembership(player: SonosPlayer, groupId: String, member: Boolean): Result<Unit> =
        runCatching {
            if (member) client(player).joinGroup(groupId) else client(player).leaveGroup()
        }.also {
            refreshTopologyNow()
        }

    private suspend fun refreshTopologyNow() {
        runCatching { refreshTopology() }
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
