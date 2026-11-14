import Foundation
import Testing
@testable import Nive

/// Parsing against real YouTube Music responses captured 2026-11-15.
struct BrowseTests {
    private func fixture(_ name: String) throws -> [String: Any] {
        let url = try #require(Bundle(for: Marker.self).url(forResource: name, withExtension: "json"))
        return try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    @Test func movieSearchTopResultIsTheSoundtrackAlbum() throws {
        let page = Parse.searchPage(try fixture("search_movie"))
        guard case .page(let top)? = page.top else { Issue.record("top result not a page: \(String(describing: page.top))"); return }
        #expect(top.kind == .album)
        #expect(top.title == "Kabir Singh")
        #expect(top.id.hasPrefix("MPREb_"))
    }

    @Test func searchListsSongsArtistsAndPlaylistsWithoutEpisodes() throws {
        let page = Parse.searchPage(try fixture("search_movie"))
        let songs = page.items.compactMap { if case .song(let s) = $0 { s } else { nil } }
        let pages = page.items.compactMap { if case .page(let r) = $0 { r } else { nil } }
        #expect(songs.contains { $0.title.hasPrefix("Bekhayali") })
        #expect(pages.contains { $0.kind == .artist && $0.title == "Arijit Singh" })
        #expect(pages.contains { $0.kind == .playlist && !$0.id.hasPrefix("VL") })
        #expect(!songs.contains { $0.artist.hasPrefix("Episode") || $0.title.contains("Sadguru") })
    }

    @Test func searchExposesFilterChips() throws {
        let titles = Parse.searchPage(try fixture("search_movie")).filters.map(\.title)
        #expect(titles.contains("Songs") && titles.contains("Albums") && titles.contains("Artists"))
        #expect(!titles.contains("Episodes") && !titles.contains("Podcasts"))
    }

    @Test func albumPageHasHeaderAndAllTracks() throws {
        let page = Parse.browsePage(try fixture("album"))
        #expect(page.header?.title == "Kabir Singh")
        #expect(page.header?.subtitle.contains("Various Artists") == true)
        #expect(page.sections.first?.songs.count == 9)
        #expect(page.sections.first?.songs.first?.title == "Bekhayali")
    }

    @Test func artistPageHasTopSongsAndAlbums() throws {
        let page = Parse.browsePage(try fixture("artist"))
        #expect(page.header?.title == "Arijit Singh")
        #expect(page.sections.contains { $0.title == "Top songs" && !$0.songs.isEmpty })
        #expect(page.sections.contains { $0.title == "Albums" && $0.items.allSatisfy { if case .page(let r) = $0 { r.kind == .album } else { false } } })
    }

    @Test func exploreAlbumCardsHaveArtwork() throws {
        let sections = Parse.browsePage(try fixture("explore")).sections
        let albums = try #require(sections.first { $0.title == "New albums & singles" })
        guard case .page(let first)? = albums.items.first else { Issue.record("no album card"); return }
        #expect(first.thumbnail != nil)
    }

    @Test func exploreHasMoodTilesWithColors() throws {
        let sections = Parse.browsePage(try fixture("explore")).sections
        let moods = try #require(sections.first { $0.title == "Moods & genres" })
        guard case .page(let chill)? = moods.items.first else { Issue.record("no mood tile"); return }
        #expect(chill.kind == .category && chill.params != nil && chill.color != nil)
    }
}

private final class Marker {}
