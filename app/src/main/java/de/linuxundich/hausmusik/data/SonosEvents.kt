// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import android.util.Log
import de.linuxundich.hausmusik.di.AppScope
import de.linuxundich.hausmusik.sonos.GenaClient
import de.linuxundich.hausmusik.sonos.GenaServer
import de.linuxundich.hausmusik.sonos.SonosPlayer
import de.linuxundich.hausmusik.sonos.SonosService
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** What changed, according to a player's event. */
sealed interface SonosTrigger {
    data class Transport(val playerUuid: String) : SonosTrigger
    data class Volume(val playerUuid: String) : SonosTrigger
    data object Topology : SonosTrigger
}

/**
 * Subscribes to the events of every player and turns them into triggers. The
 * events are only used to know *when* to read again: GetPositionInfo and
 * friends stay the source of truth (events carry stale titles after a queue
 * change, see Gnomos 0.28.4).
 */
@Singleton
class SonosEvents @Inject constructor(
    http: OkHttpClient,
    private val settings: Settings,
    @AppScope private val scope: CoroutineScope,
) {
    private val client = GenaClient(http)
    private val server = GenaServer(scope)

    private data class Sub(val player: SonosPlayer, val service: SonosService, var sid: String, var expiresAt: Long, var timeout: Int)

    private val subs = ConcurrentHashMap<String, Sub>() // key: uuid/service
    private val mutex = Mutex()
    private var listener: Job? = null
    private var renewer: Job? = null

    private val _triggers = MutableSharedFlow<SonosTrigger>(extraBufferCapacity = 64)
    val triggers: SharedFlow<SonosTrigger> = _triggers

    /** True while subscriptions exist; the repository then polls rarely. */
    val active: Boolean get() = subs.isNotEmpty()

    /** Subscribes to new players and drops vanished ones. */
    suspend fun sync(players: List<SonosPlayer>) = mutex.withLock {
        val callback = callbackBase(players.firstOrNull() ?: return@withLock) ?: return@withLock
        ensureListening()
        val wanted = buildMap {
            players.forEach { p ->
                put("${p.uuid}/${SonosService.AVTransport}", p to SonosService.AVTransport)
                put("${p.uuid}/${SonosService.RenderingControl}", p to SonosService.RenderingControl)
            }
            players.minByOrNull { it.uuid }?.let { put("${it.uuid}/${SonosService.ZoneGroupTopology}", it to SonosService.ZoneGroupTopology) }
        }
        (subs.keys - wanted.keys).forEach { key ->
            subs.remove(key)?.let { client.unsubscribe(it.player.baseUrl, it.service, it.sid) }
        }
        for ((key, pair) in wanted) {
            val (player, service) = pair
            val existing = subs[key]
            if (existing != null && existing.player.baseUrl == player.baseUrl) continue
            runCatching { client.subscribe(player.baseUrl, service, "$callback/${player.uuid}/${service.name}") }
                .onSuccess { s ->
                    subs[key] = Sub(player, service, s.sid, now() + s.timeoutSeconds * 1000L, s.timeoutSeconds)
                    Log.i(TAG, "subscribed ${player.roomName} $service via $callback (${s.timeoutSeconds} s)")
                }
                .onFailure { Log.w(TAG, "subscribe ${player.roomName} $service failed: ${it.message}") }
        }
        ensureRenewing()
    }

    /**
     * Cancels every subscription; used when nothing shows or plays any more, or
     * the household is gone. The UNSUBSCRIBEs go out in the background: without
     * Wi-Fi each would wait for its timeout, and nobody needs their answer.
     */
    suspend fun stop() = mutex.withLock {
        renewer?.cancel()
        listener?.cancel()
        renewer = null
        listener = null
        val old = subs.values.toList()
        subs.clear()
        server.stop()
        if (old.isNotEmpty()) scope.launch { old.forEach { client.unsubscribe(it.player.baseUrl, it.service, it.sid) } }
    }

    private suspend fun callbackBase(anyPlayer: SonosPlayer): String? {
        settings.debugCallback()?.let { return "http://$it" }
        val port = server.port ?: server.start(0)
        val local = GenaClient.localAddressFor(anyPlayer.baseUrl) ?: return null
        return "http://${local.hostAddress}:$port"
    }

    private suspend fun ensureListening() {
        settings.debugCallback()?.let { server.start(it.substringAfterLast(':').toInt()) } ?: server.start(0)
        if (listener?.isActive == true) return
        listener = scope.launch {
            server.events.collect { n ->
                val sub = subs.values.firstOrNull { it.sid == n.sid }
                Log.d(TAG, "event ${sub?.player?.roomName} ${sub?.service} seq ${n.seq}")
                val trigger = when (sub?.service) {
                    SonosService.AVTransport -> SonosTrigger.Transport(sub.player.uuid)
                    SonosService.RenderingControl -> SonosTrigger.Volume(sub.player.uuid)
                    // The first NOTIFY can beat the SUBSCRIBE answer; read everything then.
                    else -> SonosTrigger.Topology
                }
                _triggers.emit(trigger)
            }
        }
    }

    private fun ensureRenewing() {
        if (renewer?.isActive == true) return
        renewer = scope.launch {
            while (isActive) {
                delay(30_000)
                for ((key, s) in subs.entries.toList()) {
                    if (s.expiresAt - now() > s.timeout * 500L) continue
                    runCatching { client.renew(s.player.baseUrl, s.service, s.sid, s.timeout) }
                        .onSuccess { r -> s.expiresAt = now() + r.timeoutSeconds * 1000L }
                        .onFailure {
                            // Expired or the player rebooted: drop it, the next sync subscribes again.
                            Log.w(TAG, "renew ${s.player.roomName} ${s.service} failed: ${it.message}")
                            subs.remove(key)
                            _triggers.emit(SonosTrigger.Topology)
                        }
                }
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object { const val TAG = "SonosEvents" }
}
