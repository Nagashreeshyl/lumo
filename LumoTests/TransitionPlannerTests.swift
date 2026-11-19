import Testing
@testable import Lumo

struct TransitionPlannerTests {
    private func track(bpm: Double, conf: Double = 0.9, duration: Double = 200, offset: Double = 0.1,
                       lead: Double = 0.5, end: Double? = nil) -> TrackAnalysis {
        TrackAnalysis(duration: duration, bpm: bpm, beatOffset: offset, confidence: conf,
                      leadingSilence: lead, audioEnd: end ?? duration - 1)
    }

    @Test func compatibleTempoIsBeatMatched() {
        let a = track(bpm: 120), b = track(bpm: 124)
        let p = TransitionPlanner.plan(from: a, to: b)
        #expect(p.beatMatched)
        #expect(abs(p.rateB - 120.0 / 124.0) < 0.001)
        #expect(abs(p.overlap - 16) < 0.001)
        let phase = (p.mixStartA - a.beatOffset).truncatingRemainder(dividingBy: 0.5)
        #expect(phase < 0.001 || phase > 0.499)
        #expect(p.mixStartA + p.overlap <= a.audioEnd + 0.001)
    }

    @Test func halfTimeFolds() {
        #expect(abs((TransitionPlanner.foldedRatio(140, 70) ?? 0) - 1) < 0.001)
        #expect(abs((TransitionPlanner.foldedRatio(120, 60) ?? 0) - 1) < 0.001)
        #expect(TransitionPlanner.foldedRatio(120, 100) == nil)
    }

    @Test func incompatibleTempoCrossfades() {
        let p = TransitionPlanner.plan(from: track(bpm: 120), to: track(bpm: 100))
        #expect(!p.beatMatched)
        #expect(p.rateB == 1)
        #expect(p.overlap == 12)
    }

    @Test func moderateConfidenceStillBeatMatches() {
        let p = TransitionPlanner.plan(from: track(bpm: 120, conf: 0.22), to: track(bpm: 123, conf: 0.25))
        #expect(p.beatMatched)
    }

    @Test func lowConfidenceCrossfades() {
        let b = track(bpm: 120, conf: 0.1, lead: 1.3)
        let p = TransitionPlanner.plan(from: track(bpm: 120), to: b)
        #expect(!p.beatMatched)
        #expect(p.startB == 1.3)
    }

    @Test func startBIsFirstBeatAfterSilence() {
        let b = track(bpm: 120, offset: 0.2, lead: 1.3)
        let p = TransitionPlanner.plan(from: track(bpm: 120), to: b)
        #expect(abs(p.startB - 1.7) < 0.001)
    }

    @Test func shortSongCapsOverlap() {
        let a = track(bpm: 120, duration: 20)
        let p = TransitionPlanner.plan(from: a, to: track(bpm: 120))
        #expect(p.overlap <= 20.0 / 3 + 0.001)
        #expect(p.mixStartA >= 0)
    }

    @Test func gaplessHasNoOverlap() {
        let a = track(bpm: 120)
        let p = TransitionPlanner.gapless(from: a)
        #expect(p.mixStartA == a.duration)
        #expect(p.overlap == 0)
        #expect(p.startB == 0)
        #expect(p.rateB == 1)
        #expect(!p.beatMatched)
    }

    // Review C2: a 10 s interlude after a long song must not outlast its own overlap.
    @Test func shortIncomingSongCapsOverlap() {
        let b = track(bpm: 120, duration: 10, lead: 0)
        let p = TransitionPlanner.plan(from: track(bpm: 120), to: b)
        #expect(p.overlap <= (b.duration - p.startB) / 3 + 0.001)
    }

    // Review I1: after seeking past the planned start, replan from the new position.
    @Test func replansAfterSeekPastMixStart() {
        let a = track(bpm: 120), b = track(bpm: 120)
        let p = TransitionPlanner.plan(from: a, to: b, position: 190)   // audioEnd 199, normal start ~183
        #expect(p.mixStartA >= 190)
        #expect(p.mixStartA + p.overlap <= a.audioEnd + 0.001)
        let phase = (p.mixStartA - a.beatOffset).truncatingRemainder(dividingBy: 0.5)
        #expect(phase < 0.001 || phase > 0.499)
    }

    @Test func seekToVeryEndFallsBackToGapless() {
        let a = track(bpm: 120)
        let p = TransitionPlanner.plan(from: a, to: track(bpm: 120), position: a.audioEnd - 1)
        #expect(p.overlap == 0)
        #expect(p.mixStartA == a.duration)
    }

    @Test func positionBeforeMixStartKeepsNormalPlan() {
        let a = track(bpm: 120), b = track(bpm: 124)
        #expect(TransitionPlanner.plan(from: a, to: b, position: 30) == TransitionPlanner.plan(from: a, to: b))
    }
}
