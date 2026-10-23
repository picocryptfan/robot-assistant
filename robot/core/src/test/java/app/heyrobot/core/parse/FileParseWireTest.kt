// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

/**
 * The hand-written frames between the app and :files: exactly one frame each way, every field within its cap. A frame
 * that is cut, too long, of another version, with an unknown code, a length past its cap or a blob outside the output
 * region fails closed (null), never an exception and never a partial result.
 */
class FileParseWireTest {
    private val read = ParseRequest(ParseOp.READ, ParseRequest.TEXT or ParseRequest.PAGES or ParseRequest.THUMB or ParseRequest.DATE, parts = listOf(5000))

    private fun page(i: Int, len: Int = 1000, w: Int = 768, h: Int = 1024, at: Int = i * 1000) = ParseBlob(BlobKind.PAGE, w, h, at, len)

    private fun okRead(text: String? = "TOTAL 84.12", blobs: List<ParseBlob> = listOf(page(0), page(1), ParseBlob(BlobKind.THUMB, 240, 320, 2000, 900))) =
        ParseReply(ParseOp.READ, ParseStatus.OK, FileType.PDF, ParseReply.HAS_TEXT, 12, 612, 792, 0, text, blobs)

    @Test fun eachRequestRoundTrips() {
        val reqs = listOf(
            read,
            ParseRequest(ParseOp.PIXELS, ParseRequest.VIEW_THUMB, parts = listOf(40_000)),
            ParseRequest(ParseOp.PIXELS, 0, page = 41, parts = listOf(1)),
            ParseRequest(ParseOp.STRIP, parts = listOf(ParseLimits.MAX_INPUT)),
            ParseRequest(ParseOp.STRAIGHTEN, parts = listOf(300_000), quad = ScanQuad.of(0.1f, 0.1f, 0.9f, 0.12f, 0.88f, 0.95f, 0.05f, 0.9f)),
            ParseRequest(ParseOp.SCAN_PDF, parts = List(ParseLimits.MAX_SCAN_PAGES) { 1000 + it }),
        )
        for (r in reqs) {
            val back = FileParseWire.decodeRequest(FileParseWire.encodeRequest(r), r.inputSize, r.outSize)
            assertNotNull(r.op.name, back)
            assertEquals(r.op, back!!.op)
            assertEquals(r.flags, back.flags)
            assertEquals(r.page, back.page)
            assertEquals(r.parts, back.parts)
            assertEquals(r.quad?.points?.toList(), back.quad?.points?.toList())
        }
    }

    @Test fun aRequestThatIsntExactlyOneFailsClosed() {
        val f = FileParseWire.encodeRequest(read)
        for (n in 0 until f.size) assertNull("cut at $n", FileParseWire.decodeRequest(f.copyOf(n), 5000, read.outSize))
        assertNull(FileParseWire.decodeRequest(f + 0, 5000, read.outSize))
        // Regions of other sizes than the frame says (the service maps them and passes their own sizes).
        assertNull(FileParseWire.decodeRequest(f, 4999, read.outSize))
        assertNull(FileParseWire.decodeRequest(f, 5000, read.outSize + 1))
        // Another version, an unknown op, a flag the op doesn't take, a page on anything but PIXELS.
        assertNull(FileParseWire.decodeRequest(f.copyOf().also { it[3] = 2 }, 5000, read.outSize))
        assertNull(FileParseWire.decodeRequest(f.copyOf().also { it[4] = 9 }, 5000, read.outSize))
        assertNull(FileParseWire.decodeRequest(f.copyOf().also { it[5] = 0x40 }, 5000, read.outSize))
        assertNull(FileParseWire.decodeRequest(f.copyOf().also { it[7] = 1 }, 5000, read.outSize))
        // Two parts on a READ, a part of zero bytes, an out size that isn't the op's cap.
        val two = ParseRequest(ParseOp.SCAN_PDF, parts = listOf(10, 20))
        val tf = FileParseWire.encodeRequest(two)
        assertNotNull(FileParseWire.decodeRequest(tf, 30, two.outSize))
        assertNull(FileParseWire.decodeRequest(tf.copyOf().also { it[4] = ParseOp.READ.code.toByte() }, 30, read.outSize))
        assertNull(FileParseWire.decodeRequest(tf.copyOf().also { it[9] = 0; it[10] = 0; it[11] = 0; it[12] = 0 }, 20, two.outSize))
        val wrongOut = f.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(FileParseWire.decodeRequest(wrongOut, 5000, read.outSize))
        // A corner outside the photo.
        val q = ParseRequest(ParseOp.STRAIGHTEN, parts = listOf(100), quad = ScanQuad(IntArray(8) { 500_000 }))
        val qf = FileParseWire.encodeRequest(q)
        assertNotNull(FileParseWire.decodeRequest(qf, 100, q.outSize))
        qf[13] = 0x7F
        assertNull(FileParseWire.decodeRequest(qf, 100, q.outSize))
        // More than 64 MiB in all.
        val big = ParseRequest(ParseOp.SCAN_PDF, parts = listOf(ParseLimits.MAX_INPUT, 1))
        assertTrue(runCatching { FileParseWire.encodeRequest(big) }.isFailure)
    }

