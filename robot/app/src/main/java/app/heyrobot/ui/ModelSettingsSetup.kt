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
import app.heyrobot.core.ModelFormat
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
 * voice models. 0.4.5: your own file may be a GGUF file (llama.cpp, on the CPU), and "Let the model think first".
 */
@Composable
fun ModelScreen(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, busy: String?, a: AppActions, modifier: Modifier) {
    var confirmDelete by remember { mutableStateOf<BrainModel?>(null) }
    /** A publisher's terms to tick before its file downloads (false) or is imported (true). */
    var askTerms by remember { mutableStateOf<Pair<ModelTerms, Boolean>?>(null) }
    val chosen = s.brainModelChoice
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Model", "Robot's AI runs on this phone. It goes online only to download a model you pick.")
        ModelChoiceCard(s, update, snap)
        ThinkingCard(s, update)
        for (m in BrainModel.entries.filter { it.builtIn }) {
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
        val others = BrainModel.entries.any { it != m && snap.ready(it) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${m.title}”?") },
            text = {
                Text("Removes its file (${ModelLogic.gb(ms?.fileBytes ?: 0)})" + (if ((ms?.cacheBytes ?: 0) > 0) " and its cache (${ModelLogic.gb(ms!!.cacheBytes)})" else "") +
                    " from this phone." + (if (m == chosen && m != BrainModel.FAST) " Robot goes back to Fast." else "") +
                    (if (!others) " Robot can't answer until you download or import a model again." else ""))
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; a.deleteModel(m) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** "Robot uses": a model can be chosen once it is on the phone and checked; until then Robot uses Fast. */
@Composable
private fun ModelChoiceCard(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot) {
    val chosen = s.brainModelChoice
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard("Robot uses") {
        for (m in BrainModel.entries) {
            val ready = snap.ready(m)
            val st = snap.model(m)?.status
            val note = when {
                ready -> null
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
            ChoiceRow(m.title, m.speed + (short ?: "") + (note?.let { " $it" } ?: ""), chosen == m, enabled = ready || chosen == m) {
                // Tapping the chosen one again saves too: Robot then retries a model that didn't load.
                update(s.copy(brainModel = m.id))
            }
        }
        if (!snap.ready(chosen) && chosen != BrainModel.FAST) {
            Text("Until “${chosen.title}” is ready, Robot uses Fast.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Text("Whichever model answers, Robot's cards and switches (Settings › Actions, Safety & privacy) decide what asks you first, " +
            "and nothing leaves the phone unless you ask. Questions in a new conversation take longer with the slower models.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        snap.loaded?.let { Text("In memory now: ${it.title} (${it.modelName}).", style = MaterialTheme.typography.bodySmall, color = muted) }
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
            "Download ${ModelLogic.gb(m.bytes)}" + (if (m.cacheBytes > 0) ", plus about ${ModelLogic.gb(m.cacheBytes)} of cache the CPU writes on its first load" else "") +
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
            if (own.tensorG5Build && !own.tpuAllowed && st == ModelStatus.VERIFIED) TpuWarning(own, a)
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
            Text("Robot's check says this file was compiled for this phone's TPU (Tensor G5). It isn't Robot's pinned Fast model, so " +
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
