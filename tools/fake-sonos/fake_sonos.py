#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Christoph Langner
# SPDX-License-Identifier: GPL-3.0-or-later
"""A fake Sonos household for the emulator, tests and screenshots.

Four invented players listen on ports 1401-1404. They answer the SOAP actions
Hausmusik uses, keep state (playback, volume, groups, queue) and serve
generated album covers. Nothing here talks to real speakers.

    tools/fake-sonos/fake_sonos.py --advertise 10.0.2.2   # for the emulator

Then point the app at 10.0.2.2:1401 (debug settings).
"""
import argparse, html, io, json, re, sys, threading, time, urllib.parse, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
except ImportError:  # covers are optional
    Image = None

# --english: English room, album and playlist names, for screenshots on the
# English README and store page. The tests use the German default.
EN = "--english" in sys.argv


def L(de, en):
    return en if EN else de


ROOMS = [  # port, uuid, room, model, icon
    (1401, "RINCON_FAKE000000101400", L("Wohnzimmer", "Living Room"), "Five", "living"),
    (1402, "RINCON_FAKE000000201400", L("Küche", "Kitchen"), "Era 100", "kitchen"),
    (1403, "RINCON_FAKE000000301400", L("Arbeitszimmer", "Office"), "Play:3", "office"),
    (1404, "RINCON_FAKE000000401400", L("Bad", "Bathroom"), "Play:1", "bathroom"),
]

ALBUMS = [  # invented artists and albums
    ("Kernel Panic Ballads", "Penny Guin", ["#ffb347", "#ff3d7f", "#2b0b4a"],
     ["Border of the Stack", "Segfault Serenade", "Uptime", "Swap on Me", "Init 0"]),
    ("Rolling Release", "Arch Ibald Trio", ["#38f9d7", "#2a5bd7", "#0b1240"],
     ["Pacman Syncs", "Bleeding Edge", "AUR Lullaby", "Rebase Blues"]),
    ("Long Term Support", "Debby Ann & The Packages", ["#ffd23f", "#3bceac", "#0e2a47"],
     ["Stable", "Backports", "Five Years", "Point Release"]),
    (L("Hutmacher", "Hat Trick"), "Fedora Hutmacher", ["#ffe14d", "#ff4fa3", "#3d1a78"],
     ["Red Hat Rag", "Rawhide", "Silverblue", "Spin"]),
    (L("Pinguinzoo", "Penguin Parade"), "Tina Tux", ["#ffcf70", "#ff7a59", "#5b2a86"],
     ["Ice Floe", "Waddle", "Fish Supper", "Colony", "Snow Crash"]),
    (L("Frühstück im Terminal", "Breakfast in the Terminal"), "Pac Mann", ["#ff9f43", "#e8573d", "#2b1d1a"],
     [L("Kaffee und Cron", "Coffee and Cron"), "Bashrc", "Grep the Sun"]),
    # Real and CC BY 4.0 (joshwoodward.com), so lyrics can be tested against LRCLIB.
    ("Breadcrumbs", "Josh Woodward", ["#c9d6e8", "#5d7391", "#1f2a3a"],
     ["Border Blaster"]),
]
if EN:  # screenshots show invented music only, not the real CC album
    ALBUMS.pop()
GENRES = ["Rock", "Folk", "Folk", "Rock", "Pop", "Jazz", "Folk"]  # per album
RADIO = [("Radio Tuxfunk", "x-rincon-mp3radio://radio.example/tuxfunk", ["#ff5f6d", "#7b2ff7"]),
         ("Kernel FM", "x-rincon-mp3radio://radio.example/kernelfm", ["#00c6ff", "#0a3d91"])]

ALARMS = {"1": {"StartTime": "06:45:00", "Duration": "01:00:00", "Recurrence": "WEEKDAYS", "Enabled": "1",
               "RoomUUID": "RINCON_FAKE000000301400", "ProgramURI": "x-rincon-mp3radio://radio.example/kernelfm",
               "ProgramMetaData": "", "PlayMode": "NORMAL", "Volume": "18", "IncludeLinkedZones": "0"},
          "2": {"StartTime": "09:30:00", "Duration": "00:30:00", "Recurrence": "WEEKENDS", "Enabled": "0",
               "RoomUUID": "RINCON_FAKE000000101400", "ProgramURI": "x-rincon-buzzer:0",
               "ProgramMetaData": "", "PlayMode": "NORMAL", "Volume": "20", "IncludeLinkedZones": "1"}}
SAVED = [(L("Morgens", "Mornings"), [])]  # Sonos playlists: (title, tracks); a deleted one becomes None so the IDs stay
FAVS_EXTRA, FAVS_REMOVED = [], set()  # favorites added and removed through CreateObject/DestroyObject
LINE_IN = {L("Wohnzimmer", "Living Room"): L("Plattenspieler", "Turntable")}  # rooms with an analog input and its name
TRACKS = []
for ai, (album, artist, colors, titles) in enumerate(ALBUMS):
    for ti, t in enumerate(titles):
        TRACKS.append(dict(title=t, artist=artist, album=album, art=f"album{ai}",
                           dur=280 if t == "Border Blaster" else 150 + (ai * 37 + ti * 23) % 120,
                           uri=f"x-file-cifs://nas/music/{ai}/{ti}.mp3", nr=ti + 1))

lock = threading.Lock()
SAVED[0] = (SAVED[0][0], TRACKS[14:20])
ADVERTISE = "127.0.0.1"


class Player:
    def __init__(self, port, uid, room, model, icon):
        self.port, self.uuid, self.room, self.model, self.icon = port, uid, room, model, icon
        self.coordinator = uid
        self.volume, self.mute, self.bass, self.treble, self.loudness = 25, False, 0, 0, True
        self.state = "STOPPED"
        self.queue = []
        self.track = 1
        self.position, self.started = 0.0, None
        self.media_uri = ""
        self.radio = None
        self.playmode = "NORMAL"
        self.sleep_until = None

    @property
    def location(self):
        return f"http://{ADVERTISE}:{self.port}/xml/device_description.xml"

    def pos(self):
        p = self.position + (time.time() - self.started if self.started else 0)
        cur = self.current()
        if cur and p >= cur["dur"]:  # advance to the next track
            self.position, self.started = 0.0, time.time() if self.state == "PLAYING" else None
            if self.track < len(self.queue):
                self.track += 1
            else:
                self.track, self.state, self.started = 1, "STOPPED", None
            return 0.0
        return p

    def current(self):
        if self.radio or not self.queue:
            return None
        return self.queue[min(self.track, len(self.queue)) - 1]


players = {p[1]: Player(*p) for p in ROOMS}
by_port = {p.port: p for p in players.values()}
wz, ku, az, bad = (players[r[1]] for r in ROOMS)
ku.coordinator = wz.uuid
wz.queue, wz.media_uri, wz.state, wz.started, wz.volume = TRACKS[:5], f"x-rincon-queue:{wz.uuid}#0", "PLAYING", time.time(), 30
wz.position, ku.volume = 72, 18
az.radio, az.media_uri, az.state, az.volume = RADIO[0], RADIO[0][1], "PAUSED_PLAYBACK", 22
bad.volume = 12
az.queue = TRACKS[5:14]


def coord(p):
    return players[p.coordinator]


def hms(s):
    s = int(max(0, s))
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}"


