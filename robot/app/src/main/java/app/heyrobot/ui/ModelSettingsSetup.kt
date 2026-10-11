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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.TextApp
import app.heyrobot.core.EngineState
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelNotes
import app.heyrobot.core.ModelStatus
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
 * voice models.
 */
@Composable
fun ModelScreen(s: RobotSettings, update: (RobotSettings) -> Unit, snap: Snapshot, busy: String?, a: AppActions, modifier: Modifier) {
    var confirmDelete by remember { mutableStateOf<BrainModel?>(null) }
    val chosen = s.brainModelChoice
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Model", "Robot's AI runs on this phone. It goes online only to download a model you pick.")
        ModelChoiceCard(s, update, snap)
        for (m in BrainModel.entries.filter { it.builtIn }) ModelCard(m, snap, busy, a, onDelete = { confirmDelete = m })
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
                m == BrainModel.OWN && st == ModelStatus.VERIFIED && snap.own?.report?.tensorG5Build == true -> "Allow the TPU for it below first."
                st == ModelStatus.DOWNLOADING || st == ModelStatus.IMPORTING || st == ModelStatus.VERIFYING -> "Getting it ready below…"
                st == ModelStatus.BAD -> "Its file can't be used: see below."
                m.builtIn -> "Download it below first."
                else -> "Import a file below first."
            }
            // Your own file, checked and short (0.4.3): said where you choose it, not only on its card below.
            val short = if (m == BrainModel.OWN && ready) snap.own?.report?.takeIf { it.shortContext }
                ?.let { r -> " Chat only (short context): no actions." + if (r.vision) " Can see photos." else "" } else null
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

