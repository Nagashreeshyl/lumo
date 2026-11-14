import Foundation

/// What the mixer knows about a song. Beat k falls at `beatOffset + k * 60 / bpm` seconds.
nonisolated struct TrackAnalysis: Codable, Sendable, Equatable {
    var duration: Double
    var bpm: Double
    var beatOffset: Double
    var confidence: Double      // 0…1, how sure the tempo estimate is
    var leadingSilence: Double  // seconds of silence before the music starts
    var audioEnd: Double        // where trailing silence begins
    var loudness: Double? = nil // average level of the music in dBFS (nil in caches from older versions)
}

/// How to hand over from song A to song B.
nonisolated struct TransitionPlan: Equatable, Sendable {
    var mixStartA: Double   // time in A when B starts
    var overlap: Double     // seconds both songs play
    var startB: Double      // where in B playback begins
    var rateB: Double       // B's playback rate during the overlap (eased to 1 afterwards)
    var beatMatched: Bool
}

nonisolated enum TransitionPlanner {
    static let minConfidence = 0.2
    static let maxTempoChange = 0.08
    static let crossfadeLength = 12.0

    /// `position` is where A is now; past the normal start (after a seek) the blend is replanned from there.
    static func plan(from a: TrackAnalysis, to b: TrackAnalysis, position: Double = 0) -> TransitionPlan {
        let aBeats = a.confidence >= minConfidence
        let bBeats = b.confidence >= minConfidence
        let ratio = aBeats && bBeats ? foldedRatio(a.bpm, b.bpm) : nil
        let periodA = 60 / a.bpm

        var startB = b.leadingSilence
        if bBeats {
            let periodB = 60 / b.bpm
            let k = max(((b.leadingSilence - b.beatOffset) / periodB - 1e-9).rounded(.up), 0)
            startB = b.beatOffset + k * periodB
        }

        var overlap = ratio != nil ? min(max(32 * periodA, 6), 16) : crossfadeLength
        if a.duration < 30 { overlap = min(overlap, a.duration / 3) }
        overlap = min(overlap, max(b.duration - startB, 0) / 3)   // short next song (interlude, skit)

        var start = max(a.audioEnd - overlap, 0)
        if aBeats { start = beat(atOrBefore: start, in: a) }

        if position > start {
            // Seeked past the planned start: blend over what's left, starting on the next beat.
            let next = aBeats ? beat(atOrBefore: position, in: a) + periodA : position
            let remaining = a.audioEnd - next
            guard remaining >= 2 else { return gapless(from: a) }
            start = next
            overlap = min(overlap, remaining)
        }

        return TransitionPlan(mixStartA: start, overlap: overlap, startB: startB, rateB: ratio ?? 1, beatMatched: ratio != nil)
    }

    private static func beat(atOrBefore t: Double, in a: TrackAnalysis) -> Double {
        let period = 60 / a.bpm
        return max(a.beatOffset + ((t - a.beatOffset) / period).rounded(.down) * period, 0)
    }

    /// No overlap: B starts from its very beginning right as A ends.
    static func gapless(from a: TrackAnalysis) -> TransitionPlan {
        TransitionPlan(mixStartA: a.duration, overlap: 0, startB: 0, rateB: 1, beatMatched: false)
    }

    /// Rate for B that makes its tempo match A, allowing half/double time; nil if more than ±8% away.
    static func foldedRatio(_ bpmA: Double, _ bpmB: Double) -> Double? {
        guard bpmA > 0, bpmB > 0 else { return nil }
        let best = [bpmB, bpmB * 2, bpmB / 2].map { bpmA / $0 }.min { abs($0 - 1) < abs($1 - 1) }!
        return abs(best - 1) <= maxTempoChange ? best : nil
    }
}
