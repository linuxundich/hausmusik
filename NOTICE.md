# Notices

Hausmusik is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

Copyright (C) 2026 Christoph Langner

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.

Hausmusik is not affiliated with, endorsed by or sponsored by Sonos, Inc.
"Sonos" is a trademark of Sonos, Inc.

## The Sonos implementation comes from noson

The Sonos layer in `sonos/` is based on **noson** (libnoson) by Jean-Luc
Barrière, <https://github.com/janbar/noson>, licensed under the
GPL-3.0-or-later. libnoson is the library behind
[noson-app](https://github.com/janbar/noson-app) and behind
[Gnomos](https://github.com/linuxundich/gnomos), the author's Sonos client for
GNOME, from which Hausmusik grew.

Hausmusik's Kotlin code follows libnoson: the way players are discovered, the
UPnP services and actions it calls and with which arguments, the URI schemes
(`x-rincon-queue:`, `x-rincon:`, `x-rincon-playlist:`, radio and favorite
handling), the DIDL-Lite metadata, the event subscriptions and the workarounds
Gnomos learned on top of libnoson. It was written anew in Kotlin for Android;
no noson source files are included.

[SoCo](https://github.com/SoCo/SoCo) (MIT License) served as a second
reference for SOAP details such as library search IDs and alarm arguments.

## Libraries in the app

| Component | Authors | License |
|---|---|---|
| AndroidX: Activity, Annotation, Collection, Compose (UI, Foundation, Animation, Runtime, Material 3, Material Icons), Core, DataStore, Glance, Lifecycle, Media3, Navigation, Palette, Room, SavedState, SQLite, Startup, Window, WorkManager | The Android Open Source Project | Apache-2.0 |
| Dagger / Hilt | The Dagger Authors | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | JetBrains s.r.o. and contributors | Apache-2.0 |
| OkHttp, Okio | Square, Inc. | Apache-2.0 |
| Coil | Coil Contributors | Apache-2.0 |
| Guava (via Media3) | The Guava Authors | Apache-2.0 |
| JSpecify annotations | The JSpecify Authors | Apache-2.0 |

The Apache License 2.0 is at <https://www.apache.org/licenses/LICENSE-2.0>.

### Icons

Several icons (play, pause, skip, volume, queue, shuffle, repeat, speaker,
timer, library, radio, album, playlist, lyrics, drag handle, Wi-Fi off,
alarm, tune, play next, playlist add, copy, delete sweep, save, link) are
path data taken from Google's
[Material Icons](https://github.com/google/material-design-icons), Apache-2.0,
in `ui/theme/Icons.kt` and `res/drawable/ic_*.xml`. The app icon is the
author's own work.

## Libraries used only for building and testing

JUnit 4 (EPL-1.0), Robolectric (MIT), Turbine (Apache-2.0), OkHttp
MockWebServer (Apache-2.0), AndroidX Test (Apache-2.0). None of them ship in the
app.

## Online services

Lyrics come from [LRCLIB](https://lrclib.net), only after the user switches
them on. The lyrics belong to their respective rights holders.

Artist pictures come from the public [Deezer API](https://developers.deezer.com),
only after the user switches them on. Hausmusik is not affiliated with Deezer;
the pictures belong to their respective rights holders.

Artist and album details come from [MusicBrainz](https://musicbrainz.org), whose
core data is in the public domain (CC0), and optionally from
[Wikipedia](https://www.wikipedia.org) through [Wikidata](https://www.wikidata.org).
Wikipedia texts are licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); Hausmusik shows
the text unchanged with a link to its page. Both are used only after the user
switches them on. Artist pictures come with the Wikipedia page from
[Wikimedia Commons](https://commons.wikimedia.org), album covers from the
[Cover Art Archive](https://coverartarchive.org); they belong to their
respective rights holders. Hausmusik is not affiliated with these projects.

## Test data

- `sonos/src/test/resources/fixtures/` holds responses recorded from the
  author's own Sonos players and anonymised (player IDs, addresses, host names).
- The fake household in `tools/fake-sonos/` uses invented artists and albums,
  plus the title, album and length of "Border Blaster" from *Breadcrumbs* by
  Josh Woodward (<https://www.joshwoodward.com>), licensed under CC BY 4.0, so
  that lyrics can be tested. No audio is included.
