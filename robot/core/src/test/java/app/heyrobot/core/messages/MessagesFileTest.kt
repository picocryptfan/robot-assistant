// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** messages.json (Settings › Messages): the design's defaults, a round trip, and damage reading as the defaults. */
class MessagesFileTest {
    @Test fun theDefaultsAreTheDesignsAnswers() {
        val d = MessagesFile.fromJson(null)
        assertEquals(MessagesFile(), d)
        val s = d.settings
        assertFalse(s.on)
        assertTrue(s.allowlist.apps.isEmpty())
        assertTrue(s.allowlist.groups)
        assertEquals(KeepMessages.UNTIL_GONE, s.keep)
        assertEquals(ReplyRoute.SEND_AFTER_CARD, s.replyRoute)
        assertTrue(s.replyCard)
        assertFalse(s.voiceYesSends)
        assertEquals(AfterReading.LEAVE, s.afterReading)
        assertTrue(s.translate)
        assertTrue(s.offerTheirLanguage)
        assertEquals(MessagesLocked.OFF, s.locked)
        assertFalse(s.helpersNoCard)
        assertFalse(s.announce)
    }

    @Test fun everyChoiceRoundTrips() {
        val f = MessagesFile(
            MessageSettings(on = true, allowlist = MessageAllowlist(setOf(MessageApp.CHEOGRAM, MessageApp.SIGNAL), groups = false), keep = KeepMessages.ONE_HOUR,
                replyRoute = ReplyRoute.OPEN_CHAT, replyCard = false, voiceYesSends = true, afterReading = AfterReading.MARK_READ, translate = false,
                offerTheirLanguage = false, locked = MessagesLocked.WHO_MESSAGED, helpersNoCard = true, announce = true),
            noCard = setOf("p1", "p2"), replyLang = mapOf("p1" to Lang.FR),
        )
        assertEquals(f, MessagesFile.fromJson(f.toJson()))
        // Switches and People ids only: no message, no name.
        assertFalse(f.toJson().contains("Mom"))
    }

    @Test fun damageReadsAsDefaultsFieldByField() {
        assertEquals(MessagesFile(), MessagesFile.fromJson("{"))
        assertEquals(MessagesFile(), MessagesFile.fromJson(""))
        val odd = MessagesFile.fromJson("""{"on":"yes","apps":["CHEOGRAM","WHATSAPP",3],"keep":"FOREVER","afterReading":"MARK_READ","noCard":"p1","replyLang":{"p1":"DE","p2":"EN"}}""")
        assertFalse(odd.settings.on)
        assertEquals(setOf(MessageApp.CHEOGRAM), odd.settings.allowlist.apps)
        assertEquals(KeepMessages.UNTIL_GONE, odd.settings.keep)
        assertEquals(AfterReading.MARK_READ, odd.settings.afterReading)
        assertTrue(odd.noCard.isEmpty())
        assertEquals(mapOf("p2" to Lang.EN), odd.replyLang)
    }
}
