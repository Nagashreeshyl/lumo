package com.nagashreeshyl.lumo.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nagashreeshyl.lumo.data.Repo
import com.nagashreeshyl.lumo.model.Song
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

