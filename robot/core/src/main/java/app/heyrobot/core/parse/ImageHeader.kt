// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import java.nio.ByteBuffer

// Pure. An image's declared size, read from its header with bounds checked at every step, never by decoding: :files
// refuses a source past ParseLimits' caps before any decoder sees it (a PNG that declares 100,000 by 100,000 pixels ends
// here), and FileParseLink checks that each JPEG :files returns is the size it says. JPEG (its first SOF), PNG (IHDR) and
// WebP (VP8, VP8L, VP8X). HEIC and HEIF have no simple header: :files asks the platform decoder's own header for those.
// Null means the header couldn't be read: the image is refused as malformed, never decoded on a guess.

object ImageHeader {
    /** Segments a JPEG's header may have before its frame (each APPn, DQT, DHT…): past this it is refused. */
    const val MAX_JPEG_SEGMENTS = 512

    fun dims(type: FileType, b: ByteBuffer): Dim? = when (type) {
        FileType.JPEG -> jpeg(b)
        FileType.PNG -> png(b)
        FileType.WEBP -> webp(b)
        else -> null
    }

    fun dims(type: FileType, b: ByteArray): Dim? = dims(type, ByteBuffer.wrap(b))

    /** The first frame header (SOF0 to SOF15 but DHT, JPG and DAC); none before the scan, a zero size or a cut header: null. */
    fun jpeg(b: ByteBuffer): Dim? {
        val n = b.limit()
        if (n < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        var segments = 0
        while (i < n) {
            if (u8(b, i) != 0xFF) return null
            // Fill bytes: any number of FF before a marker.
            while (i < n && u8(b, i) == 0xFF) i++
            if (i >= n) return null
            val m = u8(b, i)
            i++
            when {
                m == 0x01 || m in 0xD0..0xD7 -> continue // TEM and RSTn: no length
                m == 0xD8 || m == 0xD9 || m == 0xDA || m == 0x00 -> return null // a second SOI, EOI or the scan before any frame
            }
            if (++segments > MAX_JPEG_SEGMENTS || i + 2 > n) return null
            val len = u16(b, i)
            if (len < 2 || i + len > n) return null
            val sof = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC
            if (sof) {
                if (len < 8) return null
                val h = u16(b, i + 3)
                val w = u16(b, i + 5)
                return if (w > 0 && h > 0) Dim(w, h) else null
            }
            i += len
        }
        return null
    }

    /** IHDR, the first chunk: 13 bytes of data, width and height each 1 to 2^31 - 1. */
    fun png(b: ByteBuffer): Dim? {
        if (b.limit() < 24) return null
        val sig = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (sig.indices.any { u8(b, it) != sig[it] }) return null
        if (u32(b, 8) != 13L || !ascii(b, 12, "IHDR")) return null
        val w = u32(b, 16)
        val h = u32(b, 20)
        if (w !in 1..Int.MAX_VALUE.toLong() || h !in 1..Int.MAX_VALUE.toLong()) return null
        return Dim(w.toInt(), h.toInt())
    }

    /** RIFF, WEBP, then the first chunk: VP8 (a key frame's 14-bit sizes), VP8L (14-bit sizes minus one) or VP8X (24-bit canvas). */
    fun webp(b: ByteBuffer): Dim? {
        if (b.limit() < 30 || !ascii(b, 0, "RIFF") || !ascii(b, 8, "WEBP")) return null
        return when {
            ascii(b, 12, "VP8 ") -> {
                // Frame tag (3 bytes, a key frame has bit 0 clear), then the start code 9D 01 2A.
                if (u8(b, 20) and 1 != 0 || u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) return null
                val w = u16le(b, 26) and 0x3FFF
                val h = u16le(b, 28) and 0x3FFF
                if (w > 0 && h > 0) Dim(w, h) else null
            }
            ascii(b, 12, "VP8L") -> {
                if (u8(b, 20) != 0x2F) return null
                val bits = u32le(b, 21)
                Dim(((bits and 0x3FFF) + 1).toInt(), (((bits shr 14) and 0x3FFF) + 1).toInt())
            }
            ascii(b, 12, "VP8X") -> Dim(u24le(b, 24) + 1, u24le(b, 27) + 1)
            else -> null
        }
    }

    private fun u8(b: ByteBuffer, i: Int): Int = b.get(i).toInt() and 0xFF
    private fun u16(b: ByteBuffer, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u16le(b: ByteBuffer, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u24le(b: ByteBuffer, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun u32(b: ByteBuffer, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
    private fun u32le(b: ByteBuffer, i: Int): Long = u16le(b, i).toLong() or (u16le(b, i + 2).toLong() shl 16)
    private fun ascii(b: ByteBuffer, i: Int, s: String): Boolean = i + s.length <= b.limit() && s.indices.all { u8(b, i + it) == s[it].code }
}
