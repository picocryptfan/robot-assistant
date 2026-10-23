// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

// Pure. Robot Files (1.4, files-1.4-plan.md §3.1, §4.1 step 2, §4.3): a file's type, sniffed from its first bytes only
// with a fixed table, never from its name or the type the sending app claims. The share target (main) may sniff with it;
// the isolated :files process sniffs again before it decodes anything, and FileParseLink checks that :files' answer is
// its own sniff. Only the decodable groups ([FileGroup.IMAGE], [FileGroup.PDF], [FileGroup.TEXT]) are ever read; the
// rest is kept and named. Every ZIP is DANGEROUS, whatever it holds: an APK, a JAR and a Word or OpenDocument file all
// start PK\3\4, and the first entry's name, which could tell them apart, is the sender's to choose.

/** What Robot does with a type. */
enum class FileGroup {
    /** An image :files decodes: JPEG, PNG, WebP, HEIC and HEIF. */
    IMAGE,
    PDF,
    /** UTF-8 text: read as text, never run or rendered. */
    TEXT,
    /** An image Robot keeps but doesn't read in 1.4 (GIF, AVIF, BMP, TIFF). */
    IMAGE_OTHER,
    VIDEO,
    AUDIO,
    /** Could install or run something: never filed, parsed or opened by Robot (§4.1 step 3). */
    DANGEROUS,
    UNKNOWN,
    EMPTY,
}

/** The fixed table's types: [code] on the wire (FileParseWire), [mime] as a file's provenance stores it. */
enum class FileType(val code: Int, val mime: String, val group: FileGroup) {
    EMPTY(0, "application/x-empty", FileGroup.EMPTY),
    UNKNOWN(1, "application/octet-stream", FileGroup.UNKNOWN),
    JPEG(2, "image/jpeg", FileGroup.IMAGE),
    PNG(3, "image/png", FileGroup.IMAGE),
    WEBP(4, "image/webp", FileGroup.IMAGE),
    HEIC(5, "image/heic", FileGroup.IMAGE),
    HEIF(6, "image/heif", FileGroup.IMAGE),
    PDF(7, "application/pdf", FileGroup.PDF),
    TEXT(8, "text/plain", FileGroup.TEXT),
    GIF(9, "image/gif", FileGroup.IMAGE_OTHER),
    AVIF(10, "image/avif", FileGroup.IMAGE_OTHER),
    BMP(11, "image/bmp", FileGroup.IMAGE_OTHER),
    TIFF(12, "image/tiff", FileGroup.IMAGE_OTHER),
    MP4(20, "video/mp4", FileGroup.VIDEO),
    QUICKTIME(21, "video/quicktime", FileGroup.VIDEO),
    THREE_GP(22, "video/3gpp", FileGroup.VIDEO),
    WEBM(23, "video/webm", FileGroup.VIDEO),
    MATROSKA(24, "video/x-matroska", FileGroup.VIDEO),
    AVI(25, "video/x-msvideo", FileGroup.VIDEO),
    MP3(30, "audio/mpeg", FileGroup.AUDIO),
    M4A(31, "audio/mp4", FileGroup.AUDIO),
    OGG(32, "audio/ogg", FileGroup.AUDIO),
    FLAC(33, "audio/flac", FileGroup.AUDIO),
    WAV(34, "audio/wav", FileGroup.AUDIO),
    /** Every ZIP: APK, JAR, DOCX, XLSX, ODT, EPUB and plain archives alike. */
    ZIP(40, "application/zip", FileGroup.DANGEROUS),
    /** OLE2 compound files: old Office documents (macros), MSI installers. */
    OLE(41, "application/x-ole-storage", FileGroup.DANGEROUS),
    /** RTF, which can embed OLE objects. */
    RTF(42, "application/rtf", FileGroup.DANGEROUS),
    ELF(43, "application/x-elf", FileGroup.DANGEROUS),
    DEX(44, "application/x-dex", FileGroup.DANGEROUS),
    PE(45, "application/x-msdownload", FileGroup.DANGEROUS),
    /** Mach-O, and Java class files (both may start CA FE BA BE). */
    MACHO(46, "application/x-mach-binary", FileGroup.DANGEROUS),
    /** A script with a #! line (or PHP's opening tag). */
    SCRIPT(47, "text/x-script", FileGroup.DANGEROUS),
    /** HTML runs scripts in whatever opens it. */
    HTML(48, "text/html", FileGroup.DANGEROUS),
    /** SVG is XML that can carry scripts. */
    SVG(49, "image/svg+xml", FileGroup.DANGEROUS),
    RAR(50, "application/vnd.rar", FileGroup.DANGEROUS),
    SEVEN_Z(51, "application/x-7z-compressed", FileGroup.DANGEROUS),
    GZIP(52, "application/gzip", FileGroup.DANGEROUS),
    BZIP2(53, "application/x-bzip2", FileGroup.DANGEROUS),
    XZ(54, "application/x-xz", FileGroup.DANGEROUS),
    ZSTD(55, "application/zstd", FileGroup.DANGEROUS),
    TAR(56, "application/x-tar", FileGroup.DANGEROUS),
    CAB(57, "application/vnd.ms-cab-compressed", FileGroup.DANGEROUS),
    ;

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun of(code: Int): FileType? = BY_CODE[code]
    }
}

