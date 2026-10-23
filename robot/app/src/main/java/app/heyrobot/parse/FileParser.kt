// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.parse

import app.heyrobot.core.parse.BlobKind
import app.heyrobot.core.parse.Box
import app.heyrobot.core.parse.Dim
import app.heyrobot.core.parse.ExifDate
import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileParseWire
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ImageHeader
import app.heyrobot.core.parse.ParseBlob
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseOp
import app.heyrobot.core.parse.ParseReply
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.parse.ParseStatus
import app.heyrobot.core.parse.ScanGeometry
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// :FILES PROCESS ONLY. What :files does with one request (files-1.4-plan.md §3.2, §4.3, D19): sniff the input's type from
// its first bytes, refuse what isn't read (empty, dangerous, kept-not-read, past a cap) before any decoder sees it, read
// an image's declared size from its header and refuse a bomb, then decode, render or write within every cap of
// core ParseLimits, and put each output into the caller's output region. The platform calls are behind [Decoders].
// Nothing here logs, names a file or a key, or reaches the network; FileParseLink checks every output again.

internal class FileParser(private val decoders: Decoders) {
    /** A refusal from deep inside a step. */
    private class Refuse(val status: ParseStatus) : Exception()

    /** The output region, filled from its start: each blob packed after the last. */
    private class Out(val buf: ByteBuffer) {
        val blobs = mutableListOf<ParseBlob>()

        fun put(kind: BlobKind, d: Dim, bytes: ByteArray) {
            if (bytes.size > buf.remaining()) throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
            blobs += ParseBlob(kind, d.width, d.height, buf.position(), bytes.size)
            buf.put(bytes)
        }

        fun putPixels(p: Picture) {
            val n = p.width.toLong() * p.height * 4
            if (n > buf.remaining()) throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
            val at = buf.position()
            val dst = buf.slice()
            p.rgba(dst)
            if (dst.position().toLong() != n) throw Refuse(ParseStatus.FAILED)
            buf.position(at + n.toInt())
            blobs += ParseBlob(BlobKind.PIXELS, p.width, p.height, at, n.toInt())
        }
    }

    /**
     * The reply frame to [req], its blobs written into [output] (writable, the request's out size). [input]: the input
     * region, read-only, of [req]'s input size; its parts follow one another.
     */
    fun run(req: ParseRequest, input: ByteBuffer, output: ByteBuffer): ByteArray {
        val parts = split(input, req.parts)
        val type = sniff(parts[0])
        val out = Out(output.duplicate().also { it.position(0) })
        return try {
            // FX2 (files-1.4-review.md finding 14): what the first 4 KiB can't tell, from the whole input (a ZIP appended to a
            // JPEG or a PDF, markup deep in a text): never decoded. The reply keeps the first bytes' type, the one the link sent.
            for (p in parts) if (FileSniff.hiddenIn(p, sniff(p)) != null) throw Refuse(ParseStatus.DANGEROUS)
            val reply = when (req.op) {
                ParseOp.READ -> read(req, parts[0], type, out)
                ParseOp.PIXELS -> pixels(req, parts[0], type, out)
                ParseOp.STRIP -> strip(parts[0], type, out)
                ParseOp.STRAIGHTEN -> straighten(req, parts[0], type, out)
                ParseOp.SCAN_PDF -> scanPdf(parts, type, out)
            }
            FileParseWire.encodeReply(reply)
        } catch (r: Refuse) {
            FileParseWire.refusal(req.op, r.status, type)
        } catch (_: TooLarge) {
            FileParseWire.refusal(req.op, ParseStatus.TOO_LARGE, type)
        } catch (_: Encrypted) {
            FileParseWire.refusal(req.op, ParseStatus.ENCRYPTED, type)
        } catch (_: Throwable) {
            // The platform's decoder or renderer gave up (or ran out of memory): nothing of it goes out.
            FileParseWire.refusal(req.op, ParseStatus.FAILED, type)
        }
    }

    private fun split(input: ByteBuffer, sizes: List<Int>): List<ByteBuffer> {
        var at = 0
        return sizes.map { n ->
            val b = input.duplicate()
            b.position(at).limit(at + n)
            at += n
            b.slice().asReadOnlyBuffer()
        }
    }

    private fun sniff(b: ByteBuffer): FileType {
        val prefix = ByteArray(minOf(FileSniff.PREFIX, b.limit()))
        b.duplicate().get(prefix)
        return FileSniff.sniff(prefix, b.limit().toLong())
    }

