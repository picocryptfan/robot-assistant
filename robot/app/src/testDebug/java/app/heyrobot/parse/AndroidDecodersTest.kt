// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.parse

import android.graphics.Bitmap
import android.graphics.Color
import app.heyrobot.core.parse.Dim
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ImageHeader
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ScanQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The platform's image calls behind FileParser's seam, run by Robolectric's native graphics (the phone's Skia and
 * ImageDecoder, built for the desktop): the target size is set from the header before decoding, a bomb's header is
 * refused there, outputs come out at the size asked, as 8-bit sRGB. PdfRenderer and PdfDocument aren't in Robolectric:
 * those, and every call in an isolated process, are F0b's to check on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidDecodersTest {
    private val dec = AndroidDecoders()

    private fun photo(w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.rgb(200, 120, 40))
        return ByteArrayOutputStream().also { b.compress(format, 90, it) }.toByteArray()
    }

    @Test fun anImageIsDecodedToItsBoxFromItsHeader() {
        val j = photo(2000, 1500)
        assertEquals(Dim(2000, 1500), ImageHeader.jpeg(ByteBuffer.wrap(j)))
        dec.decode(ByteBuffer.wrap(j), ParseLimits.PAGE).use { p ->
            assertEquals(1024 to 768, p.width to p.height)
            val out = p.jpeg(ParseLimits.PAGE_QUALITY, ParseLimits.MAX_PAGE_JPEG)!!
            assertEquals(FileType.JPEG, FileSniff.sniff(out, out.size.toLong()))
            assertEquals(Dim(1024, 768), ImageHeader.jpeg(ByteBuffer.wrap(out)))
            p.scaled(ParseLimits.THUMB).use { t ->
                assertEquals(320 to 240, t.width to t.height)
                val thumb = t.jpeg(80, ParseLimits.MAX_THUMB_JPEG)!!
                assertTrue(thumb.size <= ParseLimits.MAX_THUMB_JPEG)
            }
            val px = ByteBuffer.allocate(1024 * 768 * 4)
            p.rgba(px)
            assertEquals(1024 * 768 * 4, px.position())
            // Too small a cap: none, never a cut file.
            assertNull(p.jpeg(90, 100))
        }
        // A PNG, through the same path; a stripped copy of it stays a PNG.
        val png = photo(300, 200, Bitmap.CompressFormat.PNG)
        dec.decodePixels(ByteBuffer.wrap(png), ParseLimits.COPY_PIXELS).use { p ->
            assertEquals(300 to 200, p.width to p.height)
            val copy = p.png(ParseLimits.MAX_COPY)!!
            assertEquals(Dim(300, 200), ImageHeader.png(ByteBuffer.wrap(copy)))
        }
    }

    @Test fun aScanIsStraightenedToItsPageSize() {
        val j = photo(1200, 1600)
        dec.decode(ByteBuffer.wrap(j), ParseLimits.SCAN_SOURCE).use { p ->
            val quad = ScanQuad.of(0.1f, 0.1f, 0.9f, 0.12f, 0.88f, 0.9f, 0.08f, 0.88f)!!
            p.straightened(quad, Dim(900, 1200)).use { s ->
                assertEquals(900 to 1200, s.width to s.height)
                assertNotNull(s.jpeg(ParseLimits.SCAN_QUALITY, ParseLimits.MAX_SCAN_JPEG))
            }
        }
    }

    /** A real PNG header declaring 100,000 by 100,000 pixels: refused in the decoder's header callback, nothing decoded. */
    @Test fun aBombsHeaderIsRefusedBeforeDecoding() {
        val bomb = pngHeader(100_000, 100_000)
        assertTrue(runCatching { dec.decode(ByteBuffer.wrap(bomb), ParseLimits.PAGE) }.exceptionOrNull() is TooLarge)
        // A broken file isn't shown half-read.
        val j = photo(400, 300)
        assertTrue(runCatching { dec.decode(ByteBuffer.wrap(j.copyOf(j.size / 2)), ParseLimits.PAGE) }.isFailure)
        // The platform header's size (as HEIC's is read).
        assertEquals(Dim(400, 300), dec.size(ByteBuffer.wrap(j)))
    }

    private fun pngHeader(w: Int, h: Int): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            o.write(ByteBuffer.allocate(4).putInt(data.size).array())
            o.write(type.toByteArray())
            o.write(data)
            o.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(type.toByteArray()); update(data) }.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(6).put(0).put(0).put(0).array())
        val z = Deflater().apply { setInput(ByteArray(64)); finish() }
        val buf = ByteArray(128)
        chunk("IDAT", buf.copyOf(z.deflate(buf)))
        chunk("IEND", ByteArray(0))
        return o.toByteArray()
    }
}
