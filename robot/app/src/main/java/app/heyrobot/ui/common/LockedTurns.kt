package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.R

// Voice process (the panel). 1.0: a conversation begun on the lock screen, which anyone can talk to, continued unlocked
// (core PanelContinuity): its turns stay, set apart under "Said while the phone was locked", and a plain rule shows where
// your own, unlocked turns begin.

/** The divider above a turn: the label over the first turn said while the phone was locked, or the rule after the last. */
enum class LockedDivider { LABEL, END }

/** The divider above the turn at [index], given each turn's "said while the phone was locked" ([locked], oldest first). */
fun lockedDividerAbove(locked: List<Boolean>, index: Int): LockedDivider? = when {
    locked[index] && (index == 0 || !locked[index - 1]) -> LockedDivider.LABEL
    !locked[index] && index > 0 && locked[index - 1] -> LockedDivider.END
    else -> null
}

/** [kind]'s divider: a lock and "Said while the phone was locked" over a rule (a heading for TalkBack), or the rule alone. */
@Composable
fun LockedTurnsDivider(kind: LockedDivider, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    when (kind) {
        LockedDivider.LABEL -> Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).testTag(LOCKED_LABEL_TAG)) {
            Row(Modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                Icon(RIcons.Lock, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.panel_said_locked), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = c.outlineVariant)
        }
        LockedDivider.END -> HorizontalDivider(modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(LOCKED_END_TAG), color = c.outlineVariant)
    }
}

/** A turn of the panel's list ([content]) with [divider] above it, if any. */
@Composable
fun WithLockedDivider(divider: LockedDivider?, content: @Composable () -> Unit) {
    if (divider == null) content()
    else Column {
        LockedTurnsDivider(divider)
        content()
    }
}

const val LOCKED_LABEL_TAG = "locked_turns_label"
const val LOCKED_END_TAG = "locked_turns_end"
