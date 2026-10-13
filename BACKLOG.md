# Backlog

Priorities: **P0** needed for a first release, **P1** soon after, **P2** later.
Completed items stay checked.

## Phase 0: Foundation

- [x] **P0** Gradle project with `:app` and `:sonos`
- [x] **P0** Record read-only responses from a real household, anonymise them as test fixtures
- [x] **P0** SSDP discovery, topology, device description
- [x] **P0** Fake household for emulator and tests

## Phase 1: Control (MVP)

- [x] **P0** Rooms, play/pause, next/previous, seek, group and member volume
- [x] **P1** Volume sheet: every room with its own slider and mute button
- [x] **P0** Stage with cover colors
- [x] **P0** Local network permission (Android 17) and manual address
- [x] **P0** GENA events instead of one-second polling (embedded HTTP receiver, renewals)
- [x] **P0** Media3 session as a remote player: notification, lock screen, hardware volume buttons
- [x] **P0** Check which foreground service type Android 17 accepts for a remote player (`mediaPlayback` works)
- [x] **P1** Output switcher shows the room (MediaRoute2ProviderService), rooms selectable there
- [x] **P1** Re-subscribe after a Wi-Fi change (tested in the emulator)
- [ ] **P1** Check subscriptions after Doze on the real phone
- [x] **P1** Test lock screen controls on a device with a lock screen (Pixel 10: works, volume buttons also with the screen off)
- [ ] **P2** Remember the last room per Wi-Fi network (only matters with several households)

## Phase 2: Finding music

- [x] **P0** Favorites, albums, radio, playlists, album tracks
- [x] **P0** Queue: remove (swipe), play next (long press), clear, save as Sonos playlist
- [x] **P1** Queue: reorder by dragging (handle)
- [ ] **P2** Queue: scroll automatically while dragging near the edge
- [x] **P1** Show messages above open sheets: one pill at the top for the whole app
- [x] **P1** Search in the music share
- [x] **P1** Artists and genres
- [x] **P2** Local play history (Sonos keeps none)

## Additions

- [x] **P0** Offline handling: no Wi-Fi mask, reconnecting band, automatic retry, idle session, tile and widget
- [x] **P1** Test the offline masks on the Pixel (Wi-Fi off and on, cold start, fixed address)
- [x] **P1** Lyrics from LRCLIB (synced, opt-in)
- [x] **P1** Settings screen: speaker address, lyrics, about
- [x] **P1** Cache library lists, refresh only when Sonos reports a new list version
- [x] **P1** Artist pictures from Deezer (opt-in)
- [x] **P1** Music services through SMAPI: link (AppLink/DeviceLink), browse, play; tested against the fake bonob
- [x] **P1** bonob on the real household: linked, browsed, album appended and played
- [x] **P1** Search inside the linked services, together with the music share
- [x] **P1** Spotify through SMAPI: linked, browsed, searched, album played on the real household
- [x] **P1** TuneIn on the real household: browsed, station played
- [ ] **P2** Services with UserId sign-in (user name and password form)

- [x] **P1** Artist and album details (MusicBrainz + Wikipedia, opt-in) in a sheet on the stage
- [ ] **P2** Details from the library too (long press on an artist or album) and from the queue
- [ ] **P2** Keep details on disk (now until the app ends) and show an artist picture in the sheet
- [ ] **P2** Test the details sheet with the fake household (fake MusicBrainz in `fake_sonos.py`)

## Phase 3: Rooms and everyday use

- [x] **P0** Group by checkbox, sleep timer
- [x] **P1** Group by dragging room tiles (from design A), with undo
- [x] **P1** Show the remaining sleep time
- [x] **P1** Alarms: list, create, edit, duplicate
- [x] **P1** Sound: bass, treble, loudness
- [ ] **P2** Sound: night mode and speech enhancement for home theatre speakers (no test device)
- [ ] **P2** Tone sliders centered at 0 (the centered track has a different API in material3 1.5 beta)
- [ ] **P2** Alarms with Sonos playlists and albums as source (only chime and radio favorites so far)

## Phase 4: Part of the system

- [x] **P1** Glance widget: player for the selected room, cover colors, three sizes, scrollable queue
- [ ] **P2** Widgets for a single room or a favorite
- [x] **P1** Quick Settings tile
- [ ] **P2** App shortcuts and intents for Tasker
- [ ] **P2** Check Wear OS through the media session
- [ ] **P3** Voice commands, parked (Christoph, 2026-10-13). Finding on the
  Pixel 10: Gemini does not reach Hausmusik's media session, not even for
  "stop the music". It sent the command to Spotify (and woke its session
  again after Spotify was closed); Hausmusik got nothing. Probably because
  the session plays no audio on the phone. Plan when picked up: a microphone
  button (stage, library, Quick Settings tile) with on-device speech
  recognition and a local parser ("stop", "louder", "play Metallica from
  bonob in the living room"); needs search inside services first. Later
  AppFunctions, once Google opens them to all apps; play-from-search in the
  media session for Android Auto.

## Phase 5: Release

- [ ] **P0** README screenshots and a demo video from the fake household
- [ ] **P0** Release signing, GitHub release with APK
- [ ] **P2** Check F-Droid

## QA findings 2026-10-13

Full report with screenshots: `captures/qa-2026-10-13/REPORT.md` (git-ignored).

- [x] **P1** F-01 Sheets, dialogs and the library close on rotation: `rememberSaveable` for the sheet flags, the navigation stack and `pendingPlay`
- [x] **P1** F-05 Link dialog: after two minutes a hint and *Start again* (new page and code); the 10-minute limit in `awaitLink` stays
- [ ] **P2** F-02/F-03/F-04 Landscape: room sheet cuts off the third tile, library shows one grid row behind the mini player, search and volume sheet use the width badly
- [ ] **P2** F-06 Show mute on the room tiles and in the "plays with" list
- [ ] **P2** F-07 Check the group slider: it averages in muted rooms and scales them too (Sonos' own group volume; mute itself stays). Test on the fake household before deciding
- [ ] **P3** F-09 The whole row of the "plays with" list should toggle the checkbox
