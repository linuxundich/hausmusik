// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data.services

import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.data.history.ServiceAccount
import de.linuxundich.hausmusik.data.history.ServiceAccountDao
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.MusicService
import de.linuxundich.hausmusik.sonos.SmapiClient
import de.linuxundich.hausmusik.sonos.SmapiCredentials
import de.linuxundich.hausmusik.sonos.SmapiFault
import de.linuxundich.hausmusik.sonos.SmapiLink
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** IDs in the library: `svc:<service>:<item>` for a service's catalogue. */
object ServiceIds {
    const val OVERVIEW = "svc:"
    const val LINK_CLASS = "object.hausmusik.link"
    fun of(serviceId: Int, itemId: String) = "svc:$serviceId:$itemId"
    fun parse(navId: String): Pair<Int, String>? {
        val rest = navId.removePrefix("svc:").takeIf { navId.startsWith("svc:") } ?: return null
        val sid = rest.substringBefore(':').toIntOrNull() ?: return null
        return sid to rest.substringAfter(':')
    }
}

/** A link in progress: the page to open and the service it belongs to. */
data class PendingLink(val service: MusicService, val link: SmapiLink)

/**
 * Music services through SMAPI (bonob, Spotify, …). Hausmusik links its own
 * account with each service, the way noson and Gnomos do: the service opens a
 * page in the browser, the user signs in there, and the service hands
 * Hausmusik a token. Playing still goes through the speakers, which use the
 * household's own account (`sn=`).
 */
@Singleton
class MusicServices @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    http: OkHttpClient,
    private val repo: HouseholdRepository,
    private val accounts: ServiceAccountDao,
) {
    // Services like bonob ask a music server for every page; give them time.
    private val http = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var household: String? = null
    private var deviceId: String? = null
    private var catalogue: List<MusicService>? = null
    private val clients = mutableMapOf<Int, SmapiClient>()

    private fun coordinator() = repo.selectedCoordinator() ?: throw IOException("not connected")

    private suspend fun identity(): Pair<String, String> = mutex.withLock {
        val c = coordinator()
        val h = household ?: c.householdId().also { household = it }
        val d = deviceId ?: c.serialNumber().also { deviceId = it }
        h to d
    }

    /** Every service of the household with an MService catalogue. */
    suspend fun available(): List<MusicService> =
        catalogue ?: coordinator().musicServices().also { catalogue = it }

    /** The overview: linked services first, then those Hausmusik can link. */
    suspend fun overview(): List<DidlObject> {
        val (household, _) = identity()
        val all = available()
        val linked = accounts.forHousehold(household).first().map { it.serviceId }.toSet()
        val mine = all.filter { it.id in linked }.map { s ->
            DidlObject(id = ServiceIds.of(s.id, "root"), parentId = ServiceIds.OVERVIEW, isContainer = true,
                title = s.displayName, upnpClass = "object.container", description = s.displayName)
        }
        val more = all.filter { it.linkable && it.id !in linked }.sortedBy { it.name.lowercase() }.map { s ->
            DidlObject(id = "link:${s.id}", parentId = ServiceIds.OVERVIEW, isContainer = false,
                title = s.displayName, upnpClass = ServiceIds.LINK_CLASS,
                description = context.getString(de.linuxundich.hausmusik.R.string.service_tap_to_link))
        }
        return mine + more
    }

    private suspend fun client(serviceId: Int): SmapiClient {
        clients[serviceId]?.let { return it }
        val (household, _) = identity()
        val service = available().firstOrNull { it.id == serviceId } ?: throw IOException("service $serviceId unknown")
        val account = accounts.get(household, serviceId) ?: throw IOException("${service.name} not linked")
        return SmapiClient(http, service, SmapiCredentials(account.deviceId, household, account.token, account.key)) { fresh ->
            accounts.put(account.copy(token = fresh.token.orEmpty(), key = fresh.key))
        }.also { clients[serviceId] = it }
    }

    /** One container of a linked service as library entries. */
    suspend fun browse(navId: String): List<DidlObject> {
        val (sid, itemId) = ServiceIds.parse(navId) ?: throw IOException("not a service id: $navId")
        val c = client(sid)
        val page = try {
            c.metadataAll(itemId)
        } catch (f: SmapiFault) {
            if (f.unauthorized) unlink(sid)
            throw f
        }
        val sn = serial(sid)
        return page.items.map { SmapiClient.toDidl(c.service, sn, it, ServiceIds.of(sid, it.id)) }
    }

    /** Searches artists, albums and tracks of one linked service. */
    suspend fun search(serviceId: Int, term: String): Map<String, List<DidlObject>> {
        val c = client(serviceId)
        val sn = serial(serviceId)
        val categories = runCatching { c.searchCategories() }.getOrDefault(emptyList()).ifEmpty { listOf("artists", "albums", "tracks") }
        return categories.associateWith { cat ->
            runCatching { c.search(cat, term).items }.getOrDefault(emptyList())
                .map { SmapiClient.toDidl(c.service, sn, it, ServiceIds.of(serviceId, it.id)) }
        }
    }

    /**
     * The account serial for `sn=`: the one the household already uses in its
     * favorites, which the Sonos app wrote. Without one, `0`, which Sonos uses
     * for the first account of a service.
     */
    private suspend fun serial(serviceId: Int): String {
        val (household, _) = identity()
        val account = accounts.get(household, serviceId)
        account?.sn?.let { return it }
        val favorites = repo.cachedList("FV:2")?.items ?: repo.loadAndCache("FV:2").getOrDefault(emptyList())
        val found = SmapiClient.accountSerial(serviceId, favorites.asSequence().map { it.uri })
        if (found != null && account != null) accounts.put(account.copy(sn = found))
        return found ?: "0"
    }

    /** Starts linking [serviceId]; the caller opens [PendingLink.link]'s page. */
    suspend fun startLink(serviceId: Int): PendingLink {
        val (household, device) = identity()
        val service = available().first { it.id == serviceId }
        val link = SmapiClient(http, service, SmapiCredentials(device, household)).startLink()
        if (link.regUrl.isBlank()) throw IOException("${service.name}: no link page")
        return PendingLink(service, link)
    }

    /**
     * Waits until the user confirmed the link in the browser (or [timeoutMs]
     * passed). True once the account is stored.
     */
    suspend fun awaitLink(pending: PendingLink, timeoutMs: Long = 10 * 60_000L): Boolean {
        val (household, device) = identity()
        val client = SmapiClient(http, pending.service, SmapiCredentials(device, household))
        val deadline = System.currentTimeMillis() + timeoutMs
        val pause = (pending.service.pollIntervalS.coerceIn(2, 10)) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val creds = runCatching { client.finishLink(pending.link) }.getOrElse { if (it is SmapiFault) throw it else null }
            if (creds?.token != null) {
                accounts.put(ServiceAccount(household, pending.service.id, pending.service.displayName, device, creds.token!!, creds.key,
                    sn = null, linkedAt = System.currentTimeMillis()))
                clients.remove(pending.service.id)
                return true
            }
            delay(pause)
        }
        return false
    }

    suspend fun unlink(serviceId: Int) {
        val (household, _) = identity()
        accounts.delete(household, serviceId)
        clients.remove(serviceId)
    }

    /** Forget everything tied to the household that was connected. */
    fun reset() {
        household = null; deviceId = null; catalogue = null; clients.clear()
    }
}

/** bonob calls itself "bonob"; lists look better with a capital. */
val MusicService.displayName: String get() = name.replaceFirstChar { it.uppercase() }
