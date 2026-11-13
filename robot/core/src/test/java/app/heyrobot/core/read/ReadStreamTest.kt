// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReadStrings
import app.heyrobot.core.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P2: the panel says a reading sentence by sentence as it comes, in ReadAloud.spoken's order, and "read this" routes. */
class ReadStreamTest {
    private fun r(p: Phrase) = ReadAloud.Said.Robot(p)
    private fun w(t: String, l: String? = "en") = ReadAloud.Said.Words(t, l)

    @Test fun theKindFirstThenEachFinishedSentenceThenTheOffer() {
        val s = ReadStream(Lang.FR)
        assertEquals(emptyList<ReadAloud.Said>(), s.next("let"))
        assertEquals("", s.shown("let"))
        assertEquals(listOf(r(Phrase.M(ReadStrings.letter)), w("Dear Sam.")), s.next("letter en\nDear Sam. The "))
        assertEquals(emptyList<ReadAloud.Said>(), s.next("letter en\nDear Sam. The meeting is [unclear] at 3."))
        val all = "letter en\nDear Sam. The meeting is [unclear] at 3.\nSee you"
        assertEquals(listOf(w("The meeting is"), r(Phrase.M(ReadStrings.unclear)), w("at 3.")), s.next(all))
        assertEquals(listOf(w("See you"), r(Phrase.M(ReadStrings.offer))), s.next(all, done = true))
        assertEquals("Dear Sam. The meeting is [unclear] at 3.\nSee you", s.shown(all, done = true))
        assertEquals(emptyList<ReadAloud.Said>(), s.next(all, done = true))
    }

    @Test fun noWordsNoHeadAndYourOwnLanguage() {
        assertEquals(listOf(r(Phrase.M(ReadStrings.noWords))), ReadStream(Lang.EN).next("other en\n", done = true))
        assertEquals(listOf(w("Hello there. Bye", null)), ReadStream(Lang.EN).next("Hello there. Bye", done = true))
        assertEquals(listOf(r(Phrase.M(ReadStrings.sign)), w("Sortie.", "fr")), ReadStream(Lang.FR).next("sign fr\nSortie.", done = true))
    }

    @Test fun readThisAloneOpensTheCameraOrReadsThePhotoHeld() {
        assertEquals(ReadAloud.Ask.CAMERA, ReadAloud.ask("Read this", Attachment.NONE, false))
        assertEquals(ReadAloud.Ask.CAMERA, ReadAloud.ask("lis ça", Attachment.NONE, false))
        assertEquals(ReadAloud.Ask.PHOTO, ReadAloud.ask("read this please", Attachment.PHOTO, true))
        assertNull(ReadAloud.ask("read this", Attachment.PHOTO, false))
        assertNull(ReadAloud.ask("read this", Attachment.SCREEN, true))
        assertNull(ReadAloud.ask("read this", Attachment.FILE, true))
        assertNull(ReadAloud.ask("read this and tell me the total", Attachment.PHOTO, true))
        assertNull(ReadAloud.ask("read my messages", Attachment.NONE, false))
    }
}
