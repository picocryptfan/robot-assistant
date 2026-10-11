package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Robot reads from Cheogram: the pick from its contact list, a chat's shortcut id, a notification's sender. */
class CheogramTest {
    @Test fun aPickGivesTheNameTheAddressAndTheAccount() {
        // As ShortcutService.getShortcutIntent builds it: "xmpp:" + the bare address, the account's bare address as an extra.
        assertEquals(CheogramPick("Maman Martin", "+15145550101@cheogram.com", "me@jmp.chat"),
            CheogramLinks.pick(" Maman  Martin ", "xmpp:+15145550101@cheogram.com", "me@jmp.chat"))
        // No name: the address's local part. No account: Cheogram picks it.
        assertEquals(CheogramPick("bob", "bob@example.org", null), CheogramLinks.pick(null, "xmpp:bob@example.org", null))
        assertEquals(CheogramPick("Bob", "bob@example.org", null), CheogramLinks.pick("Bob", "xmpp:bob@example.org", "not an account"))
        // Accounts in lower case: Cheogram compares them exactly.
        assertEquals("me@jmp.chat", CheogramLinks.pick("Bob", "xmpp:bob@example.org", "Me@JMP.chat")?.account)
        // A group chat, another scheme, nothing usable: no pick.
        assertNull(CheogramLinks.pick("Friends", "xmpp:friends@conference.example.org?join", "me@example.org"))
        assertNull(CheogramLinks.pick("X", "sip:x@example.org", null))
        assertNull(CheogramLinks.pick("X", "xmpp:", null))
        assertNull(CheogramLinks.pick("X", null, null))
    }

    @Test fun aShortcutIdIsTheAccountAndTheContact() {
        assertEquals("me@jmp.chat" to "+15145550101@cheogram.com", CheogramLinks.shortcut("me@jmp.chat#+15145550101@cheogram.com"))
        assertNull(CheogramLinks.shortcut("me@jmp.chat"))
        assertNull(CheogramLinks.shortcut("#a@b.org"))
        assertNull(CheogramLinks.shortcut(null))
    }

    @Test fun onlyA1to1ChatsSenderIsLearned() {
        val id = "me@jmp.chat#zoe@example.org"
        val zoe = "Zoé" to "zoe@example.org"
        assertEquals(CheogramSender("Zoé", "zoe@example.org", "me@jmp.chat"), CheogramNotices.sender(id, false, listOf(zoe)))
        assertEquals("me@jmp.chat", CheogramNotices.sender("Me@JMP.chat#zoe@example.org", false, listOf(zoe))?.account)
        // The newest name wins; messages from you (no key, or yours) don't count.
        assertEquals("Zoé L.", CheogramNotices.sender(id, false, listOf(zoe, null to null, "Zoé L." to "ZOE@example.org"))?.name)
        // A group chat, no shortcut (a silent channel), a sender other than the chat's contact: nothing.
        assertNull(CheogramNotices.sender(id, true, listOf(zoe)))
        assertNull(CheogramNotices.sender(null, false, listOf(zoe)))
        assertNull(CheogramNotices.sender(id, false, listOf("Mallory" to "mallory@example.org")))
        // A chat with yourself, or a name that is only the address or a number: nothing to learn.
        assertNull(CheogramNotices.sender("me@jmp.chat#me@jmp.chat", false, listOf("Me" to "me@jmp.chat")))
        assertNull(CheogramNotices.sender(id, false, listOf("zoe" to "zoe@example.org")))
        assertNull(CheogramNotices.sender("me@jmp.chat#+15145550101@cheogram.com", false, listOf("+1 514-555-0101" to "+15145550101@cheogram.com")))
        assertNull(CheogramNotices.sender(id, false, listOf(" " to "zoe@example.org")))
    }
}
