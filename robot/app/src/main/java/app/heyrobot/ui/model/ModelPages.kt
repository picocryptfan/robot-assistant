// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.BrainModel
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.ProblemList
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelNotes
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.ModelTerms
import app.heyrobot.core.OwnModel
import app.heyrobot.core.PhoneStatus
import app.heyrobot.core.Phones
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.MeasuredSpeed
import app.heyrobot.core.SpeedKey
import app.heyrobot.core.SpeedLog
import app.heyrobot.core.Speeds
import app.heyrobot.core.TpuSupport
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.deviceFailureText
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.FooterPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusIcon
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TwoTargetPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Model (0.4.1; 0.4.x redesign; a tab until 1.4): which model Robot uses as radio rows,
// each with a button to its own page (download, import, check, delete, where it comes from), "Let the model think first",
// how long the model stays loaded, and the voice models, each with its page. Your own model file's page says what Robot's
// check found in it, and runs it on the TPU only after its warning dialog, bound to that file's SHA-256 (0.4.1). 0.4.5:
// your own file may be a GGUF file (llama.cpp, on the CPU). 0.4.7: the last model problem with its report to copy or share
// at the top, this phone and its chip (which Fast it gets, whether Robot uses its TPU), and where Fast runs on a phone
// without the Tensor G5's TPU build: "Use the GPU" with "Try the GPU again" after a failure, and this phone's estimated
// speeds; only this phone's Fast is listed. 0.7: the problem's "Send as bug report" (Settings › About › Send feedback), and
// the last 10 problems as a list, each with Show, Share, Send as bug report and Delete. 1.1: the pages' words are in
// strings_model.xml, core's (a model's name and state, what the check found, this phone's TPU and speeds) in ModelStrings,
// both with their Québec French; the problem report stays English (a diagnostic).

/** The route of a model's page (Settings › Model › one model), and of a voice model's. */
object ModelRoutes {
    fun model(m: BrainModel) = "model/${m.id}" // l10n: not text (a route)
    fun voice(b: VoiceBundle) = "model/voice/${b.name}" // l10n: not text (a route)
}

private fun mark(st: ModelStatus?): Mark = when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO }

@Composable
private fun free(snap: Snapshot): String = if (snap.freeBytes >= 0) ModelLogic.gbText(snap.freeBytes).str() else stringResource(R.string.model_unknown)

/** "4.1 GB (4,123,456,789 bytes)". */
@Composable
private fun bytes(n: Long): String = stringResource(R.string.model_bytes, ModelLogic.gbText(n).str(), Fmt.int(n, LocalLangs.current.ui))

/** Why a model can't be chosen yet, or null when it can. */
@Composable
internal fun notReady(m: BrainModel, snap: Snapshot): String? {
    if (snap.ready(m)) return null
    val st = snap.model(m)?.status
    return when {
        // 0.4.7: a TPU build runs only where Robot has the TPU; 0.8: the TPU of the chip its stamp names (Tensor G5 or G6).
        m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.tensorBuild != null && snap.own?.report?.runsOnTpuOf(snap.phone) != true ->
            stringResource(R.string.model_not_ready_other_tpu, snap.own?.tensorBuild?.title.orEmpty())
        m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.tensorBuild != null -> stringResource(R.string.model_not_ready_allow_tpu)
        m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.format == ModelFormat.GGUF && !snap.ggufEngine -> stringResource(R.string.model_not_ready_no_gguf)
        st == ModelStatus.DOWNLOADING || st == ModelStatus.IMPORTING || st == ModelStatus.VERIFYING -> stringResource(R.string.model_not_ready_getting)
        st == ModelStatus.BAD -> stringResource(R.string.model_not_ready_bad)
        m.builtIn -> stringResource(R.string.model_not_ready_download, ModelLogic.gbText(m.bytes).str())
        else -> stringResource(R.string.model_not_ready_import)
    }
}

/**
 * A model's row in "Robot uses": which model, then Ready or what it needs first, or (your own short-context file) chat
 * only. How fast each is, at length, is on its page.
 */
@Composable
internal fun modelRowSummary(m: BrainModel, snap: Snapshot): String {
    // Your own file, checked and short (0.4.3): said where you choose it, not only on its page.
    val short = if (m == BrainModel.OWN && snap.ready(m)) snap.own?.takeIf { it.shortContext }
        ?.let { o -> stringResource(if (o.vision) R.string.model_row_chat_only_photos else R.string.model_row_chat_only) } else null
    val name = if (m == BrainModel.OWN) snap.own?.name ?: stringResource(R.string.model_row_own_file) else m.modelName
    // 0.4.7: an E4B model on a phone with 8 GB or less (the warning in full is on its page).
    val memory = ModelCatalogue.memoryWarningText(m, snap.phone)?.let { stringResource(R.string.model_row_tight_memory) }
    return listOfNotNull(name, short, notReady(m, snap) ?: stringResource(R.string.model_row_ready).takeIf { short == null }, memory).joinToString(" · ")
}

