// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import app.heyrobot.core.parse.ParseFixtures.bytes
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An image's declared size from its header alone, every step bounded: what :files refuses before decoding. */
class ImageHeaderTest {
    @Test fun jpegsFirstFrameHeader() {
        assertEquals(Dim(4032, 3024), ImageHeader.dims(FileType.JPEG, ParseFixtures.jpeg(4032, 3024)))
        assertEquals(Dim(800, 600), ImageHeader.dims(FileType.JPEG, ParseFixtures.jpeg(800, 600, sof = 0xC2)))
        assertEquals(Dim(1, 65535), ImageHeader.dims(FileType.JPEG, ParseFixtures.jpeg(1, 65535, exif = ParseFixtures.tiff())))
        // DHT (C4), JPG (C8) and DAC (CC) aren't frames: a JPEG whose only "frame" is one has none.
        for (m in listOf(0xC4, 0xC8, 0xCC)) assertNull(ImageHeader.jpeg(ByteBuffer.wrap(ParseFixtures.jpeg(10, 10, sof = m))))
        // Fill bytes before a marker are allowed.
        val j = ParseFixtures.jpeg(20, 30)
        val filled = j.copyOfRange(0, 2) + bytes(0xFF, 0xFF) + j.copyOfRange(2, j.size)
        assertEquals(Dim(20, 30), ImageHeader.jpeg(ByteBuffer.wrap(filled)))
    }

    @Test fun aJpegThatCantBeReadHasNoSize() {
        val j = ParseFixtures.jpeg(640, 480)
        // Cut anywhere before the end of the frame header's segment (marker, length 11, 9 bytes): no size.
        val sofAt = (0 until j.size - 1).first { (j[it].toInt() and 0xFF) == 0xFF && (j[it + 1].toInt() and 0xFF) == 0xC0 }
        for (n in 0 until sofAt + 13) assertNull("$n", ImageHeader.jpeg(ByteBuffer.wrap(j.copyOf(n))))
        assertEquals(Dim(640, 480), ImageHeader.jpeg(ByteBuffer.wrap(j.copyOf(sofAt + 13))))
        // A zero size (DNL's), a scan before any frame, garbage between segments, a segment longer than the file.
        assertNull(ImageHeader.jpeg(ByteBuffer.wrap(ParseFixtures.jpeg(0, 480))))
        assertNull(ImageHeader.jpeg(ByteBuffer.wrap(bytes(0xFF, 0xD8, 0xFF, 0xDA, 0, 8, 1, 1, 0, 0, 0x3F, 0))))
        assertNull(ImageHeader.jpeg(ByteBuffer.wrap(bytes(0xFF, 0xD8, 0x00, 0xFF, 0xC0, 0, 11, 8, 0, 10, 0, 10, 1, 1, 0x11, 0))))
        assertNull(ImageHeader.jpeg(ByteBuffer.wrap(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x7F, 0xFF, 1, 2, 3))))
        assertNull(ImageHeader.jpeg(ByteBuffer.wrap(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 1))))
        // Endless segments: refused after MAX_JPEG_SEGMENTS.
        assertNull(ImageHeader.jpeg(ByteBuffer.wrap(ParseFixtures.jpeg(10, 10, extraSegments = ImageHeader.MAX_JPEG_SEGMENTS))))
        assertEquals(Dim(10, 10), ImageHeader.jpeg(ByteBuffer.wrap(ParseFixtures.jpeg(10, 10, extraSegments = ImageHeader.MAX_JPEG_SEGMENTS - 5))))
    }

    /** The hostile PNG: a few bytes that declare 100,000 by 100,000 pixels. The header says so; the caps refuse it. */
    @Test fun aPngWithAHugeDeclaredSizeIsReadAndRefusedByTheCaps() {
        val huge = ParseFixtures.png(100_000, 100_000)
        assertTrue(huge.size < 100)
        val d = ImageHeader.dims(FileType.PNG, huge)!!
        assertEquals(Dim(100_000, 100_000), d)
        assertFalse(ParseLimits.sourceOk(d))
        assertFalse(ParseLimits.sourceOk(Dim(Int.MAX_VALUE, 1)))
        assertFalse(ParseLimits.sourceOk(Dim(16_000, 16_300)))
        assertTrue(ParseLimits.sourceOk(Dim(16_320, 12_240)))
        assertTrue(ParseLimits.sourceOk(Dim(8_160, 6_120)))
    }

    @Test fun aPngThatCantBeReadHasNoSize() {
        assertEquals(Dim(3, 7), ImageHeader.dims(FileType.PNG, ParseFixtures.png(3, 7)))
        assertNull(ImageHeader.dims(FileType.PNG, ParseFixtures.png(0, 7)))
        assertNull(ImageHeader.dims(FileType.PNG, ParseFixtures.png(0x80000000L, 7)))
        assertNull(ImageHeader.dims(FileType.PNG, ParseFixtures.png(3, 7, ihdrLength = 14)))
        assertNull(ImageHeader.dims(FileType.PNG, ParseFixtures.png(3, 7).copyOf(23)))
        val wrongFirst = ParseFixtures.png(3, 7).also { it[12] = 'X'.code.toByte() }
        assertNull(ImageHeader.dims(FileType.PNG, wrongFirst))
    }

    @Test fun webpsThreeHeaders() {
        assertEquals(Dim(1000, 750), ImageHeader.dims(FileType.WEBP, ParseFixtures.webp(ParseFixtures.vp8(1000, 750))))
        assertEquals(Dim(16383, 2), ImageHeader.dims(FileType.WEBP, ParseFixtures.webp(ParseFixtures.vp8l(16383, 2))))
        assertEquals(Dim(16384, 9000), ImageHeader.dims(FileType.WEBP, ParseFixtures.webp(ParseFixtures.vp8x(16384, 9000))))
        // Not a key frame, a wrong start code, a VP8L without its signature, an unknown first chunk: no size.
        val inter = ParseFixtures.webp(ParseFixtures.vp8(10, 10)).also { it[20] = 0x11 }
        assertNull(ImageHeader.dims(FileType.WEBP, inter))
        val code = ParseFixtures.webp(ParseFixtures.vp8(10, 10)).also { it[23] = 0 }
        assertNull(ImageHeader.dims(FileType.WEBP, code))
        val sig = ParseFixtures.webp(ParseFixtures.vp8l(10, 10)).also { it[20] = 0 }
        assertNull(ImageHeader.dims(FileType.WEBP, sig))
        assertNull(ImageHeader.dims(FileType.WEBP, ParseFixtures.webp("ALPH" to ByteArray(20))))
        assertNull(ImageHeader.dims(FileType.WEBP, ParseFixtures.webp(ParseFixtures.vp8(10, 10)).copyOf(29)))
    }

    @Test fun heicAndOtherTypesHaveNoHeaderHere() {
        assertNull(ImageHeader.dims(FileType.HEIC, ParseFixtures.ftyp("heic")))
        assertNull(ImageHeader.dims(FileType.PDF, "%PDF-1.4".toByteArray()))
        // A header that says JPEG but holds a PNG: no size (and no decode).
        assertNull(ImageHeader.dims(FileType.JPEG, ParseFixtures.png(5, 5)))
    }
}
