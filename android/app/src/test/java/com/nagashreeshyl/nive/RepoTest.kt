package com.nagashreeshyl.nive

import com.music.innertube.models.Album
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import com.nagashreeshyl.nive.data.Repo
import com.nagashreeshyl.nive.data.toItem
import com.nagashreeshyl.nive.data.toSong
import com.nagashreeshyl.nive.model.Item
import com.nagashreeshyl.nive.model.Ref
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RepoTest {
    private val item = SongItem(id = "v1", title = "Song (Official Video)", artists = listOf(Artist("A", null), Artist("B", "x")),
        album = Album("Al", "MPRE1"), duration = 205, thumbnail = "https://lh3.googleusercontent.com/t=w60-h60")

    @Test fun toSongCleansTitleAndJoinsArtists() {
        val s = item.toSong()
        assertEquals("Song", s.title); assertEquals("A, B", s.artist); assertEquals(205, s.duration); assertEquals("v1", s.id)
    }

    @Test fun albumBecomesPageRef() {
        val ref = (AlbumItem("MPRE1", "OLAK", title = "Al", artists = listOf(Artist("A", null)), year = 2020, thumbnail = "t").toItem() as Item.PageItem).ref
        assertEquals(Ref.Kind.album, ref.kind); assertEquals("MPRE1", ref.id); assertEquals("A • 2020", ref.subtitle)
    }

    @Test fun failureMapsToEmpty() {
        val r: Result<List<Int>> = Result.failure(RuntimeException("boom"))
        assertEquals(emptyList<Int>(), Repo.orEmpty("x", r) { it })
    }

    @Test fun rankPutsTitlesStartingWithQueryFirst() {
        val ranked = Repo.rank(songs("bad", "ta da", "tamil", "x"), "ta")
        assertEquals(listOf("ta da", "tamil", "bad", "x"), ranked.map { it.id })
    }

    @Test fun liveSearchAndMix() = runBlocking {
        assumeTrue(System.getenv("NIVE_LIVE") == "1")
        val songs = Repo.search("blinding lights")
        assertTrue(songs.isNotEmpty())
        assertTrue(Repo.youtubeMix(songs.first().id).size > 5)
        assertTrue(Repo.homeSections().isNotEmpty())
        assertTrue(Repo.explore().isNotEmpty())
        assertTrue(Repo.searchPage("daft punk", null).items.isNotEmpty())
    }
}