/** One built-in model: status, what it is, size and cache, Download / Check / Delete, and where it comes from. */
@Composable
private fun ModelCard(m: BrainModel, snap: Snapshot, busy: String?, a: AppActions, onDelete: () -> Unit) {
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
                Button(onClick = { a.download(m) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) {
                    Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download (${ModelLogic.gb(m.bytes)})")
                }
                OutlinedButton(onClick = { a.import(m) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) {
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
 * its size and SHA-256 shown, and what Robot's check found in it. The TPU only for a proven Tensor G5 build, and only
 * once you have read its warning card; any other file runs on the CPU. Never by the file's name.
 */
@Composable
private fun OwnModelCard(snap: Snapshot, busy: String?, a: AppActions, onDelete: () -> Unit) {
    val m = BrainModel.OWN
    val ms = snap.model(m) ?: return
    val st = ms.status
    val own = snap.own.takeIf { st == ModelStatus.VERIFIED || st == ModelStatus.BAD }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard(m.title) {
        StatusRow(when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO }, own?.name ?: "A .litertlm file", ModelLogic.headline(st, m))
        // A file whose name says MedGemma: one factual line here, never added to its answers (0.4.3).
        ModelNotes.of(own?.name)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(m.about, style = MaterialTheme.typography.bodyMedium, color = muted)
        Progress(ms)
        if (own != null) {
            Text("${ModelLogic.gb(own.bytes)} (${String.format(java.util.Locale.US, "%,d", own.bytes)} bytes)\nSHA-256 ${own.sha256}",
                style = MaterialTheme.typography.bodySmall, color = muted)
            Text("What Robot found inside", style = MaterialTheme.typography.titleSmall)
            for (line in own.report.lines()) Text("• $line", style = MaterialTheme.typography.bodyMedium)
            Text(when {
                own.backend == ModelBackend.TPU -> "Runs on the TPU: you allowed it."
                own.backend == ModelBackend.CPU -> "Runs on the CPU: about as slow as the E4B models or slower, depending on its size. " +
                    if (ms.cacheBytes > 0) "Its cache holds ${ModelLogic.gb(ms.cacheBytes)} now." else "Its first load writes a cache next to it."
                own.report.tensorG5Build -> "Waits for you to allow the TPU (below)."
                else -> "Robot can't run it on this phone. Delete it to free the space."
            }, style = MaterialTheme.typography.bodyMedium)
            if (own.report.shortContext && own.report.backend != null) {
                Text("Chat only: it was built for ${own.report.maxTokens} tokens of context, so Robot gives it a short prompt and no actions " +
                    "(timers, texts, notes, the calendar…) and keeps less of the conversation. Ask for an action and Robot says so " +
                    "and offers this page, where you can choose Fast. " + (if (own.report.vision) "It can see photos." else "It can't see photos."),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (own.report.tensorG5Build && !own.tpuAllowed && st == ModelStatus.VERIFIED) TpuWarning(own, a)
            if (own.tpuAllowed) TextButton(onClick = { a.allowOwnTpu(false, null) }) { Text("Stop running it on the TPU") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { a.import(m) }, enabled = busy == null && st != ModelStatus.IMPORTING && st != ModelStatus.VERIFYING,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(RIcons.Folder, null); Spacer(Modifier.width(6.dp)); Text(if (ms.fileBytes > 0) "Import another file" else "Import a .litertlm file")
            }
            if (st == ModelStatus.VERIFIED) OutlinedButton(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
        }
        if (st == ModelStatus.UNVERIFIED) Button(onClick = { a.verify(m) }, enabled = busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Check the file") }
        if (ms.fileBytes > 0 && st != ModelStatus.IMPORTING && st != ModelStatus.VERIFYING) {
            OutlinedButton(onClick = onDelete, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete this model")
            }
        }
        Text("Robot copies the file; it needs the file's size again in free space, plus room for a cache when it runs on the CPU.",
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
            text = { Text("Robot falls back to the stand-in (${if (b == VoiceBundle.STT) "typing only" else "the phone's voice"}) until you download it again (${VoiceModelLogic.size(b.bytes)}).") },
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

/**
 * Settings › Ask Claude (docs/ask-claude.md "Key and settings"): on or off, the route (the Claude app or the API), the API
 * key (added in a small window of the assistant's process, shown masked here, deleted here), the model, how hard Claude
 * thinks, web search, the lock screen, Claude's voice, the cost line, when Robot may bring Claude in, and the daily budget.
 */
@Composable
private fun ClaudeSettingsCard(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions) {
    val f = snap.facts
    val keySaved = f?.claudeKey != null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard("Ask Claude") {
        Text("Nothing goes to Claude unless you ask for Claude: say “ask Claude …” or “go get Claude”, or let Robot check hard questions " +
            "with Claude (below). Only text leaves the phone: your voice is turned into text here, and Claude's answer is read by Robot's voices.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        SwitchRow("Ask Claude", if (keySaved) "Questions for Claude go through your API key." else "Without a key, questions for Claude open the Claude app.",
            ClaudeSettings.enabled(s, keySaved)) { update(s.copy(claudeEnabled = it)) }
        Text("Use", style = MaterialTheme.typography.titleSmall)
        val route = ClaudeSettings.route(s, keySaved)
        for (r in ClaudeRoute.entries) {
            val note = when {
                r == ClaudeRoute.APP && f?.claudeApp?.installed == false -> " The Claude app isn't installed."
                r == ClaudeRoute.API && !keySaved -> " Add a key first."
                else -> ""
            }
            ChoiceRow(r.title, r.detail + note, route == r) { update(s.copy(claudeRoute = r)) }
        }
        Text("API key", style = MaterialTheme.typography.titleSmall)
        var confirmDelete by remember { mutableStateOf(false) }
        StatusRow(if (keySaved) Mark.OK else if (f?.claudeKeyUnreadable == true) Mark.WARN else Mark.TODO, f?.claudeKey ?: "No key",
            if (f?.claudeKeyUnreadable == true) "Robot couldn't read the saved key (Android's keystore lost it), so it was removed. Add it again."
            else "From console.anthropic.com › API keys. API use is paid with API credits; a claude.ai Pro or Max plan doesn't cover it.") {
            if (keySaved) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete key") } else FilledTonalButton(onClick = a.addClaudeKey) { Text("Add key") }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete the Claude key?") },
                text = { Text("Robot removes the key from this phone. Ask Claude stays on and uses the Claude app until you add a key again.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; a.deleteClaudeKey() }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
        Text("Model", style = MaterialTheme.typography.titleSmall)
        for (m in ClaudeModel.entries) ChoiceRow(m.title, m.detail, s.claudeModelChoice == m) { update(s.copy(claudeModel = m.id)) }
        Text("How hard Claude thinks", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ClaudeEffortSetting.entries.forEachIndexed { i, v ->
                SegmentedButton(selected = s.claudeEffort == v, onClick = { update(s.copy(claudeEffort = v)) },
                    shape = SegmentedButtonDefaults.itemShape(i, ClaudeEffortSetting.entries.size)) { Text(v.title) }
            }
        }
        Text("Automatic: low for spoken questions (fast), medium for typed ones, high when you ask Claude to “think hard” or “take your time”.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        SwitchRow("Let Claude search the web", "Up to 3 searches per question, about 1¢ each. The answer lists the sites.", s.claudeSearch) { update(s.copy(claudeSearch = it)) }
        SwitchRow("Allow on lock screen", "Off: a locked phone can't show who is asking.", s.claudeLocked) { update(s.copy(claudeLocked = it)) }
        Text("Claude's voice", style = MaterialTheme.typography.titleSmall)
        for ((name, _) in ClaudeVoices.KOKORO) ChoiceRow(ClaudeVoices.title(name), null, ClaudeVoices.of(s.claudeVoice) == name) { update(s.copy(claudeVoice = name)) }
        Text("A Kokoro voice, so you hear who is talking. With the Kitten voice Claude uses Kitten's other voice; with the phone's voice, a lower pitch.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        SwitchRow("Show cost", "“≈ 1¢” under each Claude reply, estimated from the published prices.", s.claudeShowCost) { update(s.copy(claudeShowCost = it)) }
        Text("When Robot needs help", style = MaterialTheme.typography.titleSmall)
        val help = ClaudeSettings.escalation(s, keySaved)
        for (e in Escalation.entries) {
            ChoiceRow(e.title, e.detail + if (e == Escalation.AUTO && !keySaved) " Needs an API key: until then Robot asks first." else "", help == e) { update(s.copy(claudeHelp = e)) }
        }
        Text(if (s.personalToHelpers) "By itself, Robot sends your question and the last two turns of the conversation, with any screen text, " +
            "photo or what it read (Safety & privacy › Send personal content to helpers automatically is on)."
            else "By itself, Robot sends only your question and the last two turns of plain conversation. Once a conversation has screen text, " +
            "a photo, or what Robot read from your contacts, calendar or notes, the weather (where you are) or your phone's status, it sends " +
            "only your question; anything more goes only after you tap a card that lists it.", style = MaterialTheme.typography.bodyMedium, color = muted)
        Text("Daily budget", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in RobotSettings.CLAUDE_BUDGETS) {
                FilterChip(selected = s.claudeBudgetCents == c, onClick = { update(s.copy(claudeBudgetCents = c)) },
                    label = { Text(ClaudeCost.dollarsText(c * 10_000L).removeSuffix(".00")) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text("Today: ${ClaudeCost.dollarsText(f?.claudeSpendMicros ?: 0)} of ${ClaudeCost.dollarsText(s.claudeBudgetCents * 10_000L)} (an estimate). " +
            "Once it's reached, Robot asks before checking with Claude by itself until tomorrow.", style = MaterialTheme.typography.bodyMedium, color = muted)
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
private fun SettingField(label: String, value: String, detail: String, caps: Boolean = false, save: (String) -> Unit) {
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
                "what Claude may get, direct answers (${if (s.directAnswers) "on" else "off"}), screenshots in history") { a.openPage(Page.Safety) }
        }
        VoiceSettingsCard(s, snap, update, a)
        SectionCard("Actions") {
            Text("What Robot may do when you ask. On the lock screen: ${LockScreen.allowed(s.lockedTools)}; everything else asks you to unlock. " +
                "When “Just do it” still shows a card (after Robot read something, a name it isn't sure of, Claude's requests) is in " +
                "Safety & privacy.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Ask Claude's own settings decide about ask_claude (it has no level of its own).
            for (t in ToolName.entries.filter { !it.internal }) {
                ApprovalRow(t, s.approval(t), t in s.lockedTools) { lvl -> update(s.copy(approvals = s.approvals + (t to lvl))) }
                // Part of the maps actions (0.4.0): "the nearest …" found online, asked the first time by default.
                if (t == ToolName.MAPS_NAVIGATE) NearestOnlineChoice(s, snap, update)
            }
            Text(if (s.claudeActionCards) "Anything Claude asks Robot to do shows a card first." else "Claude's requests follow the levels above and Safety cards.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PeopleSettingsCard(s, snap, calendars, update, a)
        WeatherSettingsCard(s, snap, update, a)
        ClaudeSettingsCard(s, snap, update, a)
        SectionCard("Model") {
            val model = s.brainModelChoice
            NavRow(RIcons.Chip, "Robot's model", "${model.title}: ${model.modelName}. Download, choose or import a model") { a.openTab(Tab.MODEL) }
            val memory = if (model.memoryBytes > 0) "about ${PhoneStatus.size(model.memoryBytes)} of memory" else "about its file's size in memory"
            Text("Keep the model loaded after the panel closes ($memory; " +
                (if (model.backend == ModelBackend.TPU) "it loads again in about 2 seconds)." else "it loads again in a few seconds once its cache is written)."),
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
            Text("Free software under the GNU GPL version 3. Third-party parts: LiteRT-LM and the Tensor NPU runtime (Apache-2.0), " +
                "sherpa-onnx (Apache-2.0) with espeak-ng (GPL-3.0-or-later), ONNX Runtime (MIT), Silero VAD (MIT), Jetpack Compose and AndroidX (Apache-2.0), " +
                "Gemma 4 E2B and E4B by Google (Apache-2.0), the Uncensored model by olekk (Gemma 4 E4B abliterated, Apache-2.0), " +
                "your own model file under its own licence, Kokoro and Kitten TTS (Apache-2.0), the Anthropic Java SDK (MIT) with OkHttp, Okio and Jackson (Apache-2.0). " +
                "Speech recognition: NVIDIA Parakeet TDT 0.6B v3 by NVIDIA, " +
                "CC-BY-4.0, converted to ONNX by sherpa-onnx (huggingface.co/nvidia/parakeet-tdt-0.6b-v3).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
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
        Text("“Directions to the nearest pharmacy”: what would go is the words and your area (about 1 km), to OpenStreetMap's " +
            "Nominatim." + if (maps) "" else " Maps search and directions are Off.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (m in NearestOnline.entries) {
            ChoiceRow(m.title + if (m == NearestOnline.ASK) " (default)" else "", m.detail, now == m) {
                update(s.copy(nearestOnline = m, nearestOnlineAt = System.currentTimeMillis()))
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
 * Assistant setup › Ask Claude: what the network is used for, GrapheneOS's Network toggle, and the Claude app with which
 * hand-off its installed version accepts (a claude:// link, a claude.ai link, a share), so you can tell without adb.
 */
@Composable
private fun ClaudeSetupCard(f: AssistantFacts, a: AppActions) {
    SectionCard("Ask Claude") {
        Text("Robot uses the internet for three things only: downloading its models, Ask Claude when you ask for Claude, and the " +
            "weather (Open-Meteo) when you ask for it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusRow(if (f.networkGranted) Mark.OK else Mark.WARN, "Network",
            if (f.networkGranted) "Allowed, as Android reports it. If Ask Claude says “No internet” while you're online, check Robot's Network permission."
            else "Turned off for Robot: Ask Claude can't reach Claude.") {
            if (!f.networkGranted) TextButton(onClick = a.openAppSettings) { Text("Open") }
        }
        StatusRow(if (f.claudeKey != null) Mark.OK else Mark.TODO, "Claude API key", f.claudeKey ?: "None: questions open the Claude app instead (Settings › Ask Claude).")
        val c = f.claudeApp
        fun tick(b: Boolean) = if (b) "✓" else "✗"
        StatusRow(if (c.installed) Mark.OK else Mark.TODO, "Claude app",
            if (c.installed) "Installed. claude:// link ${tick(c.appLink)} · claude.ai link ${tick(c.webLink)} · Share ${tick(c.send)}" +
                (if (!c.appLink && !c.webLink && !c.send) ". Robot opens the app and puts your question on the clipboard." else "")
            else "Not installed.")
    }
}

@Composable
fun SetupScreen(f: AssistantFacts?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit, peopleFirst: Boolean = false) {
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
            ClaudeSetupCard(f, a)
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
