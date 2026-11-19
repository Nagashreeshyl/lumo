package com.nagashreeshyl.lumo

import com.nagashreeshyl.lumo.audio.MixEngine
import com.nagashreeshyl.lumo.audio.TransitionPlan
import com.nagashreeshyl.lumo.player.PlayerController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MixBlendTest {
    private val a = FakeDeck("a"); private val b = FakeDeck("b")
    private var now = 0.0
    private val engine = MixEngine(a, b, clock = { now })
    private val swapped = mutableListOf<String>()
    private val mixing = mutableListOf<Boolean>()
    init { engine.onSwapped = { swapped += it }; engine.onMixing = { mixing += it } }

    private fun run(seconds: Double) = repeat((seconds * 60).toInt()) { now += 1 / 60.0; a.advance(1 / 60.0); b.advance(1 / 60.0); engine.step() }
    private fun startBlend(plan: TransitionPlan) {
        engine.start(songs("x").first())
        engine.schedule(songs("y").first(), plan)
        a.position = plan.mixStartA - 0.5
    }

    @Test fun startsDeckBOnPlannedBeat() {
        startBlend(TransitionPlan(100.0, 10.0, 1.0, 1.0, true))
        run(0.45); assertFalse(b.playing)
        run(0.1); assertTrue(b.playing)
        assertEquals(1.0, b.position, 0.05); assertTrue(a.position in 99.98..100.05)
        assertEquals(listOf(true), mixing)
    }

    @Test fun volumesEqualPowerAtMidpointAndSwapThere() {
        startBlend(TransitionPlan(100.0, 10.0, 1.0, 1.0, true))
        run(0.5 + 5.1)
        assertEquals(0.707f, a.volume, 0.03f); assertEquals(0.707f, b.volume, 0.03f)
        assertEquals(listOf("y"), swapped)
        assertEquals("y", engine.currentId)
    }

    @Test fun blendEndsWithOldDeckStoppedAndFullVolume() {
        startBlend(TransitionPlan(100.0, 10.0, 1.0, 1.0, true))
        run(0.5 + 10.1)
        assertEquals(null, a.song); assertEquals(1f, b.volume, 0.001f); assertEquals(listOf(true, false), mixing)
    }

    @Test fun rateEasesBackToOne() {
        startBlend(TransitionPlan(100.0, 10.0, 1.0, 0.97, true))
        run(0.5 + 0.2); assertEquals(0.97f, b.speed, 0.001f)
        run(10.0); assertTrue(b.speed < 1f)
        run(8.5); assertEquals(1f, b.speed, 0.001f)
    }

    @Test fun watchdogStartsAStalledIncomingDeck() {
        startBlend(TransitionPlan(100.0, 8.0, 1.0, 1.0, true))
        run(0.55)
        b.playing = false   // the incoming deck never started rendering
        b.position = 1.0
        run(2.5)
        assertTrue(b.playing); assertTrue(b.position > 2.0)
    }

    @Test fun pauseMidBlendPausesBothDecksAndResumesWhereItWas() {
        startBlend(TransitionPlan(100.0, 10.0, 1.0, 1.0, true))
        run(0.5 + 3.0)
        val volA = a.volume
        engine.pause()
        assertFalse(a.playing); assertFalse(b.playing)
        repeat(600) { now += 1 / 60.0; engine.step() }   // 10 s paused: the blend must not move on
        assertEquals(volA, a.volume, 0.01f); assertTrue(swapped.isEmpty())
        engine.resume()
        assertTrue(a.playing && b.playing)
        run(2.5); assertEquals(listOf("y"), swapped)      // midpoint reached only after real listening time
    }

    @Test fun seekCancelsTheBlend() {
        startBlend(TransitionPlan(100.0, 10.0, 1.0, 1.0, true))
        engine.seek(20.0)
        run(100.0)
        assertFalse(b.playing); assertTrue(swapped.isEmpty())
    }

    @Test fun missingAnalysisStillCrossfades() = runTest {
        val p = PlayerController(engine, FakeRadio(), FakeLibrary(), FakeStore(), analysis = { null }, scope = backgroundScope)
        p.automix = true
        p.play(songs("x", "y")); runCurrent()
        a.durationSec = 200.0
        p.seek(0.0); runCurrent()          // replan now that the deck knows the duration
        a.position = 187.0
        run(1.5)
        assertTrue("crossfade should start ~12 s before the end", b.playing)
    }
}
