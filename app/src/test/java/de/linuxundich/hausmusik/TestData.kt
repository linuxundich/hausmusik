// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik

import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.sonos.NowPlaying
import de.linuxundich.hausmusik.sonos.SonosPlayer
import de.linuxundich.hausmusik.sonos.SourceKind
import de.linuxundich.hausmusik.sonos.TransportActions
import de.linuxundich.hausmusik.sonos.TransportState
import de.linuxundich.hausmusik.sonos.ZoneGroup
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Invented rooms and tracks for UI tests, matching the fake household. */
object TestData {
    fun player(name: String, n: Int) = SonosPlayer("RINCON_TEST$n", name, "http://127.0.0.1:${1400 + n}/".toHttpUrl())

    val wohnzimmer = player("Wohnzimmer", 1)
    val kueche = player("Küche", 2)

    fun queueGroup(playing: Boolean = true) = GroupState(
        group = ZoneGroup("G1", wohnzimmer, listOf(wohnzimmer, kueche)),
        transport = if (playing) TransportState.Playing else TransportState.Paused,
        nowPlaying = NowPlaying(
            title = "Segfault Serenade", artist = "Penny Guin", album = "Kernel Panic Ballads", station = null,
            albumArtUri = null, durationMs = 173_000, positionMs = 60_000, kind = SourceKind.Queue, queuePosition = 2,
        ),
        actions = TransportActions(setOf("Play", "Pause", "Next", "Previous", "Seek")),
        groupVolume = 24,
        queueLength = 5,
    )

    fun radioGroup() = GroupState(
        group = ZoneGroup("G2", player("Arbeitszimmer", 3), listOf(player("Arbeitszimmer", 3))),
        transport = TransportState.Playing,
        nowPlaying = NowPlaying(
            title = "Uptime", artist = "Penny Guin", album = null, station = "Radio Tuxfunk",
            albumArtUri = null, durationMs = null, positionMs = null, kind = SourceKind.Radio, queuePosition = null,
        ),
        actions = TransportActions(setOf("Play", "Pause", "Stop")),
        groupVolume = 22,
    )
}