    @Test fun eachReplyRoundTrips() {
        val r = okRead()
        val back = FileParseWire.decodeReply(FileParseWire.encodeReply(r), read)!!
        assertEquals(ParseStatus.OK, back.status)
        assertEquals(FileType.PDF, back.type)
        assertEquals(12, back.pages)
        assertEquals("TOTAL 84.12", back.text)
        assertEquals(r.blobs, back.blobs)
        val refusal = FileParseWire.decodeReply(FileParseWire.refusal(ParseOp.READ, ParseStatus.DANGEROUS, FileType.ZIP), read)!!
        assertEquals(ParseStatus.DANGEROUS, refusal.status)
        assertEquals(FileType.ZIP, refusal.type)
        val px = ParseRequest(ParseOp.PIXELS, ParseRequest.VIEW_THUMB, parts = listOf(100))
        val pr = ParseReply(ParseOp.PIXELS, ParseStatus.OK, FileType.JPEG, blobs = listOf(ParseBlob(BlobKind.PIXELS, 320, 240, 0, 320 * 240 * 4)))
        assertNotNull(FileParseWire.decodeReply(FileParseWire.encodeReply(pr), px))
    }

    @Test fun aReplyThatIsCutOrTooLongFailsClosed() {
        val f = FileParseWire.encodeReply(okRead())
        for (n in 0 until f.size) assertNull("cut at $n", FileParseWire.decodeReply(f.copyOf(n), read))
        assertNull(FileParseWire.decodeReply(f + 0, read))
        assertNull(FileParseWire.decodeReply(ByteArray(FileParseIpc.MAX_FRAME + 1), read))
    }

    @Test fun aReplyWithAnUnknownCodeOrAnotherOpFailsClosed() {
        val f = FileParseWire.encodeReply(okRead())
        assertNull(FileParseWire.decodeReply(f.copyOf().also { it[0] = 'X'.code.toByte() }, read))
        assertNull(FileParseWire.decodeReply(f.copyOf().also { it[3] = 2 }, read))
        assertNull(FileParseWire.decodeReply(f.copyOf().also { it[4] = ParseOp.PIXELS.code.toByte() }, read))
        assertNull(FileParseWire.decodeReply(f.copyOf().also { it[5] = 99 }, read))
        assertNull(FileParseWire.decodeReply(f.copyOf().also { it[6] = 199.toByte() }, read))
        assertNull(FileParseWire.decodeReply(f.copyOf().also { it[7] = 0x40 }, read))
    }

