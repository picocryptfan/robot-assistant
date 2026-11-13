// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import app.heyrobot.ui.common.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.NetLog
import app.heyrobot.ui.common.DropdownMenu
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.core.BrainModel
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.feedback.Diagnostic
import app.heyrobot.core.feedback.DiagnosticBlock
import app.heyrobot.core.feedback.FeedbackDiagnostics
import app.heyrobot.core.feedback.FeedbackDraft
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackFacts
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.feedback.FeedbackPrivacy
import app.heyrobot.core.feedback.FeedbackRef
import androidx.compose.material3.FilterChip
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.core.feedback.ReportCard
import app.heyrobot.sys.Contacts as ContactsOnPhone
import app.heyrobot.core.feedback.ReportFeature
import app.heyrobot.core.feedback.ReportHeader
import app.heyrobot.core.feedback.ReportPart
import app.heyrobot.core.feedback.ReportProblem
import app.heyrobot.core.feedback.ReportSort
import app.heyrobot.core.feedback.ReportShot
import app.heyrobot.core.feedback.ReportTurn
import app.heyrobot.core.feedback.FeedbackReport
import app.heyrobot.core.feedback.FeedbackSources
import app.heyrobot.core.feedback.SendTo
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.sys.FeedbackClipboard
import app.heyrobot.sys.FeedbackFiles
import app.heyrobot.sys.FeedbackSend
import app.heyrobot.sys.LookStore
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.Paths
import app.heyrobot.sys.Problems
import app.heyrobot.ui.Mark
import app.heyrobot.core.feedback.ReportKey
import app.heyrobot.sys.ReportKeys
import app.heyrobot.sys.ReportOut
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.CheckboxPreference
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceGroup
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.update.UpdateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// MAIN PROCESS (0.7). Settings › About › Send feedback, for testers: a bug report, an idea or anything else, in your own
// words, with what Robot may add ticked below (core feedback: the model problem's report, the voice log, a settings summary,
// the updater's codes), each with a Preview of exactly what it adds. Share hands the plain-text message to Android's share
// sheet (SMS, Signal, Molly...), Copy puts it on the clipboard, "Text it" opens your SMS app with the developer's number
// (from the menu), and "Open a GitHub issue" opens one in the browser when the updater has a repository. Robot sends nothing
// itself. Opened from About, the Model tab's problems, the voice log, an update error and the panel's feedback phrases; your
// draft survives a rotation or the app being stopped (saved state) and goes only when you tap Done.

/** Where the page was opened from, the kind it opens with, and a problem whose report to add (its time). */
data class FeedbackArgs(val entry: FeedbackEntry, val kind: FeedbackKind?, val problem: Long?) {
    /** The kind the page opens with: the one asked for, else a bug. */
    val startKind: FeedbackKind get() = kind ?: FeedbackKind.BUG

    companion object {
        /** "about/feedback?e=problem&k=bug&p=1760000000000" (SettingsRoutes.feedback). */
        fun of(route: String): FeedbackArgs {
            val q = route.substringAfter('?', "").split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return FeedbackArgs(FeedbackEntry.of(q["e"]), FeedbackKind.of(q["k"]), q["p"]?.toLongOrNull()?.takeIf { it > 0 })
        }
    }
}

/** What the page shows besides your draft: the header's facts and every diagnostic Robot could add now. */
data class FeedbackUi(
    val facts: FeedbackFacts,
    /** In [Diagnostic] order; a problem report only when there is a problem, the updater's only after an error (or from one). */
    val blocks: List<DiagnosticBlock>,
    /** What the last pass leaves out of the diagnostics (core FeedbackPrivacy). */
    val secrets: List<String> = emptyList(),
    /** The number "Text it" opens your SMS app with. */
    val sendTo: String? = null,
    /** The updater's repository: "Open a GitHub issue" there. */
    val github: GitHubRepo? = null,
    /** The problem report's date ("Thu, 9 Oct 2026, 14:05"). */
    val problemDate: String? = null,
    /** 1.0: the report is Robot's crash report (opened from "Robot closed unexpectedly"), not a model problem's. */
    val crash: Boolean = false,
    /** 1.4: People's names (and nicknames), hidden in the turn while "Hide names and numbers" is on (ReportHide). */
    val names: List<String> = emptyList(),
    /** 1.4: Robot may read your contacts; without it no name is hidden, and the hide note says so. */
    val contacts: Boolean = true,
    /** 1.4: loaded with a turn's names (or Contacts is off); before it, a bug's card doesn't send. */
    val named: Boolean = true,
    /** 1.4: their numbers (as stored and normalized) and postal addresses, hidden the same way, read with the names. */
    val numbers: List<String> = emptyList(),
    val addresses: List<String> = emptyList(),
    /** report-hide-people: how much of Robot's People was read (ReportPeople); what was read is in the three above. */
    val peopleRead: PeopleRead = PeopleRead.WHOLE,
)

/** fix-seal-14-d 1: People read whole (or not needed), in part (some of it hidden, the rest unread), or not at all. */
enum class PeopleRead { WHOLE, PART, NONE }

