package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.data.db.Track
import com.nagashreeshyl.nive.model.BrowseSection
import com.nagashreeshyl.nive.model.Item
import com.nagashreeshyl.nive.model.Ref
import com.nagashreeshyl.nive.model.Shelf
import com.nagashreeshyl.nive.ui.home.HomeFeed
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedTest {
    private fun section(title: String, vararg ids: String) = BrowseSection(title, songs(*ids).map { Item.SongItem(it) })

    @Test fun personalShelvesFirstThenYouTubeDedupedAndFiltered() = runTest {
        val now = System.currentTimeMillis()
        val tracks = listOf(
            Track.of(songs("fav").first()).copy(liked = true, likedAt = now, playCount = 5, lastPlayed = now),
        )
        val album = Item.PageItem(Ref(Ref.Kind.album, "MP", "Album", ""))
        val youtube = listOf(
            section("Quick picks", "dup"),                 // same title as a personal shelf → dropped
            section("New podcast episodes", "ep"),         // episodes → dropped
            section("Trending", "t1", "t2"),               // songs only → a song shelf
            BrowseSection("Albums for you", listOf(album)),// mixed → "more"
        )
        val updates = mutableListOf<Pair<List<Shelf>, List<BrowseSection>>>()
        HomeFeed.build(tracks, radio = { id -> songs("$id-1", "$id-2") }, youtube = { youtube }) { s, m -> updates += s to m }
        val (shelves, more) = updates.last()
        assertEquals(listOf("Quick picks", "On repeat", "Because you liked fav", "Recently played", "Trending"), shelves.map { it.title })
        assertEquals(listOf("Albums for you"), more.map { it.title })
        assertTrue(shelves.first().songs.none { it.id == "fav" })
    }

    @Test fun noUpdateWhenEverySourceFailed() = runTest {
        var calls = 0
        HomeFeed.build(emptyList(), radio = { emptyList() }, youtube = { emptyList() }) { _, _ -> calls++ }
        assertEquals(0, calls)
    }
}
