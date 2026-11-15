import Foundation
import Testing
@testable import Nive

struct LyricsTests {
    @Test func parsesTimestampsAndSorts() {
        let lines = Lyrics.parseLRC("""
        [ar:Someone]
        [00:12.50]Second
        [00:01.00][00:20.00]Chorus
        [01:02.25]Last
        """)
        #expect(lines.map(\.text) == ["Chorus", "Second", "Chorus", "Last"])
        #expect(lines.map(\.time) == [1.0, 12.5, 20.0, 62.25])
    }

    @Test func activeLineFollowsTime() {
        let lines = Lyrics.parseLRC("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        #expect(Lyrics.activeIndex(in: lines, at: 0.0) == nil)
        #expect(Lyrics.activeIndex(in: lines, at: 6.0) == 1)
        #expect(Lyrics.activeIndex(in: lines, at: 100) == 2)
    }

    @Test func cleansVideoSuffixes() {
        #expect(Lyrics.cleanTitle("Blinding Lights (Official Video)") == "Blinding Lights")
        #expect(Lyrics.cleanTitle("Song [Lyric Video]") == "Song")
        #expect(Lyrics.cleanTitle("Song (feat. X)") == "Song (feat. X)")
    }

    @Test func baseTitleStripsArtistPrefixFeaturesAndBrackets() {
        #expect(Lyrics.baseTitle("The Weeknd - I Feel It Coming ft. Daft Punk (Official Video)", artist: "The Weeknd") == "I Feel It Coming")
        #expect(Lyrics.baseTitle("Get Lucky (Official Audio) (feat. Pharrell Williams & Nile Rodgers)", artist: "Daft Punk") == "Get Lucky")
        #expect(Lyrics.baseTitle("Kesariya | Brahmastra | Arijit Singh", artist: "Arijit Singh") == "Kesariya")
        #expect(Lyrics.baseTitle("Shape of You", artist: "Ed Sheeran") == "Shape of You")
    }

    @Test func romanizesTamilAndHindiToEnglishLetters() {
        let tamil = Lyrics.romanize("வணக்கம்")
        #expect(!tamil.isEmpty)
        #expect(tamil.unicodeScalars.allSatisfy { $0.isASCII }, "got \(tamil)")
        #expect(Lyrics.romanize("मुझको इतना बताए कोई").unicodeScalars.allSatisfy { $0.isASCII })
        #expect(Lyrics.romanize("Shape of you") == "Shape of you")
    }

    @Test func detectsLyricsThatNeedRomanizing() {
        #expect(Lyrics.needsRomanization([LyricLine(id: 0, time: 1, text: "உன்னை நான்")]))
        #expect(!Lyrics.needsRomanization([LyricLine(id: 0, time: 1, text: "I'm in love with the shape of you")]))
    }

    @Test func parsesLyricsPlusResponse() throws {
        let json = #"""
        {"type":"Word","lyrics":[{"time":27395,"duration":1565,"text":"I been tryna call"},{"time":29100,"duration":900,"text":"I been on my own"}]}
        """#
        let lines = Lyrics.parseLyricsPlus(Data(json.utf8))
        #expect(lines.map(\.text) == ["I been tryna call", "I been on my own"])
        #expect(lines.first?.time == 27.395)
        #expect(Lyrics.parseLyricsPlus(Data(#"{"error":{"status":404}}"#.utf8)).isEmpty)
    }

    @Test func lyricQueriesGoFromStrictToLoose() {
        let song = Song(id: "x", title: "The Weeknd - Blinding Lights (Official Video)", artist: "The Weeknd", thumbnail: nil, duration: 200)
        let q = Lyrics.queries(for: song)
        #expect(q.first == ["track_name": "Blinding Lights", "artist_name": "The Weeknd"])
        #expect(q.contains(["q": "Blinding Lights The Weeknd"]))
        #expect(Set(q.map { $0.description }).count == q.count)   // no duplicate requests
    }
}

struct RecommenderTests {
    private func songs(_ ids: [String]) -> [Song] { ids.map { Song(id: $0, title: $0, artist: "", thumbnail: nil, duration: 0) } }

    @Test func interleaveRoundRobinsAndDedupes() {
        let out = Recommender.interleave([songs(["a", "b", "c"]), songs(["x", "a", "y"])], excluding: ["c"], limit: 10)
        #expect(out.map(\.id) == ["a", "x", "b", "y"])
    }

    @Test func interleaveRespectsLimit() {
        #expect(Recommender.interleave([songs(["a", "b", "c"])], excluding: [], limit: 2).count == 2)
    }

    @Test func onRepeatRanksMostPlayedAndIgnoresOneOffsAndDisliked() {
        func entry(_ id: String, plays: Int, disliked: Bool = false, daysAgo: Double = 1) -> Recommender.Listen {
            Recommender.Listen(song: Song(id: id, title: id, artist: "", thumbnail: nil, duration: 0), plays: plays,
                               disliked: disliked, lastPlayed: Date.now.addingTimeInterval(-daysAgo * 86_400))
        }
        let picks = Recommender.onRepeat([
            entry("once", plays: 1), entry("fav", plays: 9), entry("often", plays: 4),
            entry("hated", plays: 7, disliked: true), entry("old", plays: 6, daysAgo: 120),
        ])
        #expect(picks.map(\.id) == ["fav", "often"])
    }

    @Test func listeningTimeFormatsHoursAndMinutes() {
        #expect(StatsView.listening(59) == "0m")
        #expect(StatsView.listening(45 * 60) == "45m")
        #expect(StatsView.listening(3 * 3600 + 5 * 60) == "3h 5m")
    }

    @Test func dislikedNeedsRepeatedSkips() {
        #expect(!Recommender.isDisliked(1, 0))
        #expect(Recommender.isDisliked(2, 0))
        #expect(!Recommender.isDisliked(2, 10))
    }
}

struct ParseTests {
    @Test func extractsSongFromResponsiveListItem() throws {
        let renderer: [String: Any] = [
            "playlistItemData": ["videoId": "abc123"],
            "flexColumns": [
                ["musicResponsiveListItemFlexColumnRenderer": ["text": ["runs": [["text": "Title"]]]]],
                ["musicResponsiveListItemFlexColumnRenderer": ["text": ["runs": [
                    ["text": "Song"], ["text": " • "], ["text": "Artist"], ["text": " • "], ["text": "3:21"],
                ]]]],
            ],
        ]
        let s = try #require(Parse.song(from: renderer))
        #expect(s.id == "abc123")
        #expect(s.title == "Title")
        #expect(s.artist == "Artist")
    }

    @Test func songTitlesDropVideoTags() {
        let r: [String: Any] = ["videoId": "v", "title": ["runs": [["text": "Dracula (Official Video)"]]]]
        #expect(Parse.song(from: r)?.title == "Dracula")
    }

    @Test func ignoresAlbumsWithoutVideo() {
        let album: [String: Any] = ["title": ["runs": [["text": "Album"]]],
                                    "navigationEndpoint": ["browseEndpoint": ["browseId": "MPRE1"]]]
        #expect(Parse.song(from: album) == nil)
    }

    @Test func walksNestedJSONAndDedupes() {
        let item: [String: Any] = ["videoId": "v1", "title": ["runs": [["text": "T"]]]]
        let json: [String: Any] = ["a": [["playlistPanelVideoRenderer": item], ["playlistPanelVideoRenderer": item]]]
        #expect(Parse.songs(in: json).map(\.id) == ["v1"])
    }

    @Test func displayTitleDropsArtistPrefixAndVideoTags() {
        #expect(YT.displayTitle("The Weeknd - Save Your Tears (Official Video)", artist: "The Weeknd") == "Save Your Tears")
        #expect(YT.displayTitle("Kesariya - Brahmastra", artist: "Arijit Singh") == "Kesariya - Brahmastra")
    }

    @Test func rankPutsTitlesStartingWithQueryFirst() {
        let songs = ["Jai Ho", "Aashiqui", "Tum Hi Ho", "aankh marey", "Apna Bana Le"].map { Song(id: $0, title: $0, artist: "", thumbnail: nil, duration: 0) }
        #expect(Parse.rank(songs, for: "a").map(\.id) == ["Aashiqui", "aankh marey", "Apna Bana Le", "Jai Ho", "Tum Hi Ho"])
        #expect(Parse.rank(songs, for: "aa").map(\.id).prefix(2) == ["Aashiqui", "aankh marey"])
    }

    @Test func topResultCardComesFirstWithItsArtist() {
        let related: [String: Any] = ["videoId": "remix", "title": ["runs": [["text": "Remix"]]]]
        let card: [String: Any] = [
            "title": ["runs": [["text": "Levitating"]]],
            "subtitle": ["runs": [["text": "Song"], ["text": " • "], ["text": "Dua Lipa"], ["text": " • "], ["text": "3:24"]]],
            "onTap": ["watchEndpoint": ["videoId": "real"]],
            "contents": [["playlistPanelVideoRenderer": related]],
        ]
        let songs = Parse.songs(in: ["contents": [["musicCardShelfRenderer": card]]])
        #expect(songs.map(\.id) == ["real", "remix"])
        #expect(songs.first?.artist == "Dua Lipa")
    }
}
