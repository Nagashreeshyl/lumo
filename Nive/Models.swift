import Foundation
import SwiftData

/// Every song the user has touched: played, liked, added to a playlist or downloaded.
/// Also stores the listening signals the recommender uses.
@Model
final class Track {
    @Attribute(.unique) var videoId: String
    var title: String
    var artist: String
    var thumbnail: String?
    var duration: Int
    var liked: Bool = false
    var likedAt: Date?
    var playCount: Int = 0
    var skipCount: Int = 0
    var lastPlayed: Date?
    var localFile: String?          // filename in Documents when downloaded
    var disliked: Bool = false      // swiped away in Up Next: never auto-queue or recommend

    init(_ song: Song) {
        videoId = song.id
        title = song.title
        artist = song.artist
        thumbnail = song.thumbnail
        duration = song.duration
    }

    var song: Song { Song(id: videoId, title: title, artist: artist, thumbnail: thumbnail, duration: duration) }

    var localURL: URL? {
        localFile.map { URL.documentsDirectory.appending(path: $0) }
    }
}

@Model
final class Playlist {
    var name: String
    var createdAt: Date = Date.now
    var trackIds: [String] = []     // ordered videoIds

    init(name: String) { self.name = name }
}

extension ModelContext {
    /// Fetch-or-create the Track row for a song.
    func track(for song: Song) -> Track {
        let id = song.id
        if let existing = try? fetch(FetchDescriptor<Track>(predicate: #Predicate { $0.videoId == id })).first {
            return existing
        }
        let t = Track(song)
        insert(t)
        return t
    }

    func existingTrack(_ videoId: String) -> Track? {
        try? fetch(FetchDescriptor<Track>(predicate: #Predicate { $0.videoId == videoId })).first
    }
}
