// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D13: the first call to a host asks; "Allow for this connection" is kept per connection and origin; a changed address asks again. */
class ConnConsentTest {
    private val ha = ConnFixtures.homeAssistant().copy(answers = emptyMap())
    private val origin = "https://home.tail1234.ts.net"

    @Test fun answersPerHost() {
        assertTrue(ConnConsent.asks(ha))
        val allowed = ConnConsent.answered(ha, origin, ConsentAnswer.ALLOW)
        assertFalse(ConnConsent.asks(allowed))
        // "Just this time" spares nothing; it is kept so Settings can show it.
        val once = ConnConsent.answered(ha, origin, ConsentAnswer.ONCE)
        assertTrue(ConnConsent.asks(once))
        assertEquals(mapOf(origin to ConsentAnswer.ONCE), once.answers)
        // Forget.
        assertTrue(ConnConsent.asks(ConnConsent.answered(allowed, origin, null)))
        // An answer for another host never counts and isn't kept.
        assertEquals(emptyMap<String, ConsentAnswer>(), ConnConsent.answered(ha, "https://evil.example.org", ConsentAnswer.ALLOW).answers)
    }

    @Test fun aChangedAddressAsksAgain() {
        val allowed = ConnConsent.answered(ha, origin, ConsentAnswer.ALLOW)
        for (moved in listOf("https://home.tail1234.ts.net:8443", "https://other.tail1234.ts.net", "http://192.168.1.5:8123")) {
            val c = ConnConsent.withAddress(allowed, ConnFixtures.url(moved))
            assertTrue(moved, ConnConsent.asks(c))
            assertEquals(emptyMap<String, ConsentAnswer>(), c.answers)
            assertNull(c.tested)
            assertNull(c.where)
        }
        // The same origin with another base path keeps the answer, but needs Test again.
        val path = ConnConsent.withAddress(allowed, ConnFixtures.url("https://home.tail1234.ts.net/ha"))
        assertFalse(ConnConsent.asks(path))
        assertNull(path.tested)
        // A stale answer in a file (a hand edit) never counts for the address it now has.
        val stale = ha.copy(answers = mapOf("https://old.example.org" to ConsentAnswer.ALLOW))
        assertTrue(ConnConsent.asks(stale))
        // A hand-off has no host: it always asks (C7 decides its own card).
        assertTrue(ConnConsent.asks(ConnFixtures.handOff()))
    }

    @Test fun fromSomeoneElsesBackup() {
        val c = ConnConsent.fromSomeoneElse(ConnFixtures.homeAssistant())
        assertTrue(ConnConsent.asks(c))
        assertFalse(c.spokenYes)
        assertNull(c.tested)
        assertNull(c.where)
        assertTrue(ConnCheck.valid(c))
    }

    @Test fun theCardsWords() {
        assertEquals("“Good night” wants to send to home.tail1234.ts.net (your Home Assistant): Turn off a light.",
            app.heyrobot.core.i18n.ConnStrings.firstUse("skill" to "Good night", "host" to ha.address!!.shownHost, "name" to ha.name,
                "what" to ha.ops[0].title).en())
    }
}
