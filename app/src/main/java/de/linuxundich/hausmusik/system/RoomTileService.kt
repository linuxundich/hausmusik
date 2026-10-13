package de.linuxundich.hausmusik.system

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.Household
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: shows the selected room and pauses or resumes it. */
class RoomTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val household = HouseholdAccess.entry(this).household()
        render(household.state.value)
        // Connect briefly so the tile shows the current state, then follow it.
        scope.launch { HouseholdAccess.withSelected(this@RoomTileService) { _, _ -> } }
        watcher?.cancel()
        watcher = scope.launch { household.state.collect(::render) }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            HouseholdAccess.withSelected(this@RoomTileService) { household, group ->
                household.command { if (group.isPlaying) it.pause() else it.play() }
            }
        }
    }

    private fun render(h: Household) {
        val tile = qsTile ?: return
        val g = h.selected
        tile.icon = Icon.createWithResource(this, R.drawable.ic_speaker)
        tile.label = g?.group?.displayName ?: getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = when {
            g == null -> getString(R.string.setup_searching)
            g.nowPlaying == null -> getString(R.string.rooms_idle)
            g.isPlaying -> g.nowPlaying.title
            else -> getString(R.string.rooms_paused)
        }
        tile.state = when {
            g?.nowPlaying == null -> Tile.STATE_INACTIVE
            g.isPlaying -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
