import AVKit
import SwiftData
import SwiftUI

/// Sits in the tab bar's accessory slot (iOS 26), like the system Music app.
struct MiniPlayer: View {
    var open: () -> Void
    @Environment(Player.self) private var player

    var body: some View {
        HStack(spacing: 10) {
            Artwork(song: player.current, size: 32, radius: 6)
            VStack(alignment: .leading, spacing: 0) {
                Text(player.current?.title ?? "Not Playing").font(.subheadline.weight(.medium)).lineLimit(1)
                if let error = player.errorMessage {
                    Text(error).font(.caption).foregroundStyle(.red).lineLimit(1)
                } else if player.isMixing {
                    MixingLabel().font(.caption.weight(.semibold))
                } else if let artist = player.current?.artist {
                    Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentTransition(.opacity)
            .animation(.smooth, value: player.current?.id)
            Button { player.toggle() } label: {
                Group {
                    if player.isLoading { ProgressView() }
                    else { Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").contentTransition(.symbolEffect(.replace)) }
                }
                .frame(width: 44, height: 44)
            }
            .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
            .disabled(player.current == nil)
            Button { player.next() } label: {
                Image(systemName: "forward.fill").frame(width: 44, height: 44)
            }
            .accessibilityLabel("Next song")
            .disabled(player.upNext.isEmpty)
        }
        .font(.title3)
        .foregroundStyle(.primary)
        .padding(.leading, 12)
        .contentShape(.rect)
        .onTapGesture { if player.current != nil { open() } }
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens the player")
    }
}

struct FullPlayerView: View {
    enum Panel: String { case artwork, lyrics, queue }
    #if DEBUG
    static var initialPanel: Panel { ProcessInfo.processInfo.environment["NIVE_PANEL"].flatMap(Panel.init(rawValue:)) ?? .artwork }
    #else
    static let initialPanel = Panel.artwork
    #endif
    @Environment(Player.self) private var player
    @Environment(Downloads.self) private var downloads
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var panel = FullPlayerView.initialPanel
    @State private var swipe: CGFloat = 0
    @State private var likeTick = 0

    var body: some View {
        let song = player.current
        VStack(spacing: 0) {
            Capsule().fill(.white.opacity(0.4)).frame(width: 36, height: 5).padding(.top, 8)
                .accessibilityHidden(true)

            Group {
                switch panel {
                case .artwork: artwork(song)
                case .lyrics: LyricsPanel()
                case .queue: QueuePanel()
                }
            }
            .frame(maxHeight: .infinity)
            .clipped()  // keep the queue list from sliding under the title row
            .padding(.bottom, 16)
            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition(.blurReplace))

            VStack(spacing: 22) {
                titleRow(song)
                Scrubber()
                transport
                bottomBar
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 12)
        }
        .foregroundStyle(.white)
        .background { AmbientBackground(song: song) }
        .preferredColorScheme(.dark)
        .gesture(DragGesture(minimumDistance: 30).onEnded { if $0.translation.height > 120 { dismiss() } })
        .sensoryFeedback(.impact(weight: .light), trigger: likeTick)
        .onChange(of: player.current == nil) { if $1 { dismiss() } }
    }

    // Artwork shrinks while paused, swipes for next/previous.
    private func artwork(_ song: Song?) -> some View {
        GeometryReader { geo in
            let side = min(geo.size.width - 56, geo.size.height - 24)
            Artwork(song: song, size: side, radius: 14)
                .shadow(color: .black.opacity(0.35), radius: 24, y: 14)
                .scaleEffect(player.isPlaying ? 1 : 0.86)
                .offset(x: swipe)
                .animation(reduceMotion ? .easeOut(duration: 0.2) : .spring(duration: 0.5, bounce: 0.3), value: player.isPlaying)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .gesture(DragGesture()
                    .onChanged { swipe = $0.translation.width * 0.6 }
                    .onEnded { v in
                        if v.translation.width < -80 { player.next() } else if v.translation.width > 80 { player.previous() }
                        withAnimation(.spring(duration: 0.35)) { swipe = 0 }
                    })
                .id(song?.id)
                .transition(reduceMotion ? .opacity : .asymmetric(insertion: .scale(scale: 0.9).combined(with: .opacity), removal: .opacity))
                .animation(.smooth, value: song?.id)
        }
        .accessibilityElement()
        .accessibilityLabel(song.map { "\($0.title) by \($0.artist)" } ?? "Nothing playing")
        .accessibilityHint("Swipe left or right to change songs")
    }

    private func titleRow(_ song: Song?) -> some View {
        let liked = song.flatMap { context.existingTrack($0.id) }?.liked == true
        return HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                if player.isMixing {
                    MixingLabel()
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(.white.opacity(0.18), in: .capsule)
                        .padding(.bottom, 4)
                        .transition(.opacity.combined(with: .scale(scale: 0.9, anchor: .leading)))
                }
                Text(song?.title ?? "").font(.title3.bold()).lineLimit(1)
                Text(song?.artist ?? "").font(.body).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentTransition(.opacity)
            .animation(.smooth, value: song?.id)
            Button {
                guard let song else { return }
                toggleLike(song, in: context)
                likeTick += 1
            } label: {
                Image(systemName: liked ? "heart.fill" : "heart")
                    .symbolEffect(.bounce, value: liked)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(liked ? "Unlike" : "Like")
            Button {
                guard let song else { return }
                likeTick += 1
                player.dislike(song)
                player.next()   // don't make them sit through a song they just disliked
            } label: {
                Image(systemName: "hand.thumbsdown").frame(width: 44, height: 44)
            }
            .accessibilityLabel("Not interested, skip")
            if let song {
                Menu { SongMenu(song: song) } label: {
                    Image(systemName: "ellipsis").frame(width: 44, height: 44)
                }
                .accessibilityLabel("More actions")
            }
        }
        .font(.title3)
    }

    private var transport: some View {
        HStack {
            Button { player.toggleShuffle() } label: {
                Image(systemName: "shuffle")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(player.shuffle ? .white : .white.opacity(0.45))
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(player.shuffle ? "Shuffle on" : "Shuffle off")
            Spacer()
            Button { player.previous() } label: { Image(systemName: "backward.fill").frame(width: 64, height: 64) }
                .accessibilityLabel("Previous")
            Spacer()
            Button { player.toggle() } label: {
                Group {
                    if player.isLoading { ProgressView().controlSize(.large).tint(.white) }
                    else { Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").contentTransition(.symbolEffect(.replace)) }
                }
                .font(.system(size: 44))
                .frame(width: 80, height: 80)
            }
            .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
            Spacer()
            Button { player.next() } label: { Image(systemName: "forward.fill").frame(width: 64, height: 64) }
                .accessibilityLabel("Next")
                .disabled(player.upNext.isEmpty && player.repeatMode != .all)
            Spacer()
            Button { player.repeatMode = player.repeatMode.next } label: {
                Image(systemName: player.repeatMode.symbol)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(player.repeatMode == .off ? .white.opacity(0.45) : .white)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(player.repeatMode == .one ? "Repeat one" : player.repeatMode == .all ? "Repeat all" : "Repeat off")
        }
        .font(.system(size: 30))
        .buttonStyle(PressScale())
    }

    private var bottomBar: some View {
        HStack {
            panelButton(.lyrics, icon: "quote.bubble", label: "Lyrics")
            Spacer()
            AirPlayButton().frame(width: 44, height: 44).accessibilityLabel("AirPlay")
            Spacer()
            panelButton(.queue, icon: "list.bullet", label: "Up Next")
        }
        .font(.title3)
    }

    private func panelButton(_ p: Panel, icon: String, label: String) -> some View {
        Button {
            withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .smooth(duration: 0.4)) { panel = panel == p ? .artwork : p }
        } label: {
            Image(systemName: icon)
                .frame(width: 44, height: 44)
                .background(panel == p ? .white.opacity(0.18) : .clear, in: .circle)
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(panel == p ? .isSelected : [])
    }
}

/// Thin capsule that grows while dragging, like the system player.
struct Scrubber: View {
    var color: Color = .white      // white over the iPhone player's artwork; .primary in the Mac player bar
    var inlineTimes = false        // Mac: 1:23 ━━━━━ -2:10 on one line
    @Environment(Player.self) private var player
    @State private var dragging: Double?

    var body: some View {
        let total = max(player.duration, 1)
        let value = dragging ?? player.currentTime
        let elapsed = Text(Duration.seconds(value), format: .time(pattern: .minuteSecond))
        let remaining = Text("-" + Duration.seconds(max(total - value, 0)).formatted(.time(pattern: .minuteSecond)))
        Group {
            if inlineTimes {
                HStack(spacing: 10) {
                    elapsed.frame(width: 38, alignment: .trailing)
                    bar(total: total, value: value)
                    remaining.frame(width: 44, alignment: .leading)
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(color.opacity(0.6))
            } else {
                VStack(spacing: 6) {
                    bar(total: total, value: value)
                    HStack { elapsed; Spacer(); remaining }
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(color.opacity(0.6))
                }
            }
        }
        .opacity(player.current == nil ? 0 : 1)   // nothing playing: no fake "-0:01"
        .accessibilityElement()
        .accessibilityLabel("Playback position")
        .accessibilityValue(Duration.seconds(value).formatted(.time(pattern: .minuteSecond)))
        .accessibilityAdjustableAction { dir in
            player.seek(to: min(max(player.currentTime + (dir == .increment ? 10 : -10), 0), total))
        }
    }

    private func bar(total: Double, value: Double) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(color.opacity(0.22))
                Capsule().fill(color.opacity(dragging == nil ? 0.8 : 1))
                    .frame(width: geo.size.width * min(value / total, 1))
            }
            .frame(height: dragging == nil ? (inlineTimes ? 4 : 6) : (inlineTimes ? 8 : 12))
            .frame(maxHeight: .infinity)
            .contentShape(.rect)
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { dragging = min(max($0.location.x / geo.size.width, 0), 1) * total }
                .onEnded { _ in if let d = dragging { player.seek(to: d) }; dragging = nil })
            .animation(.spring(duration: 0.3), value: dragging == nil)
        }
        .frame(height: inlineTimes ? 16 : 24)
    }
}

/// The song's own artwork, hugely blurred, so every song tints the player.
struct AmbientBackground: View {
    let song: Song?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Color.black
            Artwork(song: song, size: 600, radius: 0)
                .scaleEffect(2.2)
                .blur(radius: 70)
                .opacity(0.9)
                .phaseAnimator(reduceMotion ? [0.0] : [0.0, 1.0]) { v, p in
                    v.rotationEffect(.degrees(p * 25)).scaleEffect(1 + p * 0.15)
                } animation: { _ in .easeInOut(duration: 14) }
                .id(song?.id)
                .transition(.opacity.animation(.easeInOut(duration: 0.8)))
            LinearGradient(colors: [.black.opacity(0.15), .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

struct LyricsPanel: View {
    @Environment(Player.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lines: [LyricLine] = []
    @State private var romanized: [String] = []          // same order as `lines`, computed once per song
    @AppStorage("romanizeLyrics") private var showRomanized = true
    @State private var searching = false
    @State private var searchTitle = ""
    @State private var searchArtist = ""
    @State private var state = "loading"

    var body: some View {
        let active = Lyrics.activeIndex(in: lines, at: player.currentTime)
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    ForEach(lines) { line in
                        let isActive = line.id == active
                        let synced = line.time != nil
                        let text = showRomanized && romanized.indices.contains(line.id) ? romanized[line.id] : line.text
                        Text(text.isEmpty ? "♪" : text)
                            .font(.title2.bold())
                            .foregroundStyle(.white.opacity(!synced || isActive ? 1 : 0.35))
                            .blur(radius: synced && !isActive && !reduceMotion ? 1.2 : 0)
                            .scaleEffect(isActive ? 1 : 0.97, anchor: .leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(.rect)
                            .onTapGesture { if let t = line.time { player.seek(to: t) } }
                            .id(line.id)
                            .animation(.smooth(duration: 0.45), value: isActive)
                    }
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 40)
            }
            .scrollIndicators(.hidden)
            .id(player.current?.id)   // fresh scroll position per song; the old offset can sit past the new song's end
            .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.08),
                                         .init(color: .black, location: 0.85), .init(color: .clear, location: 1)],
                                 startPoint: .top, endPoint: .bottom))
            .onChange(of: active) { _, i in
                guard let i else { return }
                withAnimation(reduceMotion ? nil : .spring(duration: 0.6)) { proxy.scrollTo(i, anchor: UnitPoint(x: 0, y: 0.3)) }
            }
            .onChange(of: lines) { _, new in
                // Lyrics just arrived (or panel opened mid-song): jump to the current line, or the top.
                guard let target = Lyrics.activeIndex(in: new, at: player.currentTime) ?? new.first?.id else { return }
                proxy.scrollTo(target, anchor: UnitPoint(x: 0, y: 0.3))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)   // with no lyrics the scroll view would collapse to zero width
        .overlay(alignment: .topTrailing) {
            if !romanized.isEmpty {
                Button { withAnimation(.smooth) { showRomanized.toggle() } } label: {
                    Label(showRomanized ? "Original" : "English letters", systemImage: "character.bubble")
                        .font(.footnote.weight(.semibold))
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(.white.opacity(0.15), in: .capsule)
                }
                .padding(.trailing, 20)
                .accessibilityHint("Switches lyrics between the original script and English letters")
            }
        }
        .overlay {
            if state == "loading" { ProgressView().tint(.white) }
            else if state == "none" {
                ContentUnavailableView {
                    Label("No lyrics found", systemImage: "quote.bubble")
                } description: {
                    Text("The name on YouTube may differ from the real song name.")
                } actions: {
                    Button("Search Lyrics") {
                        searchTitle = player.current.map { Lyrics.baseTitle($0.title, artist: $0.artist) } ?? ""
                        searchArtist = player.current?.artist ?? ""
                        searching = true
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.white.opacity(0.25))
                }
            }
        }
        .alert("Search Lyrics", isPresented: $searching) {
            TextField("Song name", text: $searchTitle)
            TextField("Artist", text: $searchArtist)
            Button("Search") { Task { await load(title: searchTitle, artist: searchArtist) } }
            Button("Cancel", role: .cancel) {}
        }
        .task(id: player.current?.id) {
            guard let song = player.current else { return }
            await load(title: song.title, artist: song.artist)
        }
    }

    private func load(title: String, artist: String) async {
        guard let song = player.current else { return }
        state = "loading"
        lines = []
        romanized = []
        let query = Song(id: song.id, title: title, artist: artist, thumbnail: nil, duration: song.duration)
        var fetched = (try? await Lyrics.fetch(for: query)) ?? []
        #if DEBUG
        if ProcessInfo.processInfo.environment["NIVE_NOLYRICS"] != nil { fetched = [] }   // simulator screenshots
        #endif
        guard player.current?.id == song.id else { return }
        romanized = Lyrics.needsRomanization(fetched) ? fetched.map { Lyrics.romanize($0.text) } : []
        lines = fetched
        state = lines.isEmpty ? "none" : "ready"
    }
}

struct QueuePanel: View {
    @Environment(Player.self) private var player

