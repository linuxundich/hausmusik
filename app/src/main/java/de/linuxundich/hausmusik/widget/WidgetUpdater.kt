package de.linuxundich.hausmusik.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Redraws the widget when what it shows changes: room, title, cover, playing.
 * Position and volume do not count, so a playing track does not redraw it
 * every second.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val household: HouseholdRepository,
    @AppScope private val scope: CoroutineScope,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            household.state
                .map { h -> h.selected?.let { listOf(it.group.displayName, it.nowPlaying?.title, it.nowPlaying?.artist, it.artUrl, it.isPlaying) } }
                .distinctUntilChanged()
                .debounce(500)
                .collect { runCatching { PlayerWidget.refresh(context) } }
        }
    }
}
