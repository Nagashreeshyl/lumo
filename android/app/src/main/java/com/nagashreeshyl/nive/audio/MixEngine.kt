package com.nagashreeshyl.nive.audio

import com.nagashreeshyl.nive.model.Song

/**
 * Two decks: the active one plays the current song, the idle one holds the next song ready.
 * Handover happens when the current song ends (gapless) or, with a blend plan, at `plan.mixStartA`.
 * `step()` is driven ~60 times a second while playing.
 */
class MixEngine(private val deckA: Deck, private val deckB: Deck) {
    var onFinished: () -> Unit = {}
    var onSwapped: (String) -> Unit = {}
    var onMixing: (Boolean) -> Unit = {}
    var onError: (songId: String, httpCode: Int?) -> Unit = { _, _ -> }
    var onReady: () -> Unit = {}

    private var active = deckA
    private val idle get() = if (active === deckA) deckB else deckA
    private var scheduled: Pair<Song, TransitionPlan>? = null

    val currentTime get() = active.position
    val duration get() = active.duration
    val isPlaying get() = active.isPlaying
    val currentId get() = active.songId
    var volume = 1f
        set(v) { field = v; active.volume = v }

    init {
        for (deck in listOf(deckA, deckB)) deck.listener = object : Deck.Listener {
            override fun onReady() { if (deck === active) this@MixEngine.onReady() }
            override fun onEnded() { if (deck === active) activeEnded() }
            override fun onError(httpCode: Int?) {
                if (deck === active) deck.songId?.let { this@MixEngine.onError(it, httpCode) }
                else { scheduled = null; deck.stop() }   // prefetch failed: decide again when the song ends
            }
        }
    }

    /** Plays `song` on the active deck from `at` seconds. */
    fun start(song: Song, at: Double = 0.0, play: Boolean = true) {
        cancelScheduled()
        active.load(song, at)
        active.volume = volume
        active.setRate(1f)
        if (play) active.play()
    }

    fun schedule(next: Song, plan: TransitionPlan) {
        scheduled = next to plan
        idle.load(next, plan.startB)
        idle.volume = volume
    }

    fun cancelScheduled() {
        scheduled = null
        idle.stop()
    }

    /** Manual skip: switch to the prepared next song right away. Returns its id, or null if nothing is ready. */
    fun cutToScheduled(): String? {
        val (song, _) = scheduled ?: return null
        if (!idle.isReady) return null
        swap()
        return song.id
    }

    fun pause() = active.pause()
    fun resume() = active.play()
    fun seek(sec: Double) { cancelScheduled(); active.seek(sec) }

    fun step() {
        // shortcut: blends (overlap > 0) are handled as gapless handovers until the AutoMix ramps land.
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
        active.play()
        old.stop()
    }
}
