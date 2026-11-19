import SwiftUI

// MARK: - Navigation

extension View {
    /// Album / artist / playlist / mood pages, reachable from any tab.
    func pageDestinations() -> some View {
        navigationDestination(for: Ref.self) { ref in
            switch ref.kind {
            case .album: AlbumView(ref: ref)
            case .artist: ArtistView(ref: ref)
            case .playlist: PlaylistPageView(ref: ref)
            case .category: CategoryView(ref: ref)
            }
        }
    }
}

/// Songs for an album or playlist, so a "Play" button can start it without opening the page.
func songs(for ref: Ref) async -> [Song] {
    switch ref.kind {
    case .album: (try? await YT.album(ref.id))?.sections.first?.songs ?? []
    case .playlist: (try? await YT.playlist(ref.id)) ?? []
    case .artist: (try? await YT.artist(ref.id))?.sections.first { !$0.songs.isEmpty }?.songs ?? []
    case .category: []
    }
}

// MARK: - Rows & cards

/// A search / list row for an album, artist or playlist.
struct PageRow: View {
    let ref: Ref
    var body: some View {
        NavigationLink(value: ref) {
            HStack(spacing: 12) {
                RefArtwork(ref: ref, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ref.title).font(.body).lineLimit(1)
                    Text(ref.subtitle.isEmpty ? ref.kind.label : ref.subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
    }
}

struct RefArtwork: View {
    let ref: Ref
    let size: CGFloat
    var body: some View {
        AsyncImage(url: ref.artwork(Int(size * 3))) { phase in
            if let image = phase.image { image.resizable().scaledToFill() } else {
                Rectangle().fill(.quaternary).overlay { Image(systemName: ref.kind.symbol).foregroundStyle(.tertiary) }
            }
        }
        .frame(width: size, height: size)
        .clipShape(ref.kind == .artist ? AnyShape(.circle) : AnyShape(.rect(cornerRadius: size > 100 ? 10 : 6)))
        .accessibilityHidden(true)
    }
}

extension Ref.Kind {
    var label: String { switch self { case .album: "Album"; case .artist: "Artist"; case .playlist: "Playlist"; case .category: "Mood" } }
    var symbol: String { switch self { case .album: "square.stack"; case .artist: "person.fill"; case .playlist: "music.note.list"; case .category: "sparkles" } }
}

/// Horizontal shelf of cards for any items (songs play, pages open).
struct ItemCarousel: View {
    let section: BrowseSection
    @Environment(Player.self) private var player

    var body: some View {
        let section = BrowseSection(title: section.title, items: player.visible(section.items))
        VStack(alignment: .leading, spacing: 12) {
            if !section.title.isEmpty {
                Text(section.title).font(.title2.bold()).padding(.horizontal).accessibilityAddTraits(.isHeader)
            }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(section.items) { item in card(item) }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.viewAligned)
            .contentMargins(.horizontal, 16, for: .scrollContent)
            .scrollIndicators(.hidden)
        }
    }

    @ViewBuilder private func card(_ item: Item) -> some View {
        switch item {
        case .song(let song):
            Button { player.play([song]) } label: {
                CardLabel(title: song.title, subtitle: song.artist) { Artwork(song: song, size: 150, radius: 10) }
            }
            .buttonStyle(PressScale())
            .contextMenu { SongMenu(song: song) }
        case .page(let ref) where ref.kind == .category:
            NavigationLink(value: ref) { MoodTile(ref: ref).frame(width: 160) }.buttonStyle(PressScale())
        case .page(let ref):
            NavigationLink(value: ref) {
                CardLabel(title: ref.title, subtitle: ref.subtitle) { RefArtwork(ref: ref, size: 150) }
            }
            .buttonStyle(PressScale())
        }
    }
}

private struct CardLabel<Art: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder var art: Art
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            art
            Text(title).font(.subheadline.weight(.medium)).lineLimit(1).foregroundStyle(.primary)
            Text(subtitle).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(width: 150, alignment: .leading)
    }
}

