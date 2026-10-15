// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp

/**
 * What TalkBack and a finger need from every node that can be tapped or toggled, on each window shown (a dialog is a
 * window of its own): something to read (text or a content description), at least 48 x 48 dp of touch area, and no
 * touch area overlapping another's. Compose widens a small target's touch area to 48 dp, so crowded small targets show
 * up as overlapping areas. Google's Accessibility Test Framework runs under Robolectric but missed an unlabelled 24 dp
 * target there, so the semantics tree is checked directly. Returns the problems found, empty when none.
 */
fun ComposeContentTestRule.touchTargetProblems(): List<String> {
    val min = with(density) { 48.dp.toPx() } - 0.5f
    val out = ArrayList<String>()
    for (root in onAllNodes(isRoot(), useUnmergedTree = false).fetchSemanticsNodes()) {
        val targets = ArrayList<Pair<String, Rect>>()
        fun walk(n: SemanticsNode) {
            val c = n.config
            // Only what is on screen counts: a lazy list composes the next rows before they scroll in, and a row scrolled
            // out of a list is clipped to nothing.
            if (!n.layoutInfo.isPlaced || n.boundsInRoot.width <= 0f || n.boundsInRoot.height <= 0f) return
            // A row the list has scrolled partly out of view (under a top bar) is cut at the list's edge: only targets
            // wholly on screen are measured. What is inside it is still looked at.
            val whole = n.boundsInRoot.width >= n.size.width - 1f && n.boundsInRoot.height >= n.size.height - 1f
            if (whole && c.getOrNull(SemanticsActions.OnClick) != null) {
                val label = (c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" ").trim()
                val r = n.touchBoundsInRoot
                if (r.width < min || r.height < min) out += "too small (${r.width.toInt()} x ${r.height.toInt()} px): '$label'"
                if (label.isEmpty()) out += "no label at ${n.boundsInRoot}"
                targets += label to r
            }
            n.children.forEach(::walk)
        }
        walk(root)
        for (i in targets.indices) for (j in i + 1 until targets.size) {
            val a = targets[i].second
            val b = targets[j].second
            val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (w > 1f && h > 1f) out += "overlapping targets: '${targets[i].first}' and '${targets[j].first}'"
        }
    }
    return out
}
