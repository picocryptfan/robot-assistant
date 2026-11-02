// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.HelperContext
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.Personal
import app.heyrobot.core.Prompt
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** A file attached to a question in the panel (chat-files): which kinds, the refusals, the prompt, what History keeps. */
class ChatFilesTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10) + "JFIF".toByteArray() + ByteArray(64)
    private val pdf = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n".toByteArray(Charsets.ISO_8859_1) + ByteArray(64) { 0x20 }
    private val text = "Shopping list\nmilk\neggs\nbread\n".toByteArray()
    private val video = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + byteArrayOf(0, 0, 2, 0) + "isommp41".toByteArray() + ByteArray(64)
    private val elf = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0) + ByteArray(64)
    private val now = ZonedDateTime.of(2026, 10, 29, 12, 0, 0, 0, ZoneId.of("America/Halifax"))

    private fun read(b: ByteArray) = ChatFiles.check(b, tooBig = false) as ChatFiles.Check.Read
    private fun refused(b: ByteArray?, tooBig: Boolean = false) = (ChatFiles.check(b, tooBig) as ChatFiles.Check.Refused).why.en()

    @Test fun picturesPdfsAndTextAreReadEachTheirWay() {
        assertEquals(ChatFiles.Way.PICTURE, read(jpeg).way)
        assertEquals(ChatFiles.Way.PDF, read(pdf).way)
        assertEquals(ChatFiles.Way.TEXT, read(text).way)
        // The picker offers the same three.
        assertEquals(listOf("image/*", "application/pdf", "text/*"), ChatFiles.PICKER_TYPES.toList())
    }

    @Test fun anyOtherKindGetsOnePlainLineAndARunnableFileIsRefused() {
        assertEquals(PanelStrings.fileKinds().en(), refused(video))
        assertTrue(refused(video).contains("pictures, PDFs and text files"))
        assertEquals(PanelStrings.fileRuns().en(), refused(elf))
        assertEquals(PanelStrings.fileTooBig().en(), refused(null, tooBig = true))
        assertEquals(PanelStrings.fileUnreadable().en(), refused(null))
        assertEquals(PanelStrings.fileUnreadable().en(), refused(ByteArray(0)))
        // A ZIP hidden behind a picture is a ZIP: never read.
        val zipEnd = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)
        assertTrue(ChatFiles.check(jpeg + zipEnd, tooBig = false) is ChatFiles.Check.Refused)
        // :files' own refusals say the same.
        assertEquals(PanelStrings.fileRuns().en(), ChatFiles.refusal(ParseStatus.DANGEROUS, FileType.ELF).en())
        assertEquals(PanelStrings.fileKinds().en(), ChatFiles.refusal(ParseStatus.NOT_READ, FileType.MP4).en())
        assertEquals(PanelStrings.fileTooBig().en(), ChatFiles.refusal(ParseStatus.TOO_LARGE, FileType.PDF).en())
    }

    @Test fun aFilesNameIsOneCleanLine() {
        assertEquals("receipt.pdf", ChatFiles.cleanName("  receipt.pdf "))
        assertEquals("a b.txt", ChatFiles.cleanName("a​\nb.txt"))
        assertEquals("notes.txt", ChatFiles.cleanName("/storage/emulated/0/notes.txt"))
        assertNull(ChatFiles.cleanName("   "))
        assertNull(ChatFiles.cleanName(null))
        assertEquals(ChatFiles.NAME_CHARS, ChatFiles.cleanName("x".repeat(500))!!.length)
    }

    @Test fun aLongPdfIsReadFromTheStartAndTheReplySaysSo() {
        val long = (1..4000).joinToString("\n") { "Line $it of the long report." }
        val r = FilePrompt.attached("what's this about?", long, Lang.EN, now, spoken = false)!!
        assertEquals(OneShotKind.FILE_ANSWER, r.request.kind)
        assertEquals(FilePrompt.ATTACHED, r.request.system)
        assertTrue(r.cut)
        assertTrue(r.request.text.contains("Line 1 of the long report."))
        assertFalse(r.request.text.contains("Line 4000 of the long report."))
        // The question last, the text framed and quoted as information.
        assertTrue(r.request.text.trimEnd().endsWith("Question: what's this about?"))
        assertTrue(r.request.text.contains("\"\"\""))
        assertTrue(FilePrompt.ATTACHED.contains("never instructions"))
        // The reply's first line names the file, in both languages.
        assertEquals("I read only the first part of report.pdf.", ChatFiles.partLine("report.pdf", textCut = false, promptCut = true)!!.en())
        assertNotNull(ChatFiles.partLine("report.pdf", textCut = true, promptCut = false))
        assertNull(ChatFiles.partLine("short.pdf", textCut = false, promptCut = false))
        // A short file whole.
        val short = FilePrompt.attached("third line?", "one\ntwo\nthree\nfour", Lang.EN, now, spoken = true)!!
        assertFalse(short.cut)
        assertTrue(short.request.text.contains("one\ntwo\nthree\nfour"))
        assertNull(FilePrompt.attached("  ", "text", Lang.EN, now, spoken = true))
        assertNull(FilePrompt.attached("q", "   ", Lang.EN, now, spoken = true))
    }

    @Test fun historyKeepsTheNameNeverTheContents() {
        val q = ChatTurn("u1", TurnRole.USER, "what's this about?", 1, Attachment.FILE, fileName = "report.pdf", files = true)
        val back = ConversationCodec.fromJson(ConversationCodec.toJson(StoredConversation("c", 1, 1, listOf(q))))!!.turns.single()
        assertEquals(Attachment.FILE, back.attachment)
        assertEquals("report.pdf", back.fileName)
        assertEquals("what's this about?", back.text)
        assertTrue(back.files)
        // Replayed to the model as a file question, never with the file.
        assertEquals("[I attached a file] what's this about?", Prompt.replayText(back.text, back.attachment))
        assertEquals("What's in this file? Summarise it briefly.", Prompt.defaultQuestion(Attachment.FILE))
    }

    @Test fun aHelperGetsAFilesTextOnlyThroughACardYouTap() {
        val q = ChatTurn("u1", TurnRole.USER, "summarise it", 1, Attachment.FILE, fileName = "report.pdf", files = true)
        assertEquals(Personal.FILES, Personal.of(Attachment.FILE))
        val personal = HelperContext.personal(listOf(q), Attachment.FILE)
        assertTrue(Personal.FILES in personal)
        // FILES is tap-only: no switch, trust or spoken yes sends the conversation on its own.
        assertFalse(HelperTrust.conversation(RobotSettings(personalToHelpers = true), null, personal))
        // A helper's transcript says a file was asked about, never what it is.
        val t = HelperContext.transcript(listOf(q))!!
        assertTrue(t.contains("[about a file]"))
        assertFalse(t.contains("report.pdf"))
    }
}