object FileSniff {
    /** How much of a file the sniff looks at: its first 4 KiB (a tar's "ustar" is at 257, a text's check reads it all). */
    const val PREFIX = 4096

    /** [prefix]: the file's first bytes (at most [PREFIX] are looked at); [size]: the whole file's length. */
    fun sniff(prefix: ByteArray, size: Long): FileType {
        if (size <= 0L || prefix.isEmpty()) return FileType.EMPTY
        val b = if (prefix.size > PREFIX) prefix.copyOf(PREFIX) else prefix
        magic(b)?.let { return it }
        return text(b, whole = size <= b.size)
    }

    private fun magic(b: ByteArray): FileType? {
        fun at(off: Int, vararg v: Int): Boolean = b.size >= off + v.size && v.indices.all { (b[off + it].toInt() and 0xFF) == v[it] }
        fun ascii(off: Int, s: String): Boolean = b.size >= off + s.length && s.indices.all { (b[off + it].toInt() and 0xFF) == s[it].code }
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> FileType.JPEG
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> FileType.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> FileType.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> FileType.WEBP
            ascii(0, "RIFF") && ascii(8, "WAVE") -> FileType.WAV
            ascii(0, "RIFF") && ascii(8, "AVI ") -> FileType.AVI  // l10n: not text (a magic number)
            ascii(4, "ftyp") -> isoMedia(b)
            ascii(0, "%PDF-") -> FileType.PDF  // l10n: not text (a magic number)
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) || at(0, 0x50, 0x4B, 0x07, 0x08) -> FileType.ZIP
            at(0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> FileType.OLE
            ascii(0, "{\\rtf") -> FileType.RTF
            at(0, 0x7F, 0x45, 0x4C, 0x46) -> FileType.ELF
            ascii(0, "dex\n") -> FileType.DEX
            ascii(0, "MZ") -> FileType.PE
            at(0, 0xFE, 0xED, 0xFA, 0xCE) || at(0, 0xFE, 0xED, 0xFA, 0xCF) || at(0, 0xCE, 0xFA, 0xED, 0xFE) || at(0, 0xCF, 0xFA, 0xED, 0xFE) ||
                at(0, 0xCA, 0xFE, 0xBA, 0xBE) || at(0, 0xBE, 0xBA, 0xFE, 0xCA) -> FileType.MACHO
            ascii(0, "Rar!") && at(4, 0x1A, 0x07) -> FileType.RAR  // l10n: not text (a magic number)
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> FileType.SEVEN_Z
            at(0, 0x1F, 0x8B) -> FileType.GZIP
            ascii(0, "BZh") && b.size > 3 && b[3] in '1'.code.toByte()..'9'.code.toByte() -> FileType.BZIP2
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> FileType.XZ
            at(0, 0x28, 0xB5, 0x2F, 0xFD) -> FileType.ZSTD
            ascii(0, "MSCF") -> FileType.CAB
            ascii(257, "ustar") -> FileType.TAR
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if (contains(b, "webm")) FileType.WEBM else FileType.MATROSKA
            ascii(0, "ID3") -> FileType.MP3
            at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) -> FileType.MP3
            ascii(0, "OggS") -> FileType.OGG
            ascii(0, "fLaC") -> FileType.FLAC
            ascii(0, "II*") && at(3, 0x00) || ascii(0, "MM") && at(2, 0x00, 0x2A) -> FileType.TIFF
            bmp(b) -> FileType.BMP
            ascii(0, "#!") -> FileType.SCRIPT
            else -> null
        }
    }

    /** ISO base media (an ftyp box first): HEIC and HEIF stills, AVIF, and MP4-family video and audio, by brand. */
    private fun isoMedia(b: ByteArray): FileType {
        if (b.size < 12) return FileType.UNKNOWN
        val boxSize = u32(b, 0)
        // The ftyp box: its size covers the major brand, the minor version and whole 4-byte compatible brands.
        if (boxSize < 16 || boxSize > PREFIX || (boxSize - 16) % 4 != 0L) return FileType.UNKNOWN
        val end = minOf(boxSize.toInt(), b.size)
        val brands = buildList {
            add(brand(b, 8))
            var o = 16
            while (o + 4 <= end) { add(brand(b, o)); o += 4 }
        }
        val major = brands[0]
        return when {
            major in HEIC_BRANDS -> FileType.HEIC
            major == "avif" || major == "avis" -> FileType.AVIF
            major == "mif1" || major == "msf1" -> when {
                brands.any { it in HEIC_BRANDS } -> FileType.HEIC
                brands.any { it == "avif" || it == "avis" } -> FileType.AVIF
                else -> FileType.HEIF
            }
            major == "qt  " -> FileType.QUICKTIME
            major.startsWith("3gp") || major.startsWith("3g2") -> FileType.THREE_GP
            major == "M4A " || major == "M4B " -> FileType.M4A
            major in MP4_BRANDS -> FileType.MP4
            else -> FileType.UNKNOWN
        }
    }

    private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")
    private val MP4_BRANDS = setOf("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "dash", "mmp4", "M4V ", "M4VH", "M4VP", "f4v ", "MSNV")

    private fun brand(b: ByteArray, off: Int): String = String(CharArray(4) { (b[off + it].toInt() and 0xFF).toChar() })

    /** A Windows bitmap: "BM", its reserved words zero and a known DIB header size (two letters alone are too common in text). */
    private fun bmp(b: ByteArray): Boolean {
        if (b.size < 18 || b[0] != 'B'.code.toByte() || b[1] != 'M'.code.toByte()) return false
        if ((6..9).any { b[it].toInt() != 0 }) return false
        val dib = (b[14].toInt() and 0xFF) or ((b[15].toInt() and 0xFF) shl 8) or ((b[16].toInt() and 0xFF) shl 16) or ((b[17].toInt() and 0xFF) shl 24)
        return dib in setOf(12, 40, 52, 56, 64, 108, 124)
    }

    /**
     * UTF-8 text: valid UTF-8 (a BOM allowed; a character cut by the end of the prefix too, when the file goes on), no NUL
     * and no control character but tab, line feed, form feed and carriage return. HTML, SVG and scripts are text that
     * runs: DANGEROUS. Anything else that isn't text is UNKNOWN.
     */
    private fun text(b: ByteArray, whole: Boolean): FileType {
        var i = if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF) 3 else 0
        val start = i
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            val n = when {
                c < 0x80 -> {
                    if (c == 0 || (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0C && c != 0x0D) || c == 0x7F) return FileType.UNKNOWN
                    1
                }
                c in 0xC2..0xDF -> 2
                c in 0xE0..0xEF -> 3
                c in 0xF0..0xF4 -> 4
                else -> return FileType.UNKNOWN
            }
            if (i + n > b.size) return if (whole) FileType.UNKNOWN else classify(b, start)
            for (k in 1 until n) if ((b[i + k].toInt() and 0xC0) != 0x80) return FileType.UNKNOWN
            // Overlong forms, surrogates and code points past U+10FFFF.
            if (n >= 3) {
                val c1 = b[i + 1].toInt() and 0xFF
                if (n == 3 && ((c == 0xE0 && c1 < 0xA0) || (c == 0xED && c1 > 0x9F))) return FileType.UNKNOWN
                if (n == 4 && ((c == 0xF0 && c1 < 0x90) || (c == 0xF4 && c1 > 0x8F))) return FileType.UNKNOWN
            }
            i += n
        }
        return classify(b, start)
    }

    private fun classify(b: ByteArray, start: Int): FileType {
        val head = String(b, start, b.size - start, Charsets.ISO_8859_1).lowercase()
        val lead = head.trimStart()
        return when {
            lead.startsWith("<?php") -> FileType.SCRIPT
            "<svg" in head -> FileType.SVG
            "<!doctype html" in head || "<html" in head || "<script" in head -> FileType.HTML
            else -> FileType.TEXT
        }
    }

    private fun contains(b: ByteArray, s: String): Boolean = String(b, Charsets.ISO_8859_1).contains(s)

    // FX2 (files-1.4-review.md finding 14): what the first 4 KiB can't tell. A ZIP appended to a JPEG or a PDF still opens as
    // an archive (its end record is what an archive tool reads), and markup after a text's first 4 KiB still runs in a browser.
    // [sniff] stays as it is, the same in every process (FileParseLink checks :files' answer against its own); this is a
    // second look, where the whole file passes: intake as it streams in ([Watch]), :files before it decodes ([hiddenIn]).

    /** How much of a file's end the ZIP check reads: the end record (22 bytes) and its longest comment. */
    const val TAIL = 22 + 65_535

    private val LOOKS_PAST = setOf(FileGroup.IMAGE, FileGroup.IMAGE_OTHER, FileGroup.PDF)

    /**
     * What a file whose first bytes said [type] really is, or null when nothing more: a ZIP when [tail] (its last bytes, at
     * most [TAIL]) ends in a ZIP's end record, for an image or a PDF; HTML or SVG when a text's [markup] holds theirs.
     */
    fun hidden(type: FileType, tail: ByteArray, markup: FileType?): FileType? = when {
        type.group in LOOKS_PAST && zipEnd(tail) -> FileType.ZIP
        type == FileType.TEXT && markup != null -> markup
        else -> null
    }

    /** [hidden] for a whole file in memory ([b], from its position to its limit, as :files reads it). */
    fun hiddenIn(b: java.nio.ByteBuffer, type: FileType): FileType? {
        if (type.group !in LOOKS_PAST && type != FileType.TEXT) return null
        val w = Watch()
        val d = b.duplicate()
        val chunk = ByteArray(64 * 1024)
        while (d.hasRemaining()) {
            val n = minOf(chunk.size, d.remaining())
            d.get(chunk, 0, n)
            w.feed(chunk, 0, n)
        }
        return w.hidden(type)
    }

    /**
     * Whether [tail] ends with a ZIP's end of central directory record: its signature PK 5 6, and its comment's length
     * reaching the file's end exactly (so a picture's bytes that happen to hold the signature don't count).
     */
    fun zipEnd(tail: ByteArray): Boolean {
        var i = tail.size - 22
        val stop = maxOf(0, tail.size - TAIL)
        while (i >= stop) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()) {
                val comment = (tail[i + 20].toInt() and 0xFF) or ((tail[i + 21].toInt() and 0xFF) shl 8)
                if (i + 22 + comment == tail.size) return true
            }
            i--
        }
        return false
    }

    /**
     * A file as it streams by: its last [TAIL] bytes, and the markup in its first ParseLimits.MAX_INPUT bytes (as much as
     * Robot ever reads of a text), found across chunks and case aside.
     */
    class Watch {
        private val ring = ByteArray(TAIL)
        private var count = 0L
        private var carry = ""
        /** What the markup said: HTML, SVG, or none yet. */
        var markup: FileType? = null
            private set

        fun feed(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (markup == null && count < ParseLimits.MAX_INPUT) {
                val n = minOf(len.toLong(), ParseLimits.MAX_INPUT - count).toInt()
                val text = carry + String(b, off, n, Charsets.ISO_8859_1).lowercase()
                markup = MARKUP.firstOrNull { text.contains(it.first) }?.second
                carry = text.takeLast(MARKUP_LONGEST - 1)
            }
            // The ring keeps the last TAIL bytes, oldest first from where the next byte goes.
            val skip = if (len > TAIL) len - TAIL else 0
            var at = off + skip
            var left = len - skip
            var pos = ((count + skip) % TAIL).toInt()
            while (left > 0) {
                val k = minOf(left, TAIL - pos)
                System.arraycopy(b, at, ring, pos, k)
                at += k; left -= k; pos = (pos + k) % TAIL
            }
            count += len
        }

        /** The last bytes seen, in order (all of them when fewer than [TAIL]). */
        fun tail(): ByteArray {
            if (count < TAIL) return ring.copyOf(count.toInt())
            val pos = (count % TAIL).toInt()
            return ring.copyOfRange(pos, TAIL) + ring.copyOfRange(0, pos)
        }

        /** [FileSniff.hidden] for what streamed by, first sniffed as [type]. */
        fun hidden(type: FileType): FileType? = FileSniff.hidden(type, if (type.group in LOOKS_PAST) tail() else ByteArray(0), markup)

        /** FX3: what it kept of the file (its last bytes, a few characters of text) zeroed, once it is done with. */
        fun clear() {
            ring.fill(0)
            carry = ""
        }
    }

    /** Markup that runs, as [classify] knows it, anywhere in a text (lower case). */
    private val MARKUP = listOf("<svg" to FileType.SVG, "<!doctype html" to FileType.HTML, "<html" to FileType.HTML, "<script" to FileType.HTML) // l10n: not text (markup to find)
    private val MARKUP_LONGEST = MARKUP.maxOf { it.first.length }

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
}
