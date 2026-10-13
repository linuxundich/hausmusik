<p align="center">
  <img src="docs/icon.png" alt="Hausmusik icon" width="128" height="128">
</p>

<h1 align="center">Hausmusik</h1>

<p align="center"><strong>Music in every room. A fast, local Sonos controller for Android.</strong></p>

<p align="center">
  Hausmusik talks to your Sonos speakers directly over your home network.
  No Sonos account, no cloud, no tracking. It also keeps first-generation (S1) players going.
</p>

> [!NOTE]
> Hausmusik is in early development. There is no release yet.

## What it does

- **The Stage.** The app opens with what is playing, large and in the colors of the cover.
- **Every room at a glance.** Each group with its own volume and play button. Tick a room to let it play along.
- **Your library.** Sonos favorites, albums on your music share, radio stations and Sonos playlists.
- **Lyrics.** Time-synced lyrics from LRCLIB fill the cover area; tap a line to jump there. Off until you switch them on.
- **Alarms and sound.** Create and edit Sonos alarms, set bass, treble and loudness for every speaker, and a sleep timer that shows how long is left.
- **No surprises.** Before anything replaces a queue that is not empty, Hausmusik asks: play now, play next or add to the queue.
- **Part of Android.** Media notification and lock screen controls, a home screen widget and a Quick Settings tile. The volume buttons change the volume of your room, even outside the app.
- **Built for Android 17.** Jetpack Compose with Material 3 Expressive, English and German.

What is planned next is in [BACKLOG.md](BACKLOG.md).

## How it works

1. Hausmusik finds your speakers with an SSDP search in your Wi-Fi network, or you enter the address of one speaker.
2. It controls them with the same local UPnP interface the original Sonos apps used, on port 1400.
3. Nothing leaves your home network unless you ask for it: lyrics from LRCLIB and artist pictures from Deezer are off until you switch them on, and a music service you link is asked for its catalogue.

The Sonos implementation is based on [noson](https://github.com/janbar/noson) by Jean-Luc Barrière, the library behind [noson-app](https://github.com/janbar/noson-app) and behind [Gnomos](https://github.com/linuxundich/gnomos), Hausmusik's sibling for the GNOME desktop.

Music services that link through a web page (AppLink or DeviceLink, such as [bonob](https://github.com/simojenki/bonob) and Spotify) can be linked under *Library → Services*: Hausmusik opens the service's sign-in page and receives its own token, the way noson does. Browsing and searching use that token; playing goes through the speakers with the account the Sonos system already has, so the service must be set up in the Sonos app first. Services with a user name and password form are not supported.

## Build

Requirements: JDK 21, Android SDK 37.

```sh
./gradlew :sonos:test :app:assembleDebug
```

For development without real speakers, `tools/fake-sonos/fake_sonos.py` provides an invented household. See [docs/development.md](docs/development.md).

## Credits

- [noson](https://github.com/janbar/noson) by Jean-Luc Barrière (GPL-3.0-or-later): the Sonos implementation is based on it; it was written anew in Kotlin, no noson source files are included.
- [SoCo](https://github.com/SoCo/SoCo) (MIT) as a second reference for protocol details.
- Jetpack libraries, Material Icons, OkHttp, Coil, Dagger/Hilt and Kotlin (Apache-2.0).
- Lyrics from [LRCLIB](https://lrclib.net), only when switched on.

The full list with licenses is in [NOTICE.md](NOTICE.md) and in the app under Settings, Open source licenses.

## Use of AI

Hausmusik is AI-assisted. Most of the code, the icon and the translations were written with AI assistance, directed by me: I set the requirements, made the design decisions, reviewed the results and test the app on my own Sonos household. Commits written with AI assistance carry a `Co-Authored-By` line.

Hausmusik is free software under the [GPL-3.0-or-later](LICENSE), © 2026 Christoph Langner. It is not affiliated with Sonos, Inc. Sonos is a trademark of Sonos, Inc.
