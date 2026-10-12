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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.heyrobot.core.BrainModel
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelNotes
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.ModelTerms
import app.heyrobot.core.OwnModel
import app.heyrobot.core.PhoneStatus
import app.heyrobot.core.Phones
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Speeds
import app.heyrobot.core.TpuSupport
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceModelLogic
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
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// MAIN PROCESS. The Model tab (0.4.1; 0.4.x redesign, docs/plans ui-stock-design 3.6): which model Robot uses as radio rows,
// each with a button to its own page (download, import, check, delete, where it comes from), "Let the model think first",
// how long the model stays loaded, and the voice models, each with its page. Your own model file's page says what Robot's
// check found in it, and runs it on the TPU only after its warning dialog, bound to that file's SHA-256 (0.4.1). 0.4.5:
// your own file may be a GGUF file (llama.cpp, on the CPU). 0.4.7: the last model problem with its report to copy or share
// at the top, this phone and its chip (which Fast it gets, whether Robot uses its TPU), and where Fast runs on a phone
// without the Tensor G5's TPU build: "Use the GPU" with "Try the GPU again" after a failure, and this phone's estimated
// speeds; only this phone's Fast is listed.

/** The route of a model's page (Settings › Model › one model), and of a voice model's. */
object ModelRoutes {
    fun model(m: BrainModel) = "model/${m.id}"
    fun voice(b: VoiceBundle) = "model/voice/${b.name}"
}

private fun mark(st: ModelStatus?): Mark = when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO }

private fun free(snap: Snapshot): String = if (snap.freeBytes >= 0) ModelLogic.gb(snap.freeBytes) else "unknown"

/** "4.1 GB (4,123,456,789 bytes)". */
private fun bytes(n: Long): String = "${ModelLogic.gb(n)} (${String.format(Locale.US, "%,d", n)} bytes)"

/** Why a model can't be chosen yet, or null when it can. */
internal fun notReady(m: BrainModel, snap: Snapshot): String? {
    if (snap.ready(m)) return null
    val st = snap.model(m)?.status
    return when {
        // 0.4.7: a TPU build runs only where Robot has the TPU (a Tensor G5).
        m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.tensorG5Build == true && snap.phone.tpu != TpuSupport.SUPPORTED ->
            "Built for the Tensor G5's TPU, which this phone doesn't have"
        m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.tensorG5Build == true -> "Allow the TPU for it first"
        m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.format == ModelFormat.GGUF && !snap.ggufEngine -> "This build of Robot can't run GGUF files"
        st == ModelStatus.DOWNLOADING || st == ModelStatus.IMPORTING || st == ModelStatus.VERIFYING -> "Getting it ready…"
        st == ModelStatus.BAD -> "Its file can't be used"
        m.builtIn -> "Not downloaded · ${ModelLogic.gb(m.bytes)}"
        else -> "Import a file first"
    }
}

/**
 * A model's row in "Robot uses": which model, then Ready or what it needs first, or (your own short-context file) chat
 * only. How fast each is, at length, is on its page.
 */
internal fun modelRowSummary(m: BrainModel, snap: Snapshot): String {
    // Your own file, checked and short (0.4.3): said where you choose it, not only on its page.
    val short = if (m == BrainModel.OWN && snap.ready(m)) snap.own?.takeIf { it.shortContext }
        ?.let { o -> "Chat only (short context): no actions" + if (o.vision) ", can see photos" else "" } else null
    val name = if (m == BrainModel.OWN) snap.own?.name ?: "A .litertlm or .gguf file" else m.modelName
    // 0.4.7: an E4B model on a phone with 8 GB or less (the warning in full is on its page).
    val memory = ModelCatalogue.memoryWarning(m, snap.phone)?.let { "Tight on this phone's memory" }
    return listOfNotNull(name, short, notReady(m, snap) ?: "Ready".takeIf { short == null }, memory).joinToString(" · ")
}

