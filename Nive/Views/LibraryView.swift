import SwiftData
import SwiftUI

struct LibraryView: View {
    @Environment(Player.self) private var player
    @Environment(\.modelContext) private var context
    @Query(sort: \Playlist.createdAt, order: .reverse) private var playlists: [Playlist]
    @Query(filter: #Predicate<Track> { $0.lastPlayed != nil }, sort: \Track.lastPlayed, order: .reverse) private var history: [Track]
    @State private var renaming: Playlist?
    @State private var newName = ""

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
                            Button("Rename", systemImage: "pencil") { newName = list.name; renaming = list }
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
            .alert("Rename Playlist", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Name", text: $newName)
                Button("Save") { if !newName.isEmpty { renaming?.name = newName } }
                Button("Cancel", role: .cancel) {}
            }
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

    var body: some View {
        let songs = playlist.trackIds.compactMap { context.existingTrack($0)?.song }
        List {
            if !songs.isEmpty { PlayShuffleHeader(songs: songs) }
            ForEach(Array(songs.enumerated()), id: \.element.id) { i, song in
                SongRow(song: song) { player.play(songs, at: i) }
            }
            .onDelete { playlist.trackIds.remove(atOffsets: $0) }
            .onMove { playlist.trackIds.move(fromOffsets: $0, toOffset: $1) }
        }
        .listStyle(.plain)
        .overlay {
            if songs.isEmpty {
                ContentUnavailableView("Empty playlist", systemImage: "music.note.list",
                                       description: Text("Long-press any song and choose Add to Playlist."))
            }
        }
        .navigationTitle(playlist.name)
        .toolbar { if !songs.isEmpty { EditButton() } }
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
