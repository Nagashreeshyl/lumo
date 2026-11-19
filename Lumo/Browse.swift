import Foundation
import SwiftyTube

/// Something you can open: an album, artist, playlist or mood/genre page.
nonisolated struct Ref: Hashable, Sendable, Codable {
    enum Kind: String, Sendable, Codable { case album, artist, playlist, category }
    let kind: Kind
    let id: String              // browseId (album/artist/category) or playlist id without "VL"
    let title: String
    let subtitle: String
    let thumbnail: String?
    var params: String? = nil   // category pages
    var color: UInt32? = nil    // mood/genre tile stripe (ARGB)

    func artwork(_ px: Int) -> URL? { ArtworkURLs.artwork(thumbnail, at: px).flatMap { URL(string: $0) } }
}

/// One search / shelf entry.
nonisolated enum Item: Hashable, Sendable, Identifiable, Codable {
    case song(Song)
    case page(Ref)
    var id: String {
        switch self {
        case .song(let s): "s:" + s.id
        case .page(let r): r.kind.rawValue + ":" + r.id + (r.params ?? "")
        }
    }
}

nonisolated struct BrowseSection: Identifiable, Sendable, Codable {
    var id = UUID()
    let title: String
    let items: [Item]
    private enum CodingKeys: String, CodingKey { case title, items }
    var songs: [Song] { items.compactMap { if case .song(let s) = $0 { s } else { nil } } }
}

nonisolated struct SearchPage: Sendable {
    var top: Item?
    var items: [Item] = []
    var filters: [(title: String, params: String)] = []
}

nonisolated struct Header: Sendable {
    let title: String
    let subtitle: String
    let thumbnail: String?
    func artwork(_ px: Int) -> URL? { ArtworkURLs.artwork(thumbnail, at: px).flatMap { URL(string: $0) } }
}

nonisolated struct BrowsePage: Sendable {
    var header: Header?
    var sections: [BrowseSection]
}

extension YT {
    static func searchPage(_ query: String, filter: String? = nil) async throws -> SearchPage {
        var page = Parse.searchPage(try await tube.search(query: query, params: filter))
        page.items = rankItems(page.items, for: query)
        return page
    }

    /// Album: header + its tracks (rows have no art or artist, so fill them from the header).
    static func album(_ id: String) async throws -> BrowsePage {
        var page = Parse.browsePage(try await tube.browse(browseId: id))
        if let h = page.header, let first = page.sections.first {
            let artist = h.subtitle.components(separatedBy: " • ").first ?? ""
            let tracks = first.items.map { item -> Item in
                guard case .song(var s) = item else { return item }
                if s.thumbnail == nil { s.thumbnail = h.thumbnail }
                if s.artist.isEmpty { s.artist = artist }
                return .song(s)
            }
            page.sections[0] = BrowseSection(title: first.title, items: tracks)
        }
        return page
    }

    static func artist(_ id: String) async throws -> BrowsePage {
        Parse.browsePage(try await tube.browse(browseId: id))
    }

    /// Playlist songs via the watch queue (browse "VL…" pages come back empty for guests).
    static func playlist(_ id: String) async throws -> [Song] {
        Parse.songs(in: try await tube.next(playlistId: id))
    }

    /// YouTube Music's home with everything on it (songs, albums, playlists, mixes).
    static func homeSections() async throws -> [BrowseSection] {
        Parse.browsePage(try await tube.browse(browseId: "FEmusic_home")).sections
    }

    static func explore() async throws -> [BrowseSection] {
        Parse.browsePage(try await tube.browse(browseId: "FEmusic_explore")).sections
    }

    static func category(_ ref: Ref) async throws -> BrowsePage {
        Parse.browsePage(try await tube.browse(browseId: ref.id, params: ref.params))
    }

    /// Songs and pages whose title starts with the query first, keeping YouTube's order otherwise.
    static func rankItems(_ items: [Item], for query: String) -> [Item] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        // Only for a few letters ("a", "ta"): for real queries YouTube's relevance order is better.
        guard q.count <= 3 else { return items }
        func starts(_ i: Item) -> Bool {
            switch i {
            case .song(let s): s.title.lowercased().hasPrefix(q)
            case .page(let r): r.title.lowercased().hasPrefix(q)
            }
        }
        return items.filter(starts) + items.filter { !starts($0) }
    }
}

