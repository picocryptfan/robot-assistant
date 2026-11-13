// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

// Pure (1.4, report-a-problem-plan.md "What's new" 5). One report as one file, RFB-XXXXXXXX.robotreport: the report's
// text and an optional PNG in one byte layout, read back exactly. The layout, big-endian:
//   "RBRP" (4) · version (1) · the RFB- reference, ASCII (12) · the text's length (4) · the PNG's length (4, 0 = none)
//   · the text, UTF-8 · the PNG.
// A file that is cut short, too big, has bytes after its PNG, or holds anything else is refused, never half read.
// The PNG holds pixels only: write drops its metadata chunks, read refuses them ([ReportFile.pixels]).
// Sealing is not here: RP5 seals these bytes in :link with libsignal's HPKE to the developer's public key through
// [ReportFile.Sealer] (Robot writes no cryptography of its own), and the PC tool opens them back to these bytes.

object ReportFile {
    const val EXTENSION = ".robotreport" // l10n: not text (a file name)
    /** The sealed file's cap, what Robot hands to another app or posts (the website's inbox takes no more). */
    const val MAX_BYTES = 2 * 1024 * 1024
    /** Room left for the seal's own bytes (HPKE's key, tag and framing are well under it). */
    const val SEAL_RESERVE = 4 * 1024
    /** The cap on the bytes here, before sealing. */
    const val MAX_PLAIN = MAX_BYTES - SEAL_RESERVE
    const val VERSION = 1
    const val HEAD = 4 + 1 + 12 + 4 + 4
    /** A screenshot's pixels at most (4096 × 4096): a PNG that claims more is refused, so it can't swell in a viewer. */
    const val MAX_PIXELS = 4096L * 4096L
    /** review-develop-14-b L2: the re-encoded IDAT stops here (a picture can't be more than a report holds); past it, no picture. */
    const val MAX_PACKED = MAX_PLAIN - HEAD
    /** The PNG chunks a screenshot needs; anything else (tEXt, iTXt, zTXt, eXIf, tIME and the rest) is metadata. */
    private val CHUNKS = setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND", "sRGB", "gAMA") // l10n: not text (PNG chunk types)