/** Where [m] runs on this phone now: the GPU or the CPU for the general E2B (0.4.7), else its own backend. */
internal fun runsHere(m: BrainModel, s: RobotSettings, snap: Snapshot): ModelBackend =
    ModelCatalogue.backend(m, snap.phone, s.modelGpu, snap.gpuFailure?.model == m)

/**
 * 1.2 (design Step 5): what Robot measured for [m] on [b] on this phone (core SpeedLog's medians for its file and this Android
 * build), or null until it has enough answers.
 */
internal fun measuredHere(m: BrainModel, b: ModelBackend, snap: Snapshot): MeasuredSpeed? =
    SpeedLog.measured(snap.speeds, SpeedKey.of(m, b, if (m == BrainModel.OWN) snap.own?.sha256 else m.sha256, snap.build))

/** "Thu, 11 Oct 2026, 14:05": when a problem happened. */
private fun dateTime(t: Long): String = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t))

/** Settings › Model (the Model tab until 1.4, when Files took its place: files-1.4-plan.md Q1). */
@Composable
fun ModelScreen(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, busy: String?, a: AppActions, modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null, snackbar: SnackbarHostState? = null) {
    val phone = snap.phone
    val langs = LocalLangs.current
    val res = LocalContext.current.resources
    // 0.4.7: either Fast in the file is this phone's Fast.
    val chosen = snap.resolve(s.brainModelChoice)
    val groupProblem = stringResource(R.string.model_group_problem)
    val groupProblems = stringResource(R.string.model_group_problems)
    val groupUses = stringResource(R.string.model_group_uses)
    val groupPhone = stringResource(R.string.model_group_phone)
    val groupThinking = stringResource(R.string.model_group_thinking)
    val groupMemory = stringResource(R.string.model_group_memory)
    val groupVoice = stringResource(R.string.model_group_voice)
    val footer = modelFooter(s, snap, chosen)
    PreferenceScreen(UiStrings.model().str(), onBack = onBack, modifier = modifier, snackbar = snackbar) {
        snap.problem?.let { p -> preferenceGroup(groupProblem, "problem") { ProblemRows(p, a) } }
        // 0.7: the last 10 problems, under the open one, or at the end of the tab once it is dismissed.
        val earlier = snap.problems.filter { it.time != snap.problem?.time }
        if (snap.problem != null && earlier.isNotEmpty()) preferenceGroup(groupProblems, "problems") { ProblemListRows(earlier, a) }
        preferenceGroup(groupUses, "uses") {
            for (m in ModelCatalogue.models(phone, snap.tpuFailure != null)) {
                // 0.8, a Tensor G6: the general build as Fast (beta)'s fallback, to download before the TPU build ever fails. Not a
                // choice of its own (Robot switches to it by itself), so a row that opens its page, with Download there.
                if (m == ModelCatalogue.fallback(phone, snap.tpuFailure != null)) {
                    PreferenceItem(ModelCatalogue.fallbackTitleText.str(), stringResource(R.string.model_fallback_row, modelRowSummary(m, snap)),
                        leading = { StatusIcon(mark(snap.model(m)?.status)) }, onClick = { a.openPage(Page.Settings(ModelRoutes.model(m))) },
                        trailing = { Icon(RIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                    continue
                }
                val ready = snap.ready(m)
                val title = m.titleText.str()
                TwoTargetPreference(title, modelRowSummary(m, snap),
                    // Tapping the chosen one again saves too: Robot then retries a model that didn't load.
                    onClick = { update(s.copy(brainModel = m.id)) },
                    enabled = ready || chosen == m, role = Role.RadioButton, selected = chosen == m,
                    leading = { RadioButton(selected = chosen == m, onClick = null, enabled = ready || chosen == m) }) {
                    IconButton(onClick = { a.openPage(Page.Settings(ModelRoutes.model(m))) }) {
                        Icon(RIcons.Chevron, stringResource(R.string.model_details_cd, title))
                    }
                }
            }
            if (!snap.ready(chosen) && chosen != snap.fast) {
                StatusPreference(stringResource(R.string.model_until_ready, chosen.titleText.str()), notReady(chosen, snap), Mark.WARN)
            }
            snap.loaded?.let { PreferenceItem(stringResource(R.string.model_in_memory), "${it.titleText.str()} (${it.modelNameText.str()})", RIcons.Chip) }
        }
        preferenceGroup(groupPhone, "phone") {
            StatusPreference(Phones.phoneText(phone).str(), Phones.tpuLineText(phone).str(), if (phone.tpu != TpuSupport.NONE) Mark.OK else Mark.TODO)
            // 1.2: Android's own SoC fields, on every phone, so a test phone shows what it says.
            PreferenceItem(stringResource(R.string.model_chip_fields), Phones.socFieldsText(phone).str(), RIcons.Chip)
            // 0.8: the beta TPU build didn't load here: the general build until you try the TPU again.
            snap.tpuFailure?.let { f ->
                StatusPreference(f.titleText.str(), stringResource(R.string.model_tpu_failed_when, dateTime(f.time)), Mark.WARN)
                PreferenceItem(stringResource(R.string.model_try_tpu_again), stringResource(R.string.model_try_tpu_again_summary),
                    RIcons.Refresh, onClick = a.tryTpuAgain)
            }
            // 1.2 (design Step 8): the setup guide's warning on a slow or small phone (core PhoneFit); "Use the GPU" is just below.
            snap.fit(s.modelGpu).takeIf { it.shows }?.let { f -> StatusPreference(f.titleText.render(langs), f.lines.joinToString(" ") { it.render(langs) }, Mark.WARN) }
            if (snap.fast.gpu) WhereFastRuns(s, update, snap, a)
        }
        preferenceGroup(groupThinking, "thinking") {
            SwitchPreference(stringResource(R.string.model_think_first), stringResource(R.string.model_think_first_summary), s.modelThinks) {
                update(s.copy(modelThinks = it))
            }
        }
        preferenceGroup(groupMemory, "memory") {
            val keepLabel = { m: Int -> if (m == RobotSettings.KEEP_ALWAYS) res.getString(R.string.model_keep_always) else res.getString(R.string.model_keep_minutes, m) }
            val byDefault = stringResource(R.string.model_keep_default)
            ListPreference(stringResource(R.string.model_keep_loaded), s.keepModelMinutes, RobotSettings.KEEP_CHOICES, keepLabel,
                { if (it == 5) byDefault else null }, RIcons.Timer,
                summary = stringResource(R.string.model_keep_summary, keepLabel(s.keepModelMinutes))) { update(s.copy(keepModelMinutes = it)) }
        }
        if (snap.problem == null && earlier.isNotEmpty()) preferenceGroup(groupProblems, "problems") { ProblemListRows(earlier, a) }
        preferenceGroup(groupVoice, "voice") {
            for (b in VoiceBundle.entries) {
                val v = snap.voice(b) ?: continue
                PreferenceItem(b.titleText.render(langs), listOf(VoiceModelLogic.headlineText(v.status), VoiceModelLogic.sizeText(b.bytes)).joinToString(" · ") { it.render(langs) },
                    leading = { StatusIcon(mark(v.status)) }, onClick = { a.openPage(Page.Settings(ModelRoutes.voice(b))) },
                    trailing = { Icon(RIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
        preferenceFooter(footer)
    }
}

/** The Model tab's footer, its paragraphs joined. */
@Composable
private fun modelFooter(s: RobotSettings, snap: Snapshot, chosen: BrainModel): String {
    // A Pixel 10's choice (or the default) on another phone: read as this phone's Fast, the file left as it is.
    val fastNote = if (s.brainModelChoice.isFast && s.brainModelChoice != snap.fast) when (snap.fast) {
        BrainModel.GENERAL -> stringResource(if (snap.tpuFailure != null) R.string.model_footer_general_failed else R.string.model_footer_general)
        BrainModel.FAST_G6 -> stringResource(R.string.model_footer_g6)
        else -> null
    } else null
    val memory = if (chosen.memoryBytes > 0) stringResource(R.string.model_footer_memory, app.heyrobot.core.PhoneStatus.sizeText(chosen.memoryBytes).str())
        else stringResource(R.string.model_footer_memory_file)
    val gpu = if (snap.fast.gpu && ModelCatalogue.gpuOffered(snap.phone)) stringResource(R.string.model_footer_gpu) else null
    val keep = stringResource(if (runsHere(chosen, s, snap) == ModelBackend.TPU) R.string.model_footer_keep_tpu else R.string.model_footer_keep_cpu, memory)
    val tpu = stringResource(R.string.model_footer_tpu) + "\n" + Phones.tpuListText.map { it.str() }.joinToString("\n") { "• $it" } + "\n" +
        stringResource(R.string.model_footer_speeds)
    return listOfNotNull(listOfNotNull(stringResource(R.string.model_footer_intro), fastNote).joinToString(" "), tpu, gpu,
        stringResource(R.string.model_footer_think), keep, stringResource(R.string.model_footer_downloads)).joinToString("\n\n")
}

/**
 * 0.4.7: where the general E2B (Fast on a phone without the Tensor G5's TPU build) runs: "Use the GPU" (on by default
 * where Robot offers the GPU), the GPU's failure with "Try the GPU again", and this phone's estimates on the GPU and the CPU.
 */
@Composable
private fun WhereFastRuns(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, a: AppActions) {
    val p = snap.phone
    val g = BrainModel.GENERAL
    val offered = ModelCatalogue.gpuOffered(p)
    val failure = snap.gpuFailure?.takeIf { it.model == g }
    val now = runsHere(g, s, snap)
    if (offered) {
        SwitchPreference(stringResource(R.string.model_use_gpu), stringResource(R.string.model_use_gpu_summary), s.modelGpu) { update(s.copy(modelGpu = it)) }
        if (failure != null) {
            StatusPreference(failure.titleText.str(), stringResource(R.string.model_gpu_failed_when, dateTime(failure.time)), Mark.WARN)
            PreferenceItem(stringResource(R.string.model_try_gpu_again), stringResource(R.string.model_try_gpu_again_summary),
                RIcons.Refresh, onClick = a.tryGpuAgain)
        }
    } else {
        PreferenceItem(stringResource(R.string.model_where_fast_runs), stringResource(R.string.model_where_fast_runs_cpu, p.chip.gpu.title))
    }
    for (b in if (offered) listOf(ModelBackend.GPU, ModelBackend.CPU) else listOf(ModelBackend.CPU)) {
        val e = Speeds.of(g, b, p, measuredHere(g, b, snap)) ?: continue
        PreferenceBlock {
            Text(stringResource(if (b == now) R.string.model_fast_on_now else R.string.model_fast_on, b.titleOn(p.chip)), style = MaterialTheme.typography.titleSmall)
            Text(e.textPhrase().str(), style = MaterialTheme.typography.bodyMedium)
            Text(e.sourceText.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 0.4.7: the last model problem (core ModelProblem): what happened and when, and its report, which you can read here, then
 * copy or share yourself. Robot never sends it.
 */
@Composable
private fun ProblemRows(p: ModelProblem, a: AppActions) {
    var text by remember(p.time) { mutableStateOf<String?>(null) }
    var shown by rememberSaveable(p.time) { mutableStateOf(false) }
    LaunchedEffect(p.time) {
        text = withContext(Dispatchers.IO) { try { a.reportText(p) } catch (_: Exception) { null } }
    }
    StatusPreference(p.headlineText().str(), dateTime(p.time), Mark.WARN)
    PreferenceBlock {
        Text(stringResource(R.string.model_report_intro), style = MaterialTheme.typography.bodyMedium)
        Buttons {
            Button(onClick = { text?.let(a.copyReport) }, enabled = text != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.model_copy_report)) }
            OutlinedButton(onClick = { text?.let(a.shareReport) }, enabled = text != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.model_share_report))
            }
            // 0.7: Send feedback as a bug report, this report ticked (you read and send it there).
            OutlinedButton(onClick = { a.openPage(bugReport(p)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.model_send_bug)) }
            TextButton(onClick = { shown = !shown }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (shown) R.string.model_hide_report else R.string.model_show_report))
            }
            TextButton(onClick = { a.dismissProblem(p) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.model_dismiss)) }
        }
        if (shown) SelectionContainer {
            Text(text ?: stringResource(R.string.model_making_report), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Settings › About › Send feedback as a bug report about [p], its report ticked. */
private fun bugReport(p: ModelProblem): Page = Page.Settings(SettingsRoutes.feedback(FeedbackEntry.MODEL_PROBLEM, FeedbackKind.BUG, p.time))

/**
 * 0.7: the last model problems (core ProblemList: codes and numbers only), each a row with its date, the process and the
 * stage; a tap opens its report's Show, Share, Send as bug report and Delete.
 */
@Composable
private fun ProblemListRows(list: List<ModelProblem>, a: AppActions) {
    val expanded = stringResource(R.string.model_expanded)
    val collapsed = stringResource(R.string.model_collapsed)
    for (q in list) {
        var open by rememberSaveable(q.time) { mutableStateOf(false) }
        PreferenceItem(dateTime(q.time), ProblemList.rowText(q).str(), leading = { StatusIcon(Mark.WARN) },
            modifier = Modifier.semantics { stateDescription = if (open) expanded else collapsed }, onClick = { open = !open })
        if (open) ProblemActions(q, a)
    }
}

/** One listed problem's report: Show, Share, Send as bug report, Delete. */
@Composable
private fun ProblemActions(q: ModelProblem, a: AppActions) {
    var text by remember(q.time) { mutableStateOf<String?>(null) }
    var shown by rememberSaveable(q.time) { mutableStateOf(false) }
    LaunchedEffect(q.time) {
        text = withContext(Dispatchers.IO) { try { a.reportText(q) } catch (_: Exception) { null } }
    }
    PreferenceBlock {
        Text(q.headlineText().str(), style = MaterialTheme.typography.bodyMedium)
        Buttons {
            TextButton(onClick = { shown = !shown }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (shown) R.string.model_hide else R.string.model_show))
            }
            OutlinedButton(onClick = { text?.let(a.shareReport) }, enabled = text != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.model_share))
            }
            OutlinedButton(onClick = { a.openPage(bugReport(q)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.model_send_bug)) }
            TextButton(onClick = { a.deleteProblem(q) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.model_delete)) }
        }
        if (shown) SelectionContainer {
            Text(text ?: stringResource(R.string.model_making_report), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Settings › Model's pages by route ("model/<id>", "model/voice/<bundle>"); null when [route] isn't one. */
@Composable
fun ModelRoute(route: String, s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, busy: String?, a: AppActions,
    snackbar: SnackbarHostState, onBack: () -> Unit): Boolean {
    val bundle = route.removePrefix("model/voice/").takeIf { route.startsWith("model/voice/") }?.let { n -> VoiceBundle.entries.firstOrNull { it.name == n } }
    val model = route.removePrefix("model/").takeIf { route.startsWith("model/") }?.let { id -> BrainModel.entries.firstOrNull { it.id == id } }
    when {
        bundle != null -> VoiceModelPage(bundle, snap, a, snackbar, onBack)
        model == BrainModel.OWN -> OwnModelPage(s, snap, busy, a, snackbar, onBack)
        model != null -> ModelPage(model, s, update, snap, busy, a, snackbar, onBack)
        else -> return false
    }
    return true
}

/** A row of the page's buttons, wrapping when the text is large. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ProgressBlock(progress: String?, percent: Int, failure: String?) {
    if (progress == null && failure == null) return
    PreferenceBlock {
        progress?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * One built-in model: status, what it is, Download / Import / Check / Delete, its size and cache, and where it comes from,
 * with its SHA-256 to select. A model with its publisher's terms asks them before it downloads or is imported.
 */
@Composable
private fun ModelPage(m: BrainModel, s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, busy: String?, a: AppActions,
    snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ms = snap.model(m)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    /** A publisher's terms to tick before its file downloads (false) or is imported (true). */
    var askTerms by remember { mutableStateOf<Pair<ModelTerms, Boolean>?>(null) }
    fun download() { val t = ModelTerms.missing(s, m); if (t != null) askTerms = t to false else a.download(m) }
    fun import() { val t = ModelTerms.missing(s, m); if (t != null) askTerms = t to true else a.import(m) }
    val memoryTitle = stringResource(R.string.model_memory_title)
    val groupDetails = stringResource(R.string.model_group_details)
    PreferenceScreen(ModelCatalogue.titleText(m, snap.phone, snap.tpuFailure != null).str(), onBack, snackbar = snackbar) {
        if (ms == null) return@PreferenceScreen
        val st = ms.status
        preferenceGroup(null, "state") {
            StatusPreference(m.modelName, ModelLogic.headlineText(st, m).str(), mark(st))
            PreferenceBlock {
                ModelNotes.ofText(m.modelName)?.let { Text(it.str(), style = MaterialTheme.typography.bodyMedium) }
                Text(m.speedText.str(), style = MaterialTheme.typography.bodyMedium)
                Text(m.aboutText.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 0.4.7: this phone's figures for it, measured or estimated, with where they come from (Fast on a Tensor G5
                // says its measured figures itself).
                // 0.8: the Tensor G6 build (beta) says "Estimate, not measured" with where its figure comes from.
                // 1.2: what Robot measured on this phone comes first, Fast's on a Tensor G5 too.
                val here = measuredHere(m, runsHere(m, s, snap), snap)
                if (m != BrainModel.FAST || here != null) Speeds.of(m, runsHere(m, s, snap), snap.phone, here)?.let { e ->
                    Text(stringResource(if (e.here) R.string.model_on_the_measured else R.string.model_on_this_phone, runsHere(m, s, snap).titleOn(snap.phone.chip),
                        e.textPhrase().str()), style = MaterialTheme.typography.bodyMedium)
                    Text(e.sourceText.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st == ModelStatus.BAD) Text(stringResource(R.string.model_bad_checksum),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Buttons {
                    when (st) {
                        ModelStatus.MISSING, ModelStatus.BAD -> {
                            Button(onClick = ::download, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.model_download_size, ModelLogic.gbText(m.bytes).str()))
                            }
                            OutlinedButton(onClick = ::import, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.model_import_file))
                            }
                        }
                        ModelStatus.DOWNLOADING -> OutlinedButton(onClick = { a.cancelDownload(m) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.model_cancel_download))
                        }
                        ModelStatus.UNVERIFIED -> Button(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.model_check_file))
                        }
                        ModelStatus.VERIFIED -> {
                            OutlinedButton(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.model_check_again))
                            }
                            OutlinedButton(onClick = { confirmDelete = true }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.model_delete))
                            }
                        }
                        ModelStatus.IMPORTING, ModelStatus.VERIFYING -> {}
                    }
                    if (st == ModelStatus.BAD || st == ModelStatus.UNVERIFIED) {
                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.model_delete_file)) }
                    }
                }
            }
        }
        // 0.4.7: an E4B model on a phone with 8 GB or less.
        ModelCatalogue.memoryWarningText(m, snap.phone)?.let { w ->
            preferenceGroup(null, "memory") { StatusPreference(memoryTitle, w.str(), Mark.WARN) }
        }
        item(key = "progress") { ProgressBlock(ms.progress, ms.percent, ms.failure.takeIf { st == ModelStatus.MISSING }) }
        preferenceGroup(groupDetails, "details") {
            val size = ModelLogic.gbText(m.bytes).str()
            PreferenceItem(stringResource(R.string.model_download_size_title), when {
                // 0.4.7: the general E2B's first load writes a cache where it runs.
                m.gpu -> stringResource(R.string.model_size_gpu_cache, size, ModelLogic.gbText(ModelFiles.GPU_CACHE_BYTES).str(), ModelLogic.gbText(m.cacheBytes).str())
                m.cacheBytes > 0 -> stringResource(R.string.model_size_cpu_cache, size, ModelLogic.gbText(m.cacheBytes).str())
                else -> size
            })
            if (ms.cacheBytes > 0) PreferenceItem(stringResource(R.string.model_cache_now), ModelLogic.gbText(ms.cacheBytes).str())
            PreferenceItem(stringResource(R.string.model_free_space), free(snap))
            PreferenceItem(stringResource(R.string.model_licence), "${m.publisherText.str()} · ${m.licenceText.str()}")
            PreferenceItem(stringResource(R.string.model_source), stringResource(R.string.model_source_value, m.repo.toString(), m.commit?.take(12).toString(), m.file))
            val terms = s.confirmedTerms[ModelTerms.of(m)?.id]
            if (terms != null) {
                PreferenceItem(stringResource(R.string.model_publisher_terms),
                    stringResource(R.string.model_terms_ticked, java.text.DateFormat.getDateInstance().format(java.util.Date(terms))))
            } else if (ModelTerms.of(m) != null) {
                PreferenceItem(stringResource(R.string.model_publisher_terms), stringResource(R.string.model_terms_ask))
            }
        }
        item(key = "sha") { ShaFooter(m.sha256.orEmpty()) }
    }
    askTerms?.let { (t, importing) ->
        TermsDialog(t, importing, onDismiss = { askTerms = null }) {
            askTerms = null
            // Kept first (settings, schema 12), so the download's own check finds them confirmed.
            update(ModelTerms.confirm(s, t, System.currentTimeMillis()))
            if (importing) a.import(t.model) else a.download(t.model)
        }
    }
    if (confirmDelete) DeleteModelDialog(m, s, snap, a) { confirmDelete = false }
}

