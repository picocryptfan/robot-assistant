package app.heyrobot.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtModelLogic
import app.heyrobot.core.MtSpeedReport
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.sys.MtLink
import app.heyrobot.sys.MtModelStore
import app.heyrobot.sys.MtSpeedRun
import app.heyrobot.ui.common.RIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Conversation (0.5.0, docs/conversation-mode.md 9.3): the switches for transcribing (each with
// the default the plan gives), the consent reminder, the ways to the transcripts and the Transcription check, and the
// fast translation engine: its download and its speed test (phase 5.0(b)), which talks to the :mt process through
// MtLink (by name: this process never loads the engine). The session itself runs in the voice process, which re-reads
// the settings when they change.

@Composable
fun ConversationSettingsScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    Scaffold(topBar = { BackBar("Conversation", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Robot writes down a conversation in French and English, on this phone, and saves each line as it goes. Start it from Home, " +
                "the Quick Settings tile “Transcribe”, a long press on Robot's icon, or say “hey robot, transcribe this conversation”.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard("Transcribing") {
                SwitchRow("Live words", "The words of the line being spoken, in grey, while the transcript is on screen (uses more battery; " +
                    "off when the phone is warm).", s.convPartials) { update(s.copy(convPartials = it)) }
                SwitchRow("“Hey robot” works during a conversation", if (s.convWakeDuring) "“Hey robot” pauses the transcript and opens Robot; " +
                    "what you say to Robot isn't transcribed." else "The wake word sleeps while Robot transcribes, so nobody's “hey robot” " +
                    "opens it. “Stop” still works while Robot talks, and the wake word is back when you stop.", s.convWakeDuring) { update(s.copy(convWakeDuring = it)) }
                SwitchRow("Start from the lock screen", "The tile and “hey robot” can start a transcript while the phone is locked. That screen " +
                    "shows only what it hears from then on; reading, exporting and deleting transcripts need the phone unlocked.",
                    s.convStartLocked) { update(s.copy(convStartLocked = it)) }
            }
            SectionCard("Telling people") {
                Text(ConsentRecord.TEXT, style = MaterialTheme.typography.bodyMedium)
                Text("Robot shows this before your first transcript, and the notification says “${ConsentRecord.LINE}” the whole time.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    update(s.copy(convReminderAt = System.currentTimeMillis()))
                    a.say("The reminder shows before the next transcript.")
                }) { Text("Show the reminder again") }
            }
            SectionCard("Transcripts") {
                NavRow(RIcons.Message, "Transcripts", "Read, search, rename, export, share and delete") { a.openPage(Page.Transcripts) }
                NavRow(RIcons.Search, "Transcription check", "How well Robot writes down Québec French and English") { a.openTranscriptionCheck() }
                Text("Transcripts are kept in Robot's private storage on this phone and never backed up. The audio is never kept.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TranslationCard(snap.mt, a)
            Text("Coming later: speaker labels, translated transcripts and the interpreter, summaries.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Settings › Conversation › Translation: the fast translation engine's files (download, cancel, delete, status) and Test
 * translation speed. The test keeps its link to the :mt process while this screen shows, so the engine stays loaded for
 * `adb shell dumpsys meminfo app.heyrobot:mt`; leaving the screen lets it go.
 */
@Composable
private fun TranslationCard(mt: MtModelStore.Snap?, a: AppActions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val b = MtBundle.FR_EN
    var confirmDelete by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<MtSpeedReport?>(null) }
    var step by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<MtLink?>(null) }
    val total = remember { MtSpeedRun.testSet(ctx).size }
    LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { MtSpeedRun.last(ctx) } }
    DisposableEffect(Unit) { onDispose { link?.close() } }
    SectionCard("Translation") {
        Text("Robot's fast translation engine translates French ↔ English on this phone, in a process of its own: Mozilla's " +
            "Bergamot models, run by slimt. Translated transcripts and the interpreter use it in a later update (Gemma then " +
            "improves the lines); for now you can download it and test it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mt == null) return@SectionCard
        StatusRow(
            when (mt.status) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO },
            MtModelLogic.label(b),
            VoiceModelLogic.headline(mt.status),
        )
        if (!mt.engine) {
            Text("This build of Robot doesn't include the fast engine (${MtModelStore.LIBRARY}), so it can't translate with it even " +
                "once the files are downloaded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        mt.progress?.let { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium)
            if (mt.percent >= 0) LinearProgressIndicator(progress = { mt.percent / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        mt.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        when (mt.status) {
            ModelStatus.MISSING, ModelStatus.BAD -> Button(onClick = a.mtDownload, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download (${VoiceModelLogic.size(b.downloadBytes)})")
            }
            ModelStatus.DOWNLOADING -> OutlinedButton(onClick = a.mtCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Cancel download") }
            ModelStatus.VERIFIED -> OutlinedButton(onClick = { confirmDelete = true }, enabled = step == null, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete")
            }
            else -> {}
        }
        Text("From Mozilla's servers, the files each direction needs: ${VoiceModelLogic.size(b.downloadBytes)} to download, " +
            "${VoiceModelLogic.size(b.bytes)} in Robot's private storage. Each is checked (SHA-256) before Robot uses it. Wi-Fi recommended.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Test translation speed", style = MaterialTheme.typography.titleSmall)
        Text("Translates Robot's $total test lines (Québec French and English) and compares each with what the same engine gave on " +
            "the build machine. The mark: a median of ${MtSpeedReport.MEDIAN_GATE_MS} ms a line or less, ${MtSpeedReport.RSS_GATE_MB} MB " +
            "or less with both directions loaded, and every line the same. Only the test's own sentences are translated.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val ready = mt.status == ModelStatus.VERIFIED && mt.engine
        OutlinedButton(
            onClick = {
                val l = link ?: MtLink(ctx).also { link = it }
                step = "Starting…"
                scope.launch {
                    try {
                        report = MtSpeedRun.run(ctx, l) { step = it }
                    } finally {
                        step = null
                    }
                }
            },
            enabled = ready && step == null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(if (step != null) "Testing…" else "Test translation speed") }
        step?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        report?.let { r ->
            Text("Last test: ${VoiceLogText.ago(System.currentTimeMillis() - r.at)}${if (r.passed) ", passed" else ""}.",
                style = MaterialTheme.typography.bodyMedium)
            for (line in r.summary(total)) {
                Text(line, style = MaterialTheme.typography.bodyMedium,
                    color = if (line.endsWith("misses.") || r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            if (r.error == null) {
                Text("While this screen shows, the engine stays loaded: `adb shell dumpsys meminfo app.heyrobot:mt` shows its memory.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    val text = r.plain(MtSpeedRun.testSet(ctx), "Robot ${BuildConfig.VERSION_NAME} translation speed test, ${VoiceLogText.clock(r.at)}")
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Robot translation speed test", text))
                    a.say("Copied: the result and every line that wasn't the same.")
                }) { Text("Copy the result") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the fast translation engine's files?") },
            text = { Text("Robot can't translate with the fast engine until you download them again (${VoiceModelLogic.size(b.downloadBytes)}).") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    link?.close()
                    link = null
                    a.mtDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
