// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

import kotlin.math.abs
import kotlin.math.hypot

// Pure. Scanning's four corners (files-1.4-plan.md §3.2, D4: you drag them by hand): a quadrilateral :files may
// straighten, and the page it becomes. :files maps the corners onto the page with Matrix.setPolyToPoly.

object ScanGeometry {
    /** The corners must enclose at least this share of the photo. */
    const val MIN_AREA = 0.01

    /**
     * The corners in a [src]-sized photo: clockwise from the top left (on screen, y down), convex, and enclosing at least
     * [MIN_AREA] of it. A crossed, folded or flat quadrilateral is refused.
     */
    fun valid(q: ScanQuad, src: Dim): Boolean {
        val xs = DoubleArray(4) { q.x(it, src.width).toDouble() }
        val ys = DoubleArray(4) { q.y(it, src.height).toDouble() }
        for (i in 0 until 4) {
            val a = (i + 1) % 4
            val b = (i + 2) % 4
            val cross = (xs[a] - xs[i]) * (ys[b] - ys[a]) - (ys[a] - ys[i]) * (xs[b] - xs[a])
            if (cross <= 0.0) return false
        }
        var area = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(area) / 2 >= MIN_AREA * src.pixels
    }

    /** The straightened page's size: its longer opposite edges, scaled down to fit [box]; null when the corners aren't [valid]. */
    fun page(q: ScanQuad, src: Dim, box: Box): Dim? {
        if (!valid(q, src)) return null
        fun len(i: Int, j: Int): Double = hypot((q.x(j, src.width) - q.x(i, src.width)).toDouble(), (q.y(j, src.height) - q.y(i, src.height)).toDouble())
        val w = maxOf(len(0, 1), len(3, 2))
        val h = maxOf(len(0, 3), len(1, 2))
        val d = Dim(maxOf(1, Math.round(w).toInt()), maxOf(1, Math.round(h).toInt()))
        return ParseLimits.fit(d, box)
    }
}
