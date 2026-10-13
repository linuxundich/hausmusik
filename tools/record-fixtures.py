#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Christoph Langner
# SPDX-License-Identifier: GPL-3.0-or-later
"""Record read-only SOAP responses from a real Sonos household.

Only Get*/Browse/List actions are sent; nothing changes on the speakers.
Output goes to tools/fixtures-local/ (git-ignored, contains personal data).
Usage: tools/record-fixtures.py 192.168.0.59 [more IPs]
"""
import pathlib, re, sys, urllib.request

OUT = pathlib.Path(__file__).parent / "fixtures-local"
SERVICES = {
    "AVTransport": "/MediaRenderer/AVTransport/Control",
    "RenderingControl": "/MediaRenderer/RenderingControl/Control",
    "GroupRenderingControl": "/MediaRenderer/GroupRenderingControl/Control",
    "ZoneGroupTopology": "/ZoneGroupTopology/Control",
    "ContentDirectory": "/MediaServer/ContentDirectory/Control",
    "AlarmClock": "/AlarmClock/Control",
    "DeviceProperties": "/DeviceProperties/Control",
}
READ_ONLY = re.compile(r"^(Get|Browse|List)")


def soap(ip, service, action, args):
    assert READ_ONLY.match(action), action
    body = "".join(f"<{k}>{v}</{k}>" for k, v in args)
    env = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
        f'<u:{action} xmlns:u="urn:schemas-upnp-org:service:{service}:1">{body}</u:{action}>'
        "</s:Body></s:Envelope>"
    )
    req = urllib.request.Request(
        f"http://{ip}:1400{SERVICES[service]}", data=env.encode(),
        headers={"Content-Type": 'text/xml; charset="utf-8"',
                 "SOAPACTION": f'"urn:schemas-upnp-org:service:{service}:1#{action}"'})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        return e.read().decode()


def save(name, text):
    OUT.mkdir(exist_ok=True)
    (OUT / name).write_text(text)
    print(name, len(text))


def browse(oid, flag="BrowseDirectChildren", count=20):
    return [("ObjectID", oid), ("BrowseFlag", flag), ("Filter", "*"),
            ("StartingIndex", 0), ("RequestedCount", count), ("SortCriteria", "")]


for ip in sys.argv[1:]:
    tag = ip.split(".")[-1]
    with urllib.request.urlopen(f"http://{ip}:1400/xml/device_description.xml", timeout=5) as r:
        save(f"device_description-{tag}.xml", r.read().decode())
    save(f"GetVolume-{tag}.xml", soap(ip, "RenderingControl", "GetVolume", [("InstanceID", 0), ("Channel", "Master")]))
    save(f"GetMute-{tag}.xml", soap(ip, "RenderingControl", "GetMute", [("InstanceID", 0), ("Channel", "Master")]))
    save(f"GetBass-{tag}.xml", soap(ip, "RenderingControl", "GetBass", [("InstanceID", 0)]))
    save(f"GetLoudness-{tag}.xml", soap(ip, "RenderingControl", "GetLoudness", [("InstanceID", 0), ("Channel", "Master")]))
    for a in ["GetTransportInfo", "GetPositionInfo", "GetMediaInfo", "GetTransportSettings", "GetCurrentTransportActions"]:
        save(f"{a}-{tag}.xml", soap(ip, "AVTransport", a, [("InstanceID", 0)]))
    save(f"GetGroupVolume-{tag}.xml", soap(ip, "GroupRenderingControl", "GetGroupVolume", [("InstanceID", 0)]))

ip = sys.argv[1]
save("GetZoneGroupState.xml", soap(ip, "ZoneGroupTopology", "GetZoneGroupState", []))
for name, oid in [("favorites", "FV:2"), ("playlists", "SQ:"), ("queue", "Q:0"), ("library-root", "A:"),
                  ("albums", "A:ALBUM"), ("artists", "A:ARTIST"), ("radio", "R:0/0"), ("shares", "S:")]:
    save(f"Browse-{name}.xml", soap(ip, "ContentDirectory", "Browse", browse(oid)))
save("ListAlarms.xml", soap(ip, "AlarmClock", "ListAlarms", []))
