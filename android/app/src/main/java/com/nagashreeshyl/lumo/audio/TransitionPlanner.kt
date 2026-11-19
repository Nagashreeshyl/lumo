package com.nagashreeshyl.lumo.audio

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** What the mixer knows about a song. Beat k falls at `beatOffset + k * 60 / bpm` seconds. */
@Serializable
data class TrackAnalysis(
    val duration: Double,
    val bpm: Double,
    val beatOffset: Double,
    val confidence: Double,      // 0…1, how sure the tempo estimate is
    val leadingSilence: Double,  // seconds of silence before the music starts
    val audioEnd: Double,        // where trailing silence begins
    val loudness: Double? = null // average level of the music in dBFS
)

/** How to hand over from song A to song B. */
data class TransitionPlan(
    val mixStartA: Double,  // time in A when B starts
    val overlap: Double,    // seconds both songs play
    val startB: Double,     // where in B playback begins
    val rateB: Double,      // B's playback rate during the overlap (eased to 1 afterwards)
    val beatMatched: Boolean,
)

object TransitionPlanner {
    const val MIN_CONFIDENCE = 0.2
    const val MAX_TEMPO_CHANGE = 0.08
    const val CROSSFADE_LENGTH = 12.0

    /** `position` is where A is now; past the normal start (after a seek) the blend is replanned from there. */
    fun plan(a: TrackAnalysis, b: TrackAnalysis, position: Double = 0.0): TransitionPlan {
        val aBeats = a.confidence >= MIN_CONFIDENCE
        val bBeats = b.confidence >= MIN_CONFIDENCE
        val ratio = if (aBeats && bBeats) foldedRatio(a.bpm, b.bpm) else null
        val periodA = 60 / a.bpm

        var startB = b.leadingSilence
        if (bBeats) {
            val periodB = 60 / b.bpm
            val k = maxOf(ceil((b.leadingSilence - b.beatOffset) / periodB - 1e-9), 0.0)
            startB = b.beatOffset + k * periodB
        }

        var overlap = if (ratio != null) minOf(maxOf(32 * periodA, 6.0), 16.0) else CROSSFADE_LENGTH
        if (a.duration < 30) overlap = minOf(overlap, a.duration / 3)
        overlap = minOf(overlap, maxOf(b.duration - startB, 0.0) / 3)   // short next song (interlude, skit)

        var start = maxOf(a.audioEnd - overlap, 0.0)
        if (aBeats) start = beatAtOrBefore(start, a)

        if (position > start) {
            val next = if (aBeats) beatAtOrBefore(position, a) + periodA else position
            val remaining = a.audioEnd - next
            if (remaining < 2) return gapless(a)
            start = next
            overlap = minOf(overlap, remaining)
        }
        return TransitionPlan(start, overlap, startB, ratio ?: 1.0, ratio != null)
    }

    private fun beatAtOrBefore(t: Double, a: TrackAnalysis): Double {
        val period = 60 / a.bpm
        return maxOf(a.beatOffset + floor((t - a.beatOffset) / period) * period, 0.0)
    }

    /** No overlap: B starts from its very beginning right as A ends. */
    fun gapless(a: TrackAnalysis) = TransitionPlan(a.duration, 0.0, 0.0, 1.0, false)

    /** Rate for B that makes its tempo match A, allowing half/double time; null if more than ±8% away. */
    fun foldedRatio(bpmA: Double, bpmB: Double): Double? {
        if (bpmA <= 0 || bpmB <= 0) return null
        val best = listOf(bpmB, bpmB * 2, bpmB / 2).map { bpmA / it }.minBy { abs(it - 1) }
        return if (abs(best - 1) <= MAX_TEMPO_CHANGE) best else null
    }
}
