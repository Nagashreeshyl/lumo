import SwiftUI

/// Typing shows YouTube's autocomplete (starting with your letters); tapping one or pressing Search shows songs.
struct SearchView: View {
    @Environment(Player.self) private var player
    @State private var query = ""
    @FocusState private var searchFocused: Bool
    @State private var submitted: String?          // text the user actually searched for
    @State private var suggestions: [String] = []
    @State private var page = SearchPage()
    @State private var resultsFor = ""             // the exact query+filter `page` belongs to
    @State private var filter: String?             // YouTube's filter params (Songs, Albums…); nil = All
    @State private var failed = false
    @AppStorage("recentSearches") private var recentRaw = ""

    private var recent: [String] { recentRaw.split(separator: "\n").map(String.init) }
    private var trimmed: String { query.trimmingCharacters(in: .whitespaces) }
    private var showingResults: Bool { !trimmed.isEmpty && submitted == trimmed }
    private var resultsKey: String { trimmed + "|" + (filter ?? "") }

    var body: some View {
        NavigationStack {
            List {
                if trimmed.isEmpty {
                    recentSection
                } else if !showingResults {
                    ForEach(suggestions, id: \.self) { s in
                        SuggestionRow(text: s, typed: trimmed) { submit(s) } fill: { query = s + " " }
                    }
                } else {
                    FilterChips(filters: page.filters, selected: $filter)
                        .listRowInsets(EdgeInsets())
                        .listRowSeparator(.hidden)
                    if resultsFor != resultsKey {
                        SkeletonRows()     // never show results for older text
                    } else {
                        if filter == nil, let top = page.top, !player.visible([top]).isEmpty {
                            TopResultCard(item: top) { play($0) }
                                .listRowSeparator(.hidden)
                        }
                        ForEach(player.visible(page.items)) { item in
                            switch item {
                            case .song(let song): SongRow(song: song) { play(song) }
                            case .page(let ref): PageRow(ref: ref)
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if showingResults && resultsFor == resultsKey && page.items.isEmpty && page.top == nil {
                    if failed {
                        ContentUnavailableView("Search isn't working", systemImage: "wifi.exclamationmark",
                                               description: Text("Check your connection and try again."))
                    } else {
                        ContentUnavailableView.search(text: trimmed)
                    }
                } else if trimmed.isEmpty && recent.isEmpty {
                    ContentUnavailableView("Find any song", systemImage: "magnifyingglass",
                                           description: Text("Search by song, artist, movie or lyrics."))
                }
            }
            .navigationTitle("Search")
            // Always visible and focused on arrival: tapping the Search tab should mean "start typing".
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Songs, artists, movies")
            .searchFocused($searchFocused)
            .onAppear {
                if query.isEmpty { searchFocused = true }
                #if DEBUG
                if let q = ProcessInfo.processInfo.environment["NIVE_QUERY"], query.isEmpty { query = q }   // simulator screenshots
                if let q = ProcessInfo.processInfo.environment["NIVE_SEARCH"], query.isEmpty { submit(q) }
                #endif
            }
            .onSubmit(of: .search) { submit(query) }
            // `submitted` is part of the id: pressing Search on unchanged text must still start the search.
            .task(id: query + "|" + (filter ?? "") + "|" + (submitted ?? "")) { await update() }
            .pageDestinations()
        }
    }

    @ViewBuilder private var recentSection: some View {
        if !recent.isEmpty {
            Section {
                ForEach(recent, id: \.self) { term in
                    Button { submit(term) } label: {
                        Label(term, systemImage: "clock.arrow.circlepath").foregroundStyle(.primary)
                    }
                }
            } header: {
                HStack {
                    Text("Recent")
                    Spacer()
                    Button("Clear") { recentRaw = "" }.font(.subheadline)
                }
            }
        }
    }

    private func submit(_ term: String) {
        let t = term.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        query = t
        submitted = t
        filter = nil
        searchFocused = false
        recentRaw = ([t] + recent.filter { $0 != t }).prefix(10).joined(separator: "\n")
    }

    private func update() async {
        let q = trimmed
        guard !q.isEmpty else { suggestions = []; page = SearchPage(); resultsFor = ""; return }
        if submitted == q {
            await search(q)
        } else {
            submitted = nil
            try? await Task.sleep(for: .milliseconds(150))   // debounce typing
            guard !Task.isCancelled else { return }
            let found = await YT.suggestions(q)
            if !Task.isCancelled, q == trimmed { suggestions = found.isEmpty ? [q] : found }
        }
    }

    private func search(_ q: String) async {
        let key = resultsKey, chosen = filter
        do {
            var found = try await YT.searchPage(q, filter: chosen)
            guard !Task.isCancelled, key == resultsKey else { return }
            if chosen != nil, found.filters.isEmpty { found.filters = page.filters }   // keep the chip row while filtered
            failed = false
            withAnimation(.smooth) { page = found; resultsFor = key }
        } catch {
            if !Task.isCancelled, key == resultsKey { failed = true; page = SearchPage(filters: page.filters); resultsFor = key }
        }
    }

    /// Tapping a song: play it (autoplay continues after) and show the player.
    private func play(_ song: Song) {
        searchFocused = false
        player.play([song])
        player.showFullPlayer = true
    }
}

/// One autocomplete line: typed letters normal, the completion bold (like YouTube).
private struct SuggestionRow: View {
    let text: String
    let typed: String
    var search: () -> Void
    var fill: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: search) {
                HStack(spacing: 12) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    highlighted.lineLimit(1)
                    Spacer(minLength: 0)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            Button(action: fill) {
                Image(systemName: "arrow.up.left").foregroundStyle(.secondary).frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Use \(text) and keep typing")
        }
    }

    private var highlighted: Text {
        guard text.lowercased().hasPrefix(typed.lowercased()) else { return Text(text).fontWeight(.semibold) }
        let rest = String(text.dropFirst(typed.count))
        return Text(text.prefix(typed.count)) + Text(rest).fontWeight(.semibold)
    }
}


/// All · Songs · Albums · Artists · Videos · Playlists.
private struct FilterChips: View {
    let filters: [(title: String, params: String)]
    @Binding var selected: String?

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                chip("All", params: nil)
                ForEach(filters, id: \.params) { chip($0.title, params: $0.params) }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .scrollIndicators(.hidden)
    }

    private func chip(_ title: String, params: String?) -> some View {
        let on = selected == params
        return Button { withAnimation(.smooth) { selected = params } } label: {
            Text(title).font(.subheadline.weight(.semibold))
                .padding(.horizontal, 14).padding(.vertical, 8)
                .foregroundStyle(on ? Color(.systemBackground) : .primary)
                .background(on ? AnyShapeStyle(.primary) : AnyShapeStyle(.quaternary), in: .capsule)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The big first result: album / artist / playlist / song with a Play button.
private struct TopResultCard: View {
    let item: Item
    var playSong: (Song) -> Void
    @Environment(Player.self) private var player
    @State private var loading = false

    var body: some View {
        switch item {
        case .song(let song):
            card(title: song.title, subtitle: "Song • " + song.artist, art: AnyView(Artwork(song: song, size: 96, radius: 10))) { playSong(song) }
        case .page(let ref):
            NavigationLink(value: ref) {
                card(title: ref.title, subtitle: ref.subtitle.isEmpty ? ref.kind.label : ref.subtitle,
                     art: AnyView(RefArtwork(ref: ref, size: 96))) {
                    loading = true
                    Task {
                        let list = await songs(for: ref)
                        loading = false
                        if !list.isEmpty { player.play(list); player.showFullPlayer = true }
                    }
                }
            }
        }
    }

    private func card(title: String, subtitle: String, art: AnyView, play: @escaping () -> Void) -> some View {
        HStack(spacing: 14) {
            art
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.title3.bold()).lineLimit(2)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                Button(action: play) {
                    Label(loading ? "Loading…" : "Play", systemImage: "play.fill").font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .disabled(loading)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
    }
}
