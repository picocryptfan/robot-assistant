package app.heyrobot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.settings.UpdatesEntry
import app.heyrobot.BuildConfig
import app.heyrobot.core.Approval
import app.heyrobot.core.BenchState
import app.heyrobot.core.BrainModel
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.ClaudeCost
import app.heyrobot.core.ClaudeEffortSetting
import app.heyrobot.core.ClaudeModel
import app.heyrobot.core.ClaudeRoute
import app.heyrobot.core.ClaudeSettings
import app.heyrobot.core.ClaudeVoices
import app.heyrobot.core.Escalation
import app.heyrobot.core.EmailApp
import app.heyrobot.core.LockScreen
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.NearbyServer
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.TextApp
import app.heyrobot.core.EngineState
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.Phones
import app.heyrobot.core.Speeds
import app.heyrobot.core.TpuSupport
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelNotes
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.ModelTerms
import app.heyrobot.core.OwnModel
import app.heyrobot.core.PhoneStatus
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.ToolName
import app.heyrobot.core.VoiceBenchLogic
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.RecognizerKeeper
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.toolIcon

// MAIN PROCESS. The model manager, settings and the assistant setup check.

/**
 * Settings › Model (the Model tab, 0.4.1): which model Robot uses, one card per built-in model (download, check,
 * delete, where it comes from), your own model file (import, what the check found, the TPU's warning card), and the
 * voice models. 0.4.5: your own file may be a GGUF file (llama.cpp, on the CPU), and "Let the model think first". 0.4.7:
 * this phone and its chip, whether Robot uses its TPU (and which Pixels it does on), Fast's GPU switch with this phone's
 * estimated speeds, and the last model problem with its report to copy or share. Only this phone's Fast is listed.
 */