/// Mood / genre tile: YouTube's color as a two-tone gradient, a big tilted symbol for the mood, soft depth.
struct MoodTile: View {
    let ref: Ref
    var body: some View {
        let c = ref.color ?? 0xFF7A5CFF
        let base = Color(red: Double((c >> 16) & 0xFF) / 255, green: Double((c >> 8) & 0xFF) / 255, blue: Double(c & 0xFF) / 255)
        ZStack(alignment: .topLeading) {
            LinearGradient(colors: [base, base.mix(with: .black, by: 0.45)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Image(systemName: Self.symbol(for: ref.title))
                .font(.system(size: 54, weight: .semibold))
                .foregroundStyle(.white.opacity(0.28))
                .rotationEffect(.degrees(18))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .offset(x: 10, y: 10)
            Text(ref.title)
                .font(.headline)
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
                .padding(12)
        }
        .frame(height: 92)
        .clipShape(.rect(cornerRadius: 14))
        .shadow(color: base.opacity(0.35), radius: 8, y: 4)
        .accessibilityElement(children: .combine)
    }

    static func symbol(for title: String) -> String {
        let t = title.lowercased()
        let map: [(String, String)] = [
            ("chill", "leaf.fill"), ("sleep", "moon.stars.fill"), ("focus", "scope"), ("workout", "figure.run"),
            ("energ", "bolt.fill"), ("party", "party.popper.fill"), ("romance", "heart.fill"), ("love", "heart.fill"),
            ("feel good", "sun.max.fill"), ("sad", "cloud.rain.fill"), ("commute", "car.fill"), ("travel", "airplane"),
            ("devotional", "sparkles"), ("diwali", "sparkles"), ("festive", "sparkles"), ("family", "figure.2.and.child.holdinghands"),
            ("classical", "music.quarternote.3"), ("carnatic", "music.quarternote.3"), ("jazz", "music.mic"), ("rock", "guitars.fill"),
            ("metal", "guitars.fill"), ("pop", "star.fill"), ("hip", "headphones"), ("rap", "headphones"), ("dance", "figure.dance"),
            ("electronic", "waveform"), ("edm", "waveform"), ("indie", "music.note.house.fill"), ("folk", "leaf"),
            ("country", "sun.horizon.fill"), ("bollywood", "film.fill"), ("film", "film.fill"), ("ghazal", "music.mic"),
            ("kids", "teddybear.fill"), ("gaming", "gamecontroller.fill"), ("new releases", "sparkles"), ("charts", "chart.bar.fill"),
            ("moods", "face.smiling.fill"),
        ]
        return map.first { t.contains($0.0) }?.1 ?? "music.note"
    }
}

/// Explore's top shortcuts (New releases · Charts · Moods & genres) as compact icon buttons.
struct ShortcutRow: View {
    let refs: [Ref]
    var body: some View {
        HStack(spacing: 10) {
            ForEach(refs, id: \.self) { ref in
                NavigationLink(value: ref) {
                    VStack(spacing: 8) {
                        Image(systemName: MoodTile.symbol(for: ref.title))
                            .font(.title2)
                            .foregroundStyle(Color.accentColor)
                            .frame(height: 28)
                        Text(ref.title).font(.footnote.weight(.semibold)).foregroundStyle(.primary).lineLimit(1).minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(.fill.tertiary, in: .rect(cornerRadius: 14))
                }
                .buttonStyle(PressScale())
            }
        }
        .padding(.horizontal)
    }
}

/// Any list of sections: song lists as rows, pages as card shelves, moods as a tile grid.
struct SectionsView: View {
    let sections: [BrowseSection]
    @Environment(Player.self) private var player

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 28) {
            ForEach(sections) { section in
                let moods = section.items.allSatisfy { if case .page(let r) = $0 { r.kind == .category } else { false } }
                if moods && section.title.isEmpty && section.items.count <= 4 {
                    ShortcutRow(refs: section.items.compactMap { if case .page(let r) = $0 { r } else { nil } })
                } else if moods {
                    VStack(alignment: .leading, spacing: 12) {
                        if !section.title.isEmpty { Text(section.title).font(.title2.bold()) }
                        // As many columns as fit: 2 on iPhone, 4–6 on a Mac window.
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 165), spacing: 12)], spacing: 12) {
                            ForEach(section.items.prefix(12)) { item in
                                if case .page(let ref) = item { NavigationLink(value: ref) { MoodTile(ref: ref) }.buttonStyle(PressScale()) }
                            }
                        }
                    }
                    .padding(.horizontal)
                } else if section.songs.count == section.items.count, section.items.count > 4 {
                    SongList(title: section.title, songs: section.songs)
                } else {
                    ItemCarousel(section: section)
                }
            }
        }
    }
}

