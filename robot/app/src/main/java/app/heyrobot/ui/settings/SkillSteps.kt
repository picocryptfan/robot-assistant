// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import app.heyrobot.ui.common.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.connect.ArgFill
import app.heyrobot.core.connect.ArgSlot
import app.heyrobot.core.connect.CondSubject
import app.heyrobot.core.connect.ConnSetup
import app.heyrobot.core.connect.OfferSection
import app.heyrobot.core.connect.SkillEditor
import app.heyrobot.core.connect.StepOffer
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.skills.IfTest
import app.heyrobot.core.skills.OnFail
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCards
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillEdit
import app.heyrobot.core.skills.SkillIf
import app.heyrobot.core.skills.SkillNumbers
import app.heyrobot.core.skills.SkillRef
import app.heyrobot.core.skills.SkillTemplate
import app.heyrobot.core.skills.StepKind
import app.heyrobot.ui.Mark
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.RadioDialog
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.launch

// MAIN PROCESS (docs/plans/skills-connections-plan.md §6.4; C9). A skill's steps in Settings: Add a step's sheet (your
// connections' operations, travel time, app hand-offs, your typed Termux scripts, Say something) and each step's page:
// its arguments, each a value you type, a question asked each time, or an earlier step's value, picked from lists (no
// typing `{{…}}`); a text argument's words may hold a question or a value as a chip; Only if…; If it doesn't work. Core's
// SkillEditor makes each change and SkillCheck is the gate before every save. Robot's own actions stay taught by voice.

object SkillStepRoutes {
    fun step(id: String, i: Int) = "skills/$id/step/$i" // l10n: not text (a route)

    /** The step a route names: (skill id, index), or null. */
    fun of(route: String): Pair<String, Int>? = Regex("^skills/(s-[a-z0-9]{6})/step/(\\d{1,2})$").matchEntire(route)?.let { // l10n: not text (a route)
        it.groupValues[1] to it.groupValues[2].toInt()
    }
}

/** An offer's words in the sheet. */
@Composable
fun offerText(o: StepOffer): String = when (o) {
    is StepOffer.Operation -> stringResource(R.string.conn_offer_op, o.op.title, o.conn.name)
    is StepOffer.Travel -> stringResource(R.string.conn_offer_travel, o.name)
    is StepOffer.HandOff -> ConnStrings.handOffCard("name" to o.conn.name).str()
    is StepOffer.Script -> pluralStringResource(R.plurals.conn_offer_script, o.info.decl.params.size, o.info.name, o.info.decl.params.size)
    StepOffer.Say -> stringResource(R.string.conn_offer_say)
}

@Composable
private fun sectionTitle(s: OfferSection): String = stringResource(when (s) {
    OfferSection.CONNECTIONS -> R.string.conn_section_connections
    OfferSection.TRAVEL -> R.string.conn_section_travel
    OfferSection.HAND_OFFS -> R.string.conn_section_hand_offs
    OfferSection.SCRIPTS -> R.string.conn_section_scripts
    OfferSection.SAY -> R.string.conn_section_say
})

/**
 * Add a step's sheet (plan §6.4): [offers] by section. Picking Say asks its words first. [onConnections]: Settings ›
 * Skills › Connections, to add one.
 */
