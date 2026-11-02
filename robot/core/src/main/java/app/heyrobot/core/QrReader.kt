// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads a QR code in a photo: the pairing code another Robot shows in person (robot-to-robot-plan.md §2.3, D21; job L4),
 * read only in :files, the isolated reader (app/parse; ProcessIsolationTest's filesAreReadOnlyInTheirProcess). Written here
 * instead of pulling in a library (the plan named ZXing core; no new library can be fetched for this build), it reads
 * exactly what Robot's own [QrCode] writes and nothing more: byte mode, error correction level L, versions 1 to 10, one
 * segment, then the terminator and the pad bytes in their one spelling. Anything else is no code.
 *
 * The steps follow ISO/IEC 18004, and the image side follows ZXing's reader closely (https://github.com/zxing/zxing, its
 * HybridBinarizer, FinderPatternFinder, AlignmentPatternFinder, Detector and PerspectiveTransform, Copyright 2007 ZXing
 * authors, Apache License 2.0), adapted to Kotlin for the codes Robot writes, with its constants and tolerances: a local
 * threshold over 8-pixel blocks, the three finder patterns found by their 1:1:3:1:1 runs across, down and diagonally (here
 * the trio is then chosen by shape among the best confirmed ones), the alignment pattern near where it should be (version 2
 * up), and a perspective transform onto the grid. Then Robot's own: the format and version information read from both
 * copies and taken only within 3 bits of a valid word of level L, the mask undone, the blocks' Reed-Solomon codes corrected
 * (never more than the level allows: ISO's misdecode protection for versions 1 to 3 kept), the corrected blocks checked
 * again, and the data read strictly. Light codes on dark and mirrored photos are tried too. A code that doesn't read gives
 * null, never a guess: a wrong pairing code is refused by its checksum anyway (core PairCode), and most damage by the
 * Reed-Solomon check before that.
 *
 * Bounded work for any input: one pass to threshold, one scan for finder patterns (at most [MAX_CENTERS] kept), at most
 * [TRIPLES] trios of them tried, each a grid of at most 57 by 57 modules. Never throws ([read] catches what a bug could).
 */
object QrReader {
    /** The longest text a version 10, level L code holds (QrCode's own limit), in UTF-8 bytes. */
    const val MAX_TEXT = 271
    /** A photo smaller than this on either side holds no readable code. */
    const val MIN_EDGE = 40
    private const val MAX_VERSION = QrCode.MAX_VERSION
    private const val MAX_CENTERS = 256
    private const val TRIPLES = 12

    /**
     * The text of a code [QrCode] could have written, found in a [width] × [height] image of RGBA pixels ([rgba]: 4 bytes a
     * pixel, as Android's ARGB_8888 bitmaps store them), or null.
     */
    fun read(rgba: ByteArray, width: Int, height: Int): String? {
        if (width < MIN_EDGE || height < MIN_EDGE || rgba.size.toLong() != width.toLong() * height * 4) return null
        val luma = ByteArray(width * height)
        for (p in 0 until width * height) {
            val r = rgba[4 * p].toInt() and 0xFF
            val g = rgba[4 * p + 1].toInt() and 0xFF
            val b = rgba[4 * p + 2].toInt() and 0xFF
            val a = rgba[4 * p + 3].toInt() and 0xFF
            val y = (r * 77 + g * 150 + b * 29) ushr 8
            // Transparent parts count as white paper.
            luma[p] = ((y * a + 255 * (255 - a)) / 255).toByte()
        }
        return readLuma(luma, width, height)
    }

    /** The same from [luma], one byte of brightness a pixel (0 black, 255 white). */
    fun readLuma(luma: ByteArray, width: Int, height: Int): String? = try {
        if (width < MIN_EDGE || height < MIN_EDGE || luma.size.toLong() != width.toLong() * height) null
        else {
            val bits = Binarizer.threshold(luma, width, height)
            find(Bits(bits, width, height, inverted = false)) ?: find(Bits(bits, width, height, inverted = true))
        }
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun find(img: Bits): String? {
        val centers = FinderFinder(img).find()
        for (t in triples(centers)) {
            val text = Detector(img).decode(t[0], t[1], t[2])
            if (text != null) return text
        }
        return null
    }

    /** The trios of finder pattern centres most like a QR code's corners, best first. */
    private fun triples(all: List<Center>): List<Array<Center>> {
        val confirmed = all.filter { it.count >= 2 }
        val pool = (if (confirmed.size >= 3) confirmed else all).sortedByDescending { it.count }.take(TRIPLES)
        val scored = ArrayList<Pair<Double, Array<Center>>>()
        for (a in 0 until pool.size) for (b in a + 1 until pool.size) for (c in b + 1 until pool.size) {
            val o = order(pool[a], pool[b], pool[c])
            val (bl, tl, tr) = Triple(o[0], o[1], o[2])
            val ms = listOf(bl.size, tl.size, tr.size)
            if (ms.max() > 1.6 * ms.min()) continue
            val top = dist(tl, tr)
            val left = dist(tl, bl)
            val hyp = dist(tr, bl)
            if (top < 1e-3 || left < 1e-3) continue
            val legs = max(top, left) / min(top, left)
            if (legs > 1.6) continue
            val right = abs(hyp * hyp / (top * top + left * left) - 1)
            if (right > 0.5) continue
            val avg = ms.average()
            val dim = (top + left) / 2 / avg + 7
            if (dim < 17 || dim > 4 * MAX_VERSION + 17 + 8) continue
            val score = (legs - 1) + right + (ms.max() - ms.min()) / avg
            scored += score to o
        }
        return scored.sortedBy { it.first }.take(TRIPLES).map { it.second }
    }

    private fun dist(a: Center, b: Center): Double = Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /** Bottom left, top left, top right: the corner opposite the longest side is the top left; the turn decides the rest. */
    private fun order(p0: Center, p1: Center, p2: Center): Array<Center> {
        val d01 = dist(p0, p1)
        val d12 = dist(p1, p2)
        val d02 = dist(p0, p2)
        var a: Center
        val b: Center
        var c: Center
        if (d12 >= d01 && d12 >= d02) { b = p0; a = p1; c = p2 } else if (d02 >= d12 && d02 >= d01) { b = p1; a = p0; c = p2 } else { b = p2; a = p0; c = p1 }
        if ((c.x - b.x) * (a.y - b.y) - (c.y - b.y) * (a.x - b.x) < 0f) { val t = a; a = c; c = t }
        return arrayOf(a, b, c)
    }

    // ---------------------------------------------------------------------------------------------------------- pixels

    /** The thresholded image: [dark] at (x, y); [inverted] reads a light code on a dark ground. */
    private class Bits(private val b: BooleanArray, val width: Int, val height: Int, private val inverted: Boolean) {
        fun dark(x: Int, y: Int): Boolean = b[y * width + x] != inverted
    }

    /**
     * A local threshold: each 8-pixel block's mean (or, for a flat block, a guess from its neighbours), and each pixel
     * against the mean of the 5 × 5 blocks around its own.
     */
    private object Binarizer {
        private const val SHIFT = 3
        private const val BLOCK = 1 shl SHIFT
        private const val MIN_RANGE = 24

        fun threshold(luma: ByteArray, w: Int, h: Int): BooleanArray {
            val sw = (w + BLOCK - 1) ushr SHIFT
            val sh = (h + BLOCK - 1) ushr SHIFT
            val black = IntArray(sw * sh)
            for (by in 0 until sh) {
                val y0 = min(by shl SHIFT, h - BLOCK)
                for (bx in 0 until sw) {
                    val x0 = min(bx shl SHIFT, w - BLOCK)
                    var sum = 0
                    var lo = 255
                    var hi = 0
                    for (yy in 0 until BLOCK) {
                        val row = (y0 + yy) * w + x0
                        for (xx in 0 until BLOCK) {
                            val v = luma[row + xx].toInt() and 0xFF
                            sum += v
                            if (v < lo) lo = v
                            if (v > hi) hi = v
                        }
                    }
                    var avg = sum ushr (2 * SHIFT)
                    if (hi - lo <= MIN_RANGE) {
                        // A flat block: paper unless its neighbours say the dark around here is darker.
                        avg = lo / 2
                        if (by > 0 && bx > 0) {
                            val n = (black[(by - 1) * sw + bx] + 2 * black[by * sw + bx - 1] + black[(by - 1) * sw + bx - 1]) / 4
                            if (lo < n) avg = n
                        }
                    }
                    black[by * sw + bx] = avg
                }
            }
            val out = BooleanArray(w * h)
            for (by in 0 until sh) {
                val y0 = min(by shl SHIFT, h - BLOCK)
                val top = (by).coerceIn(2, max(2, sh - 3))
                for (bx in 0 until sw) {
                    val x0 = min(bx shl SHIFT, w - BLOCK)
                    val left = (bx).coerceIn(2, max(2, sw - 3))
                    var sum = 0
                    var n = 0
                    for (dy in -2..2) for (dx in -2..2) {
                        val yy = top + dy
                        val xx = left + dx
                        if (yy in 0 until sh && xx in 0 until sw) { sum += black[yy * sw + xx]; n++ }
                    }
                    val t = sum / n
                    for (yy in 0 until BLOCK) {
                        val row = (y0 + yy) * w + x0
                        for (xx in 0 until BLOCK) out[row + xx] = (luma[row + xx].toInt() and 0xFF) <= t
                    }
                }
            }
            return out
        }
    }

    // ------------------------------------------------------------------------------------------------- finder patterns

    /** A pattern's centre ([x], [y]), its module size, and how many scans confirmed it. */
    private class Center(val x: Float, val y: Float, val size: Float, val count: Int) {
        fun near(s: Float, i: Float, j: Float): Boolean {
            if (abs(i - y) > s || abs(j - x) > s) return false
            val d = abs(s - size)
            return d <= 1f || d <= size
        }

        fun merged(i: Float, j: Float, s: Float): Center {
            val n = count + 1
            return Center((count * x + j) / n, (count * y + i) / n, (count * size + s) / n, n)
        }
    }

    private class FinderFinder(private val img: Bits) {
        private val centers = ArrayList<Center>()

        fun find(): List<Center> {
            val h = img.height
            val w = img.width
            val step = 2
            val sc = IntArray(5)
            var i = step - 1
            while (i < h) {
                sc.fill(0)
                var state = 0
                var j = 0
                while (j < w) {
                    if (img.dark(j, i)) {
                        if (state and 1 == 1) state++
                        sc[state]++
                    } else if (state and 1 == 0) {
                        if (state == 4) {
                            if (cross(sc) && center(sc, i, j)) {
                                sc.fill(0)
                                state = 0
                            } else {
                                shift2(sc)
                                state = 3
                            }
                        } else {
                            state++
                            sc[state]++
                        }
                    } else {
                        sc[state]++
                    }
                    j++
                }
                if (cross(sc)) center(sc, i, w)
                i += step
            }
            return centers
        }

        private fun shift2(sc: IntArray) {
            sc[0] = sc[2]; sc[1] = sc[3]; sc[2] = sc[4]; sc[3] = 1; sc[4] = 0
        }

        private fun fromEnd(sc: IntArray, end: Int): Float = (end - sc[4] - sc[3]).toFloat() - sc[2] / 2f

        /** A 1:1:3:1:1 run, each within half a module. */
        private fun cross(sc: IntArray, slack: Float = 2f): Boolean {
            var total = 0
            for (c in sc) { if (c == 0) return false; total += c }
            if (total < 7) return false
            val m = total / 7f
            val v = m / slack
            return abs(m - sc[0]) < v && abs(m - sc[1]) < v && abs(3f * m - sc[2]) < 3f * v && abs(m - sc[3]) < v && abs(m - sc[4]) < v
        }

        private fun center(sc: IntArray, i: Int, j: Int): Boolean {
            val total = sc.sum()
            var cj = fromEnd(sc, j)
            val ci = vertical(i, cj.toInt(), sc[2], total)
            if (ci.isNaN()) return false
            cj = horizontal(cj.toInt(), ci.toInt(), sc[2], total)
            if (cj.isNaN() || !diagonal(ci.toInt(), cj.toInt())) return false
            val s = total / 7f
            for ((k, c) in centers.withIndex()) {
                if (c.near(s, ci, cj)) {
                    centers[k] = c.merged(ci, cj, s)
                    return true
                }
            }
            if (centers.size < MAX_CENTERS) centers += Center(cj, ci, s, 1)
            return true
        }

        private fun vertical(startI: Int, cj: Int, maxCount: Int, original: Int): Float {
            val maxI = img.height
            if (cj !in 0 until img.width) return Float.NaN
            val sc = IntArray(5)
            var i = startI
            while (i >= 0 && img.dark(cj, i)) { sc[2]++; i-- }
            if (i < 0) return Float.NaN
            while (i >= 0 && !img.dark(cj, i) && sc[1] <= maxCount) { sc[1]++; i-- }
            if (i < 0 || sc[1] > maxCount) return Float.NaN
            while (i >= 0 && img.dark(cj, i) && sc[0] <= maxCount) { sc[0]++; i-- }
            if (sc[0] > maxCount) return Float.NaN
            i = startI + 1
            while (i < maxI && img.dark(cj, i)) { sc[2]++; i++ }
            if (i == maxI) return Float.NaN
            while (i < maxI && !img.dark(cj, i) && sc[3] < maxCount) { sc[3]++; i++ }
            if (i == maxI || sc[3] >= maxCount) return Float.NaN
            while (i < maxI && img.dark(cj, i) && sc[4] < maxCount) { sc[4]++; i++ }
            if (sc[4] >= maxCount) return Float.NaN
            if (5 * abs(sc.sum() - original) >= 2 * original) return Float.NaN
            return if (cross(sc)) fromEnd(sc, i) else Float.NaN
        }

        private fun horizontal(startJ: Int, ci: Int, maxCount: Int, original: Int): Float {
            val maxJ = img.width
            if (ci !in 0 until img.height) return Float.NaN
            val sc = IntArray(5)
            var j = startJ
            while (j >= 0 && img.dark(j, ci)) { sc[2]++; j-- }
            if (j < 0) return Float.NaN
            while (j >= 0 && !img.dark(j, ci) && sc[1] <= maxCount) { sc[1]++; j-- }
            if (j < 0 || sc[1] > maxCount) return Float.NaN
            while (j >= 0 && img.dark(j, ci) && sc[0] <= maxCount) { sc[0]++; j-- }
            if (sc[0] > maxCount) return Float.NaN
            j = startJ + 1
            while (j < maxJ && img.dark(j, ci)) { sc[2]++; j++ }
            if (j == maxJ) return Float.NaN
            while (j < maxJ && !img.dark(j, ci) && sc[3] < maxCount) { sc[3]++; j++ }
            if (j == maxJ || sc[3] >= maxCount) return Float.NaN
            while (j < maxJ && img.dark(j, ci) && sc[4] < maxCount) { sc[4]++; j++ }
            if (sc[4] >= maxCount) return Float.NaN
            if (5 * abs(sc.sum() - original) >= original) return Float.NaN
            return if (cross(sc)) fromEnd(sc, j) else Float.NaN
        }

        private fun diagonal(ci: Int, cj: Int): Boolean {
            if (ci !in 0 until img.height || cj !in 0 until img.width) return false
            val sc = IntArray(5)
            var i = 0
            while (ci >= i && cj >= i && img.dark(cj - i, ci - i)) { sc[2]++; i++ }
            if (sc[2] == 0) return false
            while (ci >= i && cj >= i && !img.dark(cj - i, ci - i)) { sc[1]++; i++ }
            if (sc[1] == 0) return false
            while (ci >= i && cj >= i && img.dark(cj - i, ci - i)) { sc[0]++; i++ }
            if (sc[0] == 0) return false
            i = 1
            while (ci + i < img.height && cj + i < img.width && img.dark(cj + i, ci + i)) { sc[2]++; i++ }
            while (ci + i < img.height && cj + i < img.width && !img.dark(cj + i, ci + i)) { sc[3]++; i++ }
            if (sc[3] == 0) return false
            while (ci + i < img.height && cj + i < img.width && img.dark(cj + i, ci + i)) { sc[4]++; i++ }
            if (sc[4] == 0) return false
            return cross(sc, slack = 1.333f)
        }
    }

    // ------------------------------------------------------------------------------------------------------- the grid

    private class Detector(private val img: Bits) {
        /** The text of the code whose finder patterns are [bl], [tl] and [tr], or null. */
        fun decode(bl: Center, tl: Center, tr: Center): String? {
            val ms = (oneWay(tl, tr) + oneWay(tl, bl)) / 2f
            if (ms.isNaN() || ms < 1f) return null
            val first = dimension(tl, tr, bl, ms) ?: return null
            for (dim in listOf(first, first - 4, first + 4)) {
                if (dim < 21 || dim > 4 * MAX_VERSION + 17) continue
                val grid = sample(bl, tl, tr, ms, dim) ?: continue
                Grid.read(grid)?.let { return it }
                // A mirrored photo (a front camera's): the same code, transposed.
                Grid.read(Array(dim) { y -> BooleanArray(dim) { x -> grid[x][y] } })?.let { return it }
            }
            return null
        }

        private fun dimension(tl: Center, tr: Center, bl: Center, ms: Float): Int? {
            val top = (dist(tl, tr) / ms).roundToInt()
            val left = (dist(tl, bl) / ms).roundToInt()
            var d = (top + left) / 2 + 7
            when (d and 3) {
                0 -> d++
                2 -> d--
                3 -> return null
            }
            return d
        }

        private fun sample(bl: Center, tl: Center, tr: Center, ms: Float, dim: Int): Array<BooleanArray>? {
            val version = (dim - 17) / 4
            var ax = Float.NaN
            var ay = Float.NaN
            if (version >= 2) {
                val brx = tr.x - tl.x + bl.x
                val bry = tr.y - tl.y + bl.y
                val k = 1f - 3f / (dim - 7)
                val ex = (tl.x + k * (brx - tl.x)).toInt()
                val ey = (tl.y + k * (bry - tl.y)).toInt()
                var f = 4
                while (f <= 16) {
                    val a = Alignment(img, ms).near(ex, ey, f)
                    if (a != null) { ax = a.x; ay = a.y; break }
                    f = f shl 1
                }
            }
            val far = dim - 3.5f
            val t = if (!ax.isNaN()) Perspective.quadToQuad(3.5f, 3.5f, far, 3.5f, far - 3f, far - 3f, 3.5f, far, tl.x, tl.y, tr.x, tr.y, ax, ay, bl.x, bl.y)
            else Perspective.quadToQuad(3.5f, 3.5f, far, 3.5f, far, far, 3.5f, far, tl.x, tl.y, tr.x, tr.y, tr.x - tl.x + bl.x, tr.y - tl.y + bl.y, bl.x, bl.y)
            val out = Array(dim) { BooleanArray(dim) }
            val pts = FloatArray(2 * dim)
            for (y in 0 until dim) {
                for (x in 0 until dim) { pts[2 * x] = x + 0.5f; pts[2 * x + 1] = y + 0.5f }
                t.apply(pts)
                for (x in 0 until dim) {
                    var px = pts[2 * x]
                    var py = pts[2 * x + 1]
                    if (px.isNaN() || py.isNaN() || px < -1f || py < -1f || px > img.width || py > img.height) return null
                    if (px < 0f) px = 0f
                    if (py < 0f) py = 0f
                    if (px >= img.width) px = img.width - 1f
                    if (py >= img.height) py = img.height - 1f
                    out[y][x] = img.dark(px.toInt(), py.toInt())
                }
            }
            return out
        }

        /** A module's size from the black-white-black runs between two finder patterns' centres, both ways. */
        private fun oneWay(p: Center, o: Center): Float {
            val a = bothWays(p.x.toInt(), p.y.toInt(), o.x.toInt(), o.y.toInt())
            val b = bothWays(o.x.toInt(), o.y.toInt(), p.x.toInt(), p.y.toInt())
            return when {
                a.isNaN() -> b / 7f
                b.isNaN() -> a / 7f
                else -> (a + b) / 14f
            }
        }

        private fun bothWays(fromX: Int, fromY: Int, toX: Int, toY: Int): Float {
            var result = run(fromX, fromY, toX, toY)
            var scale = 1f
            var otherX = fromX - (toX - fromX)
            if (otherX < 0) {
                scale = fromX.toFloat() / (fromX - otherX)
                otherX = 0
            } else if (otherX >= img.width) {
                scale = (img.width - 1 - fromX).toFloat() / (otherX - fromX)
                otherX = img.width - 1
            }
            var otherY = (fromY - (toY - fromY) * scale).toInt()
            scale = 1f
            if (otherY < 0) {
                scale = fromY.toFloat() / (fromY - otherY)
                otherY = 0
            } else if (otherY >= img.height) {
                scale = (img.height - 1 - fromY).toFloat() / (otherY - fromY)
                otherY = img.height - 1
            }
            otherX = (fromX + (otherX - fromX) * scale).toInt()
            result += run(fromX, fromY, otherX, otherY)
            return result - 1f
        }

        /** How far from (fromX, fromY) a black, white, black run ends towards (toX, toY) (Bresenham), or NaN. */
        private fun run(fromX0: Int, fromY0: Int, toX0: Int, toY0: Int): Float {
            var fromX = fromX0
            var fromY = fromY0
            var toX = toX0
            var toY = toY0
            val steep = abs(toY - fromY) > abs(toX - fromX)
            if (steep) {
                var t = fromX; fromX = fromY; fromY = t
                t = toX; toX = toY; toY = t
            }
            val dx = abs(toX - fromX)
            val dy = abs(toY - fromY)
            var error = -dx / 2
            val xs = if (fromX < toX) 1 else -1
            val ys = if (fromY < toY) 1 else -1
            var state = 0
            val xLimit = toX + xs
            var x = fromX
            var y = fromY
            while (x != xLimit) {
                val rx = if (steep) y else x
                val ry = if (steep) x else y
                if (rx !in 0 until img.width || ry !in 0 until img.height) break
                if ((state == 1) == img.dark(rx, ry)) {
                    if (state == 2) return Math.hypot((x - fromX).toDouble(), (y - fromY).toDouble()).toFloat()
                    state++
                }
                error += dy
                if (error > 0) {
                    if (y == toY) break
                    y += ys
                    error -= dx
                }
                x += xs
            }
            return if (state == 2) Math.hypot((toX + xs - fromX).toDouble(), (toY - fromY).toDouble()).toFloat() else Float.NaN
        }
    }

    /** The alignment pattern's centre: a dark module ringed by light, 1:1:1 across and down, near where it should be. */
    private class Alignment(private val img: Bits, private val ms: Float) {
        class Found(val x: Float, val y: Float, val size: Float)

        private val found = ArrayList<Found>()

        fun near(ex: Int, ey: Int, factor: Int): Found? {
            val allowance = (factor * ms).toInt()
            val left = max(0, ex - allowance)
            val right = min(img.width - 1, ex + allowance)
            if (right - left < ms * 3) return null
            val top = max(0, ey - allowance)
            val bottom = min(img.height - 1, ey + allowance)
            if (bottom - top < ms * 3) return null
            found.clear()
            val width = right - left
            val height = bottom - top
            val maxJ = left + width
            val middle = top + height / 2
            val sc = IntArray(3)
            for (gen in 0 until height) {
                val i = middle + if (gen and 1 == 0) (gen + 1) / 2 else -((gen + 1) / 2)
                if (i !in 0 until img.height) continue
                sc.fill(0)
                var j = left
                while (j < maxJ && !img.dark(j, i)) j++
                var state = 0
                while (j < maxJ) {
                    if (img.dark(j, i)) {
                        if (state == 1) sc[1]++
                        else if (state == 2) {
                            if (cross(sc)) center(sc, i, j)?.let { return it }
                            sc[0] = sc[2]; sc[1] = 1; sc[2] = 0
                            state = 1
                        } else {
                            state++
                            sc[state]++
                        }
                    } else {
                        if (state == 1) state++
                        sc[state]++
                    }
                    j++
                }
                if (cross(sc)) center(sc, i, maxJ)?.let { return it }
            }
            return found.firstOrNull()
        }

        private fun cross(sc: IntArray): Boolean {
            val v = ms / 2f
            return (0 until 3).all { abs(ms - sc[it]) < v }
        }

        private fun fromEnd(sc: IntArray, end: Int): Float = (end - sc[2]).toFloat() - sc[1] / 2f

        private fun center(sc: IntArray, i: Int, j: Int): Found? {
            val total = sc.sum()
            val cj = fromEnd(sc, j)
            val ci = vertical(i, cj.toInt(), 2 * sc[1], total)
            if (ci.isNaN()) return null
            val s = total / 3f
            for (f in found) {
                if (abs(ci - f.y) <= s && abs(cj - f.x) <= s && (abs(s - f.size) <= 1f || abs(s - f.size) <= f.size))
                    return Found((f.x + cj) / 2f, (f.y + ci) / 2f, (f.size + s) / 2f)
            }
            found += Found(cj, ci, s)
            return null
        }

        private fun vertical(startI: Int, cj: Int, maxCount: Int, original: Int): Float {
            if (cj !in 0 until img.width) return Float.NaN
            val maxI = img.height
            val sc = IntArray(3)
            var i = startI
            while (i >= 0 && img.dark(cj, i) && sc[1] <= maxCount) { sc[1]++; i-- }
            if (i < 0 || sc[1] > maxCount) return Float.NaN
            while (i >= 0 && !img.dark(cj, i) && sc[0] <= maxCount) { sc[0]++; i-- }
            if (sc[0] > maxCount) return Float.NaN
            i = startI + 1
            while (i < maxI && img.dark(cj, i) && sc[1] <= maxCount) { sc[1]++; i++ }
            if (i == maxI || sc[1] > maxCount) return Float.NaN
            while (i < maxI && !img.dark(cj, i) && sc[2] <= maxCount) { sc[2]++; i++ }
            if (sc[2] > maxCount) return Float.NaN
            if (5 * abs(sc.sum() - original) >= 2 * original) return Float.NaN
            return if (cross(sc)) fromEnd(sc, i) else Float.NaN
        }
    }

    /** A plane-to-plane perspective map, as 3 × 3 homogeneous coefficients. */
    private class Perspective(
        val a11: Float, val a21: Float, val a31: Float, val a12: Float, val a22: Float, val a32: Float, val a13: Float, val a23: Float, val a33: Float,
    ) {
        fun apply(p: FloatArray) {
            var i = 0
            while (i < p.size) {
                val x = p[i]
                val y = p[i + 1]
                val d = a13 * x + a23 * y + a33
                p[i] = (a11 * x + a21 * y + a31) / d
                p[i + 1] = (a12 * x + a22 * y + a32) / d
                i += 2
            }
        }

        fun times(o: Perspective) = Perspective(
            a11 * o.a11 + a21 * o.a12 + a31 * o.a13, a11 * o.a21 + a21 * o.a22 + a31 * o.a23, a11 * o.a31 + a21 * o.a32 + a31 * o.a33,
            a12 * o.a11 + a22 * o.a12 + a32 * o.a13, a12 * o.a21 + a22 * o.a22 + a32 * o.a23, a12 * o.a31 + a22 * o.a32 + a32 * o.a33,
            a13 * o.a11 + a23 * o.a12 + a33 * o.a13, a13 * o.a21 + a23 * o.a22 + a33 * o.a23, a13 * o.a31 + a23 * o.a32 + a33 * o.a33,
        )

        fun adjoint() = Perspective(
            a22 * a33 - a23 * a32, a23 * a31 - a21 * a33, a21 * a32 - a22 * a31,
            a13 * a32 - a12 * a33, a11 * a33 - a13 * a31, a12 * a31 - a11 * a32,
            a12 * a23 - a13 * a22, a13 * a21 - a11 * a23, a11 * a22 - a12 * a21,
        )

        companion object {
            /** The map taking the quadrilateral (x0, y0)…(x3, y3) onto (x0p, y0p)…(x3p, y3p). */
            fun quadToQuad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
                x0p: Float, y0p: Float, x1p: Float, y1p: Float, x2p: Float, y2p: Float, x3p: Float, y3p: Float): Perspective =
                squareToQuad(x0p, y0p, x1p, y1p, x2p, y2p, x3p, y3p).times(squareToQuad(x0, y0, x1, y1, x2, y2, x3, y3).adjoint())

            fun squareToQuad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Perspective {
                val dx3 = x0 - x1 + x2 - x3
                val dy3 = y0 - y1 + y2 - y3
                if (dx3 == 0f && dy3 == 0f) return Perspective(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0f, 0f, 1f)
                val dx1 = x1 - x2
                val dx2 = x3 - x2
                val dy1 = y1 - y2
                val dy2 = y3 - y2
                val den = dx1 * dy2 - dx2 * dy1
                val a13 = (dx3 * dy2 - dx2 * dy3) / den
                val a23 = (dx1 * dy3 - dx3 * dy1) / den
                return Perspective(x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0, y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0, a13, a23, 1f)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- the grid's contents

    internal object Grid {
        /** Level L's two bits in the format information. */
        private const val LEVEL_L = 1

        /** The text in [m] (rows of modules, true dark), when it is a code [QrCode] could have written; else null. */
        fun read(m: Array<BooleanArray>): String? {
            val n = m.size
            if (n < 21 || n > 4 * MAX_VERSION + 17 || (n - 17) % 4 != 0 || m.any { it.size != n }) return null
            val version = (n - 17) / 4
            fun dark(x: Int, y: Int) = m[y][x]
            val mask = format(n, ::dark) ?: return null
            if (version >= 7 && versionOf(n, ::dark) != version) return null
            val fn = QrCode.Matrix(version).function
            val raw = QrCode.rawModules(version) / 8
            val words = ByteArray(raw)
            var i = 0
            var right = n - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until n) for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) n - 1 - vert else vert
                    if (!fn[y][x] && i < raw * 8) {
                        if (dark(x, y) != QrCode.masked(mask, x, y)) words[i ushr 3] = (words[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
                        i++
                    }
                }
                right -= 2
            }
            val data = blocks(version, words) ?: return null
            return text(version, data)
        }

        /** The mask the format information names, read from either copy within 3 bits of a valid word, and level L's alone. */
        private fun format(n: Int, dark: (Int, Int) -> Boolean): Int? {
            fun bits(at: (Int) -> Pair<Int, Int>) = (0 until 15).fold(0) { acc, i -> val (x, y) = at(i); if (dark(x, y)) acc or (1 shl i) else acc }
            val one = bits { i -> when { i <= 5 -> 8 to i; i == 6 -> 8 to 7; i == 7 -> 8 to 8; i == 8 -> 7 to 8; else -> 14 - i to 8 } }
            val two = bits { i -> if (i <= 7) n - 1 - i to 8 else 8 to n - 15 + i }
            var best = -1
            var bestD = 4
            for (level in 0 until 4) for (mask in 0 until 8) {
                val w = formatWord(level, mask)
                val d = min(Integer.bitCount(w xor one), Integer.bitCount(w xor two))
                if (d < bestD) { bestD = d; best = (level shl 3) or mask }
            }
            if (best < 0 || best ushr 3 != LEVEL_L) return null
            return best and 7
        }

        private fun formatWord(level: Int, mask: Int): Int {
            val data = (level shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return ((data shl 10) or rem) xor 0x5412
        }

        /** The version information (versions 7 to 10 here) from either copy, within 3 bits; null when neither is. */
        private fun versionOf(n: Int, dark: (Int, Int) -> Boolean): Int? {
            var a = 0
            var b = 0
            for (i in 0 until 18) {
                if (dark(n - 11 + i % 3, i / 3)) a = a or (1 shl i)
                if (dark(i / 3, n - 11 + i % 3)) b = b or (1 shl i)
            }
            var best: Int? = null
            var bestD = 4
            for (v in 7..MAX_VERSION) {
                val w = QrCode.versionBits(v)
                val d = min(Integer.bitCount(w xor a), Integer.bitCount(w xor b))
                if (d < bestD) { bestD = d; best = v }
            }
            return best
        }

        /** Each block corrected (Reed-Solomon), then the data codewords in order; null when a block can't be. */
        private fun blocks(version: Int, words: ByteArray): ByteArray? {
            val blocks = QrCode.BLOCKS[version - 1]
            val ecc = QrCode.ECC_PER_BLOCK[version - 1]
            val raw = words.size
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val parts = Array(blocks) { ByteArray(shortLen + 1) }
            var k = 0
            for (i in 0 until shortLen + 1) for (j in 0 until blocks) {
                if (i != shortLen - ecc || j >= shortBlocks) parts[j][i] = words[k++]
            }
            // ISO/IEC 18004 table 9's misdecode protection: versions 1 to 3 at level L correct fewer errors than their codewords allow.
            val protect = when (version) { 1 -> 3; 2 -> 2; 3 -> 1; else -> 0 }
            val most = (ecc - protect) / 2
            val out = java.io.ByteArrayOutputStream(raw)
            for (j in 0 until blocks) {
                val short = j < shortBlocks
                val dataLen = shortLen - ecc + if (short) 0 else 1
                val cw = IntArray(dataLen + ecc)
                for (t in 0 until dataLen) cw[t] = parts[j][t].toInt() and 0xFF
                for (t in 0 until ecc) cw[dataLen + t] = parts[j][shortLen + 1 - ecc + t].toInt() and 0xFF
                if (!ReedSolomon.correct(cw, ecc, most)) return null
                for (t in 0 until dataLen) out.write(cw[t])
            }
            return out.toByteArray()
        }

        /**
         * The one byte-mode segment, exactly as QrCode writes it: mode 0100, the count (8 bits, 16 from version 10), the
         * bytes, then up to 4 zero bits, zeros to the byte, and the pad bytes 0xEC and 0x11 in turn to the end. UTF-8.
         */
        private fun text(version: Int, data: ByteArray): String? {
            val total = data.size * 8
            var at = 0
            fun take(k: Int): Int {
                var v = 0
                repeat(k) { v = (v shl 1) or ((data[at ushr 3].toInt() ushr (7 - (at and 7))) and 1); at++ }
                return v
            }
            val countBits = if (version <= 9) 8 else 16
            if (total < 4 + countBits) return null
            if (take(4) != 0b0100) return null
            val count = take(countBits)
            if (4 + countBits + 8 * count > total) return null
            val bytes = ByteArray(count) { take(8).toByte() }
            val term = min(4, total - at)
            if (take(term) != 0) return null
            val toByte = (8 - at % 8) % 8
            if (take(toByte) != 0) return null
            var pad = 0xEC
            while (at < total) {
                if (take(8) != pad) return null
                pad = pad xor 0xEC xor 0x11
            }
            if (bytes.size > MAX_TEXT) return null
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                null
            }
        }
    }

    /** Reed-Solomon over GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1, the generator's roots α^0 … α^(ecc−1), as QrCode's. */
    internal object ReedSolomon {
        private val EXP = IntArray(512)
        private val LOG = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11D
            }
            for (i in 255 until 512) EXP[i] = EXP[i - 255]
        }

        private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
        private fun div(a: Int, b: Int): Int = if (a == 0) 0 else EXP[LOG[a] + 255 - LOG[b]]

        /** [c] (data then [ecc] check symbols, the first the highest power) corrected in place; false when more than [most] errors, or it doesn't check. */
        fun correct(c: IntArray, ecc: Int, most: Int): Boolean {
            val len = c.size
            val s = IntArray(ecc) { i -> valueAt(c, EXP[i]) }
            if (s.all { it == 0 }) return true
            // Berlekamp-Massey: the error locator, lowest power first.
            var lambda = IntArray(ecc + 1).also { it[0] = 1 }
            var prev = IntArray(ecc + 1).also { it[0] = 1 }
            var l = 0
            var m = 1
            var b = 1
            for (r in 0 until ecc) {
                var d = s[r]
                for (i in 1..l) d = d xor mul(lambda[i], s[r - i])
                if (d == 0) {
                    m++
                } else if (2 * l <= r) {
                    val t = lambda.copyOf()
                    val coef = div(d, b)
                    for (i in 0..ecc - m) lambda[i + m] = lambda[i + m] xor mul(coef, prev[i])
                    l = r + 1 - l
                    prev = t
                    b = d
                    m = 1
                } else {
                    val coef = div(d, b)
                    for (i in 0..ecc - m) lambda[i + m] = lambda[i + m] xor mul(coef, prev[i])
                    m++
                }
            }
            if (l == 0 || l > most) return false
            // Chien search: position p (power len−1−p) is wrong when its root's inverse zeroes the locator.
            val positions = ArrayList<Int>()
            for (p in 0 until len) {
                val xInv = EXP[(255 - (len - 1 - p) % 255) % 255]
                var v = 0
                var pw = 1
                for (i in 0..l) { v = v xor mul(lambda[i], pw); pw = mul(pw, xInv) }
                if (v == 0) positions += p
            }
            if (positions.size != l) return false
            // Forney (first root α^0): e = X · Ω(X⁻¹) / Λ'(X⁻¹), Ω = S·Λ mod x^ecc.
            val omega = IntArray(ecc)
            for (i in 0 until ecc) {
                var v = 0
                for (j in 0..min(i, l)) v = v xor mul(lambda[j], s[i - j])
                omega[i] = v
            }
            for (p in positions) {
                val xPow = (len - 1 - p) % 255
                val x = EXP[xPow]
                val xInv = EXP[(255 - xPow) % 255]
                var num = 0
                var pw = 1
                for (i in 0 until ecc) { num = num xor mul(omega[i], pw); pw = mul(pw, xInv) }
                // Λ'(x): the odd terms, each one power lower.
                var den = 0
                pw = 1
                val xInv2 = mul(xInv, xInv)
                var i = 1
                while (i <= l) { den = den xor mul(lambda[i], pw); pw = mul(pw, xInv2); i += 2 }
                if (den == 0) return false
                c[p] = c[p] xor mul(x, div(num, den))
            }
            // Corrected: it checks now, or it was never this code.
            return (0 until ecc).all { valueAt(c, EXP[it]) == 0 }
        }

        /** c(x), [c] the polynomial's coefficients, highest power first (Horner). */
        private fun valueAt(c: IntArray, x: Int): Int {
            var v = 0
            for (k in c) v = mul(v, x) xor k
            return v
        }
    }
}
