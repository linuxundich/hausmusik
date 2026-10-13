# Changelog

All notable changes to Hausmusik are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Library menu: a long press on an entry offers details, adding it to the
  Sonos favorites or removing it, and adding it to a Sonos playlist (an
  existing one or a new one). Sonos playlists can be renamed and deleted, and
  tracks removed from them. Tested on the fake household; the favorite format
  copies what the S1 stores itself.
- Line-in: a speaker's analog input appears at the top of *Radio* (on the
  real household the Connect:Amp's input "Heimkino") and plays in the selected
  room.
- Crossfade: a switch in the stage menu.
- *Everywhere* in the rooms sheet lets every room play along with the
  selected one; rooms chosen in the settings stay out.
- Commands from outside: launcher shortcuts (pause everywhere, play/pause,
  favorites) and intents for Tasker and Home Assistant (`CommandActivity`,
  see docs/development.md).
- Widget "Room or favorite": set up with a room and, if wanted, a favorite;
  a tap plays or pauses that room, or starts the favorite there.
- Checked on the Pixel: with the phone in deep Doze for 42 minutes and music
  playing, every track change arrived as an event and the subscriptions were
  renewed past their 30 minutes.
- Details from more places: a long press on an artist, album or track in the
  library, the info button on an album or artist page, and *Details* in a
  queue entry's menu. The sheet shows a picture: the Wikipedia page's image
  for an artist, the cover from the Cover Art Archive for an album.
- Details are kept on the phone: a month for what was found, a week for
  "nothing found"; *Clear cache* in the settings removes them too.
- The fake household answers like MusicBrainz, Wikidata, Wikipedia and the
  Cover Art Archive for its invented artists (debug extra `-e details`).
- Queue: while dragging a track to the top or bottom edge, the list scrolls
  by itself, faster the closer the finger gets, so a track can move further
  than one screen.
- Sound: bass and treble sliders start in the middle at 0; the active part of
  the track runs from 0 to the value, and the step dots use the cover colors.
- Alarms can play albums among the favorites, Sonos playlists and any album
  of the music share (search by title or artist), not only the chime and radio
  favorites. The real S1 household stored such an alarm (created disabled and
  deleted again); a ringing alarm with an album was not tested.
- Group volume with a muted speaker: the slider shows the mean of the audible
  speakers and moves only them, each scaled like Sonos does; the muted one
  keeps its volume. Before, Sonos' group volume counted the muted speaker in
  and scaled it too, so it came back louder when un-muted. With no or all
  speakers muted nothing changes. Tested on the fake household and on the
  real S1 household (the muted living room kept its volume).
- Fixed: while dragging a single speaker's slider, a refresh could make it
  jump back for a moment; the dragged value now wins, as for the group.
- Landscape: the rooms sheet puts three tiles in a row, so a household of
  three groups fits without scrolling; library, album pages and search move
  the mini player into the top bar as a small pill and use smaller covers, so
  almost two rows of albums show; the volume sheet shows rooms side by side;
  the queue sheet is narrower, so title and length stay close together.
- A muted room shows a crossed-out speaker on its tile (when all its speakers
  are muted) and in the "plays with" list. Mute is now read for every group,
  not only the selected one.
