import Foundation

struct LyricLine: Identifiable, Hashable, Sendable {
    let id: Int
    let time: Double?       // nil for unsynced lyrics
    let text: String
}

/// Synced lyrics from LRCLIB (free, no key).
enum Lyrics {
    /// LyricsPlus (Apple Music, Spotify, Deezer… word-synced) first, then strict-to-loose LRCLIB queries.
    static func fetch(for song: Song) async throws -> [LyricLine] {
        let plus = await lyricsPlus(title: baseTitle(song.title, artist: song.artist), artist: song.artist, duration: song.duration)
        if !plus.isEmpty {
            Log.info("lyrics via LyricsPlus")
            return plus
        }
        for (i, query) in queries(for: song).enumerated() {
            var c = URLComponents(string: "https://lrclib.net/api/search")!
            c.queryItems = query.sorted { $0.key > $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
            var request = URLRequest(url: c.url!)
            request.setValue("Nive (https://github.com/Nagashreeshyl/nive)", forHTTPHeaderField: "User-Agent")
            guard let (data, _) = try? await URLSession.shared.data(for: request),
                  var results = try? JSONDecoder().decode([Result].self, from: data) else { continue }
            // Loose free-text matches must at least be the same length, or we'd show another song's words.
            if query["q"] != nil, song.duration > 0 {
                results = results.filter { abs(($0.duration ?? 0) - Double(song.duration)) <= 15 }
            }
            // Prefer synced lyrics whose length matches the song.
            let best = results
                .filter { $0.syncedLyrics?.isEmpty == false }
                .min { abs(($0.duration ?? 0) - Double(song.duration)) < abs(($1.duration ?? 0) - Double(song.duration)) }
            if let synced = best?.syncedLyrics {
                Log.info("lyrics synced via query \(i)")
                return parseLRC(synced)
            }
            if let plain = results.first(where: { $0.plainLyrics?.isEmpty == false })?.plainLyrics {
                Log.info("lyrics plain via query \(i)")
                return plain.split(separator: "\n", omittingEmptySubsequences: false)
                    .enumerated().map { LyricLine(id: $0.offset, time: nil, text: String($0.element)) }
            }
        }
        Log.info("lyrics not found for \(song.id)")
        return []
    }

    /// Public LyricsPlus servers (the same ones Mochy uses), tried in order.
    private static let lyricsPlusServers = ["https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id"]

    private static func lyricsPlus(title: String, artist: String, duration: Int) async -> [LyricLine] {
        for server in lyricsPlusServers {
            var c = URLComponents(string: server + "/v2/lyrics/get")!
            c.queryItems = [URLQueryItem(name: "title", value: title), URLQueryItem(name: "artist", value: artist)]
            if duration > 0 { c.queryItems?.append(URLQueryItem(name: "duration", value: String(duration))) }
            var request = URLRequest(url: c.url!, timeoutInterval: 8)
            request.setValue("Nive (https://github.com/Nagashreeshyl/nive)", forHTTPHeaderField: "User-Agent")
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  (response as? HTTPURLResponse)?.statusCode == 200 else { continue }
            let lines = parseLyricsPlus(data)
            if !lines.isEmpty { return lines }
            return []   // the server answered "not found"; the other mirror has the same sources
        }
        return []
    }

    /// `{"lyrics":[{"time": ms, "text": "..."}]}` → synced lines.
    static func parseLyricsPlus(_ data: Data) -> [LyricLine] {
        struct Response: Decodable { let lyrics: [Line]? }
        struct Line: Decodable { let time: Double?; let text: String? }
        guard let lines = (try? JSONDecoder().decode(Response.self, from: data))?.lyrics else { return [] }
        let synced = lines.allSatisfy { $0.time != nil }
        return lines.enumerated().map { LyricLine(id: $0.offset, time: synced ? $0.element.time! / 1000 : nil, text: $0.element.text ?? "") }
    }

    /// Strict to loose, without duplicates.
    static func queries(for song: Song) -> [[String: String]] {
        let base = baseTitle(song.title, artist: song.artist)
        let clean = cleanTitle(song.title)
        var out: [[String: String]] = []
        for q in [
            ["track_name": base, "artist_name": song.artist],
            ["track_name": clean, "artist_name": song.artist],
            ["q": "\(base) \(song.artist)"],
            ["q": base],
        ] where !out.contains(q) && !(q.values.contains { $0.trimmingCharacters(in: .whitespaces).isEmpty }) {
            out.append(q)
        }
        return out
    }

    /// Just the song name: drops "Artist - " prefixes, "| Movie | Singer" tails, brackets and featured artists.
    static func baseTitle(_ title: String, artist: String) -> String {
        var t = title
        if let r = t.range(of: " - "), t[..<r.lowerBound].localizedCaseInsensitiveContains(artist) || artist.isEmpty {
            t = String(t[r.upperBound...])
        }
        if let bar = t.firstIndex(of: "|") { t = String(t[..<bar]) }
        t = t.replacingOccurrences(of: #"\s*[\(\[][^\)\]]*[\)\]]"#, with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: #"\s+(ft\.?|feat\.?|featuring)\s.*$"#, with: "", options: [.regularExpression, .caseInsensitive])
        return t.trimmingCharacters(in: .whitespaces)
    }

    private struct Result: Decodable {
        let duration: Double?
        let syncedLyrics: String?
        let plainLyrics: String?
    }

    /// "[01:23.45] line" → LyricLine(time: 83.45). Lines with several stamps are repeated.
    static func parseLRC(_ lrc: String) -> [LyricLine] {
        var lines: [(Double, String)] = []
        for raw in lrc.split(whereSeparator: \.isNewline) {
            var rest = Substring(raw)
            var times: [Double] = []
            while rest.hasPrefix("["), let close = rest.firstIndex(of: "]") {
                let stamp = rest[rest.index(after: rest.startIndex)..<close]
                let parts = stamp.split(separator: ":")
                if parts.count == 2, let m = Double(parts[0]), let s = Double(parts[1]) {
                    times.append(m * 60 + s)
                }
                rest = rest[rest.index(after: close)...]
            }
            let text = rest.trimmingCharacters(in: .whitespaces)
            for t in times { lines.append((t, text)) }
        }
        return lines.sorted { $0.0 < $1.0 }.enumerated().map { LyricLine(id: $0.offset, time: $0.element.0, text: $0.element.1) }
    }

    /// Non-Latin lyrics (Tamil, Hindi, Korean…) in English letters, using iOS's built-in transliteration.
    /// Diacritics are dropped so it reads like people type it ("vanakkam", not "vaṇakkam").
    static func romanize(_ text: String) -> String {
        let latin = text.applyingTransform(.toLatin, reverse: false) ?? text
        return latin.applyingTransform(.stripDiacritics, reverse: false) ?? latin
    }

    /// True when the lyrics are mostly in a non-Latin script.
    static func needsRomanization(_ lines: [LyricLine]) -> Bool {
        let letters = lines.prefix(20).flatMap { $0.text.unicodeScalars }.filter { CharacterSet.letters.contains($0) }
        guard !letters.isEmpty else { return false }
        return letters.filter { !$0.isASCII }.count * 2 > letters.count
    }

    /// Index of the line being sung at `time`.
    static func activeIndex(in lines: [LyricLine], at time: Double) -> Int? {
        lines.lastIndex { ($0.time ?? .infinity) <= time + 0.2 }
    }

    /// Drops "(Official Video)", "[Lyrics]" etc. so LRCLIB matches.
    static func cleanTitle(_ title: String) -> String {
        title.replacingOccurrences(of: #"\s*[\(\[][^\)\]]*(official|lyric|video|audio|visualizer|mv)[^\)\]]*[\)\]]"#,
                                   with: "", options: [.regularExpression, .caseInsensitive])
            .trimmingCharacters(in: .whitespaces)
    }
}