/** "Delete “Fast”?": the file and cache it frees, and what Robot answers with after. */
@Composable
private fun DeleteModelDialog(m: BrainModel, s: RobotSettings, snap: Snapshot, a: AppActions, close: () -> Unit) {
    val ms = snap.model(m)
    val others = ModelCatalogue.models(snap.phone).any { it != m && snap.ready(it) }
    val file = ModelLogic.gbText(ms?.fileBytes ?: 0).str()
    val removes = if ((ms?.cacheBytes ?: 0) > 0) stringResource(R.string.model_delete_file_cache, file, ModelLogic.gbText(ms!!.cacheBytes).str())
        else stringResource(R.string.model_delete_file_only, file)
    ConfirmDialog(stringResource(R.string.model_delete_title, m.titleText.str()),
        listOfNotNull(removes, stringResource(R.string.model_delete_back_to_fast).takeIf { m == snap.resolve(s.brainModelChoice) && m != snap.fast },
            stringResource(R.string.model_delete_none_left).takeIf { !others }).joinToString(" "),
        stringResource(R.string.model_delete), onDismiss = close) { close(); a.deleteModel(m) }
}

/** The file's SHA-256, selectable, in monospace, as the page's footer. */
@Composable
private fun ShaFooter(sha: String) {
    if (sha.isEmpty()) return
    SelectionContainer { FooterPreference("SHA-256\n$sha") } // l10n: not text (a hash)
}

