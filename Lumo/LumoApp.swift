import SwiftData
import SwiftUI

@main
struct LumoApp: App {
    let container = try! ModelContainer(for: Track.self, Playlist.self)
    @State private var player: Player
    @State private var downloads: Downloads

    init() {
        // Ready at launch, not when the window appears: Shortcuts can start Lumo in the background with no window.
        let player = Player(), downloads = Downloads()
        player.context = container.mainContext
        downloads.context = container.mainContext
        _player = State(initialValue: player)
        _downloads = State(initialValue: downloads)
    }
    @State private var auth = Auth()

    var body: some Scene {
        WindowGroup {
            RootView()
                .updatePrompt()
                .environment(player)
                .environment(downloads)
                .environment(auth)
                .task {
                    await auth.restore()
                    #if DEBUG
                    // Simulator smoke test: LUMO_PLAY="query" plays the first search result.
                    if let q = ProcessInfo.processInfo.environment["LUMO_PLAY"], let song = try? await YT.search(q).first {
                        player.play([song])
                        // LUMO_SEEK_END=40 jumps to 40 s before the end to hear the AutoMix blend.
                        if let back = ProcessInfo.processInfo.environment["LUMO_SEEK_END"].flatMap(Double.init) {
                            while !player.isPlaying { try? await Task.sleep(for: .milliseconds(200)) }
                            try? await Task.sleep(for: .seconds(3))
                            player.seek(to: player.duration - back)
                        }
                    }
                    #endif
                }
        }
        .modelContainer(container)
    }
}

struct RootView: View {
    @Environment(Player.self) private var player
    #if DEBUG
    @State private var tab = ProcessInfo.processInfo.environment["LUMO_TAB"] ?? "home"   // simulator screenshots
    #else
    @State private var tab = "home"
    #endif
    @Namespace private var playerNS

    var body: some View {
        @Bindable var player = player
        TabView(selection: $tab) {
            Tab("Home", systemImage: "house.fill", value: "home") { HomeView() }
            Tab("Explore", systemImage: "safari.fill", value: "explore") { ExploreView() }
            Tab("Library", systemImage: "square.stack.fill", value: "library") { LibraryView() }
            Tab(value: "search", role: .search) { SearchView() }
        }
        .tabViewBottomAccessory {
            MiniPlayer { player.showFullPlayer = true }
                .matchedTransitionSource(id: "player", in: playerNS)
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .fullScreenCover(isPresented: $player.showFullPlayer) {
            FullPlayerView()
                .navigationTransition(.zoom(sourceID: "player", in: playerNS))
        }
        #if DEBUG
        // Simulator smoke test: LUMO_OPEN_PLAYER=1 opens the full player once a song is playing.
        .onChange(of: player.isPlaying) { _, playing in
            if playing, ProcessInfo.processInfo.environment["LUMO_OPEN_PLAYER"] != nil { player.showFullPlayer = true }
        }
        #endif
    }
}
