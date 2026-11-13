// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.files.FileId
import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.3 point and read (P3): a Files page goes to the panel as an id and a number; a text layer is read without the model. */
class FileReadTest {
    private val id = FileId.of("0123456789abcdef0123456789abcdef")!!

    @Test fun theArgumentIsAnIdAndAPageAndNothingElse() {
        assertEquals(FileRead.Page(id, 3), FileRead.parse(FileRead.arg(id, 3)))
        assertEquals("robot_", FileRead.ARG.take(6))
        for (bad in listOf(null, "", id.hex, "${id.hex}:", "${id.hex}:-1", "${id.hex}:12345", "${id.hex}:1:2", "../etc:1",
                "${id.hex.uppercase()}:1", "${id.hex}:1 ", "${id.hex}:١")) assertNull(bad, FileRead.parse(bad))
    }

    @Test fun aTextLayerIsReadAsItIs() {
        assertEquals("Pay by June 3.", FileRead.words("  Pay by June 3.\n", hasText = true, page = 0, pages = 1))
        assertEquals("Page two words", FileRead.words("one\u000CPage two words\u000C", hasText = true, page = 1, pages = 2))
        assertNull(FileRead.words("one\u000Ctwo", hasText = true, page = 5, pages = 2))
        // No text layer, or a scan's stray marks: the page's pixels go to the model.
        assertNull(FileRead.words("Pay by June 3.", hasText = false, page = 0, pages = 1))
        assertNull(FileRead.words(" .. 1 ", hasText = true, page = 0, pages = 1))
        assertNull(FileRead.words(null, hasText = true, page = 0, pages = 1))
    }

    // Review finding 3: :files joins a PDF's pages with blank lines, so page 7 read pages 1 to 10, as one utterance.
    @Test fun aPageOfSeveralIsThatPageOnly() {
        assertNull(FileRead.words("Page one words.\n\nPage two words.", hasText = true, page = 1, pages = 2))
        assertEquals("Page one words.", FileRead.words("Page one words.", hasText = true, page = 0, pages = 1))
    }

    @Test fun aTextLayerIsSaidSentenceBySentence() {
        assertEquals(listOf(ReadAloud.Said.Words("Pay by June 3.", null), ReadAloud.Said.Words("Questions?", null), ReadAloud.Said.Words("Call us", null)),
            FileRead.said("Pay by June 3. Questions?\nCall us", Lang.EN))
    }

    @Test fun theWordsAreSaidInYourVoiceWithNothingAdded() {
        assertEquals(listOf(ReadAloud.Said.Words("Pay by June 3.", null)), FileRead.said("Pay by June 3.", Lang.FR))
    }
}
