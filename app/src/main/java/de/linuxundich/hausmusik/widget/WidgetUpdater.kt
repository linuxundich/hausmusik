// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * Redraws the widget when what it shows changes: room, title, cover, playing,
 * the connection (no Wi-Fi, no speakers), for the tall size the queue
 * length and the playing track, and the selected room's volume for the player's volume row. The position
 * does not count, so a playing track does not redraw it every second.
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
                .map { h -> h.connection::class to h.selected?.let { listOf(it.group.displayName, it.nowPlaying?.title, it.nowPlaying?.artist, it.artUrl, it.isPlaying, it.queueLength, it.nowPlaying?.queuePosition, it.groupVolume, it.memberVolumes, it.memberMutes) } }
                .distinctUntilChanged()
                .debounce(500)
                .collect { runCatching { PlayerWidget.refresh(context) } }
        }
        // The room widgets show every group, not only the selected one.
        scope.launch {
            household.state
                .map { h -> h.connection::class to h.groups.map { listOf(it.group.displayName, it.nowPlaying?.title, it.nowPlaying?.artist, it.artUrl, it.isPlaying) } }
                .distinctUntilChanged()
                .debounce(500)
                .collect { runCatching { RoomWidget.refresh(context) } }
        }
    }
}
