// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One speaker (zone player) as the topology describes it. */
data class SonosPlayer(
    val uuid: String,
    val roomName: String,
    /** `http://host:1400/`, taken from the player's Location. */
    val baseUrl: HttpUrl,
    val icon: String? = null,
    val softwareVersion: String? = null,
    /** 1 = S1 (first generation software), 2 = S2. */
    val softwareGeneration: Int? = null,
    /** Satellites, subs and bonded stereo partners are invisible. */
    val isInvisible: Boolean = false,
)

/** A group of rooms that play the same thing. A single room is a group of one. */
data class ZoneGroup(
    val id: String,
    val coordinator: SonosPlayer,
    /** Visible members including the coordinator, coordinator first. */
    val members: List<SonosPlayer>,
) {
    /** "Wohnzimmer + Küche"; more than two rooms become "Wohnzimmer + 2". */
    val displayName: String
        get() = when (members.size) {
            1 -> coordinator.roomName
            2 -> "${members[0].roomName} + ${members[1].roomName}"
            else -> "${coordinator.roomName} + ${members.size - 1}"
        }
}

object Topology {
    /**
     * Parses the `ZoneGroupState` value of GetZoneGroupState (or of a
     * ZoneGroupTopology event). Newer firmware wraps it in an extra
     * `<ZoneGroupState>`, S1 sends `<ZoneGroups>` directly; both work.
     */
    fun parse(zoneGroupState: String): List<ZoneGroup> {
        val root = Xml.parse(zoneGroupState)
        return root.findAll("ZoneGroup").mapNotNull { g ->
            val members = g.children().filter { it.localName0 == "ZoneGroupMember" }.mapNotNull { m ->
                val base = m.attr("Location")?.toHttpUrlOrNull()?.resolve("/") ?: return@mapNotNull null
                SonosPlayer(
                    uuid = m.attr("UUID") ?: return@mapNotNull null,
                    roomName = m.attr("ZoneName").orEmpty(),
                    baseUrl = base,
                    icon = m.attr("Icon")?.substringAfter("x-rincon-roomicon:")?.ifBlank { null },
                    softwareVersion = m.attr("SoftwareVersion"),
                    softwareGeneration = m.attr("SWGen")?.toIntOrNull(),
                    isInvisible = m.attr("Invisible") == "1",
                )
            }.toList()
            val coordinatorId = g.attr("Coordinator") ?: return@mapNotNull null
            val coordinator = members.firstOrNull { it.uuid == coordinatorId } ?: return@mapNotNull null
            val visible = members.filterNot { it.isInvisible }
            ZoneGroup(
                id = g.attr("ID") ?: coordinatorId,
                coordinator = coordinator,
                members = listOf(coordinator) + visible.filter { it.uuid != coordinatorId }
                    .sortedBy { it.roomName.lowercase() },
            )
        }.sortedBy { it.coordinator.roomName.lowercase() }
    }
}

/** What device_description.xml says about a player. */
data class DeviceDescription(
    val uuid: String,
    val roomName: String,
    val modelName: String,
    val modelNumber: String,
    val displayName: String?,
    val softwareVersion: String?,
) {
    companion object {
        fun parse(xml: String): DeviceDescription {
            val device = Xml.parse(xml).find("device") ?: error("no <device> element")
            return DeviceDescription(
                uuid = device.childText("UDN").orEmpty().removePrefix("uuid:"),
                roomName = device.childText("roomName").orEmpty(),
                modelName = device.childText("modelName").orEmpty(),
                modelNumber = device.childText("modelNumber").orEmpty(),
                displayName = device.childText("displayName"),
                softwareVersion = device.childText("softwareVersion"),
            )
        }
    }
}
