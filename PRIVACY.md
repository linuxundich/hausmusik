# Privacy policy

Hausmusik, an app by Christoph Langner. Last updated: 2026-10-14.

## In short

Hausmusik has no account, no analytics, no advertising and no crash reporting.
The author runs no server for it and receives no data from the app. The app
talks to your Sonos speakers in your home network. It contacts services on
the internet only for features you switch on yourself.

## Your home network

To find and control your speakers, Hausmusik talks directly to them over your
Wi-Fi (SSDP discovery, UPnP/SOAP on port 1400, event subscriptions). This
traffic stays in your home network. Android asks you for the "Nearby devices"
(local network) permission for this.

## Data kept on your phone

Settings, the speaker address, cached library lists, the play history, linked
music service tokens and cached lyrics, pictures and details stay in the app's
private storage on your phone. They are not backed up to the cloud
(`allowBackup` is off) and are deleted when you uninstall the app.

## Optional online features

Each of these is off until you switch it on in the app. When used, the app
sends the named data directly from your phone to the service; the author sees
none of it. The service's own privacy policy applies, and like any web server
it sees your IP address.

| Feature | Service | What is sent |
|---|---|---|
| Lyrics | LRCLIB (lrclib.net) | Title, artist, album and length of the playing track |
| Artist pictures | Deezer (api.deezer.com) | Artist name |
| Artist and album details | MusicBrainz (musicbrainz.org), Cover Art Archive (coverartarchive.org) | Artist and album name, MusicBrainz IDs |
| Wikipedia text and pictures | Wikidata, Wikipedia, Wikimedia Commons | Wikidata and Wikipedia page IDs |
| Music videos | Wikidata (query.wikidata.org) | Title and artist; the video then opens in the YouTube app or your browser |
| Station logos | radio-browser.info, Wikidata (query.wikidata.org), Wikimedia Commons, the station's homepage | Stream address and name of the playing station to radio-browser.info; the station's homepage address to Wikidata; the homepage is read for its app icons; the logo is loaded from Commons or the station's site |
| Music services (e.g. Spotify, bonob) | The music service you link | The sign-in runs on the service's own web page in your browser; afterwards the app uses the service's token to browse and search |

## Children

Hausmusik is not directed at children and collects no personal data from
anyone.

## Contact

Christoph Langner, mail@christoph-langner.de

Changes to this policy are published in the app's source repository:
<https://github.com/linuxundich/hausmusik>
