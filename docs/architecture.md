# Architecture

Decisions as of 2026-10-13 and why they were made.

## Goal

A fast Sonos controller for Android that works only in the home network:
no Sonos account, no cloud, no server of its own. It must keep working with
first-generation (S1) players, because the author's household runs on them.

## Which Sonos interface

| Interface | Decision |
|---|---|
| UPnP/SOAP on port 1400 | **Used.** Works on S1 and S2. Playback, queue, groups, library, favorites, alarms. |
| GENA events (UPnP eventing) | **Used** as triggers: a NOTIFY says *that* something changed, the app then reads the state with SOAP. |
| Local WebSocket API on port 1443 | Not used. Undocumented, fixed API key, S2 only. Maybe later as a second path. |
| Sonos Cloud Control API | Not used. Needs a Sonos account, an own server and a client secret. |
| Music service APIs (SMAPI) | Not used. Sign-in only works through Sonos. Services are played through existing favorites and playlists. |

## Modules

```
:app    Android: Compose UI, ViewModels, HouseholdRepository, DI
:sonos  Plain Kotlin/JVM: discovery, SOAP, DIDL-Lite, topology, typed player client
```

`:sonos` has no Android dependency and is tested on the JVM against recorded
responses (`sonos/src/test/resources/fixtures`, produced by
`tools/record-fixtures.py` and `tools/anonymize-fixtures.py`). XML is parsed with
DOM, because it is the only XML API available on both the JVM and Android.

## Lessons carried over from Gnomos

- GetPositionInfo is the source of truth for the current track; events carry
  stale titles after a queue change.
- Browse results come in pages; an empty container is not an error.
- Volume moves the slider at once and is sent at most every 120 ms, always the
  latest value.
- Transport buttons follow `GetCurrentTransportActions` (radio cannot skip).
- "Previous" restarts the track after a few seconds.
- Replacing a queue asks first. In Gnomos, "play all" once wiped a streaming
  queue that could not be restored.

## State

`HouseholdRepository` keeps the household up to date while an owner needs it:
the visible activity or the playback service. `SonosEvents` subscribes to
AVTransport and RenderingControl of every player and to ZoneGroupTopology of
one, renews the subscriptions and turns each NOTIFY into a trigger. The
repository then reads the affected group again. Events are deliberately not
parsed: GetPositionInfo stays the source of truth.

Polling remains as a safety net: with working subscriptions the selected group
every 10 s and the rest every 30 s; without them once a second and every three
seconds, as before. The progress bar extrapolates between reads.

## Media session

`PlaybackService` (Media3 `MediaSessionService`) hosts `SonosSessionPlayer`, a
`SimpleBasePlayer` with `DeviceInfo.PLAYBACK_TYPE_REMOTE` and a volume range of
0 to 100. Android builds the media notification and the lock screen controls
from it and sends the hardware volume buttons to it. Media3 only skips when the
playlist has a neighbour, so the playlist is a window of up to three items
around the current track; the neighbours stand for next() and previous().

The service runs in the foreground (type `mediaPlayback`) while music plays and
ends itself after ten minutes without playback when the app is not visible.
On a Pixel 10 with Android 17 the volume buttons reach the session also with
the screen off and on the lock screen; the emulator does not route them while
the screen is off.

Volume from the Stage, the rooms sheet, the notification and the buttons goes
through one throttle in the repository: at most one request per 120 ms per
target, always the latest value.

## Toolchain (checked 2026-10-13)

| Component | Chosen | Note |
|---|---|---|
| AGP / Gradle | 9.4.1 / 9.7.1 | as in Commentator |
| Kotlin / KSP | 2.3.21 / 2.3.12 | KSP is not yet available for Kotlin 2.4 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | |
| Compose BOM | 2026.09.00 | |
| compose-material3 | 1.5.0-beta01 | pinned past the BOM: Material 3 Expressive is only public from 1.5 |
| Media3 | 1.11.1 | for the session in phase 1 |
| Glance | 1.2.0 | widgets in phase 4 |
| OkHttp / Coil | 5.5.0 / 3.6.3 | |
| Hilt | 2.60.1 | |

Deliberately not used: Firebase, Play Services, analytics, Retrofit (SOAP is
not REST), Room (nothing to persist yet), material-icons-extended (the few extra
icons are path data in `AppIcons`).

## Android 17

With targetSdk 37, every connection into the home network needs the runtime
permission `ACCESS_LOCAL_NETWORK` (shown as "nearby devices"). The app asks for
it on first start, with an explanation. SSDP additionally needs a
`WifiManager.MulticastLock`.