def secs(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def didl(inner):
    return ('<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
            'xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">'
            + inner + "</DIDL-Lite>")


def track_item(t, oid, parent="Q:0"):
    e = html.escape
    return (f'<item id="{oid}" parentID="{parent}" restricted="true"><res protocolInfo="x-file-cifs:*:audio/mpeg:*" '
            f'duration="{hms(t["dur"])}">{e(t["uri"])}</res><upnp:albumArtURI>/getaa?u={t["art"]}</upnp:albumArtURI>'
            f'<dc:title>{e(t["title"])}</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>'
            f'<dc:creator>{e(t["artist"])}</dc:creator><upnp:album>{e(t["album"])}</upnp:album>'
            f'<upnp:originalTrackNumber>{t["nr"]}</upnp:originalTrackNumber></item>')


def zone_group_state():
    out = ["<ZoneGroupState><ZoneGroups>"]
    for c in [p for p in players.values() if p.coordinator == p.uuid]:
        out.append(f'<ZoneGroup Coordinator="{c.uuid}" ID="{c.uuid}:1">')
        for m in [p for p in players.values() if p.coordinator == c.uuid]:
            out.append(f'<ZoneGroupMember UUID="{m.uuid}" Location="{m.location}" ZoneName="{html.escape(m.room)}" '
                       f'Icon="x-rincon-roomicon:{m.icon}" SoftwareVersion="85.0-66270" SWGen="2"/>')
        out.append("</ZoneGroup>")
    out.append("</ZoneGroups><VanishedDevices></VanishedDevices></ZoneGroupState>")
    return "".join(out)


def album_container(ai):
    album, artist, _, _ = ALBUMS[ai]
    return (f'<container id="A:ALBUM/{ai}" parentID="A:ALBUM" restricted="true"><dc:title>{html.escape(album)}</dc:title>'
            f'<upnp:class>object.container.album.musicAlbum</upnp:class><res protocolInfo="x-rincon-playlist:*:*:*">x-rincon-playlist:X#A:ALBUM/{ai}</res>'
            f'<dc:creator>{html.escape(artist)}</dc:creator><upnp:albumArtURI>/getaa?u=album{ai}</upnp:albumArtURI></container>')


def artist_container(name):
    q = urllib.parse.quote(name)
    return (f'<container id="A:ALBUMARTIST/{q}" parentID="A:ALBUMARTIST" restricted="true"><dc:title>{html.escape(name)}</dc:title>'
            f'<upnp:class>object.container.person.musicArtist</upnp:class><res protocolInfo="x-rincon-playlist:*:*:*">x-rincon-playlist:X#A:ALBUMARTIST/{q}</res></container>')


def browse(p, oid, start, count):
    items = []
    if oid == "FV:2":
        for i, (name, uri, _) in enumerate(RADIO):
            md = didl(f'<item id="R:0/0/{i}" parentID="R:0/0" restricted="true"><dc:title>{name}</dc:title>'
                      '<upnp:class>object.item.audioItem.audioBroadcast</upnp:class></item>')
            items.append(f'<item id="FV:2/{i}" parentID="FV:2" restricted="false"><dc:title>{name}</dc:title>'
                         f'<upnp:class>object.itemobject.item.sonos-favorite</upnp:class><res protocolInfo="x-rincon-mp3radio:*:*:*">{html.escape(uri)}</res>'
                         f'<upnp:albumArtURI>/getaa?u=radio{i}</upnp:albumArtURI><r:type>instantPlay</r:type><r:description>Radio</r:description>'
                         f'<r:resMD>{html.escape(md)}</r:resMD></item>')
        for ai, (album, artist, _, _) in enumerate(ALBUMS[:3]):
            items.append(f'<item id="FV:2/{10 + ai}" parentID="FV:2" restricted="false"><dc:title>{html.escape(album)}</dc:title>'
                         f'<upnp:class>object.itemobject.item.sonos-favorite</upnp:class><res protocolInfo="x-rincon-playlist:*:*:*">x-rincon-playlist:{p.uuid}#A:ALBUM/{ai}</res>'
                         f'<upnp:albumArtURI>/getaa?u=album{ai}</upnp:albumArtURI><r:type>instantPlay</r:type><r:description>{html.escape(artist)}</r:description></item>')
        items.append(f'<item id="FV:2/20" parentID="FV:2" restricted="false"><dc:title>{html.escape(TRACKS[1]["title"])}</dc:title>'
                     f'<upnp:class>object.itemobject.item.sonos-favorite</upnp:class><res protocolInfo="sonos.com-http:*:audio/mpeg:*">x-sonos-http:track%3a1.mp3?sid=246&amp;flags=8224&amp;sn=7</res>'
                     f'<upnp:albumArtURI>/getaa?u={TRACKS[1]["art"]}</upnp:albumArtURI><r:type>instantPlay</r:type><r:description>bonob</r:description></item>')
        items += FAVS_EXTRA
        items = [it for it in items if re.search(r'id="([^"]+)"', it).group(1) not in FAVS_REMOVED]
    elif oid == "R:0/0":
        for i, (name, uri, _) in enumerate(RADIO):
            items.append(f'<item id="R:0/0/{i}" parentID="R:0/0" restricted="false"><dc:title>{name}</dc:title>'
                         f'<upnp:class>object.item.audioItem.audioBroadcast</upnp:class><res protocolInfo="x-rincon-mp3radio:*:*:*">{html.escape(uri)}</res></item>')
    elif oid.startswith("A:") and oid.count(":") == 2:
        # Search: A:ALBUM:term, A:ALBUMARTIST:term, A:TRACKS:term
        kind, term = oid[2:].split(":", 1)
        term = urllib.parse.unquote(term).lower()
        if kind == "ALBUM":
            items = [album_container(ai) for ai, a in enumerate(ALBUMS) if term in a[0].lower()]
        elif kind in ("ALBUMARTIST", "ARTIST"):
            artists = sorted({a[1] for a in ALBUMS})
            items = [artist_container(n) for n in artists if term in n.lower()]
        elif kind == "TRACKS":
            items = [track_item(t, f"A:TRACKS/{i}", "A:TRACKS") for i, t in enumerate(TRACKS) if term in t["title"].lower()]
    elif oid == "A:ALBUMARTIST":
        items = [artist_container(n) for n in sorted({a[1] for a in ALBUMS})]
    elif oid.startswith("A:ALBUMARTIST/"):
        name = urllib.parse.unquote(oid.split("/", 1)[1])
        items = [album_container(ai) for ai, a in enumerate(ALBUMS) if a[1] == name]
    elif oid == "A:GENRE":
        items = [f'<container id="A:GENRE/{urllib.parse.quote(g)}" parentID="A:GENRE" restricted="true"><dc:title>{g}</dc:title>'
                 '<upnp:class>object.container.genre.musicGenre</upnp:class></container>' for g in sorted(set(GENRES))]
    elif oid.startswith("A:GENRE/"):
        g = urllib.parse.unquote(oid.split("/", 1)[1])
        items = [album_container(ai) for ai in range(len(ALBUMS)) if GENRES[ai] == g]
    elif oid == "A:ALBUM":
        for ai, (album, artist, _, _) in enumerate(ALBUMS):
            items.append(f'<container id="A:ALBUM/{ai}" parentID="A:ALBUM" restricted="true"><dc:title>{html.escape(album)}</dc:title>'
                         f'<upnp:class>object.container.album.musicAlbum</upnp:class><res protocolInfo="x-rincon-playlist:*:*:*">x-rincon-playlist:{p.uuid}#A:ALBUM/{ai}</res>'
                         f'<dc:creator>{html.escape(artist)}</dc:creator><upnp:albumArtURI>/getaa?u=album{ai}</upnp:albumArtURI></container>')
    elif oid.startswith("A:ALBUM/"):
        ai = int(oid.split("/")[1])
        items = [track_item(t, f"{oid}/{i}", oid) for i, t in enumerate(TRACKS) if t["album"] == ALBUMS[ai][0]]
    elif oid == "Q:0":
        c = coord(p)
        items = [track_item(t, f"Q:0/{i + 1}") for i, t in enumerate(c.queue)]
    elif oid == "SQ:":
        items = [f'<container id="SQ:{i + 1}" parentID="SQ:" restricted="true"><dc:title>{html.escape(t)}</dc:title>'
                 f'<upnp:class>object.container.playlistContainer</upnp:class><res protocolInfo="file:*:audio/mpegurl:*">file:///jffs/settings/savedqueues.rsq#{i + 1}</res></container>'
                 for i, (t, _) in ((i, sv) for i, sv in enumerate(SAVED) if sv)]
    elif oid.startswith("SQ:"):
        _, tracks = SAVED[int(oid[3:]) - 1] or ("", [])
        items = [track_item(t, f"{oid}/{i}", oid) for i, t in enumerate(tracks)]
    total = len(items)
    page = items[start:start + count] if count else items[start:]
    return {"Result": didl("".join(page)), "NumberReturned": len(page), "TotalMatches": total, "UpdateID": 1}


def line_in_didl(uri):
    """What a Sonos reports for an analog input: the input's name as title."""
    src = players.get(uri.split(":", 1)[1])
    name = LINE_IN.get(src.room, "Line-In") if src else "Line-In"
    return didl(f'<item id="-1" parentID="-1" restricted="true"><dc:title>{html.escape(name)}</dc:title>'
                '<upnp:class>object.item.audioItem</upnp:class></item>')


def tracks_for(uri):
    """The fake tracks a queue or Sonos playlist gets for an album, artist, track or playlist URI."""
    m = re.search(r"#A:ALBUM/(\d+)", uri)
    new = [t for t in TRACKS if m and t["album"] == ALBUMS[int(m.group(1))][0]] or [t for t in TRACKS if t["uri"] == uri]
    art = re.search(r"#A:ALBUMARTIST/(.+)$", uri)
    if art:
        name = urllib.parse.unquote(art.group(1))
        new = [t for t in TRACKS if t["artist"] == name]
    sm = re.match(r"x-rincon-cpcontainer:1004206calbum%3a(\d+)", uri)
    if sm:
        new = [t for t in TRACKS if t["album"] == ALBUMS[int(sm.group(1))][0]]
    sm = re.match(r"x-rincon-cpcontainer:10052064artist%3a(\d+)", uri)
    if sm:
        new = [t for t in TRACKS if t["artist"] == ALBUMS[int(sm.group(1))][1]]
    sm = re.match(r"x-sonos-http:track%3a(\d+)", uri)
    if sm:
        new = [TRACKS[int(sm.group(1))]]
    q = re.search(r"savedqueues.rsq#(\d+)", uri)
    if q:
        new = SAVED[int(q.group(1)) - 1][1][:]
    return new


def act(p, service, action, a):
    c = coord(p)
    if action == "ListAvailableServices":
        base = f"http://{ADVERTISE}:{ARGS.port_base}/smapi"
        rows = (f'<Service Id="246" Name="bonob" Version="1.1" Uri="{base}" SecureUri="{base}" ContainerType="MService" Capabilities="2625">'
                f'<Policy Auth="AppLink" PollInterval="2"/><Presentation><PresentationMap Version="1" Uri="{base}/presentationMap.xml"/></Presentation></Service>'
                '<Service Id="9" Name="Spotify" Version="1.1" Uri="https://spotify.invalid/smapi" SecureUri="https://spotify.invalid/smapi" ContainerType="MService" Capabilities="1">'
                '<Policy Auth="AppLink" PollInterval="60"/></Service>'
                f'<Service Id="254" Name="TuneIn" Version="1.1" Uri="{base}/tunein" ContainerType="MService" Capabilities="0">'
                '<Policy Auth="Anonymous" PollInterval="0"/></Service>'
                '<Service Id="2" Name="Deezer" Version="1.1" Uri="https://deezer.invalid/smapi" SecureUri="https://deezer.invalid/smapi" ContainerType="MService" Capabilities="1">'
                '<Policy Auth="DeviceLink" PollInterval="30"/></Service>'
                '<Service Id="1559" Name="Tunebob" Version="1.1" Uri="https://tunebob.invalid/smapi" ContainerType="MService" Capabilities="1">'
                '<Policy Auth="AppLink" PollInterval="30"/></Service>')
        return {"AvailableServiceDescriptorList": f"<Services>{rows}</Services>", "AvailableServiceTypeList": "62983,2311", "AvailableServiceListVersion": "RINCON_FAKE:1"}
    if action == "GetHouseholdID":
        return {"CurrentHouseholdID": "Sonos_FakeHouseholdForTests0001"}
    if action == "GetZoneInfo":
        return {"SerialNumber": f"00-0E-58-FA-KE-0{p.port % 10}:A", "SoftwareVersion": "85.0-66270"}
    if action == "GetZoneGroupState":
        return {"ZoneGroupState": zone_group_state()}
    if action == "GetTransportInfo":
        if c.sleep_until and time.time() > c.sleep_until:
            c.position, c.started, c.state, c.sleep_until = c.pos(), None, "PAUSED_PLAYBACK", None
        c.pos()
        return {"CurrentTransportState": c.state, "CurrentTransportStatus": "OK", "CurrentSpeed": "1"}
    if action == "GetPositionInfo":
        if p is not c:
            return {"Track": 0, "TrackDuration": "0:00:00", "TrackMetaData": "", "TrackURI": f"x-rincon:{c.uuid}", "RelTime": "0:00:00"}
        if c.media_uri.startswith("x-rincon-stream:"):
            md = line_in_didl(c.media_uri)
            return {"Track": 1, "TrackDuration": "0:00:00", "TrackMetaData": md, "TrackURI": c.media_uri, "RelTime": "0:00:00"}
        if c.radio:
            md = didl(f'<item id="-1" parentID="-1" restricted="true"><res protocolInfo="x-rincon-mp3radio:*:*:*">{html.escape(c.radio[1])}</res>'
                      f'<r:streamContent>Penny Guin - Uptime</r:streamContent><upnp:albumArtURI>/getaa?u=radio{RADIO.index(c.radio)}</upnp:albumArtURI>'
                      '<dc:title>x-rincon-mp3radio</dc:title><upnp:class>object.item</upnp:class></item>')
            return {"Track": 1, "TrackDuration": "0:00:00", "TrackMetaData": md, "TrackURI": c.radio[1], "RelTime": "0:00:00"}
        pos, t = c.pos(), c.current()
        if not t:
            return {"Track": 0, "TrackDuration": "0:00:00", "TrackMetaData": "", "TrackURI": "", "RelTime": "0:00:00"}
        return {"Track": c.track, "TrackDuration": hms(t["dur"]), "TrackMetaData": didl(track_item(t, "-1", "-1")),
                "TrackURI": t["uri"], "RelTime": hms(pos)}
    if action == "GetMediaInfo":
        if p is not c:
            return {"NrTracks": 0, "CurrentURI": f"x-rincon:{c.uuid}", "CurrentURIMetaData": ""}
        md = line_in_didl(c.media_uri) if c.media_uri.startswith("x-rincon-stream:") else ""
        if c.radio:
            md = didl(f'<item id="R:0/0/0" parentID="R:0/0" restricted="true"><dc:title>{c.radio[0]}</dc:title>'
                      '<upnp:class>object.item.audioItem.audioBroadcast</upnp:class></item>')
        return {"NrTracks": 1 if c.radio or md else len(c.queue), "CurrentURI": c.media_uri, "CurrentURIMetaData": md}
    if action == "GetTransportSettings":
        return {"PlayMode": c.playmode, "RecQualityMode": "NOT_IMPLEMENTED"}
    if action == "SetPlayMode":
        c.playmode = a["NewPlayMode"]; return {}
    if action == "GetCurrentTransportActions":
        acts = "Set, Stop, Pause, Play" if c.radio else "Set, Stop, Pause, Play, X_DLNA_SeekTime, Next, Previous, X_DLNA_SeekTrackNr"
        return {"Actions": acts}
    if action == "Play":
        if c.radio or c.queue:
            c.state, c.started = "PLAYING", time.time()
        return {}
    if action in ("Pause", "Stop"):
        c.position, c.started = c.pos(), None
        c.state = "PAUSED_PLAYBACK" if action == "Pause" else "STOPPED"
        return {}
    if action in ("Next", "Previous"):
        if c.radio:
            raise Fault(701)
        c.track = max(1, min(len(c.queue), c.track + (1 if action == "Next" else -1)))
        c.position, c.started = 0.0, time.time() if c.state == "PLAYING" else None
        return {}
    if action == "Seek":
        if a["Unit"] == "TRACK_NR":
            c.track, c.position = int(a["Target"]), 0.0
        else:
            c.position = secs(a["Target"])
        c.started = time.time() if c.state == "PLAYING" else None
        return {}
    if action == "SetAVTransportURI":
        uri = a["CurrentURI"]
        if uri.startswith("x-rincon:"):
            p.coordinator, p.state, p.started = uri[9:], "STOPPED", None
            return {}
        p.coordinator = p.uuid
        p.media_uri = uri
        p.radio = next((r for r in RADIO if r[1] == uri), None)
        if uri.startswith("x-rincon-queue:"):
            p.radio = None
        p.position, p.started, p.state = 0.0, None, "STOPPED"
        return {}
    if action == "DelegateGroupCoordinationTo":
        # The group and its music move to NewCoordinator; with RejoinGroup 0 this player leaves.
        m = next((x for x in players.values() if x.uuid == a.get("NewCoordinator") and x.coordinator == p.uuid and x is not p), None)
        if m is None:
            raise Fault(800)
        for n in players.values():
            if n.coordinator == p.uuid and n is not p:
                n.coordinator = m.uuid
        m.queue, m.media_uri, m.radio, m.track = p.queue[:], p.media_uri, p.radio, p.track
        m.position, m.started, m.state = p.pos(), (time.time() if p.started else None), p.state
        if a.get("RejoinGroup") == "1":
            p.coordinator = m.uuid
        else:
            p.coordinator = p.uuid
        p.state, p.started = "STOPPED", None
        return {}
    if action == "BecomeCoordinatorOfStandaloneGroup":
        for m in players.values():
            if m.coordinator == p.uuid and m is not p:  # hand the group to the next member
                for n in players.values():
                    if n.coordinator == p.uuid and n is not p and n is not m:
                        n.coordinator = m.uuid
                m.coordinator = m.uuid
                m.queue, m.media_uri, m.radio, m.track = p.queue[:], p.media_uri, p.radio, p.track
                m.position, m.started, m.state = p.pos(), p.started and time.time(), p.state
                if m.started: m.started = time.time()
                break
        p.coordinator, p.state, p.started = p.uuid, "STOPPED", None
        return {}
    if action == "AddURIToQueue":
        new = tracks_for(a["EnqueuedURI"])
        first = len(c.queue) + 1
        if a.get("EnqueueAsNext") == "1" and c.queue:
            first = c.track + 1
        c.queue[first - 1:first - 1] = new
        return {"FirstTrackNumberEnqueued": first, "NumTracksAdded": len(new), "NewQueueLength": len(c.queue)}
    if action == "GetCrossfadeMode": return {"CrossfadeMode": int(getattr(c, "crossfade", False))}
    if action == "SetCrossfadeMode": c.crossfade = a["CrossfadeMode"] == "1"; return {}
    if action == "GetAudioInputAttributes":
        if p.room not in LINE_IN: raise Fault(401)
        return {"CurrentName": LINE_IN[p.room], "CurrentIcon": "AudioComponent"}
    if action == "CreateObject" and a["ContainerID"] == "FV:2":
        oid = f"FV:2/{100 + len(FAVS_EXTRA)}"
        FAVS_EXTRA.append(re.sub(r'<item id="[^"]*" parentID="[^"]*"', f'<item id="{oid}" parentID="FV:2"', re.search(r"<item .*</item>", a["Elements"], re.S).group(0)))
        VERSIONS["FavoritesUpdateID"] += 1
        return {"ObjectID": oid, "Result": ""}
    if action == "DestroyObject":
        oid = a["ObjectID"]
        if oid.startswith("FV:2/"):
            FAVS_REMOVED.add(oid); VERSIONS["FavoritesUpdateID"] += 1
        elif oid.startswith("SQ:"):
            SAVED[int(oid[3:]) - 1] = None; VERSIONS["SavedQueuesUpdateID"] += 1
        else:
            raise Fault(701)
        return {}
    if action == "UpdateObject" and a["ObjectID"].startswith("SQ:"):
        i = int(a["ObjectID"][3:]) - 1
        title = html.unescape(re.search(r"<dc:title>(.*)</dc:title>", a["NewTagValue"]).group(1))
        SAVED[i] = (title, SAVED[i][1]); VERSIONS["SavedQueuesUpdateID"] += 1
        return {}
    if action == "CreateSavedQueue":
        SAVED.append((a["Title"], [])); VERSIONS["SavedQueuesUpdateID"] += 1
        return {"AssignedObjectID": f"SQ:{len(SAVED)}", "NumTracksAdded": 0, "NewQueueLength": 0, "NewUpdateID": 1}
    if action == "AddURIToSavedQueue":
        i = int(a["ObjectID"][3:]) - 1
        SAVED[i][1].extend(tracks_for(a["EnqueuedURI"])); VERSIONS["SavedQueuesUpdateID"] += 1
        return {"NumTracksAdded": 1, "NewQueueLength": len(SAVED[i][1]), "NewUpdateID": 1}
    if action == "ReorderTracksInSavedQueue":
        i = int(a["ObjectID"][3:]) - 1
        if a["NewPositionList"] == "":
            for k in sorted((int(x) for x in a["TrackList"].split(",")), reverse=True):
                del SAVED[i][1][k]
        VERSIONS["SavedQueuesUpdateID"] += 1
        return {"QueueLengthChange": 0, "NewQueueLength": len(SAVED[i][1]), "NewUpdateID": 1}
    if action == "RemoveAllTracksFromQueue":
        c.queue, c.track = [], 1; return {}
    if action == "RemoveTrackFromQueue":
        i = int(a["ObjectID"].split("/")[-1]); del c.queue[i - 1]
        if i < c.track:  # the playing track keeps playing, one place earlier
            c.track -= 1
        return {}
    if action == "ReorderTracksInQueue":
        s, n, b = int(a["StartingIndex"]), int(a["NumberOfTracks"]), int(a["InsertBefore"])
        playing = c.current()
        moved = c.queue[s - 1:s - 1 + n]; del c.queue[s - 1:s - 1 + n]
        if b > s: b -= n
        c.queue[b - 1:b - 1] = moved
        if playing in c.queue:  # like Sonos: the playing track stays the playing track
            c.track = next(i for i, t in enumerate(c.queue) if t is playing) + 1
        return {}
    if action == "SaveQueue":
        SAVED.append((a["Title"], c.queue[:]))
        VERSIONS["SavedQueuesUpdateID"] += 1
        return {"AssignedObjectID": f"SQ:{len(SAVED)}"}
    if action == "ConfigureSleepTimer":
        d = a.get("NewSleepTimerDuration", "")
        c.sleep_until = time.time() + secs(d) if d else None; return {}
    if action == "GetRemainingSleepTimerDuration":
        return {"RemainingSleepTimerDuration": hms(c.sleep_until - time.time()) if c.sleep_until else "", "CurrentSleepTimerGeneration": 1}
    if action == "GetVolume": return {"CurrentVolume": p.volume}
    if action == "SetVolume": p.volume = int(a["DesiredVolume"]); return {}
    if action == "GetMute": return {"CurrentMute": int(p.mute)}
    if action == "SetMute": p.mute = a["DesiredMute"] == "1"; return {}
    if action == "GetBass": return {"CurrentBass": p.bass}
    if action == "SetBass": p.bass = int(a["DesiredBass"]); return {}
    if action == "GetTreble": return {"CurrentTreble": p.treble}
    if action == "SetTreble": p.treble = int(a["DesiredTreble"]); return {}
    if action == "GetLoudness": return {"CurrentLoudness": int(p.loudness)}
    if action == "SetLoudness": p.loudness = a["DesiredLoudness"] == "1"; return {}
    members = [m for m in players.values() if m.coordinator == c.uuid]
    if action == "GetGroupVolume": return {"CurrentVolume": round(sum(m.volume for m in members) / len(members))}
    if action == "SnapshotGroupVolume": c.snapshot = [(m, m.volume) for m in members]; return {}
    if action == "SetGroupVolume":
        target = int(a["DesiredVolume"]); snap = getattr(c, "snapshot", [(m, m.volume) for m in members])
        avg = sum(v for _, v in snap) / len(snap) or 1
        for m, v in snap:
            m.volume = max(0, min(100, round(v * target / avg) if avg else target))
        return {}
    if action == "Browse":
        return browse(p, a["ObjectID"], int(a.get("StartingIndex", 0)), int(a.get("RequestedCount", 0)))
    if action == "ListAlarms":
        rows = "".join(
            "<Alarm " + " ".join(f'{k}="{html.escape(str(v))}"' for k, v in [("ID", i)] + list(al.items())) + "/>"
            for i, al in ALARMS.items())
        return {"CurrentAlarmList": f"<Alarms>{rows}</Alarms>", "CurrentAlarmListVersion": f"RINCON_FAKE:{len(ALARMS)}"}
    if action in ("CreateAlarm", "UpdateAlarm"):
        al = {"StartTime": a["StartLocalTime"]}
        for k in ("Duration", "Recurrence", "Enabled", "RoomUUID", "ProgramURI", "ProgramMetaData", "PlayMode", "Volume", "IncludeLinkedZones"):
            al[k] = a.get(k, "")
        if action == "CreateAlarm":
            new_id = str(max([int(i) for i in ALARMS] + [0]) + 1)
            ALARMS[new_id] = al
            return {"AssignedID": new_id}
        if a["ID"] not in ALARMS:
            raise Fault(801)
        ALARMS[a["ID"]] = al
        return {}
    if action == "DestroyAlarm":
        if ALARMS.pop(a["ID"], None) is None:
            raise Fault(801)
        return {}
    raise Fault(401)


SUBSCRIPTIONS = {}  # sid -> (callback url, port, event path)
VERSIONS = {"ShareListUpdateID": 1, "FavoritesUpdateID": 1, "SavedQueuesUpdateID": 1, "RadioFavoritesUpdateID": 1}


def event_body(path):
    """ContentDirectory events carry the list versions; the rest are plain triggers."""
    if "ContentDirectory" in path:
        props = "".join(f"<e:property><{k}>RINCON_FAKE000000101400,{v}</{k}></e:property>" for k, v in VERSIONS.items())
        return f'<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">{props}</e:propertyset>'.encode()
    return ('<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property>'
            '<LastChange>&lt;Event/&gt;</LastChange></e:property></e:propertyset>').encode()


def notify_all():
    """Sends a NOTIFY to every subscriber; the app only uses them as triggers."""
    subs = list(SUBSCRIPTIONS.items())

    def send():
        for sid, (url, port, path) in subs:
            body = event_body(path)
            m = re.match(r"http://([^/:]+):(\d+)(/.*)?", url)
            if not m:
                continue
            try:
                import http.client
                c = http.client.HTTPConnection(m.group(1), int(m.group(2)), timeout=2)
                c.request("NOTIFY", m.group(3) or "/", body, {"CONTENT-TYPE": 'text/xml; charset="utf-8"', "NT": "upnp:event",
                                                             "NTS": "upnp:propchange", "SID": sid, "SEQ": "0"})
                c.getresponse().read()
                c.close()
            except OSError as e:
                if ARGS.verbose:
                    print("notify failed", url, e)
    threading.Thread(target=send, daemon=True).start()


class Fault(Exception):
    def __init__(self, code): self.code = code


COVERS = {}


FONT_DIRS = ["/usr/share/fonts/noto/", "/usr/share/fonts/TTF/", "/usr/share/fonts/truetype/noto/"]


def font(name, size):
    """A system font if there is one, else Pillow's built-in font."""
    for d in FONT_DIRS:
        try:
            return ImageFont.truetype(d + name, size)
        except OSError:
            pass
    return ImageFont.load_default(size)


def rgb(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def mix(a, b, f):
    return tuple(int(a[i] + (b[i] - a[i]) * f) for i in range(3))


def gradient(d, size, cols, top=0, bottom=None):
    bottom = size if bottom is None else bottom
    for y in range(top, bottom):
        f = (y - top) / max(1, bottom - top - 1) * (len(cols) - 1)
        seg = min(int(f), len(cols) - 2)
        d.line([(0, y), (size, y)], fill=mix(cols[seg], cols[seg + 1], f - seg))


def caption(d, size, title, artist, color, title_font, y=None, align="left"):
    """Title and artist at the bottom (or at y), like a printed sleeve."""
    tf, af = font(title_font, 46), font("NotoSans-Medium.ttf", 24)
    for size_ in range(46, 24, -2):  # shrink long titles to fit the sleeve
        tf = font(title_font, size_)
        if d.textlength(title, font=tf) <= size - 80:
            break
    y = size - 110 if y is None else y
    x = 40
    if align == "center":
        x = (size - d.textlength(title, font=tf)) / 2
    d.text((x, y), title, font=tf, fill=color)
    ax = 40 if align == "left" else (size - d.textlength(artist.upper(), font=af)) / 2
    d.text((ax, y + 58), artist.upper(), font=af, fill=color)


def glow(img, draw_fn, radius=18, strength=1.0):
    """Draws a shape twice: blurred underneath for a neon glow, sharp on top."""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    blurred = layer.filter(ImageFilter.GaussianBlur(radius))
    for _ in range(int(strength * 2)):
        img.alpha_composite(blurred)
    img.alpha_composite(layer)


TUX_CACHE = {}


def tux_sprite(w, body=(22, 22, 30)):
    """A Tux-like penguin, drawn four times larger and scaled down for smooth edges."""
    key = (w, body)
    if key in TUX_CACHE:
        return TUX_CACHE[key]
    k = 4 * w / 200  # design box 200 x 240
    big = Image.new("RGBA", (int(200 * k), int(240 * k)), (0, 0, 0, 0))
    g = ImageDraw.Draw(big)
    E = lambda box, fill: g.ellipse([v * k for v in box], fill=fill)
    for side, box in ((-1, (8, 95, 62, 200)), (1, (138, 95, 192, 200))):  # flippers
        fl = Image.new("RGBA", big.size, (0, 0, 0, 0))
        ImageDraw.Draw(fl).ellipse([v * k for v in box], fill=body)
        cx, cy = (box[0] + box[2]) / 2 * k, box[1] * k
        big.alpha_composite(fl.rotate(side * -22, center=(cx, cy), resample=Image.BICUBIC))
    belly, beak, beak2 = (246, 242, 232), (255, 176, 30), (226, 128, 10)
    E((48, 205, 102, 236), beak2); E((98, 205, 152, 236), beak2)  # feet
    E((34, 62, 166, 226), body)  # body
    E((52, 8, 148, 104), body)  # head
    E((56, 92, 144, 222), belly)
    E((66, 34, 102, 84), belly); E((98, 34, 134, 84), belly); E((64, 58, 136, 112), belly)  # face
    for ex in (80, 104):
        E((ex, 50, ex + 15, 68), (20, 20, 26)); E((ex + 3, 53, ex + 8, 59), (255, 255, 255))
    E((80, 68, 120, 86), beak); E((86, 79, 114, 94), beak2)
    sprite = big.resize((w, int(240 * w / 200)), Image.LANCZOS)
    TUX_CACHE[key] = sprite
    return sprite


def penguin(img, x, y, s, body=(22, 22, 30)):
    """A penguin about 70*s px wide with its top-left corner at x, y, with a soft shadow."""
    sp = tux_sprite(int(70 * s), body)
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([x + 6 * s, y + sp.height - 8 * s, x + sp.width - 6 * s, y + sp.height + 6 * s], fill=(0, 0, 0, 90))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(4)))
    img.alpha_composite(sp, (int(x), int(y)))