    @Test fun aReplysNumbersAreCapped() {
        fun with(pages: Int = 12, w: Int = 612, h: Int = 792, date: Int = 0) =
            FileParseWire.decodeReply(FileParseWire.encodeReply(ParseReply(ParseOp.READ, ParseStatus.OK, FileType.PDF, 0, pages, w, h, date)), read)
        assertNotNull(with())
        assertNotNull(with(pages = ParseLimits.MAX_PAGE_COUNT))
        assertNull(with(pages = ParseLimits.MAX_PAGE_COUNT + 1))
        assertNull(with(pages = -1))
        assertNull(with(w = ParseLimits.MAX_SOURCE_EDGE + 1))
        assertNull(with(h = -5))
        assertNotNull(with(date = 20260814))
        assertNull(with(date = 20260230))
        assertNull(with(date = 1))
    }

    @Test fun aReplysTextIsStrictUtf8WithinItsCap() {
        assertNotNull(FileParseWire.decodeReply(FileParseWire.encodeReply(okRead("é".repeat(ParseLimits.MAX_TEXT / 2))), read))
        assertTrue(runCatching { FileParseWire.encodeReply(okRead("a".repeat(ParseLimits.MAX_TEXT + 1))) }.isFailure)
        // Overlong, a lone continuation byte, a cut sequence, an encoded surrogate: refused.
        for (bad in listOf(byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0x80.toByte()), byteArrayOf(0x61, 0xE2.toByte(), 0x82.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))) {
            assertNull(FileParseWire.utf8(bad))
            val f = FileParseWire.encodeReply(okRead("x".repeat(bad.size)))
            val at = f.indexOfFirst { it == 'x'.code.toByte() }
            bad.copyInto(f, at)
            assertNull(FileParseWire.decodeReply(f, read))
        }
        // Text answers only a READ that asked for it, and only with HAS_TEXT.
        val noText = ParseRequest(ParseOp.READ, ParseRequest.PAGES, parts = listOf(5000))
        assertNull(FileParseWire.decodeReply(FileParseWire.encodeReply(okRead(blobs = listOf(page(0)))), noText))
        val unflagged = ParseReply(ParseOp.READ, ParseStatus.OK, FileType.PDF, 0, 1, text = "hi")
        assertNull(FileParseWire.decodeReply(FileParseWire.encodeReply(unflagged), read))
    }

    @Test fun aReplysBlobsArePackedInOrderWithinTheirCaps() {
        fun ok(blobs: List<ParseBlob>, req: ParseRequest = read) = FileParseWire.decodeReply(FileParseWire.encodeReply(okRead(blobs = blobs)), req) != null
        assertTrue(ok(listOf(page(0), page(1), page(2), ParseBlob(BlobKind.THUMB, 320, 200, 3000, 64 * 1024))))
        assertTrue(ok(emptyList()))
        // A gap, an overlap, not from the region's start, past the region's end.
        assertFalse(ok(listOf(page(0), page(1, at = 1001))))
        assertFalse(ok(listOf(page(0), page(1, at = 999))))
        assertFalse(ok(listOf(page(0, at = 1))))
        assertFalse(ok(listOf(page(0, len = ParseLimits.MAX_PAGE_JPEG), page(1, len = ParseLimits.MAX_PAGE_JPEG, at = ParseLimits.MAX_PAGE_JPEG),
            page(2, len = ParseLimits.MAX_PAGE_JPEG, at = 2 * ParseLimits.MAX_PAGE_JPEG), ParseBlob(BlobKind.THUMB, 10, 10, 3 * ParseLimits.MAX_PAGE_JPEG, ParseLimits.MAX_THUMB_JPEG + 1))))
        // Empty, over the kind's cap, too many, the thumbnail before a page, two thumbnails, the wrong kind for the op.
        assertFalse(ok(listOf(page(0, len = 0))))
        assertFalse(ok(listOf(page(0, len = ParseLimits.MAX_PAGE_JPEG + 1))))
        assertFalse(ok(listOf(page(0), page(1), page(2), page(3))))
        assertFalse(ok(listOf(ParseBlob(BlobKind.THUMB, 10, 10, 0, 100), page(0, at = 100))))
        assertFalse(ok(listOf(ParseBlob(BlobKind.THUMB, 10, 10, 0, 100), ParseBlob(BlobKind.THUMB, 10, 10, 100, 100))))
        assertFalse(ok(listOf(ParseBlob(BlobKind.PDF, 0, 0, 0, 100))))
        // A page or thumbnail larger than its box; a page JPEG nobody asked for.
        assertFalse(ok(listOf(page(0, w = 1025, h = 10))))
        assertFalse(ok(listOf(ParseBlob(BlobKind.THUMB, 321, 10, 0, 100))))
        assertFalse(ok(listOf(page(0, w = 0, h = 10))))
        assertFalse(ok(listOf(page(0)), ParseRequest(ParseOp.READ, ParseRequest.TEXT or ParseRequest.THUMB, parts = listOf(5000))))
        // A refusal carries nothing.
        val refusalWithBlob = ParseReply(ParseOp.READ, ParseStatus.FAILED, FileType.PDF, blobs = listOf(page(0)))
        assertNull(FileParseWire.decodeReply(FileParseWire.encodeReply(refusalWithBlob), read))
        val refusalWithText = ParseReply(ParseOp.READ, ParseStatus.FAILED, FileType.PDF, ParseReply.HAS_TEXT, text = "x")
        assertNull(FileParseWire.decodeReply(FileParseWire.encodeReply(refusalWithText), read))
    }

    @Test fun pixelsAreExactlyWidthTimesHeightTimesFourWithinTheirBox() {
        val req = ParseRequest(ParseOp.PIXELS, 0, parts = listOf(100))
        fun ok(w: Int, h: Int, len: Int = w * h * 4) = FileParseWire.decodeReply(FileParseWire.encodeReply(
            ParseReply(ParseOp.PIXELS, ParseStatus.OK, FileType.PDF, blobs = listOf(ParseBlob(BlobKind.PIXELS, w, h, 0, len)))), req) != null
        assertTrue(ok(1600, 2400))
        assertTrue(ok(2400, 1600))
        assertFalse(ok(1601, 2400))
        assertFalse(ok(2401, 100))
        assertFalse(ok(100, 100, len = 100 * 100 * 4 - 1))
        assertFalse(ok(100, 100, len = 100 * 100 * 4 + 4))
        // A thumbnail's box is 320 by 320.
        val thumb = ParseRequest(ParseOp.PIXELS, ParseRequest.VIEW_THUMB, parts = listOf(100))
        assertNull(FileParseWire.decodeReply(FileParseWire.encodeReply(
            ParseReply(ParseOp.PIXELS, ParseStatus.OK, FileType.JPEG, blobs = listOf(ParseBlob(BlobKind.PIXELS, 321, 100, 0, 321 * 400)))), thumb))
        // Exactly one PIXELS blob, nothing else.
        assertNull(FileParseWire.decodeReply(FileParseWire.encodeReply(ParseReply(ParseOp.PIXELS, ParseStatus.OK, FileType.JPEG)), req))
    }

    @Test fun aBlobsCopiedBytesAreWhatTheFrameSays() {
        val jpeg = ParseFixtures.jpeg(768, 1024)
        assertTrue(FileParseWire.checkBlob(ParseBlob(BlobKind.PAGE, 768, 1024, 0, jpeg.size), jpeg))
        assertFalse(FileParseWire.checkBlob(ParseBlob(BlobKind.PAGE, 1024, 768, 0, jpeg.size), jpeg))
        assertFalse(FileParseWire.checkBlob(ParseBlob(BlobKind.PAGE, 768, 1024, 0, jpeg.size + 1), jpeg))
        val png = ParseFixtures.png(30, 40)
        assertFalse(FileParseWire.checkBlob(ParseBlob(BlobKind.PAGE, 30, 40, 0, png.size), png))
        assertTrue(FileParseWire.checkBlob(ParseBlob(BlobKind.COPY, 30, 40, 0, png.size), png))
        assertFalse(FileParseWire.checkBlob(ParseBlob(BlobKind.COPY, 30, 41, 0, png.size), png))
        val gif = "GIF89a".toByteArray() + ByteArray(20)
        assertFalse(FileParseWire.checkBlob(ParseBlob(BlobKind.COPY, 1, 1, 0, gif.size), gif))
        val pdf = "%PDF-1.4\n".toByteArray()
        assertTrue(FileParseWire.checkBlob(ParseBlob(BlobKind.PDF, 0, 0, 0, pdf.size), pdf))
        assertFalse(FileParseWire.checkBlob(ParseBlob(BlobKind.PDF, 0, 0, 0, jpeg.size), jpeg))
        assertTrue(FileParseWire.checkBlob(ParseBlob(BlobKind.PIXELS, 2, 3, 0, 24), ByteArray(24)))
        assertFalse(FileParseWire.checkBlob(ParseBlob(BlobKind.PIXELS, 2, 3, 0, 24), ByteArray(23)))
    }

    /** Random damage to a valid reply: never an exception; anything accepted re-encodes to exactly the bytes read. */
    @Test fun damagedRepliesNeverThrow() {
        val r = Random(1404)
        val good = FileParseWire.encodeReply(okRead())
        repeat(20_000) {
            val f = good.copyOf(if (r.nextInt(4) == 0) r.nextInt(good.size + 8) else good.size)
            repeat(1 + r.nextInt(4)) { if (f.isNotEmpty()) f[r.nextInt(f.size)] = r.nextInt(256).toByte() }
            val back = FileParseWire.decodeReply(f, read)
            if (back != null) assertArrayEquals(f, FileParseWire.encodeReply(back))
        }
        repeat(5_000) {
            val f = ByteArray(r.nextInt(64)).also(r::nextBytes)
            FileParseWire.decodeRequest(f, r.nextInt(100_000).toLong(), r.nextInt())
        }
    }

    @Test fun textIsCutOnACharactersBoundary() {
        assertEquals("abc" to false, FileParseWire.cutUtf8("abc", 3))
        assertEquals("ab" to true, FileParseWire.cutUtf8("abc", 2))
        // é is two bytes, 🎉 four (a surrogate pair): never half of either.
        assertEquals("a" to true, FileParseWire.cutUtf8("aé", 2))
        assertEquals("aé" to false, FileParseWire.cutUtf8("aé", 3))
        assertEquals("a" to true, FileParseWire.cutUtf8("a🎉", 4))
        assertEquals("a🎉" to false, FileParseWire.cutUtf8("a🎉", 5))
        val (s, cut) = FileParseWire.cutUtf8("ç".repeat(40_000), ParseLimits.MAX_TEXT)
        assertTrue(cut)
        assertEquals(ParseLimits.MAX_TEXT, s.toByteArray().size)
        // A lone surrogate (as a PDF's text may hold) becomes a replacement, still valid UTF-8.
        val lone = FileParseWire.cutUtf8("a\uD800b", 100).first
        assertNotNull(FileParseWire.utf8(lone.toByteArray()))
    }

    @Test fun outputRegionsAreTheOpsCaps() {
        assertEquals(3 * 4 * 1024 * 1024 + 64 * 1024, ParseLimits.outCap(ParseOp.READ, 0))
        assertEquals(2400 * 1600 * 4, ParseLimits.outCap(ParseOp.PIXELS, 0))
        assertEquals(320 * 320 * 4, ParseLimits.outCap(ParseOp.PIXELS, ParseRequest.VIEW_THUMB))
        assertEquals(64 * 1024 * 1024, ParseLimits.outCap(ParseOp.STRIP, 0))
        assertEquals(ParseLimits.MAX_PAGE_JPEG, app.heyrobot.core.OneShotRules.MAX_IMAGE_BYTES)
        assertEquals(64 * 1024, ParseLimits.MAX_THUMB_JPEG)
        // The frame holds the text and its fields, under Binder's 1 MB.
        assertTrue(FileParseIpc.MAX_FRAME < 1024 * 1024 / 4)
        assertEquals(ByteBuffer.wrap(FileParseWire.encodeReply(okRead(null, emptyList()))).capacity(), 29)
    }
}
