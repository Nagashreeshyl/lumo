package com.nagashreeshyl.nive.audio

import com.nagashreeshyl.nive.model.Song

/** One player "deck". MixEngine owns two, so AutoMix can play the end of one song over the start of the next. */
interface Deck {
    fun load(song: Song, startAt: Double)    // prepares paused at `startAt` seconds
    fun play()
    fun pause()
    fun seek(sec: Double)
    fun setRate(rate: Float)                 // tempo, pitch unchanged
    fun setBass(gainDb: Float)               // low-shelf for the bass swap; best effort
    fun stop()
    val songId: String?
    val position: Double                     // seconds
    val duration: Double                     // seconds, 0 = unknown yet
    val isReady: Boolean
    val isPlaying: Boolean
    var volume: Float
    var listener: Listener?

    interface Listener {
        fun onReady() {}
        fun onEnded()
        fun onError(httpCode: Int?)
    }
}