/**
 * fix-seal-14-d 2: how much of People the bug card read. Without a turn ([people] false) nothing is hidden in it, so it
 * counts as whole; [folks] null is People not read at all (ReportPeople.read: locked, damaged, too new).
 */
internal fun peopleRead(people: Boolean, folks: ReportPeople.Terms?): PeopleRead = when {
    !people || folks?.whole == true -> PeopleRead.WHOLE
    folks != null -> PeopleRead.PART
    else -> PeopleRead.NONE
}

/** The message as it leaves: its kind, text and which diagnostics it holds (the voice log's bits). */
/** [png]: RP4's screenshot, only when you added one (ReportShotHold.png), for the report file. */
data class FeedbackOut(val kind: FeedbackKind, val text: String, val bits: Int, val png: ByteArray? = null, val ref: String? = null,
    /** RP5: the parts ticked, for Reports you sent. */
    val parts: Set<ReportPart> = emptySet())

/** What the page's taps do; each returns whether an app opened (false: it says so). */
class FeedbackActions(
    val share: (FeedbackOut) -> Boolean = { true },
    /** RP5: the sealed file to the share sheet (off the main thread); [done] says whether an app took it. */
    val send: (FeedbackOut, done: (Boolean) -> Unit) -> Unit = { _, done -> done(true) },
    /** RP5: the menu's Reports you sent. */
    val sentList: () -> Unit = {},
    val copy: (FeedbackOut) -> Unit = {},
    val textIt: (FeedbackOut) -> Boolean = { true },
    val github: (FeedbackKind, FeedbackReport.IssueLink) -> Boolean = { _, _ -> true },
    /** The bug card: the report on the clipboard, then the new-issue page with its title only. */
    val githubCard: (FeedbackOut, String) -> Boolean = { _, _ -> true },
    val saveNumber: (String?) -> Unit = {},
    val say: (String) -> Unit = {},
)

/** The problem report's line under its box: Robot's crash or the model problem, and when. */
@Composable
private fun Diagnostic.summary(ui: FeedbackUi): String = when (this) {
    Diagnostic.PROBLEM -> if (ui.crash) ui.problemDate?.let { stringResource(R.string.feedback_crash_of, it) } ?: stringResource(R.string.feedback_crash)
        else ui.problemDate?.let { stringResource(R.string.feedback_problem_of, it) } ?: stringResource(R.string.feedback_problem)
    else -> detailText.str()
}

/** What its Preview button shows, for TalkBack ("Preview the voice log"). */
@Composable
private fun Diagnostic.what(): String = stringResource(when (this) {
    Diagnostic.PROBLEM -> R.string.feedback_what_problem
    Diagnostic.VOICE_LOG -> R.string.feedback_what_voice_log
    Diagnostic.SETTINGS -> R.string.feedback_what_settings
    Diagnostic.UPDATER -> R.string.feedback_what_updater
    Diagnostic.ONLINE -> R.string.everyday_feedback_what_online
})

