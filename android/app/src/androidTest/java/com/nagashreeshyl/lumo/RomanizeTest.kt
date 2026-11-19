package com.nagashreeshyl.lumo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nagashreeshyl.lumo.lyrics.LyricsParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RomanizeTest {
    @Test fun romanizesTamilAndHindiToEnglishLetters() {
        val tamil = LyricsParse.romanize("வணக்கம்")
        assertTrue(tamil.isNotEmpty() && tamil.all { it.code < 128 })
        assertTrue(LyricsParse.romanize("मुझको इतना बताए कोई").all { it.code < 128 })
        assertEquals("Shape of you", LyricsParse.romanize("Shape of you"))
    }
}
