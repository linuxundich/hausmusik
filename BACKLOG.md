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
- [x] **P0** Stage with cover colors
- [x] **P0** Local network permission (Android 17) and manual address
- [ ] **P0** GENA events instead of one-second polling (embedded HTTP receiver, renewals)
- [ ] **P0** Media3 session as a remote player: notification, lock screen, hardware volume buttons
- [ ] **P0** Check which foreground service type Android 17 accepts for a remote player
- [ ] **P1** Remember the last room per Wi-Fi network

## Phase 2: Finding music

- [x] **P0** Favorites, albums, radio, playlists, album tracks
- [ ] **P0** Queue: reorder, remove, clear, save as Sonos playlist
- [ ] **P1** Search in the music share
- [ ] **P1** Artists and genres
- [ ] **P2** Local play history (Sonos keeps none)

## Phase 3: Rooms and everyday use

- [x] **P0** Group by checkbox, sleep timer
- [ ] **P1** Group by dragging room tiles (from design A)
- [ ] **P1** Show the remaining sleep time
- [ ] **P1** Alarms: list, create, edit, duplicate
- [ ] **P1** Sound: bass, treble, loudness, night mode

## Phase 4: Part of the system

- [ ] **P1** Glance widgets: player and single rooms or favorites
- [ ] **P1** Quick Settings tile
- [ ] **P2** App shortcuts and intents for Tasker
- [ ] **P2** Check Wear OS through the media session

## Phase 5: Release

- [ ] **P0** README screenshots and a demo video from the fake household
- [ ] **P0** Release signing, GitHub release with APK
- [ ] **P2** Check F-Droid
