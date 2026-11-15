import AppKit
import SwiftData
import SwiftUI

@main
struct NiveMacApp: App {
    let container = try! ModelContainer(for: Track.self, Playlist.self)
    @State private var player: Player
    @State private var downloads: Downloads

    init() {
        // Ready at launch, not when the window appears: Shortcuts can start Nive in the background with no window.
        let player = Player(), downloads = Downloads()
        player.context = container.mainContext
        downloads.context = container.mainContext
        _player = State(initialValue: player)
        _downloads = State(initialValue: downloads)
    }
    @State private var auth = Auth()

    var body: some Scene {
        WindowGroup("Nive") {
            MacRootView()
                .environment(player)
                .environment(downloads)
                .environment(auth)
                .frame(minWidth: 960, minHeight: 620)
                .task {
                    SpaceKey.install(player)
                    await auth.restore()
                    #if DEBUG
                    // Smoke test: NIVE_PLAY="query" plays the first result; NIVE_SEEK_END=30 jumps near the end.
                    if let q = ProcessInfo.processInfo.environment["NIVE_PLAY"], let song = try? await YT.search(q).first {
                        player.play([song])
                        if let back = ProcessInfo.processInfo.environment["NIVE_SEEK_END"].flatMap(Double.init) {
                            while !player.isPlaying { try? await Task.sleep(for: .milliseconds(200)) }
                            try? await Task.sleep(for: .seconds(3))
                            player.seek(to: player.duration - back)
                        }
                    }
                    #endif
                }
        }
        .defaultSize(width: 1240, height: 820)
        .modelContainer(container)
        .commands { PlayerCommands(player: player) }

        Settings {
            SettingsView()
                .environment(player)
                .environment(downloads)
                .environment(auth)
                .frame(width: 500, height: 620)
                .modelContainer(container)
        }
    }
}

/// Space toggles play/pause anywhere except while typing (like Apple Music).
enum SpaceKey {
    @MainActor static func install(_ player: Player) {
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            guard event.keyCode == 49, event.modifierFlags.intersection(.deviceIndependentFlagsMask).isEmpty,
                  !(NSApp.keyWindow?.firstResponder is NSText) else { return event }
            player.toggle()
            return nil
        }
    }
}

/// Controls menu with keyboard shortcuts. Media keys work through the system Now Playing integration.
struct PlayerCommands: Commands {
    let player: Player
    var body: some Commands {
        CommandMenu("Controls") {
            Button(player.isPlaying ? "Pause" : "Play") { player.toggle() }
            Button("Next") { player.next() }.keyboardShortcut(.rightArrow, modifiers: .command)
            Button("Previous") { player.previous() }.keyboardShortcut(.leftArrow, modifiers: .command)
            Divider()
            Button(player.shuffle ? "Turn Shuffle Off" : "Shuffle") { player.toggleShuffle() }
            Button("Repeat: \(player.repeatMode.rawValue.capitalized)") { player.repeatMode = player.repeatMode.next }
                .keyboardShortcut("r", modifiers: .command)
            Toggle("AutoMix", isOn: Binding(get: { player.automix }, set: { player.automix = $0 }))
            Divider()
            Button("Volume Up") { player.volume = min(player.volume + 0.1, 1) }.keyboardShortcut(.upArrow, modifiers: .command)
            Button("Volume Down") { player.volume = max(player.volume - 0.1, 0) }.keyboardShortcut(.downArrow, modifiers: .command)
        }
    }
}
