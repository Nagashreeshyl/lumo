package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.audio.BeatAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class BeatAnalyzerTest {
    companion object {
        const val SR = 44_100.0
        fun clicks(bpm: Double, seconds: Double, offset: Double = 0.0, lead: Double = 0.0, tail: Double = 0.0): FloatArray {
            val total = ((lead + seconds + tail) * SR).toInt()
            val out = FloatArray(total)
            var t = lead + offset
            while (t < lead + seconds) {
                val start = (t * SR).toInt()
                for (i in 0 until (0.01 * SR).toInt()) if (start + i < total) out[start + i] = (0.8 * sin(2 * PI * 1000 * i / SR)).toFloat()
                t += 60 / bpm
            }
            return out
        }
        fun tone(seconds: Double, amp: Double) = FloatArray((seconds * SR).toInt()) { (amp * sin(2 * PI * 440 * it / SR)).toFloat() }
    }

    @Test fun detectsTempo() {
        for (bpm in listOf(90.0, 120.0, 174.0)) {
            val a = BeatAnalyzer.analyze(clicks(bpm, 30.0), SR)
            assertEquals("bpm $bpm", bpm, a.bpm, 1.0)
            assertTrue("conf ${a.confidence}", a.confidence >= 0.5)
        }
    }

    @Test fun detectsBeatPhase() {
        val a = BeatAnalyzer.analyze(clicks(120.0, 30.0, offset = 0.25), SR)
        assertEquals(0.25, a.beatOffset % 0.5, 0.03)
    }

    @Test fun detectsSilence() {
        val signal = FloatArray((2 * SR).toInt()) + tone(10.0, 0.5) + FloatArray((3 * SR).toInt())
        val a = BeatAnalyzer.analyze(signal, SR)
        assertEquals(2.0, a.leadingSilence, 0.05)
        assertEquals(12.0, a.audioEnd, 0.1)
        assertEquals(15.0, a.duration, 0.01)
    }

    @Test fun detectsTempoAtLowSampleRate() {
        val x = clicks(128.0, 30.0)
        val down = FloatArray((x.size - 3 + 3) / 4) { val j = it * 4; (x[j] + x[j + 1] + x[j + 2] + x[j + 3]) / 4 }
        assertEquals(128.0, BeatAnalyzer.analyze(down, SR / 4).bpm, 1.0)
    }

    @Test fun measuresLoudness() {
        val q = BeatAnalyzer.analyze(tone(10.0, 0.05), SR).loudness!!
        val l = BeatAnalyzer.analyze(tone(10.0, 0.5), SR).loudness!!
        assertEquals(20.0, l - q, 0.5)
    }

    @Test fun noiseIsLowConfidence() {
        val r = Random(7)
        val a = BeatAnalyzer.analyze(FloatArray((20 * SR).toInt()) { r.nextFloat() - 0.5f }, SR)
        assertTrue("conf ${a.confidence}", a.confidence < 0.3)
    }
}