/** The page. Your draft lives in its saved state; [prefill] (the panel's words) starts your text once, when it is empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    args: FeedbackArgs,
    ui: FeedbackUi?,
    a: FeedbackActions,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    prefill: String? = null,
    newRef: () -> String = { FeedbackRef.new() },
    /** 1.4: the turn you report a problem with (the panel's report_turn.json), its parts ticked for you. */
    turn: ReportTurn? = null,
    /** fix-seal-14 M1: a turn was kept but History couldn't give it: the report says it wasn't included. */
    turnMissing: Boolean = false,
    /** RP4: the screenshot part; null: none offered (the page as 0.7's). */
    shot: ReportShotHold? = null,
    /** RP5: the report key's fingerprint; null: no key, so plain text only (and no screenshot part). */
    sealed: String? = null,
) {
    var kindName by rememberSaveable { mutableStateOf(args.startKind.name) }
    val kind = FeedbackKind.of(kindName) ?: FeedbackKind.BUG
    var text by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var expected by rememberSaveable { mutableStateOf("") }
    var ticks by rememberSaveable { mutableIntStateOf(Diagnostic.bits(Diagnostic.defaults(args.startKind, args.entry))) }
    var ref by rememberSaveable { mutableStateOf(newRef()) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var numberOpen by rememberSaveable { mutableStateOf(false) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(prefill) {
        if (prefill != null && !prefilled) {
            if (text.isBlank()) text = prefill
            prefilled = true
        }
    }
    val available = ui?.blocks.orEmpty().map { it.diagnostic }.toSet()
    val draft = FeedbackDraft(kind, text, steps, expected, Diagnostic.of(ticks).intersect(available))
    // 1.4, the card (report-a-problem-plan.md "What's new" 2 and 3): a bug's turn parts and facts, each with its tick, the
    // turn hidden as it would go, and ReportSort's guess, changeable; the header gets v2's Feature, Problem and Parts.
    // Started from the turn in the frame it arrives (my answer unticked when it read another app: never a frame ticked), then yours.
    var cardTicks by rememberSaveable(turn?.at) {
        mutableIntStateOf(turn?.let { t -> ReportCard.ticked(t).let { s -> CARD_PARTS.foldIndexed(0) { i, b, p -> if (p in s) b or (1 shl i) else b } } } ?: CARD_ALL)
    }
    var hide by rememberSaveable { mutableStateOf(true) }
    var featureName by rememberSaveable { mutableStateOf<String?>(null) }
    var problemName by rememberSaveable { mutableStateOf<String?>(null) }
    // With a turn, or from the crash offer (RP3: the card, its facts); without (About, the Model tab) the page is 0.7's, its header v1.
    val bug = kind == FeedbackKind.BUG && (turn != null || args.entry == FeedbackEntry.CRASH)
    val cardTicked = CARD_PARTS.filterIndexed { i, _ -> cardTicks and (1 shl i) != 0 }.toSet()
    // Review L4: with Hide names on, the turn shows only once the names have loaded (fail closed); its parts say so meanwhile.
    val naming = hide && ui?.named != true
    val shown = remember(turn, hide, naming, ui?.names, ui?.numbers, ui?.addresses) { turn?.takeUnless { naming }?.let { ReportCard.shown(it, hide, ui?.names.orEmpty(), ui?.numbers.orEmpty(), ui?.addresses.orEmpty()) } }
    val guess = remember(turn, text, expected) { ReportSort.sort(ReportCard.input(args.entry, turn, text + "\n" + expected)) }
    val feature = ReportFeature.of(featureName) ?: guess.feature
    val problem = ReportProblem.of(problemName) ?: guess.problem
    val v2 = if (bug) ReportHeader.V2(feature, problem, ReportCard.parts(shown, cardTicked)) else null
    val turnText = if (bug && shown != null) ReportCard.section(shown, cardTicked) else if (turnMissing) ReportCard.NOT_INCLUDED else null
    val composed = remember(draft, ref, ui, v2, turnText) {
        ui?.let { FeedbackReport.compose(draft, ref, if (bug) ReportCard.facts(it.facts, ReportPart.FACTS in cardTicked) else it.facts, it.blocks, it.secrets,
            sorted = v2, turn = turnText) }
    }
    fun out(): FeedbackOut? = composed?.let { c -> shot?.takeIf { sealed != null }?.png().let { png ->
        FeedbackOut(kind, c.text, Diagnostic.bits(draft.ticked), png, ref, v2?.parts.orEmpty() + draft.ticked.map { ReportPart.of(it) } + listOfNotNull(png?.let { ReportPart.SCREENSHOT })) } }

    val langs = LocalLangs.current
    val intro = stringResource(R.string.feedback_intro)
    val footer = stringResource(R.string.feedback_footer)
    val addTitle = stringResource(R.string.feedback_add)
    val sendTitle = UiStrings.send().str()
    val sortTitle = stringResource(R.string.report_sort_title)
    val partsTitle = stringResource(R.string.report_parts_title)
    PreferenceScreen(UiStrings.sendFeedback().str(), onBack, snackbar = snackbar, actions = {
        // The number to text: in the overflow, as a setting of the page rather than of the message.
        Box {
            IconButton(onClick = { menu = true }) { Icon(RIcons.MoreVert, stringResource(R.string.feedback_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(ui?.sendTo?.let { stringResource(R.string.feedback_send_to_number, it) } ?: stringResource(R.string.feedback_send_to_menu)) },
                    leadingIcon = { Icon(RIcons.Message, null) }, onClick = { menu = false; numberOpen = true })
                if (ui?.sendTo != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.feedback_remove_number)) }, leadingIcon = { Icon(RIcons.Delete, null) },
                        onClick = { menu = false; a.saveNumber(null) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.report_sent_title)) }, leadingIcon = { Icon(RIcons.History, null) },
                    onClick = { menu = false; a.sentList() })
                if (sealed != null) {
                    DropdownMenuItem(text = { androidx.compose.foundation.layout.Column { Text(stringResource(R.string.report_send_plain))
                        Text(stringResource(R.string.report_send_plain_note), style = MaterialTheme.typography.bodySmall) } },
                        enabled = composed != null && (!bug || ui?.named != false), onClick = { menu = false; out()?.let { if (a.share(it)) sent = true } })
                }
            }
        }
    }) {
        item(key = "intro", contentType = "intro") { IntroPreference(intro) }
        item(key = "kind", contentType = "group") {
            PreferenceGroup(null) {
                PreferenceBlock {
                    // No overlap between the buttons (the row overlaps their borders by default): each is a whole 48 dp target.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = 0.dp) {
                        FeedbackKind.entries.forEachIndexed { i, k ->
                            SegmentedButton(
                                selected = k == kind,
                                onClick = {
                                    if (k != kind) {
                                        kindName = k.name
                                        // Each kind ticks its own defaults: a bug its diagnostics, an idea none.
                                        ticks = Diagnostic.bits(Diagnostic.defaults(k, args.entry))
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, FeedbackKind.entries.size),
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(k.labelText.str()) },
                            )
                        }
                    }
                }
            }
        }
        if (shot != null && sealed != null) item(key = "shot", contentType = "shot") { ReportShotPart(shot) }
        if (bug) preferenceGroup(sortTitle, "sort") {
            PreferenceBlock {
                Text(stringResource(R.string.report_sort_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.report_problem_row), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in ReportProblem.entries) FilterChip(selected = p == problem, onClick = { problemName = p.name },
                        label = { Text(stringResource(p.label)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
                Text(stringResource(R.string.report_feature_row), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in ReportFeature.entries) FilterChip(selected = f == feature, onClick = { featureName = f.name },
                        label = { Text(stringResource(f.label)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        if (bug) preferenceGroup(partsTitle, "card") {
            val show = stringResource(R.string.report_show)
            val none = stringResource(R.string.report_no_part)
            val reading = stringResource(R.string.report_part_reading)
            for (p in CARD_PARTS) {
                if (p != ReportPart.FACTS && shown == null && (turn == null || !naming)) continue
                if (p == ReportPart.FACTS && ui == null) continue
                val body = if (p == ReportPart.FACTS) stringResource(R.string.report_facts_note) else shown?.let { ReportCard.part(it, p) }
                val title = stringResource(p.label)
                val showLabel = stringResource(R.string.report_show_label, title)
                CheckboxPreference(title, body ?: if (p != ReportPart.FACTS && shown == null) reading else none, p in cardTicked && body != null, enabled = body != null, onCheckedChange = { on ->
                    val bit = 1 shl CARD_PARTS.indexOf(p)
                    cardTicks = if (on) cardTicks or bit else cardTicks and bit.inv()
                }) {
                    TextButton(onClick = { preview = PART + p.name }, enabled = body != null,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = showLabel }) { Text(show) }
                }
                if (p == ReportPart.ACTIONS) SwitchPreference(stringResource(R.string.report_hide),
                    stringResource(when { !hide -> R.string.report_hide_off; ui?.contacts == false && ui.peopleRead == PeopleRead.NONE -> R.string.report_hide_on_no_contacts_people
                        ui?.contacts == false && ui.peopleRead == PeopleRead.PART -> R.string.report_hide_on_no_contacts_some_people
                        ui?.contacts == false -> R.string.report_hide_on_no_contacts; ui?.peopleRead == PeopleRead.NONE -> R.string.report_hide_on_no_people
                        ui?.peopleRead == PeopleRead.PART -> R.string.report_hide_on_some_people; else -> R.string.report_hide_on }), hide, onCheckedChange = { hide = it })
            }
        }
        preferenceGroup(null, "words") {
            PreferenceBlock {
                val words = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                OutlinedTextField(text, { text = it.take(MAX_FIELD) }, label = { Text(kind.promptText.str()) }, minLines = 3, keyboardOptions = words,
                    supportingText = if (text.length >= MAX_FIELD) ({ Text(stringResource(R.string.feedback_max, Fmt.int(MAX_FIELD.toLong(), langs.ui))) }) else null,
                    modifier = Modifier.fillMaxWidth())
                if (kind == FeedbackKind.BUG) {
                    OutlinedTextField(steps, { steps = it.take(MAX_FIELD) }, label = { Text(stringResource(R.string.feedback_steps)) }, minLines = 2, keyboardOptions = words,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(expected, { expected = it.take(MAX_FIELD) }, label = { Text(stringResource(R.string.feedback_expected)) }, keyboardOptions = words,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
        preferenceGroup(addTitle, "diagnostics") {
            if (ui == null) PreferenceItem(stringResource(R.string.feedback_reading))
            else {
                for (b in ui.blocks) {
                    val d = b.diagnostic
                    val previewLabel = stringResource(R.string.feedback_preview_label, d.what())
                    CheckboxPreference(d.titleText.str(), d.summary(ui), d in draft.ticked, onCheckedChange = { on ->
                        ticks = if (on) ticks or d.bit else ticks and d.bit.inv()
                    }) {
                        TextButton(onClick = { preview = d.name }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = previewLabel }) {
                            Text(stringResource(R.string.feedback_preview))
                        }
                    }
                }
                composed?.let { c ->
                    PreferenceItem(stringResource(R.string.feedback_preview_all),
                        stringResource(if (c.cut.isNotEmpty()) R.string.feedback_chars_cut else R.string.feedback_chars, c.text.length),
                        onClick = { preview = ALL })
                }
            }
        }
        preferenceGroup(sendTitle, "send") {
            if (sent) {
                StatusPreference(stringResource(R.string.feedback_sent), stringResource(R.string.feedback_sent_detail), Mark.OK,
                    action = { Button(onClick = { sent = false; text = ""; steps = ""; expected = ""; ref = newRef(); onBack() },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.feedback_done)) } })
            }
            PreferenceBlock {
                val ready = composed != null && (!bug || ui?.named != false)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sealed != null) {
                        Button(onClick = { out()?.let { a.send(it) { ok -> if (ok) sent = true } } }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.report_send)) }
                    } else {
                        Button(onClick = { out()?.let { if (a.share(it)) sent = true } }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.feedback_share)) }
                    }
                    OutlinedButton(onClick = { out()?.let { a.copy(it); sent = true } }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.feedback_copy)) }
                    if (ui?.sendTo != null) {
                        OutlinedButton(onClick = { out()?.let { if (a.textIt(it)) sent = true } }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.feedback_text_it))
                        }
                    }
                }
                Text(ui?.sendTo?.let { stringResource(R.string.feedback_share_note_text, it) } ?: stringResource(R.string.feedback_share_note),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sealed != null) Text(stringResource(R.string.report_send_note, sealed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sealed != null && shot?.offered != null) Text(stringResource(R.string.report_send_words_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui?.github?.let { repo ->
                    TextButton(onClick = {
                        if (bug) { out()?.let { if (a.githubCard(it, FeedbackReport.cardIssue(repo, ref))) sent = true }; return@TextButton }
                        val link = FeedbackReport.issue(repo, draft, ref, if (bug) ReportCard.facts(ui.facts, ReportPart.FACTS in cardTicked) else ui.facts, ui.blocks, ui.secrets)
                        if (a.github(kind, link)) sent = true
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.feedback_github)) }
                    Text(stringResource(if (bug) R.string.feedback_github_card_note else R.string.feedback_github_note),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        preferenceFooter(footer)
    }
    preview?.let { which ->
        val title: String
        val body: String?
        if (which == ALL) {
            title = stringResource(R.string.feedback_whole)
            body = composed?.text
        } else if (which.startsWith(PART)) {
            // Exactly what the part adds: the turn as the card shows it (hidden while the switch is on), or the header's facts.
            val p = ReportPart.valueOf(which.removePrefix(PART))
            title = stringResource(p.label)
            body = if (p == ReportPart.FACTS) ui?.let { ReportCard.factsText(it.facts) } else shown?.let { ReportCard.part(it, p) }
        } else {
            val d = Diagnostic.valueOf(which)
            title = d.titleText.str()
            // Exactly what ticking it adds: the message composed with it ticked.
            body = ui?.let { FeedbackReport.compose(draft.copy(ticked = draft.ticked + d), ref, it.facts, it.blocks, it.secrets).blocks[d] }
        }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(title) },
            text = {
                SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(body ?: stringResource(R.string.feedback_nothing), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.feedback_close)) } },
        )
    }
    if (numberOpen) {
        val invalid = stringResource(R.string.feedback_number_invalid)
        EditDialog(
            stringResource(R.string.feedback_number_title), ui?.sendTo.orEmpty(), stringResource(R.string.feedback_number_field),
            stringResource(R.string.feedback_number_hint),
            validate = { t -> if (t.isBlank() || SendTo.valid(t)) null else invalid },
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone),
            onDismiss = { numberOpen = false },
            onSave = { t -> numberOpen = false; a.saveNumber(SendTo.normalize(t)) },
        )
    }
}