/**
 * Your own model file: imported through Android's file picker (no storage permission), copied into Robot's storage, its
 * size and SHA-256 shown, and what Robot's check found in it. A .litertlm runs with LiteRT-LM: on the TPU only for a
 * proven TPU build for this phone's own chip, and only once you allowed it in its warning dialog, else on the CPU; a .gguf (0.4.5) with
 * llama.cpp, on the CPU. Which one it is, by its contents: never by the file's name.
 */
@Composable
private fun OwnModelPage(s: RobotSettings, snap: Snapshot, busy: String?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val m = BrainModel.OWN
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var askTpu by rememberSaveable { mutableStateOf(false) }
    var warnImport by rememberSaveable { mutableStateOf(false) }
    val ms = snap.model(m)
    val groupInside = stringResource(R.string.model_group_inside)
    val footer = listOfNotNull(stringResource(R.string.model_own_footer), stringResource(R.string.model_own_footer_no_gguf).takeIf { !snap.ggufEngine },
        stringResource(R.string.model_free_on_phone, free(snap))).joinToString(" ")
    PreferenceScreen(ModelCatalogue.titleText(m, snap.phone, snap.tpuFailure != null).str(), onBack, snackbar = snackbar) {
        if (ms == null) return@PreferenceScreen
        val st = ms.status
        val own = snap.own.takeIf { st == ModelStatus.VERIFIED || st == ModelStatus.BAD }
        preferenceGroup(null, "state") {
            StatusPreference(own?.name ?: stringResource(R.string.model_row_own_file), ModelLogic.headlineText(st, m).str(), mark(st))
            PreferenceBlock {
                // A file whose name (or a GGUF file's general.name) says MedGemma: one factual line here, never added to its answers (0.4.3).
                (ModelNotes.ofText(own?.name) ?: ModelNotes.ofText(own?.gguf?.name))?.let { Text(it.str(), style = MaterialTheme.typography.bodyMedium) }
                Text(m.speedText.str(), style = MaterialTheme.typography.bodyMedium)
                Text(m.aboutText.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Buttons {
                    OutlinedButton(onClick = { warnImport = true }, enabled = busy == null && st != ModelStatus.IMPORTING && st != ModelStatus.VERIFYING,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(RIcons.Folder, null); Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (ms.fileBytes > 0) R.string.model_import_another else R.string.model_import_model_file))
                    }
                    if (st == ModelStatus.VERIFIED) OutlinedButton(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.model_check_again))
                    }
                    if (st == ModelStatus.UNVERIFIED) Button(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.model_check_file))
                    }
                    if (ms.fileBytes > 0 && st != ModelStatus.IMPORTING && st != ModelStatus.VERIFYING) {
                        OutlinedButton(onClick = { confirmDelete = true }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.model_delete))
                        }
                    }
                }
            }
        }
        item(key = "progress") { ProgressBlock(ms.progress, ms.percent, null) }
        if (own != null) {
            preferenceGroup(groupInside, "inside") {
                PreferenceItem(stringResource(R.string.model_size), bytes(own.bytes))
                PreferenceBlock { for (line in own.linesText()) Text("• ${line.str()}", style = MaterialTheme.typography.bodyMedium) }
                PreferenceBlock {
                    Text(runsOn(own, snap, ms.cacheBytes), style = MaterialTheme.typography.bodyMedium)
                    // 1.2: Robot has no figure for your own file until it measures it here.
                    own.backend?.let { b -> measuredHere(m, b, snap)?.let { Speeds.of(m, b, snap.phone, it) } }?.let { e ->
                        Text(e.textPhrase().str(), style = MaterialTheme.typography.bodyMedium)
                        Text(e.sourceText.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Built for 2,048 to 4,095 tokens (0.4.3), a .litertlm or a .gguf alike: chat only.
                if (own.shortContext && own.runsOn != null) {
                    StatusPreference(stringResource(R.string.model_chat_only),
                        stringResource(if (own.vision) R.string.model_chat_only_photos else R.string.model_chat_only_no_photos, own.contextTokens), Mark.TODO)
                }
                // 0.4.7: only where Robot has the TPU; 0.8: the TPU of the chip its stamp names (a Tensor G5, or in beta a G6).
                if (own.tensorBuild != null && !own.tpuAllowed && st == ModelStatus.VERIFIED && own.report.runsOnTpuOf(snap.phone)) {
                    PreferenceItem(stringResource(R.string.model_run_on_tpu), stringResource(R.string.model_run_on_tpu_summary, own.tensorBuild?.title.orEmpty()),
                        RIcons.Warning, onClick = { askTpu = true })
                }
                if (own.tpuAllowed) PreferenceItem(stringResource(R.string.model_stop_tpu), stringResource(R.string.model_stop_tpu_summary),
                    onClick = { a.allowOwnTpu(false, null) })
            }
        }
        preferenceFooter(footer)
        if (own != null) item(key = "sha") { ShaFooter(own.sha256) }
    }
    if (confirmDelete) DeleteModelDialog(m, s, snap, a) { confirmDelete = false }
    // 1.0 audit (native-01, ipc-5): a .litertlm runs inside Robot's model process, with Robot's permissions.
    if (warnImport) ConfirmDialog(stringResource(R.string.model_import_warn_title), stringResource(R.string.model_import_warning),
        stringResource(R.string.model_choose_file), onDismiss = { warnImport = false }) {
        warnImport = false
        a.import(m)
    }
    val own = snap.own
    if (askTpu && own != null) TpuDialog(own, onDismiss = { askTpu = false }) { askTpu = false; a.allowOwnTpu(true, own.sha256) }
}

/** Where your own file runs, and how fast. */
@Composable
private fun runsOn(own: OwnModel, snap: Snapshot, cacheBytes: Long): String = when {
    own.backend == ModelBackend.TPU -> stringResource(R.string.model_runs_tpu)
    own.format == ModelFormat.GGUF && own.backend != null && !snap.ggufEngine -> stringResource(R.string.model_runs_no_gguf)
    own.format == ModelFormat.GGUF && own.backend == ModelBackend.CPU -> stringResource(R.string.model_runs_gguf_cpu)
    own.backend == ModelBackend.CPU -> if (cacheBytes > 0) stringResource(R.string.model_runs_cpu_cache, ModelLogic.gbText(cacheBytes).str())
        else stringResource(R.string.model_runs_cpu_first)
    own.tensorBuild != null && !own.report.runsOnTpuOf(snap.phone) -> stringResource(R.string.model_runs_other_chip, own.tensorBuild?.title.orEmpty(),
        own.tensorBuild?.let { Phones.seriesOf(it) }.toString())
    own.tensorBuild != null -> stringResource(R.string.model_runs_waits_tpu)
    else -> stringResource(R.string.model_runs_cant)
}

/**
 * Before your own TPU build (for this phone's Tensor G5, or in beta G6) may run on the TPU: the risks the 0.4.1 research names, and the file. Bound to the
 * file this dialog shows ([OwnModel.sha256]): a different file in the meantime is never allowed by this tap.
 */
@Composable
private fun TpuDialog(own: OwnModel, onDismiss: () -> Unit, onAllow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(RIcons.Warning, null) },
        title = { Text(stringResource(R.string.model_tpu_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.model_tpu_dialog_intro, own.tensorBuild?.title ?: "Tensor"), style = MaterialTheme.typography.bodyMedium) // l10n: not text (a chip's name)
                for (risk in TPU_RISKS) Text("• " + stringResource(risk), style = MaterialTheme.typography.bodyMedium)
                Text("${own.name} · ${bytes(own.bytes)}\nSHA-256 ${own.sha256}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.model_tpu_cant_cpu), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.model_run_on_tpu)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** The risks of a TPU build Robot didn't pin (research 4, "Security and legal"). */
private val TPU_RISKS = listOf(R.string.model_tpu_risk_code, R.string.model_tpu_risk_crash, R.string.model_tpu_risk_sha, R.string.model_tpu_risk_terms,
    R.string.model_tpu_risk_cards)

/**
 * A model publisher's conditions as tick boxes, in its own words (core ModelTerms), before its file downloads or is
 * imported: the button works only with every box ticked.
 */
@Composable
private fun TermsDialog(t: ModelTerms, importing: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var ticked by remember(t) { mutableStateOf(emptySet<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (importing) R.string.model_terms_title_import else R.string.model_terms_title_download, t.model.titleText.str())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ModelTerms.introText.str(), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.model_terms_quote, t.prompt, t.publisher), style = MaterialTheme.typography.bodySmall)
                t.ticks.forEachIndexed { i, words ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = i in ticked, role = Role.Checkbox) { on -> ticked = if (on) ticked + i else ticked - i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = i in ticked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(words, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(ModelTerms.alsoText.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = t.complete(ticked)) {
                Text(stringResource(if (importing) R.string.model_terms_confirm_import else R.string.model_terms_confirm_download))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_cancel)) } },
    )
}

