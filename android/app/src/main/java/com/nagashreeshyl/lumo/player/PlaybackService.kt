package com.nagashreeshyl.lumo.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nagashreeshyl.lumo.MainActivity
import com.nagashreeshyl.lumo.LumoApplication

/** Keeps music playing in the background with the media notification and lock-screen controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val app get() = application as LumoApplication

    // Headphones unplugged / Bluetooth disconnected: pause like every music app.
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { app.player.pause() }
    }

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, app.sessionPlayer).setSessionActivity(open).build()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!app.player.isPlaying.value) stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(noisy)
        session?.release()
        session = null
        super.onDestroy()
    }
}
