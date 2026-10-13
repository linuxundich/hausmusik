# Development

## Emulator and the fake household

The emulator sits behind NAT, so the SSDP search cannot reach speakers in the
home network. Use the fake household instead:

```sh
tools/fake-sonos/fake_sonos.py --advertise 10.0.2.2
```

It starts four invented rooms on ports 1401 to 1404 of the host. Point a debug
build at it:

```sh
adb shell am start -n de.linuxundich.hausmusik.debug/de.linuxundich.hausmusik.MainActivity -e host 10.0.2.2:1401
```

The `host` extra only works in debug builds and can be sent while the app runs.
Grant the local network permission once in the app.

Events: the fake household runs on the host and cannot reach the emulator
directly. Forward a port and tell the app to announce it:

```sh
adb forward tcp:3400 tcp:3400
adb shell am start -n de.linuxundich.hausmusik.debug/de.linuxundich.hausmusik.MainActivity -e host 10.0.2.2:1401 -e callback 127.0.0.1:3400
```

An empty `callback` extra switches back to the phone's own address.
Hardware buttons in the emulator: `adb shell input keyevent KEYCODE_VOLUME_UP`.

## Details without the internet

The fake household also answers like MusicBrainz, Wikidata, Wikipedia and the
Cover Art Archive for its invented artists and albums (not for Josh Woodward,
who is real). Point a debug build at it, and switch details on in the app:

```sh
adb shell am start -n de.linuxundich.hausmusik.debug/de.linuxundich.hausmusik.MainActivity -e details 10.0.2.2:1401
```

`--es details ''` goes back to the real services. The same extra serves the
music video lookup (`/sparql`): invented songs get a placeholder video ID
("Me at the zoo"), Tina Tux never has one. Lyrics (`/api/get`, `/api/search`)
come back as invented lines every six seconds; "Grep the Sun" carries the
times of a shorter version, so it shows the "times probably do not fit" bar.

## Commands from outside

`CommandActivity` takes intents from launcher shortcuts, Tasker or Home
Assistant (Android companion app, `command_activity`). It shows nothing and
acts on the room in the extra `room` (any member of a group; without it, the
selected room).

| Action (`de.linuxundich.hausmusik.action.…`) | Extras |
|---|---|
| `PLAY_PAUSE`, `PLAY`, `PAUSE`, `NEXT` | `room` |
| `VOLUME_UP`, `VOLUME_DOWN` (3 steps) | `room` |
| `PAUSE_ALL` | – |
| `EVERYWHERE` | `room` (the group the others join) |
| `PLAY_FAVORITE` | `favorite` (title of a Sonos favorite), `room` |

```sh
adb shell "am start -a de.linuxundich.hausmusik.action.PLAY_FAVORITE --es favorite 'Radio Tuxfunk' --es room Bad"
```

A favorite that is not a stream replaces the queue without asking, as a
shortcut should. Starting a favorite or "Everywhere" also selects that room in
the app.

## Release build

The release key lives in `~/.android/hausmusik-release.p12`; store file, alias
and passwords are in `~/.gradle/gradle.properties` (`HAUSMUSIK_*`). Without them
the release build is unsigned. Back the key up: updates need the same key.

```sh
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Real speakers

Allowed for tests, never above 25 % volume. Set the debug cap first:

```sh
adb shell am start -n de.linuxundich.hausmusik.debug/de.linuxundich.hausmusik.MainActivity -e volcap 25
```

`-e volcap off` removes it. Save mute, volumes, groups, track and position
before a test and restore them afterwards; never touch the real queue.

## Recording fixtures

`tools/record-fixtures.py <ip> [ip…]` sends only Get/Browse/List actions to real
speakers and writes the answers to `tools/fixtures-local/` (git-ignored, contains
personal data). `tools/anonymize-fixtures.py` turns them into test fixtures:
player IDs, IP addresses and the NAS name are replaced, long lists trimmed.

## Tests

```sh
./gradlew :sonos:test :app:testDebugUnitTest :app:lintDebug
```

- `:sonos:test`: parsing against recorded fixtures, SOAP and GENA against
  MockWebServer, and `FakeHouseholdTest`, which starts the fake household per
  test (needs `python3`, otherwise skipped).
- `:app:testDebugUnitTest`: Compose UI tests under Robolectric. The host
  activity comes from `app/src/debug/AndroidManifest.xml` instead of
  `ui-test-manifest`.
