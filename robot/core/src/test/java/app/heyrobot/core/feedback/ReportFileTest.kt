// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/** 1.4, report a problem (RP1): one RFB-….robotreport, text and an optional PNG, read back exactly or refused. */
class ReportFileTest {
    private val ref = "RFB-ABCD2345"
    private val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val png by lazy { sig + ihdr(2, 2) + idat + iend }
    private val english = "ROBOT FEEDBACK v2\nKind: Bug report\nRef: $ref\n---\nWhat happened:\nThe timer didn't ring 🙁\n"
    private val french = "ROBOT FEEDBACK v2\nKind: Bug report\nRef: $ref\n---\nWhat happened:\nLe minuteur n'a pas sonné à 7 h ; pourquoi ?\n"

    private fun ok(b: ByteArray): ReportFile.Report = (ReportFile.read(b) as? ReportFile.Read.Ok)?.report ?: error("refused: ${ReportFile.read(b)}")
    private fun why(b: ByteArray): ReportFile.Why = (ReportFile.read(b) as ReportFile.Read.Refused).why

    @Test fun textAloneAndWithAPictureReadBackExactly() {
        for (text in listOf(english, french)) {
            val plain = ReportFile.Report(ref, text)
            assertEquals(plain, ok(ReportFile.write(plain)))
            val pic = ReportFile.Report(ref, text, png)
            val back = ok(ReportFile.write(pic))
            assertEquals(text, back.text)
            assertArrayEquals(png, back.png)
            assertEquals(ReportFile.size(pic), ReportFile.write(pic).size.toLong())
        }
        assertEquals(ReportFile.Report(ref, english), ok(ReportFile.write(ReportFile.Report(ref, english, ByteArray(0)))))
    }

    @Test fun theLayoutIsFixed() {
        val b = ReportFile.write(ReportFile.Report(ref, "é", png))
        assertEquals("RBRP", String(b, 0, 4, Charsets.US_ASCII))
        assertEquals(1, b[4].toInt())
        assertEquals(ref, String(b, 5, 12, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0, 0, 0, png.size.toByte()), b.copyOfRange(17, 25))
        assertEquals("é", String(b, 25, 2, Charsets.UTF_8))
        assertEquals("RFB-ABCD2345.robotreport", ReportFile.name(ref))
    }

    @Test fun everyCutShortFileIsRefused() {
        val b = ReportFile.write(ReportFile.Report(ref, french, png))
        for (n in 0 until b.size) {
            val r = ReportFile.read(b.copyOf(n))
            assertEquals("cut at $n", ReportFile.Read.Refused(ReportFile.Why.TRUNCATED), r)
        }
        assertEquals(ReportFile.Why.TRAILING, why(b + byteArrayOf(0)))
    }

    @Test fun tooBigIsRefusedBothWays() {
        val big = ReportFile.Report(ref, "x", png + ByteArray(ReportFile.MAX_PLAIN))
        assertTrue(!ReportFile.fits(big))
        try { ReportFile.write(big); fail() } catch (_: IllegalArgumentException) {}
        val edge = ReportFile.Report(ref, "x".repeat(ReportFile.MAX_PLAIN - ReportFile.HEAD))
        assertEquals(ReportFile.MAX_PLAIN, ReportFile.write(edge).size)
        assertEquals(edge, ok(ReportFile.write(edge)))
        assertEquals(ReportFile.Why.TOO_BIG, why(ByteArray(ReportFile.MAX_PLAIN + 1)))
        assertEquals(ReportFile.Read.Refused(ReportFile.Why.TOO_BIG), ReportFile.read(ByteArrayInputStream(ByteArray(ReportFile.MAX_BYTES * 2))))
        // Lengths that claim more than the cap, even in a short file.
        val lie = ReportFile.write(ReportFile.Report(ref, "x"))
        lie[17] = 0x7F
        assertEquals(ReportFile.Why.TOO_BIG, why(lie))
        lie[17] = 0xFF.toByte()
        assertEquals(ReportFile.Why.TOO_BIG, why(lie))
    }

