import Testing
@testable import Lumo

struct QueueTests {
    private func songs(_ ids: [String]) -> [Song] { ids.map { Song(id: $0, title: $0, artist: "", thumbnail: nil, duration: 0) } }

    @Test func shuffleKeepsPlayedAndCurrentInPlace() {
        let q = songs(["a", "b", "c", "d", "e", "f"])
        let s = QueueOps.shuffled(q, current: 2)
        #expect(Array(s.prefix(3)).map(\.id) == ["a", "b", "c"])
        #expect(Set(s.dropFirst(3).map(\.id)) == ["d", "e", "f"])
        #expect(s.count == q.count)
    }

    @Test func unshuffleRestoresOriginalOrderAroundCurrent() {
        let original = songs(["a", "b", "c", "d", "e"])
        let shuffled = songs(["a", "b", "e", "c", "d"])   // now playing "e" at index 2
        let (queue, index) = QueueOps.unshuffled(original: original, shuffled: shuffled, current: 2)
        #expect(queue.map(\.id) == ["a", "b", "c", "d", "e"])
        #expect(index == 4)
    }

    @Test func unshuffleKeepsSongsAddedWhileShuffled() {
        let original = songs(["a", "b", "c"])
        let shuffled = songs(["a", "c", "b", "x"])   // "x" added by autoplay during shuffle
        let (queue, index) = QueueOps.unshuffled(original: original, shuffled: shuffled, current: 1)
        #expect(queue.map(\.id) == ["a", "b", "c", "x"])
        #expect(index == 2)
    }

    @Test func coherentKeepsTheClosestAndPrefersSongsThatFitTheSession() {
        let current = songs(["a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"])
        let anchor = songs(["x", "d", "y", "b"])
        let picked = QueueOps.coherent(current, anchor: anchor, excluding: ["c"], limit: 8)
        // Songs liked by both the session's starting song and the current one come first…
        #expect(Array(picked.prefix(2)).map(\.id) == ["b", "d"])
        // …then the closest of the rest, never the drifting tail, never excluded ones.
        #expect(picked.map(\.id) == ["b", "d", "a", "e", "f", "g", "h", "i"])
        #expect(picked.count == 8)
    }

    @Test func coherentWithoutAnchorIsJustTheClosest() {
        let picked = QueueOps.coherent(songs(["a", "b", "c", "d"]), anchor: [], excluding: [], limit: 2)
        #expect(picked.map(\.id) == ["a", "b"])
    }

    private func analysis(bpm: Double, conf: Double = 0.6, loudness: Double = -14) -> TrackAnalysis {
        TrackAnalysis(duration: 200, bpm: bpm, beatOffset: 0, confidence: conf, leadingSilence: 0, audioEnd: 199, loudness: loudness)
    }

    @Test func moodGuardRejectsADanceTrackAfterMelodies() {
        let session = [analysis(bpm: 115), analysis(bpm: 120), analysis(bpm: 125)]
        #expect(!QueueOps.fitsMood(analysis(bpm: 168), session: session))       // Alaakaa Loova after Tamil melodies
        #expect(QueueOps.fitsMood(analysis(bpm: 118), session: session))
        #expect(QueueOps.fitsMood(analysis(bpm: 240), session: session))        // double-time of 120 is the same feel
    }

    @Test func moodGuardRejectsBigLoudnessJumpAndIgnoresUnsureTempo() {
        let session = [analysis(bpm: 120, loudness: -18), analysis(bpm: 120, loudness: -17)]
        #expect(!QueueOps.fitsMood(analysis(bpm: 120, loudness: -8), session: session))
        #expect(QueueOps.fitsMood(analysis(bpm: 170, conf: 0.1), session: session))   // tempo unknown: don't judge it
        #expect(QueueOps.fitsMood(analysis(bpm: 170), session: []))                    // nothing to compare with yet
    }

    @Test func repeatModeCycles() {
        #expect(RepeatMode.off.next == .all)
        #expect(RepeatMode.all.next == .one)
        #expect(RepeatMode.one.next == .off)
    }
}
