// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * QrReader (job L4): Robot's own reader of the codes Robot's QrCode writes, as :files reads another Robot's pairing code from
 * a photo. Codes are drawn here as a camera would see a phone's screen: turned, tilted, small or large in the frame, soft,
 * noisy, unevenly lit, low in contrast, light on dark, mirrored. Every version and mask reads back exactly; Reed-Solomon
 * corrects what it should and nothing past it; what isn't a code Robot writes, or is too damaged, reads as nothing, never as
 * other text. Random photos never throw and finish quickly.
 */
class QrReaderTest {
    /** A pairing code's length when shown in person (PairCode: 159 characters at most, version 8). */
    private val pairing = "ROBOT-PAIR-" + text(148, 7)

    private class Look(
        val module: Double = 6.0, val angle: Double = 0.0, val tilt: Double = 0.0, val dark: Int = 25, val light: Int = 230, val noise: Int = 0,
        val blur: Int = 0, val gradient: Int = 0, val invert: Boolean = false, val mirror: Boolean = false, val w: Int = 1_280, val h: Int = 960,
        val cx: Double = 0.5, val cy: Double = 0.5, val seed: Long = 1,
    )

    /** [q] photographed as [l] says: luminance, one byte a pixel. */
    private fun photo(q: QrCode, l: Look): ByteArray {
        val n = q.size
        val half = n / 2.0
        val k = l.tilt / (l.h / 2.0)
        val ca = cos(Math.toRadians(l.angle))
        val sa = sin(Math.toRadians(l.angle))
        val out = DoubleArray(l.w * l.h)
        val ss = 2
        for (v in 0 until l.h) for (u in 0 until l.w) {
            var acc = 0.0
            for (sy in 0 until ss) for (sx in 0 until ss) {
                var X = u + (sx + 0.5) / ss - l.cx * l.w
                val Y = v + (sy + 0.5) / ss - l.cy * l.h
                if (l.mirror) X = -X
                // Undo the tilt, then the turn and the scale: the point in the code's own modules.
                val d = 1 - k * Y
                if (d <= 0.05) { acc += 0.0; continue }
                val x = X / d
                val y = Y / d
                val mx = (ca * x + sa * y) / l.module + half
                val my = (-sa * x + ca * y) / l.module + half
                val ix = floor(mx).toInt()
                val iy = floor(my).toInt()
                acc += if (ix in 0 until n && iy in 0 until n && q.dark(ix, iy)) 1.0 else 0.0
            }
            out[v * l.w + u] = acc / (ss * ss)
        }
        val r = Random(l.seed)
        val blurred = if (l.blur > 0) box(out, l.w, l.h, l.blur) else out
        return ByteArray(l.w * l.h) { p ->
            val ink = blurred[p]
            var y = l.light + (l.dark - l.light) * ink
            y += l.gradient * ((p % l.w).toDouble() / l.w - 0.5) * 2
            if (l.noise > 0) y += (r.nextInt(2 * l.noise + 1) - l.noise)
            if (l.invert) y = 255 - y
            y.toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun box(src: DoubleArray, w: Int, h: Int, r: Int): DoubleArray {
        val tmp = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0; var c = 0
            for (d in -r..r) { val xx = x + d; if (xx in 0 until w) { s += src[y * w + xx]; c++ } }
            tmp[y * w + x] = s / c
        }
        val out = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0; var c = 0
            for (d in -r..r) { val yy = y + d; if (yy in 0 until h) { s += tmp[yy * w + x]; c++ } }
            out[y * w + x] = s / c
        }
        return out
    }

    private fun read(text: String, l: Look): String? = QrReader.readLuma(photo(QrCode.encode(text)!!, l), l.w, l.h)

    private fun text(n: Int, seed: Int = n): String {
        val r = Random(seed.toLong())
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return String(CharArray(n) { abc[r.nextInt(abc.length)] })
    }

    @Test fun everyVersionReadsBackFromAPhoto() {
        val full = listOf(17, 32, 53, 78, 106, 134, 154, 192, 230, 271)
        for ((i, n) in full.withIndex()) {
            val t = text(n)
            assertEquals(i + 1, QrCode.encode(t)!!.version)
            assertEquals("version ${i + 1}", t, read(t, Look(module = 7.0, angle = 3.0)))
        }
        // UTF-8, as QrCode writes it.
        assertEquals("Café ☕ à Montréal", read("Café ☕ à Montréal", Look()))
    }

