// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.data

import de.linuxundich.hausmusik.sonos.NowPlaying
import de.linuxundich.hausmusik.sonos.PlayMode
import de.linuxundich.hausmusik.sonos.SonosPlayer
import de.linuxundich.hausmusik.sonos.TransportActions
import de.linuxundich.hausmusik.sonos.TransportState
import de.linuxundich.hausmusik.sonos.ZoneGroup

sealed interface Connection {
    data object Searching : Connection
    data object Connected : Connection
    /** Had the household, lost it for a moment; the last state stays visible. */
    data object Reconnecting : Connection
    /** No Wi-Fi or Ethernet (mobile data only, or offline): speakers cannot be reached. */
    data object NoLocalNetwork : Connection
    /** Nothing answered. [manualHost] is the address that was tried, if any. */
    data class NotFound(val manualHost: String?) : Connection
}

/** Everything the UI needs about one group. */
data class GroupState(
    val group: ZoneGroup,
    val transport: TransportState = TransportState.Stopped,
    val nowPlaying: NowPlaying? = null,
    /** Absolute cover URL. */
    val artUrl: String? = null,
    val groupVolume: Int = 0,
    val memberVolumes: Map<String, Int> = emptyMap(),
    val actions: TransportActions = TransportActions(emptySet()),
    val playMode: PlayMode = PlayMode.Normal,
    val queueLength: Int = 0,
    /** When the position was read, to extrapolate the progress bar between polls. */
    val positionReadAt: Long = 0,
    /** Remaining sleep timer, read together with the position; null when none runs. */
    val sleepRemainingMs: Long? = null,
) {
    val id: String get() = group.coordinator.uuid
    val isPlaying: Boolean get() = transport == TransportState.Playing || transport == TransportState.Transitioning
}

/** Tone settings of one speaker. */
data class SoundSettings(val bass: Int, val treble: Int, val loudness: Boolean)

data class Household(
    val connection: Connection = Connection.Searching,
    val groups: List<GroupState> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: GroupState? get() = groups.firstOrNull { it.id == selectedId } ?: groups.firstOrNull()
    val allPlayers: List<SonosPlayer> get() = groups.flatMap { it.group.members }.sortedBy { it.roomName.lowercase() }
}
