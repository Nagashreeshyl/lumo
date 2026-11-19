# Architecture

Lumo has two app targets that share all of the engine and most views: **Lumo** (iPhone, iOS 26+) and **LumoMac** (Mac, Apple Silicon, macOS 15+). Both use Swift 5 language mode with MainActor default isolation. The only dependency is [SwiftyTube](https://github.com/SwiftyMedia/SwiftyTube). The Xcode project is generated from `project.yml` with XcodeGen.

```
Lumo/
  LumoApp.swift          App entry, tabs (Home · Explore · Library · Search), full-screen player
  YT.swift               Song model, search / home / radio / YouTube Mix / stream URLs, song parsing
  Browse.swift           Albums, artists, playlists, moods: models, page parsing, search filters
  Player.swift           Queue, repeat/shuffle, autoplay, listening signals, lock screen, AutoMix wiring
  Queue.swift            Pure shuffle / repeat helpers
  Recommender.swift      Home shelves (Quick picks, Because you liked, Recently played + YouTube home)
  Lyrics.swift           LyricsPlus + LRCLIB lookup, LRC parsing, romanization
  Downloads.swift        Offline downloads
  Auth.swift             Optional Google sign-in (session kept in the Keychain)
  Models.swift           SwiftData: Track (likes, plays, skips, downloads, Not Interested), Playlist
  Log.swift              Diagnostics (no personal data)
  Audio/
    AudioCache.swift       Chunked fetch → seekable .m4a cache → beat analysis (cached per song)
    BeatAnalyzer.swift     Tempo, beat grid, confidence, silent edges (Accelerate)
    TransitionPlanner.swift  Pure blend planning
    MixEngine.swift        Two-deck AVAudioEngine player that performs blends
  Platform.swift         iPhone/Mac differences (audio session, toolbar placements, web view, AirPlay)
  Views/                 Home, Explore and page views, Search, Library, players, shared components
LumoMac/
  LumoMacApp.swift       Mac app entry, Controls menu + shortcuts, Space for play/pause, Settings window
  MacRootView.swift      Sidebar layout, bottom player bar, lyrics / Up Next inspector panel
LumoTests/               Unit tests, including parsing against captured YouTube Music responses
```

## Data from YouTube

- **Browse and search** use YouTube Music's internal API through SwiftyTube's `InnerTubeClient`. Responses are raw JSON; `Parse` walks the tree for known renderers (`musicResponsiveListItemRenderer`, `musicTwoRowItemRenderer`, `musicCardShelfRenderer`, `playlistPanelVideoRenderer`…). It skips anything it doesn't recognise, so layout changes degrade to "nothing here" rather than crashes.
- **Search filters** come from the chips in YouTube's own response, so Lumo doesn't hard-code filter tokens.
- **Up Next** uses YouTube's Mix (`next` with playlist `RD<videoId>` on the YouTube client), falling back to YouTube Music radio (`RDAMVM<videoId>`). Lumo filters out disliked, often-skipped and recently played songs.
- **Streams:** SwiftyTube walks several client identities, mints PoTokens and deciphers signatures when needed. Lumo always requests AAC (AVFoundation can't decode Opus).

## Playback and AutoMix

1. **Fetch:** googlevideo throttles single large GETs, so `AudioCache.fetch` downloads in parallel 1 MB `Range` requests.
2. **Prepare:** the fragmented MP4 is rewrapped (passthrough, no re-encode) into a seekable `.m4a` and cached. The cache is capped at 500 MB, evicting the least recently used.
3. **Analyse:** `BeatAnalyzer` reads an ~11 kHz mono downmix and computes:
   - spectral-flux onsets
   - tempo by autocorrelation (70–180 BPM)
   - beat phase
   - confidence
   - silent intro and outro

   Results are cached as JSON. Tracks over 15 minutes skip analysis.
4. **Plan:** `TransitionPlanner` picks where to start the blend (on a beat of the outgoing song), how long (32 beats, 6–16 s, or a 12 s crossfade), where the next song starts (its first beat after silence) and the tempo ratio. Tempo is matched only within ±8%, with half/double folding, and when both songs have confidence ≥ 0.2.
5. **Play:** `MixEngine` has two decks, each `AVAudioPlayerNode → AVAudioUnitTimePitch → AVAudioUnitEQ (low shelf)`.
   - A 60 Hz step starts the incoming deck exactly when the outgoing song reaches the planned beat.
   - Volumes follow an equal-power crossfade, the bass swaps at the midpoint, and the tempo eases back to 1.0 after the blend.
   - A watchdog force-starts the incoming deck if it ever fails to render.

## Android

`android/` is a native Kotlin app (Jetpack Compose, Media3, Room) with the same features. Its pure logic is a line-by-line port of the Swift code above, and its unit tests are ports of the Swift tests with the same inputs and expected values.

```
android/
  innertube/                    YouTube Music API layer copied from Mochy-Music (GPL-3.0)
  app/src/main/java/com/nagashreeshyl/lumo/
    data/Repo.kt                YouTube → Lumo models (search, home, explore, pages, Mix)
    data/Library.kt, db/        Room: tracks (likes, plays, skips, downloads, Not Interested), playlists
    data/LibraryBackup.kt       Same JSON as Backup.swift, so backups move between iPhone, Mac and Android
    player/StreamResolver.kt    Stream URLs: VISIONOS / Android VR / iOS clients, NewPipe fallback
    player/PlayerController.kt  Port of Player.swift: queue, Up Next, mood guard, listening signals
    player/PlaybackService.kt   Media3 session: notification, lock screen, Bluetooth
    audio/                      MixEngine (two ExoPlayer decks), BeatAnalyzer, TransitionPlanner, AudioCache
    lyrics/                     LyricsPlus → LRCLIB, LRC parsing, romanization (ICU)
    ui/                         Compose screens
```

Differences from the Apple apps:

- ExoPlayer plays Opus, so there's no AAC-only format choice.
- The bass swap uses each deck's Android `Equalizer`. Phones that refuse it blend without the swap.
- Analysis downloads the song as parallel 1 MB ranges (googlevideo throttles one connection to playback speed), then decodes with `MediaCodec`.

## Tests

`xcodebuild test` covers:
- parsing, including fixtures captured from real YouTube Music responses in `LumoTests/Fixtures`
- the planner
- the beat analyzer, on synthetic click tracks
- queue and shuffle
- lyrics parsing and romanization
- the cache
- a real-engine blend test in the simulator
