package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.audio.TrackAnalysis
import com.nagashreeshyl.nive.logic.QueueOps
import com.nagashreeshyl.nive.logic.RepeatMode
import com.nagashreeshyl.nive.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

fun songs(vararg ids: String) = ids.map { Song(it, it, "", null, 0) }

class QueueOpsTest {
    @Test fun shuffleKeepsPlayedAndCurrentInPlace() {
        val q = songs("a", "b", "c", "d", "e", "f")
        val s = QueueOps.shuffled(q, 2)
        assertEquals(listOf("a", "b", "c"), s.take(3).map { it.id })
        assertEquals(setOf("d", "e", "f"), s.drop(3).map { it.id }.toSet())
        assertEquals(q.size, s.size)
    }

    @Test fun unshuffleRestoresOriginalOrderAroundCurrent() {
        val (queue, index) = QueueOps.unshuffled(songs("a", "b", "c", "d", "e"), songs("a", "b", "e", "c", "d"), 2)
        assertEquals(listOf("a", "b", "c", "d", "e"), queue.map { it.id })
        assertEquals(4, index)
    }

    @Test fun unshuffleKeepsSongsAddedWhileShuffled() {
        val (queue, index) = QueueOps.unshuffled(songs("a", "b", "c"), songs("a", "c", "b", "x"), 1)
        assertEquals(listOf("a", "b", "c", "x"), queue.map { it.id })
        assertEquals(2, index)
    }

    @Test fun coherentKeepsTheClosestAndPrefersSongsThatFitTheSession() {
        val current = songs("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")
        val picked = QueueOps.coherent(current, songs("x", "d", "y", "b"), setOf("c"), 8)
        assertEquals(listOf("b", "d", "a", "e", "f", "g", "h", "i"), picked.map { it.id })
    }

    @Test fun coherentWithoutAnchorIsJustTheClosest() {
        assertEquals(listOf("a", "b"), QueueOps.coherent(songs("a", "b", "c", "d"), emptyList(), emptySet(), 2).map { it.id })
    }

    private fun analysis(bpm: Double, conf: Double = 0.6, loudness: Double = -14.0) =
        TrackAnalysis(200.0, bpm, 0.0, conf, 0.0, 199.0, loudness)

    @Test fun moodGuardRejectsADanceTrackAfterMelodies() {
        val session = listOf(analysis(115.0), analysis(120.0), analysis(125.0))
        assertFalse(QueueOps.fitsMood(analysis(168.0), session))
        assertTrue(QueueOps.fitsMood(analysis(118.0), session))
        assertTrue(QueueOps.fitsMood(analysis(240.0), session))
    }

    @Test fun moodGuardRejectsBigLoudnessJumpAndIgnoresUnsureTempo() {
        val session = listOf(analysis(120.0, loudness = -18.0), analysis(120.0, loudness = -17.0))
        assertFalse(QueueOps.fitsMood(analysis(120.0, loudness = -8.0), session))
        assertTrue(QueueOps.fitsMood(analysis(170.0, conf = 0.1), session))
        assertTrue(QueueOps.fitsMood(analysis(170.0), emptyList()))
    }

    @Test fun repeatModeCycles() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next)
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next)
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next)
    }
}
