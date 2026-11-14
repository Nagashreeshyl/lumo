import AVFoundation
import QuartzCore

/// Two-deck player: each deck is playerNode → timePitch → low-shelf EQ → main mixer.
/// Plays the current song and, when a transition is scheduled, blends the next one in on the beat.
final class MixEngine {
    var onFinished: (() -> Void)?   // current song ended with nothing scheduled
    var onSwapped: ((String) -> Void)?   // id of the scheduled song that took over (blend midpoint)
    var onTick: (() -> Void)?       // ~4 Hz while playing, for UI
    var onMixing: ((Bool) -> Void)? // a blend started / finished (for the "AutoMix" indicator)

    private final class Deck {
        let node = AVAudioPlayerNode()
        let pitch = AVAudioUnitTimePitch()
        let eq = AVAudioUnitEQ(numberOfBands: 1)
        var file: AVAudioFile?
        var songId: String?
        var segmentStart: Double = 0     // seconds into the file where the current segment began
        var token = 0                    // invalidates stale completion callbacks
        var playing = false
        var duration: Double { file.map { Double($0.length) / $0.processingFormat.sampleRate } ?? 0 }
    }

    private struct Transition {
        let plan: TransitionPlan
        var startTime: Double        // CACurrentMediaTime() when the incoming deck starts sounding
        var swapped = false
        var overlapDone = false
        var pendingRate: Double?     // applied once the incoming deck is actually rendering
        var watchdogChecked = false
    }

