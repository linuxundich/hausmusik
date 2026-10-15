// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl

/**
 * Typed access to one player. Transport and group volume calls must go to the
 * group coordinator; RenderingControl calls go to the individual player.
 */
class SonosPlayerClient(val baseUrl: HttpUrl, private val soap: SoapClient) {

    private suspend fun av(action: String, vararg args: Pair<String, Any>) =
        soap.call(baseUrl, SonosService.AVTransport, action, listOf("InstanceID" to 0) + args)

    private suspend fun rc(action: String, vararg args: Pair<String, Any>) =
        soap.call(baseUrl, SonosService.RenderingControl, action, listOf("InstanceID" to 0) + args)

    private suspend fun grc(action: String, vararg args: Pair<String, Any>) =
        soap.call(baseUrl, SonosService.GroupRenderingControl, action, listOf("InstanceID" to 0) + args)

    private suspend fun cd(action: String, vararg args: Pair<String, Any>) =
        soap.call(baseUrl, SonosService.ContentDirectory, action, args.toList())

    // Crossfade (group setting, ask the coordinator)

    suspend fun crossfade(): Boolean = av("GetCrossfadeMode")["CrossfadeMode"] == "1"
    suspend fun setCrossfade(on: Boolean) { av("SetCrossfadeMode", "CrossfadeMode" to if (on) 1 else 0) }

    // Line-in

    /**
     * The name of this player's analog input ("Heimkino"), or null if it has
     * none: only players with an AudioIn service (Connect, Connect:Amp, Play:5, ...)
     * answer. Any player of the household plays it as `x-rincon-stream:<uuid>`.
     */
    suspend fun audioInputName(): String? = runCatching {
        soap.call(baseUrl, SonosService.AudioIn, "GetAudioInputAttributes")["CurrentName"]?.ifBlank { null }
    }.getOrNull()

    // Sonos favorites (FV:2)

