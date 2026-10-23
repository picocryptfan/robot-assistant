// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// Pure. The Binder interface between Robot's app (FileParseLink, in main and :voice) and the isolated reader, :files
// (FileParseService), files-1.4-plan.md §4.3. Written by hand, no AIDL: a code per call and two frames encoded and read
// only here, every length explicit and capped, read strictly. Nothing that crosses names a file, a key or a setting.
//
// PARSE carries: the request frame (bytes); the input, a read-only SharedMemory made by the link holding the file's
// decrypted bytes (at most 64 MiB; several parts for a scanned PDF); and the output, a SharedMemory the link makes at the
// request's cap (ParseLimits.outCap), which :files writes into. The reply is one frame (bytes): its blobs are ranges of
// the output region, packed from its start. :files never sends a descriptor, so it can't hand back a region of its own
// size or kind. The link copies each blob out of the region and checks the copy (:files still holds the region mapped).
//
//   request  "RFQ" 1 | op u8 | flags u8 | page u16 | parts u8 | part lengths u32… | quad: 8 × u32 (STRAIGHTEN only) | out u32
//   reply    "RFR" 1 | op u8 | status u8 | type u8 | flags u8 | pages u32 | width u32 | height u32 | date u32
//            | text length u32, text (UTF-8) | blobs u8 | each: kind u8, width u32, height u32, offset u32, length u32
//
// Big-endian, no padding, nothing after the last field. A frame that is cut, too long, of another version or op, with an
// unknown code, a count or length past its cap, or a blob outside the region is refused whole: decode gives null.

/** The Binder side: the service's name (bound by name, never by class), its descriptors and its calls. */
object FileParseIpc {
    const val SERVICE_CLASS = "app.heyrobot.parse.FileParseService"  // l10n: not text (a class name)
    const val DESCRIPTOR = "app.heyrobot.parse.IFileParse"  // l10n: not text (a Binder descriptor)
    const val CALLBACK = "app.heyrobot.parse.IFileParseCallback"  // l10n: not text (a Binder descriptor)

    // The app -> :files (IBinder.FIRST_CALL_TRANSACTION is 1).
    /** In: the link's callback binder. :files calls its CB_HELLO (its pid) from its own uid; out: accepted, and a problem or null. */
    const val HELLO = 1
    /**
     * In: the request frame, the input region, the output region. Out: 1 and the reply frame; or 0 and nothing, from a
     * service that isn't isolated (it touched neither region).
     */
    const val PARSE = 2
    /** One way: the process ends itself, whatever it is doing. */
    const val EXIT = 3

    // :files -> the link (the callback binder the link passes in HELLO).
    /** In: its pid. Out: 1 when the link accepts it (its calling uid is an isolated one). */
    const val CB_HELLO = 1

    /** HELLO's problem: the service isn't in an isolated process (then it touches no file). */
    const val NOT_ISOLATED = "not_isolated"  // l10n: not text (a code)

    /** The largest frame either way: the text and a few dozen bytes of fields. */
    const val MAX_FRAME = ParseLimits.MAX_TEXT + 1024
}

enum class ParseOp(val code: Int) {
    /** For filing: the type, page count, a PDF's text layer or a text file's text, page JPEGs for :brain, a thumbnail, the date taken. */
    READ(1),
    /** For showing (D19): raw pixels of an image or a PDF page, so no decoder runs outside :files. */
    PIXELS(2),
    /** For sending: a copy re-encoded from the pixels, no metadata at all (orientation applied to the pixels). */
    STRIP(3),
    /** Scanning: the camera's photo straightened from four corners to a page JPEG. */
    STRAIGHTEN(4),
    /** Scanning: page JPEGs (STRAIGHTEN's) into one PDF. */
    SCAN_PDF(5);

    companion object {
        fun of(code: Int): ParseOp? = entries.firstOrNull { it.code == code }
    }
}