def cover_sunset(img, d, n, c, title, artist):
    gradient(d, n, [c[2], c[1], c[0]], 0, int(n * .6))
    d.rectangle([0, int(n * .6), n, n], fill=(20, 4, 40))
    cx, cy, r = n // 2, int(n * .48), 140
    def sun(g):
        for y in range(cy - r, cy + r):
            f = (y - cy + r) / (2 * r)
            half = int((r * r - (y - cy) ** 2) ** .5)
            g.line([(cx - half, y), (cx + half, y)], fill=mix((255, 236, 110), (255, 60, 140), f) + (255,))
        for i, y in enumerate(range(cy + 10, cy + r, 20)):
            g.rectangle([0, y, n, y + 3 + i * 2], fill=(0, 0, 0, 0))
    glow(img, sun, 30)
    def grid(g):
        hz = int(n * .6)
        for i in range(-12, 13):
            g.line([(cx, hz), (cx + i * 80, n)], fill=(255, 61, 200, 255), width=2)
        for k in range(1, 9):
            y = hz + int((k / 8) ** 2 * (n - hz))
            g.line([(0, y), (n, y)], fill=(255, 61, 200, 255), width=2)
    glow(img, grid, 6)
    d = ImageDraw.Draw(img)
    penguin(img, 440, 300, .9, body=(10, 2, 24))
    for size_ in range(56, 30, -2):
        tf = font("NotoSerif-BoldItalic.ttf", size_)
        if d.textlength(title, font=tf) <= n - 60:
            break
    def title_(g):
        w = g.textlength(title, font=tf)
        g.text(((n - w) / 2, 470), title, font=tf, fill=(120, 255, 240, 255))
    glow(img, title_, 10)
    d = ImageDraw.Draw(img)
    af = font("NotoSans-Bold.ttf", 22)
    w = d.textlength(artist.upper(), font=af)
    d.text(((n - w) / 2, 545), artist.upper(), font=af, fill=(255, 220, 240))


