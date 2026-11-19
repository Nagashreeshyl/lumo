import AVFoundation
import Foundation

/// A song ready for the mixer: a seekable compressed file on disk plus its beat analysis.
nonisolated struct PreparedTrack: Sendable {
    let songId: String
    let file: URL
    let analysis: TrackAnalysis
}

/// Fetches songs, rewraps them as playable `.m4a`, analyses them once, and keeps a size-capped cache.
actor AudioCache {
    static let shared = AudioCache()
    static let limitBytes = 500 * 1024 * 1024

    private let dir = URL.cachesDirectory.appending(path: "audio")
    private var inflight: [String: Task<PreparedTrack, Error>] = [:]
    private var keep: Set<String> = []

    /// Songs currently on the decks; never evicted.
    func setKeep(_ ids: Set<String>) { keep = ids }

    func prepare(_ song: Song, localFile: URL?) async throws -> PreparedTrack {
        if let running = inflight[song.id] { return try await running.value }
        let dir = dir
        let task = Task { try await Self.build(song.id, localFile: localFile, in: dir) }
        inflight[song.id] = task
        defer { inflight[song.id] = nil }
        let track = try await task.value
        evict()
        return track
    }

    private static func build(_ id: String, localFile: URL?, in dir: URL) async throws -> PreparedTrack {
        let fm = FileManager.default
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let playable = dir.appending(path: "\(id).m4a"), json = dir.appending(path: "\(id).json")

        if !fm.fileExists(atPath: playable.path()) {
            var source = localFile
            var fetched: URL?
            if source == nil {
                let remote = try await YT.stream(for: id, download: true).url
                let download = dir.appending(path: "\(id).dl.mp4")
                try await fetch(remote, to: download)
                source = download
                fetched = download
            }
            defer { if let fetched { try? fm.removeItem(at: fetched) } }
            // Write under a temp name first so a crash never leaves a half-written cache entry.
            let partial = dir.appending(path: "\(id).partial.m4a")
            try await decode(source!, to: partial)
            try fm.moveItem(at: partial, to: playable)
        }

        let analysis: TrackAnalysis
        if let data = try? Data(contentsOf: json), let cached = try? JSONDecoder().decode(TrackAnalysis.self, from: data) {
            analysis = cached
        } else {
            analysis = try BeatAnalyzer.analyze(file: playable)
            try? JSONEncoder().encode(analysis).write(to: json)
        }
        try? fm.setAttributes([.modificationDate: Date.now], ofItemAtPath: playable.path())
        return PreparedTrack(songId: id, file: playable, analysis: analysis)
    }

    /// Downloads a googlevideo URL in parallel 1 MB Range requests.
    /// YouTube throttles a single whole-file GET to a crawl; small ranged requests come at full speed.
    static func fetch(_ url: URL, to destination: URL, onProgress: (@Sendable (Double) -> Void)? = nil) async throws {
        let chunk = 1 << 20
        let (first, total) = try await range(url, 0, chunk - 1)
        var parts: [Int: Data] = [0: first]
        let starts = Array(stride(from: chunk, to: total, by: chunk))
        var done = 1
        onProgress?(Double(done) / Double(starts.count + 1))
        // At most 4 requests in flight.
        for batch in stride(from: 0, to: starts.count, by: 4) {
            try await withThrowingTaskGroup(of: (Int, Data).self) { group in
                for s in starts[batch..<min(batch + 4, starts.count)] {
                    group.addTask { (s, try await range(url, s, min(s + chunk, total) - 1).data) }
                }
                for try await (s, data) in group {
                    parts[s] = data
                    done += 1
                    onProgress?(Double(done) / Double(starts.count + 1))
                }
            }
        }
        var file = Data(capacity: total)
        for s in [0] + starts { file.append(parts[s]!) }
        guard file.count == total else { throw URLError(.cannotDecodeContentData) }
        try file.write(to: destination, options: .atomic)
    }

    /// One ranged GET with two retries; returns the bytes and the file's total size.
    private static func range(_ url: URL, _ from: Int, _ to: Int) async throws -> (data: Data, total: Int) {
        var request = URLRequest(url: url)
        request.setValue("bytes=\(from)-\(to)", forHTTPHeaderField: "Range")
        var lastError: Error = URLError(.unknown)
        for attempt in 0..<3 {
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse, http.statusCode == 206 || http.statusCode == 200 else {
                    Log.info("range \(from)-\(to) HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1)")
                    throw URLError(.badServerResponse)
                }
                // "bytes 0-1048575/6012345" → 6012345
                let total = (http.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last).flatMap { Int($0) } ?? data.count
                return (data, total)
            } catch {
                lastError = error
                if attempt < 2 { try await Task.sleep(for: .milliseconds(300 * (attempt + 1))) }
            }
        }
        throw lastError
    }

    /// Rewraps YouTube's fragmented MP4 into a plain `.m4a` the audio engine can read and seek, without
    /// re-encoding: stays compressed (~1 MB/min instead of ~21 MB/min as PCM) and takes well under a second.
    static func decode(_ source: URL, to destination: URL) async throws {
        try? FileManager.default.removeItem(at: destination)
        guard let export = AVAssetExportSession(asset: AVURLAsset(url: source), presetName: AVAssetExportPresetPassthrough) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        try await export.export(to: destination, as: .m4a)
    }

    private func evict() {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]) else { return }
        // Uncompressed .caf files from earlier versions are just dropped.
        for url in items where url.pathExtension == "caf" { try? fm.removeItem(at: url) }
        let cafs: [(url: URL, accessed: Date, bytes: Int)] = items.compactMap { url in
            guard url.pathExtension == "m4a", !url.lastPathComponent.contains(".partial"),
                  !keep.contains(url.deletingPathExtension().lastPathComponent),
                  let v = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey]) else { return nil }
            return (url, v.contentModificationDate ?? .distantPast, v.fileSize ?? 0)
        }
        for url in Self.evictionList(cafs, limit: Self.limitBytes) {
            try? fm.removeItem(at: url)
            try? fm.removeItem(at: url.deletingPathExtension().appendingPathExtension("json"))
        }
    }

    /// Oldest-accessed files to delete until the total fits under `limit`.
    nonisolated static func evictionList(_ files: [(url: URL, accessed: Date, bytes: Int)], limit: Int) -> [URL] {
        var total = files.reduce(0) { $0 + $1.bytes }
        var out: [URL] = []
        for f in files.sorted(by: { $0.accessed < $1.accessed }) where total > limit {
            out.append(f.url)
            total -= f.bytes
        }
        return out
    }
}
