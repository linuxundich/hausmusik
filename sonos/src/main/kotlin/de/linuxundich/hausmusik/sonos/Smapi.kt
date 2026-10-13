// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A music service as the household lists it (`ListAvailableServices`):
 * Spotify, bonob, TuneIn and a hundred others. Hausmusik talks to the
 * service itself through SMAPI, the SOAP API Sonos defines for them.
 */
data class MusicService(
    val id: Int,
    val name: String,
    val uri: String,
    val secureUri: String?,
    /** Anonymous, UserId, DeviceLink or AppLink. */
    val auth: String,
    val pollIntervalS: Int,
    val presentationMapUri: String?,
) {
    /** The account type Sonos uses in descriptors and favorites. */
    val type: Int get() = id * 256 + 7

    /** Services Hausmusik can link itself, through a page in the browser. */
    val linkable: Boolean get() = auth == "AppLink" || auth == "DeviceLink"

    val endpoint: String get() = secureUri?.ifBlank { null } ?: uri

    /** The `<desc>` Sonos needs in the metadata to find the account. */
    val serviceDesc: String
        get() = if (linkable) "SA_RINCON${type}_X_#Svc$type-0-Token" else "SA_RINCON${type}_"

    companion object {
        fun parseList(xml: String): List<MusicService> {
            if (xml.isBlank()) return emptyList()
            return Xml.parse(xml).findAll("Service").mapNotNull { e ->
                if (e.attr("ContainerType") != "MService") return@mapNotNull null
                val policy = e.child("Policy")
                MusicService(
                    id = e.attr("Id")?.toIntOrNull() ?: return@mapNotNull null,
                    name = e.attr("Name").orEmpty(),
                    uri = e.attr("Uri").orEmpty(),
                    secureUri = e.attr("SecureUri"),
                    auth = policy?.attr("Auth").orEmpty(),
                    pollIntervalS = policy?.attr("PollInterval")?.toIntOrNull() ?: 5,
                    presentationMapUri = e.child("Presentation")?.child("PresentationMap")?.attr("Uri"),
                )
            }
        }
    }
}

/** What the header of every SMAPI call carries. */
@Serializable
data class SmapiCredentials(
    val deviceId: String,
    val householdId: String,
    val token: String? = null,
    val key: String? = null,
)

/** The page the user opens to link an account, and the code to show there. */
data class SmapiLink(val regUrl: String, val linkCode: String, val showCode: Boolean, val linkDeviceId: String?)

/** One entry of a service's catalogue: an artist, an album, a track, a station. */
@Serializable
data class SmapiItem(
    val id: String,
    /** artist, album, playlist, track, stream, program, container, … */
    val itemType: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artUrl: String? = null,
    val canPlay: Boolean = false,
    val canEnumerate: Boolean = false,
    val mimeType: String? = null,
    val durationMs: Long? = null,
) {
    /** Collections that can be opened; tracks, streams and programs are played. */
    val isContainer: Boolean get() = canEnumerate && itemType !in setOf("track", "stream", "program")
}

data class SmapiPage(val items: List<SmapiItem>, val index: Int, val total: Int)

/** An SMAPI fault, e.g. `Client.NOT_LINKED_RETRY` or `Client.LoginUnauthorized`. */
class SmapiFault(val code: String, message: String) : IOException("$code: $message") {
    val notLinkedYet: Boolean get() = code.endsWith("NOT_LINKED_RETRY")
    val unauthorized: Boolean
        get() = listOf("AuthTokenExpired", "LoginInvalid", "LoginUnauthorized", "LoginDisabled", "SessionIdInvalid", "NOT_LINKED_FAILURE")
            .any { code.endsWith(it) }
}

/**
 * Talks SMAPI to one music service. When the service hands out a fresh token
 * (`TokenRefreshRequired`), [onRefresh] stores it and the call is repeated.
 */
