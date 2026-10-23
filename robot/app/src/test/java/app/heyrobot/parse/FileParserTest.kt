// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.parse

import app.heyrobot.core.parse.BlobKind
import app.heyrobot.core.parse.Box
import app.heyrobot.core.parse.Dim
import app.heyrobot.core.parse.FileParseWire
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseOp
import app.heyrobot.core.parse.ParseReply
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.parse.ParseResults
import app.heyrobot.core.parse.ParseStatus
import app.heyrobot.core.parse.ScanQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What :files does with a request (files-1.4-plan.md §4.3), with the platform's decoders faked: what isn't read never
 * reaches a decoder, a bomb is refused from its header, every output is capped, and every answer passes the link's own
 * checks (FileParseWire.decodeReply, then ParseResults.from on the copied bytes), as it would in FileParseLink.
 */
class FileParserTest {
    // ------------------------------------------------------------------ the fake platform

    /** A JPEG whose header says [d], [size] bytes long: what checkBlob reads (SOI, SOF0, SOS, filler, EOI). */
    private fun jpeg(d: Dim, size: Int = 600): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(b(0xFF, 0xD8, 0xFF, 0xC0, 0, 11, 8, d.height shr 8, d.height and 0xFF, d.width shr 8, d.width and 0xFF, 1, 1, 0x11, 0))
        o.write(b(0xFF, 0xDA, 0, 8, 1, 1, 0, 0, 0x3F, 0))
        o.write(ByteArray(maxOf(0, size - o.size() - 2)) { 0x55 })
        o.write(b(0xFF, 0xD9))
        return o.toByteArray()
    }

    private fun png(d: Dim): ByteArray = b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13) + "IHDR".toByteArray() +
        ByteBuffer.allocate(13).putInt(d.width).putInt(d.height).put(8).put(6).array() + ByteArray(20)

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private inner class FakePicture(override val width: Int, override val height: Int) : Picture {
        var closed = false

        init { opened += this }

        override fun scaled(box: Box): Picture = ParseLimits.fit(Dim(width, height), box).let { FakePicture(it.width, it.height) }
        override fun straightened(quad: ScanQuad, size: Dim): Picture = FakePicture(size.width, size.height)
        override fun jpeg(quality: Int, max: Int): ByteArray? = jpegSize(width, height, quality).let { n -> if (n > max) null else jpeg(Dim(width, height), n) }
        override fun png(max: Int): ByteArray? = png(Dim(width, height))
        override fun rgba(out: ByteBuffer) { out.put(ByteArray(width * height * 4) { 7 }) }
        override fun close() { closed = true }
    }

    /** How big the fake JPEGs come out (a thumbnail's ladder is tried until one fits). */
    private var jpegSize: (Int, Int, Int) -> Int = { _, _, _ -> 600 }
    private val opened = CopyOnWriteArrayList<FakePicture>()

    private inner class FakePdf(val count: Int, val size: (Int) -> Dim = { Dim(612, 792) }, val texts: (Int) -> String? = { "Page $it TOTAL 12.00" }) : PdfDoc {
        val rendered = mutableListOf<Pair<Int, Dim>>()
        val textRead = mutableListOf<Int>()
        var closed = false
        override val pageCount get() = count
        override fun pageSize(i: Int) = size(i)
        override fun render(i: Int, size: Dim): Picture { rendered += i to size; return FakePicture(size.width, size.height) }
        override fun text(i: Int): String? { textRead += i; return texts(i) }
        override fun close() { closed = true }
    }

    private inner class FakeDecoders : Decoders {
        val calls = CopyOnWriteArrayList<String>()
        var source: Dim? = null
        var pdf: FakePdf = FakePdf(1)
        var decodeAs: ((Dim, Box) -> Dim)? = null
        /** The decoder applies an orientation that turns the picture a quarter. */
        var turned = false
        var fail: Throwable? = null
        var encrypted = false

        override fun size(input: ByteBuffer): Dim? { calls += "size"; return source }
        override fun decode(input: ByteBuffer, box: Box): Picture {
            calls += "decode"
            fail?.let { throw it }
            val src = source ?: header(input) ?: Dim(4032, 3024)
            if (!ParseLimits.sourceOk(src)) throw TooLarge()
            val d = decodeAs?.invoke(src, box) ?: ParseLimits.fit(src, box)
            return FakePicture(d.width, d.height)
        }
        override fun decodePixels(input: ByteBuffer, maxPixels: Long): Picture {
            calls += "decodePixels"
            val d = ParseLimits.fitPixels(source ?: Dim(4032, 3024), maxPixels)
            return if (turned) FakePicture(d.height, d.width) else FakePicture(d.width, d.height)
        }
        override fun pdf(input: ByteBuffer): PdfDoc {
            calls += "pdf"
            if (encrypted) throw Encrypted()
            fail?.let { throw it }
            return pdf
        }
        override fun pdfWriter(): PdfWriter {
            calls += "pdfWriter"
            return object : PdfWriter {
                val pages = mutableListOf<Dim>()
                override fun page(picture: Picture, points: Dim) { pages += points }
                override fun finish(max: Int): ByteArray? = ("%PDF-1.7\n" + pages.joinToString { "$it" }).toByteArray()
                override fun close() {}
            }
        }
    }

    private val dec = FakeDecoders()

    /** What a real decoder would read: the input's own header. */
    private fun header(input: ByteBuffer): Dim? {
        val prefix = ByteArray(minOf(input.remaining(), FileSniff.PREFIX)).also { input.duplicate().get(it) }
        return app.heyrobot.core.parse.ImageHeader.dims(FileSniff.sniff(prefix, input.remaining().toLong()), input.duplicate())
    }

    /** [req] run over [input], then read as FileParseLink reads it: the frame, then each blob's bytes from the region. */
    private fun run(req: ParseRequest, vararg input: ByteArray): Pair<ParseReply?, ParseResult?> {
        val all = input.fold(ByteArray(0)) { a, x -> a + x }
        val out = ByteBuffer.allocate(req.outSize)
        val frame = FileParser(dec).run(req, ByteBuffer.wrap(all).asReadOnlyBuffer(), out)
        val reply = FileParseWire.decodeReply(frame, req) ?: return null to null
        val sent = FileSniff.sniff(input[0].copyOf(minOf(input[0].size, FileSniff.PREFIX)), input[0].size.toLong())
        val result = ParseResults.from(req, reply, sent) { x -> ByteArray(x.length).also { out.duplicate().also { d -> d.position(x.offset) }.get(it) } }
        return reply to result
    }

    private fun readReq(flags: Int, size: Int) = ParseRequest(ParseOp.READ, flags, parts = listOf(size))
    private val all = ParseRequest.TEXT or ParseRequest.PAGES or ParseRequest.THUMB or ParseRequest.DATE

    private fun ok(r: Pair<ParseReply?, ParseResult?>) = (r.second as? ParseResult.Ok ?: throw AssertionError("not ok: ${r.second}")).out
    private fun refused(r: Pair<ParseReply?, ParseResult?>) = (r.second as? ParseResult.Refused ?: throw AssertionError("not refused: ${r.second}")).status

    private fun allClosed() = assertTrue("every picture closed", opened.all { it.closed })

    // ------------------------------------------------------------------ hostile inputs

    /** A zero-byte file: refused as empty by the link before it is sent, by the wire if it were, and by the parser, never decoded. */
    @Test fun aZeroByteFileIsNeverDecoded() {
        val req = readReq(all, 0)
        assertEquals(ParseStatus.EMPTY, refused(run(req, ByteArray(0))))
        assertEquals(emptyList<String>(), dec.calls)
        assertEquals(ParseStatus.EMPTY, ParseResults.before(req, listOf(FileType.EMPTY))!!.status)
        assertTrue(runCatching { FileParseWire.encodeRequest(req) }.isFailure)
    }

    /** A few bytes that declare 100,000 by 100,000 pixels: refused from the header, no decoder called. */
    @Test fun aPngWithAHugeDeclaredSizeIsRefusedBeforeAnyDecoder() {
        val bomb = png(Dim(100_000, 100_000))
        for (req in listOf(readReq(all, bomb.size), ParseRequest(ParseOp.PIXELS, 0, parts = listOf(bomb.size)), ParseRequest(ParseOp.STRIP, parts = listOf(bomb.size)))) {
            assertEquals(req.op.name, ParseStatus.TOO_LARGE, refused(run(req, bomb)))
        }
        assertEquals(emptyList<String>(), dec.calls)
        // HEIC's size comes from the platform's header: the same refusal, still before decoding.
        dec.source = Dim(40_000, 40_000)
        val heic = ftyp("heic")
        assertEquals(ParseStatus.TOO_LARGE, refused(run(readReq(all, heic.size), heic)))
        assertEquals(listOf("size"), dec.calls)
    }

    private fun ftyp(brand: String) = ByteBuffer.allocate(32).putInt(16).put("ftyp".toByteArray()).put(brand.toByteArray()).putInt(0).putInt(16).put("mdat".toByteArray()).array()

    /** A PDF that claims a million pages, each with far more text than is ever kept: 3 rendered, 10 read, 64 KiB of text. */
    @Test fun aPdfBombWithManyPagesIsCapped() {
        val huge = "x".repeat(5 * ParseLimits.MAX_TEXT)
        dec.pdf = FakePdf(1_000_000, texts = { huge })
        val pdf = "%PDF-1.7\n".toByteArray()
        val (reply, result) = run(readReq(all, pdf.size), pdf)
        val out = (result as ParseResult.Ok).out
        assertEquals(ParseLimits.MAX_PAGE_COUNT, out.pages)
        assertTrue(out.has(ParseReply.PAGES_CUT) && out.has(ParseReply.TEXT_CUT) && out.has(ParseReply.HAS_TEXT))
        assertEquals(ParseLimits.MAX_TEXT, out.text!!.toByteArray().size)
        assertTrue(dec.pdf.textRead.size <= ParseLimits.TEXT_PAGES)
        assertEquals(ParseLimits.READ_PAGES, out.pageJpegs.size)
        assertEquals(listOf(0, 1, 2, 0), dec.pdf.rendered.map { it.first })
        assertNotNull(out.thumb)
        assertTrue(dec.pdf.closed)
        assertEquals(ParseLimits.READ_PAGES + 1, reply!!.blobs.size)
        allClosed()
    }

    /** Pages of absurd sizes: rendered within the box, never 0 px; a page of no size is refused. */
    @Test fun aPdfsPageSizesAreClampedIntoTheBox() {
        dec.pdf = FakePdf(3, size = { listOf(Dim(1, 14_400), Dim(14_400, 1), Dim(100_000, 100_000))[it] })
        val pdf = "%PDF-1.4\n".toByteArray()
        ok(run(readReq(ParseRequest.PAGES, pdf.size), pdf))
        for ((_, d) in dec.pdf.rendered) assertTrue("$d", ParseLimits.PAGE.holds(d) && d.short >= ParseLimits.MIN_EDGE)
        dec.pdf = FakePdf(1, size = { Dim(0, 792) })
        assertEquals(ParseStatus.MALFORMED, refused(run(readReq(ParseRequest.PAGES, pdf.size), pdf)))
        dec.pdf = FakePdf(0)
        assertEquals(ParseStatus.MALFORMED, refused(run(readReq(ParseRequest.PAGES, pdf.size), pdf)))
        allClosed()
    }

    /** A file whose name or sender says one thing and whose bytes say another: only the bytes count. */
    @Test fun aFileThatLiesAboutItsTypeIsWhatItsBytesSay() {
        // "receipt.pdf" that is a ZIP (or an APK): dangerous, never opened.
        val zip = b(0x50, 0x4B, 0x03, 0x04, 0x14, 0, 0, 0) + "receipt.pdf".toByteArray()
        assertEquals(ParseStatus.DANGEROUS, refused(run(readReq(all, zip.size), zip)))
        // An ELF, a script, an HTML page: dangerous too.
        for (bad in listOf(b(0x7F, 0x45, 0x4C, 0x46, 2, 1), "#!/bin/sh\nreboot\n".toByteArray(), "<html><script>x()</script>".toByteArray())) {
            assertEquals(ParseStatus.DANGEROUS, refused(run(readReq(all, bad.size), bad)))
        }
        // "photo.jpg" that starts like a JPEG and holds no frame: malformed, never decoded.
        val fake = b(0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0) + png(Dim(10, 10))
        assertEquals(ParseStatus.MALFORMED, refused(run(readReq(all, fake.size), fake)))
        // A GIF, a video, unknown bytes: kept, not read.
        for (kept in listOf("GIF89a".toByteArray() + ByteArray(20), ftyp("isom"), b(1, 2, 3, 0, 5))) {
            assertEquals(ParseStatus.NOT_READ, refused(run(readReq(all, kept.size), kept)))
        }
        assertEquals(emptyList<String>(), dec.calls)
        // The link refuses all of these before sending anything.
        for (t in listOf(FileType.ZIP, FileType.ELF, FileType.SCRIPT, FileType.HTML)) assertEquals(ParseStatus.DANGEROUS, ParseResults.before(readReq(all, 10), listOf(t))!!.status)
        assertEquals(ParseStatus.NOT_READ, ParseResults.before(readReq(all, 10), listOf(FileType.MP4))!!.status)
    }

    /** A decoder that hands back more than it was asked for, or throws, or runs out of memory: nothing of it goes out. */
    @Test fun aMisbehavingDecoderGivesNoOutput() {
        val j = jpeg(Dim(4032, 3024))
        dec.decodeAs = { _, _ -> Dim(5000, 5000) }
        assertEquals(ParseStatus.OUTPUT_TOO_LARGE, refused(run(readReq(all, j.size), j)))
        dec.decodeAs = null
        dec.fail = OutOfMemoryError()
        assertEquals(ParseStatus.FAILED, refused(run(readReq(all, j.size), j)))
        dec.fail = IllegalStateException()
        val pdf = "%PDF-1.4\n".toByteArray()
        assertEquals(ParseStatus.FAILED, refused(run(readReq(all, pdf.size), pdf)))
        dec.fail = null
        dec.encrypted = true
        assertEquals(ParseStatus.ENCRYPTED, refused(run(readReq(all, pdf.size), pdf)))
        allClosed()
    }

    // ------------------------------------------------------------------ each op's outputs

    @Test fun aPhotoIsReadAsAPageAThumbnailAndItsDateTaken() {
        val j = jpegWithDate(Dim(4032, 3024))
        val (_, result) = run(readReq(all, j.size), j)
        val out = (result as ParseResult.Ok).out
        assertEquals(FileType.JPEG, out.type)
        assertEquals(1, out.pages)
        assertEquals(4032 to 3024, out.width to out.height)
        assertEquals("2026-08-14", out.date)
        assertEquals(1, out.pageJpegs.size)
        assertEquals(Dim(1024, 768), app.heyrobot.core.parse.ImageHeader.jpeg(ByteBuffer.wrap(out.pageJpegs[0])))
        assertEquals(Dim(320, 240), app.heyrobot.core.parse.ImageHeader.jpeg(ByteBuffer.wrap(out.thumb!!)))
        assertNull(out.text)
        assertEquals(listOf("decode"), dec.calls)
        allClosed()
    }

    private fun jpegWithDate(d: Dim): ByteArray {
        val tiff = ByteBuffer.allocate(80).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        tiff.put(b(0x49, 0x49)).putShort(42).putInt(8).putShort(1)
        tiff.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(26).putInt(0)
        tiff.putShort(1).putShort(0x9003.toShort()).putShort(2).putInt(20).putInt(44).putInt(0)
        tiff.put("2026:08:14 09:30:00".toByteArray()).put(0)
        val exif = "Exif".toByteArray() + b(0, 0) + tiff.array()
        val seg = b(0xFF, 0xE1, (exif.size + 2) shr 8, (exif.size + 2) and 0xFF) + exif
        val j = jpeg(d)
        return j.copyOf(2) + seg + j.copyOfRange(2, j.size)
    }

    /** The thumbnail tries each quality until one fits 64 KiB; when none does there is none, and the read still answers. */
    @Test fun aThumbnailThatWontFitIsLeftOut() {
        val j = jpeg(Dim(2000, 1500))
        jpegSize = { w, _, q -> if (w <= 320 && q > 50) ParseLimits.MAX_THUMB_JPEG + 1 else 900 }
        assertNotNull(ok(run(readReq(ParseRequest.THUMB, j.size), j)).thumb)
        jpegSize = { w, _, _ -> if (w <= 320) ParseLimits.MAX_THUMB_JPEG + 1 else 900 }
        val out = ok(run(readReq(ParseRequest.THUMB or ParseRequest.PAGES, j.size), j))
        assertNull(out.thumb)
        assertEquals(1, out.pageJpegs.size)
        // A page JPEG past F4's 4 MiB isn't sent at all.
        jpegSize = { _, _, _ -> ParseLimits.MAX_PAGE_JPEG + 1 }
        assertEquals(ParseStatus.OUTPUT_TOO_LARGE, refused(run(readReq(ParseRequest.PAGES, j.size), j)))
        allClosed()
    }

    /** Text before pictures (§4.2): a PDF with a text layer can skip its pages; one without renders them. */
    @Test fun aPdfsTextLayerAndItsPages() {
        val pdf = "%PDF-1.7\n".toByteArray()
        val flags = ParseRequest.TEXT or ParseRequest.PAGES or ParseRequest.PAGES_IF_NO_TEXT
        dec.pdf = FakePdf(2, texts = { "Hydro-Québec\nTotal 142,00 $ page $it" })
        val withText = ok(run(readReq(flags, pdf.size), pdf))
        assertEquals("Hydro-Québec\nTotal 142,00 $ page 0\n\nHydro-Québec\nTotal 142,00 $ page 1", withText.text)
        assertEquals(0, withText.pageJpegs.size)
        dec.pdf = FakePdf(2, texts = { "  " })
        val scanned = ok(run(readReq(flags, pdf.size), pdf))
        assertNull(scanned.text)
        assertEquals(2, scanned.pageJpegs.size)
        // No text API on the platform (Android 14): flagged, pages read instead.
        dec.pdf = FakePdf(2, texts = { null })
        val old = ok(run(readReq(flags, pdf.size), pdf))
        assertTrue(old.has(ParseReply.NO_TEXT_API))
        assertEquals(2, old.pageJpegs.size)
        assertEquals(listOf(0), dec.pdf.textRead)
    }

    @Test fun aTextFileIsReadAsUtf8CutOnACharacter() {
        val t = "﻿Reçu Costco\nTOTAL 84,12 $\n".toByteArray()
        assertEquals("Reçu Costco\nTOTAL 84,12 $\n", ok(run(readReq(ParseRequest.TEXT, t.size), t)).text)
        val long = ("é".repeat(40_000)).toByteArray() + b(0xFF, 0xFE) + "tail".toByteArray()
        val out = ok(run(readReq(ParseRequest.TEXT, long.size), long))
        assertTrue(out.has(ParseReply.TEXT_CUT))
        assertTrue(out.text!!.toByteArray().size <= ParseLimits.MAX_TEXT)
        assertNotNull(FileParseWire.utf8(out.text!!.toByteArray()))
        assertEquals(emptyList<String>(), dec.calls)
    }

    /** Raw pixels to show (D19): exactly width × height × 4 bytes within the box; a page that doesn't exist is a bad request. */
    @Test fun pixelsForShowing() {
        val j = jpeg(Dim(4032, 3024))
        val page = ok(run(ParseRequest(ParseOp.PIXELS, 0, parts = listOf(j.size)), j)).pixels!!
        assertEquals(2133 to 1600, page.width to page.height)
        assertEquals(2133 * 1600 * 4, page.rgba.size)
        val thumb = ok(run(ParseRequest(ParseOp.PIXELS, ParseRequest.VIEW_THUMB, parts = listOf(j.size)), j)).pixels!!
        assertEquals(320 to 240, thumb.width to thumb.height)
        dec.pdf = FakePdf(4)
        val pdf = "%PDF-1.4\n".toByteArray()
        val p3 = ok(run(ParseRequest(ParseOp.PIXELS, 0, page = 3, parts = listOf(pdf.size)), pdf)).pixels!!
        assertEquals(1600 to 2071, p3.width to p3.height)
        assertEquals(3, dec.pdf.rendered.single().first)
        assertEquals(ParseStatus.BAD_REQUEST, refused(run(ParseRequest(ParseOp.PIXELS, 0, page = 4, parts = listOf(pdf.size)), pdf)))
        assertEquals(ParseStatus.BAD_REQUEST, refused(run(ParseRequest(ParseOp.PIXELS, 0, page = 1, parts = listOf(j.size)), j)))
        // A text file has no pixels.
        val t = "notes".toByteArray()
        assertEquals(ParseStatus.NOT_READ, refused(run(ParseRequest(ParseOp.PIXELS, 0, parts = listOf(t.size)), t)))
        allClosed()
    }

    /** A copy to send: re-encoded from the pixels at most 16 MP; a PNG stays a PNG; nothing else is stripped. */
    @Test fun aStrippedCopy() {
        dec.source = Dim(16_320, 12_240)
        val j = jpeg(Dim(16_320, 12_240))
        val copy = ok(run(ParseRequest(ParseOp.STRIP, parts = listOf(j.size)), j)).copy!!
        assertEquals(FileType.JPEG, FileSniff.sniff(copy, copy.size.toLong()))
        assertTrue(app.heyrobot.core.parse.ImageHeader.jpeg(ByteBuffer.wrap(copy))!!.pixels <= ParseLimits.COPY_PIXELS)
        dec.source = Dim(800, 600)
        val p = png(Dim(800, 600))
        val pc = ok(run(ParseRequest(ParseOp.STRIP, parts = listOf(p.size)), p)).copy!!
        assertEquals(FileType.PNG, FileSniff.sniff(pc, pc.size.toLong()))
        val pdf = "%PDF-1.4\n".toByteArray()
        assertEquals(ParseStatus.NOT_READ, refused(run(ParseRequest(ParseOp.STRIP, parts = listOf(pdf.size)), pdf)))
        // A photo whose orientation turns it (the decoder applies it): the copy is upright, the same size either way round.
        dec.source = Dim(16_320, 12_240)
        dec.turned = true
        val turned = ok(run(ParseRequest(ParseOp.STRIP, parts = listOf(j.size)), j)).copy!!
        val td = app.heyrobot.core.parse.ImageHeader.jpeg(ByteBuffer.wrap(turned))!!
        assertTrue(td.height > td.width && td.pixels <= ParseLimits.COPY_PIXELS)
        allClosed()
    }

    @Test fun aScannedPageAndItsPdf() {
        dec.source = Dim(3000, 4000)
        val photo = jpeg(Dim(3000, 4000))
        val quad = ScanQuad.of(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)!!
        val page = ok(run(ParseRequest(ParseOp.STRAIGHTEN, parts = listOf(photo.size), quad = quad), photo)).scan!!
        assertEquals(Dim(1800, 2400), app.heyrobot.core.parse.ImageHeader.jpeg(ByteBuffer.wrap(page)))
        val bowTie = ScanQuad.of(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f)!!
        assertEquals(ParseStatus.BAD_REQUEST, refused(run(ParseRequest(ParseOp.STRAIGHTEN, parts = listOf(photo.size), quad = bowTie), photo)))
        // Three pages into one PDF, 200 pixels an inch.
        dec.source = null
        val pages = List(3) { jpeg(Dim(1800, 2400)) }
        val req = ParseRequest(ParseOp.SCAN_PDF, parts = pages.map { it.size })
        val pdf = ok(run(req, *pages.toTypedArray())).pdf!!
        assertTrue(String(pdf).startsWith("%PDF-"))
        assertTrue(String(pdf).contains("648x864"))
        // A page that isn't a JPEG, or one larger than a straightened page can be: refused, no PDF begun.
        dec.calls.clear()
        val mixed = listOf(jpeg(Dim(100, 100)), png(Dim(10, 10)))
        assertEquals(ParseStatus.BAD_REQUEST, refused(run(ParseRequest(ParseOp.SCAN_PDF, parts = mixed.map { it.size }), *mixed.toTypedArray())))
        val big = listOf(jpeg(Dim(3000, 100)))
        assertEquals(ParseStatus.TOO_LARGE, refused(run(ParseRequest(ParseOp.SCAN_PDF, parts = big.map { it.size }), *big.toTypedArray())))
        assertEquals(emptyList<String>(), dec.calls)
        allClosed()
    }

    /** Every answer the parser writes passes the link's checks; a blob's bytes changed after the frame is refused whole. */
    @Test fun theLinksChecksCatchABlobThatIsntWhatItsFrameSays() {
        val j = jpeg(Dim(4032, 3024))
        val req = readReq(ParseRequest.PAGES or ParseRequest.THUMB, j.size)
        val out = ByteBuffer.allocate(req.outSize)
        val frame = FileParser(dec).run(req, ByteBuffer.wrap(j), out)
        val reply = FileParseWire.decodeReply(frame, req)!!
        val page = reply.blobs.first { it.kind == BlobKind.PAGE }
        // :files still holds the region mapped: it could change a byte after answering. The copy is what's checked.
        out.put(page.offset + 7, 0x7F)
        val result = ParseResults.from(req, reply, FileType.JPEG) { x -> ByteArray(x.length).also { out.duplicate().also { d -> d.position(x.offset) }.get(it) } }
        assertTrue(result is ParseResult.Failed)
        // And an answer about another type than the link sent.
        val other = ParseResults.from(req, reply, FileType.PNG) { x -> ByteArray(x.length) }
        assertTrue(other is ParseResult.Failed)
    }
}