    private val MAGIC = byteArrayOf('R'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** One report: its reference, its whole text (header and all, as FeedbackReport wrote it) and the screenshot, if any. */
    class Report(val ref: String, val text: String, val png: ByteArray? = null) {
        override fun equals(other: Any?): Boolean = other is Report && other.ref == ref && other.text == text &&
            (if (png == null) other.png == null else other.png != null && png.contentEquals(other.png))
        override fun hashCode(): Int = 31 * (31 * ref.hashCode() + text.hashCode()) + (png?.contentHashCode() ?: 0)
        override fun toString(): String = "Report($ref, ${text.length} chars, png ${png?.size ?: 0} bytes)" // l10n: not text (toString, for tests)
    }

    /** Why a file was refused. */
    enum class Why { NOT_A_REPORT, NEWER_VERSION, TRUNCATED, TOO_BIG, TRAILING, BAD_REF, BAD_TEXT, BAD_PNG }

    sealed interface Read {
        data class Ok(val report: Report) : Read
        data class Refused(val why: Why) : Read
    }

    /** RP5's seam: these bytes in, the sealed file out (libsignal HPKE in :link). */
    fun interface Sealer { fun seal(plain: ByteArray): ByteArray }

    /** The file's name for [ref]. */
    fun name(ref: String): String {
        require(FeedbackRef.valid(ref)) { "not a reference" } // l10n: not text (an exception)
        return ref + EXTENSION
    }

    /** The bytes [write] would make, so the card can say a screenshot won't fit before you tick it. */
    fun size(r: Report): Long = HEAD.toLong() + utf8(clean(r.text)).size + (r.png?.size ?: 0)

    fun fits(r: Report): Boolean = size(r) <= MAX_PLAIN

    /** The bytes for [r]; throws IllegalArgumentException for a bad reference, a PNG that isn't one, or past [MAX_PLAIN]. */
    fun write(r: Report): ByteArray {
        require(FeedbackRef.valid(r.ref)) { "not a reference" } // l10n: not text (an exception)
        val png = r.png?.takeIf { it.isNotEmpty() }?.let { requireNotNull(pixels(it, strip = true)) { "not a PNG" } } // l10n: not text (an exception)
        val text = utf8(clean(r.text))
        val size = HEAD.toLong() + text.size + (png?.size ?: 0)
        require(size <= MAX_PLAIN) { "report too big: $size bytes" } // l10n: not text (an exception)
        return ByteBuffer.allocate(size.toInt()).put(MAGIC).put(VERSION.toByte()).put(r.ref.toByteArray(StandardCharsets.US_ASCII))
            .putInt(text.size).putInt(png?.size ?: 0).put(text).apply { png?.let { put(it) } }.array()
    }

    /** [write], then [sealer]; throws IllegalStateException when the sealed file would pass [MAX_BYTES]. */
    fun seal(r: Report, sealer: Sealer): ByteArray {
        val out = sealer.seal(write(r))
        check(out.size <= MAX_BYTES) { "sealed report too big: ${out.size} bytes" } // l10n: not text (an exception)
        return out
    }

    /** [bytes] back to the report they were written from, or why not. */
    fun read(bytes: ByteArray): Read {
        if (bytes.size > MAX_PLAIN) return Read.Refused(Why.TOO_BIG)
        val m = minOf(bytes.size, MAGIC.size)
        for (i in 0 until m) if (bytes[i] != MAGIC[i]) return Read.Refused(Why.NOT_A_REPORT)
        if (bytes.size < HEAD) return Read.Refused(Why.TRUNCATED)
        val b = ByteBuffer.wrap(bytes)
        b.position(MAGIC.size)
        val version = b.get().toInt() and 0xFF
        if (version != VERSION) return Read.Refused(if (version > VERSION) Why.NEWER_VERSION else Why.NOT_A_REPORT)
        val ref = String(ByteArray(12).also { b.get(it) }, StandardCharsets.ISO_8859_1)
        val textLen = b.int.toLong() and 0xFFFFFFFFL
        val pngLen = b.int.toLong() and 0xFFFFFFFFL
        val want = HEAD + textLen + pngLen
        if (want > MAX_PLAIN) return Read.Refused(Why.TOO_BIG)
        if (want > bytes.size) return Read.Refused(Why.TRUNCATED)
        if (want < bytes.size) return Read.Refused(Why.TRAILING)
        if (!FeedbackRef.valid(ref)) return Read.Refused(Why.BAD_REF)
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, HEAD, textLen.toInt())).toString()
        } catch (_: CharacterCodingException) { return Read.Refused(Why.BAD_TEXT) }
        if (text.codePoints().anyMatch { isControl(it) }) return Read.Refused(Why.BAD_TEXT)
        val png = if (pngLen == 0L) null else bytes.copyOfRange((HEAD + textLen).toInt(), bytes.size)
        // The pixels as re-encoded here, never the file's own bytes.
        val clean = png?.let { pixels(it, strip = false) ?: return Read.Refused(Why.BAD_PNG) }
        return Read.Ok(Report(ref, text, clean))
    }

    /** At most [MAX_PLAIN] and one byte from [input], so a file too big is refused without holding it all. */
    fun read(input: InputStream): Read {
        val bytes = input.readNBytes(MAX_PLAIN + 1)
        return read(bytes)
    }

    /**
     * [b] as a PNG of pixels only, or null: the signature, IHDR first (at most [MAX_PIXELS]), each chunk's length inside
     * the file and its CRC right, an IDAT, IEND last with nothing after it, and no chunk but [CHUNKS]. With [strip],
     * another ancillary chunk (a lower-case first letter: tEXt, eXIf...) is dropped instead of refused. IHDR holds one of
     * the PNG spec's (depth, colour type) pairs, compression and filter method 0 and interlace 0 or 1; PLTE, tRNS, sRGB
     * and gAMA come at most once and before the IDATs, which follow each other; a palette picture has its PLTE. The IDATs'
     * stream is inflated to exactly the rows IHDR gives (each with a filter type 0 to 4, nothing after the stream) and
     * written again as one IDAT, so none of the sender's own compressed bytes are kept.
     */
    internal fun pixels(b: ByteArray, strip: Boolean, cap: Int = MAX_PACKED): ByteArray? {
        if (b.size <= PNG.size || PNG.indices.any { b[it] != PNG[it] }) return null
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(PNG)
        var at = PNG.size
        var head: Head? = null
        var idat = 0 // 0 none yet, 1 in the IDATs, 2 past them
        var plte = 0L // the palette's entries
        val stream = java.io.ByteArrayOutputStream()
        val seen = HashSet<String>()
        while (b.size - at >= 12) {
            val len = ByteBuffer.wrap(b, at, 4).int.toLong() and 0xFFFFFFFFL
            if (len > b.size - at - 12L) return null
            val end = at + 12 + len.toInt()
            val type = String(b, at + 4, 4, StandardCharsets.ISO_8859_1)
            val crc = CRC32().apply { update(b, at + 4, 4 + len.toInt()) }.value
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' } || crc != (ByteBuffer.wrap(b, end - 4, 4).int.toLong() and 0xFFFFFFFFL)) return null
            if ((at == PNG.size) != (type == "IHDR")) return null // l10n: not text (a PNG chunk type)
            if (type == "IHDR") { // l10n: not text (a PNG chunk type)
                if (len != 13L) return null
                val w = ByteBuffer.wrap(b, at + 8, 4).int.toLong() and 0xFFFFFFFFL
                val h = ByteBuffer.wrap(b, at + 12, 4).int.toLong() and 0xFFFFFFFFL
                if (w == 0L || h == 0L || w > MAX_PIXELS || h > MAX_PIXELS || w * h > MAX_PIXELS) return null
                head = Head.of(w, h, b, at + 16) ?: return null
            }
            if (type == "IDAT") { // l10n: not text (a PNG chunk type)
                if (idat == 2) return null
                idat = 1
                stream.write(b, at + 8, len.toInt())
            } else if (idat == 1) idat = 2
            when {
                type == "IEND" -> { // l10n: not text (a PNG chunk type)
                    if (len != 0L || end != b.size || idat == 0 || (head!!.colour == 3 && "PLTE" !in seen)) return null // l10n: not text (a PNG chunk type)
                    val px = repack(head, stream.toByteArray(), cap) ?: return null
                    out.write(chunk("IDAT", px)) // l10n: not text (a PNG chunk type)
                    out.write(b, at, end - at)
                    return out.toByteArray()
                }
                type == "IDAT" -> {} // l10n: not text (a PNG chunk type)
                type in CHUNKS -> {
                    if (type != "IHDR" && (idat != 0 || !seen.add(type))) return null // l10n: not text (a PNG chunk type)
                    if (type == "PLTE" && (head!!.colour == 0 || head.colour == 4 || (head.colour == 3 && (len % 3 != 0L || len == 0L || len > 768)))) return null // l10n: not text (a PNG chunk type)
                    if (type == "PLTE") plte = len / 3 // l10n: not text (a PNG chunk type)
                    if (kept(type, len, b, at + 8, head!!, plte)) out.write(b, at, end - at)
                }
                !(strip && type[0].isLowerCase()) -> return null
            }
            at = end
        }
        return null
    }

    /**
     * review-develop-14-b M2: [type]'s data at [at] is the size the PNG spec gives it for [head] (sRGB 1 byte, 0 to 3; gAMA 4;
     * tRNS 2 for grey, 6 for RGB, 1 to [plte] entries after a palette, none with an alpha channel), and PLTE only in a
     * palette picture; any other is dropped, so no chunk carries the sender's own bytes past the re-encode.
     */
    private fun kept(type: String, len: Long, b: ByteArray, at: Int, head: Head, plte: Long): Boolean = when (type) {
        "IHDR" -> true // l10n: not text (a PNG chunk type)
        "PLTE" -> head.colour == 3 // l10n: not text (a PNG chunk type)
        "sRGB" -> len == 1L && b[at].toInt() in 0..3 // l10n: not text (a PNG chunk type)
        "gAMA" -> len == 4L // l10n: not text (a PNG chunk type)
        "tRNS" -> when (head.colour) { 0 -> len == 2L; 2 -> len == 6L; 3 -> len in 1..plte; else -> false } // l10n: not text (a PNG chunk type)
        else -> false
    }

    /** IHDR as read: its size, bits per pixel (depth times channels), colour type and whether it's Adam7-interlaced. */
    private class Head(val w: Long, val h: Long, val bits: Int, val colour: Int, val interlaced: Boolean) {
        /** Each row's length in the inflated stream, its filter byte included, pass by pass when interlaced. */
        fun rows(): Sequence<Long> = sequence {
            for ((x0, y0, dx, dy) in if (interlaced) ADAM7 else listOf(intArrayOf(0, 0, 1, 1))) {
                val pw = if (w > x0) (w - x0 + dx - 1) / dx else 0L
                val ph = if (h > y0) (h - y0 + dy - 1) / dy else 0L
                if (pw == 0L || ph == 0L) continue
                val len = 1 + (pw * bits + 7) / 8
                for (i in 0 until ph) yield(len)
            }
        }

        companion object {
            /** The PNG spec's bit depths by colour type (grey, RGB, palette, grey and alpha, RGBA), and its channels. */
            private val DEPTHS = mapOf(0 to setOf(1, 2, 4, 8, 16), 2 to setOf(8, 16), 3 to setOf(1, 2, 4, 8), 4 to setOf(8, 16), 6 to setOf(8, 16))
            private val CHANNELS = mapOf(0 to 1, 2 to 3, 3 to 1, 4 to 2, 6 to 4)
            /** Adam7's seven passes: first column, first row, column step, row step. */
            private val ADAM7 = listOf(intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
                intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))

            /** IHDR's last five bytes at [at]: depth, colour type, compression, filter method, interlace. */
            fun of(w: Long, h: Long, b: ByteArray, at: Int): Head? {
                val depth = b[at].toInt() and 0xFF
                val colour = b[at + 1].toInt() and 0xFF
                val depths = DEPTHS[colour] ?: return null
                if (depth !in depths || b[at + 2].toInt() != 0 || b[at + 3].toInt() != 0 || b[at + 4].toInt() !in 0..1) return null
                return Head(w, h, depth * CHANNELS.getValue(colour), colour, b[at + 4].toInt() == 1)
            }
        }
    }

    /**
     * The IDATs' zlib stream [z] inflated a piece at a time to exactly [head]'s rows, each with a filter type 0 to 4, and
     * nothing after the stream ends, deflated again at the default level; or null, also once the output passes [cap].
     */
    private fun repack(head: Head, z: ByteArray, cap: Int): ByteArray? {
        val inf = Inflater()
        val def = Deflater()
        return try {
            inf.setInput(z)
            val packed = java.io.ByteArrayOutputStream(z.size)
            val rows = head.rows().iterator()
            val buf = ByteArray(64 * 1024)
            val got = ByteArray(64 * 1024)
            var left = 0L
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && !inf.finished()) return null
                var i = 0
                while (i < n) {
                    if (left == 0L) {
                        if (!rows.hasNext()) return null
                        left = rows.next()
                        if (buf[i].toInt() !in 0..4) return null
                    }
                    val take = minOf(left, (n - i).toLong()).toInt()
                    left -= take
                    i += take
                }
                def.setInput(buf, 0, n)
                while (!def.needsInput()) packed.write(got, 0, def.deflate(got))
                if (packed.size() > cap) return null
            }
            if (inf.remaining != 0 || left != 0L || rows.hasNext()) return null
            def.finish()
            while (!def.finished()) packed.write(got, 0, def.deflate(got))
            if (packed.size() > cap) return null
            packed.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inf.end()
            def.end()
        }
    }

    /** One PNG chunk: length, type, data, CRC. */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(StandardCharsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value.toInt()
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(t).put(data).putInt(crc).array()
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    /**
     * What a report's text never holds, by code point: controls (C0 but \n and \t, DEL, C1), format characters (the bidi
     * controls, the zero-width ones, the soft hyphen, U+FEFF, the tag block) but the joiners ZWJ and ZWNJ, line and
     * paragraph separators, and lone surrogates: invisible, yet read by whatever sorts the report.
     */
    internal fun isControl(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONTROL.toInt() -> cp != '\n'.code && cp != '\t'.code
        Character.FORMAT.toInt() -> cp != 0x200C && cp != 0x200D
        Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt() -> true
        else -> false
    }

    /** [s] without [isControl]'s characters, as [write] stores it. */
    fun clean(s: String): String {
        if (s.codePoints().noneMatch { isControl(it) }) return s
        val b = StringBuilder(s.length)
        s.codePoints().forEach { if (!isControl(it)) b.appendCodePoint(it) }
        return b.toString()
    }
}