    /** Only what is read gets past here, and only within the input cap. */
    private fun gate(type: FileType, b: ByteBuffer, allowed: Set<FileGroup>) {
        when (type.group) {
            FileGroup.EMPTY -> throw Refuse(ParseStatus.EMPTY)
            FileGroup.DANGEROUS -> throw Refuse(ParseStatus.DANGEROUS)
            else -> if (type.group !in allowed) throw Refuse(ParseStatus.NOT_READ)
        }
        if (b.limit() > ParseLimits.MAX_INPUT) throw Refuse(ParseStatus.TOO_LARGE)
    }

    /** An image's declared size, from its header (core's reader, or the platform's for HEIC), refused past the caps. */
    private fun declared(type: FileType, b: ByteBuffer): Dim {
        val d = when (type) {
            FileType.HEIC, FileType.HEIF -> decoders.size(b.duplicate())
            else -> ImageHeader.dims(type, b.duplicate())
        } ?: throw Refuse(ParseStatus.MALFORMED)
        if (!ParseLimits.sourceOk(d)) throw Refuse(ParseStatus.TOO_LARGE)
        return d
    }

    /** Decoded to fit [box]; a decoder that hands back more than asked is refused. */
    private fun decode(b: ByteBuffer, box: Box): Picture {
        val p = decoders.decode(b.duplicate(), box)
        if (!box.holds(Dim(p.width, p.height))) {
            p.close()
            throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
        }
        return p
    }

    private fun read(req: ParseRequest, b: ByteBuffer, type: FileType, out: Out): ParseReply {
        gate(type, b, setOf(FileGroup.IMAGE, FileGroup.PDF, FileGroup.TEXT))
        return when (type.group) {
            FileGroup.IMAGE -> readImage(req, b, type, out)
            FileGroup.PDF -> readPdf(req, b, type, out)
            else -> readText(req, b, type)
        }
    }

    private fun readImage(req: ParseRequest, b: ByteBuffer, type: FileType, out: Out): ParseReply {
        val src = declared(type, b)
        val date = if (req.has(ParseRequest.DATE)) ExifDate.read(type, b.duplicate()) else 0
        val wantPage = req.has(ParseRequest.PAGES)
        val wantThumb = req.has(ParseRequest.THUMB)
        if (wantPage || wantThumb) {
            decode(b, if (wantPage) ParseLimits.PAGE else ParseLimits.THUMB).use { p ->
                if (wantPage) pageJpeg(p, out)
                if (wantThumb) thumb(p, out)
            }
        }
        return ParseReply(ParseOp.READ, ParseStatus.OK, type, 0, 1, src.width, src.height, date, null, out.blobs)
    }

    private fun readPdf(req: ParseRequest, b: ByteBuffer, type: FileType, out: Out): ParseReply {
        decoders.pdf(b.duplicate()).use { pdf ->
            val count = pdf.pageCount
            if (count <= 0) throw Refuse(ParseStatus.MALFORMED)
            var flags = if (count > ParseLimits.MAX_PAGE_COUNT) ParseReply.PAGES_CUT else 0
            var text: String? = null
            if (req.has(ParseRequest.TEXT)) {
                val sb = StringBuilder()
                var api = true
                for (i in 0 until minOf(count, ParseLimits.TEXT_PAGES)) {
                    val t = pdf.text(i)
                    if (t == null) { api = false; break }
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    // Never more of a page than the cap: a page's text may be far larger than anything kept.
                    sb.append(t, 0, minOf(t.length, ParseLimits.MAX_TEXT))
                    if (sb.length > ParseLimits.MAX_TEXT) break
                }
                if (!api) flags = flags or ParseReply.NO_TEXT_API
                if (sb.isNotBlank()) {
                    val (s, cut) = FileParseWire.cutUtf8(sb.toString(), ParseLimits.MAX_TEXT)
                    text = s
                    flags = flags or ParseReply.HAS_TEXT
                    if (cut || count > ParseLimits.TEXT_PAGES) flags = flags or ParseReply.TEXT_CUT
                }
            }
            val first = pdf.pageSize(0)
            val pages = req.has(ParseRequest.PAGES) && !(req.has(ParseRequest.PAGES_IF_NO_TEXT) && text != null)
            if (pages) {
                for (i in 0 until minOf(count, ParseLimits.READ_PAGES)) {
                    val size = pageDim(pdf, i, ParseLimits.PAGE)
                    pdf.render(i, size).use { pageJpeg(it, out) }
                }
            }
            if (req.has(ParseRequest.THUMB)) pdf.render(0, pageDim(pdf, 0, ParseLimits.THUMB)).use { thumb(it, out) }
            return ParseReply(ParseOp.READ, ParseStatus.OK, type, flags, minOf(count, ParseLimits.MAX_PAGE_COUNT),
                first.width.coerceIn(0, ParseLimits.MAX_SOURCE_EDGE), first.height.coerceIn(0, ParseLimits.MAX_SOURCE_EDGE), 0, text, out.blobs)
        }
    }

