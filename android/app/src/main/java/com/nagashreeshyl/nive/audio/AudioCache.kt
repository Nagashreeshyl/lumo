package com.nagashreeshyl.nive.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.common.C
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import com.nagashreeshyl.nive.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Beat analysis for AutoMix: reads the whole song through the caches (filling the play cache, so playback of it is
 * instant too), decodes it and analyses tempo/beats/silence. Results are cached as JSON per song.
 */
@OptIn(UnstableApi::class)
class AudioCache(context: Context, private val media: Media, private val resolver: com.nagashreeshyl.nive.player.StreamResolver) {
    private val dir = File(context.filesDir, "analysis").apply { mkdirs() }
    private val tmp = File(context.cacheDir, "decode").apply { mkdirs() }
    private val inFlight = mutableMapOf<String, CompletableDeferred<TrackAnalysis?>>()
    private val gate = Semaphore(2)

    /** Null on any failure (offline, decode error); callers fall back to plain crossfades. */
    suspend fun analysis(song: Song): TrackAnalysis? {
        val file = File(dir, "${song.id}.json")
        if (file.exists()) runCatching { return Json.decodeFromString<TrackAnalysis>(file.readText()) }
        val pending = synchronized(inFlight) {
            inFlight[song.id]?.let { return@synchronized it to false }
            CompletableDeferred<TrackAnalysis?>().also { inFlight[song.id] = it } to true
        }
        if (!pending.second) return pending.first.await()
        val result = runCatching { gate.withPermit { withContext(Dispatchers.IO) { analyse(song, file) } } }
            .onFailure { Log.i("Nive", "analysis failed ${song.id}: ${it.message}") }.getOrNull()
        synchronized(inFlight) { inFlight.remove(song.id) }
        pending.first.complete(result)
        return result
    }

    private fun analyse(song: Song, out: File): TrackAnalysis? {
        val audio = File(tmp, song.id)
        try {
            copyFromCaches(song.id, audio)
            val pcm = PcmDecoder.decode(audio) ?: return null
            val a = if (pcm.samples.isEmpty()) TrackAnalysis(pcm.duration, 120.0, 0.0, 0.0, 0.0, pcm.duration)
                else BeatAnalyzer.analyze(pcm.samples, pcm.sampleRate)
            out.writeText(Json.encodeToString(TrackAnalysis.serializer(), a))
            Log.i("Nive", "analysed ${song.id}: bpm ${a.bpm.toInt()} conf ${"%.2f".format(a.confidence)}")
            return a
        } finally {
            audio.delete()
        }
    }

    /**
     * Copies the song to `to`. Downloaded songs are read straight from the download cache. Otherwise the file is
     * fetched as parallel 1 MB ranges through the play cache: googlevideo throttles a single request to playback speed.
     */
    private fun copyFromCaches(id: String, to: File) {
        val downloaded = ContentMetadata.getContentLength(media.downloads.getContentMetadata(id))
        if (downloaded > 0 && media.downloads.isCached(id, 0, downloaded)) {
            read(CacheDataSource.Factory().setCache(media.downloads).createDataSource(), id, 0, C.LENGTH_UNSET.toLong()) { b, n -> to.appendBytes(b.copyOf(n)) }
            return
        }
        val url = runBlocking { resolver.resolve(id) }
        val total = contentLength(url) ?: throw java.io.IOException("no content length")
        RandomAccessFile(to, "rw").use { raf ->
            raf.setLength(total)
            runBlocking(Dispatchers.IO) {
                val gate = Semaphore(4)
                (0 until ((total + CHUNK - 1) / CHUNK).toInt()).map { i ->
                    async {
                        gate.withPermit {
                            val start = i * CHUNK
                            var offset = start
                            read(media.playCache.createDataSource(), id, start, minOf(CHUNK, total - start)) { b, n ->
                                synchronized(raf) { raf.seek(offset); raf.write(b, 0, n) }
                                offset += n
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun read(source: androidx.media3.datasource.DataSource, id: String, position: Long, length: Long, sink: (ByteArray, Int) -> Unit) {
        source.open(DataSpec.Builder().setUri(Uri.parse("nive://$id")).setKey(id).setPosition(position).setLength(length).build())
        try {
            val buf = ByteArray(64 * 1024)
            while (true) { val n = source.read(buf, 0, buf.size); if (n < 0) break; sink(buf, n) }
        } finally { source.close() }
    }

    /** Total size from `Content-Range: bytes 0-0/<total>`. */
    private fun contentLength(url: String): Long? = media.http.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").build())
        .execute().use { it.header("Content-Range")?.substringAfter("/")?.toLongOrNull() }

    private companion object { const val CHUNK = 1L shl 20 }
}
