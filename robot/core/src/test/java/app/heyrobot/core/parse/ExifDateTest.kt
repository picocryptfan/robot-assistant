// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The date taken and nothing else, from JPEG, PNG and WebP, every offset checked (files-1.4-plan.md §4.3). */
class ExifDateTest {
    private fun read(type: FileType, b: ByteArray) = ExifDate.read(type, ByteBuffer.wrap(b))

    @Test fun theDateTakenFromEachContainer() {
        assertEquals(20260814, read(FileType.JPEG, ParseFixtures.jpeg(10, 10, exif = ParseFixtures.tiff())))
        assertEquals(20260814, read(FileType.JPEG, ParseFixtures.jpeg(10, 10, exif = ParseFixtures.tiff(little = false))))
        assertEquals(20251231, read(FileType.PNG, ParseFixtures.png(4, 4, exif = ParseFixtures.tiff("2025:12:31 23:59:59"))))
        assertEquals(20240229, read(FileType.WEBP, ParseFixtures.webp(ParseFixtures.vp8x(4, 4), "EXIF" to ParseFixtures.tiff("2024:02:29 08:00:00"))))
        val prefixed = "Exif".toByteArray() + byteArrayOf(0, 0) + ParseFixtures.tiff()
        assertEquals(20260814, read(FileType.WEBP, ParseFixtures.webp(ParseFixtures.vp8x(4, 4), "EXIF" to prefixed)))
        // The Exif IFD pointer as an IFD type (13), as some writers put it.
        assertEquals(20260814, read(FileType.JPEG, ParseFixtures.jpeg(10, 10, exif = ParseFixtures.tiff(exifType = 13))))
    }

    @Test fun noDateIsZero() {
        assertEquals(0, read(FileType.JPEG, ParseFixtures.jpeg(10, 10)))
        assertEquals(0, read(FileType.PNG, ParseFixtures.png(4, 4)))
        assertEquals(0, read(FileType.WEBP, ParseFixtures.webp(ParseFixtures.vp8(4, 4))))
        assertEquals(0, read(FileType.HEIC, ParseFixtures.ftyp("heic")))
        assertEquals(0, read(FileType.PDF, "%PDF-1.4".toByteArray()))
    }

    @Test fun anOddBlockGivesNoDateNeverAnError() {
        // A day that doesn't exist, EXIF's blank date, a date of the wrong length.
        assertEquals(0, read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = ParseFixtures.tiff("2026:02:30 00:00:00"))))
        assertEquals(0, read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = ParseFixtures.tiff("    :  :     :  :  "))))
        assertEquals(0, read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = ParseFixtures.tiff("2026"))))
        // Too many entries, an Exif IFD outside the block, one pointing back at IFD0 (a loop), the wrong pointer type.
        assertEquals(0, read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = ParseFixtures.tiff(entries = ExifDate.MAX_ENTRIES + 1))))
        assertEquals(0, read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = ParseFixtures.tiff(exifAt = 100_000))))
        assertEquals(0, read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = ParseFixtures.tiff(exifAt = 8))))
        assertEquals(0, read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = ParseFixtures.tiff(exifType = 3))))
        // The TIFF block cut short at every length: never an exception.
        val t = ParseFixtures.tiff()
        for (n in 0 until 76) assertEquals("$n", 0, read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = t.copyOf(n))))
        // Random bytes after "Exif": never an exception.
        val r = java.util.Random(7)
        repeat(300) {
            val junk = ByteArray(r.nextInt(200)).also(r::nextBytes)
            read(FileType.JPEG, ParseFixtures.jpeg(1, 1, exif = junk))
            read(FileType.PNG, ParseFixtures.png(1, 1, exif = junk))
        }
    }

    @Test fun datesAreRealDays() {
        assertEquals(20260814, ExifDate.parse("2026:08:14 12:34:56"))
        assertEquals(20260814, ExifDate.parse("2026:08:14"))
        assertEquals(0, ExifDate.parse("2026-08-14 12:34:56"))
        assertEquals(0, ExifDate.parse("2026:13:01 00:00:00"))
        assertEquals(0, ExifDate.parse("1899:12:31 00:00:00"))
        assertEquals(0, ExifDate.parse("2100:02:29 00:00:00"))
        assertEquals(20000229, ExifDate.parse("2000:02:29 00:00:00"))
        assertEquals(0, ExifDate.parse("+026:08:14 12:34:56"))
        assertEquals("2026-08-14", ExifDate.iso(20260814))
        assertNull(ExifDate.iso(0))
        assertNull(ExifDate.iso(20260230))
        assertNull(ExifDate.iso(-1))
        assertNull(ExifDate.iso(99999999))
    }
}
