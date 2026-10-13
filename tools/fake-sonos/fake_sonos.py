#!/usr/bin/env python3
"""A fake Sonos household for the emulator, tests and screenshots.

Four invented players listen on ports 1401-1404. They answer the SOAP actions
Hausmusik uses, keep state (playback, volume, groups, queue) and serve
generated album covers. Nothing here talks to real speakers.

    tools/fake-sonos/fake_sonos.py --advertise 10.0.2.2   # for the emulator

Then point the app at 10.0.2.2:1401 (debug settings).
"""
import argparse, html, io, re, threading, time, urllib.parse, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from PIL import Image, ImageDraw
except ImportError:  # covers are optional
    Image = None

ROOMS = [  # port, uuid, room, model, icon
    (1401, "RINCON_FAKE000000101400", "Wohnzimmer", "Five", "living"),
    (1402, "RINCON_FAKE000000201400", "Küche", "Era 100", "kitchen"),
    (1403, "RINCON_FAKE000000301400", "Arbeitszimmer", "Play:3", "office"),
    (1404, "RINCON_FAKE000000401400", "Bad", "Play:1", "bathroom"),
]

ALBUMS = [  # invented artists and albums
    ("Kernel Panic Ballads", "Penny Guin", ["#e2a84b", "#b2462e", "#3a1f2b"],
     ["Border of the Stack", "Segfault Serenade", "Uptime", "Swap on Me", "Init 0"]),
    ("Rolling Release", "Arch Ibald Trio", ["#5fa3a8", "#1d4e6b", "#0f2233"],
     ["Pacman Syncs", "Bleeding Edge", "AUR Lullaby", "Rebase Blues"]),
    ("Long Term Support", "Debby Ann & The Packages", ["#a6c27a", "#4b7a3d", "#1c2e1a"],
     ["Stable", "Backports", "Five Years", "Point Release"]),
    ("Hutmacher", "Fedora Hutmacher", ["#c58fd6", "#6a3d8f", "#24143a"],
     ["Red Hat Rag", "Rawhide", "Silverblue", "Spin"]),
    ("Pinguinzoo", "Tina Tux", ["#f2d06b", "#e07a3a", "#7c2a2a"],
     ["Ice Floe", "Waddle", "Fish Supper", "Colony", "Snow Crash"]),
    ("Frühstück im Terminal", "Pac Mann", ["#d9d2c3", "#8f8a7c", "#3f3b35"],
     ["Kaffee und Cron", "Bashrc", "Grep the Sun"]),
    # Real and CC BY 4.0 (joshwoodward.com), so lyrics can be tested against LRCLIB.
    ("Breadcrumbs", "Josh Woodward", ["#c9d6e8", "#5d7391", "#1f2a3a"],
     ["Border Blaster"]),
]
GENRES = ["Rock", "Folk", "Folk", "Rock", "Pop", "Jazz", "Folk"]  # per album
RADIO = [("Radio Tuxfunk", "x-rincon-mp3radio://radio.example/tuxfunk", ["#4f7c93", "#1f3a4a"]),
         ("Kernel FM", "x-rincon-mp3radio://radio.example/kernelfm", ["#9c7b2c", "#3a2c10"])]

ALARMS = {"1": {"StartTime": "06:45:00", "Duration": "01:00:00", "Recurrence": "WEEKDAYS", "Enabled": "1",
               "RoomUUID": "RINCON_FAKE000000301400", "ProgramURI": "x-rincon-mp3radio://radio.example/kernelfm",
               "ProgramMetaData": "", "PlayMode": "NORMAL", "Volume": "18", "IncludeLinkedZones": "0"},
          "2": {"StartTime": "09:30:00", "Duration": "00:30:00", "Recurrence": "WEEKENDS", "Enabled": "0",
               "RoomUUID": "RINCON_FAKE000000101400", "ProgramURI": "x-rincon-buzzer:0",
               "ProgramMetaData": "", "PlayMode": "NORMAL", "Volume": "20", "IncludeLinkedZones": "1"}}
