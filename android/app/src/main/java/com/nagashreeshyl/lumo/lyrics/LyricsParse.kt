package com.nagashreeshyl.lumo.lyrics

import com.nagashreeshyl.lumo.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class LyricLine(val id: Int, val time: Double?, val text: String)   // time null = unsynced

/** Lyrics parsing and title cleanup (port of the iPhone app's Lyrics helpers). */
object LyricsParse {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class PlusResponse(val lyrics: List<PlusLine>? = null)
    @Serializable private data class PlusLine(val time: Double? = null, val text: String? = null)

    /** `{"lyrics":[{"time": ms, "text": "..."}]}` → lines (synced when every line has a time). */
    fun parseLyricsPlus(body: String): List<LyricLine> {
        val lines = runCatching { json.decodeFromString<PlusResponse>(body).lyrics }.getOrNull() ?: return emptyList()
        val synced = lines.all { it.time != null }
        return lines.mapIndexed { i, l -> LyricLine(i, if (synced) l.time!! / 1000 else null, l.text ?: "") }
    }

    /** Strict to loose, without duplicates. */
    fun queries(song: Song): List<Map<String, String>> {
        val base = baseTitle(song.title, song.artist)
        val clean = cleanTitle(song.title)
        return listOf(
            mapOf("track_name" to base, "artist_name" to song.artist),
            mapOf("track_name" to clean, "artist_name" to song.artist),
            mapOf("q" to "$base ${song.artist}"),
            mapOf("q" to base),
        ).filter { q -> q.values.none { it.isBlank() } }.distinct()
    }

    private val brackets = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val featuring = Regex("""\s+(ft\.?|feat\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val videoTags = Regex("""\s*[(\[][^)\]]*(official|lyric|video|audio|visualizer|mv)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

    /** Just the song name: drops "Artist - " prefixes, "| Movie | Singer" tails, brackets and featured artists. */
    fun baseTitle(title: String, artist: String): String {
        var t = title
        val dash = t.indexOf(" - ")
        if (dash >= 0 && (artist.isEmpty() || t.substring(0, dash).contains(artist, ignoreCase = true))) t = t.substring(dash + 3)
        t = t.substringBefore("|")
        t = t.replace(brackets, "").replace(featuring, "")
        return t.trim()
    }

    /** Drops "(Official Video)", "[Lyrics]" etc. */
    fun cleanTitle(title: String) = title.replace(videoTags, "").trim()

    /** "[01:23.45] line" → LyricLine(time = 83.45). Lines with several stamps are repeated. */
    fun parseLRC(lrc: String): List<LyricLine> {
        val lines = mutableListOf<Pair<Double, String>>()
        for (raw in lrc.lines()) {
            var rest = raw
            val times = mutableListOf<Double>()
            while (rest.startsWith("[") && rest.contains("]")) {
                val close = rest.indexOf(']')
                val parts = rest.substring(1, close).split(":")
                val m = parts.getOrNull(0)?.toDoubleOrNull(); val s = parts.getOrNull(1)?.toDoubleOrNull()
                if (parts.size == 2 && m != null && s != null) times += m * 60 + s
                rest = rest.substring(close + 1)
            }
            val text = rest.trim()
            times.forEach { lines += it to text }
        }
        return lines.sortedBy { it.first }.mapIndexed { i, (t, s) -> LyricLine(i, t, s) }
    }

    /** Non-Latin lyrics in English letters, without diacritics ("vanakkam", not "vaṇakkam"). */
    fun romanize(text: String): String =
        android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII").transliterate(text)

    /** True when the lyrics are mostly in a non-Latin script. */
    fun needsRomanization(lines: List<LyricLine>): Boolean {
        val letters = lines.take(20).flatMap { l -> l.text.codePoints().toArray().filter(Character::isLetter) }
        if (letters.isEmpty()) return false
        return letters.count { it >= 128 } * 2 > letters.size
    }

    /** Index of the line being sung at `time`. */
    fun activeIndex(lines: List<LyricLine>, time: Double): Int? =
        lines.indexOfLast { (it.time ?: Double.POSITIVE_INFINITY) <= time + 0.2 }.takeIf { it >= 0 }
}
