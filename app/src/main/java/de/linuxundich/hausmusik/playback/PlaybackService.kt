// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import de.linuxundich.hausmusik.MainActivity
import de.linuxundich.hausmusik.data.HouseholdRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the media session. While it lives, the household stays up to date in
 * the background; it ends itself when nothing has played for a while and the
 * app is not on screen, so it does not keep the phone awake for nothing.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var household: HouseholdRepository

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var idleTimer: Job? = null
    private var routeCallback: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        household.acquire(OWNER)
        val player = SonosSessionPlayer(household)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open)
            .setBitmapLoader(de.linuxundich.hausmusik.data.covers.CoilBitmapLoader(this, scope))
            .build()
        // Android binds route providers only while someone discovers routes. By
        // asking for remote playback routes ourselves, SonosRouteProvider gets
        // bound and the output chip can show the room.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val router = MediaRouter2.getInstance(this)
            val callback = object : MediaRouter2.RouteCallback() {}
            router.registerRouteCallback(
                mainExecutor, callback,
                RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK), false).build(),
            )
            routeCallback = { router.unregisterRouteCallback(callback) }
        }
        scope.launch {
            household.state.map { it.selected?.isPlaying == true }.distinctUntilChanged().collect { playing ->
                idleTimer?.cancel()
                if (!playing) idleTimer = scope.launch {
                    delay(IDLE_STOP_MS)
                    if (!household.isOwnedBy("activity")) stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        routeCallback?.invoke()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        household.release(OWNER)
        super.onDestroy()
    }

    private companion object {
        const val OWNER = "service"
        const val IDLE_STOP_MS = 10 * 60 * 1000L
    }
}
