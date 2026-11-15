package com.nagashreeshyl.nive.player

import com.nagashreeshyl.nive.model.Song
import kotlinx.serialization.Serializable

/** Where autoplay gets more songs. */
interface RadioApi {
    suspend fun youtubeMix(videoId: String): List<Song>
    suspend fun radio(videoId: String): List<Song>
}

/** Listening signals and Not Interested, stored in the library. */
interface LibraryApi {
    fun dislikedIds(): Set<String>
    fun skippedOrDisliked(): Set<String>
    fun recordPlay(song: Song)
    fun recordSkip(id: String)
    fun addListened(song: Song, seconds: Double)
    fun markDisliked(song: Song)
    fun undislike(id: String)
    fun localFile(id: String): String?
}

@Serializable
data class SavedQueue(val queue: List<Song>, val index: Int, val position: Double)

interface QueueStore {
    suspend fun load(): SavedQueue?
    fun save(queue: SavedQueue)
}