/// Titled vertical song list (up to 10).
struct SongList: View {
    let title: String
    let songs: [Song]
    var numbered = false
    @Environment(Player.self) private var player

    var body: some View {
        let songs = player.visible(songs)
        VStack(alignment: .leading, spacing: 10) {
            if !title.isEmpty { Text(title).font(.title2.bold()).padding(.horizontal).accessibilityAddTraits(.isHeader) }
            ForEach(Array(songs.prefix(numbered ? songs.count : 10).enumerated()), id: \.offset) { i, song in
                HStack(spacing: 10) {
                    if numbered {
                        Text("\(i + 1)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary).frame(width: 22)
                    }
                    SongRow(song: song) { player.play(songs, at: i) }
                }
                .padding(.horizontal)
            }
        }
    }
}

// MARK: - Pages

struct AlbumView: View {
    let ref: Ref
    @State private var page: BrowsePage?
    @State private var failed = false

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                PageHeader(title: page?.header?.title ?? ref.title, subtitle: page?.header?.subtitle ?? ref.subtitle,
                           artwork: page?.header?.artwork(720) ?? ref.artwork(720), round: false,
                           songs: page?.sections.first?.songs ?? [], saveName: page?.header?.title ?? ref.title)
                if let page {
                    if let tracks = page.sections.first { SongList(title: "", songs: tracks.songs, numbered: true) }
                    SectionsView(sections: Array(page.sections.dropFirst()))
                } else if !failed {
                    SkeletonRows().padding(.horizontal)
                }
            }
            .padding(.bottom, 24)
        }
        .overlay { if failed { ContentUnavailableView("Couldn't load this album", systemImage: "wifi.exclamationmark") } }
        .navigationTitle(ref.title).inlineTitle()
        .task { if page == nil { page = try? await YT.album(ref.id); failed = page == nil } }
    }
}

struct ArtistView: View {
    let ref: Ref
    @State private var page: BrowsePage?
    @State private var failed = false

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                let top = page?.sections.first { !$0.songs.isEmpty }
                PageHeader(title: page?.header?.title ?? ref.title, subtitle: ref.subtitle,
                           artwork: page?.header?.artwork(720) ?? ref.artwork(720), round: true, songs: top?.songs ?? [])
                if let page {
                    SectionsView(sections: page.sections)
                } else if !failed {
                    SkeletonRows().padding(.horizontal)
                }
            }
            .padding(.bottom, 24)
        }
        .overlay { if failed { ContentUnavailableView("Couldn't load this artist", systemImage: "wifi.exclamationmark") } }
        .navigationTitle(ref.title).inlineTitle()
        .task { if page == nil { page = try? await YT.artist(ref.id); failed = page == nil } }
    }
}

