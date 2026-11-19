import SwiftData
import SwiftUI

struct LibraryView: View {
    @Environment(Player.self) private var player
    @Environment(\.modelContext) private var context
    @Query(sort: \Playlist.createdAt, order: .reverse) private var playlists: [Playlist]
    @Query(filter: #Predicate<Track> { $0.lastPlayed != nil }, sort: \Track.lastPlayed, order: .reverse) private var history: [Track]
    @State private var renaming: Playlist?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink { TrackList(kind: .liked) } label: {
                        Label("Liked Songs", systemImage: "heart.fill")
                    }
                    NavigationLink { TrackList(kind: .downloads) } label: {
                        Label("Downloads", systemImage: "arrow.down.circle.fill")
                    }
                }
                Section("Playlists") {
                    Button {
                        context.insert(Playlist(name: "Playlist \(playlists.count + 1)"))
                    } label: {
                        Label("New Playlist", systemImage: "plus")
                    }
                    ForEach(playlists) { list in
                        NavigationLink { PlaylistDetail(playlist: list) } label: {
                            VStack(alignment: .leading) {
                                Text(list.name)
                                Text("^[\(list.trackIds.count) song](inflect: true)").font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            Button("Delete", systemImage: "trash", role: .destructive) { context.delete(list) }
                            Button("Rename", systemImage: "pencil") { renaming = list }
                        }
                        .contextMenu {
                            Button("Rename…", systemImage: "pencil") { renaming = list }
                            Button("Delete Playlist", systemImage: "trash", role: .destructive) { context.delete(list) }
                        }
                    }
                }
                if !history.isEmpty {
                    Section("Recently Played") {
                        let recent = history.prefix(10).map(\.song)
                        ForEach(Array(recent.enumerated()), id: \.element.id) { i, song in
                            SongRow(song: song) { player.play(recent, at: i) }
                        }
                    }
                }
            }
            .navigationTitle("Library")
            .pageDestinations()
            .renamePlaylist($renaming)
        }
    }
}

struct TrackList: View {
    enum Kind { case liked, downloads }
    let kind: Kind
    @Environment(Player.self) private var player
    @Environment(Downloads.self) private var downloads
    @Query private var tracks: [Track]

    init(kind: Kind) {
        self.kind = kind
        switch kind {
        case .liked: _tracks = Query(filter: #Predicate<Track> { $0.liked }, sort: \Track.likedAt, order: .reverse)
        case .downloads: _tracks = Query(filter: #Predicate<Track> { $0.localFile != nil }, sort: \Track.title)
        }
    }

    var body: some View {
        let songs = tracks.map(\.song)
        List {
            if !songs.isEmpty { PlayShuffleHeader(songs: songs) }
            ForEach(Array(songs.enumerated()), id: \.element.id) { i, song in
                SongRow(song: song) { player.play(songs, at: i) }
            }
            .onDelete { offsets in
                for i in offsets {
                    if kind == .downloads { downloads.delete(tracks[i]) } else { tracks[i].liked = false }
                }
            }
        }
        .listStyle(.plain)
        .overlay {
            if songs.isEmpty {
                switch kind {
                case .liked: ContentUnavailableView("No liked songs", systemImage: "heart",
                                                    description: Text("Tap the heart in the player to save songs here."))
                case .downloads: ContentUnavailableView("No downloads", systemImage: "arrow.down.circle",
                                                        description: Text("Long-press any song and choose Download to listen offline."))
                }
            }
        }
        .navigationTitle(kind == .liked ? "Liked Songs" : "Downloads")
    }
}

struct PlaylistDetail: View {
    @Bindable var playlist: Playlist
    @Environment(Player.self) private var player
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var renaming: Playlist?
    @State private var confirmDelete = false

    var body: some View {
        let songs = playlist.trackIds.compactMap { context.existingTrack($0)?.song }
        List {
            PlaylistHeader(playlist: playlist, songs: songs)
                .listRowSeparator(.hidden)
            ForEach(Array(songs.enumerated()), id: \.element.id) { i, song in
                SongRow(song: song) { player.play(songs, at: i) }
                    .swipeActions {
                        Button("Remove", systemImage: "minus.circle", role: .destructive) { remove(song.id) }
                    }
            }
            .onDelete { offsets in offsets.map { songs[$0].id }.forEach(remove) }
            .onMove { from, to in
                // Reorder by id so missing tracks can't shift positions.
                var ids = songs.map(\.id)
                ids.move(fromOffsets: from, toOffset: to)
                playlist.trackIds = ids
            }
        }
        .listStyle(.plain)
        .overlay {
            if songs.isEmpty {
                ContentUnavailableView("Empty playlist", systemImage: "music.note.list",
                                       description: Text("Use a song's ⋯ menu and choose Add to Playlist."))
                    .padding(.top, 220)
            }
        }
        .navigationTitle(playlist.name)
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .trailingBar) { if !songs.isEmpty { EditButton() } }
            #endif
            ToolbarItem(placement: .trailingBar) {
                Menu {
                    Button("Play", systemImage: "play.fill") { player.play(songs) }.disabled(songs.isEmpty)
                    Button("Shuffle", systemImage: "shuffle") { player.play(songs.shuffled()) }.disabled(songs.isEmpty)
                    Divider()
                    Button("Rename…", systemImage: "pencil") { renaming = playlist }
                    Button("Delete Playlist", systemImage: "trash", role: .destructive) { confirmDelete = true }
                } label: {
                    Label("Playlist actions", systemImage: "ellipsis.circle")
                }
            }
        }
        .renamePlaylist($renaming)
        .confirmationDialog("Delete “\(playlist.name)”?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete Playlist", role: .destructive) {
                context.delete(playlist)
                dismiss()
            }
        } message: {
            Text("The songs stay in your library.")
        }
    }

    private func remove(_ id: String) {
        playlist.trackIds.removeAll { $0 == id }
    }
}

