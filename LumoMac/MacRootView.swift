import SwiftData
import SwiftUI

/// Apple Music-style Mac window: sidebar, content, player bar, lyrics / Up Next panel.
struct MacRootView: View {
    enum Page: Hashable { case home, explore, search, liked, downloads, stats, playlist(PersistentIdentifier) }
    enum Panel: String, CaseIterable { case lyrics = "Lyrics", queue = "Up Next" }

    @Environment(Player.self) private var player
    @Environment(\.modelContext) private var context
    @Query(sort: \Playlist.createdAt, order: .reverse) private var playlists: [Playlist]
    #if DEBUG
    @State private var page: Page? = ProcessInfo.processInfo.environment["LUMO_PAGE"] == "search" ? .search
        : ProcessInfo.processInfo.environment["LUMO_PAGE"] == "explore" ? .explore : .home   // screenshots
    #else
    @State private var page: Page? = .home
    #endif
    @State private var panel: Panel?
    @State private var renaming: Playlist?
    #if DEBUG
    @State private var debugLogin = ProcessInfo.processInfo.environment["LUMO_LOGIN"] != nil   // screenshots
    #endif

    var body: some View {
        NavigationSplitView {
            List(selection: $page) {
                Section {
                    Label("Home", systemImage: "house").tag(Page.home)
                    Label("Explore", systemImage: "safari").tag(Page.explore)
                    Label("Search", systemImage: "magnifyingglass").tag(Page.search)
                }
                Section("Library") {
                    Label("Liked Songs", systemImage: "heart").tag(Page.liked)
                    Label("Downloads", systemImage: "arrow.down.circle").tag(Page.downloads)
                    Label("Listening Stats", systemImage: "chart.bar").tag(Page.stats)
                }
                Section("Playlists") {
                    ForEach(playlists) { list in
                        Label(list.name, systemImage: "music.note.list").tag(Page.playlist(list.persistentModelID))
                            .contextMenu {
                                Button("Rename…") { renaming = list }
                                Button("Delete Playlist", role: .destructive) { context.delete(list) }
                            }
                    }
                    // A normal row, aligned with the rest of the sidebar (not a button jammed into the header).
                    Button {
                        let list = Playlist(name: "Playlist \(playlists.count + 1)")
                        context.insert(list)
                        page = .playlist(list.persistentModelID)
                    } label: {
                        Label("New Playlist", systemImage: "plus")
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                HStack(spacing: 10) {
                    Image("Logo").resizable().scaledToFit().frame(width: 30, height: 30).clipShape(.rect(cornerRadius: 7))
                    Text("Lumo").font(.title2.weight(.heavy)).fontDesign(.rounded)
                    Spacer()
                }
                .padding(.horizontal, 18)
                .padding(.top, 6)
                .padding(.bottom, 10)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Lumo")
            }
            .navigationSplitViewColumnWidth(min: 190, ideal: 220, max: 280)
        } detail: {
            detail
        }
        .renamePlaylist($renaming)
        #if DEBUG
        .sheet(isPresented: $debugLogin) { LoginView() }
        #endif
        // A deleted playlist that was open: go back Home instead of showing "not found".
        .onChange(of: playlists.map(\.persistentModelID)) { _, ids in
            if case .playlist(let id) = page, !ids.contains(id) { page = .home }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { PlayerBar(panel: $panel) }
        .inspector(isPresented: Binding(get: { panel != nil }, set: { if !$0 { panel = nil } })) {
            InspectorPanel(panel: $panel)
                .inspectorColumnWidth(min: 300, ideal: 380, max: 520)
        }
    }

    @ViewBuilder private var detail: some View {
        switch page ?? .home {
        case .home: HomeView()
        case .explore: ExploreView()
        case .search: SearchView()
        case .liked: NavigationStack { TrackList(kind: .liked).pageDestinations() }
        case .downloads: NavigationStack { TrackList(kind: .downloads).pageDestinations() }
        case .stats: NavigationStack { StatsView() }
        case .playlist(let id):
            if let list = playlists.first(where: { $0.persistentModelID == id }) {
                NavigationStack { PlaylistDetail(playlist: list).pageDestinations() }
            } else {
                ContentUnavailableView("Playlist not found", systemImage: "music.note.list")
            }
        }
    }
}

/// Bottom bar: now playing, transport + progress, and actions.
struct PlayerBar: View {
    @Binding var panel: MacRootView.Panel?
    @Environment(Player.self) private var player
    @Environment(\.modelContext) private var context

    var body: some View {
        @Bindable var player = player
        let song = player.current
        let liked = song.flatMap { context.existingTrack($0.id) }?.liked == true
        HStack(spacing: 18) {
            HStack(spacing: 12) {
                Artwork(song: song, size: 52, radius: 8)
                VStack(alignment: .leading, spacing: 2) {
                    if player.isMixing { MixingLabel().font(.caption.weight(.semibold)) }
                    Text(song?.title ?? "Not Playing").font(.headline).lineLimit(1)
                    if let error = player.errorMessage {
                        Text(error).font(.subheadline).foregroundStyle(.red).lineLimit(1)
                    } else {
                        Text(song?.artist ?? " ").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
            .frame(width: 290, alignment: .leading)
            .animation(.smooth, value: player.isMixing)

            Spacer(minLength: 12)

            VStack(spacing: 4) {
                HStack(spacing: 26) {
                    barButton("shuffle", on: player.shuffle, help: "Shuffle") { player.toggleShuffle() }
                    barButton("backward.fill", help: "Previous (⌘←)") { player.previous() }
                    Button { player.toggle() } label: {
                        Group {
                            if player.isLoading { ProgressView().controlSize(.small) }
                            else { Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill").contentTransition(.symbolEffect(.replace)) }
                        }
                        .font(.system(size: 34))
                        .frame(width: 40, height: 40)
                    }
                    .buttonStyle(.plain)
                    .help("Play / Pause (Space)")
                    barButton("forward.fill", help: "Next (⌘→)") { player.next() }
                        .disabled(player.upNext.isEmpty && player.repeatMode != .all)
                    barButton(player.repeatMode.symbol, on: player.repeatMode != .off, help: "Repeat (⌘R)") { player.repeatMode = player.repeatMode.next }
                }
                Scrubber(color: .primary, inlineTimes: true).frame(maxWidth: 560)
            }
            .frame(maxWidth: 560)

            Spacer(minLength: 12)

            HStack(spacing: 14) {
                barButton(liked ? "heart.fill" : "heart", on: liked, help: liked ? "Unlike" : "Like") {
                    if let song { toggleLike(song, in: context) }
                }
                barButton("hand.thumbsdown", help: "Not interested, skip") {
                    if let song { player.dislike(song); player.next() }
                }
                barButton("quote.bubble", on: panel == .lyrics, help: "Lyrics") { panel = panel == .lyrics ? nil : .lyrics }
                barButton("list.bullet", on: panel == .queue, help: "Up Next") { panel = panel == .queue ? nil : .queue }
                AirPlayButton().frame(width: 22, height: 22).help("AirPlay")
                HStack(spacing: 6) {
                    Image(systemName: "speaker.fill").font(.caption).foregroundStyle(.secondary)
                    Slider(value: $player.volume, in: 0...1).frame(width: 90).controlSize(.small)
                }
            }
            .frame(width: 290, alignment: .trailing)
            .disabled(song == nil)
        }
        .padding(.horizontal, 18)
        .frame(height: 84)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }

    private func barButton(_ symbol: String, on: Bool = false, help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(on ? Color.accentColor : .primary)
                .frame(width: 26, height: 26)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .help(help)
        .accessibilityLabel(help)
    }
}

/// Right-side panel: lyrics or Up Next over the song's blurred artwork.
struct InspectorPanel: View {
    @Binding var panel: MacRootView.Panel?
    @Environment(Player.self) private var player

    var body: some View {
        VStack(spacing: 12) {
            Picker("Panel", selection: Binding(get: { panel ?? .lyrics }, set: { panel = $0 })) {
                ForEach(MacRootView.Panel.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(.horizontal, 16)
            .padding(.top, 12)
            if player.current == nil {
                ContentUnavailableView("Nothing playing", systemImage: "music.note")
            } else if panel == .queue {
                QueuePanel()
            } else {
                LyricsPanel()
            }
        }
        .foregroundStyle(.white)
        .background { AmbientBackground(song: player.current) }
        .environment(\.colorScheme, .dark)
    }
}
