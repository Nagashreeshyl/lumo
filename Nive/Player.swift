import AVFoundation
import MediaPlayer
import SwiftData
import SwiftUI

/// Owns playback, the queue, lock-screen controls and autoplay radio.
@Observable
final class Player {
    private(set) var queue: [Song] = []
    private(set) var index = 0
    private(set) var isPlaying = false
    private(set) var isLoading = false
    private(set) var currentTime: Double = 0
    private(set) var duration: Double = 0
    var autoplay = true
    var showFullPlayer = false   // UI: full-screen player presented
    /// App volume (Mac player bar); iPhone uses the hardware buttons.
    var volume: Double = UserDefaults.standard.object(forKey: "volume") as? Double ?? 1 {
        didSet { engine.volume = Float(volume); UserDefaults.standard.set(volume, forKey: "volume") }
    }
    private(set) var isMixing = false   // an AutoMix blend is playing right now
    var repeatMode = RepeatMode.off {
        didSet { reschedule(); updateNowPlaying() }
    }
    private(set) var shuffle = false
    private var unshuffledQueue: [Song]?
    var automix = UserDefaults.standard.object(forKey: "automix") as? Bool ?? true {
        didSet { UserDefaults.standard.set(automix, forKey: "automix"); reschedule() }
    }
    var errorMessage: String?

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }
    var upNext: ArraySlice<Song> { queue.indices.contains(index + 1) ? queue[(index + 1)...] : [] }

    var context: ModelContext? {
        didSet {
            let ids = (try? context?.fetch(FetchDescriptor<Track>(predicate: #Predicate { $0.disliked })))?.map(\.videoId) ?? []
            disliked = Set(ids)
        }
    }
    /// Songs marked "Not Interested": hidden everywhere in the app.
    private(set) var disliked: Set<String> = []

    /// `songs` without anything the user said they don't want.
    func visible(_ songs: [Song]) -> [Song] { disliked.isEmpty ? songs : songs.filter { !disliked.contains($0.id) } }
    func visible(_ items: [Item]) -> [Item] {
        disliked.isEmpty ? items : items.filter { if case .song(let s) = $0 { !disliked.contains(s.id) } else { true } }
    }
    private let engine = MixEngine()
    private var prepared: PreparedTrack?          // what the engine is playing now
    private var loadTask: Task<Void, Never>?
    private var prefetchTask: Task<Void, Never>?
    private var refilling = false
    // Mood keeping: the song the user chose, its recommendations, what autoplay added, and the feel so far.
    private var sessionAnchor: Song?
    private var anchorMix: [Song] = []
    private var autoAdded: Set<String> = []
    private var sessionProfile: [(id: String, analysis: TrackAnalysis)] = []
    private var moodSkips = 0
    private var playCounted = false       // a play counts after 30 s (or the whole song if shorter)
    private var lastTickTime: Double?
    private var failuresInARow = 0

    init() {
        Platform.configureAudio()
        engine.onTick = { [weak self] in self?.tick() }
        engine.volume = Float(volume)
        engine.onMixing = { [weak self] mixing in withAnimation(.smooth) { self?.isMixing = mixing } }
        engine.onFinished = { [weak self] in
            guard let self else { return }
            if repeatMode == .one, let prepared {
                engine.start(prepared)   // replay the same song
                didStart()
                return
            }
            Log.info("song ended with nothing scheduled -> hard load of next")
            next(finished: true)
        }
        engine.onSwapped = { [weak self] id in self?.didSwap(to: id) }
        let nc = NotificationCenter.default
        #if os(iOS)
        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let opts = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            MainActor.assumeIsolated {
                if type == .began { self?.pause() } else if type == .ended, opts.contains(.shouldResume) { self?.resume() }
            }
        }
        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            // Headphones unplugged: pause like every music app.
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            MainActor.assumeIsolated { if reason == .oldDeviceUnavailable { self?.pause() } }
        }
        #endif
        setupRemoteCommands()
    }

    // MARK: - Queue control

    func play(_ all: [Song], at tapped: Int = 0) {
        guard all.indices.contains(tapped) else { return }
        // Disliked songs never play from a list; the tapped song always does.
        let songs = all.enumerated().filter { $0.offset == tapped || !disliked.contains($0.element.id) }.map(\.element)
        let start = songs.firstIndex(of: all[tapped]) ?? 0
        sessionAnchor = all[tapped]
        anchorMix = []
        autoAdded = []
        sessionProfile = []
        recordSkipIfNeeded()
        if shuffle {
            unshuffledQueue = songs
            queue = QueueOps.shuffled(songs, current: start)
        } else {
            queue = songs
        }
        index = start
        load()
    }

    func playNext(_ song: Song) {
        queue.insert(song, at: min(index + 1, queue.count))
        if queue.count == 1 { load() } else { reschedule() }
    }

    func addToQueue(_ song: Song) {
        queue.append(song)
        if queue.count == 1 { load() } else if queue.count == index + 2 { reschedule() }
    }

    func jump(to i: Int) {
        guard queue.indices.contains(i) else { return }
        recordSkipIfNeeded()
        index = i
        load()
    }

    func next(finished: Bool = false) {
        if !finished { recordSkipIfNeeded() }
        guard index + 1 < queue.count else {
            if repeatMode == .all, !queue.isEmpty { index = 0; load() } else { pause() }
            return
        }
        // Manual skip = instant cut. If the next song is already loaded, switch without waiting.
        if !finished, let id = engine.cutToScheduled() {
            engine.resume()        // a skip while paused starts playing, like any player
            isPlaying = true
            advance(to: id)
            didStart()
            return
        }
        index += 1
        load()
    }

    func previous() {
        if currentTime > 3 || index == 0 { seek(to: 0); return }
        index -= 1
        load()
    }

    func toggle() { isPlaying ? pause() : resume() }

    /// Swiped away in Up Next: drop it and never auto-queue or recommend it again.
    func notInterested(at i: Int) {
        guard queue.indices.contains(i), i != index else { return }
        let song = queue.remove(at: i)
        unshuffledQueue?.removeAll { $0.id == song.id }
        if i < index { index -= 1 }
        markDisliked(song)
        if i == index + 1 { reschedule() }   // it was the song we were about to blend into
        Task { await refillIfNeeded() }
    }

    /// "Not Interested" from anywhere: hide it everywhere and drop it from the queue.
    func dislike(_ song: Song) {
        if let i = queue.indices.dropFirst(index + 1).first(where: { queue[$0].id == song.id }) {
            notInterested(at: i)
        } else {
            markDisliked(song)
        }
    }

    func undislike(_ id: String) {
        disliked.remove(id)
        context?.existingTrack(id)?.disliked = false
    }

    private func markDisliked(_ song: Song) {
        disliked.insert(song.id)
        context.map { t in
            let track = t.track(for: song)
            track.disliked = true
            track.liked = false
        }
    }

    func toggleShuffle() {
        if shuffle, let original = unshuffledQueue {
            (queue, index) = QueueOps.unshuffled(original: original, shuffled: queue, current: index)
            unshuffledQueue = nil
        } else {
            unshuffledQueue = queue
            queue = QueueOps.shuffled(queue, current: index)
        }
        shuffle.toggle()
        reschedule()
        updateNowPlaying()
    }

    func resume() {
        guard prepared != nil else { return }
        engine.resume()
        isPlaying = true
        updateNowPlaying()
    }

    func pause() {
        engine.pause()
        isPlaying = false
        updateNowPlaying()
    }

    func seek(to seconds: Double) {
        currentTime = seconds
        engine.seek(to: seconds)   // cancels any planned blend…
        reschedule()               // …so plan it again from the new position
        updateNowPlaying()
    }

    // MARK: - Loading

    private func load() {
        guard let song = current else { return }
        loadTask?.cancel()
        prefetchTask?.cancel()
        engine.pause()
        prepared = nil
        currentTime = 0
        duration = Double(song.duration)
        isLoading = true
        errorMessage = nil
        updateNowPlaying()
        loadTask = Task {
            do {
                let track = try await AudioCache.shared.prepare(song, localFile: localFile(song))
                guard !Task.isCancelled, current?.id == song.id else { return }
                engine.start(track)
                prepared = track
                isLoading = false
                isPlaying = true
                failuresInARow = 0
                didStart()
            } catch {
                guard !Task.isCancelled, current?.id == song.id else { return }
                Log.info("load failed \(song.id): \(error)")
                isLoading = false
                failuresInARow += 1
                // Several failures in a row means the network or YouTube is down: stop instead of burning the queue.
                guard failuresInARow < 3 else {
                    isPlaying = false
                    errorMessage = "Can't reach YouTube right now. Check your connection and try again."
                    return
                }
                errorMessage = "Couldn't play “\(song.title)”"
                // Unplayable song (region lock, removed): move on instead of stalling.
                try? await Task.sleep(for: .seconds(1.5))
                if current?.id == song.id { next(finished: true) }
            }
        }
    }

    private func localFile(_ song: Song) -> URL? {
        guard let url = context?.existingTrack(song.id)?.localURL, FileManager.default.fileExists(atPath: url.path()) else { return nil }
        return url
    }

    /// The engine blended into the next song.
    private func didSwap(to id: String) {
        Log.info("blend swapped to \(id)")
        advance(to: id)
        didStart()
    }

    /// Point `index` at the song the engine actually switched to (the queue may have changed meanwhile).
    private func advance(to id: String) {
        index = queue.indices.dropFirst(index + 1).first { queue[$0].id == id } ?? index + 1
    }

    /// Bookkeeping whenever a new song becomes current (load, blend or cut).
    private func didStart() {
        guard let song = current else { return }
        moodSkips = 0
        playCounted = false
        lastTickTime = nil
        duration = engine.duration > 0 ? engine.duration : Double(song.duration)
        currentTime = engine.currentTime
        updateNowPlaying()
        Task {
            await refillIfNeeded()
            reschedule()
        }
    }

    /// Prepare the next song and plan the blend into it (or a gapless handover when AutoMix is off).
    private func reschedule() {
        prefetchTask?.cancel()
        engine.cancelScheduled()
        // Repeat One replays the song when it ends, so there's nothing to blend into.
        guard repeatMode != .one, queue.indices.contains(index + 1), let song = current else { return }
        let next = queue[index + 1]
        let automix = automix
        prefetchTask = Task {
            await AudioCache.shared.setKeep([song.id, next.id])
            // Current song's analysis first (cached, instant): it joins the session's feel before judging the next.
            // (The engine's file may have been swapped in by a blend, so ask the cache rather than `prepared`.)
            let currentTrack = try? await AudioCache.shared.prepare(song, localFile: localFile(song))
            guard let a = currentTrack?.analysis, !Task.isCancelled, current?.id == song.id else {
                Log.info("plan skipped: current analysis unavailable")
                return
            }
            prepared = currentTrack
            if !sessionProfile.contains(where: { $0.id == song.id }) {
                sessionProfile = Array((sessionProfile + [(song.id, a)]).suffix(3))   // feel of the last 3 songs
            }

            let started = Date.now
            let upcoming: PreparedTrack
            do {
                upcoming = try await AudioCache.shared.prepare(next, localFile: localFile(next))
            } catch {
                Log.info("prefetch failed \(next.id): \(error)")
                return
            }
            Log.info("prefetch ready \(next.id) in \(String(format: "%.1f", Date.now.timeIntervalSince(started)))s")
            guard !Task.isCancelled, current?.id == song.id, queue.indices.contains(index + 1), queue[index + 1].id == next.id else {
                Log.info("prefetch discarded (queue moved on)")
                return
            }
            // Mood guard: an autoplay pick whose tempo/loudness is far from the session is skipped (not disliked).
            if autoAdded.contains(next.id), moodSkips < 4, !QueueOps.fitsMood(upcoming.analysis, session: sessionProfile.map(\.analysis)) {
                moodSkips += 1
                Log.info("mood guard skipped \(next.id) bpm \(Int(upcoming.analysis.bpm)) loud \(upcoming.analysis.loudness.map { Int($0) } ?? 0)")
                queue.remove(at: index + 1)
                unshuffledQueue?.removeAll { $0.id == next.id }
                await refillIfNeeded()
                reschedule()
                return
            }
            let position = engine.currentTime   // after a seek, plan from where we are now
            let plan = automix ? TransitionPlanner.plan(from: a, to: upcoming.analysis, position: position) : TransitionPlanner.gapless(from: a)
            Log.info("plan \(song.title) → \(next.title): bpm \(Int(a.bpm))/\(Int(upcoming.analysis.bpm)) conf \(String(format: "%.2f/%.2f", a.confidence, upcoming.analysis.confidence)) start \(Int(plan.mixStartA))s overlap \(String(format: "%.1f", plan.overlap)) rate \(String(format: "%.3f", plan.rateB)) matched \(plan.beatMatched) at \(Int(position))s")
            engine.schedule(next: upcoming, plan: plan)
        }
    }

    /// Keeps the queue going with YouTube's radio for the current song.
    private func refillIfNeeded() async {
        guard autoplay, !refilling, let song = current, queue.count - index <= 3 else { return }
        refilling = true
        defer { refilling = false }
        // YouTube's own Mix first (what the owner is used to from youtube.com), YouTube Music radio as fallback.
        let mix = (try? await YT.youtubeMix(for: song.id)) ?? []
        let radio = mix.count >= 5 ? mix : ((try? await YT.radio(for: song.id)) ?? mix)
        // What the song the user started with leads to, so the session doesn't wander off.
        if let anchor = sessionAnchor, anchor.id != song.id, anchorMix.isEmpty {
            anchorMix = (try? await YT.youtubeMix(for: anchor.id)) ?? []
        }
        let recent = Set(queue.suffix(50).map(\.id))
        let skipped = Set((context.flatMap { try? $0.fetch(FetchDescriptor<Track>(predicate: #Predicate { $0.skipCount >= 2 || $0.disliked })) } ?? []).map(\.videoId))
        let next = QueueOps.coherent(radio, anchor: anchorMix, excluding: recent.union(skipped), limit: 8)
        autoAdded.formUnion(next.map(\.id))
        queue += next
    }

    private func tick() {
        currentTime = engine.currentTime
        // Listening time and real plays (not every start) feed stats, "On repeat" and Quick picks.
        if isPlaying, let song = current, let last = lastTickTime {
            let delta = currentTime - last
            if delta > 0, delta < 2, let t = context?.existingTrack(song.id) ?? context.map({ $0.track(for: song) }) {
                t.listenedSeconds += delta
            }
            if !playCounted, currentTime >= min(30, max(duration - 1, 1)) {
                playCounted = true
                recordPlay(song)
            }
        }
        lastTickTime = currentTime
        if Int(currentTime * 4) % 120 == 0 { Log.info("t=\(Int(currentTime))s/\(Int(duration))s queue=\(queue.count)") }
    }

    // MARK: - Listening signals (feed the recommender)

    private func recordPlay(_ song: Song) {
        guard let context else { return }
        let t = context.track(for: song)
        t.playCount += 1
        t.lastPlayed = .now
    }

    private func recordSkipIfNeeded() {
        guard let song = current, currentTime > 0, currentTime < 30, let t = context?.existingTrack(song.id) else { return }
        t.skipCount += 1
    }

    // MARK: - Lock screen

    private var artworkFor: String?
    private var artwork: MPMediaItemArtwork?

    private func updateNowPlaying() {
        let c = MPRemoteCommandCenter.shared()
        c.changeRepeatModeCommand.currentRepeatType = repeatMode == .one ? .one : repeatMode == .all ? .all : .off
        c.changeShuffleModeCommand.currentShuffleType = shuffle ? .items : .off
        guard let song = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPMediaItemPropertyArtist: song.artist,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if artworkFor == song.id, let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if isPlaying, currentTime < 1 { Log.info("now playing set: \(song.id)") }
        if artworkFor != song.id { loadArtwork(song) }
    }

    private func loadArtwork(_ song: Song) {
        artworkFor = song.id
        artwork = nil
        Task {
            guard let url = song.artwork(600),
                  let (data, _) = try? await URLSession.shared.data(from: url),
                  let image = PlatformImage(data: data), artworkFor == song.id else { return }
            artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            updateNowPlaying()
        }
    }

    private func setupRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.resume() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.pause() }; return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.toggle() }; return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.next() }; return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.previous() }; return .success }
        c.changeRepeatModeCommand.addTarget { [weak self] event in
            let type = (event as? MPChangeRepeatModeCommandEvent)?.repeatType ?? .off
            MainActor.assumeIsolated { self?.repeatMode = type == .one ? .one : type == .all ? .all : .off }
            return .success
        }
        c.changeShuffleModeCommand.addTarget { [weak self] event in
            let on = (event as? MPChangeShuffleModeCommandEvent)?.shuffleType != .off
            MainActor.assumeIsolated { if let self, self.shuffle != on { self.toggleShuffle() } }
            return .success
        }
        c.changePlaybackPositionCommand.addTarget { [weak self] event in
            let t = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?? 0
            MainActor.assumeIsolated { self?.seek(to: t) }
            return .success
        }
    }
}
