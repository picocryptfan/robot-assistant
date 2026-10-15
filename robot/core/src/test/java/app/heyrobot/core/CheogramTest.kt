// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** What Robot reads from Cheogram: the pick from its contact list, a chat's shortcut id, a notification's sender. */
class CheogramTest {
    @Test fun aPickGivesTheNameTheAddressAndTheAccount() {
        // As ShortcutService.getShortcutIntent builds it: "xmpp:" + the bare address, the account's bare address as an extra.
        assertEquals(CheogramPick("Maman Martin", "+15145550101@cheogram.com", "me@example.net"),
            CheogramLinks.pick(" Maman  Martin ", "xmpp:+15145550101@cheogram.com", "me@example.net"))
        // No name: the address's local part. No account: Cheogram picks it.
        assertEquals(CheogramPick("bob", "bob@example.org", null), CheogramLinks.pick(null, "xmpp:bob@example.org", null))
        assertEquals(CheogramPick("Bob", "bob@example.org", null), CheogramLinks.pick("Bob", "xmpp:bob@example.org", "not an account"))
        // Accounts in lower case: Cheogram compares them exactly.
        assertEquals("me@example.net", CheogramLinks.pick("Bob", "xmpp:bob@example.org", "Me@EXAMPLE.net")?.account)
        // A group chat, another scheme, nothing usable: no pick.
        assertNull(CheogramLinks.pick("Friends", "xmpp:friends@conference.example.org?join", "me@example.org"))
        assertNull(CheogramLinks.pick("X", "sip:x@example.org", null))
        assertNull(CheogramLinks.pick("X", "xmpp:", null))
        assertNull(CheogramLinks.pick("X", null, null))
    }

    @Test fun aShortcutIdIsTheAccountAndTheContact() {
        assertEquals("me@example.net" to "+15145550101@cheogram.com", CheogramLinks.shortcut("me@example.net#+15145550101@cheogram.com"))
        assertNull(CheogramLinks.shortcut("me@example.net"))
        assertNull(CheogramLinks.shortcut("#a@b.org"))
        assertNull(CheogramLinks.shortcut(null))
    }

    @Test fun onlyA1to1ChatsSenderIsLearned() {
        val id = "me@example.net#zoe@example.org"
        val zoe = "Zoé" to "zoe@example.org"
        assertEquals(CheogramSender("Zoé", "zoe@example.org", "me@example.net"), CheogramNotices.sender(id, false, listOf(zoe)))
        assertEquals("me@example.net", CheogramNotices.sender("Me@EXAMPLE.net#zoe@example.org", false, listOf(zoe))?.account)
        // The newest name wins; messages from you (no key, or yours) don't count.
        assertEquals("Zoé L.", CheogramNotices.sender(id, false, listOf(zoe, null to null, "Zoé L." to "ZOE@example.org"))?.name)
        // A group chat, no shortcut (a silent channel), a sender other than the chat's contact: nothing.
        assertNull(CheogramNotices.sender(id, true, listOf(zoe)))
        assertNull(CheogramNotices.sender(null, false, listOf(zoe)))
        assertNull(CheogramNotices.sender(id, false, listOf("Mallory" to "mallory@example.org")))
        // A chat with yourself, or a name that is only the address or a number: nothing to learn.
        assertNull(CheogramNotices.sender("me@example.net#me@example.net", false, listOf("Me" to "me@example.net")))
        assertNull(CheogramNotices.sender(id, false, listOf("zoe" to "zoe@example.org")))
        assertNull(CheogramNotices.sender("me@example.net#+15145550101@cheogram.com", false, listOf("+1 514-555-0101" to "+15145550101@cheogram.com")))
        assertNull(CheogramNotices.sender(id, false, listOf(" " to "zoe@example.org")))
    }

    @Test fun onlyTheInstalledCheogramSignedAsFirstSeenTeachesNames() {
        // Review 0.4.0 #12: the Cheogram build Robot hands texts to, posting for itself.
        assertTrue(CheogramSigners.fromCheogram("com.cheogram.android", "com.cheogram.android", "com.cheogram.android"))
        assertTrue(CheogramSigners.fromCheogram("com.cheogram.android", null, "com.cheogram.android"))
        assertFalse(CheogramSigners.fromCheogram("com.cheogram.android.playstore", "com.cheogram.android.playstore", "com.cheogram.android"))
        assertFalse(CheogramSigners.fromCheogram("com.cheogram.android", "android", "com.cheogram.android"))
        assertFalse(CheogramSigners.fromCheogram("com.cheogram.android.playstore", null, null))
        // Its signer is kept at first sight; then only that signer (or one the system says follows from it) counts.
        val a = "ab".repeat(32)
        val b = "cd".repeat(32)
        assertEquals(CheogramSigners.Verdict.PIN, CheogramSigners.check(null, a, false))
        assertEquals(CheogramSigners.Verdict.REFUSE, CheogramSigners.check(null, null, false))
        assertEquals(CheogramSigners.Verdict.TRUST, CheogramSigners.check(a, a, false))
        assertEquals(CheogramSigners.Verdict.TRUST, CheogramSigners.check(a, b, true))
        assertEquals(CheogramSigners.Verdict.REFUSE, CheogramSigners.check(a, b, false))
        assertEquals(CheogramSigners.Verdict.REFUSE, CheogramSigners.check(a, null, false))
        // The pins file round-trips, and a damaged line is left out.
        val pins = mapOf("com.cheogram.android" to a, "com.cheogram.android.playstore" to b)
        assertEquals(pins, CheogramSigners.parse(CheogramSigners.write(pins)))
        assertEquals(mapOf("x" to a), CheogramSigners.parse("x ${a.uppercase()}\ny short\nnonsense\n"))
        assertEquals(emptyMap<String, String>(), CheogramSigners.parse(null))
        val bytes = ByteArray(32) { it.toByte() }
        assertTrue(bytes.contentEquals(CheogramSigners.unhex(CheogramSigners.hex(bytes))))
        assertNull(CheogramSigners.unhex("zz"))
    }

    @Test fun whatCameOfThePickIsToldApart() {
        // Review 0.4.0 #14: an answer that couldn't be read is its own outcome; a cancel and an answer lost on the way
        // both arrive as a cancel with nothing, so the log says either.
        val zoe = CheogramPick("Zoé", "zoe@example.org", "me@example.org")
        assertEquals(PickOutcome.PICKED, CheogramLinks.outcome(true, true, zoe))
        assertEquals(PickOutcome.CANCELLED, CheogramLinks.outcome(false, true, null))
        assertEquals(PickOutcome.UNREADABLE, CheogramLinks.outcome(true, false, null))
        assertEquals(PickOutcome.NO_ADDRESS, CheogramLinks.outcome(true, true, null))
        assertEquals(PickOutcome.NO_ADDRESS, CheogramLinks.outcome(true, true, zoe.copy(jid = "a?b@example.org")))
        assertEquals("Pick in Cheogram: nobody picked, or Cheogram's answer didn't arrive",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CHEOGRAM_PICK, PickOutcome.CANCELLED.id)))
        assertEquals("Pick in Cheogram: Cheogram's answer couldn't be read",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CHEOGRAM_PICK, PickOutcome.UNREADABLE.id)))
    }
}
