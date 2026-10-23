// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the link refuses before sending, and how it turns a checked reply into an answer (FileParseLink's last step). */
class ParseResultsTest {
    private val read = ParseRequest(ParseOp.READ, ParseRequest.PAGES or ParseRequest.DATE, parts = listOf(100))

    @Test fun refusedBeforeAnythingIsSent() {
        assertEquals(ParseStatus.EMPTY, ParseResults.before(ParseRequest(ParseOp.READ, parts = listOf(0)), listOf(FileType.EMPTY))!!.status)
        assertEquals(ParseStatus.TOO_LARGE, ParseResults.before(ParseRequest(ParseOp.READ, parts = listOf(ParseLimits.MAX_INPUT + 1)), listOf(FileType.PDF))!!.status)
        for (t in FileType.entries.filter { it.group == FileGroup.DANGEROUS }) assertEquals(t.name, ParseStatus.DANGEROUS, ParseResults.before(read, listOf(t))!!.status)
        for (t in listOf(FileType.GIF, FileType.MP4, FileType.MP3, FileType.UNKNOWN)) assertEquals(t.name, ParseStatus.NOT_READ, ParseResults.before(read, listOf(t))!!.status)
        assertNull(ParseResults.before(read, listOf(FileType.HEIC)))
        assertNull(ParseResults.before(read, listOf(FileType.TEXT)))
        // Pixels of a text file, a stripped PDF, a scanned PDF of anything but JPEGs.
        assertEquals(ParseStatus.NOT_READ, ParseResults.before(ParseRequest(ParseOp.PIXELS, parts = listOf(10)), listOf(FileType.TEXT))!!.status)
        assertEquals(ParseStatus.NOT_READ, ParseResults.before(ParseRequest(ParseOp.STRIP, parts = listOf(10)), listOf(FileType.PDF))!!.status)
        assertEquals(ParseStatus.BAD_REQUEST, ParseResults.before(ParseRequest(ParseOp.SCAN_PDF, parts = listOf(10, 10)), listOf(FileType.JPEG, FileType.PNG))!!.status)
        assertNull(ParseResults.before(ParseRequest(ParseOp.SCAN_PDF, parts = listOf(10, 10)), listOf(FileType.JPEG, FileType.JPEG)))
    }

    @Test fun aCheckedReplyBecomesAnAnswer() {
        val page = ParseFixtures.jpeg(1024, 768)
        val reply = ParseReply(ParseOp.READ, ParseStatus.OK, FileType.JPEG, 0, 1, 4032, 3024, 20260814, null, listOf(ParseBlob(BlobKind.PAGE, 1024, 768, 0, page.size)))
        val ok = ParseResults.from(read, reply, FileType.JPEG) { page } as ParseResult.Ok
        assertEquals("2026-08-14", ok.out.date)
        assertEquals(1, ok.out.pageJpegs.size)
        // :files saw another type than the link sent: no answer.
        assertTrue(ParseResults.from(read, reply, FileType.PNG) { page } is ParseResult.Failed)
        // The bytes copied aren't what the frame says: no answer, none of it used.
        assertTrue(ParseResults.from(read, reply, FileType.JPEG) { ParseFixtures.jpeg(1024, 769) } is ParseResult.Failed)
        // A refusal passes through with the type.
        val refusal = ParseResults.from(read, ParseReply(ParseOp.READ, ParseStatus.ENCRYPTED, FileType.JPEG), FileType.JPEG) { ByteArray(0) } as ParseResult.Refused
        assertEquals(ParseStatus.ENCRYPTED, refusal.status)
    }

    @Test fun aStrippedPngStaysAPngAndEverythingElseBecomesAJpeg() {
        val strip = ParseRequest(ParseOp.STRIP, parts = listOf(100))
        val png = ParseFixtures.png(40, 30)
        val jpeg = ParseFixtures.jpeg(40, 30)
        fun copy(sent: FileType, bytes: ByteArray) = ParseResults.from(strip,
            ParseReply(ParseOp.STRIP, ParseStatus.OK, sent, 0, 1, 40, 30, 0, null, listOf(ParseBlob(BlobKind.COPY, 40, 30, 0, bytes.size))), sent) { bytes }
        assertTrue(copy(FileType.PNG, png) is ParseResult.Ok)
        assertTrue(copy(FileType.PNG, jpeg) is ParseResult.Failed)
        assertTrue(copy(FileType.HEIC, jpeg) is ParseResult.Ok)
        assertTrue(copy(FileType.JPEG, png) is ParseResult.Failed)
    }
}