    private let engine = AVAudioEngine()
    private let decks = [Deck(), Deck()]
    private var active = 0
    private var incoming: Int { 1 - active }
    private var scheduled: (track: PreparedTrack, plan: TransitionPlan)?
    private var transition: Transition?
    private var rateEase: (from: Double, start: Double)?
    private var timer: Timer?
    private var tickCount = 0
    private var pausedAt: Double?
    private let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)!
    private static let swapGain: Float = -30
    private static let rateEaseSeconds = 8.0

    init() {
        for d in decks {
            d.eq.bands[0].filterType = .lowShelf
            d.eq.bands[0].frequency = 200
            d.eq.bands[0].gain = 0
            d.eq.bands[0].bypass = false
            engine.attach(d.node); engine.attach(d.pitch); engine.attach(d.eq)
            engine.connect(d.node, to: d.pitch, format: format)
            engine.connect(d.pitch, to: d.eq, format: format)
            engine.connect(d.eq, to: engine.mainMixerNode, format: format)
        }
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            // Route change (e.g. AirPlay) stops the engine; restart and keep playing decks going.
            MainActor.assumeIsolated { self?.restartAfterConfigChange() }
        }
    }

    var isPlaying: Bool { decks[active].playing }
    var duration: Double { decks[active].duration }

    var currentTime: Double { position(decks[active]).seconds }

    /// Position in the file at the last render, plus that render's host time (to anchor beat-accurate starts).
    private func position(_ d: Deck) -> (seconds: Double, host: UInt64?) {
        guard let nodeTime = d.node.lastRenderTime, nodeTime.isSampleTimeValid,
              let t = d.node.playerTime(forNodeTime: nodeTime), t.sampleTime >= 0 else { return (d.segmentStart, nil) }
        let seconds = min(d.segmentStart + Double(t.sampleTime) / t.sampleRate, d.duration)
        return (seconds, nodeTime.isHostTimeValid ? nodeTime.hostTime : nil)
    }

    // MARK: - Transport

    func start(_ track: PreparedTrack, at seconds: Double = 0) {
        cancelScheduled()
        abortTransition()
        for d in decks { stop(d) }
        wasPlayingDecks = []
        active = 0
        let d = decks[active]
        setFile(try? AVAudioFile(forReading: track.file), on: d)
        d.songId = track.songId
        guard d.file != nil else {
            // Cache file vanished or is corrupt: report it as finished so the player moves on.
            DispatchQueue.main.async { [weak self] in self?.onFinished?() }
            return
        }
        reset(d)
        play(d, from: seconds)
    }

    func pause() {
        guard isPlaying else { return }
        pausedAt = CACurrentMediaTime()
        wasPlayingDecks = Set(decks.indices.filter { decks[$0].playing })
        for d in decks where d.playing { d.node.pause(); d.playing = false }
        engine.pause()
        stopTimer()
    }

    func resume() {
        guard !isPlaying, decks[active].file != nil else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        try? engine.start()
        if let pausedAt {
            let gap = CACurrentMediaTime() - pausedAt
            transition?.startTime += gap
            if let e = rateEase { rateEase = (e.from, e.start + gap) }
        }
        pausedAt = nil
        for (i, d) in decks.enumerated() where wasPlayingDecks.contains(i) || i == active {
            d.node.play()
            d.playing = true
        }
        startTimer()
    }

    func seek(to seconds: Double) {
        cancelScheduled()
        abortTransition()
        let d = decks[active]
        let wasPlaying = d.playing
        stop(d)
        play(d, from: min(max(seconds, 0), d.duration), autoplay: wasPlaying)
    }

    // MARK: - Transitions

    /// Arms the next blend; it starts once any running blend has finished (step() checks that).
    func schedule(next track: PreparedTrack, plan: TransitionPlan) {
        scheduled = (track, plan)
    }

    func cancelScheduled() { scheduled = nil }

    /// Manual skip: jump straight to the blending-in (or scheduled) song at full level.
    /// Returns that song's id, or nil when nothing is ready (the player then loads normally).
    func cutToScheduled() -> String? {
        wasPlayingDecks = []
        if let t = transition, !t.swapped {
            transition = nil
            onMixing?(false)
            stop(decks[active])
            active = incoming
            reset(decks[active])
            rateEase = nil
            return decks[active].songId
        }
        guard let s = scheduled, let file = try? AVAudioFile(forReading: s.track.file) else { return nil }
        scheduled = nil
        abortTransition()          // the previous song may still be fading out
        stop(decks[active])
        active = incoming
        let d = decks[active]
        setFile(file, on: d)
        d.songId = s.track.songId
        reset(d)
        play(d, from: s.plan.startB)
        return s.track.songId
    }

    // MARK: - Internals

    private var wasPlayingDecks: Set<Int> = []

    private func play(_ d: Deck, from seconds: Double, autoplay: Bool = true, at when: AVAudioTime? = nil) {
        guard let file = d.file else { return }
        let sr = file.processingFormat.sampleRate
        let startFrame = AVAudioFramePosition(seconds * sr)
        let frames = AVAudioFrameCount(max(file.length - startFrame, 0))
        guard frames > 0 else { finished(d, token: d.token); return }
        d.token += 1
        let token = d.token
        d.segmentStart = seconds
        d.node.scheduleSegment(file, startingFrame: startFrame, frameCount: frames, at: nil,
                               completionCallbackType: .dataPlayedBack) { [weak self, weak d] _ in
            DispatchQueue.main.async { if let self, let d { self.finished(d, token: token) } }
        }
        guard autoplay else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        if !engine.isRunning { try? engine.start() }
        d.node.play(at: when)
        d.playing = true
        startTimer()
    }

    /// Loads a file onto a deck, reconnecting the deck if the file isn't the usual 44.1 kHz stereo.
    private func setFile(_ file: AVAudioFile?, on d: Deck) {
        d.file = file
        guard let fmt = file?.processingFormat, d.node.outputFormat(forBus: 0) != fmt else { return }
        engine.connect(d.node, to: d.pitch, format: fmt)
    }

    private func stop(_ d: Deck) {
        d.token += 1          // the stop() completion callback must not count as "finished"
        d.node.stop()
        d.playing = false
    }

    private func reset(_ d: Deck) {
        d.node.volume = 1
        d.pitch.rate = 1
        d.eq.bands[0].gain = 0
    }

    private func finished(_ d: Deck, token: Int) {
        guard token == d.token else { return }
        d.playing = false
        guard d === decks[active] else { return }   // outgoing deck ran out during a blend: fine
        if var tr = transition {
            if !tr.swapped {
                // Current song ran out before the blend midpoint: hand over now.
                tr.swapped = true
                transition = tr
                active = incoming
                onSwapped?(decks[active].songId ?? "")
                return
            }
            abortTransition()    // the new song itself ended mid-blend (very short track)
        }
        // With a blend still scheduled, step() arms it immediately (position is at the end).
        if scheduled == nil {
            stopTimer()
            onFinished?()
        }
    }

    private func abortTransition() {
        guard transition != nil else { return }
        transition = nil
        onMixing?(false)
        wasPlayingDecks = []
        stop(decks[incoming])   // before the swap: the new song; after it: the old one fading out
        reset(decks[active])
        rateEase = nil
    }

    private func startTimer() {
        guard timer == nil else { return }
        let t = Timer(timeInterval: 1.0 / 60, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.step() } }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    /// 60 Hz: arm transitions on time, run volume/EQ/rate automation, publish ticks.
    private func step() {
        let now = CACurrentMediaTime()
        tickCount += 1
        if tickCount % 15 == 0 { onTick?() }

        if let s = scheduled, transition == nil {
            let a = decks[active]
            let t = position(a).seconds
            // Fire on the step that reaches the planned beat and start B immediately. Both decks then go
            // through the same output latency, so B's first beat lands on A's beat within one 60 Hz step.
            // (A future host-time start via play(at:) never fired on a real iPhone.)
            let target = s.plan.overlap == 0 ? a.duration : s.plan.mixStartA
            if t >= target - 0.01 || t >= a.duration - 0.05 {
                scheduled = nil
                // If the next file can't be opened, let A play out; onFinished then loads it fresh.
                guard let file = try? AVAudioFile(forReading: s.track.file) else {
                    Log.info("blend aborted: cannot open \(s.track.songId)")
                    return
                }
                Log.info("blend started at \(String(format: "%.2f", t))s (target \(String(format: "%.2f", target))) overlap \(String(format: "%.1f", s.plan.overlap)) rate \(s.plan.rateB)")
                let b = decks[incoming]
                setFile(file, on: b)
                b.songId = s.track.songId
                reset(b)
                b.pitch.rate = 1   // tempo change applied once B is rendering
                if s.plan.overlap > 0 {
                    b.node.volume = 0
                    b.eq.bands[0].gain = Self.swapGain
                }
                play(b, from: s.plan.startB)
                transition = Transition(plan: s.plan, startTime: now, pendingRate: s.plan.rateB != 1 ? s.plan.rateB : nil)
                onMixing?(true)
            }
        }

        if var tr = transition {
            let p = tr.plan.overlap > 0 ? (now - tr.startTime) / tr.plan.overlap : (now >= tr.startTime ? 1 : 0)
            let a = decks[tr.swapped ? incoming : active], b = decks[tr.swapped ? active : incoming]
            if let rate = tr.pendingRate, now >= tr.startTime + 0.05 {
                b.pitch.rate = Float(rate)
                tr.pendingRate = nil
                transition = tr
            }
            // Watchdog: if the incoming song still isn't advancing a quarter into the blend, start it now.
            if p >= 0.25, !tr.watchdogChecked {
                tr.watchdogChecked = true
                transition = tr
                if position(b).seconds <= tr.plan.startB + 0.05 {
                    Log.info("watchdog: incoming deck not rendering, forcing start")
                    stop(b)
                    play(b, from: tr.plan.startB + tr.plan.overlap * p * tr.plan.rateB)
                }
            }
            if p > 0 {
                let x = min(p, 1)
                a.node.volume = Float(cos(x * .pi / 2))
                b.node.volume = Float(sin(x * .pi / 2))
                let swap = Float(smoothstep((x - 0.45) / 0.1))
                a.eq.bands[0].gain = Self.swapGain * swap
                b.eq.bands[0].gain = Self.swapGain * (1 - swap)
            }
            if p >= 0.5, !tr.swapped {
                tr.swapped = true
                active = incoming
                transition = tr
                onSwapped?(decks[active].songId ?? "")
            }
            if p >= 1, !tr.overlapDone {
                tr.overlapDone = true
                stop(a)
                reset(a)
                b.eq.bands[0].gain = 0
                b.node.volume = 1
                if tr.plan.rateB != 1 { rateEase = (tr.plan.rateB, now) }
                transition = nil
                onMixing?(false)
            } else if transition != nil {
                transition = tr
            }
        }

        if let e = rateEase {
            let x = min((now - e.start) / Self.rateEaseSeconds, 1)
            decks[active].pitch.rate = Float(e.from + (1 - e.from) * smoothstep(x))
            if x >= 1 { rateEase = nil }
        }
    }

    private func smoothstep(_ x: Double) -> Double {
        let t = min(max(x, 0), 1)
        return t * t * (3 - 2 * t)
    }

    private func restartAfterConfigChange() {
        let playing = decks.indices.filter { decks[$0].playing }
        guard !playing.isEmpty else { return }
        try? engine.start()
        for i in playing { decks[i].node.play() }
    }
}
