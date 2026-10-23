// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

/**
 * A QR code (ISO/IEC 18004) for a short text, written here instead of pulling in a library: Settings › About › Support
 * Robot draws one per donation address. Byte mode (the text's UTF-8), error correction level L, versions 1 to 10 (up to
 * 271 bytes); the smallest version that holds the text, and of the eight masks the one with the lowest penalty.
 * [size] modules a side; [dark] says which are dark. The caller adds the 4-module light border around it.
 *
 * Checked against an independent reader: every donation address and texts filling each version decode back exactly with
 * zbarimg 0.23.93 (19 Oct 2026). QrCodeTest reads codes of every version back itself and pins some zbarimg read; app's
 * SupportAddressesTest pins each address's code.
 */
class QrCode private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    val size: Int get() = modules.size

    /** Whether the module in column [x], row [y] is dark. */
    fun dark(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        const val MAX_VERSION = 10
        /** Error correction codewords per block and blocks, level L, versions 1 to 10 (ISO/IEC 18004 table 9). */
        internal val ECC_PER_BLOCK = intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18)
        internal val BLOCKS = intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4)
        /** Level L's two bits in the format information. */
        private const val LEVEL_L = 1

        /** The code for [text], or null when it is longer than version 10 holds. */
        fun encode(text: String): QrCode? {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version = (1..MAX_VERSION).firstOrNull { v -> 4 + countBits(v) + 8 * bytes.size <= dataCodewords(v) * 8 } ?: return null
            val codewords = withEcc(version, dataBytes(version, bytes))
            val best = (0 until 8).map { m -> Matrix(version).apply { place(codewords); applyMask(m); drawFormat(m) } }
                .withIndex().minBy { it.value.penalty() }
            return QrCode(version, best.index, best.value.modules)
        }

        /** The character count's width in byte mode. */
        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        /** Modules that hold data and error correction (not the patterns, format or version information), as Nayuki counts them. */
        internal fun rawModules(v: Int): Int {
            var n = (16 * v + 128) * v + 64
            if (v >= 2) {
                val align = v / 7 + 2
                n -= (25 * align - 10) * align - 55
                if (v >= 7) n -= 36
            }
            return n
        }

        private fun dataCodewords(v: Int) = rawModules(v) / 8 - ECC_PER_BLOCK[v - 1] * BLOCKS[v - 1]

        /** Mode, count, the bytes, the terminator, then the pad bytes 0xEC and 0x11 to fill the version. */
        private fun dataBytes(version: Int, bytes: ByteArray): ByteArray {
            val bits = ArrayList<Boolean>()
            fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
            put(0b0100, 4)
            put(bytes.size, countBits(version))
            for (b in bytes) put(b.toInt() and 0xFF, 8)
            val capacity = dataCodewords(version) * 8
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return ByteArray(capacity / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or if (bits[i * 8 + j]) 1 else 0 }.toByte() }
        }

        /** The data split into blocks, each with its Reed-Solomon codewords, interleaved. */
        private fun withEcc(version: Int, data: ByteArray): ByteArray {
            val blocks = BLOCKS[version - 1]
            val eccLen = ECC_PER_BLOCK[version - 1]
            val raw = rawModules(version) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val divisor = rsDivisor(eccLen)
            val parts = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until blocks) {
                val dat = data.copyOfRange(k, k + shortLen - eccLen + if (i < shortBlocks) 0 else 1)
                k += dat.size
                val block = dat.copyOf(shortLen + 1)
                rsRemainder(dat, divisor).copyInto(block, block.size - eccLen)
                parts += block
            }
            val out = ByteArray(raw)
            var n = 0
            for (i in 0 until shortLen + 1) for (j in 0 until blocks) {
                // A short block has no byte at the long blocks' last data position.
                if (i != shortLen - eccLen || j >= shortBlocks) out[n++] = parts[j][i]
            }
            return out
        }

        /** The generator polynomial of degree [degree], its leading 1 left out, highest power first. */
        internal fun rsDivisor(degree: Int): ByteArray {
            val r = ByteArray(degree)
            r[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    r[j] = gfMul(r[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) r[j] = (r[j].toInt() xor r[j + 1].toInt()).toByte()
                }
                root = gfMul(root, 0x02)
            }
            return r
        }

        internal fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val r = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor r[0].toInt()) and 0xFF
                System.arraycopy(r, 1, r, 0, r.size - 1)
                r[r.size - 1] = 0
                for (i in r.indices) r[i] = (r[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return r
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
        internal fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        /** The 15 format bits for level L and [mask]: BCH(15,5), then XOR 0x5412. */
        internal fun formatBits(mask: Int): Int {
            val data = (LEVEL_L shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return ((data shl 10) or rem) xor 0x5412
        }

        /** The 18 version bits (versions 7 and up): BCH(18,6). */
        internal fun versionBits(version: Int): Int {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            return (version shl 12) or rem
        }

        /** Where the alignment patterns' centres are, on each axis. */
        internal fun alignment(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val n = version / 7 + 2
            val step = (version * 8 + n * 3 + 5) / (n * 4 - 4) * 2
            val size = version * 4 + 17
            return IntArray(n) { i -> if (i == 0) 6 else size - 7 - (n - 1 - i) * step }
        }

        internal fun masked(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }
    }

    /** One version's grid: the function patterns drawn, then the codewords, a mask and the format. */
    internal class Matrix(val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        /** Modules the patterns and the format and version information use; the data skips them and no mask touches them. */
        val function = Array(size) { BooleanArray(size) }

        private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

        init {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) set(x, y, maxOf(Math.abs(dx), Math.abs(dy)).let { it != 2 && it != 4 })
                }
            }
            val at = alignment(version)
            for (i in at.indices) for (j in at.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == at.lastIndex) || (i == at.lastIndex && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(at[i] + dx, at[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            // Reserved for the format (drawn once the mask is chosen), and the dark module.
            drawFormat(0)
            if (version >= 7) {
                val bits = versionBits(version)
                for (i in 0 until 18) {
                    val bit = (bits ushr i) and 1 == 1
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, bit)
                    set(b, a, bit)
                }
            }
        }

        fun drawFormat(mask: Int) {
            val bits = formatBits(mask)
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        /** The codewords' bits, two columns at a time from the right, up then down, skipping the timing column. */
        fun place(codewords: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < codewords.size * 8) {
                        modules[y][x] = ((codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) == 1
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) if (!function[y][x] && masked(mask, x, y)) modules[y][x] = !modules[y][x]
        }

        /** ISO/IEC 18004's four penalty rules: runs of five or more, 2×2 blocks, finder-like patterns, the share of dark. */
        fun penalty(): Int {
            var p = 0
            val lines = (0 until size).map { y -> BooleanArray(size) { x -> modules[y][x] } } + (0 until size).map { x -> BooleanArray(size) { y -> modules[y][x] } }
            val finder = booleanArrayOf(true, false, true, true, true, false, true)
            for (line in lines) {
                var run = 1
                for (i in 1..size) {
                    if (i < size && line[i] == line[i - 1]) run++ else { if (run >= 5) p += 3 + run - 5; run = 1 }
                }
                for (i in 0..size - 7) {
                    if ((0 until 7).all { line[i + it] == finder[it] }) {
                        val before = (i - 4 until i).all { it < 0 || !line[it] }
                        val after = (i + 7 until i + 11).all { it >= size || !line[it] }
                        if (before || after) p += 40
                    }
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (modules[y][x + 1] == c && modules[y + 1][x] == c && modules[y + 1][x + 1] == c) p += 3
            }
            val dark = modules.sumOf { r -> r.count { it } }
            val total = size * size
            p += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10
            return p
        }
    }
}