private const val ALL = "ALL"
private const val PART = "PART_"

/** The card's own ticks, in their order on the card (bit i: CARD_PARTS[i]); all ticked when you come from the turn. */
private val CARD_PARTS = listOf(ReportPart.SAID, ReportPart.ANSWER, ReportPart.ACTIONS, ReportPart.FACTS)
private const val CARD_ALL = 0b1111

private val ReportPart.label: Int get() = when (this) {
    ReportPart.SAID -> R.string.report_said
    ReportPart.ANSWER -> R.string.report_answer
    ReportPart.ACTIONS -> R.string.report_did
    else -> R.string.report_facts
}

private val ReportProblem.label: Int get() = when (this) {
    ReportProblem.DIDNT_UNDERSTAND -> R.string.report_problem_didnt_understand
    ReportProblem.WRONG_ANSWER -> R.string.report_problem_wrong_answer
    ReportProblem.WRONG_ACTION -> R.string.report_problem_wrong_action
    ReportProblem.CRASHED -> R.string.report_problem_crashed
    ReportProblem.SLOW -> R.string.report_problem_slow
    ReportProblem.SOUND -> R.string.report_problem_sound
    ReportProblem.LANGUAGE -> R.string.report_problem_language
}

private val ReportFeature.label: Int get() = when (this) {
    ReportFeature.TIMERS -> R.string.report_feature_timers
    ReportFeature.APPS -> R.string.report_feature_apps
    ReportFeature.MAPS -> R.string.report_feature_maps
    ReportFeature.FLASHLIGHT -> R.string.report_feature_flashlight
    ReportFeature.PEOPLE -> R.string.report_feature_people
    ReportFeature.NOTES -> R.string.report_feature_notes
    ReportFeature.CALENDAR -> R.string.report_feature_calendar
    ReportFeature.WEATHER -> R.string.report_feature_weather
    ReportFeature.PHONE -> R.string.report_feature_phone
    ReportFeature.MEMORY -> R.string.report_feature_memory
    ReportFeature.WEB -> R.string.report_feature_web
    ReportFeature.TERMUX -> R.string.report_feature_termux
    ReportFeature.SKILLS -> R.string.report_feature_skills
    ReportFeature.FILES -> R.string.report_feature_files
    ReportFeature.CONNECTIONS -> R.string.report_feature_connections
    ReportFeature.LISTS -> R.string.report_feature_lists
    ReportFeature.SUMS -> R.string.report_feature_sums
    ReportFeature.LINK -> R.string.report_feature_link
    ReportFeature.HELPERS -> R.string.report_feature_helpers
    ReportFeature.ANSWERS -> R.string.report_feature_answers
    ReportFeature.VOICE -> R.string.report_feature_voice
    ReportFeature.MODEL -> R.string.report_feature_model
    ReportFeature.UPDATES -> R.string.report_feature_updates
    ReportFeature.OTHER -> R.string.report_feature_other
}
private const val MAX_FIELD = 10_000