/// Artwork mosaic, name, song count, Play / Shuffle.
private struct PlaylistHeader: View {
    let playlist: Playlist
    let songs: [Song]

    var body: some View {
        VStack(spacing: 12) {
            Mosaic(songs: Array(songs.prefix(4)))
                .frame(width: 180, height: 180)
                .clipShape(.rect(cornerRadius: 14))
                .shadow(color: .black.opacity(0.2), radius: 14, y: 8)
            VStack(spacing: 2) {
                Text(playlist.name).font(.title2.bold())
                Text("^[\(songs.count) song](inflect: true)").font(.subheadline).foregroundStyle(.secondary)
            }
            if !songs.isEmpty { PlayShuffleHeader(songs: songs) }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }
}

/// 2×2 grid of song covers (or one cover / a placeholder).
private struct Mosaic: View {
    let songs: [Song]
    var body: some View {
        GeometryReader { geo in
            let side = geo.size.width
            if songs.count >= 4 {
                VStack(spacing: 0) {
                    HStack(spacing: 0) { Artwork(song: songs[0], size: side / 2, radius: 0); Artwork(song: songs[1], size: side / 2, radius: 0) }
                    HStack(spacing: 0) { Artwork(song: songs[2], size: side / 2, radius: 0); Artwork(song: songs[3], size: side / 2, radius: 0) }
                }
            } else if let first = songs.first {
                Artwork(song: first, size: side, radius: 0)
            } else {
                Rectangle().fill(.quaternary).overlay { Image(systemName: "music.note.list").font(.largeTitle).foregroundStyle(.tertiary) }
            }
        }
    }
}

extension View {
    /// "Rename Playlist" alert bound to the playlist being renamed (nil = hidden).
    func renamePlaylist(_ target: Binding<Playlist?>) -> some View { modifier(RenamePlaylist(target: target)) }
}

private struct RenamePlaylist: ViewModifier {
    @Binding var target: Playlist?
    @State private var name = ""

    func body(content: Content) -> some View {
        content
            .onChange(of: target) { _, list in if let list { name = list.name } }
            .alert("Rename Playlist", isPresented: Binding(get: { target != nil }, set: { if !$0 { target = nil } })) {
                TextField("Name", text: $name)
                Button("Save") {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty { target?.name = trimmed }
                }
                Button("Cancel", role: .cancel) {}
            }
    }
}

private struct PlayShuffleHeader: View {
    let songs: [Song]
    @Environment(Player.self) private var player

    var body: some View {
        HStack(spacing: 12) {
            Button { player.play(songs) } label: {
                Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
            }
            Button { player.play(songs.shuffled()) } label: {
                Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .font(.body.weight(.semibold))
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 12, trailing: 16))
    }
}
