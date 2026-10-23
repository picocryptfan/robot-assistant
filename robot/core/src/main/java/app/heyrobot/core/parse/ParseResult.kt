// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

// Pure. What a request to :files came to, as FileParseLink hands it to its caller (F5's intake, F6's filing, F7's screens):
// outputs that passed every check, a refusal with the type, or why there is no answer. Distinct failures let a caller
// stop retrying a file that keeps crashing or hanging :files (F6 caps its retries).

/** Raw pixels for showing (D19): RGBA, as Bitmap.Config.ARGB_8888 stores them, exactly [width] × [height] × 4 bytes. */
class ParsedPixels(val width: Int, val height: Int, val rgba: ByteArray) {
    override fun toString(): String = "ParsedPixels(${width}x$height)"  // l10n: not text (for tests: never the bytes)
}

/** Everything a request got back, checked. [date]: "2026-08-14" or null; [text]: a PDF's text layer or a text file's. */
class ParseOutput(
    val type: FileType,
    val pages: Int,
    val width: Int,
    val height: Int,
    val date: String?,
    val text: String?,
    val flags: Int,
    /** Page JPEGs for :brain's FILE_READ (each FF D8 FF, at most 4 MiB and 1,024 px). */
    val pageJpegs: List<ByteArray> = emptyList(),
    /** The thumbnail JPEG (320 px, 64 KiB) to seal and keep. */
    val thumb: ByteArray? = null,
    val pixels: ParsedPixels? = null,
    /** The stripped copy for sending (a JPEG, or a PNG for a PNG). */
    val copy: ByteArray? = null,
    /** A straightened scan page (a JPEG). */
    val scan: ByteArray? = null,
    /** A scanned PDF. */
    val pdf: ByteArray? = null,
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    override fun toString(): String = "ParseOutput($type, $pages, ${pageJpegs.size})"  // l10n: not text (for tests: never the text)
}

/** Why a request has no answer. */
enum class ParseFailure {
    /** :files couldn't be bound or had already ended. */
    UNAVAILABLE,
    /** The process didn't prove an isolated uid (or said it isn't isolated): it was sent nothing, and it was ended. */
    NOT_ISOLATED,
    /** The process died during the request. */
    CRASHED,
    /** No answer within ParseLimits.LINK_MS: the process was ended. */
    TIMED_OUT,
    /** The answer broke the wire's rules or didn't match what it said: nothing of it was used, and the process was ended. */
    MALFORMED,
}

sealed class ParseResult {
    class Ok(val out: ParseOutput) : ParseResult()
    class Refused(val status: ParseStatus, val type: FileType) : ParseResult()
    class Failed(val why: ParseFailure) : ParseResult()

    override fun toString(): String = when (this) {
        is Ok -> "Ok($out)"  // l10n: not text (codes for tests)
        is Refused -> "Refused($status, $type)"  // l10n: not text (codes for tests)
        is Failed -> "Failed($why)"  // l10n: not text (codes for tests)
    }
}

object ParseResults {
    /** What each op reads, by the sniffed type's group: anything else is refused before it is sent. */
    fun reads(op: ParseOp): Set<FileGroup> = when (op) {
        ParseOp.READ -> setOf(FileGroup.IMAGE, FileGroup.PDF, FileGroup.TEXT)
        ParseOp.PIXELS -> setOf(FileGroup.IMAGE, FileGroup.PDF)
        ParseOp.STRIP, ParseOp.STRAIGHTEN, ParseOp.SCAN_PDF -> setOf(FileGroup.IMAGE)
    }

    /**
     * The link's own refusal of [req] before anything is sent: [types], each part's own sniff. An empty part, more than
     * the input cap, a type the op doesn't read (DANGEROUS ones are never sent), a scanned PDF of anything but JPEGs.
     */
    fun before(req: ParseRequest, types: List<FileType>): ParseResult.Refused? {
        val type = types.firstOrNull() ?: FileType.EMPTY
        return when {
            req.parts.isEmpty() || req.parts.any { it <= 0 } || types.any { it == FileType.EMPTY } -> ParseResult.Refused(ParseStatus.EMPTY, FileType.EMPTY)
            req.inputSize > ParseLimits.MAX_INPUT -> ParseResult.Refused(ParseStatus.TOO_LARGE, type)
            type.group == FileGroup.DANGEROUS -> ParseResult.Refused(ParseStatus.DANGEROUS, type)
            type.group !in reads(req.op) -> ParseResult.Refused(ParseStatus.NOT_READ, type)
            req.op == ParseOp.SCAN_PDF && types.any { it != FileType.JPEG } -> ParseResult.Refused(ParseStatus.BAD_REQUEST, type)
            else -> null
        }
    }

    /**
     * The answer to [req] from [reply] (already read by FileParseWire.decodeReply, so its numbers and ranges are within
     * caps) and each blob's bytes as copied out of the output region ([bytes]). [sent]: the link's own sniff of what it
     * sent; :files must have seen the same. Any blob whose bytes aren't what the frame says makes the whole answer
     * MALFORMED: nothing of it is used.
     */
    fun from(req: ParseRequest, reply: ParseReply, sent: FileType, bytes: (ParseBlob) -> ByteArray): ParseResult {
        if (reply.type != sent) return ParseResult.Failed(ParseFailure.MALFORMED)
        if (reply.status != ParseStatus.OK) return ParseResult.Refused(reply.status, reply.type)
        val copies = reply.blobs.map { b -> b to bytes(b) }
        if (copies.any { (b, c) -> !FileParseWire.checkBlob(b, c) }) return ParseResult.Failed(ParseFailure.MALFORMED)
        // A stripped PNG stays a PNG; everything else becomes a JPEG.
        copies.firstOrNull { it.first.kind == BlobKind.COPY }?.let { (_, c) ->
            val t = FileSniff.sniff(c, c.size.toLong())
            if (t != (if (sent == FileType.PNG) FileType.PNG else FileType.JPEG)) return ParseResult.Failed(ParseFailure.MALFORMED)
        }
        fun of(k: BlobKind) = copies.filter { it.first.kind == k }
        return ParseResult.Ok(ParseOutput(
            type = reply.type,
            pages = reply.pages,
            width = reply.width,
            height = reply.height,
            date = ExifDate.iso(reply.date),
            text = reply.text,
            flags = reply.flags,
            pageJpegs = of(BlobKind.PAGE).map { it.second },
            thumb = of(BlobKind.THUMB).firstOrNull()?.second,
            pixels = of(BlobKind.PIXELS).firstOrNull()?.let { (b, c) -> ParsedPixels(b.width, b.height, c) },
            copy = of(BlobKind.COPY).firstOrNull()?.second,
            scan = of(BlobKind.SCAN).firstOrNull()?.second,
            pdf = of(BlobKind.PDF).firstOrNull()?.second,
        ))
    }
}
