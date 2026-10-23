// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Headers built byte by byte (no image library): what the sniff, the header reader and the EXIF reader see. */
internal object ParseFixtures {
    fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** A TIFF block with IFD0 pointing to an Exif IFD that holds DateTimeOriginal (and a GPS pointer, never followed). */
    fun tiff(date: String = "2026:08:14 12:34:56", little: Boolean = true, exifType: Int = 4, entries: Int? = null, exifAt: Int? = null): ByteArray {
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val b = ByteBuffer.allocate(200).order(order)
        b.put(if (little) bytes(0x49, 0x49) else bytes(0x4D, 0x4D)).putShort(42).putInt(8)
        // IFD0 at 8: two entries (GPS pointer, Exif pointer), next IFD 0.
        b.putShort((entries ?: 2).toShort())
        b.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(150) // GPS IFD pointer: never read
        b.putShort(0x8769.toShort()).putShort(exifType.toShort()).putInt(1).putInt(exifAt ?: 38)
        b.putInt(0)
        // Exif IFD at 38: one entry, DateTimeOriginal at offset 56 (20 bytes).
        b.position(38)
        b.putShort(1)
        b.putShort(0x9003.toShort()).putShort(2).putInt(date.length + 1).putInt(56)
        b.putInt(0)
        b.position(56)
        b.put(date.toByteArray(Charsets.US_ASCII)).put(0)
        b.position(150)
        b.putShort(0).putInt(0)
        return b.array().copyOf(160)
    }

    /** SOI, APP0, an optional APP1 Exif, DQT, SOFn (its size), SOS and EOI: a header, not a picture. */
    fun jpeg(w: Int, h: Int, exif: ByteArray? = null, sof: Int = 0xC0, extraSegments: Int = 0): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(bytes(0xFF, 0xD8))
        segment(o, 0xE0, "JFIF".toByteArray() + bytes(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        exif?.let { segment(o, 0xE1, "Exif".toByteArray() + bytes(0, 0) + it) }
        repeat(extraSegments) { segment(o, 0xEB, ByteArray(4)) }
        segment(o, 0xDB, ByteArray(65))
        segment(o, sof, bytes(8, h shr 8, h and 0xFF, w shr 8, w and 0xFF, 1, 1, 0x11, 0))
        segment(o, 0xDA, bytes(1, 1, 0, 0, 0x3F, 0))
        o.write(bytes(0x12, 0x34, 0xFF, 0xD9))
        return o.toByteArray()
    }

    private fun segment(o: ByteArrayOutputStream, marker: Int, data: ByteArray) {
        o.write(bytes(0xFF, marker, (data.size + 2) shr 8, (data.size + 2) and 0xFF))
        o.write(data)
    }

    /** PNG: signature, IHDR (its size), an optional eXIf, IDAT, IEND. */
    fun png(w: Long, h: Long, exif: ByteArray? = null, ihdrLength: Int = 13): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteBuffer.allocate(13).putInt(w.toInt()).putInt(h.toInt()).put(8).put(6).put(0).put(0).put(0).array()
        chunk(o, "IHDR", ihdr, ihdrLength)
        exif?.let { chunk(o, "eXIf", it) }
        chunk(o, "IDAT", ByteArray(8))
        chunk(o, "IEND", ByteArray(0))
        return o.toByteArray()
    }

    private fun chunk(o: ByteArrayOutputStream, type: String, data: ByteArray, length: Int = data.size) {
        o.write(ByteBuffer.allocate(4).putInt(length).array())
        o.write(type.toByteArray())
        o.write(data)
        val crc = CRC32().apply { update(type.toByteArray()); update(data) }
        o.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    /** WebP: RIFF, WEBP, then [chunks] (fourcc and data). */
    fun webp(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        for ((cc, data) in chunks) {
            body.write(cc.toByteArray())
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            body.write(data)
            if (data.size % 2 == 1) body.write(0)
        }
        val b = body.toByteArray()
        return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size).array() + b
    }

    /** A lossy key frame of [w] by [h]. */
    fun vp8(w: Int, h: Int): Pair<String, ByteArray> = "VP8 " to (bytes(0x10, 0x02, 0x00, 0x9D, 0x01, 0x2A) + bytes(w and 0xFF, (w shr 8) and 0x3F, h and 0xFF, (h shr 8) and 0x3F) + ByteArray(10))

    fun vp8l(w: Int, h: Int): Pair<String, ByteArray> {
        val bits = ((w - 1) and 0x3FFF) or (((h - 1) and 0x3FFF) shl 14)
        return "VP8L" to (bytes(0x2F) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits).array() + ByteArray(10))
    }

    fun vp8x(w: Int, h: Int): Pair<String, ByteArray> =
        "VP8X" to (bytes(0x08, 0, 0, 0) + bytes((w - 1) and 0xFF, ((w - 1) shr 8) and 0xFF, ((w - 1) shr 16) and 0xFF) +
            bytes((h - 1) and 0xFF, ((h - 1) shr 8) and 0xFF, ((h - 1) shr 16) and 0xFF))

    /** An ISO media file's ftyp box: [major], minor 0, then [compatible]. */
    fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + 4 * compatible.size
        val b = ByteBuffer.allocate(size + 8).putInt(size).put("ftyp".toByteArray()).put(major.toByteArray()).putInt(0)
        for (c in compatible) b.put(c.toByteArray())
        b.putInt(8).put("mdat".toByteArray())
        return b.array()
    }
}
