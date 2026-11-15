package com.nagashreeshyl.nive.logic

import com.nagashreeshyl.nive.audio.TrackAnalysis
import com.nagashreeshyl.nive.model.Song
import kotlin.math.abs

enum class RepeatMode {
    OFF, ALL, ONE;
    val next: RepeatMode get() = when (this) { OFF -> ALL; ALL -> ONE; ONE -> OFF }
}

/** Pure queue reordering (port of the iPhone app's QueueOps). */
object QueueOps {
    /** Shuffles only the songs after `current`; what already played and what's playing stay put. */
    fun shuffled(queue: List<Song>, current: Int): List<Song> {
        if (current !in queue.indices) return queue.shuffled()
        return queue.subList(0, current + 1) + queue.subList(current + 1, queue.size).shuffled()
    }

    /** Head of YouTube's list (closest first), preferring songs the session's starting song also leads to. */
    fun coherent(candidates: List<Song>, anchor: List<Song>, excluding: Set<String>, limit: Int): List<Song> {
        val pool = candidates.filter { it.id !in excluding }
        val fits = anchor.map { it.id }.toSet()
        return (pool.filter { it.id in fits } + pool.filter { it.id !in fits }).take(limit)
    }

    /** Rejects tempo jumps beyond ±30% (half/double time counts as the same feel) and loudness jumps over 6 dB. */
    fun fitsMood(song: TrackAnalysis, session: List<TrackAnalysis>): Boolean {
        val minConfidence = 0.2
        val tempos = session.filter { it.confidence >= minConfidence }.map { it.bpm }.sorted()
        if (song.confidence >= minConfidence && tempos.isNotEmpty()) {
            val reference = tempos[tempos.size / 2]
            val off = listOf(1.0, 2.0, 0.5).minOf { abs(song.bpm * it / reference - 1) }
            if (off > 0.30) return false
        }
        val levels = session.mapNotNull { it.loudness }
        val level = song.loudness
        if (level != null && levels.isNotEmpty() && abs(level - levels.average()) > 6) return false
        return true
    }

    /** Back to the original order, keeping songs added while shuffled (autoplay) at the end. */
    fun unshuffled(original: List<Song>, shuffled: List<Song>, current: Int): Pair<List<Song>, Int> {
        val known = original.map { it.id }.toSet()
        val queue = original + shuffled.filter { it.id !in known }
        val currentId = shuffled.getOrNull(current)?.id
        val index = currentId?.let { id -> queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
        return queue to index
    }
}
