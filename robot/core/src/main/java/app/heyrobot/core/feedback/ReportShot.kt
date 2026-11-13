// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

// RP4 (report-a-problem-plan.md "What's new" 4): a screenshot for a report, as pixels. Cover boxes are filled solid black in
// the pixels themselves (a blur can be undone), then the crop; the PNG is written fresh here, IHDR, IDAT and IEND only, so it
// carries no text, time, place or colour profile, whatever the picture came from.
class ReportShot(val width: Int, val height: Int, private val px: IntArray) {
    init { require(fits(width, height) && px.size == width * height) }

    /** A rectangle in this picture's pixels, [right] and [bottom] not included. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        internal fun within(w: Int, h: Int): Box? =
            Box(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h)).takeIf { it.right > it.left && it.bottom > it.top }
    }

    /** The pixels, ARGB, row by row (a copy). */
    fun argb(): IntArray = px.copyOf()

    /** [cover]'s boxes black, then [crop] (both in this picture's places); null crop: the whole picture. */
    fun edited(crop: Box?, cover: List<Box>): ReportShot {
        val out = px.copyOf()
        for (b in cover) {
            val c = b.within(width, height) ?: continue
            for (y in c.top until c.bottom) out.fill(BLACK, y * width + c.left, y * width + c.right)
        }
        val c = crop?.within(width, height) ?: return ReportShot(width, height, out)
        val w = c.right - c.left
        val h = c.bottom - c.top
        return ReportShot(w, h, IntArray(w * h) { i -> out[(c.top + i / w) * width + c.left + i % w] })
    }

    /** A fresh PNG of the pixels: 8-bit RGB (no alpha), filter 0, and no chunk but IHDR, IDAT and IEND. */
    fun png(): ByteArray {
        val row = 1 + 3 * width
        val raw = ByteArray(height * row)
        for (y in 0 until height) {
            var o = y * row + 1
            for (x in 0 until width) {
                val p = px[y * width + x]
                raw[o++] = (p shr 16).toByte()
                raw[o++] = (p shr 8).toByte()
                raw[o++] = p.toByte()
            }
        }
        val z = ByteArrayOutputStream()
        val def = Deflater(Deflater.BEST_SPEED)
        try {
            def.setInput(raw)
            def.finish()
            val buf = ByteArray(64 * 1024)
            while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        } finally { def.end() }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()) // l10n: not text (a PNG chunk type)
        chunk(out, "IDAT", z.toByteArray()) // l10n: not text (a PNG chunk type)
        chunk(out, "IEND", ByteArray(0)) // l10n: not text (a PNG chunk type)
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(t)
        out.write(data)
        val crc = CRC32().apply { update(t); update(data) }.value
        out.write(ByteBuffer.allocate(4).putInt(crc.toInt()).array())
    }

    companion object {
        /** ReportFile's own limit for a side. */
        const val MAX_SIDE = 4096
        private const val BLACK = 0xFF000000.toInt()
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun fits(w: Int, h: Int): Boolean = w in 1..MAX_SIDE && h in 1..MAX_SIDE

        /**
         * A PNG [png] wrote, read back with no Android decoder (ProcessIsolationTest keeps those in app/parse): 8-bit RGB, filter 0,
         * IHDR, IDAT and IEND only, every CRC right; anything else is null.
         */
        fun read(png: ByteArray): ReportShot? = runCatching {
            if (png.size < 8 || SIGNATURE.indices.any { png[it] != SIGNATURE[it] }) return null
            val b = ByteBuffer.wrap(png, 8, png.size - 8)
            var w = 0
            var h = 0
            val z = ByteArrayOutputStream()
            var end = false
            while (!end) {
                if (b.remaining() < 12) return null
                val len = b.int
                if (len < 0 || len > b.remaining() - 8) return null
                val t = ByteArray(4).also { b.get(it) }
                val d = ByteArray(len).also { b.get(it) }
                if (CRC32().apply { update(t); update(d) }.value.toInt() != b.int) return null
                when (String(t, Charsets.US_ASCII)) {
                    "IHDR" -> { // l10n: not text (a PNG chunk type)
                        if (w != 0 || len != 13 || d[8].toInt() != 8 || d[9].toInt() != 2 || d[10].toInt() != 0 || d[11].toInt() != 0 || d[12].toInt() != 0) return null
                        w = ByteBuffer.wrap(d, 0, 4).int
                        h = ByteBuffer.wrap(d, 4, 4).int
                        if (!fits(w, h)) return null
                    }
                    "IDAT" -> if (w == 0) return null else z.write(d) // l10n: not text (a PNG chunk type)
                    "IEND" -> end = true // l10n: not text (a PNG chunk type)
                    else -> return null
                }
            }
            if (w == 0 || b.hasRemaining()) return null
            val row = 1 + 3 * w
            val raw = ByteArray(h * row)
            val inf = Inflater()
            try {
                inf.setInput(z.toByteArray())
                var n = 0
                while (n < raw.size) {
                    val k = inf.inflate(raw, n, raw.size - n)
                    if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) return null
                    n += k
                }
            } finally { inf.end() }
            if ((0 until h).any { raw[it * row].toInt() != 0 }) return null
            ReportShot(w, h, IntArray(w * h) { i ->
                val o = (i / w) * row + 1 + 3 * (i % w)
                (0xFF shl 24) or ((raw[o].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
            })
        }.getOrNull()

        /** The assist screenshot may wait for the card only with "Use screenshot" on (null: Android wouldn't say) and unlocked. */
        fun mayKeep(useScreenshot: Boolean?, locked: Boolean): Boolean = useScreenshot == true && !locked
    }
}