    @Test fun anythingElseIsRefused() {
        val good = ReportFile.write(ReportFile.Report(ref, english, png))
        assertEquals(ReportFile.Why.NOT_A_REPORT, why("PK\u0003\u0004 a zip".toByteArray()))
        assertEquals(ReportFile.Why.NEWER_VERSION, why(good.copyOf().also { it[4] = 2 }))
        assertEquals(ReportFile.Why.NOT_A_REPORT, why(good.copyOf().also { it[4] = 0 }))
        assertEquals(ReportFile.Why.BAD_REF, why(good.copyOf().also { it[8] = 'a'.code.toByte() }))
        assertEquals(ReportFile.Why.BAD_TEXT, why(good.copyOf().also { it[ReportFile.HEAD] = 0xC3.toByte(); it[ReportFile.HEAD + 1] = 0x28 }))
        assertEquals(ReportFile.Why.BAD_PNG, why(good.copyOf().also { it[good.size - png.size] = 0 }))
        try { ReportFile.write(ReportFile.Report("RFB-1", english)); fail() } catch (_: IllegalArgumentException) {}
        try { ReportFile.write(ReportFile.Report(ref, english, "GIF89a.....".toByteArray())); fail() } catch (_: IllegalArgumentException) {}
        try { ReportFile.name("report"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun theSealIsASeam() {
        val r = ReportFile.Report(ref, french, png)
        // A stand-in for RP5's HPKE seal: the PC tool's open gives back exactly the bytes written.
        val flip = ReportFile.Sealer { p -> byteArrayOf(9) + p.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() }
        val sealed = ReportFile.seal(r, flip)
        assertEquals(ReportFile.Why.NOT_A_REPORT, why(sealed))
        val opened = sealed.copyOfRange(1, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        assertEquals(r, ok(opened))
        try { ReportFile.seal(r, ReportFile.Sealer { ByteArray(ReportFile.MAX_BYTES + 1) }); fail() } catch (_: IllegalStateException) {}
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = java.util.zip.CRC32().apply { update(t); update(data) }.value.toInt()
        return java.nio.ByteBuffer.allocate(12 + data.size).putInt(data.size).put(t).put(data).putInt(crc).array()
    }
    private fun ihdr(w: Int, h: Int, tail: ByteArray = byteArrayOf(8, 6, 0, 0, 0)) = chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(tail).array())
    /** [raw] as a zlib stream, at Deflater's [level] (its default, as ReportFile re-encodes, unless given). */
    private fun z(raw: ByteArray, level: Int = java.util.zip.Deflater.DEFAULT_COMPRESSION): ByteArray = java.util.zip.Deflater(level).run {
        setInput(raw); finish(); val o = ByteArray(raw.size + 64); val n = deflate(o); end(); o.copyOf(n)
    }
    /** 2 by 2 RGBA, 8 bits: two rows of a filter byte (0) and 8 bytes. */
    private val raw = ByteArray(18) { if (it % 9 == 0) 0 else (it * 7).toByte() }
    private val idat = chunk("IDAT", z(raw))
    private val iend = chunk("IEND", ByteArray(0))
    /** A file as a stranger could make one, past [ReportFile.write]'s checks. */
    private fun file(text: String, png: ByteArray = ByteArray(0)): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8)
        return java.nio.ByteBuffer.allocate(ReportFile.HEAD + t.size + png.size).put("RBRP".toByteArray()).put(byteArrayOf(1)).put(ref.toByteArray())
            .putInt(t.size).putInt(png.size).put(t).put(png).array()
    }

    @Test fun thePngHoldsOnlyPixels() {
        assertArrayEquals(png, ok(file(english, png)).png)
        for (meta in listOf("tEXt", "iTXt", "zTXt", "eXIf", "tIME")) {
            val withMeta = sig + ihdr(2, 2) + chunk(meta, "Author\u0000Jane Doe".toByteArray()) + idat + iend
            // Robot's own report drops it; a file that carries one is refused.
            assertArrayEquals(meta, png, ok(ReportFile.write(ReportFile.Report(ref, english, withMeta))).png)
            assertEquals(meta, ReportFile.Why.BAD_PNG, why(file(english, withMeta)))
        }
        // Bytes after IEND, a chunk length that lies, a wrong CRC, IDAT before IHDR, no IEND, no IDAT, a picture too big
        // to open, a critical chunk it doesn't know.
        val bad = listOf(png + "hidden".toByteArray(), png.copyOf().also { it[33] = 1 }, png.copyOf().also { it[41] = (it[41] + 1).toByte() },
            sig + idat + ihdr(2, 2) + iend, sig + ihdr(2, 2) + idat, sig + ihdr(2, 2) + iend, sig + ihdr(100_000, 100_000) + idat + iend,
            sig + ihdr(2, 2) + chunk("ABCD", ByteArray(1)) + idat + iend)
        for ((i, b) in bad.withIndex()) {
            assertEquals("bad $i", ReportFile.Why.BAD_PNG, why(file(english, b)))
            try { ReportFile.write(ReportFile.Report(ref, english, b)); fail("bad $i") } catch (_: IllegalArgumentException) {}
        }
    }

    /** review-develop-14-b M2: a kept chunk has its exact length, PLTE only in a palette picture; any other is dropped. */
    @Test fun keptChunksHoldNoBytesOfTheSenders() {
        fun ok2(b: ByteArray) = ok(file(english, b)).png!!
        fun ok3(b: ByteArray) = ok(ReportFile.write(ReportFile.Report(ref, english, b))).png!!
        val secret = ByteArray(5000) { (0x41 + it % 26).toByte() }
        // On RGBA: an oversized tRNS, sRGB or gAMA, an sRGB past 3, a tRNS at all (RGBA has its own alpha), a PLTE.
        for ((i, c) in listOf(chunk("tRNS", secret), chunk("sRGB", secret), chunk("gAMA", secret), chunk("sRGB", byteArrayOf(9)),
            chunk("tRNS", ByteArray(6)), chunk("PLTE", ByteArray(768) { 0x41 })).withIndex()) {
            val b = sig + ihdr(2, 2) + c + idat + iend
            assertArrayEquals("chunk $i", png, ok2(b))
            assertArrayEquals("chunk $i", png, ok3(b))
        }
        // The right lengths are kept: sRGB 1, gAMA 4; tRNS 2 for grey, 6 for RGB, at most PLTE's entries for a palette.
        val srgb = sig + ihdr(2, 2) + chunk("sRGB", byteArrayOf(0)) + chunk("gAMA", byteArrayOf(0, 0, -79, -113)) + idat + iend
        assertArrayEquals(srgb, ok2(srgb))
        val greyIdat = chunk("IDAT", z(ByteArray(6)))
        val grey = sig + ihdr(2, 2, byteArrayOf(8, 0, 0, 0, 0))
        assertArrayEquals(grey + chunk("tRNS", ByteArray(2)) + greyIdat + iend, ok2(grey + chunk("tRNS", ByteArray(2)) + greyIdat + iend))
        assertArrayEquals(grey + greyIdat + iend, ok2(grey + chunk("tRNS", ByteArray(3)) + greyIdat + iend))
        val rgbIdat = chunk("IDAT", z(ByteArray(14)))
        val rgb = sig + ihdr(2, 2, byteArrayOf(8, 2, 0, 0, 0))
        assertArrayEquals(rgb + chunk("tRNS", ByteArray(6)) + rgbIdat + iend, ok2(rgb + chunk("tRNS", ByteArray(6)) + rgbIdat + iend))
        assertArrayEquals(rgb + rgbIdat + iend, ok2(rgb + chunk("tRNS", ByteArray(7)) + rgbIdat + iend))
        assertArrayEquals(rgb + rgbIdat + iend, ok2(rgb + chunk("PLTE", ByteArray(768) { 0x41 }) + rgbIdat + iend))
        val pal = sig + ihdr(2, 2, byteArrayOf(8, 3, 0, 0, 0)) + chunk("PLTE", ByteArray(6))
        assertArrayEquals(pal + chunk("tRNS", ByteArray(2)) + greyIdat + iend, ok2(pal + chunk("tRNS", ByteArray(2)) + greyIdat + iend))
        assertArrayEquals(pal + greyIdat + iend, ok2(pal + chunk("tRNS", ByteArray(3)) + greyIdat + iend))
        // tRNS before the palette it describes: dropped.
        val early = sig + ihdr(2, 2, byteArrayOf(8, 3, 0, 0, 0)) + chunk("tRNS", ByteArray(1)) + chunk("PLTE", ByteArray(6)) + greyIdat + iend
        assertArrayEquals(pal + greyIdat + iend, ok2(early))
    }

    /** review-develop-14-b L2: the re-encode stops once its packed output passes the cap: the picture is dropped. */
    @Test fun theReEncodeHasACap() {
        assertEquals(ReportFile.MAX_PLAIN - ReportFile.HEAD, ReportFile.MAX_PACKED)
        assertArrayEquals(png, ReportFile.pixels(png, strip = true))
        val big = ByteArray(1 + 4 * 512) { (it * 31 + it / 7).toByte() }.also { it[0] = 0 }
        val wide = sig + ihdr(512, 1) + chunk("IDAT", z(big)) + iend
        assertNotNull(ReportFile.pixels(wide, strip = true, cap = 4096))
        assertNull(ReportFile.pixels(wide, strip = true, cap = 64))
        assertNull(ReportFile.pixels(png, strip = false, cap = 4))
    }

    /** review-fix-report-core-b 9: IHDR's depth and colour type, IDAT's stream and the chunks' order; the pixels re-encoded. */
    @Test fun thePixelsAreCheckedAndReEncoded() {
        fun ok2(b: ByteArray) = ok(file(english, b)).png
        // Stored (level 0) or split over two IDATs: read and written as one IDAT at the default level, the same bytes.
        assertArrayEquals(png, ok2(sig + ihdr(2, 2) + chunk("IDAT", z(raw, 0)) + iend))
        val zr = z(raw)
        assertArrayEquals(png, ok2(sig + ihdr(2, 2) + chunk("IDAT", zr.copyOf(5)) + chunk("IDAT", zr.copyOfRange(5, zr.size)) + iend))
        assertArrayEquals(png, ok(ReportFile.write(ReportFile.Report(ref, english, sig + ihdr(2, 2) + chunk("IDAT", z(raw, 0)) + iend))).png)
        val grey = sig + ihdr(2, 2, byteArrayOf(16, 0, 0, 0, 0)) + chunk("IDAT", z(ByteArray(10))) + iend
        assertArrayEquals(grey, ok2(grey))
        val plte = chunk("PLTE", ByteArray(6))
        val bad = listOf(
            sig + ihdr(2, 2, byteArrayOf(0, 6, 0, 0, 0)) + idat + iend, // depth 0
            sig + ihdr(2, 2, byteArrayOf(8, 7, 0, 0, 0)) + idat + iend, // colour type 7
            sig + ihdr(2, 2, byteArrayOf(16, 3, 0, 0, 0)) + plte + idat + iend, // a 16-bit palette
            sig + ihdr(2, 2, byteArrayOf(8, 6, 1, 0, 0)) + idat + iend, // compression 1
            sig + ihdr(2, 2, byteArrayOf(8, 6, 0, 1, 0)) + idat + iend, // filter method 1
            sig + ihdr(2, 2, byteArrayOf(8, 6, 0, 0, 2)) + idat + iend, // interlace 2
            sig + ihdr(2, 2) + chunk("IDAT", z(raw) + "hidden".toByteArray()) + iend, // bytes after the stream
            sig + ihdr(2, 2) + chunk("IDAT", z(raw.copyOf(17))) + iend, // a row short
            sig + ihdr(2, 2) + chunk("IDAT", z(raw + ByteArray(9))) + iend, // a row more
            sig + ihdr(2, 2) + chunk("IDAT", z(raw.copyOf().also { it[9] = 5 })) + iend, // filter type 5
            sig + ihdr(2, 2) + chunk("IDAT", ByteArray(150) { (it * 7).toByte() }) + iend, // not a zlib stream
            sig + ihdr(2, 2, byteArrayOf(8, 3, 0, 0, 0)) + chunk("IDAT", z(ByteArray(6))) + iend, // a palette picture with no PLTE
            sig + ihdr(2, 2) + idat + plte + iend, // PLTE after IDAT
            sig + ihdr(2, 2) + chunk("gAMA", ByteArray(4)) + chunk("gAMA", ByteArray(4)) + idat + iend, // gAMA twice
            sig + ihdr(2, 2) + chunk("sRGB", ByteArray(1)) + idat + chunk("sRGB", ByteArray(1)) + iend, // sRGB after IDAT
            sig + ihdr(2, 2) + idat + chunk("tEXt", ByteArray(1)) + idat + iend) // IDATs apart
        for ((i, b) in bad.withIndex()) {
            assertEquals("bad $i", ReportFile.Why.BAD_PNG, why(file(english, b)))
            try { ReportFile.write(ReportFile.Report(ref, english, b)); fail("bad $i") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun controlCharactersNeverPass() {
        val dirty = "Line\u001B[2J one\u0007\r\n\u202Eevil\u2066x\u2069\u0085 end\u00A0ok\tyes\n"
        assertEquals("Line[2J one\nevilx end\u00A0ok\tyes\n", ok(ReportFile.write(ReportFile.Report(ref, dirty))).text)
        for (c in listOf('\u001B', '\u0000', '\u0007', '\r', '\u007F', '\u009B', '\u202E', '\u2067', '\u200F'))
            assertEquals("U+%04X".format(c.code), ReportFile.Why.BAD_TEXT, why(file("a${c}b")))
        assertEquals("Le minuteur\tn'a pas sonné\n", ok(file("Le minuteur\tn'a pas sonné\n")).text)
    }

    @Test fun invisibleCharactersNeverPass() {
        val tag = String(Character.toChars(0xE0041))
        val dirty = "a\u2028b\u2029c\u200Bd\u2060e\uFEFFf\u00ADg${tag}h" + 0xD800.toChar() + "i zwj\u200Dok\u200Cyes"
        assertEquals("abcdefghi zwj\u200Dok\u200Cyes", ok(ReportFile.write(ReportFile.Report(ref, dirty))).text)
        for (s in listOf("\u2028", "\u2029", "\u200B", "\u2060", "\uFEFF", "\u00AD", tag))
            assertEquals(ReportFile.Why.BAD_TEXT, why(file("a${s}b")))
        assertEquals("Call [number].", ReportHide.hide("Call 902\u200B555\u200B0123."))
    }
}
