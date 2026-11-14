import SwiftData
import SwiftUI

/// Square album art with a quiet placeholder.
/// shortcut: AsyncImage relies on URLCache only; add an image cache if scrolling feels slow.
struct Artwork: View {
    let song: Song?
    var size: CGFloat
    var radius: CGFloat = 6

    var body: some View {
        AsyncImage(url: song?.artwork(Int(size * 3)), transaction: Transaction(animation: .easeOut(duration: 0.25))) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                Rectangle().fill(.quaternary)
                    .overlay { Image(systemName: "music.note").font(.system(size: size * 0.3)).foregroundStyle(.tertiary) }
            }
        }
        .frame(width: size, height: size)
        .clipShape(.rect(cornerRadius: radius))
        .accessibilityHidden(true)
    }
}

/// A song in any list: tap to play, long-press for actions.
struct SongRow: View {
    let song: Song
    var onPlay: () -> Void
    @Environment(Player.self) private var player
    @Environment(Downloads.self) private var downloads
    @Environment(\.modelContext) private var context
    @Query private var match: [Track]

    init(song: Song, onPlay: @escaping () -> Void) {
        self.song = song
        self.onPlay = onPlay
        let id = song.id
        _match = Query(filter: #Predicate<Track> { $0.videoId == id })
    }

    private var isCurrent: Bool { player.current?.id == song.id }

    var body: some View {
        HStack(spacing: 4) {
        Button(action: onPlay) {
            HStack(spacing: 12) {
                Artwork(song: song, size: 48)
                    .overlay {
                        if isCurrent {
                            RoundedRectangle(cornerRadius: 6).fill(.black.opacity(0.35))
                            Image(systemName: "waveform")
                                .symbolEffect(.variableColor.iterative, isActive: player.isPlaying)
                                .foregroundStyle(.white)
                        }
                    }
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title).font(.body).foregroundStyle(isCurrent ? Color.accentColor : .primary).lineLimit(1)
                    Text(song.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 8)
                if let p = downloads.active[song.id] {
                    ProgressView(value: p).progressViewStyle(.circular).controlSize(.small)
                } else if match.first?.localFile != nil {
                    Image(systemName: "arrow.down.circle.fill").font(.footnote).foregroundStyle(.secondary)
                        .accessibilityLabel("Downloaded")
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityHint("Plays the song")
            // Visible "⋯" so actions like Not Interested don't depend on knowing about long-press.
            Menu { SongMenu(song: song) } label: {
                Image(systemName: "ellipsis").font(.body.weight(.semibold)).foregroundStyle(.secondary)
                    .frame(width: 36, height: 44).contentShape(.rect)
            }
            .accessibilityLabel("More actions for \(song.title)")
        }
        .contextMenu { SongMenu(song: song) }
    }
}

/// Shared long-press / "…" actions for a song.
struct SongMenu: View {
    let song: Song
    @Environment(Player.self) private var player
    @Environment(Downloads.self) private var downloads
    @Environment(\.modelContext) private var context
    @State private var pickPlaylist = false

    var body: some View {
        let track = context.existingTrack(song.id)
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(song) }
        Button("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward") { player.addToQueue(song) }
        Divider()
        Button(track?.liked == true ? "Unlike" : "Like", systemImage: track?.liked == true ? "heart.slash" : "heart") {
            toggleLike(song, in: context)
        }
        Menu("Add to Playlist", systemImage: "text.badge.plus") { PlaylistMenu(song: song) }
        Button("Not Interested", systemImage: "hand.thumbsdown", role: .destructive) { withAnimation { player.dislike(song) } }
        if track?.localFile != nil {
            Button("Remove Download", systemImage: "trash", role: .destructive) { if let track { downloads.delete(track) } }
        } else {
            Button("Download", systemImage: "arrow.down.circle") { downloads.download(song) }
        }
    }
}

/// Lists playlists inside a menu, plus "New Playlist…".
struct PlaylistMenu: View {
    let song: Song
    @Environment(\.modelContext) private var context
    @Query(sort: \Playlist.createdAt, order: .reverse) private var playlists: [Playlist]

    var body: some View {
        ForEach(playlists) { list in
            Button(list.name) { add(to: list) }
        }
        Divider()
        Button("New Playlist", systemImage: "plus") {
            // Renamable from the Library tab.
            let list = Playlist(name: "Playlist \(playlists.count + 1)")
            context.insert(list)
            add(to: list)
        }
    }

    private func add(to list: Playlist) {
        _ = context.track(for: song)
        if !list.trackIds.contains(song.id) { list.trackIds.append(song.id) }
    }
}

func toggleLike(_ song: Song, in context: ModelContext) {
    let t = context.track(for: song)
    t.liked.toggle()
    t.likedAt = t.liked ? .now : nil
}

/// Loading placeholder rows that match SongRow's shape.
struct SkeletonRows: View {
    var count = 6
    var body: some View {
        ForEach(0..<count, id: \.self) { _ in
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 6).frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Song title placeholder")
                    Text("Artist").font(.subheadline)
                }
            }
            .redacted(reason: .placeholder)
            .phaseAnimator([0.4, 0.8]) { v, phase in v.opacity(phase) } animation: { _ in .easeInOut(duration: 0.9) }
        }
        .accessibilityLabel("Loading")
    }
}
