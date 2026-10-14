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
- [x] **P1** Check subscriptions after Doze on the real phone (42 min deep idle while playing: all events, renewals fine)
- [x] **P1** Test lock screen controls on a device with a lock screen (Pixel 10: works, volume buttons also with the screen off)
- [ ] **P2** Remember the last room per Wi-Fi network (only matters with several households)

## Phase 2: Finding music

- [x] **P0** Favorites, albums, radio, playlists, album tracks
- [x] **P0** Queue: remove (swipe), play next (long press), clear, save as Sonos playlist
- [x] **P1** Queue: reorder by dragging (handle)
- [x] **P2** Queue: scroll automatically while dragging near the edge
- [x] **P1** Show messages above open sheets: one pill at the top for the whole app
- [x] **P1** Search in the music share
- [x] **P1** Artists and genres
- [x] **P2** Local play history (Sonos keeps none)

## Additions

- [x] **P0** Offline handling: no Wi-Fi mask, reconnecting band, automatic retry, idle session, tile and widget
- [x] **P1** Test the offline masks on the Pixel (Wi-Fi off and on, cold start, fixed address)
- [x] **P1** Lyrics from LRCLIB (synced, opt-in)
- [x] Lyrics shift per track (long press, ±0.5 s), doubtful times flagged, better LRCLIB match
- [ ] Lyrics shift on the real live album (Pixel, once LRCLIB answers again)
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
- [x] **P2** Details from the library too (long press on an artist or album) and from the queue
- [x] **P2** Keep details on disk and show a picture in the sheet (Wikimedia for artists, Cover Art Archive for albums)
- [x] **P2** Test the details sheet with the fake household (fake MusicBrainz in `fake_sonos.py`)

- [x] **P2** Sonos favorites: add and remove from the library
- [x] **P2** Sonos playlists: add to, create, rename, delete, remove tracks
- [x] **P2** Line-in as a source (Radio tab)
- [ ] **P2** Check adding a Sonos favorite on the real S1 (only the fake was tested)
- [ ] **P2** Sleep timer that fades out over the last minutes
- [ ] **P3** Play files from the phone (own HTTP server)
- [ ] **P3** Announcements: a sound in chosen rooms, then back to what played

