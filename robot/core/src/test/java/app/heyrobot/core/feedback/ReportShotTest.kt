// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RP4: the screenshot's crop and cover, and the PNG it becomes (report-a-problem-plan.md "What's new" 4). */
class ReportShotTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private fun shot(w: Int = 20, h: Int = 10) = ReportShot(w, h, IntArray(w * h) { white })
    /** The PNG read back on its own (8-bit RGB, filter 0, as ReportShot writes it), pixel by pixel. */
    private class Img(val width: Int, val height: Int, val rgb: IntArray) { fun getRGB(x: Int, y: Int) = rgb[y * width + x] }
    private fun decode(png: ByteArray): Img {
        val d = DataInputStream(ByteArrayInputStream(png))
        d.skipBytes(8)
        var w = 0; var h = 0
        val idat = java.io.ByteArrayOutputStream()
        while (d.available() > 0) {
            val len = d.readInt()
            val type = ByteArray(4).also { d.readFully(it) }.toString(Charsets.US_ASCII)
            val data = ByteArray(len).also { d.readFully(it) }
            d.skipBytes(4)
            if (type == "IHDR") { w = java.nio.ByteBuffer.wrap(data).int; h = java.nio.ByteBuffer.wrap(data, 4, 4).int; assertEquals(8, data[8].toInt()); assertEquals(2, data[9].toInt()) }
            if (type == "IDAT") idat.write(data)
        }
        val raw = ByteArray(h * (1 + 3 * w))
        Inflater().apply { setInput(idat.toByteArray()); assertEquals(raw.size, inflate(raw)); end() }
        val rgb = IntArray(w * h) { i ->
            val o = (i / w) * (1 + 3 * w) + 1 + 3 * (i % w)
            assertEquals(0, raw[(i / w) * (1 + 3 * w)].toInt())
            (0xFF shl 24) or ((raw[o].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
        }
        return Img(w, h, rgb)
    }

    private fun chunks(png: ByteArray): List<String> {
        val d = DataInputStream(ByteArrayInputStream(png))
        d.skipBytes(8)
        val out = ArrayList<String>()
        while (d.available() > 0) {
            val len = d.readInt()
            val type = ByteArray(4).also { d.readFully(it) }.toString(Charsets.US_ASCII)
            out += type
            d.skipBytes(len + 4)
        }
        return out
    }

    @Test fun aCoverBoxIsBlackInThePngsPixels() {
        val img = decode(shot().edited(null, listOf(ReportShot.Box(2, 2, 6, 5))).png())
        assertEquals(20, img.width)
        for (x in 2 until 6) for (y in 2 until 5) assertEquals("$x,$y", black, img.getRGB(x, y))
        assertEquals(white, img.getRGB(6, 5))
        assertEquals(white, img.getRGB(15, 8))
    }

    @Test fun cropComesAfterTheCoverInTheWholePicturesPlaces() {
        val img = decode(shot().edited(ReportShot.Box(5, 0, 15, 10), listOf(ReportShot.Box(4, 0, 7, 3), ReportShot.Box(-5, 8, 99, 99))).png())
        assertEquals(10, img.width)
        assertEquals(10, img.height)
        assertEquals(black, img.getRGB(0, 0))
        assertEquals(black, img.getRGB(1, 2))
        assertEquals(white, img.getRGB(2, 0))
        assertEquals(black, img.getRGB(9, 9))
    }

    @Test fun thePngHoldsPixelsOnlyNoTextOrOtherMetadata() {
        val png = shot().png()
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks(png))
        assertNotNull(ReportFile.pixels(png, strip = false))
    }

    @Test fun readGivesBackWhatPngWroteAndRefusesAnythingElse() {
        val s = shot(7, 3).edited(null, listOf(ReportShot.Box(1, 1, 3, 2)))
        val back = ReportShot.read(s.png())!!
        assertEquals(7, back.width)
        assertTrue(s.argb().contentEquals(back.argb()))
        val png = s.png()
        png[png.size - 20] = (png[png.size - 20] + 1).toByte()
        assertEquals(null, ReportShot.read(png))
        assertEquals(null, ReportShot.read(ByteArray(40)))
    }

    @Test fun noneKeptWithUseScreenshotOffOrUnknownOrLocked() {
        assertTrue(ReportShot.mayKeep(useScreenshot = true, locked = false))
        assertFalse(ReportShot.mayKeep(useScreenshot = false, locked = false))
        assertFalse(ReportShot.mayKeep(useScreenshot = null, locked = false))
        assertFalse(ReportShot.mayKeep(useScreenshot = true, locked = true))
    }
}