/** :files' answer to a request. Anything but [OK] carries no output. */
enum class ParseStatus(val code: Int) {
    OK(0),
    EMPTY(1),
    /** A type that could install or run something: never decoded. */
    DANGEROUS(2),
    /** A type Robot keeps but doesn't read (video, audio, GIF…), or an output this type has none of. */
    NOT_READ(3),
    /** Past a cap: the input, or the size an image's header declares. */
    TOO_LARGE(4),
    /** Its header or structure can't be read. */
    MALFORMED(5),
    /** The platform's decoder or renderer failed on it. */
    FAILED(6),
    /** A PDF with a password. */
    ENCRYPTED(7),
    BAD_REQUEST(9),
    /** An output would pass its cap. */
    OUTPUT_TOO_LARGE(10);

    companion object {
        fun of(code: Int): ParseStatus? = entries.firstOrNull { it.code == code }
    }
}

/** A reply's outputs, by kind; each kind's caps are checked where the frame is read and again on the copied bytes. */
enum class BlobKind(val code: Int) {
    /** A page's JPEG for :brain (READ): at most [ParseLimits.READ_PAGES], 1,024 px, F4's 4 MiB. */
    PAGE(1),
    /** The thumbnail JPEG (READ): 320 px, 64 KiB. */
    THUMB(2),
    /** Raw pixels (PIXELS): RGBA, 4 bytes each, as Bitmap.Config.ARGB_8888 stores them. */
    PIXELS(3),
    /** The stripped copy (STRIP): a JPEG, or a PNG for a PNG. */
    COPY(4),
    /** A straightened page (STRAIGHTEN): a JPEG of at most 2,400 px. */
    SCAN(5),
    /** The scanned PDF (SCAN_PDF). */
    PDF(6);

    companion object {
        fun of(code: Int): BlobKind? = entries.firstOrNull { it.code == code }
    }
}

/** One request. [parts]: the input's parts' lengths (one, but a SCAN_PDF's pages); [quad]: STRAIGHTEN's corners. */
class ParseRequest(
    val op: ParseOp,
    val flags: Int = 0,
    val page: Int = 0,
    val parts: List<Int>,
    val quad: ScanQuad? = null,
) {
    /** The output region's size: always the op's cap, so :files can't choose it. */
    val outSize: Int get() = ParseLimits.outCap(op, flags)
    val inputSize: Long get() = parts.sumOf { it.toLong() }

    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        // READ's flags.
        const val TEXT = 1
        const val PAGES = 2
        const val THUMB = 4
        const val DATE = 8
        /** With PAGES: render a PDF's pages only when it has no text layer (§4.2: text before pictures). */
        const val PAGES_IF_NO_TEXT = 16
        // PIXELS' flag: a thumbnail's box (320 by 320), else a page's (1,600 by 2,400).
        const val VIEW_THUMB = 1

        /** The flags each op may carry. */
        fun allowed(op: ParseOp): Int = when (op) {
            ParseOp.READ -> TEXT or PAGES or THUMB or DATE or PAGES_IF_NO_TEXT
            ParseOp.PIXELS -> VIEW_THUMB
            else -> 0
        }
    }
}

/** A blob of a reply: [length] bytes at [offset] of the output region, an image's [width] and [height] (0 for a PDF). */
data class ParseBlob(val kind: BlobKind, val width: Int, val height: Int, val offset: Int, val length: Int)

/** A reply. [date]: yyyymmdd or 0; [text]: null when there is none; [width], [height]: the source's size, as :files read it. */
class ParseReply(
    val op: ParseOp,
    val status: ParseStatus,
    val type: FileType,
    val flags: Int = 0,
    val pages: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val date: Int = 0,
    val text: String? = null,
    val blobs: List<ParseBlob> = emptyList(),
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        /** The text came from a PDF's text layer, or a text file. */
        const val HAS_TEXT = 1
        /** The text was cut at [ParseLimits.MAX_TEXT], or after [ParseLimits.TEXT_PAGES] pages. */
        const val TEXT_CUT = 2
        /** The page count passed [ParseLimits.MAX_PAGE_COUNT] (it is reported as that). */
        const val PAGES_CUT = 4
        /** The platform has no text API for PDFs (minSdk 34 without PdfRenderer's): pages only. */
        const val NO_TEXT_API = 8
        const val ALL_FLAGS = HAS_TEXT or TEXT_CUT or PAGES_CUT or NO_TEXT_API
    }
}

