package com.nagashreeshyl.nive.audio

import android.util.Log
import com.nagashreeshyl.nive.model.Song
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Two decks: the active one plays the current song, the idle one holds the next song ready.
 * Handover is gapless when a song ends, or an AutoMix blend at `plan.mixStartA` (port of the iPhone MixEngine):
 * the incoming deck starts on the planned beat, volumes follow an equal-power curve, the bass swaps at the
 * midpoint and the tempo eases back to 1.0 after the blend. `step()` runs ~60 times a second.
 */
class MixEngine(private val deckA: Deck, private val deckB: Deck, private val clock: () -> Double = { System.nanoTime() / 1e9 }) {
    var onFinished: () -> Unit = {}
    var onSwapped: (String) -> Unit = {}
    var onMixing: (Boolean) -> Unit = {}
    var onError: (songId: String, httpCode: Int?) -> Unit = { _, _ -> }
    var onReady: () -> Unit = {}

    private var active = deckA
    private val idle get() = if (active === deckA) deckB else deckA
    private var scheduled: Pair<Song, TransitionPlan>? = null

    private class Transition(val plan: TransitionPlan, var start: Double, val outgoing: Deck, val incoming: Deck, val id: String) {
        var rateApplied = false
        var watchdogChecked = false
        var swapped = false
    }
    private var transition: Transition? = null
    private var rateEase: Pair<Double, Double>? = null   // (from rate, start time)

    val currentTime get() = active.position
    val duration get() = active.duration
    val isPlaying get() = active.isPlaying
    val currentId get() = active.songId
    var volume = 1f
        set(v) { field = v; if (transition == null) active.volume = v }

    init {
        for (deck in listOf(deckA, deckB)) deck.listener = object : Deck.Listener {
            override fun onReady() { if (deck === active) this@MixEngine.onReady() }
            override fun onEnded() {
                val tr = transition
                if (tr != null && deck === tr.outgoing) { finishBlend(tr); return }   // outgoing ran out mid-blend
                if (deck === active) activeEnded()
            }
            override fun onError(httpCode: Int?) {
                if (deck === active) deck.songId?.let { this@MixEngine.onError(it, httpCode) }
                else if (transition == null) { scheduled = null; deck.stop() }   // prefetch failed: decide again at the end
            }
        }
    }

    /** Plays `song` on the active deck from `at` seconds. */
    fun start(song: Song, at: Double = 0.0, play: Boolean = true) {
        endTransitionNow()
        pausedAt = if (play) null else clock()
        cancelScheduled()
        active.load(song, at)
        active.volume = volume
        active.setRate(1f)
        active.setBass(0f)
        if (play) active.play()
    }

    fun schedule(next: Song, plan: TransitionPlan) {
        if (transition != null) return
        scheduled = next to plan
        idle.load(next, plan.startB)
        idle.volume = volume
        idle.setRate(1f)
        idle.setBass(0f)
    }

    fun cancelScheduled() {
        scheduled = null
        if (transition == null) idle.stop()
    }

    /** Manual skip: switch to the prepared next song right away. Returns its id, or null if nothing is ready. */
    fun cutToScheduled(): String? {
        endTransitionNow()
        val (song, _) = scheduled ?: return null
        if (!idle.isReady) return null
        swap()
        return song.id
    }

    private var pausedAt: Double? = null

    /** Pauses both decks mid-blend; the blend clock stops too. */
    fun pause() {
        active.pause()
        transition?.let { it.outgoing.pause(); it.incoming.pause() }
        if (pausedAt == null) pausedAt = clock()
    }

    fun resume() {
        pausedAt?.let { at ->
            val gap = clock() - at
            transition?.let { it.start += gap }
            rateEase = rateEase?.let { (from, start) -> from to start + gap }
        }
        pausedAt = null
        transition?.let { it.outgoing.play(); it.incoming.play() } ?: active.play()
    }
    fun seek(sec: Double) { endTransitionNow(); cancelScheduled(); active.seek(sec) }