def cover_waves(img, d, n, c, title, artist):
    import math
    gradient(d, n, [c[2], mix(c[2], c[1], .5)])
    def waves(g):
        for k in range(16):
            y0 = 200 + k * 20
            col = mix(c[0], (255, 80, 200), k / 15) + (255,)
            pts = [(x, y0 + 34 * math.sin(x / 65 + k * .4) * (1 - k / 40)) for x in range(-10, n + 10, 8)]
            g.line(pts, fill=col, width=3)
    glow(img, waves, 12)
    d = ImageDraw.Draw(img)
    d.text((38, 40), title.upper(), font=font("NotoSans-Black.ttf", 58), fill=(255, 255, 255))
    d.text((42, 110), artist.upper(), font=font("NotoSans-Bold.ttf", 22), fill=c[0])
    d.text((42, n - 52), "STEREO  ·  TUX RECORDS", font=font("NotoSans-Bold.ttf", 16), fill=(180, 200, 255))


def cover_boxes(img, d, n, c, title, artist):
    gradient(d, n, [c[2], (30, 20, 70)])
    pal = [(255, 210, 63), (59, 206, 172), (255, 90, 120), (90, 140, 255), (255, 150, 60), (190, 110, 255)]
    import random
    rnd = random.Random(3)
    for row in range(5):
        for col in range(5):
            x, y = 40 + col * 106, 40 + row * 78
            fill = pal[rnd.randrange(len(pal))]
            d.rounded_rectangle([x + 4, y + 6, x + 96, y + 70], 10, fill=(0, 0, 0))  # shadow
            d.rounded_rectangle([x, y, x + 92, y + 64], 10, fill=fill)
            d.rectangle([x + 40, y, x + 52, y + 64], fill=mix(fill, (255, 255, 255), .45))  # tape
    penguin(img, 255, 300, 1.0)
    d.text((40, n - 120), title, font=font("NotoSans-Black.ttf", 50), fill=(255, 255, 255))
    d.text((42, n - 60), artist.upper(), font=font("NotoSans-Bold.ttf", 22), fill=(255, 210, 63))