/** STRAIGHTEN's four corners, in millionths of the photo's width and height, clockwise from the top left. */
class ScanQuad(val points: IntArray) {
    init {
        require(points.size == 8)
    }

    /** Corner [i]'s x and y in a [w] by [h] image. */
    fun x(i: Int, w: Int): Float = points[2 * i] / ONE.toFloat() * w
    fun y(i: Int, h: Int): Float = points[2 * i + 1] / ONE.toFloat() * h

    companion object {
        const val ONE = 1_000_000

        /** Corners as fractions of the width and height (0 to 1), rounded to millionths; null when one is outside. */
        fun of(vararg xy: Float): ScanQuad? {
            if (xy.size != 8 || xy.any { !it.isFinite() || it < 0f || it > 1f }) return null
            return ScanQuad(IntArray(8) { Math.round(xy[it] * ONE.toDouble()).toInt() })
        }
    }
}

object FileParseWire {
    private val REQUEST_MAGIC = byteArrayOf('R'.code.toByte(), 'F'.code.toByte(), 'Q'.code.toByte(), 1)
    private val REPLY_MAGIC = byteArrayOf('R'.code.toByte(), 'F'.code.toByte(), 'R'.code.toByte(), 1)
    /** Blobs in one reply: READ's pages and its thumbnail. */
    const val MAX_BLOBS = ParseLimits.READ_PAGES + 1

    fun encodeRequest(r: ParseRequest): ByteArray {
        require(r.parts.size in 1..ParseLimits.MAX_SCAN_PAGES && r.parts.all { it > 0 } && r.inputSize <= ParseLimits.MAX_INPUT)
        require(r.flags and ParseRequest.allowed(r.op).inv() == 0 && r.page in 0..0xFFFF)
        require((r.op == ParseOp.STRAIGHTEN) == (r.quad != null))
        require(r.op == ParseOp.SCAN_PDF || r.parts.size == 1)
        val b = ByteBuffer.allocate(4 + 1 + 1 + 2 + 1 + 4 * r.parts.size + (if (r.quad != null) 32 else 0) + 4).order(ByteOrder.BIG_ENDIAN)
        b.put(REQUEST_MAGIC).put(r.op.code.toByte()).put(r.flags.toByte()).putShort(r.page.toShort()).put(r.parts.size.toByte())
        for (p in r.parts) b.putInt(p)
        r.quad?.points?.forEach { b.putInt(it) }
        b.putInt(r.outSize)
        return b.array()
    }

    /**
     * The request in [frame], when it is exactly one and its regions are the sizes it says ([inputSize], [outSize]: the
     * regions' own sizes, as the service mapped them); else null (BAD_REQUEST).
     */
    fun decodeRequest(frame: ByteArray, inputSize: Long, outSize: Int): ParseRequest? {
        if (frame.size < 13 || frame.size > 13 + 4 * ParseLimits.MAX_SCAN_PAGES + 32) return null
        val b = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (!magic(b, REQUEST_MAGIC)) return null
        val op = ParseOp.of(u8(b)) ?: return null
        val flags = u8(b)
        if (flags and ParseRequest.allowed(op).inv() != 0) return null
        val page = b.short.toInt() and 0xFFFF
        val n = u8(b)
        if (n !in 1..ParseLimits.MAX_SCAN_PAGES || (op != ParseOp.SCAN_PDF && n != 1)) return null
        val quadBytes = if (op == ParseOp.STRAIGHTEN) 32 else 0
        if (b.remaining() != 4 * n + quadBytes + 4) return null
        val parts = List(n) { b.int }
        if (parts.any { it <= 0 } || parts.sumOf { it.toLong() } != inputSize || inputSize > ParseLimits.MAX_INPUT) return null
        var quad: ScanQuad? = null
        if (quadBytes > 0) {
            val p = IntArray(8) { b.int }
            if (p.any { it !in 0..ScanQuad.ONE }) return null
            quad = ScanQuad(p)
        }
        if (op != ParseOp.PIXELS && page != 0) return null
        val r = ParseRequest(op, flags, page, parts, quad)
        val out = b.int
        if (out != r.outSize || outSize != r.outSize) return null
        return r
    }

