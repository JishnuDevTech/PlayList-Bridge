# PlayList Bridge

**A privacy-first local music player for Android and web, with fast library sync.**

PlayList Bridge (Groove) plays audio files selected from your own devices. It does not use Spotify or another streaming catalog, and the audio itself stays on the device. Paired clients sync library details, playlists, and queue state through the included small sync service.

The product is branded Groove in the UI. It includes a responsive web player/PWA, a native Android project, and a small sync server for library metadata. It does not connect to Spotify or upload audio files.

## Web player

Serve the project root over HTTPS (or localhost while developing). For a quick local preview:

```sh
python3 -m http.server 8000
```

Open `http://localhost:8000`. Choose individual audio files or a folder. The browser saves selected audio in IndexedDB on that device, so it remains available after refresh. A browser can read music only after the user selects it; it cannot silently scan the device.

## Sync service

The sync service shares song metadata, playlists, queue, and a theme preference. It does not store music files. Each client keeps and plays its own local audio. A track imported on Android will appear in the web library quickly after pairing; play it in the browser by selecting the corresponding local file there.

```sh
cd server
GROOVE_ORIGINS=https://your-web-host.example node server.js
```

On first start, the service prints a one-time pairing code and writes its library and pairing configuration to `server/data/`. Keep that folder on persistent storage. Enter the server's HTTPS address and pairing code under **Set up device sync** in the web player and **Sync devices** in Android.

Set `GROOVE_DATA_DIR` to a persistent path when deploying. Set `GROOVE_ORIGINS` to the exact web origin(s); avoid `*` on a public deployment. Put the service behind HTTPS before exposing it to the internet. `GROOVE_PAIR_CODE` can set the initial pairing code explicitly. Each paired device gets a revocable-style bearer token stored in its local app/browser preferences; device removal UI is not implemented yet.

The server is a single shared library instance. Run a separate instance/data directory for a separate household or account. Back up `server/data/`.

## Android app

Open the `android/` folder in Android Studio and let it sync the Gradle project. The app can scan a user-selected folder, select files, persist Android document permissions, play local tracks, manage a queue and playlists, and pair to the same sync service. Its device library and URI access are saved locally.

## Current feature scope

- Green/black Groove branding and light/dark themes on both clients.
- Local file and folder selection, search, song/artist/album/genre/folder views, playlists, queue, playback controls, track options, and library metadata edits.
- Fast sync: server-sent events in the web app; Android polls the small metadata state every 2.5 seconds.
- Audio files stay local. A synced metadata row without its matching device file is visible but not playable until the user selects that file on the device.
- Browser file/tag edits update Groove metadata; they do not rewrite the original audio tags. **Move to folder** updates the Groove folder label only. Removing a track from the browser library does not delete the original file.

The Android audio cutter, ringtone export, background media notification/control, and native media-tag writing still need implementation. Device-token revocation and conflict resolution for simultaneous edits are also future work. Android signing, installable APK generation, and deployment of the sync server remain to be done.

## Project layout

- `index.html`, `manifest.webmanifest`, `sw.js`, `icon.svg` — responsive web player and PWA.
- `android/` — native Android app source and Gradle wrapper.
- `server/` — metadata-only sync service.
- `tools/metadata_engine.py` — optional local-only metadata report helper.

## Local audio metadata

Android reads title, artist, album, genre, duration, and embedded cover art using the platform media retriever. The browser reads ID3v2 text tags and embedded artwork from MP3 files; other browser formats use filename fallbacks because browser APIs do not expose their embedded tags consistently. Artwork is read and displayed locally on each device, and stays out of sync traffic along with the audio files. A synced track shows artwork when the matching local file is available and readable on that device.

For a local offline metadata report across Mutagen-supported audio formats, install the optional Python helper and pass local file paths:

```sh
python -m pip install -r tools/requirements.txt
python tools/metadata_engine.py "Music/Artist - Song.mp3"
```

The helper only reads local tags and embedded pictures. It does not contact a catalog or upload audio.