    /** Every version and mask, read straight from the modules (no photo): exactly the text. */
    @Test fun everyGridReadsExactly() {
        var masks = 0
        for (n in listOf(1, 10, 17, 18, 32, 33, 53, 54, 78, 79, 106, 107, 134, 135, 154, 155, 192, 193, 230, 231, 271)) for (s in 0 until 6) {
            val t = text(n, n * 31 + s)
            val q = QrCode.encode(t)!!
            masks = masks or (1 shl q.mask)
            assertEquals(t, QrReader.Grid.read(Array(q.size) { y -> BooleanArray(q.size) { x -> q.dark(x, y) } }))
        }
        assertEquals(0xFF, masks)
        assertEquals("", QrReader.Grid.read(QrCode.encode("")!!.let { q -> Array(q.size) { y -> BooleanArray(q.size) { x -> q.dark(x, y) } } }))
    }

    @Test fun aPairingCodeReadsTheWaysACameraSeesIt() {
        assertEquals(159, pairing.length)
        assertEquals(8, QrCode.encode(pairing)!!.version)
        val looks = listOf(
            Look(), Look(angle = 7.0), Look(angle = 30.0), Look(angle = 45.0), Look(angle = 90.0), Look(angle = 135.0), Look(angle = 180.0), Look(angle = 270.0),
            Look(angle = 333.0, tilt = 0.2), Look(tilt = 0.3), Look(tilt = -0.3, angle = 12.0), Look(module = 4.0, angle = 20.0), Look(module = 10.0, angle = 5.0),
            Look(noise = 25, blur = 1), Look(gradient = 60, angle = 15.0), Look(dark = 90, light = 165, noise = 8), Look(invert = true), Look(mirror = true, angle = 10.0),
            Look(cx = 0.3, cy = 0.35, module = 5.0), Look(module = 3.5, w = 1_600, h = 1_200, noise = 6, blur = 1),
        )
        for ((i, l) in looks.withIndex()) assertEquals("look $i", pairing, read(pairing, l))
    }

    @Test fun whatIsntACodeRobotWritesReadsAsNothing() {
        // Blank, noise, a gradient.
        assertNull(QrReader.readLuma(ByteArray(640 * 480) { 0xEE.toByte() }, 640, 480))
        val r = Random(9)
        assertNull(QrReader.readLuma(ByteArray(640 * 480).also(r::nextBytes), 640, 480))
        assertNull(QrReader.readLuma(ByteArray(640 * 480) { (it % 640 / 3).toByte() }, 640, 480))
        // Too small to hold one, or the wrong size of buffer.
        assertNull(QrReader.readLuma(ByteArray(39 * 100), 39, 100))
        assertNull(QrReader.readLuma(ByteArray(100), 50, 50))
        assertNull(QrReader.read(ByteArray(4 * 50 * 50 - 1), 50, 50))
        // Another error correction level's format information: not a code Robot writes.
        val q = QrCode.encode(pairing)!!
        val m = Array(q.size) { y -> BooleanArray(q.size) { x -> q.dark(x, y) } }
        val levelM = formatWord(0, q.mask)
        for (i in 0 until 15) {
            val bit = (levelM ushr i) and 1 == 1
            val (x1, y1) = when { i <= 5 -> 8 to i; i == 6 -> 8 to 7; i == 7 -> 8 to 8; i == 8 -> 7 to 8; else -> 14 - i to 8 }
            val (x2, y2) = if (i <= 7) q.size - 1 - i to 8 else 8 to q.size - 15 + i
            m[y1][x1] = bit; m[y2][x2] = bit
        }
        assertNull(QrReader.Grid.read(m))
        // Damage past what Reed-Solomon corrects: nothing, never other text.
        val rr = Random(3)
        repeat(50) {
            val d = Array(q.size) { y -> BooleanArray(q.size) { x -> q.dark(x, y) } }
            repeat(q.size * q.size / 4) { val x = rr.nextInt(q.size); val y = rr.nextInt(q.size); d[y][x] = !d[y][x] }
            val got = QrReader.Grid.read(d)
            assertTrue(got == null || got == pairing)
        }
        // A few modules wrong: corrected.
        val few = Array(q.size) { y -> BooleanArray(q.size) { x -> q.dark(x, y) } }
        for ((x, y) in listOf(20 to 30, 25 to 40, 40 to 22, 33 to 33)) few[y][x] = !few[y][x]
        assertEquals(pairing, QrReader.Grid.read(few))
    }