SAVED = [("Morgens", [])]  # Sonos playlists: (title, tracks)
TRACKS = []
for ai, (album, artist, colors, titles) in enumerate(ALBUMS):
    for ti, t in enumerate(titles):
        TRACKS.append(dict(title=t, artist=artist, album=album, art=f"album{ai}",
                           dur=280 if t == "Border Blaster" else 150 + (ai * 37 + ti * 23) % 120,
                           uri=f"x-file-cifs://nas/music/{ai}/{ti}.mp3", nr=ti + 1))

lock = threading.Lock()
SAVED[0] = ("Morgens", TRACKS[14:20])
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
                 for i, (t, _) in enumerate(SAVED)]
    elif oid.startswith("SQ:"):
        _, tracks = SAVED[int(oid[3:]) - 1]
        items = [track_item(t, f"{oid}/{i}", oid) for i, t in enumerate(tracks)]
    total = len(items)
    page = items[start:start + count] if count else items[start:]
    return {"Result": didl("".join(page)), "NumberReturned": len(page), "TotalMatches": total, "UpdateID": 1}


def act(p, service, action, a):
    c = coord(p)
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
        md = ""
        if c.radio:
            md = didl(f'<item id="R:0/0/0" parentID="R:0/0" restricted="true"><dc:title>{c.radio[0]}</dc:title>'
                      '<upnp:class>object.item.audioItem.audioBroadcast</upnp:class></item>')
        return {"NrTracks": 1 if c.radio else len(c.queue), "CurrentURI": c.media_uri, "CurrentURIMetaData": md}
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
        uri = a["EnqueuedURI"]
        m = re.search(r"#A:ALBUM/(\d+)", uri)
        new = [t for t in TRACKS if m and t["album"] == ALBUMS[int(m.group(1))][0]] or [t for t in TRACKS if t["uri"] == uri]
        art = re.search(r"#A:ALBUMARTIST/(.+)$", uri)
        if art:
            name = urllib.parse.unquote(art.group(1))
            new = [t for t in TRACKS if t["artist"] == name]
        q = re.search(r"savedqueues.rsq#(\d+)", uri)
        if q:
            new = SAVED[int(q.group(1)) - 1][1][:]
        first = len(c.queue) + 1
        if a.get("EnqueueAsNext") == "1" and c.queue:
            first = c.track + 1
        c.queue[first - 1:first - 1] = new
        return {"FirstTrackNumberEnqueued": first, "NumTracksAdded": len(new), "NewQueueLength": len(c.queue)}
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


def notify_all():
    """Sends a NOTIFY to every subscriber; the app only uses them as triggers."""
    subs = list(SUBSCRIPTIONS.items())

    def send():
        for sid, (url, port, path) in subs:
            body = ('<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property>'
                    '<LastChange>&lt;Event/&gt;</LastChange></e:property></e:propertyset>').encode()
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


def cover(key):
    if key in COVERS:
        return COVERS[key]
    colors = None
    m = re.match(r"(album|radio)(\d+)", key)
    if m:
        colors = (ALBUMS if m.group(1) == "album" else RADIO)[int(m.group(2))][2]
    if not colors or Image is None:
        return None
    size = 300
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    rgb = [tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) for c in colors]
    for y in range(size):
        f = y / size
        seg = min(int(f * (len(rgb) - 1)), len(rgb) - 2)
        lf = f * (len(rgb) - 1) - seg
        col = tuple(int(rgb[seg][i] + (rgb[seg + 1][i] - rgb[seg][i]) * lf) for i in range(3))
        d.line([(0, y), (size, y)], fill=col)
    d.ellipse([60, 60, 240, 240], outline=(255, 255, 255), width=3)
    buf = io.BytesIO(); img.save(buf, "PNG")
    COVERS[key] = buf.getvalue()
    return COVERS[key]


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
        if self.path.startswith("/getaa"):
            m = re.search(r"u=([a-z]+\d+)", self.path)
            data = cover(m.group(1)) if m else None
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
        m = re.search(r'service:(\w+):1#(\w+)', self.headers.get("SOAPACTION", ""))
        if not m:
            return self.send(400, "")
        service, action = m.groups()
        inner = re.search(rf"<u:{action}[^>]*>(.*)</u:{action}>", body, re.S)
        args = {k: html.unescape(v) for k, v in re.findall(r"<(\w+)>(.*?)</\1>", inner.group(1) if inner else "", re.S)}
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
