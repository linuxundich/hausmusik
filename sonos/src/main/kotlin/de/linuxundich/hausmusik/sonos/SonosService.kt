// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

/** The UPnP services of a Sonos player that Hausmusik talks to. */
enum class SonosService(val controlPath: String, val eventPath: String) {
    AVTransport("/MediaRenderer/AVTransport/Control", "/MediaRenderer/AVTransport/Event"),
    RenderingControl("/MediaRenderer/RenderingControl/Control", "/MediaRenderer/RenderingControl/Event"),
    GroupRenderingControl("/MediaRenderer/GroupRenderingControl/Control", "/MediaRenderer/GroupRenderingControl/Event"),
    ZoneGroupTopology("/ZoneGroupTopology/Control", "/ZoneGroupTopology/Event"),
    ContentDirectory("/MediaServer/ContentDirectory/Control", "/MediaServer/ContentDirectory/Event"),
    AlarmClock("/AlarmClock/Control", "/AlarmClock/Event"),
    DeviceProperties("/DeviceProperties/Control", "/DeviceProperties/Event"),
    MusicServices("/MusicServices/Control", "/MusicServices/Event"),
    ;

    val urn: String = "urn:schemas-upnp-org:service:$name:1"
}
