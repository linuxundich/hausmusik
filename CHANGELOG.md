# Changelog

All notable changes to Hausmusik are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Landscape reviewed screen by screen and reworked:
  - Sheets get wider and lose the drag handle; the rooms sheet shows tiles and
    "plays with" side by side, the sound sheet two speakers per row.
  - Lyrics take half the width and fade out at the edges (also in portrait).
  - Library tabs move into the top bar; the mini player is flatter.
  - Albums and artists: cover and title on the left, tracks on the right,
    "Play" in the top bar.
  - Alarms in two columns with "Add" in the top bar instead of a floating
    button over the cards; the editor in two columns.
  - Settings and licenses at a readable width; the not-found screen in two
    columns; all centered screens scroll if the height is short.
  - A running sleep timer shows its time left in the landscape header.
- Fixed: the clock dial in the alarm editor was cut off in landscape; it now
  uses Material 3's TimePickerDialog.
- Fixed: the alarm editor showed white status bar icons on white.
- Fixed: the room chip broke its arrow into a second line on long names.
- Licensing: SPDX header (copyright and GPL-3.0-or-later) in every source file;
  the Sonos layer names noson by Jean-Luc Barrière as its basis. NOTICE.md with
  all third-party components and licenses; Credits in the README; in the app
  the about section shows copyright, warranty notice and the noson credit, and
  a screen lists the open source licenses.

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
- Offline handling, since the phone is often on mobile data:
  - No Wi-Fi or Ethernet: a clear "Not in your home Wi-Fi" mask with a button
    to the Wi-Fi settings; no search, no timeouts. Hausmusik connects by itself
    when Wi-Fi is back. A fixed speaker address is still tried once (VPN).
  - Speakers gone for a moment: the Stage stays, with a "reconnecting" band;
    after three failed reads within a few seconds "No speakers found".
  - Speakers not found: Hausmusik looks again every 30 seconds by itself and
    subscribes to events afresh (rebooted speakers forget subscriptions).
  - Media notification goes idle, the tile shows "Not in Wi-Fi" as
    unavailable, the widget says so too; tile and widget actions return at once.
- Tested on the Pixel 10 on mobile data: the mask appears within 1 to 3 s,
  also on a cold start (with the last room), and Hausmusik is back about 10 s
  after Wi-Fi returns. Retries after a miss come after 2, 5 and 10 s before
  the 30 s rhythm, since a fresh Wi-Fi is often not usable at once.
- Fixed: unsubscribing waited for every speaker without Wi-Fi (3 s each), which
  held the app in "reconnecting"; unsubscribes now run in the background.
- Fixed: with a fixed address and no Wi-Fi the screen flickered between the
  mask and the band on every retry.
- Fixed: a quiet tick between polls reset the failure count, so lost speakers
  were never noticed while events were active.
- The media output chip on the notification shows the room instead of "This
  phone". The system output switcher lists every room: tap one to show it,
  "+" lets it play along, the slider sets the group volume, "Stop streaming"
  pauses. Done with a MediaRoute2ProviderService (Android 11+) whose session is
  linked to the media session through DeviceInfo.routingControllerId.
- History: every track that plays while Hausmusik is connected, per day, kept
  only on the phone (Room); a tap searches the library for it.
- Lyrics on the Stage: time-synced from LRCLIB, the current line lights up, a
  tap on a line jumps there; plain text when there is no timing. Off until the
  user agrees, since title, artist, album and length go to lrclib.net.
- Settings: change the speaker address or go back to the automatic search,
  switch lyrics on or off, version and licence.
- Fake household: "Breadcrumbs" by Josh Woodward (CC BY 4.0) with the real
  "Border Blaster", so lyrics can be tried without personal data.
- Compose UI tests with Robolectric: Stage (track, room, radio cannot skip,
  timer label), play dialog in English and German, alarm day labels; unit
  tests for the speaker address.
- The progress bar no longer jumps to the end when a position has no read time.
- After a Wi-Fi change the app connects and subscribes again, since its own
  address for the events changed.
- Queue: drag a track by its handle to reorder it. Messages show above the
  queue sheet.
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
