package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.lyrics.LyricsRepo
import com.nagashreeshyl.nive.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepoTest {
    private val song = Song("v", "Blinding Lights (Official Video)", "The Weeknd", null, 200)
    private val plus = """{"lyrics":[{"time":1000,"text":"hello"}]}"""

    @Test fun secondLyricsPlusServerWhenFirstIsDown() = runTest {
        val asked = mutableListOf<String>()
        val repo = LyricsRepo { url -> asked += url; if (url.contains("binimum")) 500 to "" else 200 to plus }
        val lines = repo.fetch(song)
        assertEquals("hello", lines.single().text)
        assertTrue(asked[0].contains("binimum") && asked[1].contains("prjktla"))
        assertTrue(asked[0].contains("title=Blinding%20Lights") || asked[0].contains("title=Blinding+Lights"))
    }

    @Test fun notFoundOnLyricsPlusFallsBackToLrclibPickingClosestSyncedLength() = runTest {
        val lrclib = """[{"duration":260,"syncedLyrics":"[00:01.00]wrong"},{"duration":201,"syncedLyrics":"[00:02.00]right"},{"duration":200,"plainLyrics":"plain"}]"""
        val asked = mutableListOf<String>()
        val repo = LyricsRepo { url -> asked += url; if (url.contains("lyricsplus")) 200 to """{"error":{"status":404}}""" else 200 to lrclib }
        val lines = repo.fetch(song)
        assertEquals(listOf("right"), lines.map { it.text }); assertEquals(2.0, lines[0].time!!, 0.0)
        assertEquals(1, asked.count { it.contains("lyricsplus") })   // the mirror has the same sources
    }

    @Test fun plainLyricsWhenNothingSynced() = runTest {
        val repo = LyricsRepo { url -> if (url.contains("lyricsplus")) 404 to "" else 200 to """[{"duration":200,"plainLyrics":"a\nb"}]""" }
        val lines = repo.fetch(song)
        assertEquals(listOf("a", "b"), lines.map { it.text }); assertTrue(lines.all { it.time == null })
    }

    @Test fun manualSearchUsesFreeText() = runTest {
        val asked = mutableListOf<String>()
        val repo = LyricsRepo { url -> asked += url; 200 to """[{"duration":1,"syncedLyrics":"[00:01.00]x"}]""" }
        assertEquals("x", repo.search("my words").single().text)
        assertTrue(asked.single().contains("q=my"))
    }
}