@Composable
fun ModelScreen(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, busy: String?, a: AppActions, modifier: Modifier) {
    var confirmDelete by remember { mutableStateOf<BrainModel?>(null) }
    /** A publisher's terms to tick before its file downloads (false) or is imported (true). */
    var askTerms by remember { mutableStateOf<Pair<ModelTerms, Boolean>?>(null) }
    val chosen = ModelCatalogue.resolve(s.brainModelChoice, snap.phone)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Model", "Robot's AI runs on this phone. It goes online only to download a model you pick.")
        snap.problem?.let { ProblemCard(it, a) }
        PhoneCard(snap)
        ModelChoiceCard(s, update, snap)
        if (snap.fast.gpu) WhereFastRunsCard(s, update, snap, a)
        ThinkingCard(s, update)
        for (m in ModelCatalogue.models(snap.phone).filter { it.builtIn }) {
            val terms = ModelTerms.of(m)
            ModelCard(m, snap, busy, a, onDelete = { confirmDelete = m }, termsConfirmed = terms?.let { s.confirmedTerms[it.id] },
                onDownload = { val t = ModelTerms.missing(s, m); if (t != null) askTerms = t to false else a.download(m) },
                onImport = { val t = ModelTerms.missing(s, m); if (t != null) askTerms = t to true else a.import(m) })
        }
        OwnModelCard(snap, busy, a, onDelete = { confirmDelete = BrainModel.OWN })
        SectionCard("About the downloads") {
            Text("Each model downloads from Hugging Face only when you tap Download, pinned to one commit of its repository; " +
                "Robot checks the file's SHA-256 before it is ever loaded, and the download resumes by itself if the connection drops. " +
                "You can also download a file on a computer and import it. Nothing you say or type goes to Hugging Face.",
                style = MaterialTheme.typography.bodyMedium)
        }
        VoiceModelsCard(snap, a)
        Spacer(Modifier.height(12.dp))
    }
    askTerms?.let { (t, importing) ->
        TermsDialog(t, importing, onDismiss = { askTerms = null }) {
            askTerms = null
            // Kept first (settings, schema 12), so the download's own check finds them confirmed.
            update(ModelTerms.confirm(s, t, System.currentTimeMillis()))
            if (importing) a.import(t.model) else a.download(t.model)
        }
    }
    confirmDelete?.let { m ->
        val ms = snap.model(m)
        val others = ModelCatalogue.models(snap.phone).any { it != m && snap.ready(it) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${m.title}”?") },
            text = {
                Text("Removes its file (${ModelLogic.gb(ms?.fileBytes ?: 0)})" + (if ((ms?.cacheBytes ?: 0) > 0) " and its cache (${ModelLogic.gb(ms!!.cacheBytes)})" else "") +
                    " from this phone." + (if (m == chosen && m != snap.fast) " Robot goes back to Fast." else "") +
                    (if (!others) " Robot can't answer until you download or import a model again." else ""))
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; a.deleteModel(m) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/**
 * 0.4.7: this phone and its chip, whether its TPU runs Robot's model (and so which Fast it gets), and which Pixels Robot's
 * TPU build runs on (core Phones).
 */
@Composable
private fun PhoneCard(snap: Snapshot) {
    val p = snap.phone
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard("This phone") {
        StatusRow(if (p.tpu == TpuSupport.SUPPORTED) Mark.OK else Mark.TODO, Phones.line(p).removePrefix("This phone: "), Phones.tpuLine(p))
        Text("TPU support in Robot", style = MaterialTheme.typography.titleSmall)
        for (line in Phones.TPU_LIST) Text("• $line", style = MaterialTheme.typography.bodySmall, color = muted)
        Text("Speeds below are measured on a Pixel 10 Pro where it says so, otherwise estimates, each with where it comes from.",
            style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/**
 * 0.4.7: where the general E2B runs: Settings › Model › "Use the GPU" (on by default where Robot offers the GPU), this
 * phone's estimates on the GPU and the CPU, and the GPU's failure, if any, with "Try the GPU again".
 */
@Composable
private fun WhereFastRunsCard(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, a: AppActions) {
    val p = snap.phone
    val g = BrainModel.GENERAL
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val offered = ModelCatalogue.gpuOffered(p)
    val failure = snap.gpuFailure?.takeIf { it.model == g }
    val now = ModelCatalogue.backend(g, p, s.modelGpu, failure != null)
    SectionCard("Where Fast runs") {
        if (offered) {
            SwitchRow("Use the GPU", "Recommended. On the GPU, Fast starts answering sooner, uses less battery and leaves the CPU to " +
                "Robot's voice; the CPU may write a little faster. If the GPU fails, Robot switches to the CPU by itself and says so.",
                s.modelGpu) { update(s.copy(modelGpu = it)) }
            if (failure != null) {
                Text("The GPU ${failure.what} on ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(failure.time))}, so " +
                    "Robot runs Fast on the CPU.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = a.tryGpuAgain, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try the GPU again") }
            }
        } else {
            Text("Robot doesn't use this phone's GPU (${p.chip.gpu.title}, as on the Pixel 10): Fast runs on the CPU.",
                style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        for (b in if (offered) listOf(ModelBackend.GPU, ModelBackend.CPU) else listOf(ModelBackend.CPU)) {
            val e = Speeds.of(g, b, p) ?: continue
            Text("On the ${b.title}" + if (b == now) " (now)" else "", style = MaterialTheme.typography.titleSmall)
            Text(e.text(), style = MaterialTheme.typography.bodyMedium)
            Text(e.source, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

/**
 * 0.4.7: the last model problem (core ModelProblem): what happened, and its report, which you can read here, then copy or
 * share yourself. Robot never sends it.
 */
@Composable
private fun ProblemCard(p: ModelProblem, a: AppActions) {
    var text by remember(p.time) { mutableStateOf<String?>(null) }
    var shown by remember(p.time) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(p.time) {
        text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { a.reportText(p) } catch (_: Exception) { null } }
    }
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(RIcons.Warning, null)
                Spacer(Modifier.width(10.dp))
                Text("Model problem", style = MaterialTheme.typography.titleSmall)
            }
            Text(p.headline(), style = MaterialTheme.typography.bodyMedium)
            Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.time))}. Robot made a report you can read, then copy " +
                "or share if you want to help fix it: codes, this phone and its Android build, the model file and the last 50 voice log " +
                "events. Never what you said or asked. Robot sends nothing itself.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { text?.let(a.copyReport) }, enabled = text != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy report") }
                OutlinedButton(onClick = { text?.let(a.shareReport) }, enabled = text != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share report") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { shown = !shown }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (shown) "Hide the report" else "Show the report") }
                TextButton(onClick = { a.dismissProblem(p) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
            }
            if (shown) Text(text ?: "Making the report…", style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

/** "Robot uses": a model can be chosen once it is on the phone and checked; until then Robot uses Fast. */
@Composable
private fun ModelChoiceCard(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot) {
    val chosen = ModelCatalogue.resolve(s.brainModelChoice, snap.phone)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard("Robot uses") {
        for (m in ModelCatalogue.models(snap.phone)) {
            val ready = snap.ready(m)
            val st = snap.model(m)?.status
            val note = when {
                ready -> null
                m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.tensorG5Build == true && snap.phone.tpu != TpuSupport.SUPPORTED ->
                    "It was compiled for the Tensor G5's TPU, which this phone doesn't have."
                m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.tensorG5Build == true -> "Allow the TPU for it below first."
                m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.format == ModelFormat.GGUF && !snap.ggufEngine ->
                    "This build of Robot can't run GGUF files."
                st == ModelStatus.DOWNLOADING || st == ModelStatus.IMPORTING || st == ModelStatus.VERIFYING -> "Getting it ready below…"
                st == ModelStatus.BAD -> "Its file can't be used: see below."
                m.builtIn -> "Download it below first."
                else -> "Import a file below first."
            }
            // Your own file, checked and short (0.4.3): said where you choose it, not only on its card below.
            val short = if (m == BrainModel.OWN && ready) snap.own?.takeIf { it.shortContext }
                ?.let { o -> " Chat only (short context): no actions." + if (o.vision) " Can see photos." else "" } else null
            // 0.4.7: this phone's figures for it (measured or estimated, Speeds), and a memory warning on 8 GB or less.
            // (Fast on a Tensor G5 says its measured figures itself.)
            val here = if (m == BrainModel.FAST) "" else Speeds.of(m, ModelCatalogue.backend(m, snap.phone, s.modelGpu, snap.gpuFailure?.model == m), snap.phone)
                ?.let { " " + it.text() } ?: ""
            val warning = ModelCatalogue.memoryWarning(m, snap.phone)?.let { " $it" } ?: ""
            ChoiceRow(m.title, m.speed + here + warning + (short ?: "") + (note?.let { " $it" } ?: ""), chosen == m, enabled = ready || chosen == m) {
                // Tapping the chosen one again saves too: Robot then retries a model that didn't load.
                update(s.copy(brainModel = m.id))
            }
        }
        if (!snap.ready(chosen) && chosen != snap.fast) {
            Text("Until “${chosen.title}” is ready, Robot uses Fast.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Text("Whichever model answers, Robot's cards and switches (Settings › Actions, Safety & privacy) decide what asks you first, " +
            "and nothing leaves the phone unless you ask. Questions in a new conversation take longer with the slower models.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        snap.loaded?.let { Text("In memory now: ${it.title} (${it.modelName}).", style = MaterialTheme.typography.bodySmall, color = muted) }
        // A Pixel 10's choice (or the default) on another phone: read as this phone's Fast, the file left as it is.
        if (s.brainModelChoice == BrainModel.FAST && snap.fast != BrainModel.FAST) {
            Text("Fast on this phone is Gemma 4 E2B's general build: the Tensor G5 build needs a Pixel 10.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

/**
 * Settings › Model › "Let the model think first" (0.4.5, off): a model that can reason before it answers may; off, Robot
 * asks it to answer directly. The thinking is never shown or spoken either way (core Thinking).
 */
@Composable
private fun ThinkingCard(s: RobotSettings, update: (RobotSettings) -> Unit) {
    SectionCard("Thinking") {
        SwitchRow("Let the model think first",
            "Some models can reason before they answer: Qwen3, MiniCPM 5, and GGUF files whose chat template allows it. On, " +
                "they may: answers take longer, and the thinking is never shown or read out. Off, Robot asks them to answer " +
                "directly. Robot's own Gemma models answer the same either way.",
            s.modelThinks) { update(s.copy(modelThinks = it)) }
    }
}

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

/**
 * One built-in model: status, what it is, size and cache, Download / Check / Delete, and where it comes from. A model with
 * its publisher's terms ([termsConfirmed]: when you ticked them, or null) asks them before [onDownload] or [onImport].
 */
@Composable
private fun ModelCard(m: BrainModel, snap: Snapshot, busy: String?, a: AppActions, onDelete: () -> Unit,
    termsConfirmed: Long? = null, onDownload: () -> Unit = { a.download(m) }, onImport: () -> Unit = { a.import(m) }) {
    val ms = snap.model(m) ?: return
    val st = ms.status
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard(m.title) {
        StatusRow(when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO }, m.modelName, ModelLogic.headline(st, m))
        ModelNotes.of(m.modelName)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(m.about, style = MaterialTheme.typography.bodyMedium, color = muted)
        Progress(ms)
        if (st == ModelStatus.MISSING && ms.failure != null) Text(ms.failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (st == ModelStatus.BAD) Text("The file's checksum is wrong, so Robot won't load it. Delete it and download it again.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Download ${ModelLogic.gb(m.bytes)}" + (if (m.gpu) ", plus a cache its first load writes: about ${ModelLogic.gb(app.heyrobot.core.ModelFiles.GPU_CACHE_BYTES)} on the GPU, " +
                "up to ${ModelLogic.gb(m.cacheBytes)} on the CPU" else if (m.cacheBytes > 0) ", plus about ${ModelLogic.gb(m.cacheBytes)} of cache the CPU writes on its first load" else "") +
                ". " + (if (ms.cacheBytes > 0) "Its cache holds ${ModelLogic.gb(ms.cacheBytes)} now. " else "") +
                "Free on this phone: ${if (snap.freeBytes >= 0) ModelLogic.gb(snap.freeBytes) else "unknown"}.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
        when (st) {
            ModelStatus.MISSING, ModelStatus.BAD -> {
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) {
                    Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download (${ModelLogic.gb(m.bytes)})")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) {
                    Icon(RIcons.Folder, null); Spacer(Modifier.width(8.dp)); Text("Import a downloaded file")
                }
            }
            ModelStatus.DOWNLOADING -> OutlinedButton(onClick = { a.cancelDownload(m) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Cancel download") }
            ModelStatus.UNVERIFIED -> Button(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Check the file") }
            ModelStatus.VERIFIED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Icon(RIcons.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Check again") }
                OutlinedButton(onClick = onDelete, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete this model") }
            }
            ModelStatus.IMPORTING, ModelStatus.VERIFYING -> {}
        }
        if (st == ModelStatus.BAD || st == ModelStatus.UNVERIFIED) TextButton(onClick = onDelete) { Text("Delete the file") }
        Text("${m.publisher} · licence ${m.licence}\n${m.repo} at commit ${m.commit?.take(12)}\n${m.file}\nSHA-256 ${m.sha256}",
            style = MaterialTheme.typography.bodySmall, color = muted)
        if (termsConfirmed != null) {
            Text("You ticked its publisher's two conditions on ${java.text.DateFormat.getDateInstance().format(java.util.Date(termsConfirmed))}: " +
                "research and personal experimentation only, and you're responsible for its outputs.",
                style = MaterialTheme.typography.bodySmall, color = muted)
        } else if (ModelTerms.of(m) != null) {
            Text("Its publisher asks you to confirm two conditions before you download it: research and personal experimentation " +
                "only, and you're responsible for its outputs.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun Progress(ms: app.heyrobot.sys.ModelStore.Snap) {
    val p = ms.progress ?: return
    Text(p, style = MaterialTheme.typography.bodyMedium)
    if (ms.percent >= 0) LinearProgressIndicator(progress = { ms.percent / 100f }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

/**
 * Your own model file: imported through Android's file picker (no storage permission), copied into Robot's storage,
 * its size and SHA-256 shown, and what Robot's check found in it. A .litertlm runs with LiteRT-LM: on the TPU only for a
 * proven Tensor G5 build, and only once you have read its warning card, else on the CPU; a .gguf (0.4.5) with llama.cpp,
 * on the CPU. Which one it is, by its contents: never by the file's name.
 */
@Composable
private fun OwnModelCard(snap: Snapshot, busy: String?, a: AppActions, onDelete: () -> Unit) {
    val m = BrainModel.OWN
    val ms = snap.model(m) ?: return
    val st = ms.status
    val own = snap.own.takeIf { st == ModelStatus.VERIFIED || st == ModelStatus.BAD }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard(m.title) {
        StatusRow(when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO }, own?.name ?: "A .litertlm or .gguf file", ModelLogic.headline(st, m))
        // A file whose name (or a GGUF file's general.name) says MedGemma: one factual line here, never added to its answers (0.4.3).
        (ModelNotes.of(own?.name) ?: ModelNotes.of(own?.gguf?.name))?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(m.about, style = MaterialTheme.typography.bodyMedium, color = muted)
        Progress(ms)
        if (own != null) {
            Text("${ModelLogic.gb(own.bytes)} (${String.format(java.util.Locale.US, "%,d", own.bytes)} bytes)\nSHA-256 ${own.sha256}",
                style = MaterialTheme.typography.bodySmall, color = muted)
            Text("What Robot found inside", style = MaterialTheme.typography.titleSmall)
            for (line in own.lines()) Text("• $line", style = MaterialTheme.typography.bodyMedium)
            Text(when {
                own.backend == ModelBackend.TPU -> "Runs on the TPU: you allowed it."
                own.format == ModelFormat.GGUF && own.backend != null && !snap.ggufEngine ->
                    "This build of Robot can't run GGUF files: its llama.cpp library wasn't built. A build made with tools/build_llama.sh can."
                own.format == ModelFormat.GGUF && own.backend == ModelBackend.CPU -> "Runs on the CPU with llama.cpp, memory-mapped (no cache). " +
                    "Expect it to be slower than Fast: about the speed of the E4B models for a 4B file, faster for a smaller one."
                own.backend == ModelBackend.CPU -> "Runs on the CPU: about as slow as the E4B models or slower, depending on its size. " +
                    if (ms.cacheBytes > 0) "Its cache holds ${ModelLogic.gb(ms.cacheBytes)} now." else "Its first load writes a cache next to it."
                own.tensorG5Build && snap.phone.tpu != TpuSupport.SUPPORTED -> "It was compiled for the Tensor G5's TPU (Pixel 10), which this " +
                    "phone doesn't have, and a TPU build can't run on the CPU: Robot can't use it here."
                own.tensorG5Build -> "Waits for you to allow the TPU (below)."
                else -> "Robot can't run it on this phone. Delete it to free the space."
            }, style = MaterialTheme.typography.bodyMedium)
            // Built for 2,048 to 4,095 tokens (0.4.3), a .litertlm or a .gguf alike: chat only.
            if (own.shortContext && own.runsOn != null) {
                Text("Chat only: it was built for ${own.contextTokens} tokens of context, so Robot gives it a short prompt and no actions " +
                    "(timers, texts, notes, the calendar…) and keeps less of the conversation. Ask for an action and Robot says so " +
                    "and offers this page, where you can choose Fast. " + (if (own.vision) "It can see photos." else "It can't see photos."),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (own.tensorG5Build && !own.tpuAllowed && st == ModelStatus.VERIFIED && snap.phone.tpu == TpuSupport.SUPPORTED) TpuWarning(own, a)
            if (own.tpuAllowed) TextButton(onClick = { a.allowOwnTpu(false, null) }) { Text("Stop running it on the TPU") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { a.import(m) }, enabled = busy == null && st != ModelStatus.IMPORTING && st != ModelStatus.VERIFYING,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(RIcons.Folder, null); Spacer(Modifier.width(6.dp)); Text(if (ms.fileBytes > 0) "Import another file" else "Import a model file")
            }
            if (st == ModelStatus.VERIFIED) OutlinedButton(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
        }
        if (st == ModelStatus.UNVERIFIED) Button(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Check the file") }
        if (ms.fileBytes > 0 && st != ModelStatus.IMPORTING && st != ModelStatus.VERIFYING) {
            OutlinedButton(onClick = onDelete, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete this model")
            }
        }
        Text("A .litertlm (LiteRT-LM) or .gguf (llama.cpp) file. Robot copies it; it needs the file's size again in free space, " +
            "plus room for a cache when LiteRT-LM runs it on the CPU (a .gguf needs none)." +
            (if (snap.ggufEngine) "" else " This build of Robot can't run .gguf files (its llama.cpp library wasn't built)."),
            style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** The warning card before your own Tensor G5 build may run on the TPU: the risks the 0.4.1 research names, and the file. */
@Composable
private fun TpuWarning(own: OwnModel, a: AppActions) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(RIcons.Warning, null)
                Spacer(Modifier.width(10.dp))
                Text("Run this file on the TPU?", style = MaterialTheme.typography.titleSmall)
            }
            Text("Robot's check says this file was compiled for the Tensor G5's TPU, which this phone has. It isn't Robot's pinned Fast model, so " +
                "Robot can't vouch for it:", style = MaterialTheme.typography.bodyMedium)
            for (risk in TPU_RISKS) Text("• $risk", style = MaterialTheme.typography.bodyMedium)
            Text("${own.name} · ${ModelLogic.gb(own.bytes)} (${String.format(java.util.Locale.US, "%,d", own.bytes)} bytes)\nSHA-256 ${own.sha256}",
                style = MaterialTheme.typography.bodySmall)
            Text("A TPU build can't run on the CPU: without the TPU, Robot doesn't use it.", style = MaterialTheme.typography.bodyMedium)
            // Bound to the file this card shows: a different file in the meantime is never allowed by this tap.
            Button(onClick = { a.allowOwnTpu(true, own.sha256) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("I understand: run it on the TPU") }
        }
    }
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

/** Model › Voice models: speech recognition, the voice and the fast fallback voice, each downloaded on its own. */
@Composable
private fun VoiceModelsCard(snap: Snapshot, a: AppActions) {
    var confirmDelete by remember { mutableStateOf<VoiceBundle?>(null) }
    SectionCard("Voice models") {
        Text("For talking to Robot and hearing it answer. They run on the phone; nothing you say leaves it. " +
            "Each file is checked (SHA-256) before Robot uses it. Wi-Fi recommended.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (b in VoiceBundle.entries) {
            val v = snap.voice(b) ?: continue
            StatusRow(
                when (v.status) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO },
                "${b.title} · ${VoiceModelLogic.size(b.bytes)}",
                VoiceModelLogic.headline(v.status),
            )
            Text(b.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.progress?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
                if (v.percent >= 0) LinearProgressIndicator(progress = { v.percent / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            v.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            when (v.status) {
                ModelStatus.MISSING, ModelStatus.BAD -> Button(onClick = { a.voiceDownload(b) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download (${VoiceModelLogic.size(b.bytes)})")
                }
                ModelStatus.DOWNLOADING -> OutlinedButton(onClick = { a.voiceCancel(b) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Cancel download") }
                ModelStatus.VERIFIED -> OutlinedButton(onClick = { confirmDelete = b }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete")
                }
                else -> {}
            }
        }
        Text("Free on this phone: ${if (snap.freeBytes >= 0) ModelLogic.gb(snap.freeBytes) else "unknown"}.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    confirmDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${b.title}”?") },
            text = {
                Text(if (b == VoiceBundle.SPEAKERS) "Transcripts stop telling speakers apart (every line is “Speaker”) until you download it again " +
                    "(${VoiceModelLogic.size(b.bytes)}). A voiceprint of yours, if you made one, stays until you delete it in Settings › Conversation."
                else "Robot falls back to the stand-in (${if (b == VoiceBundle.STT) "typing only" else "the phone's voice"}) until you download it again (${VoiceModelLogic.size(b.bytes)}).")
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; a.voiceDelete(b) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Settings › Voice: wake words, hearing you while Robot talks (and its test), the volume key, the voice log (0.2.3), then
 * the voice, its speed, live words, the engines' state and the voice speed test.
 */
@Composable
private fun VoiceSettingsCard(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions) {
    SectionCard("Voice") {
        VoiceListeningSection(s, snap, update, a)
        if (!snap.voiceReady) {
            StatusRow(Mark.TODO, "Download voice models", "Speech recognition and Robot's voice (${VoiceModelLogic.size(VoiceBundle.STT.bytes + VoiceBundle.KOKORO.bytes)}). Until then you type, and Robot uses the phone's voice.") {
                FilledTonalButton(onClick = { a.openTab(Tab.MODEL) }) { Text("Open") }
            }
        }
        Text("Robot's voice", style = MaterialTheme.typography.titleSmall)
        for (v in VoiceChoice.entries) {
            val installed = v.bundle.let { b -> b == null || snap.voice(b)?.status == ModelStatus.VERIFIED }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = s.voiceChoice == v, role = Role.RadioButton) { update(s.copy(voice = v.id)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.voiceChoice == v, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.title, style = MaterialTheme.typography.bodyLarge)
                    Text(if (installed) v.detail else "${v.detail}. Not downloaded: the phone's voice speaks instead.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        var rate by remember(s.speechRate) { mutableFloatStateOf(s.speechRate) }
        Text("Speed: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(value = rate, onValueChange = { rate = it }, onValueChangeFinished = { update(s.copy(speechRate = RobotSettings.clampRate(rate))) },
            valueRange = 0.5f..2.0f, steps = 14)
        OutlinedButton(onClick = a.speakTest, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test the voice") }
        SwitchRow("Show the words while I speak", "Writes down your question as you talk (uses more battery).", s.livePartials) { update(s.copy(livePartials = it)) }
        val e = snap.engines
        if (e != null) {
            val stuck = listOf(VoiceBundle.STT, VoiceBundle.KOKORO, VoiceBundle.KITTEN).filter { e.state(it) == EngineState.BROKEN || e.state(it) == EngineState.FAILED }
            for (b in listOf(VoiceBundle.STT, s.voiceChoice.bundle).filterNotNull().distinct()) {
                val st = e.state(b)
                StatusRow(when (st) { EngineState.READY, EngineState.IDLE, EngineState.LOADING -> Mark.OK; EngineState.MISSING -> Mark.TODO; else -> Mark.WARN }, b.title, st.text())
            }
            if (stuck.isNotEmpty()) OutlinedButton(onClick = a.voiceReset, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
        }
        NavRow(RIcons.Search, "Transcription check", "How well Robot writes down Québec French and English: read sentences, or say anything " +
            "and fix it; scored as conversations are decoded") { a.openTranscriptionCheck() }
        Text("Test voice speed", style = MaterialTheme.typography.titleSmall)
        Text("Loads the downloaded models and times them on a built-in recording and two fixed sentences (nothing is played or saved).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val bench = snap.bench
        val running = bench?.state == BenchState.RUNNING && System.currentTimeMillis() - bench.started < 5 * 60_000L
        OutlinedButton(onClick = a.voiceBench, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (running) "Testing…" else "Test voice speed") }
        if (bench != null) {
            if (running) {
                Text(bench.step, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (bench.state == BenchState.FAILED) Text(bench.step, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            for (line in VoiceBenchLogic.lines(bench)) Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Settings › People, notes and calendar: the texting app and its SMS gateway, the phone region, the email app, the
 * calendar for new events and their length (docs/phase-2.md "Main app").
 */
@Composable
private fun PeopleSettingsCard(s: RobotSettings, snap: Snapshot, calendars: List<CalendarInfo>, update: (RobotSettings) -> Unit, a: AppActions) {
    val f = snap.facts
    SectionCard("People, notes and calendar") {
        Text("Robot never sends anything itself: texts and emails open in the app as drafts, and you press Send there.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Texting app", style = MaterialTheme.typography.titleSmall)
        val text = s.textAppFor(f?.cheogramInstalled == true)
        for (v in TextApp.entries) {
            ChoiceRow(v.title, when (v) {
                TextApp.CHEOGRAM -> if (f?.cheogramInstalled == true) "Numbers go through the SMS gateway below; XMPP addresses directly." else "Not installed."
                TextApp.SMS -> "The phone's SMS app, with the text filled in."
                TextApp.OFF -> "Robot doesn't write texts."
            }, text == v) { update(s.copy(textApp = v)) }
        }
        SettingField("SMS gateway", s.smsGateway, "The domain Cheogram texts numbers through (JMP: cheogram.com).") { v ->
            update(s.copy(smsGateway = PhoneNumbers.gateway(v)))
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // TelephonyManager calls: once per screen, not on every recomposition.
        val device = remember { app.heyrobot.sys.Phones.deviceRegion(ctx) }
        SettingField("Phone region", s.phoneRegion ?: "",
            "Two letters, for numbers without a country code (US, FR, DE…). Empty: this phone's own (${device ?: "unknown"}).", caps = true) { v ->
            update(s.copy(phoneRegion = PhoneNumbers.region(v)))
        }
        Text("Email app", style = MaterialTheme.typography.titleSmall)
        val mail = s.emailAppFor(f?.protonInstalled == true)
        for (v in EmailApp.entries) {
            ChoiceRow(v.title, when (v) {
                EmailApp.PROTON -> if (f?.protonInstalled == true) "Opens Proton Mail's composer with the draft." else "Not installed: Robot asks each time."
                EmailApp.ASK -> "Android asks which email app to use."
            }, mail == v) { update(s.copy(emailApp = v)) }
        }
        Text("Calendar for new events", style = MaterialTheme.typography.titleSmall)
        if (f?.calendarGranted != true) {
            StatusRow(Mark.TODO, "Allow the calendar", "Robot then adds events itself. Until then your calendar app opens with the event filled in.") {
                FilledTonalButton(onClick = a.askCalendar) { Text("Allow") }
            }
        } else if (calendars.isEmpty()) {
            Text("No calendar on this phone takes new events: your calendar app opens with the event filled in.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val auto = CalendarPick.choose(calendars, CalendarPick.AUTO)
            val chosen = CalendarPick.choose(calendars, s.eventCalendar)
            ChoiceRow("Automatic", auto?.let { "Now: ${it.name}" }, s.eventCalendar == CalendarPick.AUTO || chosen == null) {
                update(s.copy(eventCalendar = CalendarPick.AUTO))
            }
            for (c in calendars) {
                ChoiceRow(c.name, c.account.takeIf { it.isNotBlank() && it != c.name }, s.eventCalendar == c.id) { update(s.copy(eventCalendar = c.id)) }
            }
        }
        Text("Length of a new event", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in RobotSettings.EVENT_MINUTES.filter { it >= 30 }) {
                FilterChip(selected = s.eventMinutes == m, onClick = { update(s.copy(eventMinutes = m)) },
                    label = { Text(if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60}.${m % 60 * 10 / 60} h") },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        NavRow(RIcons.Person, "People", "Who Robot knows besides your contacts, and what you call them") { a.openPage(Page.People) }
        NavRow(RIcons.Note, "Robot Notes", "Your notes, to read, edit, search and share") { a.openTab(Tab.NOTES) }
        NavRow(RIcons.Person, "Contacts and calendar access", "Allow or check what Robot may read") { a.openSetup() }
    }
}

@Composable
internal fun ChoiceRow(title: String, detail: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A text setting saved with the keyboard's Done or the Save button (what's saved is the cleaned value). */
@Composable
internal fun SettingField(label: String, value: String, detail: String, caps: Boolean = false, save: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true,
            supportingText = { Text(detail) },
            keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save(v) }),
            modifier = Modifier.weight(1f),
        )
        if (v.trim() != value) TextButton(onClick = { save(v) }) { Text("Save") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(s: RobotSettings, snap: Snapshot, calendars: List<CalendarInfo>, update: (RobotSettings) -> Unit, a: AppActions, modifier: Modifier) {
    var confirmHistory by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Settings")
        SectionCard("Wake word") {
            SwitchRow("Listen for wake words", "“Hey robot” and the others in Settings › Voice › Wake words. The microphone stays on while Robot listens; nothing is recorded or saved.",
                s.wakeWord) { update(s.copy(wakeWord = it)) }
            SwitchRow("Only while charging", "Saves battery.", s.wakeOnlyWhileCharging, enabled = s.wakeWord) { update(s.copy(wakeOnlyWhileCharging = it)) }
        }
        SectionCard("Replies") {
            Text("Speak replies", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SpeakReplies.entries.forEachIndexed { i, v ->
                    SegmentedButton(
                        selected = s.speakReplies == v, onClick = { update(s.copy(speakReplies = v)) },
                        shape = SegmentedButtonDefaults.itemShape(i, SpeakReplies.entries.size),
                    ) { Text(when (v) { SpeakReplies.VOICE -> "Voice only"; SpeakReplies.ALWAYS -> "Always"; SpeakReplies.NEVER -> "Never" }) }
                }
            }
            Text("“Voice only” speaks when you asked by voice or with a wake word, not when you typed. " +
                "Tap the conversation, press volume down, or say “stop” (when the wake word is on), to stop Robot talking.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("Safety & privacy") {
            NavRow(RIcons.Lock, "Safety & privacy", "When Robot asks first (${s.safetyCards.title.replaceFirstChar { it.lowercase() }}), the lock screen, " +
                "what helpers may get, direct answers (${if (s.directAnswers) "on" else "off"}), screenshots in history") { a.openPage(Page.Safety) }
        }
        VoiceSettingsCard(s, snap, update, a)
        SectionCard("Actions") {
            Text("What Robot may do when you ask. On the lock screen: ${LockScreen.allowed(s.lockedTools)}; everything else asks you to unlock. " +
                "When “Just do it” still shows a card (after Robot read something, a name it isn't sure of, a helper's requests) is in " +
                "Safety & privacy.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The helpers' own settings decide about ask_claude (it has no level of its own).
            for (t in ToolName.entries.filter { !it.internal }) {
                ApprovalRow(t, s.approval(t), t in s.lockedTools) { lvl -> update(s.copy(approvals = s.approvals + (t to lvl))) }
                // Part of the maps actions (0.4.0): "the nearest …" found online, asked the first time by default.
                if (t == ToolName.MAPS_NAVIGATE) NearestOnlineChoice(s, snap, update)
            }
            Text(if (s.claudeActionCards) "Anything a helper asks Robot to do always shows a card first." else "A helper's requests follow the levels above and Safety cards.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("Conversation") {
            NavRow(RIcons.Message, "Conversation", "Transcribing: live words (${if (s.convPartials) "on" else "off"}), the wake word during it, " +
                "the lock screen, telling people") { a.openPage(Page.ConversationSettings) }
            NavRow(RIcons.History, "Transcripts", "Read, search, export, share and delete") { a.openPage(Page.Transcripts) }
        }
        PeopleSettingsCard(s, snap, calendars, update, a)
        WeatherSettingsCard(s, snap, update, a)
        HelpersCard(s, snap, a)
        SectionCard("Model") {
            val model = ModelCatalogue.resolve(s.brainModelChoice, snap.phone)
            val where = ModelCatalogue.backend(model, snap.phone, s.modelGpu, snap.gpuFailure?.model == model)
            NavRow(RIcons.Chip, "Robot's model", "${model.title}: ${model.modelName}" + (if (model.gpu) " on the ${where.title}" else "") +
                ". Download, choose or import a model") { a.openTab(Tab.MODEL) }
            val memory = if (model.memoryBytes > 0) "about ${PhoneStatus.size(model.memoryBytes)} of memory" else "about its file's size in memory"
            Text("Keep the model loaded after the panel closes ($memory; " +
                (if (where == ModelBackend.TPU) "it loads again in about 2 seconds)." else "it loads again in a few seconds once its cache is written)."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in RobotSettings.KEEP_CHOICES) {
                    FilterChip(selected = s.keepModelMinutes == m, onClick = { update(s.copy(keepModelMinutes = m)) },
                        label = { Text(if (m == RobotSettings.KEEP_ALWAYS) "Always" else "$m min") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        SectionCard("History") {
            SwitchRow("Save conversations", "Kept in Robot's private storage on this phone. " + if (s.keepMedia) "Thumbnails and screen text are kept too (Safety & privacy)."
                else "Photos and screen contents aren't saved (Safety & privacy).", s.saveHistory) { update(s.copy(saveHistory = it)) }
            TextButton(onClick = { confirmHistory = true }) { Text("Delete all conversations") }
        }
        SectionCard("Assistant") {
            NavRow(RIcons.Robot, "Assistant setup", "Check that Robot is your digital assistant", a.openSetup)
        }
        SectionCard("Advanced") {
            NavRow(RIcons.Chip, "ADB fixes", "One-time fixes with a computer, such as staying the assistant after a restart") { a.openPage(Page.AdbFixes) }
        }
        SectionCard("About") {
            Text("Robot ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text("Free software under the GNU GPL version 3, with NO WARRANTY. It uses LiteRT-LM, sherpa-onnx with espeak-ng, ONNX " +
                "Runtime, Jetpack Compose and more, and downloads models such as Gemma 4 and Parakeet when you ask: each with its " +
                "licence, credits and notices under Licences.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NavRow(RIcons.Info, "Licences", "Robot's licence and source code, and every part it uses with its licence and notices") { a.openPage(Page.Licences) }
            UpdatesEntry(a)
        }
        Spacer(Modifier.height(12.dp))
    }
    if (confirmHistory) {
        AlertDialog(
            onDismissRequest = { confirmHistory = false },
            title = { Text("Delete all conversations?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmHistory = false; a.deleteAllConversations() }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The whole row is the switch: one TalkBack stop that reads the title, the detail and the state.
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Settings › Actions › "Find the nearest place online" (0.4.0): Ask the first time (the default), Always, Never. The
 * panel's answer to its card ("Allow always", "Don't ask again") shows here when it is newer (core Nearby.mode).
 */
@Composable
private fun NearestOnlineChoice(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit) {
    val now = Nearby.mode(s, snap.nearestAnswer)
    val maps = s.approval(ToolName.MAPS_NAVIGATE) != Approval.OFF || s.approval(ToolName.MAPS_SEARCH) != Approval.OFF
    Column(Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp, bottom = 4.dp)) {
        Text("Find the nearest place online", style = MaterialTheme.typography.titleSmall)
        Text("“Directions to the nearest pharmacy”: what would go is the words and your area (about 1 km), to " +
            "${s.nearbyServer.label}." + if (maps) "" else " Maps search and directions are Off.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (m in NearestOnline.entries) {
            ChoiceRow(m.title + if (m == NearestOnline.ASK) " (default)" else "", m.detail, now == m) {
                update(s.copy(nearestOnline = m, nearestOnlineAt = System.currentTimeMillis()))
            }
        }
        SearchServerField(s, update)
    }
}

/**
 * Settings › Actions › "Find the nearest place online" › Search server (schema 12): OpenStreetMap's Nominatim by default, or
 * any HTTPS Nominatim you type (your own, or another public one). Nominatim's usage policy asks that apps can switch the
 * service without an update. Checked by core NearbyServer before it is saved; Reset goes back to OpenStreetMap's.
 */
@Composable
private fun SearchServerField(s: RobotSettings, update: (RobotSettings) -> Unit) {
    val server = s.nearbyServer
    val shown = if (server.isDefault) "" else server.base
    var text by remember(shown) { mutableStateOf(shown) }
    var problem by remember(shown) { mutableStateOf<String?>(null) }
    fun save() {
        val c = NearbyServer.check(text)
        val chosen = c.server ?: run { problem = c.problem; return }
        problem = null
        text = if (chosen.isDefault) "" else chosen.base
        update(s.copy(nearestServer = chosen.saved))
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Search server", style = MaterialTheme.typography.titleSmall)
        Text(if (server.isDefault) "OpenStreetMap's Nominatim (${Nearby.HOST}), the default. To use another Nominatim, such as your own, type its address."
            else "${server.name}: the words and your area go to this server instead of OpenStreetMap's.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = text, onValueChange = { text = it; problem = null }, singleLine = true,
            label = { Text("Nominatim server") }, placeholder = { Text(Nearby.HOST) }, isError = problem != null,
            supportingText = { Text(problem ?: "Its address, such as nominatim.example.org. Encrypted (HTTPS) only.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (text.trim() != shown) TextButton(onClick = { save() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
            if (!server.isDefault) {
                TextButton(onClick = { text = ""; problem = null; update(s.copy(nearestServer = "")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Reset to OpenStreetMap's")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalRow(t: ToolName, level: Approval, worksLocked: Boolean, onChange: (Approval) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(toolIcon(t), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(t.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (worksLocked) Text("works locked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.online) Text("uses the internet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Approval.entries.forEachIndexed { i, v ->
                SegmentedButton(selected = level == v, onClick = { onChange(v) }, shape = SegmentedButtonDefaults.itemShape(i, Approval.entries.size)) { Text(v.title) }
            }
        }
    }
}

/** Assistant setup › People, notes and calendar: the two permissions with their grant buttons, and the hand-off apps. */
@Composable
private fun PeopleSetupCard(f: AssistantFacts, a: AppActions) {
    SectionCard("People, notes and calendar") {
        Text("Robot asks for these only when you tap Allow. What it reads stays on the phone; texts and emails open as drafts and you press Send.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusRow(if (f.contactsGranted) Mark.OK else Mark.TODO, "Contacts",
            if (f.contactsGranted) "Allowed: Robot finds the person you name." else "To text or email someone by name, and to look up a number.") {
            if (!f.contactsGranted) FilledTonalButton(onClick = a.askContacts) { Text("Allow") }
        }
        StatusRow(if (f.calendarGranted) Mark.OK else Mark.TODO, "Calendar",
            if (f.calendarGranted) "Allowed: Robot reads your events and adds new ones." else "To read your events and add new ones (without it, your calendar app opens to add one).") {
            if (!f.calendarGranted) FilledTonalButton(onClick = a.askCalendar) { Text("Allow") }
        }
        StatusRow(if (f.cheogramInstalled) Mark.OK else Mark.TODO, "Cheogram",
            if (f.cheogramInstalled) "Installed: texts open there as drafts." else "Not installed: texts use the SMS app (Settings › People, notes and calendar).")
        StatusRow(if (f.protonInstalled) Mark.OK else Mark.TODO, "Proton Mail",
            if (f.protonInstalled) "Installed: emails open there as drafts." else "Not installed: Android asks which email app to use.")
        // 0.4.0: notification access is asked for only once "Learn names from Cheogram notifications" is on (Settings › People).
        when {
            f.learnFromCheogram && f.notificationAccess -> StatusRow(Mark.OK, "Cheogram notifications",
                "Notification access allowed: Robot keeps who messages you in Cheogram, never the text.")
            f.learnFromCheogram -> {
                StatusRow(Mark.TODO, "Cheogram notifications", "“Learn names from Cheogram notifications” is on (Settings › People) and needs " +
                    "notification access. Robot installed from a file needs one more step first:")
                NotificationAccessSteps()
            }
            else -> Text("Optional: Settings › People can learn names from Cheogram notifications. It is off, and needs no access while it is off.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!f.contactsGranted || !f.calendarGranted) {
            TextButton(onClick = a.openAppSettings) { Text("Android doesn't ask? Open Robot's app settings") }
        }
    }
}

/**
 * Assistant setup › Helpers: what the network is used for, GrapheneOS's Network toggle, each helper's key, and the Claude
 * app with which hand-off its installed version accepts (a claude:// link, a claude.ai link, a share), so you can tell
 * without adb.
 */
@Composable
private fun HelpersSetupCard(f: AssistantFacts, helpers: List<app.heyrobot.core.helpers.HelperConfig>, a: AppActions) {
    SectionCard("Helpers") {
        Text("Robot uses the network for four things only: downloading its models, a helper when you ask for one, the weather " +
            "(Open-Meteo) when you ask for it, and your own AI server, if you add one.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusRow(if (f.networkGranted) Mark.OK else Mark.WARN, "Network",
            if (f.networkGranted) "Allowed, as Android reports it. If a helper says “No internet” while you're online, check Robot's Network permission."
            else "Turned off for Robot: no helper can be reached.") {
            if (!f.networkGranted) TextButton(onClick = a.openAppSettings) { Text("Open") }
        }
        for (h in helpers.filter { it.presetOf.keyRequired }) {
            StatusRow(if (f.keys[h.id] != null) Mark.OK else Mark.TODO, "${h.name} API key", f.keys[h.id]
                ?: if (h.presetOf.app != null) "None: questions open the ${h.presetOf.defaultName} app instead (Settings › Helpers › ${h.name})." else "None (Settings › Helpers › ${h.name}).")
        }
        val c = f.claudeApp
        fun tick(b: Boolean) = if (b) "✓" else "✗"
        StatusRow(if (c.installed) Mark.OK else Mark.TODO, "Claude app",
            if (c.installed) "Installed. claude:// link ${tick(c.appLink)} · claude.ai link ${tick(c.webLink)} · Share ${tick(c.send)}" +
                (if (!c.appLink && !c.webLink && !c.send) ". Robot opens the app and puts your question on the clipboard." else "")
            else "Not installed.")
    }
}

@Composable
fun SetupScreen(f: AssistantFacts?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit, peopleFirst: Boolean = false,
    helpers: List<app.heyrobot.core.helpers.HelperConfig> = emptyList()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Opening Assistant setup checks the speech recognizer setting (Robot repairs it itself with the adb grant).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RecognizerKeeper.check(ctx, app.heyrobot.core.RepairWhen.SETUP) }
    }
    Scaffold(topBar = { BackBar("Assistant setup", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (f == null) {
                Text("Checking…")
                return@Column
            }
            // Opened from a card's "Allow": the permissions first.
            if (peopleFirst) PeopleSetupCard(f, a)
            if (peopleFirst) ToolsSetupCard(f, a)
            SectionCard("Right now") {
                StatusRow(if (f.roleHeld) Mark.OK else Mark.TODO, "Robot is the digital assistant",
                    if (f.roleHeld) "Android lists Robot as your assistant." else "Pick Robot in Settings › Apps › Default apps › Digital assistant app.")
                StatusRow(when (f.active) { true -> Mark.OK; false -> if (f.roleHeld) Mark.WARN else Mark.TODO; null -> Mark.TODO },
                    "The assistant is running",
                    when (f.active) {
                        true -> "The corner swipe and “hey robot” open Robot."
                        false -> if (f.roleHeld) "Android chose Robot but isn't running it. This happens after a restart: pick Robot again." else "Not yet."
                        null -> "Android didn't say."
                    })
                // "Ask about this screen" needs both of the assistant's screen settings (core ScreenAccess).
                val screen = ScreenAccess.setup(f.screenTextOn, f.screenshotOn)
                StatusRow(when (screen.level) { ScreenAccess.Level.OK -> Mark.OK; ScreenAccess.Level.INFO -> Mark.TODO; ScreenAccess.Level.WARN -> Mark.WARN },
                    "Screen sharing", screen.text) {
                    if (screen.level != ScreenAccess.Level.OK) TextButton(onClick = a.openAssistantSettings) { Text("Open") }
                }
                StatusRow(
                    if (f.recognizerIsRobot) Mark.OK else Mark.WARN,
                    "Default speech recognizer",
                    if (f.recognizerIsRobot) "Set to Robot, so Android keeps Robot as the assistant after a restart."
                    else "Not set to Robot (${if (f.recognizer == "unset") "none set" else "another app"}; ${f.recognizersFound} installed). After a restart Android may turn Robot off: see below.",
                )
                StatusRow(
                    if (f.recognizerIsRobot) Mark.OK else Mark.WARN,
                    "Survives restarts",
                    (if (f.recognizerIsRobot) "Yes: Robot is the speech recognizer, so Android keeps it the assistant."
                    else "Not yet: Android may turn Robot off at the next restart. A one-time fix with a computer solves it.") +
                        (if (f.secureSettingsGranted) " Robot keeps this set itself." else ""),
                ) {
                    if (!f.recognizerIsRobot || !f.secureSettingsGranted) FilledTonalButton(onClick = { a.openPage(Page.AdbFixes) }) { Text("Fix") }
                }
                StatusRow(if (f.micGranted) Mark.OK else Mark.TODO, "Microphone", if (f.micGranted) "Allowed" else "Needed for wake words and voice questions.")
                StatusRow(if (f.notificationsGranted) Mark.OK else Mark.TODO, "Notifications", if (f.notificationsGranted) "Allowed" else "Needed for the restart reminder.")
                StatusRow(
                    when (f.listening) { true -> Mark.OK; else -> Mark.TODO },
                    "Listening for wake words",
                    when (f.listening) {
                        true -> "Yes"
                        false -> f.listeningNote ?: "No"
                        null -> "Starts when Robot is the running assistant."
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = a.openAssistantSettings, modifier = Modifier.heightIn(min = 52.dp)) { Text("Assistant settings") }
                    if (!f.micGranted || !f.notificationsGranted) OutlinedButton(onClick = a.askPermissions, modifier = Modifier.heightIn(min = 52.dp)) { Text("Allow") }
                }
            }
            if (!peopleFirst) PeopleSetupCard(f, a)
            if (!peopleFirst) ToolsSetupCard(f, a)
            HelpersSetupCard(f, helpers, a)
            SectionCard("After a restart") {
                Text(
                    "Android 17 keeps a non-system assistant across a restart only when a default speech recognizer is set, and it never " +
                        "picks one from an app like Robot by itself. GrapheneOS has no screen to choose one. So after a restart, Android may " +
                        "quietly turn Robot off as your assistant.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Robot checks 3 minutes after every restart. If it was turned off, you get a notification: tap it, choose " +
                        "“Digital assistant app” and pick Robot again. To make it stay, use the one-time fix in Settings › Advanced › ADB fixes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { a.openPage(Page.AdbFixes) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("ADB fixes") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
