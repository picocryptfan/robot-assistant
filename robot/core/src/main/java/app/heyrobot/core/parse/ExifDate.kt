// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import java.nio.ByteBuffer

// Pure. A photo's date taken (EXIF DateTimeOriginal, tag 0x9003) and nothing else (files-1.4-plan.md §4.3): never its
// location, its camera or its owner. Read in :files only, from a JPEG's APP1 "Exif" segment, a PNG's eXIf chunk or a
// WebP's EXIF chunk, with every offset checked against the TIFF block, at most [MAX_ENTRIES] entries an IFD and two IFDs
// followed (IFD0, then its Exif IFD). Anything odd gives no date, never an exception. HEIC's date is in its item boxes:
// not read here (F0b decides whether the platform's reader works in :files).

object ExifDate {
    const val TAG_EXIF_IFD = 0x8769
    const val TAG_DATE_ORIGINAL = 0x9003
    /** Entries read in one IFD. */
    const val MAX_ENTRIES = 256
    /** Chunks or segments walked looking for the EXIF block. */
    private const val MAX_BLOCKS = 512

    /** The date taken as yyyymmdd (20260814), or 0 when there is none or it isn't a real date. */
    fun read(type: FileType, b: ByteBuffer): Int {
        val tiff = when (type) {
            FileType.JPEG -> jpegExif(b)
            FileType.PNG -> pngExif(b)
            FileType.WEBP -> webpExif(b)
            else -> null
        } ?: return 0
        return try { dateIn(b, tiff.first, tiff.last + 1) } catch (_: RuntimeException) { 0 }
    }

    /** "2026:08:14 12:34:56" (EXIF's form) as 20260814; 0 for anything else, or a day that doesn't exist. */
    fun parse(s: String): Int {
        if (s.length < 10 || s[4] != ':' || s[7] != ':') return 0
        if (s.length > 10 && s[10] != ' ') return 0
        val y = s.substring(0, 4).toIntOrNull() ?: return 0
        val m = s.substring(5, 7).toIntOrNull() ?: return 0
        val d = s.substring(8, 10).toIntOrNull() ?: return 0
        if (!s.substring(0, 10).all { it.isDigit() || it == ':' }) return 0
        return of(y, m, d)
    }

    /** yyyymmdd for a real day between 1900 and 2199, else 0. */
    fun of(y: Int, m: Int, d: Int): Int {
        if (y !in 1900..2199 || m !in 1..12 || d < 1) return 0
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[m - 1]
        return if (d <= days) y * 10_000 + m * 100 + d else 0
    }

    /** A wire date (yyyymmdd, or 0) as it is shown and filed: "2026-08-14", or null. */
    fun iso(v: Int): String? {
        if (v == 0 || of(v / 10_000, v / 100 % 100, v % 100) != v) return null
        return "%04d-%02d-%02d".format(v / 10_000, v / 100 % 100, v % 100)  // l10n: not text (an ISO date)
    }

