package com.nagashreeshyl.lumo

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun appId() = assertEquals("com.nagashreeshyl.nive", BuildConfig.APPLICATION_ID)
}
