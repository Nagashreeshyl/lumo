package com.nagashreeshyl.nive

import com.nagashreeshyl.nive.audio.MixEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class MixEngineTest {
    @Test fun deckReadyReachesEngineCallbackOnce() {
        val a = FakeDeck("a"); val b = FakeDeck("b")
        val e = MixEngine(a, b)
        var ready = 0
        e.onReady = { ready++ }
        e.start(songs("x").first())
        a.listener!!.onReady()
        assertEquals(1, ready)
    }
}
