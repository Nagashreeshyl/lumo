package com.nagashreeshyl.nive.data

import com.nagashreeshyl.nive.data.db.LibraryDao
import com.nagashreeshyl.nive.data.db.Playlist
import com.nagashreeshyl.nive.data.db.Track
import com.nagashreeshyl.nive.model.Song
import com.nagashreeshyl.nive.player.LibraryApi

/** Likes, playlists, listening signals and Not Interested, stored with Room. */
class Library(val dao: LibraryDao) : LibraryApi {
    private val pendingListen = mutableMapOf<Song, Double>()

    fun track(song: Song): Track { dao.insertIfMissing(Track.of(song)); return dao.track(song.id)!! }
    fun existing(id: String): Track? = dao.track(id)
    private fun edit(song: Song, change: (Track) -> Track) = dao.upsert(change(track(song)))

    fun isLiked(id: String) = dao.track(id)?.liked == true
    fun setLiked(song: Song, liked: Boolean) = edit(song) {
        it.copy(liked = liked, likedAt = if (liked) System.currentTimeMillis() else it.likedAt, disliked = if (liked) false else it.disliked)
    }
    fun setDownloaded(song: Song, downloaded: Boolean) = edit(song) { it.copy(localFile = if (downloaded) song.id else null) }

    // Playlists
    fun createPlaylist(name: String): Long = dao.insert(Playlist(name = name))
    fun rename(list: Playlist, name: String) { if (name.isNotBlank()) dao.update(list.copy(name = name.trim())) }
    fun delete(list: Playlist) = dao.delete(list)
    fun add(list: Playlist, song: Song) {
        track(song)
        val now = dao.playlistNow(list.id) ?: return
        if (song.id !in now.trackIds) dao.update(now.copy(trackIds = now.trackIds + song.id))
    }
    fun remove(list: Playlist, id: String) { dao.playlistNow(list.id)?.let { dao.update(it.copy(trackIds = it.trackIds - id)) } }
    fun reorder(list: Playlist, ids: List<String>) { dao.playlistNow(list.id)?.let { dao.update(it.copy(trackIds = ids)) } }
    fun songs(list: Playlist): List<Song> = list.trackIds.mapNotNull { dao.track(it)?.song }

    // Backup
    fun exportBackup(): String { flushListening(); return LibraryBackup.export(dao.allTracks(), dao.allPlaylists()) }
    fun importBackup(text: String): LibraryBackup.Merge {
        val m = LibraryBackup.merge(LibraryBackup.decode(text), dao::track, dao.allPlaylists())
        dao.upsertAll(m.tracks)
        m.newPlaylists.forEach { dao.insert(it) }
        m.updatedPlaylists.forEach { dao.update(it) }
        return m
    }

    // LibraryApi (player signals)
    override fun dislikedIds() = dao.dislikedIds().toSet()
    override fun skippedOrDisliked() = dao.skippedOrDisliked().toSet()
    override fun recordPlay(song: Song) { flushListening(); edit(song) { it.copy(playCount = it.playCount + 1, lastPlayed = System.currentTimeMillis()) } }
    override fun recordSkip(id: String) { dao.track(id)?.let { dao.upsert(it.copy(skipCount = it.skipCount + 1)) } }
    override fun addListened(song: Song, seconds: Double) {
        pendingListen.merge(song, seconds, Double::plus)
        if (pendingListen.values.sum() >= 15) flushListening()   // write about every 15 s, not every tick
    }
    override fun markDisliked(song: Song) = edit(song) { it.copy(disliked = true, liked = false) }
    override fun undislike(id: String) { dao.track(id)?.let { dao.upsert(it.copy(disliked = false)) } }
    override fun localFile(id: String) = dao.track(id)?.localFile

    fun flushListening() {
        val pending = pendingListen.toMap(); pendingListen.clear()
        pending.forEach { (song, s) -> edit(song) { it.copy(listenedSeconds = it.listenedSeconds + s) } }
    }
}
