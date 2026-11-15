package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.audio.MixEngine
import com.nagashreeshyl.nive.logic.RepeatMode
import com.nagashreeshyl.nive.player.PlayerController
import com.nagashreeshyl.nive.player.SavedQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerTest {
    private val a = FakeDeck("a"); private val b = FakeDeck("b")
    private val library = FakeLibrary(); private val radio = FakeRadio(); private val store = FakeStore()

    private fun TestScope.controller(automix: Boolean = false) =
        PlayerController(MixEngine(a, b), radio, library, store, analysis = { null }, scope = backgroundScope).also {
            it.automix = automix
        }

    /** Moves the playing deck forward in 0.25 s ticks, like the real 4 Hz tick. */
    private fun PlayerController.play(seconds: Double) {
        repeat((seconds * 4).toInt()) { a.advance(0.25); b.advance(0.25); engine.step(); tick() }
    }

    @Test fun countsPlayAfter30s() = runTest {
        val p = controller()
        p.play(songs("x", "y")); runCurrent()
        p.play(29.0); assertEquals(null, library.plays["x"])
        p.play(2.0); assertEquals(1, library.plays["x"])
        assertTrue(library.listened["x"]!! > 30)
    }

    @Test fun skipUnder30sCountsSkip() = runTest {
        val p = controller()
        p.play(songs("x", "y")); runCurrent()
        p.play(10.0); p.next(); runCurrent()
        assertEquals(1, library.skips["x"]); assertEquals("y", p.current.value?.id)
    }

    @Test fun refillsWhenTwoLeft() = runTest {
        val p = controller()
        p.play(songs("x", "y", "z")); runCurrent()
        assertEquals(1, radio.calls)
        assertEquals(3 + 8, p.queue.value.size)   // coherent limit 8
    }

    @Test fun dislikedSongsAreNotRefilled() = runTest {
        library.disliked += "x-r1"
        val p = controller()
        p.play(songs("x")); runCurrent()
        assertFalse(p.queue.value.any { it.id == "x-r1" })
    }

    @Test fun stopsAfterThreeFailures() = runTest {
        radio.mix = { emptyList() }
        val p = controller()
        p.play(songs("1", "2", "3", "4", "5")); runCurrent()
        repeat(3) { (if (a.song != null && a.ready) a else b).fail(500); advanceTimeBy(2_000); runCurrent() }
        assertNotNull(p.error.value); assertFalse(p.isPlaying.value)
        assertEquals("3", p.current.value?.id)
    }

    @Test fun expiredUrlReloadsAtSamePosition() = runTest {
        val p = controller()
        p.play(songs("x")); runCurrent()
        p.play(50.0)
        val loads = a.loads
        a.fail(403); runCurrent()
        assertEquals(loads + 1, a.loads); assertEquals(50.0, a.position, 0.5); assertEquals("x", p.current.value?.id)
    }

    @Test fun repeatOneReplays() = runTest {
        val p = controller()
        p.play(songs("x", "y")); runCurrent()
        p.setRepeat(RepeatMode.ONE)
        p.play(5.0); a.end(); runCurrent()
        assertEquals("x", p.current.value?.id); assertTrue(a.playing); assertEquals(0.0, a.position, 0.01)
    }

    @Test fun gaplessHandoverWhenSongEnds() = runTest {
        val p = controller()
        p.play(songs("x", "y")); runCurrent()
        a.end(); runCurrent()
        assertEquals("y", p.current.value?.id); assertTrue(b.playing || a.playing)
    }

    @Test fun restoresQueueFromStore() = runTest {
        store.saved = SavedQueue(songs("q1", "q2", "q3"), 1, 42.0)
        val p = controller(); p.restore(); runCurrent()
        assertEquals("q2", p.current.value?.id); assertFalse(p.isPlaying.value)
        p.toggle(); runCurrent()
        assertEquals("q2", a.song?.id); assertEquals(42.0, a.position, 0.01); assertTrue(a.playing)
    }

    @Test fun savesQueueOnChange() = runTest {
        val p = controller()
        p.play(songs("x", "y")); runCurrent()
        assertEquals("x", store.saved?.queue?.first()?.id)
    }

    @Test fun previousRestartsAfterThreeSeconds() = runTest {
        val p = controller()
        p.play(songs("x", "y"), 1); runCurrent()
        p.play(5.0); p.previous(); runCurrent()
        assertEquals("y", p.current.value?.id)
        p.previous(); runCurrent()
        assertEquals("x", p.current.value?.id)
    }
}
