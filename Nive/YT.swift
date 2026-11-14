import Foundation
import SwiftyTube

/// A playable song, as returned by search, home shelves and radio.
nonisolated struct Song: Hashable, Codable, Identifiable, Sendable {
    let id: String          // YouTube videoId
    var title: String
    var artist: String
    var thumbnail: String?
    var duration: Int       // seconds, 0 = unknown

    func artwork(_ px: Int) -> URL? {
        URL(string: ArtworkURLs.artwork(thumbnail, at: px) ?? ArtworkURLs.fallback(for: id))
    }
}

nonisolated struct Shelf: Identifiable, Sendable, Codable {
    var id: String { title }
    let title: String
    let songs: [Song]
}

/// Thin layer over SwiftyTube. SwiftyTube owns the fragile parts (clients, cipher, PoTokens);
/// when YouTube breaks playback, bump its version in project.yml.
nonisolated enum YT {
    static let tube = InnerTubeClient(config: .music)
    static let sessionId = UUID().uuidString

    static func search(_ query: String) async throws -> [Song] {
        let songs = Parse.songs(in: try await tube.search(query: query))
        return query.trimmingCharacters(in: .whitespaces).count <= 3 ? Parse.rank(songs, for: query) : songs
    }

    /// Autocomplete as you type, for the device's region.
    /// (SwiftyTube's suggestqueries-clients6 endpoint now answers 400, so call Google's directly.)
    static func suggestions(_ query: String) async -> [String] {
        var c = URLComponents(string: "https://suggestqueries.google.com/complete/search")!
        c.queryItems = [
            URLQueryItem(name: "client", value: "firefox"), URLQueryItem(name: "ds", value: "yt"),
            URLQueryItem(name: "oe", value: "utf8"), URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "gl", value: Locale.current.region?.identifier ?? "US"),
        ]
        guard let url = c.url, let (data, _) = try? await URLSession.shared.data(from: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [Any], root.count > 1,
              let list = root[1] as? [String] else { return [] }
        return list
    }

    /// YouTube Music home feed (personalized when signed in).
    static func home() async throws -> [Shelf] {
        Parse.shelves(in: try await tube.browse(browseId: "FEmusic_home"))
    }

    static let youtube = InnerTubeClient(config: .youtube)

    /// YouTube's own "Mix" for a song — what autoplays on youtube.com. Long videos (jukeboxes, live sets) are dropped.
    static func youtubeMix(for videoId: String) async throws -> [Song] {
        let json = try await youtube.next(videoId: videoId, playlistId: "RD\(videoId)")
        return Parse.songs(in: json)
            .filter { $0.id != videoId && ($0.duration == 0 || $0.duration <= 12 * 60) }
            .map { var s = $0; s.title = displayTitle(s.title, artist: s.artist); return s }
    }

    /// YouTube video titles → song titles: drops "(Official Video)" and a leading "Artist - ".
    static func displayTitle(_ title: String, artist: String) -> String {
        var t = Lyrics.cleanTitle(title)
        if let r = t.range(of: " - "), !artist.isEmpty, t[..<r.lowerBound].localizedCaseInsensitiveContains(artist) {
            t = String(t[r.upperBound...])
        }
        return t
    }

    /// Endless "radio" of songs similar to `videoId` (the same mix YouTube Music autoplays).
    static func radio(for videoId: String) async throws -> [Song] {
        let json = try await tube.next(videoId: videoId, playlistId: "RDAMVM\(videoId)")
        return Parse.songs(in: json).filter { $0.id != videoId }
    }

    /// Playable AAC stream plus YouTube's own duration (AVPlayer misreads it for DASH streams).
    static func stream(for videoId: String, download: Bool = false) async throws -> (url: URL, duration: Int?) {
        // Apple can't decode Opus/WebM. Only the download selector picks the AAC format within a
        // response (playback's preferAAC just rejects whole clients), so always use it.
        var request = StreamResolveRequest.download(videoId: videoId)
        if !download { request.policy = .playback }   // keep the stream cache for playback
        request.policy.preferAAC = true
        var providers = StreamResolveProviders.live
        providers.poTokenProvider = { id in
            try? await PoTokenGenerator.shared.generate(videoId: id, sessionId: sessionId)
        }
        let result = try await StreamFallback.resolveFirstValid(request, using: tube, providers: providers)
        guard let url = URL(string: result.streamUrl) else { throw URLError(.badURL) }
        return (url, result.duration)
    }
}

