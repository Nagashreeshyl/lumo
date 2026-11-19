package com.nagashreeshyl.lumo

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.nagashreeshyl.lumo.audio.ExoDeck
import com.nagashreeshyl.lumo.audio.Media
import com.nagashreeshyl.lumo.audio.MixEngine
import com.nagashreeshyl.lumo.player.DataStoreQueueStore
import com.nagashreeshyl.lumo.data.Downloads
import com.nagashreeshyl.lumo.data.Library
import com.nagashreeshyl.lumo.data.db.LumoDb
import com.nagashreeshyl.lumo.player.PlayerController
import com.nagashreeshyl.lumo.player.RepoRadio
import com.nagashreeshyl.lumo.player.SessionPlayer
import com.nagashreeshyl.lumo.player.StreamResolver
import com.nagashreeshyl.lumo.player.YTStreams
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class LumoApplication : Application() {
    val scope = MainScope()
    lateinit var media: Media
    lateinit var audioCache: com.nagashreeshyl.lumo.audio.AudioCache
    /** Full-screen player shown (UI state shared by the mini player, notification tap and back button). */
    val showPlayer = kotlinx.coroutines.flow.MutableStateFlow(false)
    lateinit var resolver: StreamResolver
    lateinit var player: PlayerController
    lateinit var library: Library
    lateinit var settings: com.nagashreeshyl.lumo.data.Settings
    lateinit var session: com.nagashreeshyl.lumo.auth.Session
    /** Route to open from a launcher shortcut (e.g. "search"). */
    val pendingRoute = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    lateinit var downloads: Downloads
    lateinit var sessionPlayer: SessionPlayer
    private var resumeOnFocus = false

    override fun onCreate() {
        super.onCreate()
        resolver = StreamResolver(YTStreams::find)
        media = Media(this, resolver)
        audioCache = com.nagashreeshyl.lumo.audio.AudioCache(this, media, resolver)
        settings = com.nagashreeshyl.lumo.data.Settings(this)
        session = com.nagashreeshyl.lumo.auth.Session(this)
        library = Library(LumoDb.open(this).dao())
        downloads = Downloads(this, media, library)
        val engine = MixEngine(ExoDeck(this, media.sources), ExoDeck(this, media.sources))
        player = PlayerController(engine, RepoRadio, library, DataStoreQueueStore(this, scope),
            analysis = { audioCache.analysis(it) }, scope = scope, onStreamExpired = resolver::invalidate)
        player.automix = settings.automix
        player.autoplay = settings.autoplay
        sessionPlayer = SessionPlayer(player)
        player.onChange = { sessionPlayer.refresh() }
        player.startTicker()
        scope.launch { player.restore() }
        scope.launch { com.nagashreeshyl.lumo.data.Repo.ensureVisitor() }
        scope.launch { player.isPlaying.collect { if (it) requestFocus() } }
        scope.launch { library.dao.playlists().collect { Shortcuts.update(this@LumoApplication, it) } }
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