    var body: some View {
        @Bindable var player = player
        List {
            Section {
                Toggle("AutoMix", systemImage: "dial.medium", isOn: $player.automix)
                Toggle("Autoplay similar songs", systemImage: "infinity", isOn: $player.autoplay)
            }
            .tint(.white.opacity(0.5))
            Section("Up Next") {
                ForEach(Array(player.upNext.enumerated()), id: \.offset) { offset, song in
                    SongRow(song: song) { player.jump(to: player.index + 1 + offset) }
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button("Not Interested", systemImage: "hand.thumbsdown", role: .destructive) {
                                withAnimation { player.notInterested(at: player.index + 1 + offset) }
                            }
                        }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .listRowBackground(Color.clear)
    }
}

struct SettingsView: View {
    @Environment(Auth.self) private var auth
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    @State private var showLogin = false

    var body: some View {
        @Bindable var player = player
        NavigationStack {
            Form {
                Section {
                    if let account = auth.account {
                        LabeledContent("Signed in as", value: account.name)
                        Button("Sign Out", role: .destructive) { Task { await auth.signOut() } }
                    } else {
                        Button("Sign in with Google") { showLogin = true }
                    }
                } header: {
                    Text("YouTube Music account")
                } footer: {
                    Text("Optional. Signing in gives you your personal YouTube Music home and mixes. Nive works fully without it.")
                }
                Section {
                    NavigationLink { StatsView() } label: { Label("Listening stats", systemImage: "chart.bar.fill") }
                }
                Section {
                    NavigationLink("Hidden songs (\(player.disliked.count))") { HiddenSongsView() }
                } footer: {
                    Text("Songs you marked Not Interested never show up anywhere in Nive.")
                }
                Section {
                    Toggle("AutoMix", isOn: $player.automix)
                } footer: {
                    Text("Blends songs into each other with matched beats, like a DJ.")
                }
                Section {
                    Toggle("Autoplay similar songs", isOn: $player.autoplay)
                }
                Section {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                    Link("Source code", destination: URL(string: "https://github.com/Nagashreeshyl/nive")!)
                } footer: {
                    Text("Nive is free and open source (GPL-3.0). Not affiliated with YouTube or Google.")
                }
            }
            .navigationTitle("Settings")
            .inlineTitle()
            #if os(iOS)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            #endif
            .sheet(isPresented: $showLogin) { LoginView() }
        }
    }
}


/// "AutoMix" with a gently animating dial, shown while two songs are blending.
struct MixingLabel: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Label("AutoMix", systemImage: "dial.high")
            .symbolEffect(.variableColor.iterative, isActive: !reduceMotion)
            .foregroundStyle(Color.accentColor)
            .accessibilityLabel("Mixing into the next song")
    }
}


/// Songs marked Not Interested, with a way to bring them back.
struct HiddenSongsView: View {
    @Environment(Player.self) private var player
    @Query(filter: #Predicate<Track> { $0.disliked }, sort: \Track.title) private var tracks: [Track]

    var body: some View {
        List {
            ForEach(tracks) { t in
                HStack(spacing: 12) {
                    Artwork(song: t.song, size: 44)
                    VStack(alignment: .leading) {
                        Text(t.title).lineLimit(1)
                        Text(t.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .swipeActions {
                    Button("Show Again", systemImage: "eye") { player.undislike(t.videoId) }.tint(.accentColor)
                }
            }
        }
        .overlay { if tracks.isEmpty { ContentUnavailableView("No hidden songs", systemImage: "hand.thumbsdown") } }
        .navigationTitle("Hidden songs")
    }
}


/// What you've listened to: totals, most played songs and artists.
struct StatsView: View {
    @Environment(Player.self) private var player
    @Query(filter: #Predicate<Track> { $0.playCount > 0 }, sort: \Track.playCount, order: .reverse) private var tracks: [Track]

    var body: some View {
        let plays = tracks.reduce(0) { $0 + $1.playCount }
        let seconds = tracks.reduce(0) { $0 + $1.listenedSeconds }
        let artists = Dictionary(grouping: tracks, by: \.artist)
            .map { (name: $0.key, plays: $0.value.reduce(0) { $0 + $1.playCount }) }
            .filter { !$0.name.isEmpty }
            .sorted { $0.plays > $1.plays }
        List {
            Section {
                HStack {
                    stat(Self.listening(seconds), "listened")
                    Divider()
                    stat("\(plays)", plays == 1 ? "play" : "plays")
                    Divider()
                    stat("\(tracks.count)", tracks.count == 1 ? "song" : "songs")
                }
                .padding(.vertical, 6)
            } footer: {
                Text("A play counts after 30 seconds of listening.")
            }
            if !tracks.isEmpty {
                Section("Most played songs") {
                    ForEach(Array(tracks.prefix(25).enumerated()), id: \.element.videoId) { i, t in
                        HStack(spacing: 12) {
                            Text("\(i + 1)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary).frame(width: 24)
                            SongRow(song: t.song) { player.play([t.song]) }
                            Text("\(t.playCount)×").font(.subheadline.monospacedDigit().weight(.semibold)).foregroundStyle(.secondary)
                                .accessibilityLabel("\(t.playCount) plays")
                        }
                    }
                }
                Section("Top artists") {
                    ForEach(Array(artists.prefix(10).enumerated()), id: \.element.name) { i, a in
                        HStack {
                            Text("\(i + 1)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary).frame(width: 24)
                            Text(a.name).lineLimit(1)
                            Spacer()
                            Text("\(a.plays) plays").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .overlay {
            if tracks.isEmpty {
                ContentUnavailableView("No listening yet", systemImage: "chart.bar",
                                       description: Text("Play some songs and your stats will show up here."))
            }
        }
        .navigationTitle("Listening stats")
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.bold().monospacedDigit()).lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    static func listening(_ seconds: Double) -> String {
        let minutes = Int(seconds / 60)
        return minutes >= 60 ? "\(minutes / 60)h \(minutes % 60)m" : "\(minutes)m"
    }
}
