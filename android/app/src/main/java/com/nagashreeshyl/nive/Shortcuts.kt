package com.nagashreeshyl.nive

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.nagashreeshyl.nive.data.db.Playlist

/** Long-press the app icon: Shuffle Liked Songs, Search (static, res/xml/shortcuts.xml) and your latest playlists. */
object Shortcuts {
    const val PLAY_LIKED = "com.nagashreeshyl.nive.PLAY_LIKED"
    const val SEARCH = "com.nagashreeshyl.nive.SEARCH"
    const val PLAY_PLAYLIST = "com.nagashreeshyl.nive.PLAY_PLAYLIST"

    fun update(context: Context, playlists: List<Playlist>) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = playlists.filter { it.trackIds.isNotEmpty() }.take(2).map { list ->
            ShortcutInfo.Builder(context, "playlist-${list.id}")
                .setShortLabel(list.name.take(24))
                .setLongLabel("Play ${list.name}")
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_playlist))
                .setIntent(Intent(PLAY_PLAYLIST, null, context, MainActivity::class.java).putExtra("id", list.id))
                .build()
        }
        runCatching { sm.dynamicShortcuts = shortcuts }
    }

    /** Handles a shortcut intent; returns true if it was one. */
    fun handle(app: NiveApplication, intent: Intent?): Boolean {
        when (intent?.action) {
            PLAY_LIKED -> {
                val liked = app.library.dao.likedNow().map { it.song }
                if (liked.isNotEmpty()) { app.player.play(liked.shuffled()); app.showPlayer.value = true }
            }
            PLAY_PLAYLIST -> {
                val list = app.library.dao.playlistNow(intent.getLongExtra("id", -1)) ?: return true
                val songs = app.library.songs(list)
                if (songs.isNotEmpty()) { app.player.play(songs); app.showPlayer.value = true }
            }
            SEARCH -> app.pendingRoute.value = "search"
            else -> return false
        }
        return true
    }
}
