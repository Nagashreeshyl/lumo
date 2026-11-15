import Foundation
import SwiftData
import Testing
@testable import Nive

@MainActor
struct BackupTests {
    private func context() throws -> ModelContext {
        ModelContext(try ModelContainer(for: Track.self, Playlist.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true)))
    }
    private func song(_ id: String) -> Song { Song(id: id, title: "T\(id)", artist: "A", thumbnail: nil, duration: 200) }

    @Test func exportThenImportRestoresEverything() throws {
        let old = try context()
        let liked = old.track(for: song("1")); liked.liked = true; liked.playCount = 7; liked.listenedSeconds = 900
        let hidden = old.track(for: song("2")); hidden.disliked = true
        let kept = old.track(for: song("3")); kept.localFile = "3.m4a"
        let list = Playlist(name: "Gym"); list.trackIds = ["1", "3"]; old.insert(list)

        let data = try LibraryBackup.export(from: old)
        let fresh = try context()
        let result = try LibraryBackup.importBackup(data, into: fresh)

        #expect(fresh.existingTrack("1")?.liked == true)
        #expect(fresh.existingTrack("1")?.playCount == 7)
        #expect(fresh.existingTrack("2")?.disliked == true)
        #expect(result.toDownload.map(\.id) == ["3"])
        #expect(fresh.existingTrack("3")?.localFile == nil)   // the audio file itself isn't in the backup
        let lists = try fresh.fetch(FetchDescriptor<Playlist>())
        #expect(lists.map(\.name) == ["Gym"] && lists.first?.trackIds == ["1", "3"])
    }

    @Test func importMergesWithoutLosingNewerListening() throws {
        let old = try context()
        let t = old.track(for: song("1")); t.playCount = 3; t.liked = true
        let p = Playlist(name: "Chill"); p.trackIds = ["1"]; old.insert(p)
        let data = try LibraryBackup.export(from: old)

        let current = try context()
        let c = current.track(for: song("1")); c.playCount = 10
        let existing = Playlist(name: "Chill"); existing.trackIds = ["9"]; current.insert(existing)
        _ = try LibraryBackup.importBackup(data, into: current)

        #expect(current.existingTrack("1")?.playCount == 10)   // keeps the higher count
        #expect(current.existingTrack("1")?.liked == true)
        let lists = try current.fetch(FetchDescriptor<Playlist>())
        #expect(lists.count == 1 && lists.first?.trackIds == ["9", "1"])   // same-name playlist merged, no duplicate
    }
}
