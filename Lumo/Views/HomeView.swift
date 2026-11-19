import SwiftData
import SwiftUI

struct HomeView: View {
    @Environment(Player.self) private var player
    @Environment(Auth.self) private var auth
    @Environment(\.modelContext) private var context
    @State private var shelves: [Shelf] = []
    @State private var more: [BrowseSection] = []   // YouTube's album / playlist / mix shelves
    @State private var loading = true
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 28) {
                    if loading && shelves.isEmpty {
                        VStack(alignment: .leading, spacing: 12) { SkeletonRows(count: 5) }.padding(.horizontal)
                    } else if shelves.isEmpty {
                        ContentUnavailableView {
                            Label("Nothing to show yet", systemImage: "wifi.exclamationmark")
                        } description: {
                            Text("Check your connection, then pull down to try again.")
                        }
                        .padding(.top, 80)
                    }
                    ForEach(Array(shelves.map { Shelf(title: $0.title, songs: player.visible($0.songs)) }.filter { !$0.songs.isEmpty }.enumerated()), id: \.element.id) { i, shelf in
                        if i == 0 && shelf.title == "Quick picks" {
                            QuickPicks(shelf: shelf)
                        } else {
                            CardShelf(shelf: shelf)
                        }
                    }
                    if !more.isEmpty { SectionsView(sections: more) }
                }
                .padding(.vertical)
                .animation(.smooth, value: shelves.map(\.id))
            }
            .navigationTitle(greeting)
            .toolbar {
                #if os(iOS)
                ToolbarItem(placement: .leadingBar) {
                    HStack(spacing: 8) {
                        Image("Logo").resizable().frame(width: 28, height: 28).clipShape(.rect(cornerRadius: 7))
                        Text("Lumo").font(.title3.weight(.heavy)).fontDesign(.rounded).lineLimit(1)
                    }
                    .fixedSize()
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Lumo")
                }
                .sharedBackgroundVisibility(.hidden)
                #endif
                ToolbarItem(placement: .trailingBar) {
                    #if os(macOS)
                    // Mac: the Lumo mark lives in the sidebar; account & settings is the Settings window (⌘,).
                    SettingsLink { Image(systemName: "person.crop.circle") }
                        .help("Account and settings (⌘,)")
                    #else
                    Button { showSettings = true } label: {
                        if let url = auth.account?.thumbnailUrl.flatMap({ URL(string: $0) }) {
                            AsyncImage(url: url) { $0.resizable() } placeholder: { Color.secondary }
                                .frame(width: 32, height: 32).clipShape(.circle)
                        } else {
                            Image(systemName: "person.crop.circle")
                        }
                    }
                    .accessibilityLabel("Account and settings")
                    #endif
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .pageDestinations()
            .refreshable { await load() }
            .task(id: auth.isSignedIn) { await load() }
        }
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: .now) {
        case 5..<12: "Good morning"
        case 12..<17: "Good afternoon"
        case 17..<22: "Good evening"
        default: "Late night"
        }
    }

    private static let cacheURL = URL.cachesDirectory.appending(path: "home.json")

    private struct Cache: Codable { var shelves: [Shelf]; var more: [BrowseSection] }

    private func load() async {
        // Last Home shows instantly while the fresh one loads.
        if shelves.isEmpty, let data = try? Data(contentsOf: Self.cacheURL),
           let cached = try? JSONDecoder().decode(Cache.self, from: data) {
            shelves = cached.shelves
            more = cached.more
        }
        loading = true
        var latest = Cache(shelves: [], more: [])
        await Recommender.home(context: context) { fresh, extra in
            latest = Cache(shelves: fresh, more: extra)
            withAnimation(.smooth) { shelves = fresh; more = extra }
        }
        loading = false
        if !latest.shelves.isEmpty, let data = try? JSONEncoder().encode(latest) { try? data.write(to: Self.cacheURL) }
    }
}

/// YouTube Music-style grid: 4 rows per column, paging horizontally.
private struct QuickPicks: View {
    let shelf: Shelf
    @Environment(Player.self) private var player

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ShelfTitle(title: shelf.title) { player.play(shelf.songs) }
            #if os(macOS)
            // Mac: columns that fit the window exactly (no half-cut column at the edge).
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 300), spacing: 24, alignment: .leading)], alignment: .leading, spacing: 8) {
                ForEach(shelf.songs.prefix(12)) { song in
                    SongRow(song: song) { player.play([song]) }   // a station from this song keeps its mood
                }
            }
            .padding(.horizontal)
            #else
            ScrollView(.horizontal) {
                LazyHGrid(rows: Array(repeating: GridItem(.fixed(56), spacing: 8), count: 4), spacing: 16) {
                    ForEach(shelf.songs) { song in
                        SongRow(song: song) { player.play([song]) }   // a station from this song keeps its mood
                            .containerRelativeFrame(.horizontal) { w, _ in w * 0.86 }
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.viewAligned)
            .contentMargins(.horizontal, 16, for: .scrollContent)
            .scrollIndicators(.hidden)
            #endif
        }
    }
}

private struct CardShelf: View {
    let shelf: Shelf
    @Environment(Player.self) private var player

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ShelfTitle(title: shelf.title) { player.play(shelf.songs) }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(Array(shelf.songs.enumerated()), id: \.element.id) { i, song in
                        Button { player.play([song]) } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Artwork(song: song, size: 150, radius: 10)
                                Text(song.title).font(.subheadline.weight(.medium)).lineLimit(1)
                                Text(song.artist).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                            }
                            .frame(width: 150, alignment: .leading)
                        }
                        .buttonStyle(PressScale())
                        .contextMenu { SongMenu(song: song) }
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.viewAligned)
            .contentMargins(.horizontal, 16, for: .scrollContent)
            .scrollIndicators(.hidden)
        }
    }
}

private struct ShelfTitle: View {
    let title: String
    var playAll: () -> Void
    var body: some View {
        HStack {
            Text(title).font(.title2.bold()).lineLimit(1)
            Spacer()
            Button("Play all", systemImage: "play.fill", action: playAll)
                .labelStyle(.iconOnly)
                .font(.body.weight(.semibold))
                .frame(width: 44, height: 44)
                .accessibilityLabel("Play all of \(title)")
        }
        .padding(.leading)
        .padding(.trailing, 8)
    }
}

/// Subtle press-down for tappable cards.
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.spring(duration: 0.25), value: configuration.isPressed)
    }
}
