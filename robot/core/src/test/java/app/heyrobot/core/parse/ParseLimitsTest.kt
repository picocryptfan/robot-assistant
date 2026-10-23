// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sizes :files decodes and renders to, the scan's corners, and which copy of a file leaves the phone. */
class ParseLimitsTest {
    @Test fun anImageFitsItsBoxEitherWayRoundAndIsNeverEnlarged() {
        assertEquals(Dim(1024, 768), ParseLimits.fit(Dim(4032, 3024), ParseLimits.PAGE))
        assertEquals(Dim(768, 1024), ParseLimits.fit(Dim(3024, 4032), ParseLimits.PAGE))
        assertEquals(Dim(2400, 1600), ParseLimits.fit(Dim(6000, 4000), ParseLimits.VIEW_PAGE))
        assertEquals(Dim(1600, 2400), ParseLimits.fit(Dim(4000, 6000), ParseLimits.VIEW_PAGE))
        assertEquals(Dim(1600, 2133), ParseLimits.fit(Dim(3000, 4000), ParseLimits.VIEW_PAGE))
        assertEquals(Dim(500, 300), ParseLimits.fit(Dim(500, 300), ParseLimits.PAGE))
        // A PDF page's vectors may be drawn larger: a 612 by 792 point page fills the box.
        assertEquals(Dim(791, 1024), ParseLimits.fit(Dim(612, 792), ParseLimits.PAGE, up = true))
        assertTrue(ParseLimits.PAGE.holds(ParseLimits.fit(Dim(612, 792), ParseLimits.PAGE, up = true)))
    }

    /** A page of 1 by 14,400 points, or a 1 by 32,000 pixel strip: clamped to MIN_EDGE, never 0 px, never outside the box. */
    @Test fun anAbsurdAspectIsClampedNotRenderedToNothing() {
        for (src in listOf(Dim(1, 14_400), Dim(14_400, 1), Dim(1, 32_768), Dim(32_768, 3), Dim(1, 1))) {
            for (box in listOf(ParseLimits.PAGE, ParseLimits.THUMB, ParseLimits.VIEW_PAGE, ParseLimits.VIEW_THUMB, ParseLimits.SCAN_PAGE)) {
                for (up in listOf(false, true)) {
                    val d = ParseLimits.fit(src, box, up)
                    assertTrue("$src $box $up -> $d", d.short >= ParseLimits.MIN_EDGE && box.holds(d))
                }
            }
        }
    }

    @Test fun aCopyIsScaledToSixteenMegapixels() {
        assertEquals(Dim(4000, 3000), ParseLimits.fitPixels(Dim(4000, 3000), ParseLimits.COPY_PIXELS))
        val big = ParseLimits.fitPixels(Dim(16_320, 12_240), ParseLimits.COPY_PIXELS)
        assertTrue(big.pixels <= ParseLimits.COPY_PIXELS && big.pixels > ParseLimits.COPY_PIXELS * 99 / 100)
        assertEquals(16_320.0 / 12_240, big.width.toDouble() / big.height, 0.01)
        val strip = ParseLimits.fitPixels(Dim(32_768, 1), 1_000)
        assertTrue(strip.pixels <= 1_000 || strip.short == ParseLimits.MIN_EDGE)
    }

    @Test fun aScannedPageIsTwoHundredPixelsAnInch() {
        assertEquals(Dim(864, 612), ParseLimits.scanPoints(Dim(2400, 1700)))
        assertEquals(Dim(1, 1), ParseLimits.scanPoints(Dim(1, 1)))
        assertEquals(ParseLimits.MAX_PDF_POINTS, ParseLimits.scanPoints(Dim(1_000_000, 10)).width)
    }

    @Test fun aScansCornersAreClockwiseConvexAndEncloseEnough() {
        val src = Dim(3000, 4000)
        val square = ScanQuad.of(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)!!
        assertTrue(ScanGeometry.valid(square, src))
        assertEquals(Dim(1800, 2400), ScanGeometry.page(square, src, ParseLimits.SCAN_PAGE))
        val tilted = ScanQuad.of(0.15f, 0.05f, 0.95f, 0.12f, 0.85f, 0.97f, 0.02f, 0.9f)!!
        assertTrue(ScanGeometry.valid(tilted, src))
        assertTrue(ParseLimits.SCAN_PAGE.holds(ScanGeometry.page(tilted, src, ParseLimits.SCAN_PAGE)!!))
        // Counter-clockwise, crossed (a bow tie), three in a line, a fold, too small: refused.
        val ccw = ScanQuad.of(0.1f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.9f, 0.1f)!!
        val bowTie = ScanQuad.of(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f)!!
        val line = ScanQuad.of(0.1f, 0.1f, 0.5f, 0.1f, 0.9f, 0.1f, 0.1f, 0.9f)!!
        val fold = ScanQuad.of(0.1f, 0.1f, 0.9f, 0.1f, 0.2f, 0.2f, 0.1f, 0.9f)!!
        val tiny = ScanQuad.of(0.5f, 0.5f, 0.55f, 0.5f, 0.55f, 0.55f, 0.5f, 0.55f)!!
        for (q in listOf(ccw, bowTie, line, fold, tiny)) {
            assertFalse(ScanGeometry.valid(q, src))
            assertNull(ScanGeometry.page(q, src, ParseLimits.SCAN_PAGE))
        }
        // A corner outside the photo, or not a number: no quad at all.
        assertNull(ScanQuad.of(-0.1f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        assertNull(ScanQuad.of(0f, 0f, 1.01f, 0f, 1f, 1f, 0f, 1f))
        assertNull(ScanQuad.of(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        assertNull(ScanQuad.of(0f, 0f, 1f, 0f, 1f, 1f))
    }

    @Test fun whatLeavesThePhoneFollowsStripLocation() {
        assertEquals(OutgoingCopy.STRIPPED, Outgoing.copy(true, FileType.JPEG))
        assertEquals(OutgoingCopy.STRIPPED, Outgoing.copy(true, FileType.HEIC))
        assertEquals(OutgoingCopy.CANT_STRIP, Outgoing.copy(true, FileType.GIF))
        assertEquals(OutgoingCopy.CANT_STRIP, Outgoing.copy(true, FileType.MP4))
        assertEquals(OutgoingCopy.ORIGINAL, Outgoing.copy(true, FileType.PDF))
        assertEquals(OutgoingCopy.ORIGINAL, Outgoing.copy(true, FileType.TEXT))
        for (t in FileType.entries) assertEquals(OutgoingCopy.ORIGINAL, Outgoing.copy(false, t))
    }
}
