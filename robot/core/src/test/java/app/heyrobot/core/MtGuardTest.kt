// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The guards on every translation (docs/conversation-mode.md 4.2). */
class MtGuardTest {
    private val id: LangId by lazy { LangId.load(File(System.getProperty("user.dir") ?: ".", "src/main/assets/" + LangId.ASSET).inputStream()) }

    private fun check(src: String, out: String?, to: String) = MtGuard.check(src, out, to, id)

    @Test fun aTranslationInTheWrongLanguageFails() {
        assertEquals(MtVerdict.OK, check("Je vais être en retard de dix minutes.", "I'm going to be ten minutes late.", "en").verdict)
        // The engine gave the French back, or answered in French.
        assertEquals(MtVerdict.LANGUAGE, check("Je vais être en retard de dix minutes.", "Je vais être en retard de dix minutes.", "en").verdict)
        assertEquals(MtVerdict.LANGUAGE, check("Can you pick me up at the daycare?", "Can you pick me up at the daycare?", "fr").verdict)
        // Two words or fewer may be not sure ("OK", a name): kept.
        assertEquals(MtVerdict.OK, check("D'accord.", "OK.", "en").verdict)
        assertEquals("Jean-François Tremblay", check("Jean-François Tremblay", "Jean-François Tremblay", "en").text)
    }

    @Test fun theLengthMustBeAboutRight() {
        val src = "On se rejoint au café après le travail, vers cinq heures et demie?"
        assertEquals(MtVerdict.LENGTH, check(src, "Yes.", "en").verdict)
        assertEquals(MtVerdict.LENGTH, check(src, "Let's meet at the café after work, around half past five? " + "And then we can talk about everything else. ".repeat(4), "en").verdict)
        assertEquals(MtVerdict.OK, check(src, "Shall we meet at the café after work, around half past five?", "en").verdict)
        // A short line may change length a lot within the slack.
        assertEquals(MtVerdict.OK, check("OK.", "D'accord.", "fr").verdict)
    }

    @Test fun aRepeatedSentenceIsCollapsed() {
        val r = check("Oui.", "Yes. Yes.", "en")
        assertEquals(MtVerdict.REPEAT, r.verdict)
        assertEquals("Yes.", r.text)
        assertTrue(r.verdict.usable)
        assertEquals("No.", check("Non.", "No. No. No.", "en").text)
        // Two sentences said, two different ones written: left alone.
        assertEquals(MtVerdict.OK, check("Oui. Non.", "Yes. No.", "en").verdict)
        assertEquals("Yes! No!", MtGuard.collapseRepeats("Oui !", "Yes! No!"))
    }

    @Test fun aShortLinesFullStopComesOffForBergamotAndBack() {
        val p = MtGuard.prepare("Oui.", MtEngine.BERGAMOT)
        assertEquals(MtGuard.Prepared("Oui", true), p)
        assertEquals("Yes.", MtGuard.restore("Yes", p))
        assertEquals("Yes!", MtGuard.restore("Yes!", p))
        assertEquals(MtGuard.Prepared("Merci beaucoup", true), MtGuard.prepare("Merci beaucoup.", MtEngine.BERGAMOT))
        // Three words, a question, an ellipsis, or Gemma: left alone.
        assertFalse(MtGuard.prepare("Je suis là.", MtEngine.BERGAMOT).stop)
        assertFalse(MtGuard.prepare("Oui?", MtEngine.BERGAMOT).stop)
        assertFalse(MtGuard.prepare("Bon...", MtEngine.BERGAMOT).stop)
        assertFalse(MtGuard.prepare("Oui.", MtEngine.GEMMA).stop)
        assertEquals("Yes", MtGuard.restore(" Yes ", MtGuard.prepare("Oui", MtEngine.BERGAMOT)))
    }

    @Test fun labelsQuotesAndNotesComeOff() {
        assertEquals("See you tomorrow.", MtGuard.clean("Translation: See you tomorrow."))
        assertEquals("À demain.", MtGuard.clean("Traduction : « À demain. »"))
        assertEquals("See you tomorrow.", MtGuard.clean("“See you tomorrow.”"))
        assertEquals("See you tomorrow.", MtGuard.clean("\"See you tomorrow.\""))
        assertEquals("See you tomorrow.", MtGuard.clean("English: See you tomorrow.\n\nNote: \"à demain\" is informal."))
        assertEquals("It's raining hard.", MtGuard.clean("It's raining hard. (Literally: it's raining to drink standing up.)"))
        // Quotes inside the sentence stay.
        assertEquals("He said « oui » and « non ».", MtGuard.clean("He said « oui » and « non »."))
        val r = check("À demain.", "Translation: \"See you tomorrow.\"", "en")
        assertEquals(MtResult("See you tomorrow.", MtVerdict.OK), r)
    }

    @Test fun emptySlowAndRamblingAnswersFail() {
        assertEquals(MtResult(null, MtVerdict.EMPTY), check("Bonjour.", "", "en"))
        assertEquals(MtResult(null, MtVerdict.EMPTY), check("Bonjour.", null, "en"))
        assertEquals(MtResult(null, MtVerdict.EMPTY), check("Bonjour.", " \"\" ", "en"))
        assertEquals(MtResult(null, MtVerdict.TIMEOUT), MtGuard.check("Bonjour.", "Hello.", "en", id, timedOut = true))
        assertEquals(MtVerdict.LINES, check("Bonjour, ça va bien aujourd'hui?", "Hello,\nhow\nare\nyou?", "en").verdict)
        assertNull(check("Bonjour, ça va bien aujourd'hui?", "Hello,\nhow\nare\nyou?", "en").text)
    }

    @Test fun thePair() {
        assertEquals("en", LangPair.FR_EN.other("fr"))
        assertEquals("fr", LangPair.FR_EN.other("en"))
        assertNull(LangPair.FR_EN.other("de"))
        assertEquals(LangPair.FR_EN, LangPair.of("nope"))
    }
}
