// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.read

import app.heyrobot.assistant.ask.SharedItem
import app.heyrobot.core.ask.SharedKind
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.read.ReadCommand
import app.heyrobot.core.read.ReadRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Read it to me's share door and Files' text (read-it-to-me-plan.md §1, step 6).
class PanelReadTest {
    /** AskChip's "Read it to me" says its request for you: ReadRouter hears it in both languages as the shared text's reading. */
    @Test fun theShareDoorsButtonIsHeardAsReadingTheSharedText() {
        for (lang in listOf(Lang.EN, Lang.FR)) {
            val said = HandsFreeStrings.readThisAsk().render(Langs(lang, lang, lang))
            assertEquals(said, ReadCommand.Shared, ReadRouter.route(said))
        }
    }

    /** Only a shared text is read: a photo or a voice message's words are not. */
    @Test fun onlyASharedTextIsRead() {
        val text = "The council voted on Tuesday. The new bridge opens in May."
        assertEquals(text, PanelRead.sharedText(SharedItem(SharedKind.TEXT, text)))
        assertNull(PanelRead.sharedText(SharedItem(SharedKind.VOICE, "call me back", seconds = 3)))
        assertNull(PanelRead.sharedText(SharedItem(SharedKind.PHOTO, jpeg = ByteArray(4))))
        assertNull(PanelRead.sharedText(null))
    }

    /** A PDF's text from :files with PAGE_BREAKS is read by page, losing its headers and page numbers; a text file is read as is. */
    @Test fun aPdfsPagesComeApartAtTheFormFeed() {
        val word = listOf("first", "second", "third")
        val pages = (1..3).map { "Lease agreement 2026\nThe tenant pays rent on the ${word[it - 1]} day of the month.\nKeys are returned at the end.\nPage $it of 3" }
        val pdf = PanelRead.fileText(FileType.PDF, pages.joinToString(ParseRequest.PAGE_BREAK.toString()))
        val said = pdf.pieces.joinToString(" ") { it.text }
        assertFalse(said, said.contains("Lease agreement"))
        assertFalse(said, said.contains("Page"))
        assertEquals(3, pdf.pieces.count { it.text.startsWith("The tenant pays rent") })
        val plain = PanelRead.fileText(FileType.TEXT, "Lease agreement 2026\nThe tenant pays rent.")
        assertTrue(plain.pieces.joinToString(" ") { it.text }.contains("Lease agreement"))
    }
}
