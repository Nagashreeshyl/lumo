import Foundation
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

/// Library backup as one small JSON file: likes, playlists, listening stats, Not Interested, and which songs were downloaded.
/// Moves a library between installs (e.g. iloader → SideStore copy, or iPhone → Mac). Audio files aren't included;
/// downloaded songs are downloaded again after import.
enum LibraryBackup {
    struct File: Codable {
        var version = 1
        var exported = Date.now
        var tracks: [TrackRecord]
        var playlists: [PlaylistRecord]
    }
    struct TrackRecord: Codable {
        var song: Song
        var liked: Bool
        var likedAt: Date?
        var playCount: Int
        var skipCount: Int
        var lastPlayed: Date?
        var disliked: Bool
        var listenedSeconds: Double
        var downloaded: Bool
    }
    struct PlaylistRecord: Codable {
        var name: String
        var createdAt: Date
        var trackIds: [String]
    }
    struct ImportResult {
        var songs = 0
        var playlists = 0
        var toDownload: [Song] = []
    }

    static func export(from context: ModelContext) throws -> Data {
        let tracks = try context.fetch(FetchDescriptor<Track>()).map {
            TrackRecord(song: $0.song, liked: $0.liked, likedAt: $0.likedAt, playCount: $0.playCount, skipCount: $0.skipCount,
                        lastPlayed: $0.lastPlayed, disliked: $0.disliked, listenedSeconds: $0.listenedSeconds, downloaded: $0.localFile != nil)
        }
        let playlists = try context.fetch(FetchDescriptor<Playlist>()).map {
            PlaylistRecord(name: $0.name, createdAt: $0.createdAt, trackIds: $0.trackIds)
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(File(tracks: tracks, playlists: playlists))
    }

    /// Merges a backup into the library without losing anything already there: counts keep the higher value,
    /// likes and Not Interested are kept if either side has them, same-name playlists are merged.
    @discardableResult
    static func importBackup(_ data: Data, into context: ModelContext) throws -> ImportResult {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let file = try decoder.decode(File.self, from: data)
        var result = ImportResult()
        for r in file.tracks {
            let t = context.track(for: r.song)
            t.playCount = max(t.playCount, r.playCount)
            t.skipCount = max(t.skipCount, r.skipCount)
            t.listenedSeconds = max(t.listenedSeconds, r.listenedSeconds)
            if r.liked && !t.liked { t.liked = true; t.likedAt = r.likedAt ?? .now }
            if r.disliked { t.disliked = true }
            if let last = r.lastPlayed, last > (t.lastPlayed ?? .distantPast) { t.lastPlayed = last }
            if r.downloaded && t.localFile == nil { result.toDownload.append(r.song) }
            result.songs += 1
        }
        let existing = try context.fetch(FetchDescriptor<Playlist>())
        for p in file.playlists {
            if let same = existing.first(where: { $0.name == p.name }) {
                same.trackIds += p.trackIds.filter { !same.trackIds.contains($0) }
            } else {
                let list = Playlist(name: p.name)
                list.createdAt = p.createdAt
                list.trackIds = p.trackIds
                context.insert(list)
            }
            result.playlists += 1
        }
        try context.save()
        return result
    }
}

/// Lets SwiftUI's file exporter write the backup.
struct BackupDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.json]
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/// Settings section: Export / Import Library.
struct BackupSection: View {
    @Environment(\.modelContext) private var context
    @Environment(Player.self) private var player
    @Environment(Downloads.self) private var downloads
    @State private var exportDoc: BackupDocument?
    @State private var importing = false
    @State private var message: String?

    var body: some View {
        Section {
            Button("Export Library…", systemImage: "square.and.arrow.up") {
                do { exportDoc = BackupDocument(data: try LibraryBackup.export(from: context)) }
                catch { message = "Couldn't export: \(error.localizedDescription)" }
            }
            Button("Import Library…", systemImage: "square.and.arrow.down") { importing = true }
        } header: {
            Text("Backup")
        } footer: {
            Text("Saves likes, playlists, stats and hidden songs to a file, to move them to another install or to your Mac. Downloaded songs are downloaded again after import.")
        }
        .fileExporter(isPresented: Binding(get: { exportDoc != nil }, set: { if !$0 { exportDoc = nil } }),
                      document: exportDoc, contentType: .json,
                      defaultFilename: "Nive Library \(Date.now.formatted(.iso8601.year().month().day()))") { result in
            if case .success = result { message = "Library exported." }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let r = try LibraryBackup.importBackup(try Data(contentsOf: url), into: context)
                r.toDownload.forEach(downloads.download)
                player.context = context   // reload hidden songs
                message = "Imported \(r.songs) songs and \(r.playlists) playlists." + (r.toDownload.isEmpty ? "" : " Re-downloading \(r.toDownload.count) songs.")
            } catch {
                message = "That file couldn't be imported."
            }
        }
        .alert("Library", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") {}
        } message: {
            Text(message ?? "")
        }
    }
}