    /** UTF-8 text: its first MAX_TEXT bytes, malformed sequences replaced, its BOM dropped, cut on a character. */
    private fun readText(req: ParseRequest, b: ByteBuffer, type: FileType): ParseReply {
        if (!req.has(ParseRequest.TEXT)) return ParseReply(ParseOp.READ, ParseStatus.OK, type, 0, 1)
        val take = b.duplicate()
        take.limit(minOf(take.limit(), ParseLimits.MAX_TEXT))
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(take).toString().removePrefix("﻿")
        val (s, cut) = FileParseWire.cutUtf8(decoded, ParseLimits.MAX_TEXT)
        val more = b.limit() > ParseLimits.MAX_TEXT
        var flags = if (cut || more) ParseReply.TEXT_CUT else 0
        val text = s.takeIf { it.isNotEmpty() }
        if (text != null) flags = flags or ParseReply.HAS_TEXT else flags = 0
        return ParseReply(ParseOp.READ, ParseStatus.OK, type, flags, 1, 0, 0, 0, text)
    }

    /** A page's size for [box]: its points scaled to fill the box (vectors), an absurd aspect clamped. */
    private fun pageDim(pdf: PdfDoc, i: Int, box: Box): Dim {
        val pt = pdf.pageSize(i)
        if (pt.width < 1 || pt.height < 1) throw Refuse(ParseStatus.MALFORMED)
        return ParseLimits.fit(pt, box, up = true)
    }

    private fun pageJpeg(p: Picture, out: Out) {
        val jpeg = p.jpeg(ParseLimits.PAGE_QUALITY, ParseLimits.MAX_PAGE_JPEG) ?: throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
        out.put(BlobKind.PAGE, Dim(p.width, p.height), jpeg)
    }

    /** The thumbnail: 320 px, the first quality that fits 64 KiB; none when even the lowest doesn't. */
    private fun thumb(p: Picture, out: Out) {
        val small = if (ParseLimits.THUMB.holds(Dim(p.width, p.height))) null else p.scaled(ParseLimits.THUMB)
        try {
            val t = small ?: p
            if (!ParseLimits.THUMB.holds(Dim(t.width, t.height))) throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
            for (q in ParseLimits.THUMB_QUALITIES) {
                val jpeg = t.jpeg(q, ParseLimits.MAX_THUMB_JPEG) ?: continue
                out.put(BlobKind.THUMB, Dim(t.width, t.height), jpeg)
                return
            }
        } finally {
            small?.close()
        }
    }

    private fun pixels(req: ParseRequest, b: ByteBuffer, type: FileType, out: Out): ParseReply {
        gate(type, b, setOf(FileGroup.IMAGE, FileGroup.PDF))
        val box = if (req.has(ParseRequest.VIEW_THUMB)) ParseLimits.VIEW_THUMB else ParseLimits.VIEW_PAGE
        if (type.group == FileGroup.IMAGE) {
            if (req.page != 0) throw Refuse(ParseStatus.BAD_REQUEST)
            val src = declared(type, b)
            decode(b, box).use { out.putPixels(it) }
            return ParseReply(ParseOp.PIXELS, ParseStatus.OK, type, 0, 1, src.width, src.height, 0, null, out.blobs)
        }
        decoders.pdf(b.duplicate()).use { pdf ->
            val count = pdf.pageCount
            if (count <= 0) throw Refuse(ParseStatus.MALFORMED)
            if (req.page >= count) throw Refuse(ParseStatus.BAD_REQUEST)
            val pt = pdf.pageSize(req.page)
            pdf.render(req.page, pageDim(pdf, req.page, box)).use { p ->
                if (!box.holds(Dim(p.width, p.height))) throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
                out.putPixels(p)
            }
            return ParseReply(ParseOp.PIXELS, ParseStatus.OK, type, if (count > ParseLimits.MAX_PAGE_COUNT) ParseReply.PAGES_CUT else 0,
                minOf(count, ParseLimits.MAX_PAGE_COUNT), pt.width.coerceIn(0, ParseLimits.MAX_SOURCE_EDGE),
                pt.height.coerceIn(0, ParseLimits.MAX_SOURCE_EDGE), 0, null, out.blobs)
        }
    }