/** "Thu, 9 Oct 2026, 14:05". */
private fun dateTime(t: Long): String = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t))

/** Hundreds of characters, rounded up, for the voice log. */
private fun hundreds(text: String): Int = (text.length + 99) / 100

/**
 * The page wired to the phone (SettingsNav's "about/feedback" route): the facts and the diagnostics read off the main
 * thread from Robot's own folder, the panel's words taken once, the number from feedback.json, and the ways out.
 */
@Composable
internal fun FeedbackRoute(route: String, snap: Snapshot, snackbar: SnackbarHostState, onBack: () -> Unit, openSent: () -> Unit = {}) {
    // review-batch-17-18 M3: the turn and the screenshot (another app's screen) stay out of Recents and screenshots, so
    // no picture outlives the 10 minutes; always, so it fails closed.
    app.heyrobot.ui.common.SecureWindow()
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val args = remember(route) { FeedbackArgs.of(route) }
    var ui by remember { mutableStateOf<FeedbackUi?>(null) }
    var prefill by rememberSaveable { mutableStateOf<String?>(null) }
    // fix-bug-card-14 M2: the saved state keeps where the turn is (refJson: no words); the words stay in memory, read again
    // from History when Android recreates the page.
    var refJson by rememberSaveable { mutableStateOf<String?>(null) }
    var turnJson by remember { mutableStateOf<String?>(null) }
    var turnMissing by rememberSaveable { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val shot = remember { ReportShotHold() }
    // fix-bug-card-14 M3: the page's activity.
    val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull() }
    // fix-bug-card-14-c Low 2: the panel's hand (the turn in memory), taken once; MainActivity keeps only Robot's own.
    val turnHand = remember { FeedbackFiles.cardHand() }
    // RP5: the key reports are sealed to; with none, Send and the screenshot part are hidden (a screenshot couldn't go).
    val reportKey = remember { ReportKeys.key() }
    DisposableEffect(Unit) { onDispose { shot.close(); FeedbackFiles.leftCard(turnHand, activity?.isChangingConfigurations == true) } }
    fun say(t: String) { scope.launch { snackbar.showSnackbar(t) } }
    fun sayRes(id: Int, vararg args: Any) = say(ctx.getString(id, *args))
    LaunchedEffect(Unit) {
        if (!opened) {
            opened = true
            if (args.entry == FeedbackEntry.VOICE) prefill = withContext(Dispatchers.IO) { FeedbackFiles.takePrefill(app) }?.text
            // 1.4: the turn the panel kept for the card (10 minutes, also while locked), taken once.
            val r = withContext(Dispatchers.IO) { FeedbackFiles.takeTurnRef(app) }
            val t = r?.let { withContext(Dispatchers.IO) { FeedbackFiles.cardTurn(app, it, turnHand) } }
            refJson = r?.toJson()
            turnJson = t?.toJson()
            // fix-seal-14 M1: a turn was kept but History couldn't give it (locked, deleted): the report says it isn't there.
            if (r != null && t == null) { turnMissing = true; sayRes(R.string.feedback_turn_missing) }
            // RP4: the panel's screenshot, if it kept one, in memory only from here; it goes only on a tap.
            withContext(Dispatchers.IO) { r?.let { FeedbackFiles.cardShot(it, turnHand) }?.let { (png, pkg) -> ReportShot.read(png) to appName(app, pkg) } }
                ?.let { (s, name) -> if (reportKey != null) shot.offer(s, name) }
            withContext(Dispatchers.IO) { FeedbackFiles.log(app, VoiceEvent.FEEDBACK_OPENED, args.entry.id, args.startKind.id) }
        } else if (turnJson == null) withContext(Dispatchers.IO) { FeedbackFiles.restoredTurn(app, refJson, turnHand) }?.let { k ->
            turnJson = k.turn?.toJson()
            // fix-bug-card-14-b L-c: recreated after the panel's process died (no History copy): the report says it isn't there.
            if (k.turn == null && !turnMissing) { turnMissing = true; sayRes(R.string.feedback_turn_missing) }
        }
    }
    // Once the app's snapshot has this phone's models, the model line says whether the one you chose is ready.
    // Your words and my answer, not hidden, are dropped once a report goes (turnJson and refJson null); the page keeps the turn in memory.
    // fix-bugcard-drop: but never past the 10 minutes, open or restored, sent or not: then the turn and the screenshot go.
    val held = remember { CardTurnHeld() }
    var forgot by remember { mutableIntStateOf(0) }
    val turn = remember(turnJson, forgot) { held.show(turnJson, System.currentTimeMillis()) }
    fun dropStale() {
        if (!held.stale(turnJson, System.currentTimeMillis())) return
        turnJson = null; refJson = null; held.forget(); forgot++; FeedbackFiles.dropHanded(turnHand); shot.close()
    }
    // Once the turn goes stale (10 minutes after it was kept); the timer stops while the phone sleeps, so starts and resumes check too.
    LaunchedEffect(turn) { turn?.let { t -> delay(ReportCard.staleIn(t, System.currentTimeMillis())); dropStale() } }
    // RP6: the chooser opening is not a send; back in Robot, it is one only once ReportPicked has listed the report.
    val wait = remember { SendWait() }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START || e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) dropStale()
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && wait.waiting) scope.launch {
                repeat(2) { i -> if (i > 0) delay(1_000); if (wait.check(withContext(Dispatchers.IO) { FeedbackFiles.sent(app) })) return@launch }
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(route, tick, snap.models.isNotEmpty(), turn != null) { ui = withContext(Dispatchers.IO) { load(app, args, snap, turn != null) } }
    FeedbackScreen(args, ui, FeedbackActions(
        sentList = openSent,
        send = { o, done ->
            val key = reportKey; val ref = o.ref
            if (key == null || ref == null) done(false) else scope.launch {
                val file = withContext(Dispatchers.IO) { ReportOut.plain(ref, o.text, o.png)?.let { ReportOut.seal(app, key, ref, it) } }
                val ok = file != null && FeedbackSend.start(ctx, ReportOut.sendIntent(ctx, file, ref, ctx.getString(R.string.report_send_with), o.kind, o.parts))
                when {
                    ok -> { ReportOut.schedule(app); wait.start(ref) { FeedbackFiles.log(app, VoiceEvent.FEEDBACK_SHARED, o.kind.id, hundreds(o.text), o.bits); turnJson = null; refJson = null; FeedbackFiles.dropHanded(turnHand); shot.close(); done(true) } }
                    file == null -> { sayRes(R.string.report_seal_failed); done(false) }
                    else -> { file.delete(); sayRes(R.string.report_no_send); done(false) }
                }
            }
        },
        share = { o ->
            FeedbackSend.start(ctx, FeedbackSend.shareIntent(o.text, ctx.getString(R.string.feedback_share_with))).also { ok ->
                if (ok) { FeedbackFiles.log(app, VoiceEvent.FEEDBACK_SHARED, o.kind.id, hundreds(o.text), o.bits); turnJson = null; refJson = null; FeedbackFiles.dropHanded(turnHand); shot.close() } else sayRes(R.string.feedback_no_share)
            }
        },
        copy = { o ->
            FeedbackClipboard.copy(ctx, o.text)
            FeedbackFiles.log(app, VoiceEvent.FEEDBACK_COPIED, o.kind.id, hundreds(o.text), o.bits)
            turnJson = null; refJson = null; FeedbackFiles.dropHanded(turnHand)
            shot.close()
            sayRes(R.string.feedback_copied)
        },
        textIt = { o ->
            val n = ui?.sendTo
            n != null && FeedbackSend.start(ctx, FeedbackSend.textIntent(n, o.text)).also { ok ->
                if (ok) { FeedbackFiles.log(app, VoiceEvent.FEEDBACK_TEXTED, o.kind.id, hundreds(o.text), o.bits); turnJson = null; refJson = null; FeedbackFiles.dropHanded(turnHand); shot.close() } else sayRes(R.string.feedback_no_sms)
            }
        },
        github = { k, link ->
            FeedbackSend.start(ctx, FeedbackSend.browserIntent(link.url)).also { ok ->
                if (ok) { FeedbackFiles.log(app, VoiceEvent.FEEDBACK_GITHUB, k.id, (link.chars + 99) / 100, if (link.cut) 1 else 0); turnJson = null; refJson = null; FeedbackFiles.dropHanded(turnHand); shot.close() } else sayRes(R.string.feedback_no_browser)
            }
        },
        githubCard = { o, url ->
            FeedbackClipboard.copy(ctx, o.text)
            FeedbackSend.start(ctx, FeedbackSend.browserIntent(url)).also { ok ->
                if (ok) { FeedbackFiles.log(app, VoiceEvent.FEEDBACK_GITHUB, o.kind.id, hundreds(o.text), 0); turnJson = null; refJson = null; FeedbackFiles.dropHanded(turnHand); shot.close() } else sayRes(R.string.feedback_no_browser)
            }
        },
        saveNumber = { n ->
            scope.launch {
                withContext(Dispatchers.IO) { FeedbackFiles.savePrefs(app, FeedbackPrefs(n)) }
                tick++
                if (n == null) sayRes(R.string.feedback_number_removed) else sayRes(R.string.feedback_number_saved, n)
            }
        },
        say = ::say,
    ), onBack, snackbar, prefill, turn = turn, turnMissing = turnMissing, shot = shot.takeIf { reportKey != null },
        sealed = reportKey?.let(ReportKey::fingerprint))
}

