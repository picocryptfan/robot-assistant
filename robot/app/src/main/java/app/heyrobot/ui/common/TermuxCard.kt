// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TermuxStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.termux.CommandAnalyzer
import app.heyrobot.core.termux.TERMUX_LIMITS
import app.heyrobot.core.termux.limitLabel
import app.heyrobot.core.termux.whereText
import app.heyrobot.ui.theme.RobotTokens
import app.heyrobot.ui.theme.str

// Main and voice processes. A Termux command's card (0.9), in the panel's bubbles: the
// command in monospace (Edit makes it a text field, read again for warnings as you type), Robot's own warnings as chips
// and a red line for one that sends right away, Gemma's reason labelled as Gemma's, how Robot splits the arguments when
// quotes or spaces matter, the folder and the time limit (changeable for this run); Run / Edit / Cancel, and Open in
// Termux for a command that needs a terminal. While it runs: Stop. After: "Exit 0 · 0.8 s" and the output (cleaned,
// secrets hidden), its first lines with Show all, and Copy (marked sensitive). 1.1: the card's words are in
// strings_termux.xml and core's TermuxStrings (its lines are phrases, drawn in the screen's languages), with their French.

/** What a Termux card shows (built in the voice process from TermuxTool's card and live state). */
data class TermuxCardView(
    /** The action's key (TermuxTool's live state is found by it). */
    val key: String,
    /** The command as written, invisible characters shown (CommandAnalyzer.visible). */
    val command: String,
    /** Your script's name, for a script card (no Edit: its arguments are its own). */
    val script: String? = null,
    val chips: List<Phrase> = emptyList(),
    /** "This sends a text right away. Robot's own texting only opens a draft." */
    val red: Phrase? = null,
    /** Gemma's reason, labelled as Gemma's. */
    val why: String? = null,
    val readsAs: Phrase? = null,
    /** "Written from content Robot read" (rule 3). */
    val fromContent: Boolean = false,
    /** Needs a terminal: Open in Termux. */
    val terminal: Boolean = false,
    /** Only a tap runs it (said under the buttons by the card's line). */
    val tapOnly: Boolean = false,
    val where: String = "~",
    val limitSeconds: Int = 60,
    val editable: Boolean = true,
    /** The output is personal ("your Termux output"). */
    val personal: Boolean = false,
    /** The command as you edited it (it ran, or runs, instead). */
    val edited: String? = null,
    /** You're editing it on the card: only a tap on Run answers it. */
    val editing: Boolean = false,
    val running: Boolean = false,
    /** After the run (or a stop): History's line, drawn as the card's headline ("Ran a command in Termux (exit 0)"). */
    val saved: Phrase? = null,
    /** After the run: "Exit 0 · 0.8 s", or why it didn't run. */
    val exitLine: Phrase? = null,
    /** The output as the card keeps it (16 KB at most). */
    val output: String? = null,
    /** "4.1 KB". */
    val size: Phrase? = null,
    /** Termux or Robot cut the output. */
    val cut: Boolean = false,
    /** Secrets hidden in the output. */
    val masked: Int = 0,
)

/** A Termux card's buttons besides Run (unchanged) and Cancel: the panel's PanelActions.termux. */
sealed class TermuxCardChange {
    /** Run, with [command] edited on the card and another [limitSeconds] for this run (null: as it was). */
    data class Run(val command: String?, val limitSeconds: Int?) : TermuxCardChange()
    /** Edit was tapped: a yes no longer answers the card. */
    data object Editing : TermuxCardChange()
    data object OpenInTermux : TermuxCardChange()
    /** Stop while it runs: the panel's Stop (the run's whole process tree ends). */
    data object Stop : TermuxCardChange()
}

/** The output's first lines shown before Show all. */
private const val OUTPUT_LINES = 12

