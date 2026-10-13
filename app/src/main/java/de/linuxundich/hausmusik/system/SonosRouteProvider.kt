package de.linuxundich.hausmusik.system

import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.Household
import de.linuxundich.hausmusik.data.HouseholdRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publishes the Sonos groups as media routes. With it, Android's media output
 * chip shows the room instead of "This phone", and the output switcher lists
 * every room: transferring there shows that room in Hausmusik, adding a room
 * lets it play along.
 *
 * The media session points at [RouteIds.SESSION_ID] through
 * DeviceInfo.routingControllerId; the system matches it with the session
 * published here (same original ID, same package).
 */
@RequiresApi(Build.VERSION_CODES.R)
@AndroidEntryPoint
class SonosRouteProvider : MediaRoute2ProviderService() {
    @Inject lateinit var household: HouseholdRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionPublished = false

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            household.state
                .map { h -> h.groups.map { listOf(it.id, it.group.displayName, it.groupVolume, it.nowPlaying?.title, it.isPlaying) } to h.selectedId }
                .distinctUntilChanged()
                .collect { publish(household.state.value) }
        }
    }

    private fun publish(h: Household) {
        notifyRoutes(h.groups.map { route(it, it.id == h.selected?.id) })
        val selected = h.selected ?: return
        val session = RoutingSessionInfo.Builder(RouteIds.SESSION_ID, packageName)
            .setName(selected.group.displayName)
            .addSelectedRoute(selected.id)
            .apply {
                h.groups.filter { it.id != selected.id }.forEach {
                    addTransferableRoute(it.id)
                    addSelectableRoute(it.id)
                }
            }
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(100)
            .setVolume(selected.groupVolume)
            .build()
        if (sessionPublished) {
            notifySessionUpdated(session)
        } else {
            notifySessionCreated(REQUEST_ID_NONE, session)
            sessionPublished = true
        }
    }

    private fun route(g: GroupState, selected: Boolean): MediaRoute2Info {
        val now = g.nowPlaying
        return MediaRoute2Info.Builder(g.id, g.group.displayName)
            .addFeature(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
            .addFeature(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK)
            .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
            .setDescription(now?.let { listOfNotNull(it.title, it.artist).joinToString(" · ") })
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(100)
            .setVolume(g.groupVolume)
            .setConnectionState(if (selected) MediaRoute2Info.CONNECTION_STATE_CONNECTED else MediaRoute2Info.CONNECTION_STATE_DISCONNECTED)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setType(if (g.group.members.size > 1) MediaRoute2Info.TYPE_GROUP else MediaRoute2Info.TYPE_REMOTE_SPEAKER)
                }
            }
            .build()
    }

    // Requests from the output switcher

    override fun onCreateSession(requestId: Long, packageName: String, routeId: String, sessionHints: Bundle?) {
        household.select(routeId)
        val h = household.state.value
        val g = h.groups.firstOrNull { it.id == routeId }
        if (g == null) {
            notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
            return
        }
        val session = RoutingSessionInfo.Builder(RouteIds.SESSION_ID, packageName)
            .setName(g.group.displayName).addSelectedRoute(g.id)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE).setVolumeMax(100).setVolume(g.groupVolume)
            .build()
        notifySessionCreated(requestId, session)
        sessionPublished = true
    }

    /**
     * "Stop streaming" pauses the room. The session stays: the Sonos group still
     * exists, and in the background Android would not bind the provider again
     * to bring a released session back.
     */
    override fun onReleaseSession(requestId: Long, sessionId: String) {
        Log.i(TAG, "release session $sessionId")
        scope.launch {
            household.command { it.pause() }
            publish(household.state.value)
        }
    }

    /** "Transfer" = show this room in Hausmusik (and in the media notification). */
    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) {
        Log.i(TAG, "transfer to $routeId")
        household.select(routeId)
    }

    /** "Add" = let the room play along with the current one. */
    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) {
        val h = household.state.value
        val source = h.groups.firstOrNull { it.id == routeId } ?: return
        val target = h.selected ?: return
        scope.launch { household.mergeGroups(source, target) }
    }

    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) {
        val players = household.state.value.allPlayers.filter { it.uuid == routeId }
        scope.launch { household.separate(players) }
    }

    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) {
        household.requestGroupVolume(routeId, volume)
    }

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) {
        household.state.value.selected?.let { household.requestGroupVolume(it.id, volume) }
    }

    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

}

/** Kept outside the provider, which only exists from API 30 on. */
private const val TAG = "SonosRoutes"

object RouteIds {
    /** Shared by the route provider's session and the media session's DeviceInfo.routingControllerId. */
    const val SESSION_ID = "hausmusik"
}
