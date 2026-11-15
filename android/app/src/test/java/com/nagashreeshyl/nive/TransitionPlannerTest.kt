package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.audio.TrackAnalysis
import com.nagashreeshyl.nive.audio.TransitionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TransitionPlannerTest {
    private fun track(bpm: Double, conf: Double = 0.9, duration: Double = 200.0, offset: Double = 0.1, lead: Double = 0.5, end: Double? = null) =
        TrackAnalysis(duration, bpm, offset, conf, lead, end ?: (duration - 1))

    private fun onBeat(t: Double, offset: Double) = ((t - offset) % 0.5).let { it < 0.001 || it > 0.499 }

    @Test fun compatibleTempoIsBeatMatched() {
        val a = track(120.0); val b = track(124.0)
        val p = TransitionPlanner.plan(a, b)
        assertTrue(p.beatMatched)
        assertEquals(120.0 / 124.0, p.rateB, 0.001)
        assertEquals(16.0, p.overlap, 0.001)
        assertTrue(onBeat(p.mixStartA, a.beatOffset))
        assertTrue(p.mixStartA + p.overlap <= a.audioEnd + 0.001)
    }

    @Test fun halfTimeFolds() {
        assertEquals(1.0, TransitionPlanner.foldedRatio(140.0, 70.0)!!, 0.001)
        assertEquals(1.0, TransitionPlanner.foldedRatio(120.0, 60.0)!!, 0.001)
        assertNull(TransitionPlanner.foldedRatio(120.0, 100.0))
    }

    @Test fun incompatibleTempoCrossfades() {
        val p = TransitionPlanner.plan(track(120.0), track(100.0))
        assertFalse(p.beatMatched); assertEquals(1.0, p.rateB, 0.0); assertEquals(12.0, p.overlap, 0.0)
    }

    @Test fun moderateConfidenceStillBeatMatches() =
        assertTrue(TransitionPlanner.plan(track(120.0, conf = 0.22), track(123.0, conf = 0.25)).beatMatched)

    @Test fun lowConfidenceCrossfades() {
        val p = TransitionPlanner.plan(track(120.0), track(120.0, conf = 0.1, lead = 1.3))
        assertFalse(p.beatMatched); assertEquals(1.3, p.startB, 0.0)
    }

    @Test fun noAnalysisConfidenceOnBothCrossfades() {
        val p = TransitionPlanner.plan(track(120.0, conf = 0.0), track(120.0, conf = 0.0))
        assertFalse(p.beatMatched); assertEquals(12.0, p.overlap, 0.0)
    }

    @Test fun startBIsFirstBeatAfterSilence() =
        assertEquals(1.7, TransitionPlanner.plan(track(120.0), track(120.0, offset = 0.2, lead = 1.3)).startB, 0.001)

    @Test fun shortSongCapsOverlap() {
        val p = TransitionPlanner.plan(track(120.0, duration = 20.0), track(120.0))
        assertTrue(p.overlap <= 20.0 / 3 + 0.001); assertTrue(p.mixStartA >= 0)
    }

    @Test fun gaplessHasNoOverlap() {
        val a = track(120.0)
        val p = TransitionPlanner.gapless(a)
        assertEquals(a.duration, p.mixStartA, 0.0); assertEquals(0.0, p.overlap, 0.0); assertEquals(0.0, p.startB, 0.0)
        assertEquals(1.0, p.rateB, 0.0); assertFalse(p.beatMatched)
    }

    @Test fun shortIncomingSongCapsOverlap() {
        val b = track(120.0, duration = 10.0, lead = 0.0)
        val p = TransitionPlanner.plan(track(120.0), b)
        assertTrue(p.overlap <= (b.duration - p.startB) / 3 + 0.001)
    }

    @Test fun replansAfterSeekPastMixStart() {
        val a = track(120.0)
        val p = TransitionPlanner.plan(a, track(120.0), 190.0)
        assertTrue(p.mixStartA >= 190); assertTrue(p.mixStartA + p.overlap <= a.audioEnd + 0.001)
        assertTrue(onBeat(p.mixStartA, a.beatOffset))
    }

    @Test fun seekToVeryEndFallsBackToGapless() {
        val a = track(120.0)
        val p = TransitionPlanner.plan(a, track(120.0), a.audioEnd - 1)
        assertEquals(0.0, p.overlap, 0.0); assertEquals(a.duration, p.mixStartA, 0.0)
    }

    @Test fun positionBeforeMixStartKeepsNormalPlan() {
        val a = track(120.0); val b = track(124.0)
        assertEquals(TransitionPlanner.plan(a, b), TransitionPlanner.plan(a, b, 30.0))
    }
}