/**
 * The card. [a] is the action (its summary, status and line); [t] the Termux part. [onConfirm]: Run (true) and Cancel;
 * [change]: Edit, another limit, Open in Termux, Stop. History passes neither: no buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermuxCard(a: ActionView, t: TermuxCardView, onConfirm: ((String, Boolean) -> Unit)?, change: ((String, TermuxCardChange) -> Unit)?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    // Waiting for Run (not once it runs: the card stays the turn's pending one until the result is in).
    val waiting = a.status == ActionStatus.PENDING && onConfirm != null && !t.running
    var editing by rememberSaveable(a.id) { mutableStateOf(false) }
    var text by rememberSaveable(a.id) { mutableStateOf(t.edited ?: t.command) }
    var limit by rememberSaveable(a.id) { mutableStateOf(t.limitSeconds) }
    var limits by remember { mutableStateOf(false) }
    var all by rememberSaveable(a.id) { mutableStateOf(false) }
    val ctx = LocalContext.current
    val runLabel = UiStrings.run().str()
    val clipLabel = stringResource(R.string.termux_card_clip)
    PanelCard(modifier.widthIn(max = 340.dp)) {
        val bad = a.status in setOf(ActionStatus.FAILED, ActionStatus.INVALID, ActionStatus.NEEDS_UNLOCK, ActionStatus.DISABLED, ActionStatus.NEEDS_PERMISSION,
            ActionStatus.NOT_FOUND, ActionStatus.NO_APP)
        CardHeader(
            headline = when {
                a.status == ActionStatus.PENDING -> if (t.script != null) TermuxStrings.runScript("name" to t.script).str() else stringResource(R.string.termux_card_run_in)
                else -> t.saved?.str() ?: a.summary
            },
            supporting = when {
                t.running -> stringResource(R.string.termux_card_running)
                t.exitLine != null -> t.exitLine.str()
                a.status == ActionStatus.PENDING && t.editing -> TermuxStrings.editing("run" to UiLabel(UiStrings.run)).str()
                a.status == ActionStatus.PENDING -> a.detail ?: stringResource(R.string.termux_card_check)
                else -> cardStatusLine(a)
            },
            error = bad && !t.running,
            leading = { CardIcon(RIcons.Terminal) },
            trailing = if (a.status.succeeded && !t.running) ({ Icon(RIcons.Check, a.status.label, tint = c.primary) }) else null,
        )
        // The command: what runs, exactly.
        if (editing && waiting) {
            val analysis = remember(text) { CommandAnalyzer.analyze(text) }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                label = { Text(stringResource(R.string.termux_card_command)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Chips(analysis.chipTexts, analysis.warnings.firstOrNull { it.red }?.text)
        } else {
            val commandDesc = stringResource(R.string.termux_card_command_desc, t.edited ?: t.command)
            Surface(
                color = c.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text("$ " + (t.edited ?: t.command), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { contentDescription = commandDesc })
            }
            if (t.edited == null) Chips(t.chips, t.red) else Chips(CommandAnalyzer.analyze(t.edited).chipTexts, null)
        }
        val small = MaterialTheme.typography.bodySmall
        val quiet = c.onSecondaryContainer
        if (!editing) t.readsAs?.let { Text(stringResource(R.string.termux_card_reads_as, it.str()), style = small, color = quiet, modifier = Modifier.padding(top = 6.dp)) }
        if (t.why != null && t.edited == null) {
            val whyDesc = stringResource(R.string.termux_card_gemma_desc, t.why)
            Text(stringResource(R.string.termux_card_gemma, t.why), style = small.copy(fontStyle = FontStyle.Italic), color = quiet, modifier = Modifier.padding(top = 6.dp)
                .semantics { contentDescription = whyDesc })
        }
        if (t.fromContent) Text(stringResource(R.string.termux_card_from_content), style = small.copy(fontWeight = FontWeight.SemiBold), color = c.error,
            modifier = Modifier.padding(top = 6.dp))
        // Where, and the time limit (changeable for this run while it waits).
        Box(Modifier.padding(top = 4.dp)) {
            val line = listOfNotNull(whereText(t.where, limit).str(), "▾".takeIf { waiting }).joinToString(" ")
            if (waiting && change != null) {
                val changeLabel = stringResource(R.string.termux_card_change_limit)
                Text(line, style = small, color = quiet, modifier = Modifier.heightIn(min = RobotTokens.minTouch).clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClickLabel = changeLabel) { limits = true }.padding(horizontal = 4.dp, vertical = 14.dp))
                DropdownMenu(expanded = limits, onDismissRequest = { limits = false }) {
                    for (s in TERMUX_LIMITS) DropdownMenuItem(text = { Text(limitLabel(s)) }, onClick = { limit = s; limits = false })
                }
            } else {
                Text(line, style = small, color = quiet, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        // While it waits: Cancel, Edit, Run; Open in Termux for a command that needs a terminal.
        if (waiting) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onConfirm!!(a.id, false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.cancel().str()) }
                if (t.editable && !editing && change != null) {
                    TextButton(onClick = { editing = true; change(a.id, TermuxCardChange.Editing) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.termux_card_edit))
                    }
                }
                Button(onClick = {
                    val edited = if (editing) text else null
                    if ((edited != null && edited != t.command) || limit != t.limitSeconds) change?.invoke(a.id, TermuxCardChange.Run(edited, limit))
                    else onConfirm!!(a.id, true)
                }, modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp)) { Text(runLabel) }
            }
            if (t.terminal && change != null) {
                TextButton(onClick = { change(a.id, TermuxCardChange.OpenInTermux) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.termux_card_open_instead))
                }
            }
        }
        if (t.running && change != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { change(a.id, TermuxCardChange.Stop) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.stop().str()) }
            }
        }
        // After the run: the output.
        t.output?.let { out ->
            val lines = remember(out) { out.lines() }
            val shown = if (all || lines.size <= OUTPUT_LINES) out else lines.take(OUTPUT_LINES).joinToString("\n")
            Surface(
                color = c.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Box(Modifier.heightIn(max = if (all) 360.dp else 240.dp).verticalScroll(rememberScrollState())) {
                    Text(shown, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            val notes = listOfNotNull(if (t.cut) stringResource(R.string.termux_card_cut) else null,
                if (t.masked > 0) pluralStringResource(R.plurals.termux_card_secrets, t.masked, t.masked) else null)
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = small, color = quiet, modifier = Modifier.padding(top = 6.dp))
            if (onConfirm != null || change != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (lines.size > OUTPUT_LINES) TextButton(onClick = { all = !all }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (all) stringResource(R.string.termux_card_show_less)
                            else t.size?.let { stringResource(R.string.termux_card_show_all_size, it.str()) } ?: stringResource(R.string.termux_card_show_all))
                    }
                    TextButton(onClick = { copySensitive(ctx, clipLabel, out) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(RIcons.Copy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.termux_card_copy))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(chips: List<Phrase>, red: Phrase?) {
    val c = MaterialTheme.colorScheme
    if (chips.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in chips) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.errorContainer).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(RIcons.Warning, null, Modifier.size(16.dp), tint = c.onErrorContainer)
                    Spacer(Modifier.width(4.dp))
                    Text(w.str(), style = MaterialTheme.typography.labelMedium, color = c.onErrorContainer)
                }
            }
        }
    }
    red?.let { Text(it.str(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.error, modifier = Modifier.padding(top = 8.dp)) }
}

/** Copy, marked sensitive so the keyboard's clipboard preview and the system's copy notice don't show it. */
private fun copySensitive(ctx: Context, label: String, text: String) {
    try {
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    } catch (_: Exception) {}
}
