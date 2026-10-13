// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.linuxundich.hausmusik.BuildConfig
import de.linuxundich.hausmusik.data.history.CachedList
import de.linuxundich.hausmusik.data.history.CachedListDao
import de.linuxundich.hausmusik.data.net.LocalNetwork
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
    private val local: LocalNetwork,
    private val cache: CachedListDao,
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

    /**
     * Set by the music services: the service's own cover for an item the
     * speaker only offers as a slow, small `/getaa` copy (bonob: ~1.7 s and
     * 5 KB on an S1, the original comes from the music server at once).
     */
    @Volatile var coverResolver: ((String) -> String?)? = null

    /** The best cover URL known right now: the service's own, else the speaker's. Never waits. */
    fun coverFor(c: SonosPlayerClient, albumArtUri: String?): String? =
        albumArtUri?.let { uri -> coverResolver?.invoke(uri) } ?: c.absoluteArtUrl(albumArtUri)

    /** Reads the selected room again soon, e.g. when a better cover turned up. */
    fun refreshSelected() {
        _state.value.selected?.let { pendingGroups += it.id }
        refreshNow()
    }

    // Waiting for the speakers: a command counts as busy from the moment it is
    // sent until a read of its group (or of the topology) started after the
    // command had finished, so the UI shows the change, not just the request.
    private val busySeq = java.util.concurrent.atomic.AtomicLong()
    private val _busy = MutableStateFlow<List<Busy>>(emptyList())
    val busy: StateFlow<List<Busy>> = _busy.asStateFlow()

    private fun beginBusy(groupId: String?, kind: BusyKind): Long {
        val id = busySeq.incrementAndGet()
        _busy.update { it + Busy(id, groupId, kind) }
        // A safety net: never spin for longer than this.
        scope.launch { delay(BUSY_LIMIT_MS); endBusy(id) }
        return id
    }

    private fun commandDone(id: Long) {
        val at = System.currentTimeMillis()
        _busy.update { list -> list.map { if (it.id == id) it.copy(finishedAt = at) else it } }
    }

    private fun endBusy(id: Long) = _busy.update { list -> list.filterNot { it.id == id } }

    /** Called after a read that began at [startedAt]: what it read is now confirmed. */
    private fun settleBusy(startedAt: Long, groupIds: Set<String>, topology: Boolean) {
        if (_busy.value.isEmpty()) return
        _busy.update { list ->
            list.filterNot { b ->
                val done = b.finishedAt ?: return@filterNot false
                done <= startedAt && (if (b.groupId == null) topology else b.groupId in groupIds)
            }
        }
    }
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
        watchNetwork()
    }

    // Network: the speakers live in the home Wi-Fi. Without Wi-Fi or Ethernet
    // (mobile data) there is nothing to search; a new Wi-Fi means a new own
    // address for the event callbacks and maybe other speakers.

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val localNetworks = ConcurrentHashMap.newKeySet<Network>()
    @Volatile private var lastLocalNetwork: Network? = null

    /** True while the phone has Wi-Fi or Ethernet. */
    @Suppress("DEPRECATION") // allNetworks: the simple way to see a Wi-Fi that is not the default network
    fun hasLocalNetwork(): Boolean {
        if (localNetworks.isNotEmpty()) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasOffline = localNetworks.isEmpty() && _state.value.connection == Connection.NoLocalNetwork
                val previous = lastLocalNetwork
                localNetworks += network
                lastLocalNetwork = network
                when {
                    wasOffline -> {
                        Log.i(TAG, "local network back, connecting")
                        scope.launch { reconnect() }
                    }
                    previous != null && previous != network -> {
                        Log.i(TAG, "network changed, reconnecting")
                        scope.launch { reconnect() }
                    }
                }
            }

            override fun onLost(network: Network) {
                localNetworks -= network
                if (localNetworks.isEmpty()) {
                    Log.i(TAG, "no local network")
                    scope.launch {
                        if (settings.manualHost() == null) {
                            events.stop()
                            entry = null
                            _state.update { it.copy(connection = Connection.NoLocalNetwork) }
                        } else {
                            // A fixed address may still answer (VPN): check right away.
                            _state.value.selected?.let { pendingGroups += it.id }
                            _state.update { it.copy(connection = Connection.Reconnecting) }
                        }
                        refreshNow()
                    }
                }
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        runCatching { cm.registerNetworkCallback(request, callback) }.onSuccess { networkCallback = callback }
    }

    private fun unwatchNetwork() {
        val cb = networkCallback ?: return
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        networkCallback = null
        localNetworks.clear()
        lastLocalNetwork = null
    }

    /** Same address setting, fresh connection and subscriptions. */
    suspend fun reconnect() = connectTo(settings.manualHost())

    @Synchronized
    fun release(owner: String) {
        owners -= owner
        if (owners.isNotEmpty()) return
        loop?.cancel()
        triggerJob?.cancel()
        loop = null
        triggerJob = null
        unwatchNetwork()
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

    // List versions from ContentDirectory events, for the library cache.
    private val _libraryVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val libraryVersions: StateFlow<Map<String, String>> = _libraryVersions.asStateFlow()

    /**
     * The version a cached list of [objectId] must match: the music share index
     * for A:, favorites, saved queues (Sonos playlists), radio. Null when Sonos
     * has not told us yet, or for lists that change all the time (the queue).
     */
    fun versionFor(objectId: String): String? {
        val v = _libraryVersions.value
        return when {
            objectId.startsWith("A:") -> v["ShareListUpdateID"]
            objectId.startsWith("FV:") -> v["FavoritesUpdateID"]
            objectId.startsWith("SQ:") -> v["SavedQueuesUpdateID"]
            objectId.startsWith("R:") -> v["RadioFavoritesUpdateID"]
            else -> null
        }
    }

    /** A key for this household, so two homes do not share a cache. */
    fun householdKey(): String? = _state.value.allPlayers.minOfOrNull { it.uuid }

    private fun onTrigger(t: SonosTrigger) {
        when (t) {
            SonosTrigger.Topology -> pendingTopology = true
            is SonosTrigger.Library -> {
                val keep = t.properties.filterKeys { it.endsWith("UpdateID") }
                if (keep.isNotEmpty()) _libraryVersions.update { it + keep }
                return
            }
            is SonosTrigger.Transport, is SonosTrigger.Volume -> {
                val uuid = when (t) {
                    is SonosTrigger.Transport -> t.playerUuid
                    is SonosTrigger.Volume -> t.playerUuid
                    else -> return
                }
                _state.value.groups.firstOrNull { g -> g.group.members.any { it.uuid == uuid } }?.let { pendingGroups += it.id }
            }
        }
        refreshNow()
    }

    private suspend fun run() {
        var failures = 0
        var misses = 0
        var lastTopology = 0L
        var lastSelected = 0L
        var lastOthers = 0L
        while (true) {
            if (entry == null) {
                val local = hasLocalNetwork()
                val manual = settings.manualHost()
                // Mobile data only: no search, no timeouts. A fixed address is still
                // tried once, it may be reachable through a VPN.
                if (!local && manual == null) {
                    _state.update { it.copy(connection = Connection.NoLocalNetwork) }
                    wakeUp.receive()
                    continue
                }
                _state.update {
                    it.copy(
                        connection = when {
                            // Retrying a fixed address without Wi-Fi: keep the mask, no flicker.
                            !local && it.connection == Connection.NoLocalNetwork -> Connection.NoLocalNetwork
                            it.groups.isEmpty() -> Connection.Searching
                            else -> Connection.Reconnecting
                        },
                    )
                }
                entry = findEntry(searchNetwork = local)
                if (entry == null) {
                    _state.update {
                        it.copy(connection = if (!hasLocalNetwork()) Connection.NoLocalNetwork else Connection.NotFound(manual))
                    }
                    // Fresh Wi-Fi often is not usable for a few seconds, and speakers
                    // may be rebooting: look again soon, then every half minute
                    // (or at once on "search again").
                    val wait = RETRY_DELAYS.getOrElse(misses++) { RETRY_DELAYS.last() }
                    withTimeoutOrNull(wait) { wakeUp.receive() }
                    continue
                }
                lastTopology = 0L
                lastOthers = 0L
                misses = 0
            }
            // With events, polling is only a safety net; the progress bar extrapolates.
            val evented = events.active
            val now = System.currentTimeMillis()
            try {
                var readSomething = false
                var readTopology = false
                if (pendingTopology || now - lastTopology > (if (evented) 60_000 else 5_000)) {
                    readSomething = true
                    readTopology = true
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
                if (ids.isNotEmpty()) {
                    refreshGroups(ids)
                    readSomething = true
                }
                settleBusy(now, ids, readTopology)
                // Only a successful read proves the speakers are there; a quiet
                // tick between polls must not clear a failure.
                if (readSomething) {
                    failures = 0
                    _state.update { it.copy(connection = Connection.Connected) }
                }
            } catch (e: IOException) {
                Log.w(TAG, "poll failed: ${e.message}")
                // A slow library request keeps an S1 player busy; that is not a lost speaker.
                if (e is java.io.InterruptedIOException && slowRequests.get() > 0) {
                    withTimeoutOrNull(1000) { wakeUp.receive() }
                    continue
                }
                if (!hasLocalNetwork()) {
                    // Without Wi-Fi a second try will not do better: start over at
                    // once (a fixed address gets one more try there, e.g. over VPN).
                    events.stop()
                    entry = null
                    failures = 0
                    _state.update { it.copy(connection = Connection.NoLocalNetwork) }
                    continue
                }
                lastSelected = 0L // try again on the next tick, not in ten seconds
                // S1 players stall now and then (e.g. while answering many
                // subscriptions): one miss is no reason for the band yet, and
                // timeouts get one more try before giving up.
                failures++
                if (failures >= 2) _state.update { it.copy(connection = Connection.Reconnecting) }
                val limit = if (e is java.net.SocketTimeoutException) 4 else 3
                if (failures >= limit) {
                    // Rebooted speakers do not know our subscriptions any more.
                    events.stop()
                    entry = null
                    failures = 0
                    continue
                }
            }
            withTimeoutOrNull(1000) { wakeUp.receive() }
        }
    }

    /** A manual address wins; otherwise SSDP. Returns a player that answered GetZoneGroupState. */
    private suspend fun findEntry(searchNetwork: Boolean = true): HttpUrl? {
        settings.manualHost()?.let { host ->
            val url = hostToUrl(host) ?: return@let
            if (runCatching { client(url).zoneGroups() }.isSuccess) return url
        }
        if (!searchNetwork) return null
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("hausmusik-ssdp")?.apply { setReferenceCounted(false) }
        return try {
            lock?.acquire()
            // The search goes out on Wi-Fi even if the system prefers mobile data.
            val net = local.current
            val found = runCatching { SsdpDiscovery.search { socket -> net?.bindSocket(socket) } }.getOrDefault(emptySet())
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
        _state.value.selected?.group?.displayName?.let { settings.setLastRoomName(it) }
        // New groups have no state yet, changed groups need their member volumes.
        pendingGroups += _state.value.groups.filter { g ->
            g.positionReadAt == 0L || g.group.members.map { it.uuid }.toSet() != g.memberVolumes.keys && g.memberVolumes.isNotEmpty()
        }.map { it.id }
    }

    private suspend fun refreshGroups(ids: Set<String>) = coroutineScope {
        if (volumeCap < 100 && _state.value.groups.any { g -> g.memberVolumes.values.any { it > volumeCap } }) {
            launch { enforceVolumeCap() }
        }
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
        // Mute for every group, not only the selected one: the room tiles show it.
        val mutes = gs.group.members.map { m -> async { m.uuid to runCatching { client(m).mute() }.getOrDefault(false) } }
        val now = NowPlaying.from(position.await(), media.await())
        var next = gs.copy(
            transport = transport.await(),
            nowPlaying = now,
            artUrl = coverFor(c, now?.albumArtUri),
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
        next.copy(groupVolume = groupVolume.await(), memberMutes = mutes.awaitAll().toMap())
    }

    // Volume: the slider moves at once; Sonos gets at most one request per
    // 120 ms per target, always the latest value (learned in Gnomos).

    private val volumeInFlight = ConcurrentHashMap<String, Int>()
    private val volumeSenders = ConcurrentHashMap<String, Job>()

    private fun throttledVolume(key: String, value: Int, send: suspend (Int) -> Unit) {
        volumeInFlight[key] = value
        if (volumeSenders[key]?.isActive == true) return
        val token = beginBusy(key.substringBefore('/'), BusyKind.Other)
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
            commandDone(token)
            pendingGroups += key.substringBefore('/')
            refreshNow()
        }
    }

    /** Debug builds: the test cap from the settings; release builds never have one. */
    @Volatile private var volumeCap: Int = 100

    init {
        if (BuildConfig.DEBUG) scope.launch {
            settings.debugVolumeCap.collect {
                volumeCap = it ?: 100
                if (it != null) enforceVolumeCap()
            }
        }
    }

    /** Debug cap: puts every speaker above the cap back down to it. */
    private suspend fun enforceVolumeCap() {
        val cap = volumeCap
        if (cap >= 100) return
        for (p in _state.value.allPlayers) runCatching {
            val c = client(p)
            if (c.volume() > cap) {
                Log.w(TAG, "volume cap: ${p.roomName} down to $cap")
                c.setVolume(cap)
            }
        }
    }

    fun requestGroupVolume(groupId: String, volume: Int) {
        var v = volume.coerceIn(0, volumeCap)
        // Sonos scales the members proportionally, so the loudest one may end up
        // above the group value: keep it at or below the cap as well.
        if (volumeCap < 100) {
            val g = _state.value.groups.firstOrNull { it.id == groupId }
            val loudest = g?.memberVolumes?.values?.maxOrNull() ?: 0
            val current = g?.groupVolume ?: 0
            if (loudest > 0 && current > 0) v = v.coerceAtMost(volumeCap * current / loudest)
        }
        _state.update { s -> s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(groupVolume = v) else it }) }
        val coordinator = _state.value.groups.firstOrNull { it.id == groupId }?.group?.coordinator ?: return
        throttledVolume(groupId, v) { client(coordinator).setGroupVolume(it) }
    }

    /** Mutes or unmutes one speaker of [groupId]; the switch shows the new state at once. */
    suspend fun setMemberMute(groupId: String, player: SonosPlayer, mute: Boolean): Result<Unit> {
        _state.update { s ->
            s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(memberMutes = it.memberMutes + (player.uuid to mute)) else it })
        }
        return busy(groupId, BusyKind.Other) { client(player).setMute(mute) }
    }

    fun requestMemberVolume(groupId: String, player: SonosPlayer, volume: Int) {
        val v = volume.coerceIn(0, volumeCap)
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
        scope.launch {
            settings.setLastRoom(groupId)
            _state.value.selected?.group?.displayName?.let { settings.setLastRoomName(it) }
        }
        refreshNow()
    }

    fun selectedCoordinator(): SonosPlayerClient? = _state.value.selected?.let { client(it.group.coordinator) }

    /** Runs [block] against the selected coordinator and polls right after. */
    suspend fun command(kind: BusyKind = BusyKind.Other, block: suspend (SonosPlayerClient) -> Unit): Result<Unit> {
        val id = _state.value.selected?.id ?: return Result.failure(IOException("no room"))
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return busy(id, kind) { block(c) }
    }

    suspend fun commandFor(groupId: String, kind: BusyKind = BusyKind.Other, block: suspend (SonosPlayerClient) -> Unit): Result<Unit> {
        val g = _state.value.groups.firstOrNull { it.id == groupId } ?: return Result.failure(IOException("no room"))
        return busy(groupId, kind) { block(client(g.group.coordinator)) }
    }

    /** Runs [block] as busy for [groupId], then reads the group again right away instead of waiting for its event. */
    private suspend fun <T> busy(groupId: String, kind: BusyKind, block: suspend () -> T): Result<T> {
        val token = beginBusy(groupId, kind)
        return runCatching { block() }.also {
            if (it.isFailure) endBusy(token) else commandDone(token)
            pendingGroups += groupId
            refreshNow()
        }
    }

    /** Like [busy] for changes of the groups themselves; settles with the next topology read. */
    private suspend fun <T> topologyBusy(block: suspend () -> T): Result<T> {
        val token = beginBusy(null, BusyKind.Other)
        return runCatching { block() }.also {
            if (it.isFailure) endBusy(token) else commandDone(token)
            pendingTopology = true
            refreshNow()
        }
    }

    /** Lets [player] play along with the group [groupId], or leave it. */
    suspend fun setMembership(player: SonosPlayer, groupId: String, member: Boolean): Result<Unit> =
        topologyBusy { if (member) client(player).joinGroup(groupId) else client(player).leaveGroup() }

    /**
     * Lets every room of [source] play along with [target]. Returns the moved
     * players so the UI can undo the move.
     */
    suspend fun mergeGroups(source: GroupState, target: GroupState): Result<List<SonosPlayer>> = topologyBusy {
        source.group.members.forEach { client(it).joinGroup(target.id) }
        source.group.members
    }

    /** Takes the given players out of their groups again (undo of [mergeGroups]). */
    suspend fun separate(players: List<SonosPlayer>): Result<Unit> = topologyBusy {
        players.forEach { client(it).leaveGroup() }
    }

    // Content

    suspend fun browse(objectId: String, start: Int = 0, count: Int = 100): Result<BrowseResult> {
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return runCatching { c.browse(objectId, start, count) }
    }

    private val slowRequests = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun <T> slow(block: suspend () -> T): Result<T> = runCatching { whileBusy(block) }

    /**
     * Runs a long request to the speakers (library, music services). Meanwhile
     * a timed-out poll does not count as a lost speaker: an S1 answers one
     * request after another, and the reconnecting band would show for nothing.
     */
    suspend fun <T> whileBusy(block: suspend () -> T): T {
        slowRequests.incrementAndGet()
        return try { block() } finally { slowRequests.decrementAndGet() }
    }

    suspend fun browseAll(objectId: String, limit: Int = 3000): Result<List<DidlObject>> {
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return slow { c.browseAll(objectId, limit = limit) }
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(DidlObject.serializer())

    /** The stored list and whether it still matches what Sonos reports. */
    data class Cached(val items: List<DidlObject>, val current: Boolean)

    suspend fun cachedList(objectId: String): Cached? {
        val household = householdKey() ?: return null
        val row = cache.get(household, objectId) ?: return null
        val items = runCatching { json.decodeFromString(listSerializer, row.json) }.getOrNull() ?: return null
        val version = versionFor(objectId)
        val current = version != null && version == row.version
        Log.d(TAG, "cache $objectId: ${items.size} items, ${if (current) "current" else "stale (${row.version} vs $version)"}")
        return Cached(items, current)
    }

    /** Loads the whole list from Sonos and stores it with the current version. */
    suspend fun loadAndCache(objectId: String): Result<List<DidlObject>> {
        val version = versionFor(objectId)
        Log.d(TAG, "load $objectId from Sonos (version $version)")
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return slow { c.browseComplete(objectId) }.map { listing ->
            val household = householdKey()
            // Only a complete list is worth keeping; a partial one would stick until the version changes.
            if (listing.complete && household != null && objectId != "Q:0") {
                cache.put(CachedList(household, objectId, version, System.currentTimeMillis(), json.encodeToString(listSerializer, listing.items)))
            } else if (!listing.complete) {
                Log.w(TAG, "$objectId incomplete: ${listing.items.size} of ${listing.total}, not cached")
            }
            listing.items
        }
    }

    suspend fun clearListCache() = cache.clear()

    suspend fun search(term: String): Result<LibrarySearch> {
        val c = selectedCoordinator() ?: return Result.failure(IOException("no room"))
        return slow { c.search(term) }
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
        val capped = alarm.copy(volume = alarm.volume.coerceAtMost(volumeCap))
        runCatching { if (capped.id == null) c.create(capped) else c.update(capped) }.map { }
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

    /**
     * Moves the track at [from] so it ends up at [to] (1-based). Sonos counts
     * InsertBefore in the old numbering, so moving down needs one more.
     */
    suspend fun moveInQueue(from: Int, to: Int): Result<Unit> = command { c ->
        if (from != to) c.reorderQueue(from, 1, if (to > from) to + 1 else to)
    }

    /** Moves a queued track right behind the one that plays now. */
    suspend fun moveToNext(trackNumber: Int): Result<Unit> = command { c ->
        val current = _state.value.selected?.nowPlaying?.queuePosition ?: 0
        if (trackNumber != current && trackNumber != current + 1) c.reorderQueue(trackNumber, 1, current + 1)
    }

    suspend fun clearQueue(): Result<Unit> = command { it.removeAllTracksFromQueue() }

    suspend fun saveQueue(title: String): Result<Unit> = command { it.saveQueue(title) }

    companion object {
        private const val TAG = "Household"
        private const val BUSY_LIMIT_MS = 15_000L
        private val RETRY_DELAYS = listOf(2_000L, 5_000L, 10_000L, 30_000L)

        /** "192.168.0.59" → http://192.168.0.59:1400/, "10.0.2.2:1401" keeps its port. */
        fun hostToUrl(host: String): HttpUrl? {
            val h = host.trim().removePrefix("http://").trimEnd('/')
            if (h.isEmpty()) return null
            val withPort = if (h.contains(':')) h else "$h:1400"
            return "http://$withPort/".toHttpUrlOrNull()
        }
    }
}
