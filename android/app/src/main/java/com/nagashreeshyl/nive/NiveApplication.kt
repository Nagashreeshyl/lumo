package com.nagashreeshyl.nive

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.nagashreeshyl.nive.audio.ExoDeck
import com.nagashreeshyl.nive.audio.Media
import com.nagashreeshyl.nive.audio.MixEngine
import com.nagashreeshyl.nive.player.DataStoreQueueStore
import com.nagashreeshyl.nive.data.Downloads
import com.nagashreeshyl.nive.data.Library
import com.nagashreeshyl.nive.data.db.NiveDb
import com.nagashreeshyl.nive.player.PlayerController
import com.nagashreeshyl.nive.player.RepoRadio
import com.nagashreeshyl.nive.player.SessionPlayer
import com.nagashreeshyl.nive.player.StreamResolver
import com.nagashreeshyl.nive.player.YTStreams
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class NiveApplication : Application() {
    val scope = MainScope()
    lateinit var media: Media
    lateinit var resolver: StreamResolver
    lateinit var player: PlayerController
    lateinit var library: Library
    lateinit var downloads: Downloads
    lateinit var sessionPlayer: SessionPlayer
    private var resumeOnFocus = false

    override fun onCreate() {
        super.onCreate()
        resolver = StreamResolver(YTStreams::find)
        media = Media(this, resolver)
        library = Library(NiveDb.open(this).dao())
        downloads = Downloads(this, media, library)
        val engine = MixEngine(ExoDeck(this, media.sources), ExoDeck(this, media.sources))
        player = PlayerController(engine, RepoRadio, library, DataStoreQueueStore(this, scope),
            analysis = { null }, scope = scope, onStreamExpired = resolver::invalidate)
        sessionPlayer = SessionPlayer(player)
        player.onChange = { sessionPlayer.refresh() }
        player.startTicker()
        scope.launch { player.restore() }
        scope.launch { com.nagashreeshyl.nive.data.Repo.ensureVisitor() }
        scope.launch { player.isPlaying.collect { if (it) requestFocus() } }
    }

    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; player.pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocus = player.isPlaying.value; player.pause() }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) { resumeOnFocus = false; player.resume() }
                }
            }
            .build()
    }

    private fun requestFocus() { audio.requestAudioFocus(focus) }
}