/// Song extraction from InnerTube JSON. Walks the whole tree, so it survives layout reshuffles.
nonisolated enum Parse {
    static func songs(in json: Any) -> [Song] {
        var out: [Song] = []
        var seen = Set<String>()
        walk(json) { renderer in
            if let s = song(from: renderer), seen.insert(s.id).inserted { out.append(s) }
        }
        return out
    }

    /// Songs whose title starts with what was typed first, then ones with a word starting with it,
    /// then the rest — each group in YouTube's order.
    static func rank(_ songs: [Song], for query: String) -> [Song] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return songs }
        func tier(_ s: Song) -> Int {
            let t = s.title.lowercased()
            if t.hasPrefix(q) { return 0 }
            if t.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).contains(where: { $0.hasPrefix(q) }) { return 1 }
            return 2
        }
        return songs.enumerated().sorted { (tier($0.element), $0.offset) < (tier($1.element), $1.offset) }.map(\.element)
    }

    static func shelves(in json: [String: Any]) -> [Shelf] {
        var out: [Shelf] = []
        walk(json, key: "musicCarouselShelfRenderer") { shelf in
            let header = (shelf["header"] as? [String: Any])?["musicCarouselShelfBasicHeaderRenderer"] as? [String: Any]
            guard let title = InnerTubeJSON.runsText(header?["title"] as? [String: Any]) else { return }
            let songs = songs(in: shelf["contents"] ?? [])
            if !songs.isEmpty { out.append(Shelf(title: title, songs: songs)) }
        }
        return out
    }

    /// Visits every dictionary stored under one of the song renderer keys (or `key`).
    private static func walk(_ node: Any, key: String? = nil, visit: ([String: Any]) -> Void) {
        let keys = key.map { [$0] } ?? songKeys
        if let dict = node as? [String: Any] {
            for (k, v) in dict {
                if keys.contains(k), let r = v as? [String: Any] {
                    visit(r)
                    // The search "top result" card also lists related songs inside it.
                    if key == nil && k != "musicCardShelfRenderer" { continue }
                }
                walk(v, key: key, visit: visit)
            }
        } else if let array = node as? [Any] {
            for v in array { walk(v, key: key, visit: visit) }
        }
    }

    private static let songKeys = ["musicCardShelfRenderer", "musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "playlistPanelVideoRenderer"]
    private static let kindLabels: Set<String> = ["Song", "Video", "Single", "EP", "Album", "Episode"]

    static func song(from r: [String: Any]) -> Song? {
        guard let id = videoId(r) else { return nil }
        let title: String?
        var bylineRuns: [String]
        if let flex = r["flexColumns"] as? [[String: Any]] {
            title = runs(flex, 0).first
            bylineRuns = runs(flex, 1)
        } else {
            title = InnerTubeJSON.runsText(r["title"] as? [String: Any])
            bylineRuns = InnerTubeJSON.runsTexts((r["longBylineText"] ?? r["shortBylineText"] ?? r["subtitle"]) as? [String: Any])
        }
        if let first = bylineRuns.first, kindLabels.contains(first) { bylineRuns.removeFirst() }
        guard let title, !title.isEmpty else { return nil }
        return Song(
            id: id,
            title: title,
            artist: bylineRuns.first ?? "",
            thumbnail: thumbnail(r),
            duration: duration(r)
        )
    }

    private static func duration(_ r: [String: Any]) -> Int {
        let d = DurationFormat.parseRendererDuration(r)
        if d > 0 { return d }
        // YouTube (not Music) panels carry "4:05" in lengthText.
        return InnerTubeJSON.runsText(r["lengthText"] as? [String: Any]).flatMap(DurationFormat.parseClock) ?? 0
    }

    private static func runs(_ flex: [[String: Any]], _ i: Int) -> [String] {
        guard i < flex.count else { return [] }
        let col = (flex[i]["musicResponsiveListItemFlexColumnRenderer"] ?? flex[i]["musicResponsiveListItemColumnRenderer"]) as? [String: Any]
        return InnerTubeJSON.runsTexts(col?["text"] as? [String: Any])
    }

    /// Only items that play a single video (not albums/playlists/artists).
    private static func videoId(_ r: [String: Any]) -> String? {
        if let id = r["videoId"] as? String { return id }
        if let id = (r["playlistItemData"] as? [String: Any])?["videoId"] as? String { return id }
        for endpointKey in ["navigationEndpoint", "onTap"] {
            if let id = ((r[endpointKey] as? [String: Any])?["watchEndpoint"] as? [String: Any])?["videoId"] as? String {
                return id
            }
        }
        if let flex = r["flexColumns"] as? [[String: Any]],
           let col = flex.first?["musicResponsiveListItemFlexColumnRenderer"] as? [String: Any],
           let run = ((col["text"] as? [String: Any])?["runs"] as? [[String: Any]])?.first,
           let id = ((run["navigationEndpoint"] as? [String: Any])?["watchEndpoint"] as? [String: Any])?["videoId"] as? String {
            return id
        }
        return nil
    }
}
