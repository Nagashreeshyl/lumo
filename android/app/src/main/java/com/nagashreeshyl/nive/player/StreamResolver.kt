package com.nagashreeshyl.nive.player

import android.net.Uri
import android.util.Log
import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Caches each song's stream URL until it expires. A 403 (expired or blocked URL) drops the entry, so the next
 * open re-resolves.
 */
class StreamResolver(
    private val source: suspend (String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = mutableMapOf<String, Pair<String, Long>>()
    private val lock = Mutex()

    suspend fun resolve(videoId: String): String = lock.withLock {
        cache[videoId]?.let { (url, expires) -> if (now() < expires) return url }
        val url = source(videoId) ?: throw java.io.IOException("No playable stream for $videoId")
        cache[videoId] = url to expiry(url)
        url
    }

    fun onHttpError(videoId: String, code: Int) {
        if (code == 403 || code == 410) synchronized(cache) { cache.remove(videoId) }
    }

    fun invalidate(videoId: String) = synchronized(cache) { cache.remove(videoId) }

    /** googlevideo URLs carry `expire` (unix seconds); refresh a minute early. Unknown → 30 minutes. */
    private fun expiry(url: String): Long {
        val expire = runCatching { Uri.parse(url).getQueryParameter("expire") }.getOrNull()?.toLongOrNull()
            ?: Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return expire?.let { it * 1000 - 60_000 } ?: (now() + 30 * 60_000)
    }
}

/** Finds a playable audio URL: YouTube clients that don't need PoTokens first, NewPipe's extractor as fallback. */
object YTStreams {
    private const val TAG = "Nive"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val clients = listOf(
        YouTubeClient.ANDROID_VR_NO_AUTH, YouTubeClient.ANDROID_VR_1_65_10, YouTubeClient.VISIONOS,
        YouTubeClient.IOS, YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
    )

    suspend fun find(videoId: String): String? = withContext(Dispatchers.IO) {
        for (client in clients) {
            val res = YouTube.player(videoId, client = client).getOrNull() ?: continue
            if (res.playabilityStatus.status != "OK") { Log.i(TAG, "${client.clientName}: ${res.playabilityStatus.status}"); continue }
            val format = bestAudio(res.streamingData?.adaptiveFormats.orEmpty()) ?: continue
            val url = NewPipeExtractor.getStreamUrl(format, videoId) ?: format.url ?: continue
            if (works(url)) return@withContext url
            Log.i(TAG, "${client.clientName}: stream rejected")
        }
        NewPipeExtractor.newPipePlayer(videoId)
            .filter { (itag, _) -> itag in audioItags }
            .sortedBy { audioItags.indexOf(it.first) }
            .firstOrNull { works(it.second) }?.second
    }

    // Opus 160k, AAC 128k, Opus 70k/50k, AAC 48k.
    private val audioItags = listOf(251, 140, 250, 249, 139)

    private fun bestAudio(formats: List<PlayerResponse.StreamingData.Format>) =
        formats.filter { it.isAudio && it.audioTrack?.let { t -> t.isAutoDubbed != true } != false }
            .maxByOrNull { it.bitrate }

    private fun works(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).header("Range", "bytes=0-1").build()).execute().use { it.code == 206 || it.code == 200 }
    }.getOrDefault(false)
}