@Composable
fun StepOffersDialog(offers: List<StepOffer>, onDismiss: () -> Unit, onConnections: () -> Unit, onPick: (StepOffer, String) -> Unit) {
    var saying by rememberSaveable { mutableStateOf(false) }
    if (saying) {
        EditDialog(stringResource(R.string.conn_offer_say), "", stringResource(R.string.conn_say_words), stringResource(R.string.conn_say_words_help),
            validate = { if (it.length > app.heyrobot.core.RoutineCheck.MAX_SAY) "" else null }, canSave = { it.isNotBlank() }, onDismiss = onDismiss) {
            onPick(StepOffer.Say, it)
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conn_add_step)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (section in OfferSection.entries) {
                    val here = offers.filter { it.section == section }
                    if (here.isEmpty() && section != OfferSection.CONNECTIONS) continue
                    Text(sectionTitle(section), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    if (here.isEmpty()) TextButton(onClick = { onDismiss(); onConnections() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.conn_offer_none))
                    }
                    for (o in here) TextButton(onClick = { if (o == StepOffer.Say) saying = true else onPick(o, "") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(offerText(o), modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}

/** A question's or an output's words: "Who should I tell?", or "minutes from step 1". */
@Composable
private fun refText(s: Skill, r: SkillRef): String {
    val langs = LocalLangs.current
    if (r.output == null) return s.param(r.name)?.let { if (langs.ui == Lang.FR) it.askFr else it.askEn } ?: r.name
    val step = s.steps.indexOfFirst { it.id == r.name }
    return stringResource(R.string.conn_from_step, r.output!!, if (step >= 0) step + 1 else 0)
}

/** What an argument holds, in words. */
@Composable
private fun fillText(s: Skill, slot: ArgSlot, f: ArgFill): String = when (f) {
    ArgFill.Empty -> stringResource(if (slot.required) R.string.conn_arg_empty else R.string.conn_arg_default)
    is ArgFill.Fixed -> when (val v = f.value) {
        is Boolean -> stringResource(if (v) R.string.conn_yes else R.string.conn_no)
        else -> SkillTemplate.text(v)
    }
    is ArgFill.Value -> if (f.ref.output == null) stringResource(R.string.conn_arg_asked, refText(s, f.ref)) else refText(s, f.ref)
    is ArgFill.Words -> f.parts.joinToString("") { p -> when (p) { is SkillTemplate.Part.Words -> p.text; is SkillTemplate.Part.Ref -> "«${p.ref}»" } }
}

/** How an argument is filled, as its dialog offers it. */
private sealed interface ArgWay {
    data object Type : ArgWay
    data object Ask : ArgWay
    data object Words : ArgWay
    data object Clear : ArgWay
    data class Use(val ref: SkillRef) : ArgWay
}

/** A condition, as Only if… offers it. */
private sealed interface CondWay {
    data object Always : CondWay
    data class Test(val subject: CondSubject, val test: IfTest) : CondWay
    data class Number(val subject: CondSubject, val below: Boolean) : CondWay
}

/** A step's page (plan §6.4): its arguments, Only if…, and If it doesn't work. */
@Composable
fun SkillStepScreen(s: Skill, i: Int, d: SkillsData, act: SkillActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val st = s.steps.getOrNull(i) ?: return
    val links = d.links
    val scope = rememberCoroutineScope()
    val langs = LocalLangs.current
    val doesntFit = stringResource(R.string.conn_doesnt_fit)
    var choosing by remember { mutableStateOf<ArgSlot?>(null) }
    var typing by remember { mutableStateOf<ArgSlot?>(null) }
    var asking by remember { mutableStateOf<ArgSlot?>(null) }
    var wording by remember { mutableStateOf<ArgSlot?>(null) }
    var conditioning by rememberSaveable { mutableStateOf(false) }
    var number by remember { mutableStateOf<CondWay.Number?>(null) }
    fun edit(new: Skill?) {
        if (new == null) { scope.launch { snackbar.showSnackbar(doesntFit) }; return }
        val problem = SkillCheck.problems(new).firstOrNull()
        if (problem != null) { scope.launch { snackbar.showSnackbar(problem.render(langs)) }; return }
        act.save(SkillEdit.saved(s, new, System.currentTimeMillis()))
    }
    val slots = SkillEditor.slots(s, i, d.connections, d.scripts)
    val tValues = stringResource(R.string.conn_step_values)
    val tGoOn = stringResource(R.string.conn_go_on)
    val tStop = stringResource(R.string.conn_stop)
    PreferenceScreen(stringResource(R.string.conn_step_title, i + 1), onBack, snackbar = snackbar) {
        item(key = "what") { IntroPreference(SkillCards.stepText(s, st, links = links).str().replace("*", "")) }
        val attention = SkillCheck.attention(s, links).filter { it.step == i }
        if (attention.isNotEmpty()) preferenceGroup(null, "attention") {
            for (a in attention) StatusPreference(ConnSetup.attention(a.step, a.why).str(), null, Mark.WARN)
        }
        if (slots.isNotEmpty()) preferenceGroup(tValues, "args") {
            for (slot in slots) {
                val f = SkillEditor.fill(s, i, slot)
                PreferenceItem(slot.label ?: slot.name, fillText(s, slot, f), onClick = { choosing = slot })
                // C8: a number question wider than what the script takes.
                if (st.kind == StepKind.TERMUX_SCRIPT && f is ArgFill.Value && f.ref.output == null) {
                    s.param(f.ref.name)?.let { q -> ConnSetup.rangeWide(q, slot.min, slot.max) }?.let { StatusPreference(it.str(), null, Mark.WARN) }
                }
            }
        } else if (st.kind.tool != null) item(key = "voice") { IntroPreference(stringResource(R.string.conn_step_by_voice)) }
        if (st.kind == StepKind.TERMUX_SCRIPT) {
            val info = d.scripts.firstOrNull { it.name == st.args["script"] }
            val why = info?.let { app.heyrobot.core.termux.ScriptParams.why(it.paramLines) }
            if (why != null) preferenceGroup(null, "script") { StatusPreference(ConnSetup.scriptWhy(why).str(), null, Mark.WARN) }
        }
        preferenceGroup(null, "when") {
            PreferenceItem(stringResource(R.string.conn_only_if), st.cond?.let { SkillEditor.condText(it).str() } ?: stringResource(R.string.conn_always),
                onClick = { conditioning = true })
            val ways = SkillEditor.onFailWays(s, i, links)
            ListPreference(stringResource(R.string.conn_on_fail), st.onFail, ways, { if (it == OnFail.GO_ON) tGoOn else tStop },
                enabled = ways.size > 1, summary = if (ways.size > 1) null else stringResource(R.string.conn_stop_only)) {
                edit(SkillEditor.setOnFail(s, i, it, links))
            }
        }
    }
    choosing?.let { slot ->
        val uses = SkillEditor.values(s, i, slot, links)
        val ways = buildList {
            add(ArgWay.Type)
            if (s.params.size < SkillCheck.MAX_PARAMS && (slot.type != ParamType.CHOICE || slot.options.size in SkillCheck.MIN_OPTIONS..SkillCheck.MAX_OPTIONS) &&
                ((slot.type != ParamType.INTEGER && slot.type != ParamType.NUMBER) || (slot.min != null && slot.max != null))) add(ArgWay.Ask)
            if (slot.text && SkillEditor.chips(s, i, links).isNotEmpty()) add(ArgWay.Words)
            for (r in uses) add(ArgWay.Use(r))
            if (!slot.required) add(ArgWay.Clear)
        }
        val now = when (val f = SkillEditor.fill(s, i, slot)) { is ArgFill.Value -> ArgWay.Use(f.ref); is ArgFill.Words -> ArgWay.Words; else -> ArgWay.Type }
        val labels = ways.associateWith { w ->
            when (w) {
                ArgWay.Type -> stringResource(R.string.conn_way_type)
                ArgWay.Ask -> stringResource(R.string.conn_way_ask)
                ArgWay.Words -> stringResource(R.string.conn_way_words)
                ArgWay.Clear -> stringResource(R.string.conn_way_clear)
                is ArgWay.Use -> if (w.ref.output == null) stringResource(R.string.conn_arg_asked, refText(s, w.ref)) else refText(s, w.ref)
            }
        }
        RadioDialog(slot.label ?: slot.name, now, ways, { labels.getValue(it) }, onDismiss = { choosing = null }) { w ->
            choosing = null
            when (w) {
                ArgWay.Type -> typing = slot
                ArgWay.Ask -> asking = slot
                ArgWay.Words -> wording = slot
                ArgWay.Clear -> edit(SkillEditor.set(s, i, slot, null))
                is ArgWay.Use -> edit(SkillEditor.pick(s, i, slot, w.ref, links))
            }
        }
    }
    typing?.let { slot ->
        val f = SkillEditor.fill(s, i, slot)
        val current = (f as? ArgFill.Fixed)?.value
        when (slot.type) {
            ParamType.CHOICE, ParamType.YES_NO -> {
                val values = if (slot.type == ParamType.CHOICE) slot.options else listOf("true", "false")
                val yes = stringResource(R.string.conn_yes)
                val no = stringResource(R.string.conn_no)
                RadioDialog(slot.label ?: slot.name, current?.toString() ?: "", values, { v -> if (slot.type == ParamType.YES_NO) (if (v == "true") yes else no) else v },
                    onDismiss = { typing = null }) { v -> typing = null; edit(SkillEditor.setConstant(s, i, slot, v)) }
            }
            else -> {
                val range = if (slot.min != null && slot.max != null) stringResource(R.string.conn_range, SkillNumbers.text(slot.min!!), SkillNumbers.text(slot.max!!)) else ""
                EditDialog(slot.label ?: slot.name, current?.let(SkillTemplate::text).orEmpty(), slot.label ?: slot.name, range,
                    validate = { t -> if (t.isBlank() || SkillEditor.constant(slot, t) != null) null else doesntFit },
                    keyboard = if (slot.type == ParamType.TEXT) KeyboardOptions.Default else KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    canSave = { it.isNotBlank() }, onDismiss = { typing = null }) { t -> typing = null; edit(SkillEditor.setConstant(s, i, slot, t)) }
            }
        }
    }
    asking?.let { slot ->
        AskDialog(slot, onDismiss = { asking = null }) { q, dflt -> asking = null; edit(SkillEditor.ask(s, i, slot, q, dflt)) }
    }
    wording?.let { slot ->
        val start = (SkillEditor.fill(s, i, slot) as? ArgFill.Words)?.let { s.steps[i].args[slot.name] as? String }
            ?: ((SkillEditor.fill(s, i, slot) as? ArgFill.Fixed)?.value as? String).orEmpty()
        WordsDialog(slot, start, SkillEditor.chips(s, i, links).map { it.first }, onDismiss = { wording = null }) { t ->
            wording = null
            edit(SkillEditor.setWords(s, i, slot, t, links))
        }
    }
    if (conditioning) {
        val subjects = SkillEditor.subjects(s, i, links)
        val ways = buildList<CondWay> {
            add(CondWay.Always)
            for (c in subjects) {
                for (t in SkillEditor.tests(c)) add(CondWay.Test(c, t))
                if (SkillEditor.numeric(c)) { add(CondWay.Number(c, true)); add(CondWay.Number(c, false)) }
            }
        }
        val labels = ways.associateWith { w ->
            when (w) {
                CondWay.Always -> stringResource(R.string.conn_always)
                is CondWay.Test -> SkillEditor.condText(SkillIf(w.subject.ref, w.test)).str()
                is CondWay.Number -> stringResource(if (w.below) R.string.conn_if_below else R.string.conn_if_above, w.subject.ref.toString())
            }
        }
        val now = st.cond?.let { c -> ways.firstOrNull { it is CondWay.Test && it.subject.ref == c.value && it.test == c.test } } ?: CondWay.Always
        RadioDialog(stringResource(R.string.conn_only_if), now, ways, { labels.getValue(it) }, onDismiss = { conditioning = false }) { w ->
            conditioning = false
            when (w) {
                CondWay.Always -> edit(SkillEditor.setCondition(s, i, null, links))
                is CondWay.Test -> edit(SkillEditor.setCondition(s, i, SkillIf(w.subject.ref, w.test), links))
                is CondWay.Number -> number = w
            }
        }
    }
    number?.let { w ->
        EditDialog(stringResource(R.string.conn_only_if), "", w.subject.ref.toString(), "", validate = { t -> if (t.isBlank() || t.replace(',', '.').toDoubleOrNull() != null) null else doesntFit },
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal), canSave = { it.isNotBlank() }, onDismiss = { number = null }) { t ->
            number = null
            val n = t.replace(',', '.').toDoubleOrNull() ?: return@EditDialog
            edit(SkillEditor.setCondition(s, i, SkillIf(w.subject.ref, if (w.below) IfTest.Below(n) else IfTest.Above(n)), links))
        }
    }
}

/** A new question for an argument: what Robot asks, and its value when your words don't give one. */
@Composable
private fun AskDialog(slot: ArgSlot, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var dflt by rememberSaveable { mutableStateOf(slot.options.firstOrNull() ?: slot.min?.let(SkillNumbers::text) ?: if (slot.type == ParamType.YES_NO) "false" else "") }
    val ok = q.isNotBlank() && q.length <= SkillCheck.MAX_ASK && dflt.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conn_way_ask)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it.take(SkillCheck.MAX_ASK) }, label = { Text(stringResource(R.string.conn_ask_question)) },
                    supportingText = { Text(stringResource(R.string.conn_ask_question_help)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dflt, { dflt = it }, label = { Text(stringResource(R.string.conn_ask_default)) },
                    supportingText = { Text(stringResource(R.string.conn_ask_default_help)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(q.trim(), dflt.trim()) }, enabled = ok) { Text(UiStrings.save().str()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}

/** `{{…}}` shown bold in the words, the text itself unchanged (so the cursor stays where it is). */
private object ChipMarks : VisualTransformation {
    private val RX = Regex("\\{\\{[a-z0-9_.]+\\}\\}")
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(buildAnnotatedString {
        append(text.text)
        for (m in RX.findAll(text.text)) addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
    }, OffsetMapping.Identity)
}

/** A text argument's words, with a question or an earlier value put in as a chip where the cursor is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordsDialog(slot: ArgSlot, start: String, chips: List<SkillRef>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(slot.label ?: slot.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(v, { v = it }, label = { Text(stringResource(R.string.conn_say_words)) }, visualTransformation = ChipMarks,
                    supportingText = { Text(stringResource(R.string.conn_words_help)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in chips) AssistChip(onClick = {
                        val t = SkillTemplate.of(r)
                        val at = v.selection.start.coerceIn(0, v.text.length)
                        v = TextFieldValue(v.text.substring(0, at) + t + v.text.substring(v.selection.end.coerceIn(at, v.text.length)), TextRange(at + t.length))
                    }, label = { Text(stringResource(R.string.conn_insert, r.toString())) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(v.text) }, enabled = v.text.isNotBlank()) { Text(UiStrings.save().str()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}
