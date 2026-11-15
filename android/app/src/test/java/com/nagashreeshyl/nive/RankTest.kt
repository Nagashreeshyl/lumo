package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.logic.Rank
import com.nagashreeshyl.nive.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankTest {
    @Test fun interleaveRoundRobinsAndDedupes() =
        assertEquals(listOf("a", "x", "b", "y"), Rank.interleave(listOf(songs("a", "b", "c"), songs("x", "a", "y")), setOf("c"), 10).map { it.id })

    @Test fun interleaveRespectsLimit() = assertEquals(2, Rank.interleave(listOf(songs("a", "b", "c")), emptySet(), 2).size)

    @Test fun onRepeatRanksMostPlayedAndIgnoresOneOffsAndDisliked() {
        val now = System.currentTimeMillis()
        fun e(id: String, plays: Int, disliked: Boolean = false, daysAgo: Double = 1.0) =
            Rank.Listen(Song(id, id, "", null, 0), plays, disliked, now - (daysAgo * 86_400_000).toLong())
        val picks = Rank.onRepeat(listOf(e("once", 1), e("fav", 9), e("often", 4), e("hated", 7, true), e("old", 6, daysAgo = 120.0)), now)
        assertEquals(listOf("fav", "often"), picks.map { it.id })
    }

    @Test fun listeningTimeFormatsHoursAndMinutes() {
        assertEquals("0m", Rank.listening(59.0))
        assertEquals("45m", Rank.listening(45 * 60.0))
        assertEquals("3h 5m", Rank.listening(3 * 3600 + 5 * 60.0))
    }

    @Test fun dislikedNeedsRepeatedSkips() {
        assertFalse(Rank.isDisliked(1, 0)); assertTrue(Rank.isDisliked(2, 0)); assertFalse(Rank.isDisliked(2, 10))
    }

    @Test fun seedsFavourLovedAndRecent() {
        val now = System.currentTimeMillis()
        val seeds = Rank.seeds(listOf(
            Rank.Seed("liked", plays = 2, liked = true, skips = 0, lastPlayed = now),
            Rank.Seed("skipped", plays = 1, liked = false, skips = 3, lastPlayed = now),
            Rank.Seed("played", plays = 4, liked = false, skips = 0, lastPlayed = now),
        ), now)
        assertEquals(listOf("liked", "played"), seeds)
    }

    @Test fun artworkResizesGoogleThumbnails() {
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", Song("v", "", "", "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 0).artwork(544))
        assertEquals("https://i.ytimg.com/vi/v/hqdefault.jpg", Song("v", "", "", null, 0).artwork(544))
    }
}
