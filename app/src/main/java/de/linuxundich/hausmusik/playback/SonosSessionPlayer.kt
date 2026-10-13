package de.linuxundich.hausmusik.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.sonos.SourceKind
import de.linuxundich.hausmusik.system.RouteIds
import de.linuxundich.hausmusik.sonos.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The selected Sonos group as a Media3 player of type "remote". Media3 builds
 * the media notification and lock screen controls from it, and Android routes
 * the hardware volume buttons to [handleSetDeviceVolume] and friends.
 *
 * Media3 only skips when the playlist has a next or previous item, so the
 * playlist is a window of up to three entries around the current track; the
 * neighbours are placeholders that turn into next() and previous().
 */
@UnstableApi
class SonosSessionPlayer(private val household: HouseholdRepository) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var group: GroupState? = household.state.value.selected

    init {
        scope.launch {
            household.state.map { it.selected }.distinctUntilChanged().collect {
                group = it
                invalidateState()
            }
        }
    }

    val isPlayingNow: Boolean get() = group?.isPlaying == true

    override fun getState(): State {
        val g = group
        val now = g?.nowPlaying
        val builder = State.Builder()
            .setDeviceInfo(REMOTE)
            .setDeviceVolume(g?.groupVolume ?: 0)
        if (g == null || now == null) {
            return builder
                .setAvailableCommands(Player.Commands.Builder().addAll(*VOLUME_COMMANDS).build())
                .setPlaybackState(STATE_IDLE)
                .build()
        }
        val isRadio = now.kind == SourceKind.Radio
        val canSeek = g.actions.canSeek && (now.durationMs ?: 0) > 0
        val hasNext = !isRadio && g.actions.canNext
        val hasPrevious = !isRadio && (now.queuePosition ?: 1) > 1

        val key = "${g.id}|${now.title}|${now.artist}|${g.artUrl}"
        val metadata = MediaMetadata.Builder()
            .setTitle(now.title)
            .setArtist(if (isRadio) listOfNotNull(now.artist, now.station).joinToString(" · ") else now.artist)
            .setAlbumTitle(now.album)
            .setStation(now.station)
            .setSubtitle(g.group.displayName)
            .setArtworkUri(g.artUrl?.let(Uri::parse))
            .setIsPlayable(true)
            .build()
        val current = MediaItemData.Builder(key)
            .setMediaItem(MediaItem.Builder().setMediaId(key).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(now.durationMs?.takeIf { it > 0 }?.times(1000) ?: androidx.media3.common.C.TIME_UNSET)
            .setIsSeekable(canSeek)
            .setIsDynamic(isRadio)
            .setLiveConfiguration(if (isRadio) MediaItem.LiveConfiguration.UNSET else null)
            .build()
        val items = buildList {
            if (hasPrevious) add(placeholder("prev|$key"))
            add(current)
            if (hasNext) add(placeholder("next|$key"))
        }
        val playing = g.isPlaying
        val readAt = g.positionReadAt
        val basePosition = now.positionMs ?: 0
        val duration = now.durationMs
        val commands = Player.Commands.Builder()
            .addAll(*VOLUME_COMMANDS)
            .addAll(COMMAND_PLAY_PAUSE, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_METADATA, COMMAND_GET_TIMELINE, COMMAND_STOP)
            .addIf(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, canSeek)
            .addIf(COMMAND_SEEK_TO_NEXT, hasNext)
            .addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext)
            .addIf(COMMAND_SEEK_TO_PREVIOUS, !isRadio)
            .addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPrevious)
            .build()
        return builder
            .setAvailableCommands(commands)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(if (hasPrevious) 1 else 0)
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (g.transport == TransportState.Transitioning) STATE_BUFFERING else STATE_READY)
            .setContentPositionMs(
                PositionSupplier {
                    val elapsed = if (playing && readAt > 0) System.currentTimeMillis() - readAt else 0
                    val p = basePosition + elapsed
                    if (duration != null && duration > 0) p.coerceAtMost(duration) else p
                },
            )
            .build()
    }

    private fun placeholder(uid: String) = MediaItemData.Builder(uid)
        .setMediaItem(MediaItem.Builder().setMediaId(uid).build())
        .build()

    private fun launchCommand(block: suspend () -> Unit): ListenableFuture<*> {
        scope.launch { block() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = launchCommand {
        household.command { if (playWhenReady) it.play() else it.pause() }
    }

    override fun handleStop() = launchCommand { household.command { it.pause() } }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val currentIndex = currentMediaItemIndex
        return launchCommand {
            household.command {
                when {
                    mediaItemIndex > currentIndex -> it.next()
                    mediaItemIndex < currentIndex -> it.previous()
                    else -> it.seekTo(positionMs.coerceAtLeast(0))
                }
            }
        }
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        group?.let { household.requestGroupVolume(it.id, deviceVolume) }
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        household.adjustSelectedVolume(+STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        household.adjustSelectedVolume(-STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> = launchCommand {
        household.command { it.setMute(muted) }
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val STEP = 2
        // The routing controller ID ties the session to the route provider's
        // session, so the output chip shows the room (SonosRouteProvider).
        val REMOTE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100)
            .setRoutingControllerId(RouteIds.SESSION_ID)
            .build()
        val VOLUME_COMMANDS = intArrayOf(
            COMMAND_GET_DEVICE_VOLUME,
            COMMAND_SET_DEVICE_VOLUME,
            COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            COMMAND_ADJUST_DEVICE_VOLUME,
            COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
    }
}
