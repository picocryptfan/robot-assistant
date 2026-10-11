package app.heyrobot.core.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSettingsTest {
    /** The design's defaults, with its open questions answered as recommended. */
    @Test fun defaults() {
        val d = MessageSettings()
        assertFalse(d.on)
        assertTrue(d.allowlist.apps.isEmpty())
        assertTrue(d.allowlist.groups)
        assertEquals(KeepMessages.UNTIL_GONE, d.keep)
        assertEquals(0L, d.keep.afterGoneMs)
        assertEquals(ReplyRoute.SEND_AFTER_CARD, d.replyRoute)
        assertTrue(d.replyCard)
        assertFalse(d.voiceYesSends)
        assertEquals(AfterReading.LEAVE, d.afterReading)
        assertTrue(d.translate && d.offerTheirLanguage)
        assertEquals(MessagesLocked.OFF, d.locked)
        assertFalse(d.helpersNoCard)
        assertFalse(d.announce)
    }

    @Test fun lockedPhone() {
        val d = MessageSettings(on = true)
        assertEquals(LockedMessages.READ, MessageAccess.whenLocked(false, d))
        assertEquals(LockedMessages.UNLOCK_FIRST, MessageAccess.whenLocked(true, d))
        assertEquals(LockedMessages.NAMES_ONLY, MessageAccess.whenLocked(true, d.copy(locked = MessagesLocked.WHO_MESSAGED)))
        assertEquals(LockedMessages.READ, MessageAccess.whenLocked(true, d.copy(locked = MessagesLocked.READ_AND_REPLY)))
    }

    @Test fun helpersGetMessagesOnlyThroughACard() {
        val d = MessageSettings(on = true)
        assertTrue(MessageAccess.helperNeedsCard(d, trusted = true))
        assertTrue(MessageAccess.helperNeedsCard(d.copy(helpersNoCard = true), trusted = false))
        assertFalse(MessageAccess.helperNeedsCard(d.copy(helpersNoCard = true), trusted = true))
    }
}
