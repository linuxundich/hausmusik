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

## Recording fixtures

`tools/record-fixtures.py <ip> [ip…]` sends only Get/Browse/List actions to real
speakers and writes the answers to `tools/fixtures-local/` (git-ignored, contains
personal data). `tools/anonymize-fixtures.py` turns them into test fixtures:
player IDs, IP addresses and the NAS name are replaced, long lists trimmed.

## Tests

```sh
./gradlew :sonos:test :app:testDebugUnitTest :app:lintDebug
```