- The room name in the "plays with" list ticks its box too, not only the box.
- Linking a service: after two minutes of waiting the dialog says that a
  sign-in is only valid for a few minutes and offers *Start again*, which asks
  the service for a new page and code (opening the old page again did not
  help once it showed an error, as Sonos' Spotify page does with "500"). A
  failed link offers *Start again* too.
- Fixed: turning the phone closed the open sheet, dialog or library page and
  went back to the stage. The screen stack, the sheets, the play choice and
  the queue's dialogs now survive a rotation (also five quick turns in a row).
- Artist and album details: *Details* in the stage menu (or a tap on the
  artist and album line) opens a sheet with facts from MusicBrainz (type,
  country, years, genres, release date) and, if wanted, a short Wikipedia text
  in the phone's language (English as fallback), found through Wikidata. Off
  until switched on, in the sheet or in the settings, where the Wikipedia text
  has its own switch. MusicBrainz is asked at most once a second and once more
  when it answers 503 or 429; answers are kept until the app ends.
- Volume of each room: the speaker icon left of the stage's volume slider
  opens a "Volume" sheet with all rooms together, one slider per room and a
  mute button per room.

### Added

- Fixed: a long room name on the stage ("Arbeitszimmer + Wohnzimmer") ran
  under the menu button; it is shortened now and keeps the menu free.

- Messages show as one pill at the top of the screen, above everything
  (design C from https://claude.ai/artifact/Pj4FGtisTCz7KWgnHxHpQp): light
  on the stage and the sheets, dark on the library. They no longer hide
  behind the queue or rooms sheet; each sheet hosts the pill in its own
  window, and the whole app shares one message queue. Messages end by
  themselves after their duration; *Undo* stays available while one shows.

- Spotify and TuneIn tested on the real S1 household: Spotify browses (your
  albums, playlists, charts) and an album appended to the queue played;
  TuneIn browses and a station (Deutschlandfunk) played. Everything restored
  afterwards.
- Fixed before that test: Spotify tracks need the `x-sonos-spotify:` scheme
  (Hausmusik sent `x-sonos-http:` as for bonob), TuneIn stations the item ID
  prefix `F10092020`, and service containers now carry
  `?sid=…&flags=8300&sn=…` like the Sonos app writes them (checked against
  the household's own favorites).
- TuneIn stations show their logos; *Unlink* only shows for services
  Hausmusik linked, not for TuneIn.

- Fixed: the first search after starting the app could show "Speakers
  briefly gone, reconnecting". Setting up the music services asks the
  speaker for household, serial number and the 35 KB service list; on an S1
  a poll then timed out and counted as a lost speaker. These requests now
  count as long requests, like library loads, and any timeout during one is
  tolerated.

- The library search covers the linked services too: the music share, bonob
  and Spotify are searched at once and shown as sections (artists, albums,
  tracks, playlists), each as soon as its source answers. Search categories
  come from the service's presentation map, for Spotify through its
  manifest. Items a service lists twice (bonob) no longer crash the list.

- Covers appear much faster when skipping. The next three tracks' covers are
  loaded in the background; requests for the same cover from stage, palette,
  widget and media notification share one fetch (the notification now loads
  through the app's image loader too); the stage keeps the previous cover
  until the new one is there, so nothing flickers within an album. For a
  linked service (bonob) the cover comes from the service itself
  (getMediaMetadata) instead of the speaker: on the S1 the speaker needed
  about 1.7 s per cover and served a 5 KB thumbnail; bonob's cover is asked
  for at 640 px, Spotify's comes from its image server. The lookup never
  holds up a read of the room: the speaker's cover shows first and is
  replaced once the service answered.

- Waiting indicator while the speakers have not answered yet (design A + B
  from https://claude.ai/artifact/VHMYvndVYtRUQ7g35h3E7f): play/pause shows
  the morphing Material 3 Expressive loading indicator in the button, every
  other command (skip, seek, volume, shuffle, grouping) a short wavy line
  under the room chip. A command counts until a read of its group started
  after the command had returned, so the indicator ends when the change is
  visible; it only appears after 300 ms and gives up after 15 s.
- `tools/fake-sonos/fake_sonos.py --slow 1.5` lets every command wait like a
  busy S1, to see the indicators in the emulator.

- Fixed: covers of Spotify tracks stayed empty on S1 speakers. The speaker
  fetches such a picture from the internet first (about 4 s) and serves
  requests one after another; stage, palette, widget and notification asked
  at once and ran into the 8 s limit. Covers now load with 30 s patience,
  two at a time per speaker, and are kept in a 64 MB disk cache.
- Service radios such as a Spotify artist radio show the playing track,
  artist, album, length and progress, with the station as context, instead
  of only the station name.

- New app icon, design 4b-3 "LEDs in the speaker ring": a gable of LED dots
  inside a speaker surround, the peak in a sand accent, in Sonos-like colors
  (near-black ground, warm white). Symmetric around the center; themed
  (monochrome) variant included. Chosen from the drafts at
  https://claude.ai/artifact/P639rGUidzRnMTWcfZgGzN.

- The widget knows where it is: without Wi-Fi it shows "Not on Wi-Fi" (a
  tap opens the Wi-Fi picker), in a network without Sonos "No speakers
  here", while searching "Looking for speakers", and "Reconnecting" over the
  last state; controls and queue only show with speakers in reach. It checks
  the network itself on every redraw, and while offline asks Android to wake
  it when a Wi-Fi network appears, also with the app not running (such a
  request fires once, so it is only made while offline). A periodic update
  every 30 minutes catches a lost network when the app is not running.

- Home screen widget redesigned in the stage's look: background gradient and
  accent from the cover (night blue and amber without one), a pill-shaped
  play button. Three sizes: one row; a large player with a big cover, room,
  title and artist (default 4x2); and from 4x3 on the queue to scroll below,
  starting at the playing track, tap a track to play it.
- Fixed: in the release build the widget never left its loading spinner and
  its buttons did nothing. R8 had removed constructors that WorkManager and
  Glance create by reflection; keep rules added.
- Fixed: the widget showed what was known when the app last ran ("nothing
  playing" while music played); it now connects briefly on every redraw.

- Menus and dialogs follow Material 3 Expressive: menus have 16 dp corners
  and an icon per entry (on the stage and in the queue in the cover colors,
  no longer the light system menu), dialogs carry an icon above the title,
  the play and sleep timer dialogs offer their choices as one grouped list
  instead of a stack of text buttons, *Clear* is shown in the warning color,
  and the alarm editor picks weekdays with one row of seven round toggles.

- Landscape album and folder pages: the cover fills a 152 dp column, the
  gaps to the edge and to the list are an even 24 dp, and the title stays
  above the player bar. Tracks without a track number (bonob) are numbered
  by position instead of repeating the cover.
- Debug builds are called "Hausmusik Dev", so they cannot be mistaken for
  the release when both are on one phone.

- Tested on the real first-generation (S1) household, all speakers muted and
  everything restored afterwards: play and pause, events from the speakers
  (about 2 s), volume buttons in the app and with the screen off (S1 scales
  the group proportionally), next and previous, leaving and joining the group
  by checkbox and by dragging a tile, sleep timer, creating and deleting an
  alarm, media output chip. No errors found.
- The empty alarm list text is centered.
- List cache: artists, albums, genres, favorites, playlists and radio are kept
  on the phone (Room) and show at once. Hausmusik subscribes to the speakers'
  library events and compares the list versions Sonos reports
  (ShareListUpdateID, FavoritesUpdateID, SavedQueuesUpdateID,
  RadioFavoritesUpdateID); only a changed list is loaded again, also while the
  app is open. On the S1 household 1061 albums and 38 favorites came from the
  cache after a restart. Settings can delete the stored lists.
- Artist pictures from Deezer, off until switched on (a card in the Artists tab
  or Settings). Only the names of artists on screen go to api.deezer.com, at
  most three at a time; the result is stored, and a name without a picture is
  asked again after 30 days. Names are matched without article, accents and
  punctuation ("The Beatles" = "Beatles"); collections such as "Various
  Artists" get no picture. Shown in the artist list, in search results and on
  the artist page.
- Music services through SMAPI, the API Sonos defines for them. A new
  *Services* tab lists the linked services and the well-known ones in
  Germany, Austria and Switzerland (Spotify, Deezer, TIDAL, Amazon Music,
  Apple Music, YouTube Music, Qobuz, Audible, Storytel, Plex, Bandcamp,
  IDAGIO, TuneIn) plus those the household already uses (found in its
  favorites, e.g. bonob); every other service is under *More services* (the
  S1 household offers 96). Once a service is linked, the tab shows only the
  linked services and TuneIn (no sign-in needed); every service still to
  link moves to *More services*. Services
  without sign-in, such as TuneIn and many
  radio stations, open directly; their stations play without the queue. Linking opens the
  service's sign-in page in the browser and waits for the confirmation; the
  token is stored per household and renewed when the service hands out a new
  one. Folders, albums and tracks open like the local library; albums,
  playlists and tracks play through the queue with the account serial the
  household already uses for that service (read from its favorites).
  *Unlink* sits on the service's start page. The fake household now includes
  a small bonob for tests. Tested on the real S1 household with bonob:
  linking, browsing down to tracks, appending a 40-track album to the queue
  and playing it (with the account serial `sn=7` from the favorites);
  queue and volumes restored afterwards.
- Fixed: a page that an S1 player answers empty while still resolving
  favorites ended the list early; it is now asked once more, and only complete
  lists are stored.
- S1 players stall now and then: one missed poll no longer shows the
  reconnecting band, timeouts get four tries.
- Release build signed with its own key (kept outside the repository) and
  installed on the Pixel; 3.5 MB after R8.
- Fixed: requests to the speakers could go out over mobile data while Wi-Fi was
  connected (the Pixel does that when it rates mobile data better), which led to
  timeouts and a wrong address for the event callbacks. SOAP, covers, events and
  the SSDP search are now bound to the Wi-Fi or Ethernet network; LRCLIB keeps
  the default route.
- Fixed: an S1 player can take half a minute for the first Browse of the
  favorites and answers nothing else meanwhile. Library calls now wait up to
  60 s, polls do not count such timeouts as a lost speaker, and the library
  says that the first load can take a while.
- Debug builds: a volume cap for tests on real speakers (`-e volcap 25`). It
  keeps every speaker at or below the cap, also when Sonos scales a group
  proportionally, clamps alarm volumes and puts any speaker above it back down.
  Tested on the S1 household: twelve presses of volume up ended at 24.
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
