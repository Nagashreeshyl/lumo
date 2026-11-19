package com.nagashreeshyl.lumo

import com.nagashreeshyl.lumo.audio.Deck
import com.nagashreeshyl.lumo.audio.TrackAnalysis
import com.nagashreeshyl.lumo.model.Song
import com.nagashreeshyl.lumo.player.LibraryApi
import com.nagashreeshyl.lumo.player.QueueStore
import com.nagashreeshyl.lumo.player.RadioApi
import com.nagashreeshyl.lumo.player.SavedQueue

/** A deck whose clock the test moves by hand. */
class FakeDeck(val name: String) : Deck {
    var song: Song? = null
    var playing = false
    var ready = false
    var durationSec = 200.0
    var speed = 1f
    var loads = 0
    override var position = 0.0
    override val duration get() = if (ready) durationSec else 0.0
    override val isReady get() = ready
    override val isPlaying get() = playing
    override var volume = 1f
    override var listener: Deck.Listener? = null
    override val songId get() = song?.id

    override fun load(song: Song, startAt: Double) { this.song = song; position = startAt; playing = false; ready = true; loads++ }
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun seek(sec: Double) { position = sec }
    override fun setRate(rate: Float) { speed = rate }
    override fun stop() { song = null; playing = false; ready = false; position = 0.0 }
    override fun setBass(gainDb: Float) {}

    fun advance(sec: Double) { if (playing) position += sec * speed }
    fun end() { playing = false; listener?.onEnded() }
    fun fail(code: Int?) { playing = false; listener?.onError(code) }
}

class FakeLibrary : LibraryApi {
    val plays = mutableMapOf<String, Int>()
    val skips = mutableMapOf<String, Int>()
    val listened = mutableMapOf<String, Double>()
    val disliked = mutableSetOf<String>()
    override fun dislikedIds() = disliked.toSet()
    override fun skippedOrDisliked() = disliked + skips.filterValues { it >= 2 }.keys
    override fun recordPlay(song: Song) { plays.merge(song.id, 1, Int::plus) }
    override fun recordSkip(id: String) { skips.merge(id, 1, Int::plus) }
    override fun addListened(song: Song, seconds: Double) { listened.merge(song.id, seconds, Double::plus) }
    override fun markDisliked(song: Song) { disliked += song.id }
    override fun undislike(id: String) { disliked -= id }
    override fun localFile(id: String): String? = null
}

class FakeRadio(var mix: (String) -> List<Song> = { id -> (1..10).map { Song("$id-r$it", "r$it", "", null, 200) } }) : RadioApi {
    var calls = 0
    override suspend fun youtubeMix(videoId: String): List<Song> { calls++; return mix(videoId) }
    override suspend fun radio(videoId: String): List<Song> = mix(videoId)
}

class FakeStore(var saved: SavedQueue? = null) : QueueStore {
    override suspend fun load() = saved
    override fun save(queue: SavedQueue) { saved = queue }
}

fun analysisOf(duration: Double) = TrackAnalysis(duration, 120.0, 0.0, 0.0, 0.0, duration)
