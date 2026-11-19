package com.nagashreeshyl.lumo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nagashreeshyl.lumo.audio.BeatAnalyzer
import com.nagashreeshyl.lumo.audio.PcmDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PcmDecoderTest {
    @Test fun decodesClickTrackAndFindsTempo() {
        val ctx = InstrumentationRegistry.getInstrumentation()
        val file = File(ctx.targetContext.cacheDir, "click120.m4a")
        ctx.context.assets.open("click120.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        val pcm = PcmDecoder.decode(file)!!
        assertTrue("rate ${pcm.sampleRate}", pcm.sampleRate in 10_000.0..12_000.0)
        assertEquals(20.0, pcm.samples.size / pcm.sampleRate, 0.3)
        val a = BeatAnalyzer.analyze(pcm.samples, pcm.sampleRate)
        assertEquals(120.0, a.bpm, 2.0)
        assertTrue("confidence ${a.confidence}", a.confidence >= 0.5)
    }
}