/** The app [pkg] by its name, or null (unknown, or hidden from Robot). */
private fun appName(ctx: android.content.Context, pkg: String?): String? = pkg?.let {
    runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } }.getOrNull()
}

/** Reads what the page shows (files and Android, off the main thread). */
private fun load(app: android.content.Context, args: FeedbackArgs, snap: Snapshot, people: Boolean = false): FeedbackUi {
    val inputs = FeedbackSources.read(Paths.dir(app))
    val s = inputs.settings
    val phone = ModelStore.phone(app)
    // 0.8: any build of Fast is this phone's (the general build once the beta TPU build failed here).
    val chosen = ModelCatalogue.resolve(s.brainModelChoice, phone, Problems.tpuFailure(app) != null)
    val own = if (chosen == BrainModel.OWN) ModelStore.own(app) else null
    val backend = if (chosen == BrainModel.OWN) own?.backend ?: chosen.backend
        else ModelCatalogue.backend(chosen, phone, s.modelGpu, inputs.gpuFailure?.model == chosen)
    // What Robot uses meanwhile when the model you chose isn't ready (the app's own snapshot knows; before it, not said).
    val using = if (snap.models.isNotEmpty() && !snap.ready(chosen)) snap.fast else null
    val facts = FeedbackFacts(
        robot = FeedbackFacts.robot(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG),
        phone = FeedbackFacts.phone(phone, Build.VERSION.RELEASE.orEmpty(), Build.ID.orEmpty(), Build.VERSION.INCREMENTAL.orEmpty()),
        model = FeedbackFacts.model(chosen, backend, inputs.gpuFailure, own?.format, using),
        look = FeedbackFacts.look(LookStore.current(app).wallpaperColors),
        language = FeedbackFacts.language(Locale.getDefault().toLanguageTag()),
        chip = FeedbackFacts.chip(phone),
    )
    // The problem asked about, else the open one, else one from the last day.
    val now = System.currentTimeMillis()
    val listed = Problems.list(app)
    // 1.0: from "Robot closed unexpectedly", the crash's own report instead (null when Android no longer keeps it).
    val crash = if (args.entry == FeedbackEntry.CRASH) args.problem?.let { Problems.crashReport(app, it) } else null
    val problem: ModelProblem? = if (args.entry == FeedbackEntry.CRASH) null
        else args.problem?.let { t -> listed.firstOrNull { it.time == t } ?: Problems.last(app)?.takeIf { it.time == t } }
            ?: Problems.open(app) ?: listed.firstOrNull { now - it.time in 0..86_400_000L }
    val blocks = listOfNotNull(
        crash?.let { FeedbackDiagnostics.problem(it, "Crash report:") } // l10n: not text (the report, a diagnostic: English)
            ?: problem?.let { FeedbackDiagnostics.problem(Problems.report(app, it)) },
        // The card's 10 minutes only with a turn; a crash or About report keeps the whole log.
        FeedbackDiagnostics.voiceLog(inputs.voiceLog, since = ReportCard.logSince(people, now)),
        FeedbackDiagnostics.settings(s, inputs.look, inputs.updates, inputs.memory, inputs.messages),
        if (args.entry == FeedbackEntry.UPDATER || FeedbackDiagnostics.updaterFailed(inputs.updateState)) FeedbackDiagnostics.updater(inputs.updateState) else null,
        // The everyday set (§5.8): What went online's last 7 days, offered as a box you tick, never ticked for you.
        NetLog.lastWeek(app).let { (rows, on) -> FeedbackDiagnostics.online(rows, on) },
    )
    val prefs = FeedbackFiles.prefs(app)
    // Read like the names, with the same check, and never sent.
    val book = if (people && ContactsOnPhone.granted(app)) ContactsOnPhone.numbersAndAddresses(app) else emptyList<String>() to emptyList()
    // report-hide-people: and Robot's People, through PeopleFiles; unread, the hide note says so.
    val folks = if (people) ReportPeople.read(app) else null
    return FeedbackUi(
        facts = facts,
        blocks = blocks,
        secrets = FeedbackPrivacy.secrets(s, inputs.updates, UpdateStore.defaultRepo(app)?.slug, listOfNotNull(prefs.sendTo)),
        sendTo = prefs.sendTo,
        github = UpdateStore.repo(app),
        problemDate = if (crash != null) args.problem?.let(::dateTime) else problem?.let { dateTime(it.time) },
        crash = crash != null,
        // Only with a turn to hide them in, read here on the phone and never sent.
        names = (if (people && ContactsOnPhone.granted(app)) ContactsOnPhone.names(app).flatMap { listOf(it.name) + it.nicknames } else emptyList<String>()) + folks?.names.orEmpty(),
        contacts = ContactsOnPhone.granted(app),
        numbers = book.first + folks?.numbers.orEmpty(),
        addresses = book.second + folks?.addresses.orEmpty(),
        peopleRead = peopleRead(people, folks),
        named = people,
    )
}

/** RP6: a Send waiting for the chooser's pick. It counts once Reports you sent holds its reference (Back: never). */
internal class SendWait {
    private var ref: String? = null
    private var then: (() -> Unit)? = null
    val waiting: Boolean get() = ref != null

    fun start(ref: String, then: () -> Unit) { this.ref = ref; this.then = then }

    /** True, and the send's end runs once, when [sent] lists the waiting report. */
    fun check(sent: List<app.heyrobot.core.feedback.SentReport>): Boolean {
        val r = ref ?: return false
        if (sent.none { it.ref == r }) return false
        val t = then
        ref = null; then = null
        t?.invoke()
        return true
    }
}
