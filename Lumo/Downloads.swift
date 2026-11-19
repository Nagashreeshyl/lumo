import Foundation
import SwiftData

/// Saves songs into the app's Documents folder for offline playback.
@Observable
final class Downloads {
    private(set) var active: [String: Double] = [:]   // videoId → progress 0...1
    var context: ModelContext?

    func isDownloading(_ id: String) -> Bool { active[id] != nil }

    func download(_ song: Song) {
        guard active[song.id] == nil else { return }
        active[song.id] = 0
        Task {
            defer { active[song.id] = nil }
            do {
                let remote = try await YT.stream(for: song.id, download: true).url
                let name = "\(song.id).m4a"
                try await AudioCache.fetch(remote, to: URL.documentsDirectory.appending(path: name)) { p in
                    Task { @MainActor in if self.active[song.id] != nil { self.active[song.id] = p } }
                }
                guard let context else { return }
                context.track(for: song).localFile = name
                try? context.save()
            } catch {
                print("Download failed for \(song.id): \(error)")
            }
        }
    }

    func delete(_ track: Track) {
        if let url = track.localURL { try? FileManager.default.removeItem(at: url) }
        track.localFile = nil
    }
}