- [x] **P2** Music video: open the official video in the YouTube app at the current point (Wikidata P1651)
- [ ] **P3** Music video: check the start point on the real phone (the emulator's ad never finished)

## Phase 3: Rooms and everyday use

- [x] **P0** Group by checkbox, sleep timer
- [x] **P1** Group by dragging room tiles (from design A), with undo
- [x] **P1** Show the remaining sleep time
- [x] **P1** Alarms: list, create, edit, duplicate
- [x] **P1** Sound: bass, treble, loudness
- [ ] **P2** Sound: night mode and speech enhancement for home theatre speakers (no test device)
- [x] **P2** Tone sliders centered at 0 (the centered track has a different API in material3 1.5 beta)
- [x] **P2** Alarms with Sonos playlists and albums as source (favorite albums, Sonos playlists, album search)
- [ ] **P2** Check that an alarm with an album really rings on the S1 (only storing it was tested)
- [ ] **P3** Alarms with service content (Spotify, bonob playlists): needs the account in the metadata

- [x] **P2** Crossfade switch
- [x] **P2** "Everywhere": all rooms play along, with rooms left out in the settings

## Phase 4: Part of the system

- [x] **P1** Glance widget: player for the selected room, cover colors, three sizes, scrollable queue
- [x] **P2** Widgets for a single room or a favorite ("Room or favorite", set up when placed)
- [x] **P1** Quick Settings tile
- [x] **P2** App shortcuts and intents for Tasker (CommandActivity, docs/development.md)
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

## Extras (2026-10-14)

- [x] Stage light with house lights, tap along, turntable, night stage; each switchable
- [ ] Night stage on the Pixel at night: brightness, burn-in wander, a whole night on the charger
- [ ] A tap on the night stage cannot open the app (the system's dream overlay takes the touch); revisit if Android allows it

## Usability audit 2026-10-14

Report: `captures/ux-2026-10-14/BERICHT.md` (git-ignored).

- [x] P1-1 Mute visible on the stage
- [x] P1-2 One tap mutes a single room
- [x] P1-3 Volume as a number on the stage
- [x] P1-6 Unlink a service only through a menu, confirmed
- [x] P1-7 Feedback on long starts and on skip
- [x] P2-12 No "–:––" for tracks without a length
- [x] P2-13 Undo after removing from the queue
- [x] P2-8 Room tiles: selection framed, names on two lines
- [x] P1-4 Library by source first (Favorites · My music · services · Radio)
- [x] Service marks in the source row (Sonos lists no logo URLs; own monogram instead)
- [x] Local music library can be switched off (Christoph, 2026-10-14)
- [x] P1-5 Mark what plays in lists (station logos: Sonos gives none for saved stations; radio icon instead)
- [x] P1-8 Short outage: keep the banner ("trying again") instead of the full-screen setup
- [x] P2-7 Split the ⋮ menu by track / room / app; crossfade as a switch
- [x] P2-9 "Always replace" as an opt-in in the replace dialog
- [x] P2-10 Radio: inputs in their own section, no dead buttons (station logos: Sonos gives none)
- [x] P2-11 Landscape: library beside the stage
- [x] P2-14 bonob duplicates the local library: stays, it is the user's choice which way to browse (Christoph, 2026-10-14)
- [x] Room switch from the library (room in the mini player)

## Phase 5: Release

- [ ] **P0** README screenshots and a demo video from the fake household
- [x] **P0** Release signing (own key, from `~/.gradle/gradle.properties`); `bundleRelease` builds a signed AAB
- [x] **P0** GitHub release with APK (v0.1.0, 2026-10-14)
- [ ] **P2** Check F-Droid

### Google Play (paid, stays GPL; checklist in `docs/play-store.md`)

- [x] **P0** *About* links to source code and privacy policy; `PRIVACY.md`
- [x] **P0** Store texts (DE/EN), data safety and foreground service answers drafted
- [ ] **P0** Make `github.com/linuxundich/hausmusik` public, tag `v0.1.0` (needs Christoph's OK)
- [ ] **P0** Play Console: account, identity, payments profile, Play App Signing (Christoph)
- [ ] **P0** Closed test with 12 testers for 14 days (new personal accounts)
- [ ] **P0** Decide the price before the first production release (free cannot become paid)
- [x] **P0** English screenshots from the fake household (`--english`, generated tux covers), README and fastlane
- [x] **P0** Store graphics: icon 512 px, feature graphic (EN/DE), in `fastlane/metadata/android/`
- [x] **P1** German screenshots for the store (`fake_sonos.py --shots`)
- [ ] **P0** Short video of the notification controls for the foreground service declaration
- [ ] **P1** Check the Deezer API terms for a paid app

## QA findings 2026-10-13

Full report with screenshots: `captures/qa-2026-10-13/REPORT.md` (git-ignored).

- [x] **P1** F-01 Sheets, dialogs and the library close on rotation: `rememberSaveable` for the sheet flags, the navigation stack and `pendingPlay`
- [x] **P1** F-05 Link dialog: after two minutes a hint and *Start again* (new page and code); the 10-minute limit in `awaitLink` stays
- [x] **P2** F-02/F-03/F-04 Landscape: room sheet cuts off the third tile, library shows one grid row behind the mini player, search and volume sheet use the width badly
- [x] **P2** F-06 Show mute on the room tiles and in the "plays with" list
- [x] **P2** F-07 Check the group slider: it averages in muted rooms and scales them too (Sonos' own group volume; mute itself stays). Test on the fake household before deciding
- [x] **P3** F-09 The whole row of the "plays with" list should toggle the checkbox
