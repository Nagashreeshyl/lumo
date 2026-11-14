import Foundation
import SwiftData

/// Builds the Home feed: personal shelves from local listening + YouTube Music's own home.
/// shortcut: simple weighted seeds, no ML; upgrade only if picks feel stale.
enum Recommender {
    /// Builds Home progressively: `update` is called each time a source arrives (YouTube home, Quick picks,
    /// "Because you liked"), so the screen fills in as fast as the quickest source instead of the slowest.
    static func home(context: ModelContext, update: @escaping ([Shelf]) -> Void) async {
        let tracks = (try? context.fetch(FetchDescriptor<Track>())) ?? []
        let disliked = Set(tracks.filter { $0.disliked || isDisliked($0.skipCount, $0.playCount) }.map(\.videoId))
        let recent = tracks.filter { $0.lastPlayed != nil }.sorted { $0.lastPlayed! > $1.lastPlayed! }.prefix(20).map(\.song)
        let seeds = seeds(tracks)

        enum Part { case quick([Song]), liked(Shelf?), youtube([Shelf]) }
        var quick: [Song] = [], liked: Shelf?, youtube: [Shelf] = []
        await withTaskGroup(of: Part.self) { group in
            group.addTask { .quick(await quickPicks(seeds: seeds, excluding: disliked)) }
            group.addTask { .liked(await becauseYouLiked(tracks, excluding: disliked)) }
            group.addTask { .youtube((try? await YT.home()) ?? []) }
            for await part in group {
                switch part {
                case .quick(let q): quick = q
                case .liked(let l): liked = l
                case .youtube(let y): youtube = y
                }
                var shelves: [Shelf] = []
                if !quick.isEmpty { shelves.append(Shelf(title: "Quick picks", songs: quick)) }
                if let liked { shelves.append(liked) }
                if !recent.isEmpty { shelves.append(Shelf(title: "Recently played", songs: recent)) }
                let personal = Set(shelves.map(\.title))
                shelves += youtube.filter { !personal.contains($0.title) }
                // Don't replace what's on screen with a half-empty page from a source that failed.
                if !quick.isEmpty || !youtube.isEmpty { update(shelves) }
            }
        }
    }

    /// Skipped much more often than finished.
    static func isDisliked(_ skips: Int, _ plays: Int) -> Bool { skips >= 2 && skips > plays / 2 }

    /// Up to 4 seed songs: most-loved first, favouring recent activity.
    static func seeds(_ tracks: [Track]) -> [String] {
        let now = Date.now
        return tracks
            .map { t -> (String, Double) in
                let days = t.lastPlayed.map { now.timeIntervalSince($0) / 86_400 } ?? 365
                let score = Double(t.playCount) + (t.liked ? 5 : 0) - Double(t.skipCount) * 2 - days * 0.1
                return (t.videoId, score)
            }
            .filter { $0.1 > 0 }
            .sorted { $0.1 > $1.1 }
            .prefix(4)
            .map(\.0)
    }

    private static func quickPicks(seeds: [String], excluding disliked: Set<String>) async -> [Song] {
        let radios = await withTaskGroup(of: [Song].self) { group in
            for id in seeds { group.addTask { (try? await YT.radio(for: id)) ?? [] } }
            return await group.reduce(into: []) { $0.append($1) }
        }
        return interleave(radios, excluding: disliked.union(seeds), limit: 20)
    }

    private static func becauseYouLiked(_ tracks: [Track], excluding disliked: Set<String>) async -> Shelf? {
        guard let fav = tracks.filter(\.liked).max(by: { ($0.likedAt ?? .distantPast) < ($1.likedAt ?? .distantPast) }),
              let radio = try? await YT.radio(for: fav.videoId) else { return nil }
        let songs = radio.filter { !disliked.contains($0.id) }.prefix(15)
        return songs.isEmpty ? nil : Shelf(title: "Because you liked \(fav.title)", songs: Array(songs))
    }

    /// Round-robin merge so every seed contributes, without duplicates.
    static func interleave(_ lists: [[Song]], excluding: Set<String>, limit: Int) -> [Song] {
        var seen = excluding
        var out: [Song] = []
        let longest = lists.map(\.count).max() ?? 0
        for i in 0..<longest {
            for list in lists where i < list.count && seen.insert(list[i].id).inserted {
                out.append(list[i])
                if out.count == limit { return out }
            }
        }
        return out
    }
}