def cover_hat(img, d, n, c, title, artist):
    d.rectangle([0, 0, n, n], fill=c[0])
    for yy in range(0, n, 22):  # pop-art halftone
        for xx in range(0, n, 22):
            r = 2 + 8 * ((xx + yy) / (2 * n))
            d.ellipse([xx - r, yy - r, xx + r, yy + r], fill=c[1])
    cx = n // 2
    penguin(img, cx - 75, 250, 2.5)
    d.ellipse([cx - 160, 250, cx + 160, 300], fill=(25, 12, 40))
    d.rounded_rectangle([cx - 90, 140, cx + 90, 280], 40, fill=(25, 12, 40))
    d.rectangle([cx - 90, 240, cx + 90, 262], fill=c[0])
    tf = font("NotoSans-Black.ttf", 64)
    for off, col in ((6, (25, 12, 40)), (0, (255, 255, 255))):
        d.text((40 + off, 40 + off), title.upper(), font=tf, fill=col)
    d.text((44, 118), artist.upper(), font=font("NotoSans-Bold.ttf", 22), fill=(25, 12, 40))


def cover_penguins(img, d, n, c, title, artist):
    gradient(d, n, [c[2], c[1], c[0]], 0, int(n * .66))
    d.ellipse([200, 250, 400, 450], fill=(255, 236, 170))
    d.rectangle([0, int(n * .66), n, n], fill=(70, 150, 220))
    for i in range(6):  # sun reflection
        d.rectangle([260 - i * 6, 400 + i * 16, 340 + i * 6, 404 + i * 16], fill=(255, 220, 160))
    d.polygon([(0, 420), (180, 390), (330, 420), (330, 470), (0, 480)], fill=(240, 248, 255))
    d.polygon([(330, 410), (520, 380), (n, 400), (n, 460), (330, 470)], fill=(222, 238, 252))
    for x, y, s in [(40, 318, 1.25), (140, 342, 1.0), (380, 290, 1.4), (500, 322, .95)]:
        penguin(img, x, y, s)
    d.text((40, 40), title, font=font("NotoSans-Black.ttf", 58), fill=(255, 255, 255))
    d.text((44, 108), artist.upper(), font=font("NotoSans-Bold.ttf", 22), fill=(255, 236, 170))


