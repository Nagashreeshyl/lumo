package com.nagashreeshyl.lumo.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.nagashreeshyl.lumo.model.Song

/** A Deck backed by its own ExoPlayer. Audio focus is handled once, app-wide, not per deck. */
@OptIn(UnstableApi::class)
class ExoDeck(context: Context, sources: MediaSource.Factory) : Deck {
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(sources)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
    override var listener: Deck.Listener? = null
    override var songId: String? = null
        private set
    private var readySent = false
    private var bass: BassShelf? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !readySent) { readySent = true; listener?.onReady() }
                if (state == Player.STATE_ENDED) listener?.onEnded()
            }
            override fun onPlayerError(error: PlaybackException) { listener?.onError(httpCode(error)) }
        })
    }

    private fun httpCode(e: Throwable?): Int? {
        var c = e
        while (c != null) { if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode; c = c.cause }
        return null
    }

    override fun load(song: Song, startAt: Double) {
        songId = song.id
        readySent = false
        exo.playWhenReady = false
        exo.setMediaItem(MediaItem.Builder().setMediaId(song.id).setUri("lumo://${song.id}").setCustomCacheKey(song.id).build(),
            (startAt * 1000).toLong())
        exo.prepare()
    }
    override fun play() { exo.playWhenReady = true }
    override fun pause() { exo.playWhenReady = false }
    override fun seek(sec: Double) = exo.seekTo((sec * 1000).toLong())
    override fun setRate(rate: Float) { exo.playbackParameters = PlaybackParameters(rate, 1f) }
    override fun setBass(gainDb: Float) {
        val b = bass ?: BassShelf.create(exo.audioSessionId)?.also { bass = it } ?: return
        b.set(gainDb)
    }
    override fun stop() { songId = null; readySent = false; exo.stop(); exo.clearMediaItems() }
    override val position get() = exo.currentPosition / 1000.0
    override val duration get() = exo.duration.let { if (it == C.TIME_UNSET) 0.0 else it / 1000.0 }
    override val isReady get() = exo.playbackState == Player.STATE_READY || (readySent && exo.playbackState == Player.STATE_BUFFERING)
    override val isPlaying get() = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.playbackState != Player.STATE_IDLE
    override var volume: Float
        get() = exo.volume
        set(v) { exo.volume = v }
}

/** Lowest equalizer band on a deck's audio session, used to swap the bass mid-blend. Not every phone allows it. */
class BassShelf private constructor(private val eq: android.media.audiofx.Equalizer) {
    fun set(db: Float) = runCatching {
        val (min, _) = eq.bandLevelRange.let { it[0] to it[1] }
        eq.setBandLevel(0, (db * 100).toInt().coerceIn(min.toInt(), 0).toShort())
    }
    companion object {
        fun create(session: Int): BassShelf? = runCatching {
            BassShelf(android.media.audiofx.Equalizer(0, session).apply { enabled = true })
        }.getOrNull()
    }
}