struct PlaylistPageView: View {
    let ref: Ref
    @State private var songs: [Song]?

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                PageHeader(title: ref.title, subtitle: ref.subtitle, artwork: ref.artwork(720), round: false, songs: songs ?? [], saveName: ref.title)
                if let songs { SongList(title: "", songs: songs, numbered: true) } else { SkeletonRows().padding(.horizontal) }
            }
            .padding(.bottom, 24)
        }
        .overlay { if songs?.isEmpty == true { ContentUnavailableView("This playlist is empty", systemImage: "music.note.list") } }
        .navigationTitle(ref.title).inlineTitle()
        .task { if songs == nil { songs = (try? await YT.playlist(ref.id)) ?? [] } }
    }
}

struct CategoryView: View {
    let ref: Ref
    @State private var page: BrowsePage?

    var body: some View {
        ScrollView {
            if let page { SectionsView(sections: page.sections).padding(.vertical) } else { SkeletonRows().padding() }
        }
        .navigationTitle(ref.title)
        .task { if page == nil { page = try? await YT.category(ref) } }
    }
}

/// Big artwork, title, subtitle, Play / Shuffle.
private struct PageHeader: View {
    let title: String
    let subtitle: String
    let artwork: URL?
    let round: Bool
    let songs: [Song]
    var saveName: String? = nil   // set on albums and playlists: shows Save to Library
    @Environment(Player.self) private var player
    @Environment(\.modelContext) private var context
    @State private var saved = false

    var body: some View {
        VStack(spacing: 14) {
            AsyncImage(url: artwork) { phase in
                if let image = phase.image { image.resizable().scaledToFill() } else { Rectangle().fill(.quaternary) }
            }
            .frame(width: 230, height: 230)
            .clipShape(round ? AnyShape(.circle) : AnyShape(.rect(cornerRadius: 14)))
            .shadow(color: .black.opacity(0.25), radius: 18, y: 10)
            .accessibilityHidden(true)
            VStack(spacing: 4) {
                Text(title).font(.title2.bold()).multilineTextAlignment(.center)
                if !subtitle.isEmpty { Text(subtitle).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center) }
            }
            .padding(.horizontal)
            HStack(spacing: 12) {
                Button { player.play(songs) } label: { Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity) }
                Button { player.play(songs.shuffled()) } label: { Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .font(.body.weight(.semibold))
            .disabled(songs.isEmpty)
            .padding(.horizontal)
            if let saveName, !songs.isEmpty {
                Button {
                    context.savePlaylist(name: saveName, songs: songs)
                    saved = true
                } label: {
                    Label(saved ? "Saved to Library" : "Save to Library", systemImage: saved ? "checkmark" : "plus.rectangle.on.rectangle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered).controlSize(.large)
                .disabled(saved)
                .sensoryFeedback(.success, trigger: saved)
                .padding(.horizontal)
            }
        }
        .padding(.top, 8)
    }
}

// MARK: - Explore tab

struct ExploreView: View {
    @State private var sections: [BrowseSection] = []
    @State private var failed = false

    var body: some View {
        NavigationStack {
            ScrollView {
                if sections.isEmpty && !failed {
                    SkeletonRows().padding()
                } else {
                    SectionsView(sections: sections.filter { !$0.title.localizedCaseInsensitiveContains("episode") }).padding(.vertical)
                }
            }
            .overlay {
                if failed {
                    ContentUnavailableView("Explore isn't loading", systemImage: "wifi.exclamationmark",
                                           description: Text("Pull down to try again."))
                }
            }
            .refreshable { await load() }
            .navigationTitle("Explore")
            .pageDestinations()
            .task { if sections.isEmpty { await load() } }
        }
    }

    private func load() async {
        // Top buttons (New releases, Charts, Moods, Podcasts) become tiles; podcasts aren't part of Lumo.
        let all = (try? await YT.explore()) ?? []
        sections = all.map { s in
            BrowseSection(title: s.title, items: s.items.filter { if case .page(let r) = $0 { r.id != "FEmusic_non_music_audio" } else { true } })
        }
        failed = sections.isEmpty
    }
}