class SmapiClient(
    private val http: OkHttpClient,
    val service: MusicService,
    @Volatile var credentials: SmapiCredentials,
    private val locale: String = "en-US",
    private val onRefresh: suspend (SmapiCredentials) -> Unit = {},
) {
    private fun header(): String = buildString {
        append("""<credentials xmlns="$NS"><deviceId>${credentials.deviceId.xmlEscape()}</deviceId><deviceProvider>Sonos</deviceProvider>""")
        val token = credentials.token
        if (token != null) {
            append("<loginToken><token>").append(token.xmlEscape()).append("</token>")
            credentials.key?.let { append("<key>").append(it.xmlEscape()).append("</key>") }
            append("<householdId>").append(credentials.householdId.xmlEscape()).append("</householdId></loginToken>")
        }
        append("</credentials>")
    }

    private suspend fun call(action: String, args: List<Pair<String, Any>>, retried: Boolean = false): org.w3c.dom.Element {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""")
            append("<s:Header>").append(header()).append("</s:Header><s:Body>")
            append("""<ns:$action xmlns:ns="$NS">""")
            for ((k, v) in args) append("<ns:$k>").append(v.toString().xmlEscape()).append("</ns:$k>")
            append("</ns:$action></s:Body></s:Envelope>")
        }
        val request = Request.Builder().url(service.endpoint)
            .header("SOAPAction", "\"$NS#$action\"")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", locale)
            .header("X-Sonos-SWGen", "1")
            .post(body.toRequestBody(XML))
            .build()
        val text = withContext(Dispatchers.IO) { http.newCall(request).await().use { it.body.string() } }
        val root = runCatching { Xml.parse(text) }.getOrElse { throw IOException("$action: no XML from ${service.name}") }
        val fault = root.find("Fault")
        if (fault != null) {
            val code = fault.childText("faultcode").orEmpty().substringAfter(':')
            if (code.endsWith("TokenRefreshRequired") && !retried) {
                val token = fault.find("authToken")?.textContent
                if (token != null) {
                    credentials = credentials.copy(token = token, key = fault.find("privateKey")?.textContent ?: credentials.key)
                    onRefresh(credentials)
                    return call(action, args, retried = true)
                }
            }
            throw SmapiFault(code, fault.childText("faultstring").orEmpty())
        }
        return root.find("${action}Result") ?: root.find("${action}Response") ?: throw IOException("$action: empty answer")
    }

    /** Starts linking an AppLink or DeviceLink service. */
    suspend fun startLink(): SmapiLink = if (service.auth == "AppLink") {
        val r = call("getAppLink", listOf("householdId" to credentials.householdId))
        val link = r.find("deviceLink") ?: throw IOException("${service.name}: no device link")
        SmapiLink(link.childText("regUrl").orEmpty(), link.childText("linkCode").orEmpty(),
            link.childText("showLinkCode") == "true", link.childText("linkDeviceId"))
    } else {
        val r = call("getDeviceLinkCode", listOf("householdId" to credentials.householdId))
        SmapiLink(r.childText("regUrl").orEmpty(), r.childText("linkCode").orEmpty(),
            r.childText("showLinkCode") != "false", r.childText("linkDeviceId"))
    }

    /**
     * Asks whether the user confirmed the link. Null while they have not
     * (the service answers NOT_LINKED_RETRY); the new credentials once they did.
     */
    suspend fun finishLink(link: SmapiLink): SmapiCredentials? {
        val args = buildList<Pair<String, Any>> {
            add("householdId" to credentials.householdId)
            add("linkCode" to link.linkCode)
            link.linkDeviceId?.let { add("linkDeviceId" to it) }
        }
        val r = try {
            call("getDeviceAuthToken", args)
        } catch (f: SmapiFault) {
            if (f.notLinkedYet) return null
            throw f
        }
        val token = r.childText("authToken") ?: throw IOException("${service.name}: no token")
        credentials = credentials.copy(token = token, key = r.childText("privateKey"))
        return credentials
    }

    /** One page of a container; `root` is the top level. */
    suspend fun metadata(id: String, index: Int = 0, count: Int = 100): SmapiPage =
        parsePage(call("getMetadata", listOf("id" to id, "index" to index, "count" to count, "recursive" to false)))

    /** Searches one category, e.g. `artists`, `albums` or `tracks`. */
    suspend fun search(categoryId: String, term: String, index: Int = 0, count: Int = 30): SmapiPage =
        parsePage(call("search", listOf("id" to categoryId, "term" to term, "index" to index, "count" to count)))

    /** All pages of a container, up to [limit] entries. */
    suspend fun metadataAll(id: String, pageSize: Int = 100, limit: Int = 2000): SmapiPage {
        val items = mutableListOf<SmapiItem>()
        var total: Int
        do {
            val page = metadata(id, items.size, pageSize)
            total = page.total
            if (page.items.isEmpty()) break
            items += page.items
        } while (items.size < total && items.size < limit)
        return SmapiPage(items, 0, total)
    }

    /** The search categories the service offers, read from its presentation map. */
    suspend fun searchCategories(): List<String> {
        val uri = service.presentationMapUri ?: return emptyList()
        val text = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(uri).header("User-Agent", USER_AGENT).build()).await().use { it.body.string() }
        }
        return parseSearchCategories(text)
    }

    companion object {
        const val NS = "http://www.sonos.com/Services/1.1"
        private const val USER_AGENT = "Linux UPnP/1.0 Sonos/85.0-66270 (ACR_hausmusik)"
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

        internal fun parseSearchCategories(xml: String): List<String> = runCatching {
            Xml.parse(xml).findAll("SearchCategories").firstOrNull()?.children()
                ?.filter { it.localName0 == "Category" || it.localName0 == "CustomCategory" }
                ?.mapNotNull { it.attr("mappedId") ?: it.attr("id") }?.toList()
        }.getOrNull().orEmpty()

        internal fun parsePage(result: org.w3c.dom.Element): SmapiPage {
            val items = result.children().mapNotNull { e ->
                when (e.localName0) {
                    "mediaCollection" -> SmapiItem(
                        id = e.childText("id").orEmpty(),
                        itemType = e.childText("itemType").orEmpty(),
                        title = e.childText("title").orEmpty(),
                        artist = e.childText("artist")?.ifBlank { null },
                        artUrl = e.childText("albumArtURI")?.ifBlank { null },
                        canPlay = e.childText("canPlay") == "true",
                        canEnumerate = e.childText("canEnumerate") != "false",
                    )
                    "mediaMetadata" -> {
                        val t = e.child("trackMetadata")
                        SmapiItem(
                            id = e.childText("id").orEmpty(),
                            itemType = e.childText("itemType").orEmpty(),
                            title = e.childText("title").orEmpty(),
                            artist = t?.childText("artist")?.ifBlank { null },
                            album = t?.childText("album")?.ifBlank { null },
                            artUrl = t?.childText("albumArtURI")?.ifBlank { null },
                            canPlay = true,
                            mimeType = e.childText("mimeType"),
                            durationMs = t?.childText("duration")?.toLongOrNull()?.times(1000),
                        )
                    }
                    else -> null
                }
            }.toList()
            return SmapiPage(items, result.childText("index")?.toIntOrNull() ?: 0, result.childText("total")?.toIntOrNull() ?: items.size)
        }

        /** Sonos-style escaping of an item ID inside a URI: `track:1` -> `track%3a1`. */
        fun escapeId(id: String): String = buildString {
            for (b in id.toByteArray()) {
                val c = b.toInt().toChar()
                if (c.isLetterOrDigit() && b >= 0 || c in "-._~") append(c) else append('%').append("%02x".format(b.toInt() and 0xff))
            }
        }

        private fun extension(mime: String?): String = when (mime?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> ".mp3"
            "audio/flac", "audio/x-flac" -> ".flac"
            "audio/mp4", "audio/aac", "audio/x-m4a" -> ".mp4"
            "audio/ogg", "audio/vorbis" -> ".ogg"
            else -> ""
        }

        /**
         * What a player needs to play [item] of [service]: an `x-sonos-http`
         * track or an `x-rincon-cpcontainer` album, playlist or other container,
         * with the account serial [sn] the household knows the service by.
         */
        fun toDidl(service: MusicService, sn: String, item: SmapiItem, navId: String): DidlObject {
            val enc = escapeId(item.id)
            val (didlId, uri, upnpClass) = when {
                item.itemType == "track" -> Triple("10032020$enc",
                    "x-sonos-http:$enc${extension(item.mimeType)}?sid=${service.id}&flags=8224&sn=$sn", "object.item.audioItem.musicTrack")
                item.itemType == "stream" -> Triple("10092020$enc", "x-sonosapi-stream:$enc?sid=${service.id}&flags=8224&sn=$sn",
                    "object.item.audioItem.audioBroadcast")
                item.itemType == "program" -> Triple("100c206c$enc", "x-sonosapi-radio:$enc?sid=${service.id}&flags=8300&sn=$sn",
                    "object.item.audioItem.audioBroadcast.#program")
                item.itemType == "album" -> Triple("1004206c$enc", "x-rincon-cpcontainer:1004206c$enc", "object.container.album.musicAlbum")
                item.itemType == "playlist" || item.itemType == "albumList" -> Triple("1006206c$enc", "x-rincon-cpcontainer:1006206c$enc", "object.container.playlistContainer")
                item.itemType == "artist" -> Triple("10052064$enc", "x-rincon-cpcontainer:10052064$enc", "object.container.person.musicArtist")
                else -> Triple("100f006c$enc", "x-rincon-cpcontainer:100f006c$enc", "object.container")
            }
            val playable = item.canPlay || item.itemType == "track"
            val md = buildString {
                append("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
                append("""xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""")
                append("""<item id="${didlId.xmlEscape()}" parentID="" restricted="true">""")
                append("<dc:title>${item.title.xmlEscape()}</dc:title><upnp:class>$upnpClass</upnp:class>")
                item.artist?.let { append("<dc:creator>${it.xmlEscape()}</dc:creator>") }
                item.album?.let { append("<upnp:album>${it.xmlEscape()}</upnp:album>") }
                item.artUrl?.let { append("<upnp:albumArtURI>${it.xmlEscape()}</upnp:albumArtURI>") }
                append("""<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">${service.serviceDesc.xmlEscape()}</desc>""")
                append("</item></DIDL-Lite>")
            }
            return DidlObject(
                id = navId,
                parentId = "",
                isContainer = item.isContainer,
                title = item.title,
                upnpClass = upnpClass,
                creator = item.artist,
                album = item.album,
                uri = if (playable) uri else null,
                albumArtUri = item.artUrl,
                resMd = if (playable) md else null,
                description = if (playable) service.name else null,
                durationMs = item.durationMs,
            )
        }

        /** The account serial (`sn=`) of [serviceId] in URIs the household already uses, e.g. favorites. */
        fun accountSerial(serviceId: Int, uris: Sequence<String?>): String? =
            uris.filterNotNull().firstNotNullOfOrNull { Regex("[?&]sid=$serviceId&(?:.*&)?sn=(\\d+)").find(it)?.groupValues?.get(1) }
    }
}
