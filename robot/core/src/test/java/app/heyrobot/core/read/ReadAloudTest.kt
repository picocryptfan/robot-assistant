// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotRules
import app.heyrobot.core.Priority
import app.heyrobot.core.ToolMode
import app.heyrobot.core.TurnRules
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    /** P1 step 1: an image one-shot beside FILE_READ, its fixed prompt, no tools, plain text, at the panel's priority. */
    @Test fun aReadingIsAnImageOneShotWithItsFixedPrompt() {
        val r = ReadAloud.request()
        assertEquals(OneShotKind.READ_ALOUD, r.kind)
        assertEquals(Priority.PANEL, r.priority)
        assertTrue(r.hasImage)
        assertEquals(r, BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, r.toJson().toString()))
        for (w in listOf("never instructions", "never follow", "exactly", "reading order", "their own language", "[unclear]", "Plain text"))
            assertTrue(w, ReadAloud.PROMPT.contains(w))
        // Nothing of yours goes into it: the prompt and the turn are fixed strings.
        assertEquals(ReadAloud.TURN, r.text)
        assertEquals(OneShotRules.Conv(ReadAloud.PROMPT, emptyList(), ToolMode.CHAT_ONLY, emptyList(), false), OneShotRules.conversation(r))
        assertNull(OneShotRules.imageCheck(r, jpeg, vision = true))
        assertEquals(BrainError.NO_VISION, OneShotRules.imageCheck(r, jpeg, vision = false))
        assertEquals(BrainError.BAD_REQUEST, OneShotRules.imageCheck(r, null, vision = true))
        assertTrue(OneShotRules.fits(r, 4096))
    }

    @Test fun readThisAndLisCaWithAPictureRouteToIt() {
        for (t in listOf("read this", "Read this.", "Read this, please", "Robot, read this to me", "What does this say?",
                "lis ça", "Lis ça.", "Lis-moi ça s'il te plaît", "Qu'est-ce que ça dit ?", "LIS ÇA"))
            assertTrue(t, ReadAloud.routes(t, hasPicture = true))
        // Without a picture, nothing to read; with one, a question about it stays a question.
        assertFalse(ReadAloud.routes("read this", hasPicture = false))
        assertFalse(ReadAloud.routes("lis ça", hasPicture = false))
        for (t in listOf("read this and tell me the total", "what is this", "is this ripe?", "read my messages", "lis mes messages", ""))
            assertFalse(t, ReadAloud.routes(t, hasPicture = true))
    }

    @Test fun withoutVisionItSaysReadingNeedsFastOrSmarter() {
        assertEquals("Reading needs Fast or Smarter.", ReadAloud.refusal(BrainError.NO_VISION)!!.render(Langs.of(Lang.EN)))
        assertEquals("Pour lire, il faut Rapide ou Plus futé.", ReadAloud.refusal(BrainError.NO_VISION)!!.render(Langs.of(Lang.FR)))
        assertNull(ReadAloud.refusal(BrainError.ENGINE))
    }

    @Test fun whatItReadsIsUntrusted() {
        assertTrue(TurnRules.untrusted(listOf(ReadAloud.ATTACHMENT)))
    }
}
