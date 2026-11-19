<div align="center">

<img src="Lumo/Assets.xcassets/AppIcon.appiconset/icon.png" width="112" height="112" alt="Lumo icon">

# Lumo

**A free, ad-free music player for iPhone, Mac and Android, powered by YouTube Music.**

Search anything, blend songs like a DJ with AutoMix, read synced lyrics, and keep your music offline.

iPhone (iOS 26+) · Mac (Apple Silicon, macOS 15+) · Android (10+) · GPL-3.0

</div>

---

## Coming from Nive?

Lumo is the new name of Nive. It updates over Nive and keeps your likes, playlists, downloads and sign-in:

- **iPhone:** SideStore shows **Update** for Nive. After updating, the app is called Lumo.
- **Android:** download `Lumo-<version>.apk` from the [latest release](https://github.com/Nagashreeshyl/lumo/releases/latest) and install it over Nive (Nive 0.3.0 can't check for updates itself; Lumo can).
- **Mac:** install the new DMG and delete the old Nive app from Applications. Your library carries over.

## Features

**Listen**
- Search songs, artists, albums, movie soundtracks and playlists, with live suggestions as you type
- Filters: All · Songs · Albums · Artists · Videos · Playlists, with a big top result you can play in one tap
- Album, artist and playlist pages; search a movie name to get its full soundtrack
- Screen-off playback with lock screen and Dynamic Island controls
- Repeat (all / one) and shuffle

**AutoMix**
- Songs blend into each other at the end instead of stopping
- Detects each song's tempo and beats; when tempos are close it gently matches them and lines up the beats
- Bass swap mid-blend so two kick drums never clash, and silent intros and outros are skipped
- Falls back to a smooth crossfade when a song has no clear beat
- An **AutoMix** badge shows while a blend is playing

**Discover**
- **Home:** Quick picks built from what you play and like, "Because you liked…", Recently played, plus YouTube Music's home
- **Explore:** new releases, charts, and moods & genres
- **Up Next** keeps going with YouTube's own Mix for the current song
- **Not Interested** (swipe in Up Next, 👎 in the player, or the ⋯ menu) hides a song everywhere, for good

**Lyrics**
- Word-synced lyrics from Apple Music, Spotify and Deezer via LyricsPlus, with LRCLIB as fallback
- Tap a line to jump there
- Non-Latin lyrics (Tamil, Hindi, Telugu, Korean…) can be shown in English letters
- "Search Lyrics" when a song's name on YouTube doesn't match

**Updates**
- Lumo checks for a new version each time it opens. Android downloads and installs it in the app; iPhone opens SideStore; Mac downloads the new DMG

**Shortcuts & Siri**
- Actions for the Shortcuts app: Play Liked Songs, Play a Playlist, Play a Song, Play / Pause, Next / Previous, Set AutoMix
- Use them in automations ("every morning at 8, play my liked songs"), on the Home Screen, or with Siri

**Library**
- Liked songs, your own playlists (rename, reorder, remove songs, delete), downloads for offline listening, recently played
- **Save to Library** on any YouTube Music playlist or album copies the whole thing into your playlists
- Optional Google sign-in for your personal YouTube Music home (Lumo works fully without it)

## Install on Mac

Download **`Lumo-<version>.dmg`** from the [latest release](https://github.com/Nagashreeshyl/lumo/releases/latest), drag Lumo into Applications, and allow it once in **System Settings → Privacy & Security → Open Anyway**. The Mac app never expires. Details and keyboard shortcuts: **[docs/INSTALL-MAC.md](docs/INSTALL-MAC.md)**.

The Mac app has the same features in a Mac layout: a sidebar, a player bar, a lyrics / Up Next side panel, media keys and shortcuts.

## Install on Android

Download **`Lumo-<version>.apk`** from the [latest release](https://github.com/Nagashreeshyl/lumo/releases/latest) on your phone, open it and tap **Install** (allow installs from your browser when Android asks). Step by step, plus automatic updates with Obtainium: **[docs/INSTALL-ANDROID.md](docs/INSTALL-ANDROID.md)**.

The Android app has the same features as the iPhone app, AutoMix included, and reads the same library backup files, so you can move your likes and playlists between an iPhone and an Android phone.

## Install on iPhone (free)

Lumo isn't on the App Store. You install it with **[SideStore](https://sidestore.io)** using a free Apple ID, and SideStore keeps it signed.

Short version:
1. Install **LocalDevVPN** from the App Store and **SideStore** with [iloader](https://docs.sidestore.io/docs/installation/prerequisites) on your computer.
2. In SideStore → **Sources → +**, add:
   ```
   https://raw.githubusercontent.com/Nagashreeshyl/lumo/main/altstore.json
   ```
3. Install **Lumo** from that source. New versions show up there as **Update**.

Step-by-step guide, weekly refresh and troubleshooting: **[docs/INSTALL.md](docs/INSTALL.md)**.

## How it works

Lumo talks to YouTube Music's internal API for search, pages and recommendations, and resolves playable audio streams with [SwiftyTube](https://github.com/SwiftyMedia/SwiftyTube). Songs are fetched in parallel chunks, kept compressed in a local cache, beat-analysed once, and played through a two-deck `AVAudioEngine` mixer that performs AutoMix. Details: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

Nothing is sent anywhere except YouTube, the lyrics services and Google's search suggestions. There's no account, analytics or tracking.

## Build from source

Requires Xcode 26+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```bash
git clone https://github.com/Nagashreeshyl/lumo.git
cd lumo
xcodegen generate
open Lumo.xcodeproj            # run on a simulator or your device
./build_ipa.sh 0.2.0           # unsigned build/Lumo.ipa for SideStore
./build_dmg.sh 0.2.0           # build/Lumo-0.2.0.dmg for Mac
```

Tests:

```bash
xcodebuild test -project Lumo.xcodeproj -scheme Lumo -destination 'platform=iOS Simulator,name=iPhone 17'
```

Android (Kotlin, Jetpack Compose) lives in [`android/`](android). It needs JDK 21 and the Android SDK, no Android Studio:

```bash
cd android
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./build_apk.sh 0.3.0 30               # signed release → build/Lumo-0.3.0.apk
```

Publishing a new version: **[docs/RELEASING.md](docs/RELEASING.md)**.

## FAQ

**Do I need YouTube Premium?** No. Lumo plays without ads whether or not you have Premium, and it keeps working after Premium ends.

**Playback stopped working.** YouTube changes things from time to time. The fix is usually an update to the streaming engine (SwiftyTube on iPhone and Mac, the YouTube layer from Mochy-Music on Android) and a new release. Check for an update in SideStore, or download the newest APK.

**Lumo won't open.** The 7-day signature expired. Connect LocalDevVPN, open SideStore, tap **Refresh All**.

## Credits

- [Mochy-Music](https://github.com/realidkroo/Mochy-Music), the Android app that inspired Lumo; the Android version uses its YouTube Music layer (`android/innertube`)
- [SwiftyTube](https://github.com/SwiftyMedia/SwiftyTube) and [Trop](https://github.com/SwiftyMedia/Trop), the InnerTube and stream resolution engine and reference client
- [LyricsPlus](https://github.com/ibratabian17/LyricsPlus) and [LRCLIB](https://lrclib.net), lyrics
- [SideStore](https://sidestore.io), sideloading

## Disclaimer

Lumo is an independent, unofficial project for personal and educational use. It is not affiliated with, endorsed by, or connected to YouTube, Google, Apple or any music service. Please support the artists you listen to.

## License

[GPL-3.0](LICENSE)
