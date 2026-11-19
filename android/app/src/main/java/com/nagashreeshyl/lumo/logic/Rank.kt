package com.nagashreeshyl.lumo.logic

import com.nagashreeshyl.lumo.model.Song

/** Pure ranking rules behind Home (port of the iPhone app's Recommender helpers). */
object Rank {
    private const val DAY_MS = 86_400_000.0

    data class Listen(val song: Song, val plays: Int, val disliked: Boolean, val lastPlayed: Long?)
    data class Seed(val videoId: String, val plays: Int, val liked: Boolean, val skips: Int, val lastPlayed: Long?)

    /** Songs you keep coming back to: 3+ real plays in the last 60 days, most played first. */
    fun onRepeat(listens: List<Listen>, now: Long = System.currentTimeMillis()): List<Song> {
        val cutoff = now - 60 * DAY_MS
        return listens
            .filter { it.plays >= 3 && !it.disliked && (it.lastPlayed ?: 0L) > cutoff }
            .sortedWith(compareByDescending<Listen> { it.plays }.thenByDescending { it.lastPlayed ?: 0L })
            .take(20)
            .map { it.song }
    }

    /** Skipped much more often than finished. */
    fun isDisliked(skips: Int, plays: Int) = skips >= 2 && skips > plays / 2

    /** Up to 4 seed songs: most-loved first, favouring recent activity. */
    fun seeds(tracks: List<Seed>, now: Long = System.currentTimeMillis()): List<String> = tracks
        .map { t ->
            val days = t.lastPlayed?.let { (now - it) / DAY_MS } ?: 365.0
            t.videoId to (t.plays + (if (t.liked) 5 else 0) - t.skips * 2 - days * 0.1)
        }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(4)
        .map { it.first }

    /** Round-robin merge so every seed contributes, without duplicates. */
    fun interleave(lists: List<List<Song>>, excluding: Set<String>, limit: Int): List<Song> {
        val seen = excluding.toMutableSet()
        val out = mutableListOf<Song>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (list in lists) {
            if (i < list.size && seen.add(list[i].id)) {
                out += list[i]
                if (out.size == limit) return out
            }
        }
        return out
    }

    /** "3h 5m" / "45m". */
    fun listening(seconds: Double): String {
        val s = seconds.toLong()
        val h = s / 3600; val m = s % 3600 / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
