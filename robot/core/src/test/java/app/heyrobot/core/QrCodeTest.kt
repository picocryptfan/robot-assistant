// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * QrCode, read back the way a scanner does: the format information from both copies, the version information (7 and
 * up), the mask taken off, the codewords de-interleaved, every block's Reed-Solomon syndromes zero (computed here, not
 * with the encoder's divisor), then the byte-mode text. The codes pinned at the end are ones zbarimg 0.23.93 decoded to
 * exactly their text on 19 Oct 2026 (Ko-fi's link, version 2, and full codes of versions 6, 7 and 10); the donation
 * addresses' codes are pinned in app's SupportAddressesTest.
 */
class QrCodeTest {
    /** Exp and log tables of GF(2^8) mod 0x11D, for the syndromes. */
    private val exp = IntArray(512).also { e -> var x = 1; for (i in 0 until 255) { e[i] = x; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }; for (i in 255 until 512) e[i] = e[i - 255] }

    /** The value of polynomial [c] (highest power first) at α^[i]. */
    private fun syndrome(c: IntArray, i: Int): Int {
        var s = 0
        for (b in c) s = (if (s == 0) 0 else exp[(log(s) + i) % 255]) xor b
        return s
    }
    private val logs = IntArray(256).also { l -> for (i in 0 until 255) l[exp[i]] = i }
    private fun log(v: Int): Int = logs[v]

    private class Read(val text: String, val version: Int, val mask: Int)

    private fun read(q: QrCode): Read {
        val size = q.size
        val version = (size - 17) / 4
        assertEquals(size, version * 4 + 17)
        // The three finder patterns: a dark ring, a light ring, a dark 3×3 centre, with a light separator.
        for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) for (dy in -4..4) for (dx in -4..4) {
            val ring = maxOf(Math.abs(dx), Math.abs(dy))
            if (cx + dx in 0 until size && cy + dy in 0 until size) assertEquals("finder at $cx,$cy", ring != 2 && ring != 4, q.dark(cx + dx, cy + dy))
        }
        for (i in 8 until size - 8) { assertEquals(i % 2 == 0, q.dark(6, i)); assertEquals(i % 2 == 0, q.dark(i, 6)) }
        assertTrue("the dark module", q.dark(8, size - 8))
        // Format information, both copies.
        val first = (0..5).map { 8 to it } + listOf(8 to 7, 8 to 8, 7 to 8) + (9 until 15).map { (14 - it) to 8 }
        val second = (0 until 8).map { (size - 1 - it) to 8 } + (8 until 15).map { 8 to (size - 15 + it) }
        fun bits(at: List<Pair<Int, Int>>) = at.withIndex().sumOf { (i, p) -> if (q.dark(p.first, p.second)) 1 shl i else 0 }
        val format = bits(first)
        assertEquals(format, bits(second))
        val raw = format xor 0x5412
        assertEquals("level L", 1, raw ushr 13)
        val mask = (raw ushr 10) and 7
        // Its BCH code: the 15 bits are a multiple of the generator 0x537.
        var rem = raw
        for (i in 14 downTo 10) if ((rem ushr i) and 1 == 1) rem = rem xor (0x537 shl (i - 10))
        assertEquals("format BCH", 0, rem)
        if (version >= 7) {
            val a = (0 until 18).map { (size - 11 + it % 3) to (it / 3) }
            val b = (0 until 18).map { (it / 3) to (size - 11 + it % 3) }
            val v = bits(a)
            assertEquals(v, bits(b))
            assertEquals(version, v ushr 12)
            var r = v
            for (i in 17 downTo 12) if ((r ushr i) and 1 == 1) r = r xor (0x1F25 shl (i - 12))
            assertEquals("version BCH", 0, r)
        }
        // The data modules, in placement order, unmasked.
        val function = QrCode.Matrix(version).function
        val bits = ArrayList<Boolean>()
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0 until 2) {
                val x = right - j
                val y = if (((right + 1) and 2) == 0) size - 1 - vert else vert
                if (!function[y][x]) bits += q.dark(x, y) xor QrCode.masked(mask, x, y)
            }
            right -= 2
        }
        val total = QrCode.rawModules(version) / 8
        assertTrue(bits.size - total * 8 in 0..7)
        val codewords = IntArray(total) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or if (bits[i * 8 + j]) 1 else 0 } }
        // De-interleave: short blocks first, the long ones a data codeword longer.
        val blocks = QrCode.BLOCKS[version - 1]
        val ecc = QrCode.ECC_PER_BLOCK[version - 1]
        val shortBlocks = blocks - total % blocks
        val shortLen = total / blocks
        val dataLen = IntArray(blocks) { shortLen - ecc + if (it < shortBlocks) 0 else 1 }
        val parts = Array(blocks) { ArrayList<Int>() }
        var n = 0
        for (i in 0 until dataLen.max()) for (j in 0 until blocks) if (i < dataLen[j]) parts[j] += codewords[n++]
        for (i in 0 until ecc) for (j in 0 until blocks) parts[j] += codewords[n++]
        assertEquals(total, n)
        for (p in parts) for (i in 0 until ecc) assertEquals("syndrome $i", 0, syndrome(p.toIntArray(), i))
        // Byte mode: 0100, the count, the bytes, then the terminator and pad bytes.
        val data = parts.flatMapIndexed { j, p -> p.take(dataLen[j]) }
        val stream = data.flatMap { b -> (7 downTo 0).map { (b ushr it) and 1 } }
        var at = 0
        fun take(k: Int): Int = (0 until k).fold(0) { acc, _ -> (acc shl 1) or stream[at++] }
        assertEquals(0b0100, take(4))
        val count = take(if (version <= 9) 8 else 16)
        val text = ByteArray(count) { take(8).toByte() }.toString(Charsets.UTF_8)
        assertEquals(0, take(minOf(4, stream.size - at)))
        while (at % 8 != 0) assertEquals(0, take(1))
        var pad = 0xEC
        while (at < stream.size) { assertEquals(pad, take(8)); pad = pad xor 0xEC xor 0x11 }
        return Read(text, version, mask)
    }

    private fun encode(text: String) = QrCode.encode(text) ?: error("too long: ${text.length}") // l10n: not text (a test)

    /** The most bytes each version holds at level L (ISO/IEC 18004 table 7). */
    private val capacity = intArrayOf(17, 32, 53, 78, 106, 134, 154, 192, 230, 271)

    @Test fun everyVersionFullAndOneByteOverReadsBack() {
        val rnd = Random(18004)
        for (v in 1..QrCode.MAX_VERSION) {
            val full = String(CharArray(capacity[v - 1]) { (' ' + rnd.nextInt(95)) })
            val r = read(encode(full))
            assertEquals(full, r.text)
            assertEquals(v, r.version)
            if (v < QrCode.MAX_VERSION) assertEquals(v + 1, read(encode(full + "x")).version)
        }
        assertNull(QrCode.encode("x".repeat(capacity.last() + 1)))
    }

    @Test fun shortTextsAndUtf8ReadBack() {
        for (t in listOf("", "a", "https://ko-fi.com/picocryptfan", "Café ☕ à Montréal", "0123456789".repeat(5))) assertEquals(t, read(encode(t)).text)
    }

    @Test fun eachMaskIsUsedSomewhereAndReadsBack() {
        val rnd = Random(7)
        val masks = HashSet<Int>()
        repeat(200) {
            val t = String(CharArray(rnd.nextInt(1, 120)) { 'a' + rnd.nextInt(26) })
            val r = read(encode(t))
            assertEquals(t, r.text)
            masks += r.mask
        }
        assertEquals((0 until 8).toSet(), masks)
    }

    @Test fun reedSolomonMatchesTheStandardsGenerator() {
        // Degree 7 (version 1-L): α^0, α^87, α^229, α^146, α^149, α^238, α^102, α^21 (ISO/IEC 18004 annex A).
        assertArrayEquals(intArrayOf(87, 229, 146, 149, 238, 102, 21).map { exp[it].toByte() }.toByteArray(), QrCode.rsDivisor(7))
    }

    @Test fun formatAndVersionBitsMatchTheStandardsTables() {
        // Level L with mask 0 is 111011111000100; version 7's information is 000111110010010100 (annexes C and D).
        assertEquals(0b111011111000100, QrCode.formatBits(0))
        assertEquals(0b000111110010010100, QrCode.versionBits(7))
        assertEquals(intArrayOf(6, 22, 38).toList(), QrCode.alignment(7).toList())
        assertEquals(intArrayOf(6, 28, 50).toList(), QrCode.alignment(10).toList())
    }

    @Test fun theCodesZbarimgReadArePinned() {
        val rnd = Random(18004)
        val texts = listOf("https://ko-fi.com/picocryptfan") + listOf(6, 7, 10).map { v ->
            String(CharArray(capacity[v - 1]) { 'A' + rnd.nextInt(26) })
        }
        assertEquals(PINNED, texts.map { sha(encode(it)) })
    }

    companion object {
        fun sha(q: QrCode): String {
            val rows = (0 until q.size).joinToString("\n") { y -> (0 until q.size).joinToString("") { x -> if (q.dark(x, y)) "1" else "0" } }
            return MessageDigest.getInstance("SHA-256").digest(rows.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        /** SHA-256 of the rows ("1" dark, "0" light, one line each) of the four codes zbarimg read. */
        val PINNED = listOf(
            "eddf7b43c4b42fa767455d23161bea59e7b19cc9fb54981c42454ad1cedd581e",
            "5d5831537a9aa600ae815656bd7591f650e39bb550c0613b4d9777ed7d4a8d8e",
            "c3206c750e118314add43be8efe05be03f9591771dddf9d4dde4e65da5212715",
            "c1b67ec57d1082fac663cab51373063076c10842e86f7f4a8751a2cfb841fffe",
        )
    }
}
