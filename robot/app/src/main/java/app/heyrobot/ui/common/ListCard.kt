// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.ui.theme.str

// Voice process. 1.4 Lists (lists-plan.md §7.4; LS5): the card under a list read aloud or checked ("what's on the shopping
// list"): the list's name, its open items with tick boxes, "Done (5)" collapsed, and "Open Shopping". A tick on it ticks
// the item (the panel, assistant/PanelLists, does it through the vault) and the card then offers Undo. Shown, never saved:
// History keeps the read's placeholder only (D17), and a locked panel gets no card at all.

/** One item on the card: its id (32 hex characters), its words as shown, its quantity. */
@Immutable
data class ListCardRow(val id: String, val text: String, val qty: String? = null)

/**
 * The card as drawn: [list], the list's id; [name], as shown (in the reply's language); [open] and [done], its items;
 * [line], what the last tap did ("Ticked off eggs."); [undo], that tap can be undone; [label], how the list is called, for
 * the Open button, which is in the menus' language as every card's button is (LS13: "Open Shopping" on an add's card and
 * "Open Épicerie" on a read's, in one conversation in French).
 */
@Immutable
data class ListCardView(
    val list: String,
    val name: String,
    val open: List<ListCardRow>,
    val done: List<ListCardRow>,
    val line: String? = null,
    val undo: Boolean = false,
    val label: Phrase = Phrase.plain(name),
    /** recipe-list: a recipe's card ([list] is its key, not a list): the rows' ids ticked to add, and an Add button. */
    val picks: Set<String>? = null,
)

/** A tap on the card. */
sealed class ListCardOp {
    /** [item] ticked ([done]) or put back. */
    data class Tick(val item: String, val done: Boolean) : ListCardOp()
    data object Undo : ListCardOp()
    data object Open : ListCardOp()
    /** recipe-list: add the ticked rows. */
    data object Add : ListCardOp()
}

/** The card; [onOp] null (no buttons): drawn only. */
@Composable
fun ListCard(v: ListCardView, onOp: ((ListCardOp) -> Unit)?, modifier: Modifier = Modifier) {
    var showDone by remember(v.list) { mutableStateOf(false) }
    PanelCard(modifier) {
        CardHeader(v.name, supporting = v.line, leading = { CardIcon(RIcons.Checklist) }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            for (r in v.open) Item(r, done = v.picks?.contains(r.id) ?: false, onOp)
            if (v.picks == null && v.done.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { showDone = !showDone }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lists_done_count, v.done.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(RIcons.Chevron, null, Modifier.rotate(if (showDone) 270f else 90f), tint = LocalContentColor.current)
                }
                if (showDone) for (r in v.done) Item(r, done = true, onOp)
            }
        }
        if (onOp != null && v.picks != null) CardButtons {
            TextButton(onClick = { onOp(ListCardOp.Add) }, enabled = v.picks.isNotEmpty()) { Text(stringResource(R.string.lists_recipe_add, v.picks.size)) }
        } else if (onOp != null) CardButtons {
            if (v.undo) TextButton(onClick = { onOp(ListCardOp.Undo) }) { Text(stringResource(R.string.lists_undo)) }
            TextButton(onClick = { onOp(ListCardOp.Open) }) { Text(stringResource(R.string.lists_card_open, v.label.str())) }
        }
    }
}

@Composable
private fun Item(r: ListCardRow, done: Boolean, onOp: ((ListCardOp) -> Unit)?) {
    val label = stringResource(if (done) R.string.lists_card_untick else R.string.lists_card_tick, r.text)
    // The row is the target (48 dp, the whole width), "Tick off eggs" its action; the box only shows the state.
    val m = if (onOp != null) Modifier.clickable(onClickLabel = label, role = Role.Checkbox) { onOp(ListCardOp.Tick(r.id, !done)) } else Modifier
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(m).semantics(mergeDescendants = true) { toggleableState = ToggleableState(done) },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = done, onCheckedChange = null, enabled = onOp != null, modifier = Modifier.padding(start = 4.dp, end = 16.dp))
        Text(r.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            color = if (done) LocalContentColor.current.copy(alpha = 0.7f) else LocalContentColor.current)
        r.qty?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp)) }
    }
}
