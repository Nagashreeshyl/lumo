package com.nagashreeshyl.nive.player

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nagashreeshyl.nive.logic.RepeatMode

/** What the lock screen, notification and Bluetooth see: a view of PlayerController, commands forwarded to it. */
@OptIn(UnstableApi::class)
class SessionPlayer(private val c: PlayerController) : SimpleBasePlayer(Looper.getMainLooper()) {
    fun refresh() = invalidateState()

    override fun getState(): State {
        val q = c.queue.value
        val index = c.index.value
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_TO_MEDIA_ITEM, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
            COMMAND_SET_REPEAT_MODE, COMMAND_SET_SHUFFLE_MODE,
        ).build()
        val b = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(c.isPlaying.value, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(when (c.repeat.value) { RepeatMode.OFF -> REPEAT_MODE_OFF; RepeatMode.ALL -> REPEAT_MODE_ALL; RepeatMode.ONE -> REPEAT_MODE_ONE })
            .setShuffleModeEnabled(c.shuffle.value)
        if (q.isEmpty()) return b.setPlaybackState(STATE_IDLE).build()
        val items = q.mapIndexed { i, s ->
            val seconds = if (i == index && c.duration.value > 0) c.duration.value else s.duration.toDouble()
            MediaItemData.Builder("$i:${s.id}")
                .setMediaItem(MediaItem.Builder().setMediaId(s.id).setMediaMetadata(
                    MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setArtworkUri(Uri.parse(s.artwork(544))).build()
                ).build())
                .setDurationUs(if (seconds > 0) (seconds * 1_000_000).toLong() else C.TIME_UNSET)
                .build()
        }
        return b.setPlaylist(items)
            .setCurrentMediaItemIndex(index.coerceIn(items.indices))
            .setPlaybackState(if (c.isLoading.value && c.isPlaying.value) STATE_BUFFERING else STATE_READY)
            .setContentPositionMs { (c.engine.currentTime * 1000).toLong() }
            .build()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) c.resume() else c.pause(); return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> c.next()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> c.previous()
            else -> if (mediaItemIndex != c.index.value && mediaItemIndex >= 0) c.jump(mediaItemIndex) else c.seek(positionMs / 1000.0)
        }
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        c.setRepeat(when (repeatMode) { REPEAT_MODE_ALL -> RepeatMode.ALL; REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF })
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled != c.shuffle.value) c.toggleShuffle(); return done()
    }

    override fun handleStop(): ListenableFuture<*> { c.pause(); return done() }
}
