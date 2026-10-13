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
