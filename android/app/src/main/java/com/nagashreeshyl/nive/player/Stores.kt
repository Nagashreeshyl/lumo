package com.nagashreeshyl.nive.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nagashreeshyl.nive.data.Repo
import com.nagashreeshyl.nive.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val Context.queueStore by preferencesDataStore("queue")

/** Last queue and position, so the app reopens where it left off. */
class DataStoreQueueStore(private val context: Context, private val scope: CoroutineScope) : QueueStore {
    private val key = stringPreferencesKey("saved")
    override suspend fun load(): SavedQueue? =
        context.queueStore.data.first()[key]?.let { runCatching { Json.decodeFromString<SavedQueue>(it) }.getOrNull() }
    override fun save(queue: SavedQueue) {
        val json = Json.encodeToString(SavedQueue.serializer(), queue)
        scope.launch { context.queueStore.edit { it[key] = json } }
    }
}

object RepoRadio : RadioApi {
    override suspend fun youtubeMix(videoId: String): List<Song> = Repo.youtubeMix(videoId)
    override suspend fun radio(videoId: String): List<Song> = Repo.radio(videoId)
}

/** shortcut: in-memory library until the Room library lands (Task 6). */
class MemoryLibrary : LibraryApi {
    private val disliked = mutableSetOf<String>()
    override fun dislikedIds() = disliked.toSet()
    override fun skippedOrDisliked() = disliked.toSet()
    override fun recordPlay(song: Song) {}
    override fun recordSkip(id: String) {}
    override fun addListened(song: Song, seconds: Double) {}
    override fun markDisliked(song: Song) { disliked += song.id }
    override fun undislike(id: String) { disliked -= id }
    override fun localFile(id: String): String? = null
}