    fun encodeReply(r: ParseReply): ByteArray {
        val text = r.text?.encodeToByteArray() ?: ByteArray(0)
        require(text.size <= ParseLimits.MAX_TEXT && r.blobs.size <= MAX_BLOBS)
        val b = ByteBuffer.allocate(4 + 4 + 16 + 4 + text.size + 1 + 17 * r.blobs.size).order(ByteOrder.BIG_ENDIAN)
        b.put(REPLY_MAGIC).put(r.op.code.toByte()).put(r.status.code.toByte()).put(r.type.code.toByte()).put(r.flags.toByte())
        b.putInt(r.pages).putInt(r.width).putInt(r.height).putInt(r.date)
        b.putInt(text.size).put(text)
        b.put(r.blobs.size.toByte())
        for (x in r.blobs) b.put(x.kind.code.toByte()).putInt(x.width).putInt(x.height).putInt(x.offset).putInt(x.length)
        return b.array()
    }

    /** What [encodeReply] writes for a refusal: [status], the sniffed [type], nothing else. */
    fun refusal(op: ParseOp, status: ParseStatus, type: FileType): ByteArray =
        encodeReply(ParseReply(op, status, type))

    /**
     * The reply to [req] in [frame], when every field is within its cap and its blobs are packed from the start of the
     * output region (of [req]'s size) in the op's order; else null. Content is checked after the copy ([checkBlob]).
     */
    fun decodeReply(frame: ByteArray, req: ParseRequest): ParseReply? {
        if (frame.size < 29 || frame.size > FileParseIpc.MAX_FRAME) return null
        val b = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (!magic(b, REPLY_MAGIC)) return null
        val op = ParseOp.of(u8(b)) ?: return null
        val status = ParseStatus.of(u8(b)) ?: return null
        if (op != req.op) return null
        val type = FileType.of(u8(b)) ?: return null
        val flags = u8(b)
        if (flags and ParseReply.ALL_FLAGS.inv() != 0) return null
        val pages = b.int
        val width = b.int
        val height = b.int
        val date = b.int
        if (pages !in 0..ParseLimits.MAX_PAGE_COUNT || width !in 0..ParseLimits.MAX_SOURCE_EDGE || height !in 0..ParseLimits.MAX_SOURCE_EDGE) return null
        if (date != 0 && ExifDate.iso(date) == null) return null
        val textLen = b.int
        if (textLen !in 0..ParseLimits.MAX_TEXT || textLen > b.remaining() - 1) return null
        val textBytes = ByteArray(textLen).also { b.get(it) }
        val text = if (textLen == 0) null else utf8(textBytes) ?: return null
        val count = u8(b)
        if (count > MAX_BLOBS || b.remaining() != 17 * count) return null
        val blobs = List(count) { ParseBlob(BlobKind.of(u8(b)) ?: return null, b.int, b.int, b.int, b.int) }
        val reply = ParseReply(op, status, type, flags, pages, width, height, date, text, blobs)
        return reply.takeIf { fits(it, req) }
    }

    /** What each op may answer: a refusal carries nothing; an answer only the outputs its request asked for, within caps. */
    private fun fits(r: ParseReply, req: ParseRequest): Boolean {
        if (r.status != ParseStatus.OK) return r.blobs.isEmpty() && r.text == null && r.flags == 0 && r.date == 0
        if (r.text != null && (req.op != ParseOp.READ || !req.has(ParseRequest.TEXT) || !r.has(ParseReply.HAS_TEXT))) return false
        if (r.date != 0 && (req.op != ParseOp.READ || !req.has(ParseRequest.DATE))) return false
        val kinds = r.blobs.map { it.kind }
        val ok = when (req.op) {
            ParseOp.READ -> {
                val pages = kinds.count { it == BlobKind.PAGE }
                val thumbs = kinds.count { it == BlobKind.THUMB }
                pages + thumbs == kinds.size && pages <= ParseLimits.READ_PAGES && thumbs <= 1 &&
                    kinds == kinds.sortedBy { it.code } && (pages == 0 || req.has(ParseRequest.PAGES)) && (thumbs == 0 || req.has(ParseRequest.THUMB))
            }
            ParseOp.PIXELS -> kinds == listOf(BlobKind.PIXELS)
            ParseOp.STRIP -> kinds == listOf(BlobKind.COPY)
            ParseOp.STRAIGHTEN -> kinds == listOf(BlobKind.SCAN)
            ParseOp.SCAN_PDF -> kinds == listOf(BlobKind.PDF)
        }
        if (!ok) return false
        var at = 0L
        for (x in r.blobs) {
            if (x.offset.toLong() != at || x.length <= 0 || x.length > maxBytes(x.kind, req)) return false
            at += x.length
            if (at > req.outSize) return false
            if (!dimsFit(x, req)) return false
        }
        return true
    }