def cover_terminal(img, d, n, c, title, artist):
    gradient(d, n, [c[0], c[1]])
    d.rounded_rectangle([46, 76, 416, 384], 18, fill=(0, 0, 0))  # shadow
    d.rounded_rectangle([40, 66, 410, 376], 18, fill=(28, 26, 34))
    for i, col in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        d.ellipse([64 + i * 30, 86, 82 + i * 30, 104], fill=col)
    mono = font("NotoSansMono-Bold.ttf", 25)
    lines = [("$ ", "brew --coffee"), ("", "Brewing... done."), ("$ ", "crontab -e"), ("", "0 7 * * * toast"), ("$ ", "")]
    for i, (pr, txt) in enumerate(lines):
        y = 130 + i * 44
        d.text((66, y), pr, font=mono, fill=(120, 230, 120))
        d.text((66 + d.textlength(pr, font=mono), y), txt, font=mono, fill=(235, 230, 220))
    d.rectangle([98, 310, 112, 336], fill=(235, 230, 220))
    mx, my = 436, 250  # mug
    d.rounded_rectangle([mx, my, mx + 110, my + 120], 16, fill=(255, 255, 255))
    d.ellipse([mx + 95, my + 30, mx + 145, my + 85], outline=(255, 255, 255), width=12)
    penguin(img, mx + 30, my + 30, .7)
    for k in range(3):
        d.arc([mx + 20 + k * 25, my - 70, mx + 50 + k * 25, my - 10], 200, 340, fill=(255, 255, 255), width=5)
    caption(d, n, title, artist, (255, 255, 255), "NotoSansMono-Bold.ttf")


def cover_crumbs(img, d, n, c, title, artist):
    gradient(d, n, c)
    import random
    rnd = random.Random(7)
    for i in range(40):
        x, y = 60 + i * 12 + rnd.randint(-8, 8), 380 - i * 7 + rnd.randint(-10, 10)
        r = rnd.randint(4, 9)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(245, 240, 228))
    caption(d, n, title, artist, (245, 240, 228), "NotoSerif-Regular.ttf")


