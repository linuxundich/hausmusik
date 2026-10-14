<p align="center">
  <img src="docs/icon.png" alt="Hausmusik icon" width="128" height="128">
</p>

<h1 align="center">Hausmusik</h1>

<p align="center"><strong>Music in every room. Your Sonos speakers, on your Android phone.</strong></p>

<p align="center">
  Play music, group rooms and set the volume everywhere in the house from one fast, good-looking app.
  Hausmusik talks to your speakers directly over your home network. No Sonos account, no cloud, no tracking.
</p>

<p align="center">
  <a href="https://github.com/linuxundich/hausmusik/releases/latest"><strong>Download APK</strong></a>
  &nbsp;·&nbsp; Google Play: coming soon &nbsp;·&nbsp; F-Droid: coming soon &nbsp;·&nbsp;
  <a href="CHANGELOG.md">Changelog</a>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_stage.png" alt="The stage: the playing track, large and in the colors of its cover" width="19%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_rooms.png" alt="Every room with its own volume; tick a room to let it play along" width="19%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_queue.png" alt="The queue with covers" width="19%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_library.png" alt="Albums from the music share as a cover grid" width="19%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5_lyrics.png" alt="Synced lyrics over the stage" width="19%">
</p>

<p align="center"><sub>The artists and covers in the screenshots are invented.</sub></p>

## What it does

### The stage

Open the app and the music is right there: the playing track, large and in the colors of its cover. Play, skip, seek and set the volume with your thumb. If you like, a stage light glows in the cover's colors, a record turns beside the cover, and while your phone charges it becomes a night screen with cover, time and the line being sung.

### Every room in one place

See at a glance what plays where. Drag one room onto another to play together, tick a room to let it join, give every speaker its own volume or mute a single room. One tap lets the whole house play along, and one tap pauses everything.

### All your music

Your Sonos favorites, the music share on your network, internet radio, Sonos playlists and the record player on a speaker's line-in. Linked music services such as Spotify can be browsed and searched right in the app. Search finds artists, albums and tracks in your music share and your services at once.

### Lyrics and background

Time-synced lyrics fill the stage; tap a line to jump there. For the playing artist and album, Hausmusik shows facts, a short Wikipedia text and a picture, and it can open the official music video in the YouTube app. Each of these is off until you switch it on.

### Queue, alarms and sound

- Reorder the queue by dragging, swipe a track away and undo it if you change your mind, or save the queue as a Sonos playlist.
- Hausmusik asks before anything replaces your queue: play now, play next or add to the queue.
- Create and edit Sonos alarms, set a sleep timer, adjust bass, treble and loudness for every speaker, and turn on crossfade.
- Keep your Sonos favorites and playlists tidy without the Sonos app.

### Part of your phone

Control the music from the notification and the lock screen, and change the volume of your room with the phone's volume buttons, even outside the app. Home screen widgets, a Quick Settings tile, shortcuts on the app icon and Android's output switcher are included. Tasker and Home Assistant can send commands too.

### A second life for older speakers

Hausmusik works with current Sonos systems and keeps first-generation (S1) players going, which the current Sonos app no longer supports.

## Your privacy

Hausmusik has no account, no ads and no analytics. It talks to your speakers inside your home network. Lyrics, artist pictures, background and music videos come from free online sources only after you switch them on. Details are in the [privacy policy](PRIVACY.md).

## Install

1. Download `hausmusik-<version>.apk` from the [latest release](https://github.com/linuxundich/hausmusik/releases/latest) and open it on your phone (Android 8.0 or newer).
2. Allow the "Nearby devices" permission when Android asks: Hausmusik needs it to find your speakers.

Your phone needs to be in the same Wi-Fi as the speakers. Hausmusik is coming to Google Play and F-Droid. Want to build it yourself? See [docs/development.md](docs/development.md).

## More

- [All features](docs/FEATURES.md), including how Hausmusik talks to the speakers · [Changelog](CHANGELOG.md) · [Planned](BACKLOG.md)
- Found a bug or tested other Sonos hardware? [Open an issue](https://github.com/linuxundich/hausmusik/issues)
- [Gnomos](https://github.com/linuxundich/gnomos), Hausmusik's sibling for the GNOME desktop

## Credits

Hausmusik's Sonos support is based on [noson](https://github.com/janbar/noson) by Jean-Luc Barrière, the library behind noson-app and Gnomos. Lyrics come from [LRCLIB](https://lrclib.net), artist and album facts from [MusicBrainz](https://musicbrainz.org) and [Wikipedia](https://www.wikipedia.org). The full list with licenses is in [NOTICE.md](NOTICE.md) and in the app under *Settings → Open source licenses*.

## Use of AI

Hausmusik is AI-assisted. Most of the code, the icon and the translations were written with AI assistance, directed by me: I set the requirements, made the design decisions, reviewed the results and test the app on my own Sonos household. Commits written with AI assistance carry a `Co-Authored-By` line.

Hausmusik is free software under the [GPL-3.0-or-later](LICENSE), © 2026 Christoph Langner. It is not affiliated with Sonos, Inc. Sonos is a trademark of Sonos, Inc.
