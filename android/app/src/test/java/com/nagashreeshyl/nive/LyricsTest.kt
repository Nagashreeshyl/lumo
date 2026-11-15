package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.lyrics.LyricLine
import com.nagashreeshyl.nive.lyrics.LyricsParse
import com.nagashreeshyl.nive.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun parsesTimestampsAndSorts() {
        val lines = LyricsParse.parseLRC("[ar:Someone]\n[00:12.50]Second\n[00:01.00][00:20.00]Chorus\n[01:02.25]Last")
        assertEquals(listOf("Chorus", "Second", "Chorus", "Last"), lines.map { it.text })
        assertEquals(listOf(1.0, 12.5, 20.0, 62.25), lines.map { it.time })
    }

    @Test fun activeLineFollowsTime() {
        val lines = LyricsParse.parseLRC("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertNull(LyricsParse.activeIndex(lines, 0.0))
        assertEquals(1, LyricsParse.activeIndex(lines, 6.0))
        assertEquals(2, LyricsParse.activeIndex(lines, 100.0))
    }

    @Test fun cleansVideoSuffixes() {
        assertEquals("Blinding Lights", LyricsParse.cleanTitle("Blinding Lights (Official Video)"))
        assertEquals("Song", LyricsParse.cleanTitle("Song [Lyric Video]"))
        assertEquals("Song (feat. X)", LyricsParse.cleanTitle("Song (feat. X)"))
    }

    @Test fun baseTitleStripsArtistPrefixFeaturesAndBrackets() {
        assertEquals("I Feel It Coming", LyricsParse.baseTitle("The Weeknd - I Feel It Coming ft. Daft Punk (Official Video)", "The Weeknd"))
        assertEquals("Get Lucky", LyricsParse.baseTitle("Get Lucky (Official Audio) (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk"))
        assertEquals("Kesariya", LyricsParse.baseTitle("Kesariya | Brahmastra | Arijit Singh", "Arijit Singh"))
        assertEquals("Shape of You", LyricsParse.baseTitle("Shape of You", "Ed Sheeran"))
    }

    @Test fun detectsLyricsThatNeedRomanizing() {
        assertTrue(LyricsParse.needsRomanization(listOf(LyricLine(0, 1.0, "உன்னை நான்"))))
        assertFalse(LyricsParse.needsRomanization(listOf(LyricLine(0, 1.0, "I'm in love with the shape of you"))))
    }

    @Test fun parsesLyricsPlusResponse() {
        val json = """{"type":"Word","lyrics":[{"time":27395,"duration":1565,"text":"I been tryna call"},{"time":29100,"duration":900,"text":"I been on my own"}]}"""
        val lines = LyricsParse.parseLyricsPlus(json)
        assertEquals(listOf("I been tryna call", "I been on my own"), lines.map { it.text })
        assertEquals(27.395, lines.first().time!!, 1e-9)
        assertTrue(LyricsParse.parseLyricsPlus("""{"error":{"status":404}}""").isEmpty())
    }

    @Test fun lyricQueriesGoFromStrictToLoose() {
        val q = LyricsParse.queries(Song("x", "The Weeknd - Blinding Lights (Official Video)", "The Weeknd", null, 200))
        assertEquals(mapOf("track_name" to "Blinding Lights", "artist_name" to "The Weeknd"), q.first())
        assertTrue(q.contains(mapOf("q" to "Blinding Lights The Weeknd")))
        assertEquals(q.size, q.toSet().size)
    }
}