    /** The TIFF block inside a JPEG's first APP1 "Exif\0\0" segment, before the scan. */
    private fun jpegExif(b: ByteBuffer): IntRange? {
        val n = b.limit()
        if (n < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        repeat(MAX_BLOCKS) {
            if (i + 4 > n || u8(b, i) != 0xFF) return null
            val m = u8(b, i + 1)
            if (m == 0xDA || m == 0xD9) return null
            val len = u16(b, i + 2)
            if (len < 2 || i + 2 + len > n) return null
            if (m == 0xE1 && len >= 8 && ascii(b, i + 4, "Exif") && u8(b, i + 8) == 0 && u8(b, i + 9) == 0) return (i + 10) until (i + 2 + len)
            i += 2 + len
        }
        return null
    }

    /** A PNG's eXIf chunk (before IDAT or after it), its data the TIFF block. */
    private fun pngExif(b: ByteBuffer): IntRange? {
        val n = b.limit()
        var i = 8
        repeat(MAX_BLOCKS) {
            if (i + 12 > n) return null
            val len = u32(b, i)
            if (len < 0 || len > n - i - 12L) return null
            if (ascii(b, i + 4, "eXIf")) return (i + 8) until (i + 8 + len.toInt())
            if (ascii(b, i + 4, "IEND")) return null
            i += 12 + len.toInt()
        }
        return null
    }

    /** A WebP's EXIF chunk (an extended file's), its data the TIFF block (some writers keep "Exif\0\0" before it). */
    private fun webpExif(b: ByteBuffer): IntRange? {
        val n = b.limit()
        if (n < 12 || !ascii(b, 0, "RIFF") || !ascii(b, 8, "WEBP")) return null
        var i = 12
        repeat(MAX_BLOCKS) {
            if (i + 8 > n) return null
            val len = u32le(b, i + 4)
            if (len < 0 || len > n - i - 8L) return null
            if (ascii(b, i, "EXIF")) {
                val start = i + 8
                val skip = if (len >= 6 && ascii(b, start, "Exif") && u8(b, start + 4) == 0 && u8(b, start + 5) == 0) 6 else 0
                return (start + skip) until (start + len.toInt())
            }
            i += 8 + len.toInt() + (len.toInt() and 1)
        }
        return null
    }

    /** DateTimeOriginal in the Exif IFD that IFD0 points to, within the TIFF block [start] to [end]. */
    private fun dateIn(b: ByteBuffer, start: Int, end: Int): Int {
        if (end - start < 8) return 0
        val little = when {
            u8(b, start) == 0x49 && u8(b, start + 1) == 0x49 -> true
            u8(b, start) == 0x4D && u8(b, start + 1) == 0x4D -> false
            else -> return 0
        }
        val r = Tiff(b, start, end, little)
        if (r.u16(2) != 42) return 0
        val ifd0 = r.u32(4)
        val exif = r.find(ifd0, TAG_EXIF_IFD) ?: return 0
        if ((exif.type != 4 && exif.type != 13) || exif.count != 1L) return 0
        val exifIfd = exif.value
        if (exifIfd == ifd0) return 0
        val date = r.find(exifIfd, TAG_DATE_ORIGINAL) ?: return 0
        // ASCII, "YYYY:MM:DD HH:MM:SS" and its NUL: 20 bytes, held at an offset (more than 4 bytes).
        if (date.type != 2 || date.count !in 11L..64L) return 0
        val at = date.value
        if (at < 0 || at + date.count > r.size) return 0
        val s = String(CharArray(19.coerceAtMost(date.count.toInt())) { (r.u8(at.toInt() + it)).toChar() })
        return parse(s.trimEnd('\u0000'))
    }

    private class Entry(val type: Int, val count: Long, val value: Long)

    private class Tiff(val b: ByteBuffer, val base: Int, end: Int, val little: Boolean) {
        val size = (end - base).toLong()

        fun u8(o: Int): Int = b.get(base + o).toInt() and 0xFF
        fun u16(o: Long): Int {
            require(o >= 0 && o + 2 <= size)
            val i = o.toInt()
            return if (little) u8(i) or (u8(i + 1) shl 8) else (u8(i) shl 8) or u8(i + 1)
        }
        fun u32(o: Long): Long {
            require(o >= 0 && o + 4 <= size)
            val a = u16(o).toLong()
            val c = u16(o + 2).toLong()
            return if (little) a or (c shl 16) else (a shl 16) or c
        }

        /** Tag [tag]'s entry in the IFD at [ifd], or null. */
        fun find(ifd: Long, tag: Int): Entry? {
            if (ifd < 8 || ifd + 2 > size) return null
            val n = u16(ifd)
            if (n > MAX_ENTRIES || ifd + 2 + n * 12L > size) return null
            for (k in 0 until n) {
                val e = ifd + 2 + k * 12L
                if (u16(e) == tag) return Entry(u16(e + 2), u32(e + 4), u32(e + 8))
            }
            return null
        }
    }

    private fun u8(b: ByteBuffer, i: Int): Int = b.get(i).toInt() and 0xFF
    private fun u16(b: ByteBuffer, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteBuffer, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
    private fun u32le(b: ByteBuffer, i: Int): Long =
        u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
    private fun ascii(b: ByteBuffer, i: Int, s: String): Boolean = i + s.length <= b.limit() && s.indices.all { u8(b, i + it) == s[it].code }
}
