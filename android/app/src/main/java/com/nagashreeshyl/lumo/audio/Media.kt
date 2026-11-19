package com.nagashreeshyl.lumo.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.nagashreeshyl.lumo.player.StreamResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

/** Shared caches and the data source chain: downloads → 500 MB play cache → YouTube (URL resolved on demand). */
@OptIn(UnstableApi::class)
class Media(context: Context, private val resolver: StreamResolver) {
    private val db = StandaloneDatabaseProvider(context)
    val cache: Cache = SimpleCache(File(context.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024), db)
    val downloads: Cache = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), db)
    val http = OkHttpClient.Builder().build()

    val upstream = ResolvingDataSource.Factory(OkHttpDataSource.Factory(http)) { spec ->
        val id = spec.uri.host ?: return@Factory spec
        if (spec.uri.scheme != "lumo" && spec.uri.scheme != "nive") return@Factory spec   // nive:// = downloads queued before the rename
        spec.withUri(Uri.parse(runBlocking { resolver.resolve(id) }))
    }

    /** Play cache backed by YouTube; used to write downloads too. */
    val playCache: CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)

    /** Downloaded songs are read first, then the play cache, then the network. */
    val sources: MediaSource.Factory = DefaultMediaSourceFactory(
        CacheDataSource.Factory().setCache(downloads).setUpstreamDataSourceFactory(playCache).setCacheWriteDataSinkFactory(null)
    )
}