    private fun formatWord(level: Int, mask: Int): Int {
        val data = (level shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        return ((data shl 10) or rem) xor 0x5412
    }

    /** Reed-Solomon, against QrCode's own encoder: up to the level's capacity corrected exactly; past it, never a wrong codeword. */
    @Test fun reedSolomonCorrectsWhatItShould() {
        val r = Random(11)
        for (ecc in listOf(7, 10, 15, 18, 20, 24, 26, 30)) {
            val divisor = QrCode.rsDivisor(ecc)
            repeat(200) {
                val data = ByteArray(10 + r.nextInt(80)).also(r::nextBytes)
                val rem = QrCode.rsRemainder(data, divisor)
                val good = IntArray(data.size + ecc) { i -> (if (i < data.size) data[i] else rem[i - data.size]).toInt() and 0xFF }
                val t = r.nextInt(ecc / 2 + 1)
                val c = good.copyOf()
                for (p in (0 until c.size).shuffled(kotlin.random.Random(r.nextLong())).take(t)) c[p] = c[p] xor (1 + r.nextInt(255))
                assertTrue(QrReader.ReedSolomon.correct(c, ecc, ecc / 2))
                assertArrayEquals(good, c)
                // Past the capacity: refused, or (rarely) another codeword, which the strict data reading then refuses.
                val over = good.copyOf()
                for (p in (0 until over.size).shuffled(kotlin.random.Random(r.nextLong())).take(ecc / 2 + 1)) over[p] = over[p] xor (1 + r.nextInt(255))
                QrReader.ReedSolomon.correct(over, ecc, ecc / 2)
            }
        }
        // The level's own limit: fewer than the codewords allow for versions 1 to 3 (ISO's misdecode protection).
        val divisor = QrCode.rsDivisor(7)
        val data = ByteArray(19) { it.toByte() }
        val rem = QrCode.rsRemainder(data, divisor)
        val good = IntArray(26) { i -> (if (i < 19) data[i] else rem[i - 19]).toInt() and 0xFF }
        val three = good.copyOf().also { it[0] = it[0] xor 1; it[5] = it[5] xor 2; it[9] = it[9] xor 3 }
        assertFalse(QrReader.ReedSolomon.correct(three.copyOf(), 7, (7 - 3) / 2))
        assertTrue(QrReader.ReedSolomon.correct(three, 7, 3))
    }

    /** Random photos, odd sizes: never an exception, and a 3-megapixel one of noise read in well under the reader's watchdog. */
    @Test fun randomPhotosNeverThrowAndFinishQuickly() {
        val r = Random(12)
        repeat(60) {
            val w = 40 + r.nextInt(300)
            val h = 40 + r.nextInt(300)
            val b = ByteArray(w * h)
            when (r.nextInt(3)) {
                0 -> r.nextBytes(b)
                1 -> for (i in b.indices) b[i] = if (r.nextInt(5) == 0) 0 else -1
                else -> for (i in b.indices) b[i] = if ((i % w / (1 + r.nextInt(9)) + i / w / 3) % 2 == 0) 0 else -1
            }
            QrReader.readLuma(b, w, h)
            QrReader.read(ByteArray(w * h * 4).also(r::nextBytes), w, h)
        }
        // Finder-like stripes everywhere: many candidates, bounded work.
        val w = 2_048
        val h = 1_536
        val stripes = ByteArray(w * h) { p -> val x = p % w; if (listOf(0, 2, 3, 4, 6).contains(x % 7)) 0 else -1 }
        val noise = ByteArray(w * h).also(r::nextBytes)
        for (img in listOf(stripes, noise)) {
            val t0 = System.nanoTime()
            assertNull(QrReader.readLuma(img, w, h))
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("$ms ms", ms < 5_000)
        }
    }
}