    /** A copy for sending: re-encoded from the pixels (PNG stays PNG, the rest becomes JPEG), at most 16 MP, no metadata at all. */
    private fun strip(b: ByteBuffer, type: FileType, out: Out): ParseReply {
        gate(type, b, setOf(FileGroup.IMAGE))
        val src = declared(type, b)
        val want = ParseLimits.fitPixels(src, ParseLimits.COPY_PIXELS)
        decoders.decodePixels(b.duplicate(), ParseLimits.COPY_PIXELS).use { p ->
            val d = Dim(p.width, p.height)
            // Either way round: the decoder applies the photo's orientation.
            if (d.pixels > ParseLimits.COPY_PIXELS || d.long > want.long + 1 || d.short > want.short + 1) throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
            val bytes = (if (type == FileType.PNG) p.png(ParseLimits.MAX_COPY) else p.jpeg(ParseLimits.COPY_QUALITY, ParseLimits.MAX_COPY))
                ?: throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
            out.put(BlobKind.COPY, d, bytes)
        }
        return ParseReply(ParseOp.STRIP, ParseStatus.OK, type, 0, 1, src.width, src.height, 0, null, out.blobs)
    }

    /** The camera's photo straightened from the four corners you set (§3.2) to a page JPEG of at most 2,400 px. */
    private fun straighten(req: ParseRequest, b: ByteBuffer, type: FileType, out: Out): ParseReply {
        gate(type, b, setOf(FileGroup.IMAGE))
        val quad = req.quad ?: throw Refuse(ParseStatus.BAD_REQUEST)
        val src = declared(type, b)
        decode(b, ParseLimits.SCAN_SOURCE).use { photo ->
            val size = ScanGeometry.page(quad, Dim(photo.width, photo.height), ParseLimits.SCAN_PAGE) ?: throw Refuse(ParseStatus.BAD_REQUEST)
            photo.straightened(quad, size).use { page ->
                if (page.width != size.width || page.height != size.height) throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
                val jpeg = page.jpeg(ParseLimits.SCAN_QUALITY, ParseLimits.MAX_SCAN_JPEG) ?: throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
                out.put(BlobKind.SCAN, size, jpeg)
            }
        }
        return ParseReply(ParseOp.STRAIGHTEN, ParseStatus.OK, type, 0, 1, src.width, src.height, 0, null, out.blobs)
    }

    /** Straightened pages (JPEGs, at most 2,400 px each) into one PDF, a page each at 200 pixels an inch. */
    private fun scanPdf(parts: List<ByteBuffer>, type: FileType, out: Out): ParseReply {
        if (parts.size > ParseLimits.MAX_SCAN_PAGES) throw Refuse(ParseStatus.BAD_REQUEST)
        for (p in parts) {
            if (sniff(p) != FileType.JPEG) throw Refuse(ParseStatus.BAD_REQUEST)
            if (p.limit() > ParseLimits.MAX_SCAN_JPEG) throw Refuse(ParseStatus.TOO_LARGE)
            val d = declared(FileType.JPEG, p)
            if (!ParseLimits.SCAN_PAGE.holds(d)) throw Refuse(ParseStatus.TOO_LARGE)
        }
        decoders.pdfWriter().use { w ->
            for (p in parts) decode(p, ParseLimits.SCAN_PAGE).use { pic -> w.page(pic, ParseLimits.scanPoints(Dim(pic.width, pic.height))) }
            val pdf = w.finish(ParseLimits.MAX_PDF) ?: throw Refuse(ParseStatus.OUTPUT_TOO_LARGE)
            out.put(BlobKind.PDF, Dim(0, 0), pdf)
        }
        return ParseReply(ParseOp.SCAN_PDF, ParseStatus.OK, type, 0, parts.size, 0, 0, 0, null, out.blobs)
    }
}