/** Model › one voice model (speech recognition, the voice, the fast fallback voice, the speaker model): its state, download and delete. */
@Composable
private fun VoiceModelPage(b: VoiceBundle, snap: Snapshot, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val v = snap.voice(b)
    val title = b.titleText.str()
    val size = VoiceModelLogic.sizeText(b.bytes).str()
    val footer = stringResource(R.string.model_voice_footer) + " " + stringResource(R.string.model_free_on_phone, free(snap))
    PreferenceScreen(title, onBack, snackbar = snackbar) {
        if (v == null) return@PreferenceScreen
        preferenceGroup(null, "state") {
            StatusPreference("$title · $size", VoiceModelLogic.headlineText(v.status).str(), mark(v.status))
            PreferenceBlock {
                Text(b.aboutText.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Buttons {
                    when (v.status) {
                        ModelStatus.MISSING, ModelStatus.BAD -> Button(onClick = { a.voiceDownload(b) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.model_download_size, size))
                        }
                        ModelStatus.DOWNLOADING -> OutlinedButton(onClick = { a.voiceCancel(b) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.model_cancel_download))
                        }
                        ModelStatus.VERIFIED -> OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.model_delete))
                        }
                        else -> {}
                    }
                }
            }
        }
        item(key = "progress") { ProgressBlock(v.progress, v.percent, v.failure) }
        preferenceFooter(footer)
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.model_delete_title, title),
            stringResource(when (b) {
                VoiceBundle.SPEAKERS -> R.string.model_voice_delete_speakers
                VoiceBundle.STT -> R.string.model_voice_delete_stt
                else -> R.string.model_voice_delete_tts
            }, size),
            stringResource(R.string.model_delete), onDismiss = { confirmDelete = false }) { confirmDelete = false; a.voiceDelete(b) }
    }
}
