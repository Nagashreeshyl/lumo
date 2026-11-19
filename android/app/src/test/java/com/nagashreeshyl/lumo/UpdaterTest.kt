package com.nagashreeshyl.lumo

import com.nagashreeshyl.lumo.data.Updater
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class UpdaterTest {
    @Test fun comparesNumerically() {
        assertTrue(Updater.isNewer("0.10.0", "0.9.1"))
        assertTrue(Updater.isNewer("1.0", "0.9.9"))
        assertTrue(Updater.isNewer("0.4.0", "0.0.0-dev"))
        assertFalse(Updater.isNewer("0.4.0", "0.4.0"))
        assertFalse(Updater.isNewer("0.3.9", "0.4.0"))
        assertFalse(Updater.isNewer("0.4", "0.4.0"))
    }
}
