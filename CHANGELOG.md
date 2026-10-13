# Changelog

All notable changes to Hausmusik are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `:sonos` module in plain Kotlin: SSDP discovery, SOAP client, DIDL-Lite parser,
  zone group topology, transport, volume, queue, browse and sleep timer calls.
  Tested against anonymised responses recorded from a first-generation (S1)
  household.
- Fake Sonos household (`tools/fake-sonos/fake_sonos.py`) with four invented
  rooms, state, queue, radio and generated covers, for the emulator and screenshots.
- App with the Stage (now playing in the cover's colors, wavy progress bar,
  morphing play button), the rooms sheet (group volume, play/pause, grouping
  by checkbox, member volumes), the queue sheet, the library (favorites, albums,
  radio, playlists, album tracks) and a sleep timer.
- A question before anything replaces a non-empty queue: play now, play next or
  add to the queue.
- Local network permission flow for Android 17 and a manual speaker address when
  the SSDP search finds nothing.
- Adaptive app icon with a monochrome layer for themed icons.
- English and German.
- UPnP events (GENA): a small receiver in the app, subscriptions for every
  player with renewal. Changes made elsewhere show up within a second; polling
  drops to a safety net (selected room every 10 s, others every 30 s).
- Media session (Media3) as a remote player: media notification and lock screen
  controls with cover, progress, play/pause, next and previous, and the hardware
  volume buttons change the volume of the selected room, also outside the app
  while the screen is on.
- Volume changes from the app, the notification and the buttons share one
  throttle: at most one request per 120 ms, always the latest value.
- Queue editing: swipe a track to the left to remove it, long press for
  "play next", clear the queue (asks first) and save it as a Sonos playlist.
- Messages (errors, "saved") now also show on the Stage.
- Alarms: list with on/off switch, create, edit, duplicate and delete. Time
  from the clock dial, days (Monday first), room, linked rooms, Sonos chime or a
  radio favorite, volume and duration.
- Sound: bass, treble and loudness for every speaker of the selected group.
- The timer button shows the remaining sleep time.
- Landscape layout for the Stage: cover on the left, controls on the right.
- Room tiles in the rooms sheet: hold a tile and drop it on another room to
  play together, with undo. A changed group reads its member volumes at once.
- Library search: artists, albums and tracks of the music share, as you type.
- Library tabs for artists and genres; artists open their albums, genres
  their albums, albums their tracks with track numbers. Every level can be
  played as a whole.
- Integration tests: the Sonos client against the fake household (15 tests).
- Quick Settings tile: shows the selected room and what plays, a tap pauses or
  resumes it, also while the app is closed.
- Home screen widget (Glance): cover, title, room, previous, play/pause, next.
  It follows the state live while the household is connected and connects
  briefly on its own when shown.
- Switching to another speaker address restarts the update loop, so a poll in
  flight can no longer subscribe to the old household again.
- Every command reads its group again right away instead of waiting for the
  speaker's event.
- Tested on a Pixel 10 (Android 17): SSDP search and UPnP events with the real
  S1 household, lock screen controls, volume buttons also with the screen off.
- The fake household accepts subscriptions and sends NOTIFY on every change.
