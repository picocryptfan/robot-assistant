// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.connect.ConnCheck
import app.heyrobot.core.connect.ConnOp
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HaEntity
import app.heyrobot.core.connect.HaPick
import app.heyrobot.core.connect.HaPickers
import app.heyrobot.core.connect.SkillEditor
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.ui.theme.str

// VOICE PROCESS (the connection window; docs/plans/skills-connections-plan.md §3.1, §6.3; C9). Home Assistant's operation
// picker: what Test just read (HaPickerHold, in this process's memory only) offers a service to call, a state to read or a
// script to run, and the entities it targets; you title it. Core HaPick makes the operation and ConnCheck checks it when
// it's saved. Entity names are your Home Assistant's words, shown as information beside each id, never kept. What the
// operation does is shown before you save it ("locks, unlocks or opens").

private enum class HaWhat { CALL, READ, SCRIPT }

/** At most this many rows a list shows at once; the search narrows it. */
private const val SHOWN = 40

@Composable
fun HaOpPicker(c: Connection, p: HaPickers, onCancel: () -> Unit, onSave: (ConnOp) -> Unit) {
    var what by remember { mutableStateOf(HaWhat.CALL) }
    var search by remember { mutableStateOf("") }
    var service by remember { mutableStateOf<Pair<String, String>?>(null) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var title by remember { mutableStateOf("") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val focus = LocalFocusManager.current
    fun opId(base: String): String {
        val b = base.lowercase().replace(Regex("[^a-z0-9_]"), "_").trimStart('_', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9').take(28).ifEmpty { "op" } // l10n: not text (an operation id)
        return generateSequence(1) { it + 1 }.map { n -> if (n == 1) b else b + n }.first { id -> SkillStep.validOpId(id) && c.op(id) == null }
    }
    val made: ConnOp? = when (what) {
        HaWhat.CALL -> service?.let { (d, sv) -> HaPick.call(opId("${d}_$sv"), title.trim(), d, sv, picked.toList().sorted(), p) }
        HaWhat.READ -> picked.singleOrNull()?.let { HaPick.read(opId("read_" + it.substringAfter('.')), title.trim(), it, p) }
        HaWhat.SCRIPT -> HaPick.script(opId("script_" + (picked.singleOrNull()?.substringAfter('.') ?: "run")), title.trim(), picked.toList().sorted(), p)
    }
    Text(stringResource(R.string.conn_pick_title, c.name), style = MaterialTheme.typography.headlineSmall)
    Column {
        for (w in HaWhat.entries) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(what == w, role = Role.RadioButton) {
            what = w; picked = emptySet(); service = null; search = ""
        }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(what == w, onClick = null)
            Text(stringResource(when (w) { HaWhat.CALL -> R.string.conn_pick_call; HaWhat.READ -> R.string.conn_pick_read; HaWhat.SCRIPT -> R.string.conn_pick_script }),
                modifier = Modifier.weight(1f))
        }
    }
    OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.conn_pick_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val q = search.trim().lowercase()
    if (what == HaWhat.CALL && service == null) {
        Text(stringResource(R.string.conn_pick_service), style = MaterialTheme.typography.titleSmall)
        val all = p.services.values.flatten().filter { q.isEmpty() || "${it.domain}.${it.service}".contains(q) }
        for (sv in all.take(SHOWN)) TextButton(onClick = { service = sv.domain to sv.service; search = ""; focus.clearFocus() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("${sv.domain}.${sv.service}", modifier = Modifier.fillMaxWidth()) // l10n: not text (Home Assistant's service id)
        }
        if (all.size > SHOWN) Text(stringResource(R.string.conn_pick_more, all.size - SHOWN), color = muted)
        if (all.isEmpty()) Text(stringResource(R.string.conn_pick_no_match), color = muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { Text(UiStrings.cancel().str()) } }
        return
    }
    service?.let { (d, sv) ->
        Text("$d.$sv", style = MaterialTheme.typography.titleSmall) // l10n: not text (Home Assistant's service id)
        TextButton(onClick = { service = null; picked = emptySet(); search = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_pick_change)) }
    }
    val targets: List<HaEntity> = when (what) {
        HaWhat.CALL -> service?.let { (d, sv) -> p.targets(d, sv) }.orEmpty()
        HaWhat.READ -> p.entities
        HaWhat.SCRIPT -> p.entities.filter { it.domain == "script" } // l10n: not text (Home Assistant's domain)
    }
    Text(stringResource(if (what == HaWhat.READ) R.string.conn_pick_entity else R.string.conn_pick_entities), style = MaterialTheme.typography.titleSmall)
    val shown = targets.filter { q.isEmpty() || it.id.contains(q) || it.name?.lowercase()?.contains(q) == true }
    for (e in (shown.filter { it.id in picked } + shown.filter { it.id !in picked }).take(SHOWN)) {
        val on = e.id in picked
        val toggle = { picked = if (what == HaWhat.READ) setOf(e.id) else if (on) picked - e.id else if (picked.size < ConnCheck.MAX_OPTIONS) picked + e.id else picked }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Checkbox) { toggle() }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(e.id) // l10n: not text (Home Assistant's entity id)
                e.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            }
        }
    }
    if (shown.size > SHOWN) Text(stringResource(R.string.conn_pick_more, shown.size - SHOWN), color = muted)
    if (shown.isEmpty()) Text(stringResource(if (targets.isEmpty()) R.string.conn_pick_no_targets else R.string.conn_pick_no_match), color = muted)
    OutlinedTextField(title, { title = it.take(ConnCheck.MAX_TITLE) }, label = { Text(stringResource(R.string.conn_op_title)) },
        supportingText = { Text(stringResource(R.string.conn_pick_title_help)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val ok = made != null && ConnCheck.validLine(title.trim(), ConnCheck.MAX_TITLE)
    if (made != null) {
        val chips = SkillEditor.chips(HaPick.effects(c, made))
        if (chips.isNotEmpty()) Text(stringResource(R.string.conn_pick_does, Phrase.ListOf(chips).str()), style = MaterialTheme.typography.bodyMedium)
    }
    // A Save that can't save says what it still needs, never nothing.
    val needs = when {
        ok || targets.isEmpty() -> null
        picked.isEmpty() -> if (what == HaWhat.READ) R.string.conn_pick_needs_entity else R.string.conn_pick_needs_entities
        made == null -> R.string.conn_pick_needs_other
        else -> R.string.conn_pick_needs_title
    }
    if (needs != null) Text(stringResource(needs), style = MaterialTheme.typography.bodyMedium, color = muted)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.cancel().str()) }
        Button(onClick = { made?.let(onSave) }, enabled = ok, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.save().str()) }
    }
}
