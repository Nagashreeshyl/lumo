import AppIntents
import SwiftData

/// Shortcuts / Siri actions. Audio intents start playback even when Lumo is in the background.

enum LumoIntentError: Error, CustomLocalizedStringResourceConvertible {
    case notReady, notFound(String), empty(String)
    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .notReady: "Lumo isn't ready yet. Open it once and try again."
        case .notFound(let what): "Couldn't find \(what)."
        case .empty(let what): "\(what) is empty."
        }
    }
}

@MainActor private func player() throws -> Player {
    guard let p = Player.shared else { throw LumoIntentError.notReady }
    return p
}

struct PlayPauseIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play / Pause"
    static let description = IntentDescription("Plays or pauses the current song in Lumo.")

    @MainActor func perform() async throws -> some IntentResult {
        let p = try player()
        if p.current == nil { throw LumoIntentError.empty("The queue") }
        p.toggle()
        return .result()
    }
}

struct NextSongIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Next Song"
    static let description = IntentDescription("Skips to the next song in Lumo.")
    @MainActor func perform() async throws -> some IntentResult { try player().next(); return .result() }
}

struct PreviousSongIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Previous Song"
    static let description = IntentDescription("Goes back to the previous song in Lumo.")
    @MainActor func perform() async throws -> some IntentResult { try player().previous(); return .result() }
}

struct PlaySongIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play a Song"
    static let description = IntentDescription("Searches YouTube Music and plays the best match, then keeps going with similar songs.")

    @Parameter(title: "Song", requestValueDialog: "Which song?")
    var query: String

    static var parameterSummary: some ParameterSummary { Summary("Play \(\.$query) in Lumo") }

    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        let p = try player()
        guard let song = try await YT.search(query).first else { throw LumoIntentError.notFound("“\(query)”") }
        p.play([song])
        return .result(dialog: "Playing \(song.title) by \(song.artist).")
    }
}

struct PlayLikedSongsIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Liked Songs"
    static let description = IntentDescription("Plays your Liked Songs in Lumo.")

    @Parameter(title: "Shuffle", default: true)
    var shuffle: Bool

    static var parameterSummary: some ParameterSummary { Summary("Play Liked Songs (shuffle: \(\.$shuffle))") }

    @MainActor func perform() async throws -> some IntentResult {
        let p = try player()
        let liked = (try? p.context?.fetch(FetchDescriptor<Track>(predicate: #Predicate { $0.liked }, sortBy: [SortDescriptor(\.likedAt, order: .reverse)]))) ?? []
        guard !liked.isEmpty else { throw LumoIntentError.empty("Liked Songs") }
        let songs = liked.map(\.song)
        p.play(shuffle ? songs.shuffled() : songs)
        return .result()
    }
}

struct PlaylistEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Playlist"
    static let defaultQuery = PlaylistQuery()
    let id: String          // playlist name
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(id)") }
}

struct PlaylistQuery: EntityQuery {
    @MainActor private func all() -> [PlaylistEntity] {
        let lists = (try? Player.shared?.context?.fetch(FetchDescriptor<Playlist>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)]))) ?? []
        return lists.map { PlaylistEntity(id: $0.name) }
    }
    @MainActor func entities(for identifiers: [String]) async throws -> [PlaylistEntity] { all().filter { identifiers.contains($0.id) } }
    @MainActor func suggestedEntities() async throws -> [PlaylistEntity] { all() }
}

struct PlayPlaylistIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play a Playlist"
    static let description = IntentDescription("Plays one of your Lumo playlists.")

    @Parameter(title: "Playlist") var playlist: PlaylistEntity
    @Parameter(title: "Shuffle", default: false) var shuffle: Bool

    static var parameterSummary: some ParameterSummary { Summary("Play \(\.$playlist) (shuffle: \(\.$shuffle))") }

    @MainActor func perform() async throws -> some IntentResult {
        let p = try player()
        let name = playlist.id
        guard let context = p.context,
              let list = try? context.fetch(FetchDescriptor<Playlist>(predicate: #Predicate { $0.name == name })).first else {
            throw LumoIntentError.notFound("the playlist “\(name)”")
        }
        let songs = list.trackIds.compactMap { context.existingTrack($0)?.song }
        guard !songs.isEmpty else { throw LumoIntentError.empty("“\(name)”") }
        p.play(shuffle ? songs.shuffled() : songs)
        return .result()
    }
}

struct SetAutoMixIntent: AppIntent {
    static let title: LocalizedStringResource = "Set AutoMix"
    static let description = IntentDescription("Turns Lumo's DJ-style AutoMix on or off.")

    @Parameter(title: "AutoMix", default: true) var enabled: Bool

    static var parameterSummary: some ParameterSummary { Summary("Turn AutoMix \(\.$enabled)") }

    @MainActor func perform() async throws -> some IntentResult {
        try player().automix = enabled
        return .result()
    }
}

/// Ready-made shortcuts that show up in Shortcuts and Spotlight and work with Siri.
struct LumoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: PlayLikedSongsIntent(), phrases: ["Play my liked songs in \(.applicationName)",
                                                              "Shuffle my likes in \(.applicationName)"],
                    shortTitle: "Play Liked Songs", systemImageName: "heart.fill")
        AppShortcut(intent: PlayPlaylistIntent(), phrases: ["Play \(\.$playlist) in \(.applicationName)"],
                    shortTitle: "Play Playlist", systemImageName: "music.note.list")
        AppShortcut(intent: PlayPauseIntent(), phrases: ["Pause \(.applicationName)", "Resume \(.applicationName)"],
                    shortTitle: "Play / Pause", systemImageName: "playpause.fill")
        AppShortcut(intent: NextSongIntent(), phrases: ["Next song in \(.applicationName)", "Skip in \(.applicationName)"],
                    shortTitle: "Next Song", systemImageName: "forward.fill")
        AppShortcut(intent: SetAutoMixIntent(), phrases: ["Set AutoMix in \(.applicationName)"],
                    shortTitle: "AutoMix", systemImageName: "dial.high")
    }
}
