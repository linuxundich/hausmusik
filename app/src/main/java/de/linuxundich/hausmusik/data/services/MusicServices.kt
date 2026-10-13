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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** IDs in the library: `svc:<service>:<item>` for a service's catalogue. */
object ServiceIds {
    const val OVERVIEW = "svc:"
    /** The long list of every other service Sonos offers. */
    const val MORE = "svc:more"
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
    @de.linuxundich.hausmusik.di.AppScope private val scope: kotlinx.coroutines.CoroutineScope,
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
    private val covers = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 1000
    })

    init {
        repo.coverResolver = ::serviceCover
    }

    private val coverJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * The cover a linked service gives for the item behind a speaker cover
     * path (`/getaa?…&u=x-sonos-http:…?sid=…`). Never waits: a known cover
     * comes back at once; otherwise null now, the lookup runs in the
     * background and the room is read again when it found one. Only a real
     * answer is remembered ("none" as an empty string), never a failure.
     */
    fun serviceCover(albumArtUri: String): String? {
        val (sid, itemId) = SmapiClient.itemOf(SmapiClient.uriOfCover(albumArtUri)) ?: return null
        val key = "$sid/$itemId"
        covers[key]?.let { return it.ifEmpty { null } }
        if (coverJobs[key]?.isActive == true) return null
        coverJobs[key] = scope.launch {
            try {
                val (household, _) = identity()
                if (accounts.get(household, sid) == null) {
                    covers[key] = ""
                    return@launch
                }
                // bonob hands out its 180 px size; its size map goes up to 1500, the stage needs about 640.
                val url = client(sid).artFor(itemId)?.replace(Regex("/size/\\d+$"), "/size/640")
                covers[key] = url.orEmpty()
                if (url != null) repo.refreshSelected()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MusicServices", "cover of $key: ${e.message}")
            } finally {
                coverJobs.remove(key)
            }
        }
        return null
    }

    private fun coordinator() = repo.selectedCoordinator() ?: throw IOException("not connected")

    // Asked once per household; while the speaker works on these, polls may wait.
    private suspend fun identity(): Pair<String, String> = mutex.withLock {
        val c = coordinator()
        val h = household ?: repo.whileBusy { c.householdId() }.also { household = it }
        val d = deviceId ?: repo.whileBusy { c.serialNumber() }.also { deviceId = it }
        h to d
    }

    /** Every service of the household with an MService catalogue (about 35 KB from an S1). */
    suspend fun available(): List<MusicService> =
        catalogue ?: repo.whileBusy { coordinator().musicServices() }.also { catalogue = it }

    /**
     * The overview. Before anything is linked: the well-known services and
     * those the household already uses, the rest under "More services".
     * Once one service is linked, only the linked ones and the well-known ones
     * without sign-in (TuneIn); everything to link moves to "More services"
     * (Christoph's choice, 2026-10-13).
     */
    suspend fun overview(): List<DidlObject> {
        val all = available()
        val linked = linkedIds()
        val rest = DidlObject(id = ServiceIds.MORE, parentId = ServiceIds.OVERVIEW, isContainer = true,
            title = context.getString(de.linuxundich.hausmusik.R.string.services_more), upnpClass = "object.container")
        val top = topIds()
        if (linked.isNotEmpty()) {
            // Ready to use: linked services and the well-known ones without sign-in (TuneIn).
            return all.filter { it.id in linked || it.anonymous && it.id in top }.sortedBy { it.name.lowercase() }.map(::openEntry) + rest
        }
        return all.filter { it.id in top && (it.anonymous || it.linkable) }.sortedBy { it.name.lowercase() }
            .map { if (it.anonymous) openEntry(it) else linkEntry(it) } + rest
    }

    /** Every service not shown in the overview, A to Z: those without sign-in open, the rest to link. */
    suspend fun more(): List<DidlObject> {
        val linked = linkedIds()
        val top = topIds()
        // Before anything is linked, the well-known ones stand in the overview; afterwards only those without sign-in do.
        val shown = if (linked.isEmpty()) top else available().filter { it.anonymous && it.id in top }.map { it.id }.toSet()
        return available()
            .filter { it.id !in linked && it.id !in shown && (it.linkable || it.anonymous) }
            .sortedBy { it.name.lowercase() }
            .map { if (it.anonymous) openEntry(it) else linkEntry(it) }
    }

    private suspend fun linkedIds(): Set<Int> {
        val (household, _) = identity()
        return accounts.forHousehold(household).first().map { it.serviceId }.toSet()
    }

    private suspend fun topIds(): Set<Int> = POPULAR + usedServiceIds()

    private fun openEntry(s: MusicService) = DidlObject(id = ServiceIds.of(s.id, "root"), parentId = ServiceIds.OVERVIEW,
        isContainer = true, title = s.displayName, upnpClass = "object.container")

    private fun linkEntry(s: MusicService) = DidlObject(id = "link:${s.id}", parentId = ServiceIds.OVERVIEW, isContainer = false,
        title = s.displayName, upnpClass = ServiceIds.LINK_CLASS,
        description = context.getString(de.linuxundich.hausmusik.R.string.service_tap_to_link))

    /** Service IDs in the household's favorites: `sid=` in URIs, `SA_RINCON<type>_` in metadata. */
    private suspend fun usedServiceIds(): Set<Int> {
        val favorites = repo.cachedList("FV:2")?.items ?: repo.loadAndCache("FV:2").getOrDefault(emptyList())
        return favorites.flatMap { f ->
            Regex("[?&]sid=(\\d+)").findAll(f.uri.orEmpty()).map { it.groupValues[1].toInt() } +
                Regex("SA_RINCON(\\d+)_").findAll(f.resMd.orEmpty()).map { it.groupValues[1].toInt() / 256 }
        }.toSet()
    }

    private suspend fun client(serviceId: Int): SmapiClient {
        clients[serviceId]?.let { return it }
        val (household, device) = identity()
        val service = available().firstOrNull { it.id == serviceId } ?: throw IOException("service $serviceId unknown")
        if (service.anonymous) return SmapiClient(http, service, SmapiCredentials(device, household)).also { clients[serviceId] = it }
        val account = accounts.get(household, serviceId) ?: throw IOException("${service.name} not linked")
        return SmapiClient(http, service, SmapiCredentials(account.deviceId, household, account.token, account.key)) { fresh ->
            accounts.put(account.copy(token = fresh.token.orEmpty(), key = fresh.key))
        }.also { clients[serviceId] = it }
    }

    /** One container of a linked service as library entries. */
    suspend fun browse(navId: String): List<DidlObject> {
        if (navId == ServiceIds.MORE) return more()
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

    /** The services this household has linked in Hausmusik, A to Z. */
    suspend fun linked(): List<MusicService> {
        val ids = linkedIds()
        return available().filter { it.id in ids }.sortedBy { it.name.lowercase() }
    }

    /**
     * Searches one linked service: artists, albums and tracks first, then
     * whatever else it offers (playlists, podcasts). [perCategory] results each.
     */
    suspend fun search(serviceId: Int, term: String, perCategory: Int = 8): List<Pair<String, List<DidlObject>>> =
        kotlinx.coroutines.coroutineScope {
            val c = client(serviceId)
            val sn = serial(serviceId)
            val categories = runCatching { c.searchCategories() }.getOrDefault(emptyList())
                .ifEmpty { listOf("artists", "albums", "tracks").map { de.linuxundich.hausmusik.sonos.SearchCategory(it, it) } }
            val order = listOf("artist", "album", "track")
            categories.sortedBy { cat -> order.indexOfFirst { cat.id.lowercase().startsWith(it) }.let { if (it < 0) order.size else it } }
                .map { cat ->
                    async {
                        cat.id to runCatching { c.search(cat.searchId, term, count = perCategory).items }.getOrDefault(emptyList())
                            .distinctBy { it.id }
                            .map { SmapiClient.toDidl(c.service, sn, it, ServiceIds.of(serviceId, it.id)) }
                    }
                }.awaitAll().filter { it.second.isNotEmpty() }
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
            // Only a definite "no" ends the wait; hiccups (network, other faults) are asked again.
            val creds = runCatching { client.finishLink(pending.link) }.getOrElse {
                if (it is SmapiFault && it.unauthorized) throw it
                android.util.Log.i("MusicServices", "waiting for ${pending.service.name}: ${it.message}")
                null
            }
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

/**
 * Services shown at the top of the overview, besides those the household
 * already uses: the ones people in Germany, Austria and Switzerland know
 * (Christoph's choice, 2026-10-13). By Sonos service ID, which stays fixed.
 */
private val POPULAR = setOf(
    9, // Spotify
    2, // Deezer
    174, // TIDAL
    201, // Amazon Music
    204, // Apple Music
    284, // YouTube Music
    31, // Qobuz
    239, // Audible
    226, // Storytel
    212, // Plex
    157, // Bandcamp
    271, // IDAGIO
    254, // TuneIn
)

/** bonob calls itself "bonob"; lists look better with a capital. */
val MusicService.displayName: String get() = name.replaceFirstChar { it.uppercase() }