    /**
     * Adds a Sonos favorite in the form the speakers store themselves: the item's
     * URI as `res`, its DIDL as `r:resMD`, played at once ("instantPlay").
     */
    suspend fun addFavorite(item: DidlObject, resMd: String) {
        val didl = buildString {
            append("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
            append("""xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""")
            append("""<item id="" parentID="FV:2" restricted="false">""")
            append("<dc:title>${item.title.xmlEscape()}</dc:title>")
            append("<upnp:class>object.itemobject.item.sonos-favorite</upnp:class>")
            append("""<res protocolInfo="${(item.protocolInfo ?: "x-rincon-playlist:*:*:*").xmlEscape()}">${item.uri.orEmpty().xmlEscape()}</res>""")
            item.albumArtUri?.let { append("<upnp:albumArtURI>${it.xmlEscape()}</upnp:albumArtURI>") }
            append("<r:type>instantPlay</r:type>")
            // As the Sonos app and noson: the album or artist when the item has no description of its own.
            (item.description ?: item.album ?: item.creator)?.let { append("<r:description>${it.xmlEscape()}</r:description>") }
            append("<r:resMD>${resMd.xmlEscape()}</r:resMD>")
            append("</item></DIDL-Lite>")
        }
        cd("CreateObject", "ContainerID" to "FV:2", "Elements" to didl)
    }

    suspend fun removeFavorite(objectId: String) { cd("DestroyObject", "ObjectID" to objectId) }

    // Sonos playlists (SQ:n)

    suspend fun createPlaylist(title: String): String =
        av("CreateSavedQueue", "Title" to title, "EnqueuedURI" to "", "EnqueuedURIMetaData" to "")["AssignedObjectID"].orEmpty()

    suspend fun deletePlaylist(objectId: String) { cd("DestroyObject", "ObjectID" to objectId) }

    suspend fun renamePlaylist(objectId: String, oldTitle: String, newTitle: String) {
        cd("UpdateObject", "ObjectID" to objectId,
            "CurrentTagValue" to "<dc:title>${oldTitle.xmlEscape()}</dc:title>",
            "NewTagValue" to "<dc:title>${newTitle.xmlEscape()}</dc:title>")
    }

    /** Appends a track, album or playlist to a Sonos playlist. */
    suspend fun addToPlaylist(objectId: String, uri: String, metadata: String) {
        val update = browse(objectId, 0, 1).updateId ?: 0
        av("AddURIToSavedQueue", "ObjectID" to objectId, "UpdateID" to update,
            "EnqueuedURI" to uri, "EnqueuedURIMetaData" to metadata, "AddAtIndex" to 4294967295L)
    }

    /** Removes the track at [index] (0-based) from a Sonos playlist: an empty new position removes it. */
    suspend fun removeFromPlaylist(objectId: String, index: Int) {
        val update = browse(objectId, 0, 1).updateId ?: 0
        av("ReorderTracksInSavedQueue", "ObjectID" to objectId, "UpdateID" to update, "TrackList" to index, "NewPositionList" to "")
    }

    // Topology

    suspend fun zoneGroups(): List<ZoneGroup> =
        Topology.parse(soap.call(baseUrl, SonosService.ZoneGroupTopology, "GetZoneGroupState")["ZoneGroupState"].orEmpty())

    // Transport (coordinator)

    suspend fun transportState(): TransportState = TransportState.parse(av("GetTransportInfo")["CurrentTransportState"])

    suspend fun positionInfo(): PositionInfo {
        val r = av("GetPositionInfo")
        return PositionInfo(
            trackNumber = r["Track"]?.toIntOrNull() ?: 0,
            durationMs = parseDuration(r["TrackDuration"]),
            positionMs = parseDuration(r["RelTime"]),
            trackUri = r["TrackURI"]?.ifBlank { null },
            track = Didl.parse(r["TrackMetaData"]).firstOrNull(),
        )
    }

    suspend fun mediaInfo(): MediaInfo {
        val r = av("GetMediaInfo")
        return MediaInfo(
            numberOfTracks = r["NrTracks"]?.toIntOrNull() ?: 0,
            currentUri = r["CurrentURI"]?.ifBlank { null },
            currentUriMetadata = Didl.parse(r["CurrentURIMetaData"]).firstOrNull(),
        )
    }

    suspend fun playMode(): PlayMode = PlayMode.parse(av("GetTransportSettings")["PlayMode"])

    suspend fun setPlayMode(mode: PlayMode) { av("SetPlayMode", "NewPlayMode" to mode.sonosValue) }

    suspend fun transportActions(): TransportActions =
        TransportActions.parse(av("GetCurrentTransportActions")["Actions"])

    suspend fun play() { av("Play", "Speed" to 1) }
    suspend fun pause() { av("Pause") }
    suspend fun stop() { av("Stop") }
    suspend fun next() { av("Next") }
    suspend fun previous() { av("Previous") }
    suspend fun seekTo(ms: Long) { av("Seek", "Unit" to "REL_TIME", "Target" to formatDuration(ms)) }
    suspend fun seekTrack(trackNumber: Int) { av("Seek", "Unit" to "TRACK_NR", "Target" to trackNumber) }

    /** Plays a URI directly (radio, line-in, a favorite that is not a container). */
    suspend fun setUri(uri: String, metadata: String = "") {
        av("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
    }

    /** Switches the coordinator back to its own queue. */
    suspend fun useQueue(coordinatorUuid: String) = setUri("x-rincon-queue:$coordinatorUuid#0")

    /** Returns the queue position of the first added track. */
    suspend fun addToQueue(uri: String, metadata: String = "", position: Int = 0, asNext: Boolean = false): Int {
        val r = av(
            "AddURIToQueue",
            "EnqueuedURI" to uri,
            "EnqueuedURIMetaData" to metadata,
            "DesiredFirstTrackNumberEnqueued" to position,
            "EnqueueAsNext" to if (asNext) 1 else 0,
        )
        return r["FirstTrackNumberEnqueued"]?.toIntOrNull() ?: 0
    }

    suspend fun removeAllTracksFromQueue() { av("RemoveAllTracksFromQueue") }
    suspend fun removeTrackFromQueue(trackNumber: Int, updateId: Int = 0) {
        av("RemoveTrackFromQueue", "ObjectID" to "Q:0/$trackNumber", "UpdateID" to updateId)
    }
    suspend fun reorderQueue(from: Int, count: Int, insertBefore: Int, updateId: Int = 0) {
        av("ReorderTracksInQueue", "StartingIndex" to from, "NumberOfTracks" to count,
            "InsertBefore" to insertBefore, "UpdateID" to updateId)
    }

    /** Saves the queue as a Sonos playlist and returns its object ID (e.g. "SQ:12"). */
    suspend fun saveQueue(title: String): String =
        av("SaveQueue", "Title" to title, "ObjectID" to "")["AssignedObjectID"].orEmpty()

    /** Duration of 0 or null cancels the timer. */
    suspend fun configureSleepTimer(durationMs: Long?) {
        av("ConfigureSleepTimer", "NewSleepTimerDuration" to (durationMs?.takeIf { it > 0 }?.let(::formatDuration) ?: ""))
    }
    suspend fun remainingSleepTimerMs(): Long? = parseDuration(av("GetRemainingSleepTimerDuration")["RemainingSleepTimerDuration"])

    // Grouping

    /** Lets this player follow [coordinatorUuid]. */
    suspend fun joinGroup(coordinatorUuid: String) = setUri("x-rincon:$coordinatorUuid")

    suspend fun leaveGroup() { av("BecomeCoordinatorOfStandaloneGroup") }

    /**
     * Called on a group's coordinator: hands the group and what it plays to
     * [newCoordinatorUuid], a member. Without [rejoin] this player then plays
     * on its own (silent), as when the Sonos app unticks the leading room.
     */
    suspend fun delegateCoordination(newCoordinatorUuid: String, rejoin: Boolean = false) {
        av("DelegateGroupCoordinationTo", "NewCoordinator" to newCoordinatorUuid, "RejoinGroup" to if (rejoin) 1 else 0)
    }

    // Volume (per player)

    suspend fun volume(): Int = rc("GetVolume", "Channel" to "Master")["CurrentVolume"]?.toIntOrNull() ?: 0
    suspend fun setVolume(volume: Int) { rc("SetVolume", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100)) }
    suspend fun mute(): Boolean = rc("GetMute", "Channel" to "Master")["CurrentMute"] == "1"
    suspend fun setMute(mute: Boolean) { rc("SetMute", "Channel" to "Master", "DesiredMute" to if (mute) 1 else 0) }
    suspend fun bass(): Int = rc("GetBass")["CurrentBass"]?.toIntOrNull() ?: 0
    suspend fun setBass(value: Int) { rc("SetBass", "DesiredBass" to value.coerceIn(-10, 10)) }
    suspend fun treble(): Int = rc("GetTreble")["CurrentTreble"]?.toIntOrNull() ?: 0
    suspend fun setTreble(value: Int) { rc("SetTreble", "DesiredTreble" to value.coerceIn(-10, 10)) }
    suspend fun loudness(): Boolean = rc("GetLoudness", "Channel" to "Master")["CurrentLoudness"] == "1"
    suspend fun setLoudness(on: Boolean) { rc("SetLoudness", "Channel" to "Master", "DesiredLoudness" to if (on) 1 else 0) }

    // Volume (whole group, coordinator)

    suspend fun groupVolume(): Int = grc("GetGroupVolume")["CurrentVolume"]?.toIntOrNull() ?: 0

    /** Sonos scales the members proportionally from the last snapshot, so take one first. */
    suspend fun setGroupVolume(volume: Int) {
        grc("SnapshotGroupVolume")
        grc("SetGroupVolume", "DesiredVolume" to volume.coerceIn(0, 100))
    }

    // Content

    suspend fun browse(objectId: String, start: Int = 0, count: Int = 100, metadata: Boolean = false): BrowseResult {
        val r = soap.call(
            baseUrl, SonosService.ContentDirectory, "Browse",
            listOf(
                "ObjectID" to objectId,
                "BrowseFlag" to if (metadata) "BrowseMetadata" else "BrowseDirectChildren",
                "Filter" to "*",
                "StartingIndex" to start,
                "RequestedCount" to count,
                "SortCriteria" to "",
            ),
            slow = true,
        )
        return BrowseResult(
            items = Didl.parse(r["Result"]),
            totalMatches = r["TotalMatches"]?.toIntOrNull() ?: 0,
            updateId = r["UpdateID"]?.toIntOrNull(),
        )
    }

    /** Loads every page of a container. Empty containers return an empty list, not an error. */
    suspend fun browseAll(objectId: String, pageSize: Int = 100, limit: Int = 5000): List<DidlObject> =
        browseComplete(objectId, pageSize, limit).items

    /** A whole container plus whether Sonos really delivered all of it. */
    data class Listing(val items: List<DidlObject>, val total: Int) {
        val complete: Boolean get() = items.size >= total
    }

    /**
     * Like [browseAll], but says whether the list is complete. An S1 player that
     * is still resolving favorites may answer a page with no items although more
     * exist; that page is asked for once more after a pause.
     */
    suspend fun browseComplete(objectId: String, pageSize: Int = 100, limit: Int = 5000): Listing {
        val out = mutableListOf<DidlObject>()
        var total: Int
        var retried = false
        do {
            val page = browse(objectId, out.size, pageSize)
            total = page.totalMatches
            if (page.items.isEmpty()) {
                if (retried || out.size >= total) break
                retried = true
                kotlinx.coroutines.delay(1000)
                continue
            }
            out += page.items
        } while (out.size < total && out.size < limit)
        return Listing(out, minOf(total, limit))
    }

    /**
     * Searches the music library: artists, albums and tracks whose names
     * contain [term]. Sonos does this through Browse with IDs like
     * `A:ALBUM:term`. The three lists are loaded in parallel.
     */
    suspend fun search(term: String, perKind: Int = 30): LibrarySearch = coroutineScope {
        val q = java.net.URLEncoder.encode(term.trim(), "UTF-8").replace("+", "%20")
        val artists = async { browse("A:ALBUMARTIST:$q", 0, perKind).items }
        val albums = async { browse("A:ALBUM:$q", 0, perKind).items }
        val tracks = async { browse("A:TRACKS:$q", 0, perKind).items }
        LibrarySearch(artists.await(), albums.await(), tracks.await())
    }

    // Household and music services

    /** The household ID, which music services use to tell households apart. */
    suspend fun householdId(): String = soap.call(baseUrl, SonosService.DeviceProperties, "GetHouseholdID")["CurrentHouseholdID"].orEmpty()

    /** The player's serial number; music services get it as device ID. */
    suspend fun serialNumber(): String = soap.call(baseUrl, SonosService.DeviceProperties, "GetZoneInfo")["SerialNumber"].orEmpty()

    /** Every music service this household could use, linked or not. */
    suspend fun musicServices(): List<MusicService> =
        MusicService.parseList(soap.call(baseUrl, SonosService.MusicServices, "ListAvailableServices", slow = true)["AvailableServiceDescriptorList"].orEmpty())

    /** Absolute URL for a relative `/getaa?...` album art path. */
    fun absoluteArtUrl(albumArtUri: String?): String? = when {
        albumArtUri.isNullOrBlank() -> null
        albumArtUri.startsWith("http") -> albumArtUri
        else -> baseUrl.resolve(albumArtUri)?.toString()
    }
}
