// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// Pure. 1.3, point and read (point-and-read-plan.md, "Jobs": P0's desk measure): the pictures a reading sends. One
// picture by default, the whole photo at most [SIDE] px on its longer side; four tiles behind [TILES_DEFAULT], the flag
// P0's numbers set (small print on a page may need them). The tiles are P0's (logs/point-and-read-p0/prep.py): the four
// corners, each 55 % of the width and height, so they overlap by a tenth and a line across the middle is whole in two.
//
// More than one picture crosses in KEY_IMAGE's one read-only region, packed: each JPEG after its length (4 bytes, big
// end first). One picture is the JPEG itself, as a panel photo and FILE_READ's page are.

object ReadImages {
    /** Off until P0's words-per-100 say tiles read small print better than one picture. */
    const val TILES_DEFAULT = false
    /** Four tiles at most: about 1,200 tokens of pictures (TokenEstimate.IMAGE_TOKENS each). */
    const val MAX = 4
    /** The longer side of each picture sent, in px (P0's 1024x1024> resize). */
    const val SIDE = 1024
    /** Each tile's share of the photo's width and of its height. */
    const val TILE_SHARE = 0.55

    data class Tile(val x: Int, val y: Int, val w: Int, val h: Int)

    /** How many pictures a reading sends. */
    fun count(tiles: Boolean = TILES_DEFAULT): Int = if (tiles) MAX else 1

    /** The parts of a [w] by [h] photo to send: the whole of it, or its four corner tiles, top left first, row by row. */
    fun parts(w: Int, h: Int, tiles: Boolean = TILES_DEFAULT): List<Tile> {
        require(w > 0 && h > 0)
        if (!tiles) return listOf(Tile(0, 0, w, h))
        val tw = maxOf(1, (w * TILE_SHARE).toInt()); val th = maxOf(1, (h * TILE_SHARE).toInt())
        return listOf(Tile(0, 0, tw, th), Tile(w - tw, 0, tw, th), Tile(0, h - th, tw, th), Tile(w - tw, h - th, tw, th))
    }

    /** The size a [w] by [h] part is sent at: at most [SIDE] on its longer side, never enlarged, its shape kept. */
    fun fit(w: Int, h: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= SIDE) return w to h
        return maxOf(1, Math.round(w.toDouble() * SIDE / long).toInt()) to maxOf(1, Math.round(h.toDouble() * SIDE / long).toInt())
    }

    /** The bytes for KEY_IMAGE: one JPEG as it is, several packed. */
    fun pack(jpegs: List<ByteArray>): ByteArray {
        require(jpegs.size in 1..MAX && jpegs.all { it.isNotEmpty() })
        if (jpegs.size == 1) return jpegs[0]
        val out = ByteArrayOutputStream()
        for (j in jpegs) { out.write(ByteBuffer.allocate(4).putInt(j.size).array()); out.write(j) }
        return out.toByteArray()
    }

    /** [n] packed parts of [bytes], exactly: null if a length runs past the end, a part is empty or bytes are left over. */
    fun unpack(bytes: ByteArray, n: Int): List<ByteArray>? {
        if (n !in 2..MAX) return null
        val b = ByteBuffer.wrap(bytes)
        val parts = ArrayList<ByteArray>(n)
        repeat(n) {
            if (b.remaining() < 4) return null
            val len = b.int
            if (len <= 0 || len > b.remaining()) return null
            parts += ByteArray(len).also { b.get(it) }
        }
        return if (b.hasRemaining()) null else parts
    }
}