/** Where [m] runs on this phone now: the GPU or the CPU for the general E2B (0.4.7), else its own backend. */
internal fun runsHere(m: BrainModel, s: RobotSettings, snap: Snapshot): ModelBackend =
    ModelCatalogue.backend(m, snap.phone, s.modelGpu, snap.gpuFailure?.model == m)

/** "Thu, 11 Oct 2026, 14:05": when a problem happened. */
private fun dateTime(t: Long): String = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t))

/** "Always", "5 min". */
private fun keepLabel(m: Int): String = if (m == RobotSettings.KEEP_ALWAYS) "Always" else "$m min"

/** The Model tab. */
@Composable
fun ModelScreen(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, busy: String?, a: AppActions, modifier: Modifier = Modifier) {
    val phone = snap.phone
    // 0.4.7: either Fast in the file is this phone's Fast.
    val chosen = ModelCatalogue.resolve(s.brainModelChoice, phone)
    PreferenceScreen("Model", onBack = null, modifier = modifier) {
        snap.problem?.let { p -> preferenceGroup("Model problem", "problem") { ProblemRows(p, a) } }
        preferenceGroup("Robot uses", "uses") {
            for (m in ModelCatalogue.models(phone)) {
                val ready = snap.ready(m)
                TwoTargetPreference(m.title, modelRowSummary(m, snap),
                    // Tapping the chosen one again saves too: Robot then retries a model that didn't load.
                    onClick = { update(s.copy(brainModel = m.id)) },
                    enabled = ready || chosen == m, role = Role.RadioButton, selected = chosen == m,
                    leading = { RadioButton(selected = chosen == m, onClick = null, enabled = ready || chosen == m) }) {
                    IconButton(onClick = { a.openPage(Page.Settings(ModelRoutes.model(m))) }) { Icon(RIcons.Chevron, "${m.title} details") }
                }
            }
            if (!snap.ready(chosen) && chosen != snap.fast) {
                StatusPreference("Until “${chosen.title}” is ready, Robot uses Fast", notReady(chosen, snap), Mark.WARN)
            }
            snap.loaded?.let { PreferenceItem("In memory now", "${it.title} (${it.modelName})", RIcons.Chip) }
        }
        preferenceGroup("This phone", "phone") {
            StatusPreference(Phones.line(phone).removePrefix("This phone: "), Phones.tpuLine(phone),
                if (phone.tpu == TpuSupport.SUPPORTED) Mark.OK else Mark.TODO)
            if (snap.fast.gpu) WhereFastRuns(s, update, snap, a)
        }
        preferenceGroup("Thinking", "thinking") {
            SwitchPreference("Let the model think first", "Qwen3, MiniCPM 5 and some GGUF files", s.modelThinks) { update(s.copy(modelThinks = it)) }
        }
        preferenceGroup("Memory", "memory") {
            ListPreference("Keep the model loaded", s.keepModelMinutes, RobotSettings.KEEP_CHOICES, ::keepLabel,
                { if (it == 5) "The default" else null }, RIcons.Timer,
                summary = keepLabel(s.keepModelMinutes) + " after the panel closes") { update(s.copy(keepModelMinutes = it)) }
        }
        preferenceGroup("Voice models", "voice") {
            for (b in VoiceBundle.entries) {
                val v = snap.voice(b) ?: continue
                PreferenceItem(b.title, "${VoiceModelLogic.headline(v.status)} · ${VoiceModelLogic.size(b.bytes)}",
                    leading = { StatusIcon(mark(v.status)) }, onClick = { a.openPage(Page.Settings(ModelRoutes.voice(b))) },
                    trailing = { Icon(RIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
        val memory = if (chosen.memoryBytes > 0) "about ${PhoneStatus.size(chosen.memoryBytes)} of memory" else "about its file's size in memory"
        preferenceFooter(
            "Models run on this phone. Robot goes online only to download one you pick. Whichever model answers, Robot's cards and " +
                "switches (Settings › Actions, Safety & privacy) decide what asks you first, and nothing leaves the phone unless you ask. " +
                "Questions in a new conversation take longer with the slower models." +
                // A Pixel 10's choice (or the default) on another phone: read as this phone's Fast, the file left as it is.
                (if (s.brainModelChoice == BrainModel.FAST && snap.fast != BrainModel.FAST)
                    " Fast on this phone is Gemma 4 E2B's general build: the Tensor G5 build needs a Pixel 10." else "") +
                "\n\nTPU support in Robot:\n" + Phones.TPU_LIST.joinToString("\n") { "• $it" } +
                "\nSpeeds on the models' pages are measured on a Pixel 10 Pro where they say so, otherwise estimates, each with where it comes from." +
                (if (snap.fast.gpu && ModelCatalogue.gpuOffered(phone)) "\n\nUse the GPU: on the GPU, Fast starts answering sooner, uses less " +
                    "battery and leaves the CPU to Robot's voice; the CPU may write a little faster. If the GPU fails, Robot switches to the " +
                    "CPU by itself and says so." else "") +
                "\n\nLet the model think first: some models can reason before they answer: Qwen3, MiniCPM 5, and GGUF files whose chat " +
                "template allows it. On, they may: answers take longer, and the thinking is never shown or read out. Off, Robot asks " +
                "them to answer directly. Robot's own Gemma models answer the same either way.\n\n" +
                "Keep the model loaded after the panel closes ($memory; " +
                (if (runsHere(chosen, s, snap) == ModelBackend.TPU) "it loads again in about 2 seconds)." else "it loads again in a few seconds once its cache is written).") +
                "\n\nAbout the downloads: each model downloads from Hugging Face only when you tap Download, pinned to one commit of its " +
                "repository; Robot checks the file's SHA-256 before it is ever loaded, and the download resumes by itself if the " +
                "connection drops. You can also download a file on a computer and import it. Nothing you say or type goes to Hugging " +
                "Face. The voice models are for talking to Robot and hearing it answer. They run on the phone; nothing you say leaves " +
                "it. Each file is checked (SHA-256) before Robot uses it. Wi-Fi recommended.",
        )
    }
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
        SwitchPreference("Use the GPU", "Recommended. If it fails, Robot switches to the CPU by itself", s.modelGpu) { update(s.copy(modelGpu = it)) }
        if (failure != null) {
            StatusPreference("The GPU ${failure.what}", "${dateTime(failure.time)}. Robot runs Fast on the CPU.", Mark.WARN)
            PreferenceItem("Try the GPU again", "At the next question. If it fails again, Robot goes back to the CPU by itself",
                RIcons.Refresh, onClick = a.tryGpuAgain)
        }
    } else {
        PreferenceItem("Where Fast runs", "On the CPU: Robot doesn't use this phone's GPU (${p.chip.gpu.title}, as on the Pixel 10)")
    }
    for (b in if (offered) listOf(ModelBackend.GPU, ModelBackend.CPU) else listOf(ModelBackend.CPU)) {
        val e = Speeds.of(g, b, p) ?: continue
        PreferenceBlock {
            Text("Fast on the ${b.title}" + if (b == now) " (now)" else "", style = MaterialTheme.typography.titleSmall)
            Text(e.text(), style = MaterialTheme.typography.bodyMedium)
            Text(e.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    StatusPreference(p.headline(), dateTime(p.time), Mark.WARN)
    PreferenceBlock {
        Text("Robot made a report you can read, then copy or share if you want to help fix it: codes, this phone and its Android " +
            "build, the model file and the last 50 voice log events. Never what you said or asked. Robot sends nothing itself.",
            style = MaterialTheme.typography.bodyMedium)
        Buttons {
            Button(onClick = { text?.let(a.copyReport) }, enabled = text != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy report") }
            OutlinedButton(onClick = { text?.let(a.shareReport) }, enabled = text != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share report") }
            TextButton(onClick = { shown = !shown }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (shown) "Hide the report" else "Show the report") }
            TextButton(onClick = { a.dismissProblem(p) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
        }
        if (shown) SelectionContainer {
            Text(text ?: "Making the report…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
    PreferenceScreen(m.title, onBack, snackbar = snackbar) {
        if (ms == null) return@PreferenceScreen
        val st = ms.status
        preferenceGroup(null, "state") {
            StatusPreference(m.modelName, ModelLogic.headline(st, m), mark(st))
            PreferenceBlock {
                ModelNotes.of(m.modelName)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(m.speed, style = MaterialTheme.typography.bodyMedium)
                Text(m.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 0.4.7: this phone's figures for it, measured or estimated, with where they come from (Fast on a Tensor G5
                // says its measured figures itself).
                if (m != BrainModel.FAST) Speeds.of(m, runsHere(m, s, snap), snap.phone)?.let { e ->
                    Text("On this phone, on the ${runsHere(m, s, snap).title}. ${e.text()}", style = MaterialTheme.typography.bodyMedium)
                    Text(e.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st == ModelStatus.BAD) Text("The file's checksum is wrong, so Robot won't load it. Delete it and download it again.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Buttons {
                    when (st) {
                        ModelStatus.MISSING, ModelStatus.BAD -> {
                            Button(onClick = ::download, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download ${ModelLogic.gb(m.bytes)}")
                            }
                            OutlinedButton(onClick = ::import, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import a file") }
                        }
                        ModelStatus.DOWNLOADING -> OutlinedButton(onClick = { a.cancelDownload(m) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel download") }
                        ModelStatus.UNVERIFIED -> Button(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check the file") }
                        ModelStatus.VERIFIED -> {
                            OutlinedButton(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
                            OutlinedButton(onClick = { confirmDelete = true }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                        }
                        ModelStatus.IMPORTING, ModelStatus.VERIFYING -> {}
                    }
                    if (st == ModelStatus.BAD || st == ModelStatus.UNVERIFIED) {
                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete the file") }
                    }
                }
            }
        }
        // 0.4.7: an E4B model on a phone with 8 GB or less.
        ModelCatalogue.memoryWarning(m, snap.phone)?.let { w -> preferenceGroup(null, "memory") { StatusPreference("This phone's memory", w, Mark.WARN) } }
        item(key = "progress") { ProgressBlock(ms.progress, ms.percent, ms.failure.takeIf { st == ModelStatus.MISSING }) }
        preferenceGroup("Details", "details") {
            PreferenceItem("Download size", ModelLogic.gb(m.bytes) + when {
                // 0.4.7: the general E2B's first load writes a cache where it runs.
                m.gpu -> ", plus a cache its first load writes: about ${ModelLogic.gb(ModelFiles.GPU_CACHE_BYTES)} on the GPU, " +
                    "up to ${ModelLogic.gb(m.cacheBytes)} on the CPU"
                m.cacheBytes > 0 -> ", plus about ${ModelLogic.gb(m.cacheBytes)} of cache the CPU writes on its first load"
                else -> ""
            })
            if (ms.cacheBytes > 0) PreferenceItem("Cache now", ModelLogic.gb(ms.cacheBytes))
            PreferenceItem("Free space", free(snap))
            PreferenceItem("Licence", "${m.publisher} · ${m.licence}")
            PreferenceItem("Source", "${m.repo} at commit ${m.commit?.take(12)}\n${m.file}")
            val terms = s.confirmedTerms[ModelTerms.of(m)?.id]
            if (terms != null) {
                PreferenceItem("Publisher's conditions", "You ticked them on ${java.text.DateFormat.getDateInstance().format(java.util.Date(terms))}: " +
                    "research and personal experimentation only, and you're responsible for its outputs.")
            } else if (ModelTerms.of(m) != null) {
                PreferenceItem("Publisher's conditions", "Its publisher asks you to confirm two conditions before you download it: research and " +
                    "personal experimentation only, and you're responsible for its outputs.")
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
    ConfirmDialog("Delete “${m.title}”?",
        "Removes its file (${ModelLogic.gb(ms?.fileBytes ?: 0)})" + (if ((ms?.cacheBytes ?: 0) > 0) " and its cache (${ModelLogic.gb(ms!!.cacheBytes)})" else "") +
            " from this phone." + (if (m == ModelCatalogue.resolve(s.brainModelChoice, snap.phone) && m != snap.fast) " Robot goes back to Fast." else "") +
            (if (!others) " Robot can't answer until you download or import a model again." else ""),
        "Delete", onDismiss = close) { close(); a.deleteModel(m) }
}

/** The file's SHA-256, selectable, in monospace, as the page's footer. */
@Composable
private fun ShaFooter(sha: String) {
    if (sha.isEmpty()) return
    SelectionContainer { FooterPreference("SHA-256\n$sha") }
}

/**
 * Your own model file: imported through Android's file picker (no storage permission), copied into Robot's storage, its
 * size and SHA-256 shown, and what Robot's check found in it. A .litertlm runs with LiteRT-LM: on the TPU only for a
 * proven Tensor G5 build, and only once you allowed it in its warning dialog, else on the CPU; a .gguf (0.4.5) with
 * llama.cpp, on the CPU. Which one it is, by its contents: never by the file's name.
 */
@Composable
private fun OwnModelPage(s: RobotSettings, snap: Snapshot, busy: String?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val m = BrainModel.OWN
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var askTpu by rememberSaveable { mutableStateOf(false) }
    val ms = snap.model(m)
    PreferenceScreen(m.title, onBack, snackbar = snackbar) {
        if (ms == null) return@PreferenceScreen
        val st = ms.status
        val own = snap.own.takeIf { st == ModelStatus.VERIFIED || st == ModelStatus.BAD }
        preferenceGroup(null, "state") {
            StatusPreference(own?.name ?: "A .litertlm or .gguf file", ModelLogic.headline(st, m), mark(st))
            PreferenceBlock {
                // A file whose name (or a GGUF file's general.name) says MedGemma: one factual line here, never added to its answers (0.4.3).
                (ModelNotes.of(own?.name) ?: ModelNotes.of(own?.gguf?.name))?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(m.speed, style = MaterialTheme.typography.bodyMedium)
                Text(m.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Buttons {
                    OutlinedButton(onClick = { a.import(m) }, enabled = busy == null && st != ModelStatus.IMPORTING && st != ModelStatus.VERIFYING,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(RIcons.Folder, null); Spacer(Modifier.width(8.dp)); Text(if (ms.fileBytes > 0) "Import another file" else "Import a model file")
                    }
                    if (st == ModelStatus.VERIFIED) OutlinedButton(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
                    if (st == ModelStatus.UNVERIFIED) Button(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check the file") }
                    if (ms.fileBytes > 0 && st != ModelStatus.IMPORTING && st != ModelStatus.VERIFYING) {
                        OutlinedButton(onClick = { confirmDelete = true }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                    }
                }
            }
        }
        item(key = "progress") { ProgressBlock(ms.progress, ms.percent, null) }
        if (own != null) {
            preferenceGroup("What Robot found inside", "inside") {
                PreferenceItem("Size", bytes(own.bytes))
                PreferenceBlock { for (line in own.lines()) Text("• $line", style = MaterialTheme.typography.bodyMedium) }
                PreferenceBlock { Text(runsOn(own, snap, ms.cacheBytes), style = MaterialTheme.typography.bodyMedium) }
                // Built for 2,048 to 4,095 tokens (0.4.3), a .litertlm or a .gguf alike: chat only.
                if (own.shortContext && own.runsOn != null) {
                    StatusPreference("Chat only", "It was built for ${own.contextTokens} tokens of context, so Robot gives it a short prompt and no actions " +
                        "(timers, texts, notes, the calendar…) and keeps less of the conversation. Ask for an action and Robot says so and " +
                        "offers this page, where you can choose Fast. " + (if (own.vision) "It can see photos." else "It can't see photos."), Mark.TODO)
                }
                // 0.4.7: only where Robot has the TPU (a Tensor G5).
                if (own.tensorG5Build && !own.tpuAllowed && st == ModelStatus.VERIFIED && snap.phone.tpu == TpuSupport.SUPPORTED) {
                    PreferenceItem("Run on the TPU", "A Tensor G5 build: read the risks first", RIcons.Warning, onClick = { askTpu = true })
                }
                if (own.tpuAllowed) PreferenceItem("Stop running it on the TPU", "Robot won't use the TPU for it", onClick = { a.allowOwnTpu(false, null) })
            }
        }
        preferenceFooter(
            "A .litertlm (LiteRT-LM) or .gguf (llama.cpp) file. Robot copies it; it needs the file's size again in free space, plus room " +
                "for a cache when LiteRT-LM runs it on the CPU (a .gguf needs none)." +
                (if (snap.ggufEngine) "" else " This build of Robot can't run .gguf files (its llama.cpp library wasn't built).") +
                " Free on this phone: ${free(snap)}.",
        )
        if (own != null) item(key = "sha") { ShaFooter(own.sha256) }
    }
    if (confirmDelete) DeleteModelDialog(m, s, snap, a) { confirmDelete = false }
    val own = snap.own
    if (askTpu && own != null) TpuDialog(own, onDismiss = { askTpu = false }) { askTpu = false; a.allowOwnTpu(true, own.sha256) }
}

/** Where your own file runs, and how fast. */
private fun runsOn(own: OwnModel, snap: Snapshot, cacheBytes: Long): String = when {
    own.backend == ModelBackend.TPU -> "Runs on the TPU: you allowed it."
    own.format == ModelFormat.GGUF && own.backend != null && !snap.ggufEngine ->
        "This build of Robot can't run GGUF files: its llama.cpp library wasn't built. A build made with tools/build_llama.sh can."
    own.format == ModelFormat.GGUF && own.backend == ModelBackend.CPU -> "Runs on the CPU with llama.cpp, memory-mapped (no cache). " +
        "Expect it to be slower than Fast: about the speed of the E4B models for a 4B file, faster for a smaller one."
    own.backend == ModelBackend.CPU -> "Runs on the CPU: about as slow as the E4B models or slower, depending on its size. " +
        if (cacheBytes > 0) "Its cache holds ${ModelLogic.gb(cacheBytes)} now." else "Its first load writes a cache next to it."
    own.tensorG5Build && snap.phone.tpu != TpuSupport.SUPPORTED -> "It was compiled for the Tensor G5's TPU (Pixel 10), which this " +
        "phone doesn't have, and a TPU build can't run on the CPU: Robot can't use it here."
    own.tensorG5Build -> "Waits for you to allow the TPU (Run on the TPU, above)."
    else -> "Robot can't run it on this phone. Delete it to free the space."
}

/**
 * Before your own Tensor G5 build may run on the TPU: the risks the 0.4.1 research names, and the file. Bound to the
 * file this dialog shows ([OwnModel.sha256]): a different file in the meantime is never allowed by this tap.
 */
@Composable
private fun TpuDialog(own: OwnModel, onDismiss: () -> Unit, onAllow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(RIcons.Warning, null) },
        title = { Text("Run this file on the TPU?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Robot's check says this file was compiled for the Tensor G5's TPU, which this phone has. It isn't Robot's pinned Fast model, so " +
                    "Robot can't vouch for it:", style = MaterialTheme.typography.bodyMedium)
                for (risk in TPU_RISKS) Text("• $risk", style = MaterialTheme.typography.bodyMedium)
                Text("${own.name} · ${bytes(own.bytes)}\nSHA-256 ${own.sha256}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("A TPU build can't run on the CPU: without the TPU, Robot doesn't use it.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Run on the TPU") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The risks of a TPU build Robot didn't pin (research 4, "Security and legal"). */
private val TPU_RISKS = listOf(
    "Most of it is compiled TPU code that nobody can review. It runs through the phone's own TPU driver and Google's vendor libraries, " +
        "outside what Robot's separate model process can contain.",
    "A bad or mismatched build can crash Robot's model, hang the TPU until the phone restarts, or, rarely, restart the phone.",
    "Its SHA-256 only proves the file is the one you picked, not that it is safe.",
    "Google's Tensor compiler is a closed beta whose terms forbid sharing what it builds without Google's permission: a TPU build " +
        "not published by Google may have been shared against them.",
    "Robot's cards and switches still apply, as with every model: what asks you first doesn't change.",
)

/**
 * A model publisher's conditions as tick boxes, in its own words (core ModelTerms), before its file downloads or is
 * imported: the button works only with every box ticked.
 */
@Composable
private fun TermsDialog(t: ModelTerms, importing: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var ticked by remember(t) { mutableStateOf(emptySet<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you ${if (importing) "import" else "download"} “${t.model.title}”") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ModelTerms.INTRO, style = MaterialTheme.typography.bodyMedium)
                Text("“${t.prompt}” (${t.publisher}, on the model's page)", style = MaterialTheme.typography.bodySmall)
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
                Text(ModelTerms.ALSO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = t.complete(ticked)) { Text(if (importing) "Confirm and import" else "Confirm and download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Model › one voice model (speech recognition, the voice, the fast fallback voice, the speaker model): its state, download and delete. */
@Composable
private fun VoiceModelPage(b: VoiceBundle, snap: Snapshot, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val v = snap.voice(b)
    PreferenceScreen(b.title, onBack, snackbar = snackbar) {
        if (v == null) return@PreferenceScreen
        preferenceGroup(null, "state") {
            StatusPreference("${b.title} · ${VoiceModelLogic.size(b.bytes)}", VoiceModelLogic.headline(v.status), mark(v.status))
            PreferenceBlock {
                Text(b.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Buttons {
                    when (v.status) {
                        ModelStatus.MISSING, ModelStatus.BAD -> Button(onClick = { a.voiceDownload(b) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download ${VoiceModelLogic.size(b.bytes)}")
                        }
                        ModelStatus.DOWNLOADING -> OutlinedButton(onClick = { a.voiceCancel(b) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel download") }
                        ModelStatus.VERIFIED -> OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                        else -> {}
                    }
                }
            }
        }
        item(key = "progress") { ProgressBlock(v.progress, v.percent, v.failure) }
        preferenceFooter("For talking to Robot and hearing it answer. It runs on the phone; nothing you say leaves it. The file is checked " +
            "(SHA-256) before Robot uses it. Wi-Fi recommended. Free on this phone: ${free(snap)}.")
    }
    if (confirmDelete) {
        ConfirmDialog("Delete “${b.title}”?",
            if (b == VoiceBundle.SPEAKERS) "Transcripts stop telling speakers apart (every line is “Speaker”) until you download it again " +
                "(${VoiceModelLogic.size(b.bytes)}). A voiceprint of yours, if you made one, stays until you delete it in Settings › Conversation."
            else "Robot falls back to the stand-in (${if (b == VoiceBundle.STT) "typing only" else "the phone's voice"}) until you download it again (${VoiceModelLogic.size(b.bytes)}).",
            "Delete", onDismiss = { confirmDelete = false }) { confirmDelete = false; a.voiceDelete(b) }
    }
}
