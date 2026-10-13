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
- The fake household accepts subscriptions and sends NOTIFY on every change.
