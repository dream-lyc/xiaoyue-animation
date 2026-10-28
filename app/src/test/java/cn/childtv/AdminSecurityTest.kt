package cn.childtv

import org.junit.Assert.*
import org.junit.Test

class AdminSecurityTest {
    @Test fun `password is stored as salted verifier and sessions expire`() {
        var now = 1000L
        val security = AdminSecurity({ now })
        val encoded = security.hashPassword("correct horse battery")
        assertFalse(encoded.contains("correct horse"))
        assertNull(security.login("wrong", encoded))
        val session = requireNotNull(security.login("correct horse battery", encoded))
        assertTrue(security.authorize(session.token, session.csrfToken, "http://192.168.1.2:8080", "192.168.1.2:8080", true))
        assertFalse(security.authorize(session.token, "bad", "http://192.168.1.2:8080", "192.168.1.2:8080", true))
        assertFalse(security.authorize(session.token, session.csrfToken, "http://evil.test", "192.168.1.2:8080", true))
        now += 3_600_001
        assertFalse(security.authorize(session.token, null, null, "192.168.1.2:8080", false))
    }
    @Test fun `three wrong attempts block even a correct password until cooldown`() {
        var now = 0L
        val security = AdminSecurity({ now })
        val hash = security.hashPassword("a long password")
        repeat(3) { assertNull(security.login("incorrect", hash)) }
        assertNull(security.login("a long password", hash))
        now = 30_001
        assertNotNull(security.login("a long password", hash))
    }
}
