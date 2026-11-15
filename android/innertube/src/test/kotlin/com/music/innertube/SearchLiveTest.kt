package com.music.innertube

import com.music.innertube.models.SongItem
import com.music.innertube.YouTube.SearchFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SearchLiveTest {
    @Test fun searchReturnsSongs() = runBlocking {
        assumeTrue(System.getenv("NIVE_LIVE") == "1")
        val result = YouTube.search("daft punk one more time", SearchFilter.FILTER_SONG).getOrThrow()
        assertTrue(result.items.any { it is SongItem })
    }
}