def finish(img):
    """Grain, vignette and a sleeve shine so the covers read as printed records."""
    n = img.width
    noise = Image.effect_noise((n, n), 28).convert("RGBA")
    noise.putalpha(22)
    img.alpha_composite(noise)
    vig = Image.new("L", (n, n), 0)
    ImageDraw.Draw(vig).ellipse([-n * .25, -n * .25, n * 1.25, n * 1.25], fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(n // 6))
    dark = Image.new("RGBA", (n, n), (0, 0, 0, 120))
    dark.putalpha(Image.eval(vig, lambda v: int((255 - v) * .5)))
    img.alpha_composite(dark)
    shine = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(shine).polygon([(0, 0), (n * .55, 0), (0, n * .55)], fill=(255, 255, 255, 18))
    img.alpha_composite(shine.filter(ImageFilter.GaussianBlur(40)))


ALBUM_STYLES = [cover_sunset, cover_waves, cover_boxes, cover_hat, cover_penguins, cover_terminal, cover_crumbs]


def radio_logo(d, n, c, name):
    gradient(d, n, c)
    d.rounded_rectangle([60, 60, n - 60, n - 60], 60, outline=(255, 255, 255), width=10)
    words = name.split()
    big = font("NotoSans-Black.ttf", 92)
    small = font("NotoSans-Bold.ttf", 44)
    w = d.textlength(words[-1].upper(), font=big)
    d.text(((n - w) / 2, 220), words[-1].upper(), font=big, fill=(255, 255, 255))
    if len(words) > 1:
        w = d.textlength(" ".join(words[:-1]).upper(), font=small)
        d.text(((n - w) / 2, 160), " ".join(words[:-1]).upper(), font=small, fill=(255, 255, 255))
    for i in range(3):  # on-air waves
        r = 30 + i * 22
        d.arc([n // 2 - r, 470 - r, n // 2 + r, 470 + r], 220, 320, fill=(255, 255, 255), width=6)


def cover(key):
    if key in COVERS:
        return COVERS[key]
    m = re.match(r"(album|radio)(\d+)", key)
    if not m or Image is None:
        return None
    n = 600
    img = Image.new("RGBA", (n, n), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    i = int(m.group(2))
    if m.group(1) == "album":
        title, artist, colors, _ = ALBUMS[i]
        ALBUM_STYLES[i % len(ALBUM_STYLES)](img, d, n, [rgb(x) for x in colors], title, artist)
    else:
        name, _, colors = RADIO[i]
        radio_logo(d, n, [rgb(x) for x in colors], name)
    finish(img)
    buf = io.BytesIO(); img.convert("RGB").save(buf, "PNG")
    COVERS[key] = buf.getvalue()
    return COVERS[key]


LYRICS_EN = [  # invented lines for English screenshots
    "I boot up slow on a Sunday morning", "the kernel hums a song for me", "every process finds its home",
    "and every packet finds the sea", "Oh, penguin, waddle through my window", "bring the light from the server room",
    "we're rolling, rolling, never stable", "but my heart is long-term support", "grep the sun and pipe it to me",
    "sudo make me a cup of tea", "the uptime counts the nights we're dancing", "nobody reboots tonight",
]


LINKS = {}  # linkCode -> confirmed
SMAPI_TOKEN = "fake-bonob-token"


def smapi_item(kind, i):
    if kind == "artist":
        return (f"<mediaCollection><id>artist:{i}</id><itemType>artist</itemType><title>{html.escape(ALBUMS[i][1])}</title>"
                "<canPlay>true</canPlay><canEnumerate>true</canEnumerate></mediaCollection>")
    if kind == "album":
        return (f"<mediaCollection><id>album:{i}</id><itemType>album</itemType><title>{html.escape(ALBUMS[i][0])}</title>"
                f"<artist>{html.escape(ALBUMS[i][1])}</artist><albumArtURI>http://{ADVERTISE}:{ARGS.port_base}/getaa?u=album{i}</albumArtURI>"
                "<canPlay>true</canPlay><canEnumerate>true</canEnumerate></mediaCollection>")
    t = TRACKS[i]
    return (f"<mediaMetadata><id>track:{i}</id><itemType>track</itemType><title>{html.escape(t['title'])}</title><mimeType>audio/mpeg</mimeType>"
            f"<trackMetadata><artist>{html.escape(t['artist'])}</artist><album>{html.escape(t['album'])}</album><duration>{t['dur']}</duration>"
            f"<albumArtURI>http://{ADVERTISE}:{ARGS.port_base}/getaa?u={t['art']}</albumArtURI></trackMetadata></mediaMetadata>")


def tunein(action, a):
    """A tiny TuneIn without sign-in: two stations at the top level."""
    if action != "getMetadata":
        raise Fault("Client.UnsupportedOperation")
    rows = [f"<mediaMetadata><id>s{1000 + i}</id><itemType>stream</itemType><title>{html.escape(name)}</title><mimeType>audio/mpeg</mimeType>"
            "<streamMetadata><logo/></streamMetadata></mediaMetadata>" for i, (name, _, _) in enumerate(RADIO)]
    return f"<getMetadataResult><index>0</index><count>{len(rows)}</count><total>{len(rows)}</total>{''.join(rows)}</getMetadataResult>"


def smapi(action, a, token):
    """A tiny bonob: AppLink, getMetadata and search over the invented albums."""
    if action == "getAppLink":
        code = uuid.uuid4().hex[:8]
        LINKS[code] = False
        return (f"<getAppLinkResult><authorizeAccount><appUrlStringId>AppLinkMessage</appUrlStringId><deviceLink>"
                f"<regUrl>http://{ADVERTISE}:{ARGS.port_base}/smapi/login?linkCode={code}</regUrl><linkCode>{code}</linkCode>"
                "<showLinkCode>false</showLinkCode></deviceLink></authorizeAccount></getAppLinkResult>")
    if action == "getDeviceAuthToken":
        if not LINKS.get(a.get("linkCode")):
            raise Fault("Client.NOT_LINKED_RETRY")
        return f"<getDeviceAuthTokenResult><authToken>{SMAPI_TOKEN}</authToken><privateKey>k</privateKey></getDeviceAuthTokenResult>"
    if token != SMAPI_TOKEN:
        raise Fault("Client.LoginUnauthorized")
    if action == "getMetadata":
        oid = a.get("id", "root")
        if oid == "root":
            rows = ["<mediaCollection><id>artists</id><itemType>container</itemType><title>Artists</title><canEnumerate>true</canEnumerate></mediaCollection>",
                    "<mediaCollection><id>albums</id><itemType>albumList</itemType><title>Albums</title><canEnumerate>true</canEnumerate></mediaCollection>"]
        elif oid == "artists":
            rows = [smapi_item("artist", i) for i in range(len(ALBUMS))]
        elif oid == "albums":
            rows = [smapi_item("album", i) for i in range(len(ALBUMS))]
        elif oid.startswith("artist:"):
            rows = [smapi_item("album", int(oid[7:]))]
        elif oid.startswith("album:"):
            name = ALBUMS[int(oid[6:])][0]
            rows = [smapi_item("track", j) for j, t in enumerate(TRACKS) if t["album"] == name]
        else:
            raise Fault("Client.ItemNotFound")
    elif action == "search":
        term = a.get("term", "").lower()
        kind = {"artists": "artist", "albums": "album", "tracks": "track"}[a["id"]]
        pool = range(len(TRACKS)) if kind == "track" else range(len(ALBUMS))
        name = (lambda i: TRACKS[i]["title"]) if kind == "track" else (lambda i: ALBUMS[i][1 if kind == "artist" else 0])
        rows = [smapi_item(kind, i) for i in pool if term in name(i).lower()]
    else:
        raise Fault("Client.UnsupportedOperation")
    start, count = int(a.get("index", 0)), int(a.get("count", 100))
    page = rows[start:start + count]
    return f"<{action}Result><index>{start}</index><count>{len(page)}</count><total>{len(rows)}</total>{''.join(page)}</{action}Result>"


PRESENTATION = ('<?xml version="1.0" encoding="utf-8"?><Presentation><PresentationMap type="Search"><Match><SearchCategories>'
                '<Category id="artists"/><Category id="albums"/><Category id="tracks"/></SearchCategories></Match></PresentationMap></Presentation>')


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if ARGS.verbose:
            super().log_message(fmt, *args)

    def player(self):
        return by_port[self.server.server_address[1]]

    def send(self, code, body, ctype="text/xml; charset=\"utf-8\""):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        p = self.player()
        if self.path.startswith("/xml/device_description.xml"):
            return self.send(200, f'<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>'
                                  f'<deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType><friendlyName>{ADVERTISE} - Sonos {p.model}</friendlyName>'
                                  f'<manufacturer>Sonos, Inc.</manufacturer><modelNumber>{p.model}</modelNumber><modelName>Sonos {p.model}</modelName>'
                                  f'<softwareVersion>85.0-66270</softwareVersion><roomName>{html.escape(p.room)}</roomName><displayName>{p.model}</displayName>'
                                  f'<UDN>uuid:{p.uuid}</UDN></device></root>')
        if self.path.startswith("/smapi/presentationMap.xml"):
            return self.send(200, PRESENTATION)
        if self.path.startswith("/smapi/login"):
            code = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("linkCode", [""])[0]
            if code in LINKS:
                LINKS[code] = True
                return self.send(200, "<html><body><h1>Linked</h1><p>Fake bonob: you can go back to the app.</p></body></html>", "text/html")
            return self.send(404, "unknown link code", "text/plain")
        if self.path.startswith(("/ws/2/", "/w/api.php", "/wiki/", "/coverart/", "/sparql")):
            return self.details()
        if self.path.startswith("/api/"):
            return self.lrclib()
        if self.path.startswith("/getaa"):
            m = re.search(r"u=([a-z]+\d+)", self.path)
            data = cover(m.group(1)) if m else None
            return self.send(200, data, "image/png") if data else self.send(404, "")
        self.send(404, "")

    def lrclib(self):
        """Fake LRCLIB with invented lines (app extra `-e details host:port`). "Grep the Sun" carries
        the times of a longer version: lines come after the song's end, so the app doubts them."""
        url = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
        def entry(t):
            short = t["title"] == "Grep the Sun"
            end = t["dur"] * (1.3 if short else 0.9)
            lines = [f"[{int(s // 60):02d}:{s % 60:05.2f}] " + (LYRICS_EN[n % len(LYRICS_EN)] if EN else f"{t['title']}, Zeile {n + 1}")
                     for n, s in enumerate(range(8, int(end), 6))]
            return dict(trackName=t["title"], artistName=t["artist"], albumName=t["album"], duration=t["dur"], instrumental=False,
                        plainLyrics="\n".join(l.split("] ", 1)[1] for l in lines), syncedLyrics="\n".join(lines))
        # Josh Woodward's real lines are on lrclib.net: nothing invented for him.
        tracks = [t for t in TRACKS if t["artist"] != "Josh Woodward" and t["title"].lower() == q.get("track_name", "").lower()
                  and t["artist"].lower() == q.get("artist_name", t["artist"]).lower()]
        if url.path == "/api/get":
            hit = next((t for t in tracks if abs(t["dur"] - int(q.get("duration", "0"))) <= 2), None)
            return self.send(200, json.dumps(entry(hit)), "application/json") if hit else self.send(404, "{}", "application/json")
        if url.path == "/api/search":
            return self.send(200, json.dumps([entry(t) for t in tracks]), "application/json")
        self.send(404, "")

    def details(self):
        """Fake MusicBrainz, Wikidata and Wikipedia for the details sheet (app extra `-e details host:port`)."""
        url = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(url.query)
        js = lambda o: self.send(200, json.dumps(o), "application/json")
        # Josh Woodward is a real artist (his CC BY track is here for LRCLIB): no invented biography for him.
        artists = sorted({a for _, a, _, _ in ALBUMS if a != "Josh Woodward"})
        def quoted(field):
            m = re.search(field + r':"((?:[^"\\]|\\.)*)"', q.get("query", [""])[0])
            return m.group(1).replace('\\"', '"').lower() if m else None
        def first_album(artist):
            return next(i for i, al in enumerate(ALBUMS) if al[1] == artist)
        path = url.path
        if path == "/ws/2/artist":
            name = quoted("artist")
            hits = [dict(id=f"fake-artist-{artists.index(a)}", name=a, type="Person" if a in ("Penny Guin", "Tina Tux", "Pac Mann") else "Group",
                         country="DE", score=100, disambiguation="invented for Hausmusik tests") for a in artists if a.lower() == name]
            return js({"artists": hits})
        if path.startswith("/ws/2/artist/"):
            a = artists[int(path.rsplit("-", 1)[1])]
            i = first_album(a)
            return js({"life-span": {"begin": f"{2008 + i}-03-01", "ended": False},
                       "genres": [{"name": GENRES[i].lower(), "count": 3}],
                       "relations": [{"type": "wikidata", "url": {"resource": f"https://www.wikidata.org/wiki/Q900{artists.index(a)}"}}]})
        if path == "/ws/2/release-group":
            title, artist = quoted("releasegroup"), quoted("artist")
            hits = [dict(id=f"fake-album-{i}", title=al[0], score=100, **{"primary-type": "Album", "first-release-date": f"{2012 + i}-05-01",
                         "artist-credit": [{"name": al[1], "joinphrase": ""}]})
                    for i, al in enumerate(ALBUMS) if al[1] in artists and al[0].lower() == title and (artist is None or al[1].lower() == artist)]
            return js({"release-groups": hits})
        if path.startswith("/ws/2/release-group/"):
            i = int(path.rsplit("-", 1)[1])
            return js({"genres": [{"name": GENRES[i].lower(), "count": 2}], "artist-credit": [{"name": ALBUMS[i][1], "joinphrase": ""}],
                       "relations": [{"type": "wikidata", "url": {"resource": f"https://www.wikidata.org/wiki/Q800{i}"}}]})
        if path == "/w/api.php":
            qid = q.get("ids", [""])[0]
            n = int(qid[4:]) if qid[:4] in ("Q900", "Q800") else -1
            title = (artists[n] if qid.startswith("Q900") else ALBUMS[n][0]) if n >= 0 else None
            return js({"entities": {qid: {"sitelinks": {"dewiki": {"title": title}, "enwiki": {"title": title}} if title else {}}}})
        if path.startswith("/wiki/"):
            _, _, lang, title = path.split("/", 3)
            title = urllib.parse.unquote(title)
            base = f"http://{ADVERTISE}:{self.server.server_address[1]}"
            if title in artists:
                i = first_album(title)
                text = (f"{title} ist ein erfundener Interpret aus dem Testhaushalt von Hausmusik. Das erste Album „{ALBUMS[i][0]}“ "
                        f"mischt {GENRES[i]} mit dem Rauschen eines Serverschranks.\nAuftritte gibt es nur im Terminal."
                        if lang == "de" else
                        f"{title} is an invented artist from the Hausmusik test household. Its first album, {ALBUMS[i][0]}, "
                        f"mixes {GENRES[i].lower()} with the hum of a server rack.\nConcerts only ever happen in a terminal.")
                image = f"{base}/getaa?u=album{i}"
            else:
                i = next((k for k, al in enumerate(ALBUMS) if al[0] == title), 0)
                text = (f"„{title}“ ist ein erfundenes Album von {ALBUMS[i][1]}, aufgenommen für die Tests von Hausmusik."
                        if lang == "de" else f"{title} is an invented album by {ALBUMS[i][1]}, recorded for the tests of Hausmusik.")
                image = None
            return js({"type": "standard", "extract": text, "content_urls": {"desktop": {"page": f"https://example.invalid/wiki/{urllib.parse.quote(title)}"}},
                       **({"thumbnail": {"source": image}} if image else {})})
        if path == "/sparql":
            # Music videos: invented songs get a placeholder ID ("Me at the zoo", public), so opening can be
            # tested; Tina Tux never has one ("not found"). Real artists get nothing.
            qs = q.get("query", [""])[0]
            hit = any(f'"{t["title"]}"@en' in qs and f'"{t["artist"]}"@en' in qs for t in TRACKS if t["artist"] in artists and t["artist"] != "Tina Tux")
            return js({"results": {"bindings": [{"yt": {"type": "literal", "value": "jNQXAC9IVRw"}}] if hit else []}})
        if path.startswith("/coverart/fake-album-"):
            data = cover(f"album{int(path.rsplit('-', 1)[1])}")
            return self.send(200, data, "image/png") if data else self.send(404, "")
        self.send(404, "")

    def do_SUBSCRIBE(self):
        sid = self.headers.get("SID")
        cb = re.search(r"<([^>]+)>", self.headers.get("CALLBACK", ""))
        if not sid:
            sid = f"uuid:{uuid.uuid4()}"
            if cb:
                SUBSCRIPTIONS[sid] = (cb.group(1), self.server.server_address[1], self.path)
                notify_all()  # Sonos sends the initial state right away
        elif sid not in SUBSCRIPTIONS:
            return self.send(412, "")
        self.send_response(200)
        self.send_header("SID", sid)
        self.send_header("TIMEOUT", "Second-3600")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_UNSUBSCRIBE(self):
        SUBSCRIPTIONS.pop(self.headers.get("SID"), None)
        self.send(200, "")

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        if self.path.startswith("/smapi"):
            action = self.headers.get("SOAPAction", "").strip('"').split("#")[-1]
            inner = re.search(rf"<ns:{action}[^>]*>(.*)</ns:{action}>", body, re.S)
            args = {k: html.unescape(v) for k, v in re.findall(r"<ns:(\w+)>(.*?)</ns:\1>", inner.group(1) if inner else "", re.S)}
            token = re.search(r"<token>(.*?)</token>", body)
            env = '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>{}</soap:Body></soap:Envelope>'
            try:
                with lock:
                    if self.path.startswith("/smapi/tunein"):
                        out = tunein(action, args)
                    else:
                        out = smapi(action, args, html.unescape(token.group(1)) if token else None)
            except Fault as f:
                return self.send(500, env.format(f"<soap:Fault><faultcode>soap:{f.code}</faultcode><faultstring>{f.code}</faultstring></soap:Fault>"))
            return self.send(200, env.format(f'<{action}Response xmlns="http://www.sonos.com/Services/1.1">{out}</{action}Response>'))
        m = re.search(r'service:(\w+):1#(\w+)', self.headers.get("SOAPACTION", ""))
        if not m:
            return self.send(400, "")
        service, action = m.groups()
        inner = re.search(rf"<u:{action}[^>]*>(.*)</u:{action}>", body, re.S)
        args = {k: html.unescape(v) for k, v in re.findall(r"<(\w+)>(.*?)</\1>", inner.group(1) if inner else "", re.S)}
        if ARGS.slow and not re.match(r"(Get|Browse|List)", action):
            time.sleep(ARGS.slow)
        try:
            with lock:
                out = act(self.player(), service, action, args)
        except Fault as f:
            return self.send(500, '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>'
                                  '<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>'
                                  f'<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>{f.code}</errorCode></UPnPError>'
                                  '</detail></s:Fault></s:Body></s:Envelope>')
        if not re.match(r"(Get|Browse|List)", action):
            notify_all()
        fields = "".join(f"<{k}>{html.escape(str(v), quote=False)}</{k}>" for k, v in out.items())
        self.send(200, '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
                       f'<s:Body><u:{action}Response xmlns:u="urn:schemas-upnp-org:service:{service}:1">{fields}</u:{action}Response></s:Body></s:Envelope>')


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--advertise", default="127.0.0.1", help="host the app uses to reach this machine (emulator: 10.0.2.2)")
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--port-base", type=int, default=1401, help="port of the first room; the others follow")
    ap.add_argument("--slow", type=float, default=0, help="seconds every command waits, like a busy S1 (Get/Browse stay fast)")
    ap.add_argument("--english", action="store_true", help="English names for screenshots")
    ap.add_argument("-v", "--verbose", action="store_true")
    ARGS = ap.parse_args()
    ADVERTISE = ARGS.advertise
    for i, p in enumerate(sorted(players.values(), key=lambda p: p.port)):
        p.port = ARGS.port_base + i
    by_port.clear()
    by_port.update({p.port: p for p in players.values()})
    servers = [ThreadingHTTPServer((ARGS.bind, port), Handler) for port in by_port]
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print("Fake Sonos household:", ", ".join(f"{p.room} :{p.port}" for p in by_port.values()), flush=True)
    servers[0].serve_forever()