nonisolated extension Parse {
    private static let skippedKinds: Set<String> = ["Episode", "Podcast", "Profile"]
    private static let allowedFilters: Set<String> = ["Songs", "Albums", "Artists", "Videos", "Featured playlists", "Community playlists"]

    /// A shelf entry as a song or an openable page; nil for episodes/podcasts/unknown.
    static func item(from r: [String: Any]) -> Item? {
        if let nav = r["clickCommand"] as? [String: Any] ?? (r["navigationEndpoint"] as? [String: Any]),
           r["buttonText"] != nil, let browse = nav["browseEndpoint"] as? [String: Any], let id = browse["browseId"] as? String {
            // Mood / genre / explore button.
            let title = InnerTubeJSON.runsText(r["buttonText"] as? [String: Any]) ?? ""
            let color = ((r["solid"] as? [String: Any])?["leftStripeColor"] as? NSNumber)?.uint32Value
            return .page(Ref(kind: .category, id: id, title: title, subtitle: "", thumbnail: nil,
                             params: browse["params"] as? String, color: color))
        }
        let subtitleRuns = subtitleTexts(r)
        if let kind = subtitleRuns.first, skippedKinds.contains(kind) { return nil }
        if let (kind, id) = browseTarget(r) {
            let title = titleText(r) ?? ""
            guard !title.isEmpty else { return nil }
            var subtitle = subtitleRuns
            if let first = subtitle.first, ["Album", "Single", "EP", "Artist", "Playlist"].contains(first), kind != .album { subtitle.removeFirst() }
            return .page(Ref(kind: kind, id: kind == .playlist && id.hasPrefix("VL") ? String(id.dropFirst(2)) : id,
                             title: title, subtitle: subtitle.joined(separator: " • "),
                             thumbnail: thumbnail(r)))
        }
        return song(from: r).map(Item.song)
    }

    static func searchPage(_ json: [String: Any]) -> SearchPage {
        var page = SearchPage()
        walkKeys(json, ["chipCloudChipRenderer"]) { _, chip in
            let title = InnerTubeJSON.runsText(chip["text"] as? [String: Any]) ?? ""
            let params = ((chip["navigationEndpoint"] as? [String: Any])?["searchEndpoint"] as? [String: Any])?["params"] as? String
            if let params, allowedFilters.contains(title) {
                page.filters.append((title, params.removingPercentEncoding ?? params))
            }
        }
        var seen = Set<String>()
        walkKeys(json, ["musicCardShelfRenderer", "musicResponsiveListItemRenderer"], descendInto: ["musicCardShelfRenderer"]) { key, r in
            guard let item = item(from: r), seen.insert(item.id).inserted else { return }
            if key == "musicCardShelfRenderer", page.top == nil { page.top = item } else { page.items.append(item) }
        }
        return page
    }

    static func browsePage(_ json: [String: Any]) -> BrowsePage {
        var header: Header?
        walkKeys(json, ["musicResponsiveHeaderRenderer", "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer", "musicDetailHeaderRenderer"]) { _, h in
            guard header == nil else { return }
            let text = { (k: String) in InnerTubeJSON.runsText(h[k] as? [String: Any]) ?? "" }
            let subtitle = [text("straplineTextOne"), text("subtitle"), text("secondSubtitle")].filter { !$0.isEmpty }.joined(separator: " • ")
            header = Header(title: text("title"), subtitle: subtitle, thumbnail: InnerTubeJSON.musicThumbnailURL(h))
        }
        var sections: [BrowseSection] = []
        walkKeys(json, ["musicShelfRenderer", "musicCarouselShelfRenderer", "gridRenderer", "musicPlaylistShelfRenderer"]) { _, shelf in
            let header = shelf["header"] as? [String: Any]
            let titleDict = shelf["title"] as? [String: Any]
                ?? (header?["musicCarouselShelfBasicHeaderRenderer"] as? [String: Any])?["title"] as? [String: Any]
                ?? (header?["gridHeaderRenderer"] as? [String: Any])?["title"] as? [String: Any]
            let entries = (shelf["contents"] ?? shelf["items"]) as? [[String: Any]] ?? []
            let items = entries.compactMap { entry in entry.values.lazy.compactMap { $0 as? [String: Any] }.compactMap(item(from:)).first }
            if !items.isEmpty { sections.append(BrowseSection(title: InnerTubeJSON.runsText(titleDict) ?? "", items: items)) }
        }
        return BrowsePage(header: header, sections: sections)
    }

    // MARK: helpers

    /// Visits dictionaries under `keys` in document order. Doesn't descend into a match unless listed.
    static func walkKeys(_ node: Any, _ keys: Set<String>, descendInto: Set<String> = [], visit: (String, [String: Any]) -> Void) {
        if let dict = node as? [String: Any] {
            // Dictionary order is random; renderer dicts hold one renderer key so order rarely matters,
            // but sort so "header" is seen before "contents" deterministically.
            for k in dict.keys.sorted(by: { ($0 == "header" ? 0 : 1, $0) < ($1 == "header" ? 0 : 1, $1) }) {
                let v = dict[k]!
                if keys.contains(k), let r = v as? [String: Any] {
                    visit(k, r)
                    if !descendInto.contains(k) { continue }
                }
                walkKeys(v, keys, descendInto: descendInto, visit: visit)
            }
        } else if let array = node as? [Any] {
            for v in array { walkKeys(v, keys, descendInto: descendInto, visit: visit) }
        }
    }

    private static func pageType(_ endpoint: [String: Any]?) -> (Ref.Kind, String)? {
        guard let browse = endpoint?["browseEndpoint"] as? [String: Any], let id = browse["browseId"] as? String else { return nil }
        let type = (((browse["browseEndpointContextSupportedConfigs"] as? [String: Any])?["browseEndpointContextMusicConfig"]
                     as? [String: Any])?["pageType"] as? String) ?? ""
        switch type {
        case "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_AUDIOBOOK": return (.album, id)
        case "MUSIC_PAGE_TYPE_ARTIST": return (.artist, id)
        case "MUSIC_PAGE_TYPE_PLAYLIST": return (.playlist, id)
        default: return nil
        }
    }

    /// Where tapping the row goes, if it opens a page rather than playing.
    private static func browseTarget(_ r: [String: Any]) -> (Ref.Kind, String)? {
        if let t = pageType(r["navigationEndpoint"] as? [String: Any]) ?? pageType(r["onTap"] as? [String: Any]) { return t }
        // Search "top result" card: the title run carries the endpoint.
        guard r["flexColumns"] == nil, let run = InnerTubeJSON.rawRuns(r["title"] as? [String: Any]).first else { return nil }
        return pageType(run["navigationEndpoint"] as? [String: Any])
    }

    /// Cards keep art under "thumbnailRenderer", rows under "thumbnail".
    static func thumbnail(_ r: [String: Any]) -> String? {
        for key in ["thumbnail", "thumbnailRenderer"] {
            guard let t = r[key] as? [String: Any] else { continue }
            let inner = (t["musicThumbnailRenderer"] as? [String: Any]) ?? t
            if let url = InnerTubeJSON.nestedThumbnailURL(inner) ?? InnerTubeJSON.lastThumbnailURL(inner["thumbnails"] as? [[String: Any]]) {
                return url
            }
        }
        return InnerTubeJSON.musicThumbnailURL(r)
    }

    private static func titleText(_ r: [String: Any]) -> String? {
        if let flex = r["flexColumns"] as? [[String: Any]] {
            let col = (flex.first?["musicResponsiveListItemFlexColumnRenderer"] as? [String: Any])?["text"] as? [String: Any]
            return InnerTubeJSON.runsText(col)
        }
        return InnerTubeJSON.runsText(r["title"] as? [String: Any])
    }

    private static func subtitleTexts(_ r: [String: Any]) -> [String] {
        if let flex = r["flexColumns"] as? [[String: Any]], flex.count > 1 {
            let col = (flex[1]["musicResponsiveListItemFlexColumnRenderer"] as? [String: Any])?["text"] as? [String: Any]
            return InnerTubeJSON.runsTexts(col).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && $0 != "•" }
        }
        return InnerTubeJSON.runsTexts(r["subtitle"] as? [String: Any]).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && $0 != "•" }
    }
}
