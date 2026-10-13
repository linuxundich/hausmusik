// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

import kotlinx.serialization.Serializable

/**
 * One entry of a DIDL-Lite document: a track, an album, a favorite, a radio
 * station. Sonos wraps everything it describes in DIDL-Lite.
 */
@Serializable
data class DidlObject(
    val id: String,
    val parentId: String,
    val isContainer: Boolean,
    val title: String,
    val upnpClass: String,
    val creator: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val uri: String? = null,
    val protocolInfo: String? = null,
    /** Relative (`/getaa?...`) or absolute album art URL, as Sonos sent it. */
    val albumArtUri: String? = null,
    /** Raw DIDL-Lite to pass along when playing a favorite (`r:resMD`). */
    val resMd: String? = null,
    /** `r:description` of a favorite, e.g. "ALBUM" or a service name. */
    val description: String? = null,
    /** For a radio stream: the "now playing" text (`r:streamContent`). */
    val streamContent: String? = null,
    val originalTrackNumber: Int? = null,
    /** Duration in milliseconds if the resource carries one. */
    val durationMs: Long? = null,
) {
    val isRadio: Boolean
        get() = upnpClass.startsWith("object.item.audioItem.audioBroadcast") ||
            uri?.startsWith("x-rincon-mp3radio:") == true ||
            uri?.startsWith("x-sonosapi-stream:") == true ||
            uri?.startsWith("aac:") == true ||
            uri?.startsWith("x-sonosapi-radio:") == true
}

object Didl {
    /** Parses a DIDL-Lite string. Empty or blank input yields an empty list. */
    fun parse(didl: String?): List<DidlObject> {
        if (didl.isNullOrBlank() || didl == "NOT_IMPLEMENTED") return emptyList()
        val root = Xml.parse(didl)
        return root.children()
            .filter { it.localName0 == "item" || it.localName0 == "container" }
            .map { e ->
                val res = e.child("res")
                DidlObject(
                    id = e.attr("id").orEmpty(),
                    parentId = e.attr("parentID").orEmpty(),
                    isContainer = e.localName0 == "container",
                    title = e.childText("title").orEmpty(),
                    upnpClass = e.childText("class").orEmpty(),
                    creator = e.childText("creator")?.ifBlank { null },
                    album = e.childText("album")?.ifBlank { null },
                    albumArtist = e.childText("albumArtist")?.ifBlank { null },
                    uri = res?.textContent?.ifBlank { null },
                    protocolInfo = res?.attr("protocolInfo"),
                    albumArtUri = e.childText("albumArtURI")?.ifBlank { null },
                    resMd = e.childText("resMD")?.ifBlank { null },
                    description = e.childText("description")?.ifBlank { null },
                    streamContent = e.childText("streamContent")?.ifBlank { null },
                    originalTrackNumber = e.childText("originalTrackNumber")?.toIntOrNull(),
                    durationMs = res?.attr("duration")?.let(::parseDuration),
                )
            }
            .toList()
    }

    /** Builds the minimal DIDL-Lite Sonos needs as metadata for SetAVTransportURI. */
    fun minimal(id: String, parentId: String, title: String, upnpClass: String, serviceDesc: String? = null): String =
        buildString {
            append("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """)
            append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
            append("""xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" """)
            append("""xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""")
            append("""<item id="${id.xmlEscape()}" parentID="${parentId.xmlEscape()}" restricted="true">""")
            append("<dc:title>${title.xmlEscape()}</dc:title>")
            append("<upnp:class>${upnpClass.xmlEscape()}</upnp:class>")
            if (serviceDesc != null) {
                append("""<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">""")
                append(serviceDesc.xmlEscape()).append("</desc>")
            }
            append("</item></DIDL-Lite>")
        }
}

/** Parses Sonos durations like `0:03:33` or `1:02:03.500`. Returns null for NOT_IMPLEMENTED. */
fun parseDuration(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    val parts = text.trim().split(':')
    if (parts.size != 3) return null
    val h = parts[0].toLongOrNull() ?: return null
    val m = parts[1].toLongOrNull() ?: return null
    val s = parts[2].toDoubleOrNull() ?: return null
    return (h * 3600 + m * 60) * 1000 + (s * 1000).toLong()
}

/** Formats milliseconds as `H:MM:SS`, the form Sonos expects for Seek. */
fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

private val directPrefixes = listOf(
    "x-sonosapi-stream:", "x-sonosapi-radio:", "x-rincon-mp3radio:", "aac:",
    "x-sonosapi-hls:", "x-sonosapi-hls-static:", "x-rincon-stream:", "x-sonos-htastream:", "hls-radio:",
)

/**
 * True if this item is played by switching the transport to it (radio, line-in).
 * Everything else (tracks, albums, playlists, service containers) goes through
 * the queue.
 */
val DidlObject.playsDirectly: Boolean
    get() = isRadio || directPrefixes.any { uri?.startsWith(it) == true }
