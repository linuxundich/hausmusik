# Google Play release

Hausmusik stays GPL-3.0-or-later and is sold on Google Play. The GPL allows
selling; it requires that every buyer can get the complete source under the
same licence. Others may build and share the app for free.

## Licence duties for the store build

- [ ] Public repository `github.com/linuxundich/hausmusik` with the exact
      source of every store release, tagged `vX.Y.Z` (the version name comes
      from the tag, see `app/build.gradle.kts`).
- [x] *Settings → About* links to the source and the privacy policy, shows the
      GPL notice, no warranty, the noson credit and the licence list.
- [ ] Store description names the licence and links the source (texts below).
- [ ] Release notes on GitHub per store release.

## Play Console, one-time

- [ ] Developer account (25 USD). Personal accounts created after
      November 2023 must run a **closed test with at least 12 testers for
      14 days in a row** before production access.
- [ ] Identity verification; for a paid app also a **payments (merchant)
      profile**. As a trader under the EU Digital Services Act, Play shows
      address, e-mail and phone number publicly on the store page.
- [ ] **Free or paid is decided once:** an app published for free can never
      become paid. Set the price before the first production release; testing
      tracks do not fix it.
- [ ] Play App Signing: Google keeps the app signing key, the existing
      Hausmusik key (`~/.gradle/gradle.properties`) becomes the upload key.
      Package name `de.linuxundich.hausmusik` is permanent.

## App content forms

| Form | Answer |
|---|---|
| Privacy policy | `https://github.com/linuxundich/hausmusik/blob/main/PRIVACY.md` |
| Ads | No ads |
| App access | No login needed; the app needs Sonos speakers in the same Wi-Fi. Reviewers get a note and a short video against the fake household (`tools/fake-sonos`) |
| Target audience | 18+ (not directed at children) |
| Content rating (IARC) | Utility/music, no user content, no sharing |
| Data safety | **No data collected, no data shared.** No SDKs that collect data. Opt-in requests to LRCLIB, Deezer, MusicBrainz and Wikimedia are user-initiated lookups the user expects, carry no identifiers and reach no service of the developer. Data in transit is encrypted (HTTPS); speaker traffic is local HTTP. Users can delete data by clearing app storage or uninstalling. |
| Foreground service | `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: media session that controls playback on the Sonos speakers (notification, lock screen, volume buttons) while music plays. Needs a short screen recording of the notification controls. |
| News / health / finance / government | No |

## Store listing

App name (30): `Hausmusik – Sonos remote` / `Hausmusik – Sonos-Fernbedienung`
("Sonos" only as a description; the policy forbids implying a connection.)

### English

Short description (80):
Control your Sonos speakers locally: rooms, groups, queue, library, widget.

Full description:

> Hausmusik controls your Sonos speakers directly in your home network, without
> a cloud account. It also works with older S1 systems.
>
> - Rooms and groups: group by dragging, one volume per room, mute single rooms
> - Stage: the playing track in the colours of its cover
> - Queue: reorder, remove with undo, play next, save as Sonos playlist
> - Library: favourites, music share, artists, albums, genres, radio, line-in
> - Linked music services such as Spotify and bonob: browse and search
> - Alarms, sleep timer, sound, crossfade
> - Notification, lock screen and volume buttons, widget, quick settings tile,
>   system output switcher
> - Optional: synced lyrics, artist pictures and background from MusicBrainz
>   and Wikipedia
>
> No ads, no tracking, no account. Hausmusik is free software under the GNU
> GPL 3.0 or later; the source is at github.com/linuxundich/hausmusik.
>
> Hausmusik is not affiliated with Sonos, Inc. Sonos is a trademark of
> Sonos, Inc.

### Deutsch

Kurzbeschreibung (80):
Sonos-Lautsprecher lokal steuern: Räume, Gruppen, Warteschlange, Widget.

Vollständige Beschreibung:

> Hausmusik steuert deine Sonos-Lautsprecher direkt im Heimnetz, ohne
> Cloud-Konto. Auch ältere S1-Systeme funktionieren.
>
> - Räume und Gruppen: Gruppieren per Ziehen, Lautstärke pro Raum, einzelne
>   Räume stumm schalten
> - Bühne: der laufende Titel in den Farben seines Covers
> - Warteschlange: sortieren, entfernen mit Rückgängig, als Nächstes spielen,
>   als Sonos-Playlist sichern
> - Bibliothek: Favoriten, Musikfreigabe, Interpreten, Alben, Genres, Radio,
>   Line-in
> - Verknüpfte Musikdienste wie Spotify und bonob: durchsuchen und suchen
> - Wecker, Schlummer-Timer, Klang, Überblenden
> - Benachrichtigung, Sperrbildschirm und Lautstärketasten, Widget,
>   Schnelleinstellung, Ausgabe-Umschalter des Systems
> - Optional: synchrone Liedtexte, Künstlerbilder und Hintergründe aus
>   MusicBrainz und Wikipedia
>
> Keine Werbung, kein Tracking, kein Konto. Hausmusik ist freie Software unter
> der GNU GPL 3.0 oder neuer; der Quelltext liegt auf
> github.com/linuxundich/hausmusik.
>
> Hausmusik steht in keiner Verbindung zu Sonos, Inc. Sonos ist eine Marke von
> Sonos, Inc.

### Graphics

- [ ] Icon 512×512 PNG (from the icon geometry, see `docs/icon.png`)
- [ ] Feature graphic 1024×500
- [ ] 2–8 phone screenshots per language, from the fake household only
      (invented artists; no real covers, no private notifications)

## Build

```
git tag v0.1.0
./gradlew bundleRelease
```

The bundle is `app/build/outputs/bundle/release/app-release.aab`. `versionCode`
is the commit count, so it only grows as long as history is not rewritten.

## Risks outside the licence

- SMAPI sign-in for Spotify and other services uses Sonos' unofficial device
  interface. Sonos or a service could object; then the feature goes.
- Deezer API terms for commercial use are still to be checked before
  release.