    fun step() {
        if (pausedAt != null) return
        val now = clock()
        val s = scheduled
        if (s != null && transition == null && s.second.overlap > 0 && active.isPlaying && idle.isReady) {
            val t = active.position
            if (t >= s.second.mixStartA - 0.01 || (active.duration > 0 && t >= active.duration - 0.05)) startBlend(s.first, s.second, now)
        }

        transition?.let { tr ->
            val p = (now - tr.start) / tr.plan.overlap
            val a = tr.outgoing; val b = tr.incoming
            if (!tr.rateApplied && now >= tr.start + 0.05) {   // tempo change once B is rendering
                tr.rateApplied = true
                if (tr.plan.rateB != 1.0) b.setRate(tr.plan.rateB.toFloat())
            }
            // Watchdog: if the incoming song still isn't advancing a quarter into the blend, start it now.
            if (p >= 0.25 && !tr.watchdogChecked) {
                tr.watchdogChecked = true
                if (b.position <= tr.plan.startB + 0.05 || !b.isPlaying) {
                    Log.i("Nive", "watchdog: incoming deck not rendering, forcing start")
                    b.seek(tr.plan.startB + tr.plan.overlap * p * tr.plan.rateB)
                    b.play()
                }
            }
            if (p > 0) {
                val x = p.coerceAtMost(1.0)
                a.volume = (cos(x * PI / 2) * volume).toFloat()
                b.volume = (sin(x * PI / 2) * volume).toFloat()
                val swap = smoothstep((x - 0.45) / 0.1)
                a.setBass((SWAP_GAIN * swap).toFloat())
                b.setBass((SWAP_GAIN * (1 - swap)).toFloat())
            }
            if (p >= 0.5 && !tr.swapped) {
                tr.swapped = true
                active = b
                onSwapped(tr.id)
            }
            if (p >= 1) finishBlend(tr)
        }

        rateEase?.let { (from, start) ->
            val x = ((now - start) / RATE_EASE_SECONDS).coerceAtMost(1.0)
            active.setRate((from + (1 - from) * smoothstep(x)).toFloat())
            if (x >= 1) rateEase = null
        }
    }

    private fun startBlend(song: Song, plan: TransitionPlan, now: Double) {
        scheduled = null
        val b = idle
        Log.i("Nive", "blend started at ${"%.2f".format(active.position)}s overlap ${"%.1f".format(plan.overlap)} rate ${"%.3f".format(plan.rateB)}")
        b.volume = 0f
        b.setBass(SWAP_GAIN.toFloat())
        b.play()
        transition = Transition(plan, now, active, b, song.id)
        onMixing(true)
    }

    private fun finishBlend(tr: Transition) {
        if (transition !== tr) return
        transition = null
        if (!tr.swapped) { tr.swapped = true; active = tr.incoming; onSwapped(tr.id) }
        tr.outgoing.stop()
        tr.outgoing.setBass(0f)
        tr.incoming.setBass(0f)
        tr.incoming.volume = volume
        if (tr.plan.rateB != 1.0) rateEase = tr.plan.rateB to clock()
        onMixing(false)
    }

    /** A seek, skip or new song during a blend: keep only what's active, at full volume and normal tempo. */
    private fun endTransitionNow() {
        val tr = transition ?: run { rateEase = null; return }
        transition = null
        rateEase = null
        val other = if (active === tr.outgoing) tr.incoming else tr.outgoing
        other.stop()
        active.volume = volume
        active.setRate(1f)
        active.setBass(0f)
        onMixing(false)
    }

    private fun activeEnded() {
        if (scheduled != null && idle.isReady) {
            val id = scheduled!!.first.id
            swap()
            onSwapped(id)
        } else onFinished()
    }

    private fun swap() {
        val old = active
        active = idle
        scheduled = null
        active.volume = volume
        active.setRate(1f)
        active.setBass(0f)
        active.play()
        old.stop()
    }

    private fun smoothstep(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    companion object {
        const val SWAP_GAIN = -30.0          // dB on the low shelf of the song giving up its bass
        const val RATE_EASE_SECONDS = 8.0
    }
}