    /** A blob kind's largest size, in bytes. */
    fun maxBytes(kind: BlobKind, req: ParseRequest): Int = when (kind) {
        BlobKind.PAGE -> ParseLimits.MAX_PAGE_JPEG
        BlobKind.THUMB -> ParseLimits.MAX_THUMB_JPEG
        BlobKind.PIXELS -> ParseLimits.outCap(ParseOp.PIXELS, req.flags)
        BlobKind.COPY -> ParseLimits.MAX_COPY
        BlobKind.SCAN -> ParseLimits.MAX_SCAN_JPEG
        BlobKind.PDF -> ParseLimits.MAX_PDF
    }

    private fun dimsFit(x: ParseBlob, req: ParseRequest): Boolean {
        val d = Dim(x.width, x.height)
        return when (x.kind) {
            BlobKind.PAGE -> ParseLimits.PAGE.holds(d)
            BlobKind.THUMB -> ParseLimits.THUMB.holds(d)
            BlobKind.PIXELS -> (if (req.has(ParseRequest.VIEW_THUMB)) ParseLimits.VIEW_THUMB else ParseLimits.VIEW_PAGE).holds(d) &&
                d.pixels * 4 == x.length.toLong()
            BlobKind.COPY -> x.width >= 1 && x.height >= 1 && d.pixels <= ParseLimits.COPY_PIXELS
            BlobKind.SCAN -> ParseLimits.SCAN_PAGE.holds(d)
            BlobKind.PDF -> x.width == 0 && x.height == 0
        }
    }

    /**
     * A blob's bytes, as copied out of the region, are what the frame says: a JPEG (or a COPY's PNG) whose own header is
     * the blob's size, a PDF that starts as one, pixels of exactly width × height × 4 bytes.
     */
    fun checkBlob(x: ParseBlob, bytes: ByteArray): Boolean {
        if (bytes.size != x.length) return false
        val d = Dim(x.width, x.height)
        return when (x.kind) {
            BlobKind.PAGE, BlobKind.THUMB, BlobKind.SCAN -> FileSniff.sniff(bytes, bytes.size.toLong()) == FileType.JPEG && ImageHeader.jpeg(ByteBuffer.wrap(bytes)) == d
            BlobKind.COPY -> when (FileSniff.sniff(bytes, bytes.size.toLong())) {
                FileType.JPEG -> ImageHeader.jpeg(ByteBuffer.wrap(bytes)) == d
                FileType.PNG -> ImageHeader.png(ByteBuffer.wrap(bytes)) == d
                else -> false
            }
            BlobKind.PDF -> FileSniff.sniff(bytes, bytes.size.toLong()) == FileType.PDF
            BlobKind.PIXELS -> bytes.size.toLong() == d.pixels * 4
        }
    }

    /** Strict UTF-8 (no malformed or cut sequence), or null. */
    fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** [s] as UTF-8 of at most [max] bytes, cut on a character's boundary (never inside a surrogate pair); [cut]: it was. */
    fun cutUtf8(s: String, max: Int): Pair<String, Boolean> {
        val enc = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val out = ByteBuffer.allocate(max)
        val input = CharBuffer.wrap(s)
        val r = enc.encode(input, out, true)
        val cut = r.isOverflow || input.hasRemaining()
        if (!cut) enc.flush(out)
        return String(out.array(), 0, out.position(), Charsets.UTF_8) to cut
    }

    private fun magic(b: ByteBuffer, m: ByteArray): Boolean = m.all { b.get() == it }
    private fun u8(b: ByteBuffer): Int = b.get().toInt() and 0xFF
}
