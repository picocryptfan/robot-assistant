// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.theme.RobotTokens

// 1.0, accessibility. Two small pieces the screens share: the answer's text as TalkBack hears it, and a tick box with its
// words as one target.

/**
 * An answer's text for TalkBack. With [announce] (the panel, while replies aren't spoken aloud) it is one node, and once it
 * is complete ([streaming] false) a polite live region, so TalkBack reads the whole answer once instead of nothing, or
 * every word as it streams in. Elsewhere (History, or replies spoken aloud) nothing changes.
 */
fun Modifier.answerSemantics(streaming: Boolean, announce: Boolean): Modifier =
    if (!announce) this else semantics(mergeDescendants = true) { if (!streaming) liveRegion = LiveRegionMode.Polite }

/** A tick box and its words: the whole row is the target (48 dp or more), and TalkBack hears one tick box with its state. */
@Composable
fun TickRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
            style: TextStyle = MaterialTheme.typography.bodyLarge) {
    Row(modifier.fillMaxWidth().heightIn(min = RobotTokens.minTouch).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        // The box keeps the place a clickable Checkbox takes, so the row looks as before.
        Box(Modifier.size(RobotTokens.minTouch), contentAlignment = Alignment.Center) { Checkbox(checked = checked, onCheckedChange = null) }
        Text(text, style = style)
    }
}
