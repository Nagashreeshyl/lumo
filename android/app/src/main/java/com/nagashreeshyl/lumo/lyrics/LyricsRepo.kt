package com.nagashreeshyl.lumo.lyrics

import com.nagashreeshyl.lumo.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Lyrics like the iPhone app: LyricsPlus (word-synced, from several stores) first, then strict-to-loose LRCLIB queries.
 * `get` returns (HTTP status, body) or null on network failure.
 */
class LyricsRepo(private val get: suspend (String) -> Pair<Int, String>?) {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Result(val duration: Double? = null, val syncedLyrics: String? = null, val plainLyrics: String? = null)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun fetch(song: Song): List<LyricLine> {
        val plus = lyricsPlus(LyricsParse.baseTitle(song.title, song.artist), song.artist, song.duration)
        if (plus.isNotEmpty()) return plus
        for (query in LyricsParse.queries(song)) {
            val lines = lrclib(query, song.duration)
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    /** "Search Lyrics" with the user's own words. */
    suspend fun search(text: String): List<LyricLine> = lrclib(mapOf("q" to text), 0)

    private suspend fun lyricsPlus(title: String, artist: String, duration: Int): List<LyricLine> {
        for (server in SERVERS) {
            var url = "$server/v2/lyrics/get?title=${enc(title)}&artist=${enc(artist)}"
            if (duration > 0) url += "&duration=$duration"
            val (code, body) = get(url) ?: continue
            if (code != 200) continue
            return LyricsParse.parseLyricsPlus(body)   // answered "not found": the other mirror has the same sources
        }
        return emptyList()
    }

    private suspend fun lrclib(query: Map<String, String>, duration: Int): List<LyricLine> {
        val params = query.entries.sortedByDescending { it.key }.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val (code, body) = get("https://lrclib.net/api/search?$params") ?: return emptyList()
        if (code != 200) return emptyList()
        var results = runCatching { json.decodeFromString<List<Result>>(body) }.getOrNull() ?: return emptyList()
        // Loose free-text matches must at least be the same length, or we'd show another song's words.
        if (query.containsKey("q") && duration > 0) results = results.filter { abs((it.duration ?: 0.0) - duration) <= 15 }
        val best = results.filter { !it.syncedLyrics.isNullOrEmpty() }.minByOrNull { abs((it.duration ?: 0.0) - duration) }
        best?.syncedLyrics?.let { return LyricsParse.parseLRC(it) }
        val plain = results.firstOrNull { !it.plainLyrics.isNullOrEmpty() }?.plainLyrics ?: return emptyList()
        return plain.split("\n").mapIndexed { i, t -> LyricLine(i, null, t) }
    }

    companion object {
        private val SERVERS = listOf("https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id")
        private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

        val live = LyricsRepo { url ->
            withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url).header("User-Agent", "Lumo (https://github.com/Nagashreeshyl/lumo)").build())
                        .execute().use { it.code to (it.body?.string() ?: "") }
                }.getOrNull()
            }
        }
    }
}
