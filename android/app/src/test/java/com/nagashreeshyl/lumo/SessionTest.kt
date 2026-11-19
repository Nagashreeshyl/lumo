package com.nagashreeshyl.lumo

import com.nagashreeshyl.lumo.auth.Session
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    @Test fun signedInNeedsSapisid() {
        assertTrue(Session.hasSession("PREF=x; SAPISID=abc/def; __Secure-3PAPISID=abc"))
        assertFalse(Session.hasSession("PREF=x; VISITOR_INFO1_LIVE=y"))
        assertFalse(Session.hasSession(null))
        assertFalse(Session.hasSession("SAPISID="))
    }
}
