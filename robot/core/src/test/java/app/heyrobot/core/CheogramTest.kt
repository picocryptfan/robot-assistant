package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Robot reads from Cheogram: the pick from its contact list. */
class CheogramTest {
    @Test fun aPickGivesTheNameTheAddressAndTheAccount() {
        // As ShortcutService.getShortcutIntent builds it: "xmpp:" + the bare address, the account's bare address as an extra.
        assertEquals(CheogramPick("Maman Martin", "+15145550101@cheogram.com", "me@jmp.chat"),
            CheogramLinks.pick(" Maman  Martin ", "xmpp:+15145550101@cheogram.com", "me@jmp.chat"))
        // No name: the address's local part. No account: Cheogram picks it.
        assertEquals(CheogramPick("bob", "bob@example.org", null), CheogramLinks.pick(null, "xmpp:bob@example.org", null))
        assertEquals(CheogramPick("Bob", "bob@example.org", null), CheogramLinks.pick("Bob", "xmpp:bob@example.org", "not an account"))
        // A group chat, another scheme, nothing usable: no pick.
        assertNull(CheogramLinks.pick("Friends", "xmpp:friends@conference.example.org?join", "me@example.org"))
        assertNull(CheogramLinks.pick("X", "sip:x@example.org", null))
        assertNull(CheogramLinks.pick("X", "xmpp:", null))
        assertNull(CheogramLinks.pick("X", null, null))
    }
}
