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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.core.Approval
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelSpec
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.ToolName
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.toolIcon

// MAIN PROCESS. The model manager, settings and the assistant setup check.

@Composable
fun ModelScreen(snap: Snapshot, busy: String?, a: AppActions, modifier: Modifier) {
    var confirmDelete by remember { mutableStateOf(false) }
    val st = snap.model
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Model", "Gemma 4 E2B runs on the phone's TPU. It never goes online.")
        SectionCard {
            StatusRow(
                when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO },
                ModelSpec.NAME,
                ModelLogic.headline(st),
            )
            val p = snap.progress
            if (p != null) {
                Text(p, style = MaterialTheme.typography.bodyMedium)
                if (snap.percent >= 0) LinearProgressIndicator(progress = { snap.percent / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (st == ModelStatus.MISSING && snap.failure != null) Text(snap.failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (st == ModelStatus.BAD) Text("The file's checksum is wrong, so Robot won't load it. Delete it and download it again.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Size ${ModelLogic.gb(ModelSpec.BYTES)}. Free on this phone: ${if (snap.freeBytes >= 0) ModelLogic.gb(snap.freeBytes) else "unknown"}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (st) {
                ModelStatus.MISSING, ModelStatus.BAD -> {
                    Button(onClick = a.download, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) {
                        Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download (${ModelLogic.gb(ModelSpec.BYTES)})")
                    }
                    OutlinedButton(onClick = a.import, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) {
                        Icon(RIcons.Folder, null); Spacer(Modifier.width(8.dp)); Text("Import a downloaded file")
                    }
                }
                ModelStatus.DOWNLOADING -> OutlinedButton(onClick = a.cancelDownload, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Cancel download") }
                ModelStatus.UNVERIFIED -> Button(onClick = a.verify, enabled = busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Check the file") }
                ModelStatus.VERIFIED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = a.verify, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Icon(RIcons.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Check again") }
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete") }
                }
                ModelStatus.IMPORTING, ModelStatus.VERIFYING -> {}
            }
            if (st == ModelStatus.BAD || st == ModelStatus.UNVERIFIED) {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete the file") }
            }
        }
        SectionCard("About the download") {
            Text("The file comes from Hugging Face (litert-community). Robot checks its SHA-256 before it is ever loaded. " +
                "The download resumes by itself if the connection drops. You can also download the file on a computer and import it here.",
                style = MaterialTheme.typography.bodyMedium)
            Text("${ModelSpec.FILE}\nSHA-256 ${ModelSpec.SHA256}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the model?") },
            text = { Text("Robot can't answer until you download or import it again (3.1 GB).") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; a.deleteModel() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, modifier: Modifier) {
    var confirmHistory by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Settings")
        SectionCard("Wake word") {
            SwitchRow("Listen for “hey robot”", "The microphone stays on while Robot listens; nothing is recorded or saved.", s.wakeWord) { update(s.copy(wakeWord = it)) }
            SwitchRow("Only while charging", "Saves battery.", s.wakeOnlyWhileCharging, enabled = s.wakeWord) { update(s.copy(wakeOnlyWhileCharging = it)) }
        }
        SectionCard("Spoken replies") {
            Text("Speak replies", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SpeakReplies.entries.forEachIndexed { i, v ->
                    SegmentedButton(
                        selected = s.speakReplies == v, onClick = { update(s.copy(speakReplies = v)) },
                        shape = SegmentedButtonDefaults.itemShape(i, SpeakReplies.entries.size),
                    ) { Text(when (v) { SpeakReplies.VOICE -> "Voice only"; SpeakReplies.ALWAYS -> "Always"; SpeakReplies.NEVER -> "Never" }) }
                }
            }
            Text("“Voice only” speaks when you asked by voice or with “hey robot”, not when you typed.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Voice: the phone's text-to-speech (GrapheneOS Speech Services).", style = MaterialTheme.typography.bodyMedium)
            var rate by remember(s.speechRate) { mutableFloatStateOf(s.speechRate) }
            Text("Speed: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(value = rate, onValueChange = { rate = it }, onValueChangeFinished = { update(s.copy(speechRate = RobotSettings.clampRate(rate))) },
                valueRange = 0.5f..2.0f, steps = 14)
            OutlinedButton(onClick = a.speakTest, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test the voice") }
        }
        SectionCard("Actions") {
            Text("What Robot may do when you ask. On the lock screen only timers and alarms work; everything else asks you to unlock.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (t in ToolName.entries) ApprovalRow(t, s.approval(t)) { lvl -> update(s.copy(approvals = s.approvals + (t to lvl))) }
        }
        SectionCard("Model memory") {
            Text("Keep Gemma loaded after the panel closes (about 2 GB of memory; it loads again in about 2 seconds).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in RobotSettings.KEEP_CHOICES) {
                    FilterChip(selected = s.keepModelMinutes == m, onClick = { update(s.copy(keepModelMinutes = m)) },
                        label = { Text(if (m == RobotSettings.KEEP_ALWAYS) "Always" else "$m min") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        SectionCard("History") {
            SwitchRow("Save conversations", "Kept in Robot's private storage on this phone. Photos and screen contents are never saved.", s.saveHistory) { update(s.copy(saveHistory = it)) }
            TextButton(onClick = { confirmHistory = true }) { Text("Delete all conversations") }
        }
        SectionCard("Assistant") {
            NavRow(RIcons.Robot, "Assistant setup", "Check that Robot is your digital assistant", a.openSetup)
        }
        SectionCard("About") {
            Text("Robot ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text("Free software under the GNU GPL version 3. Third-party parts: LiteRT-LM and the Tensor NPU runtime (Apache-2.0), " +
                "sherpa-onnx (Apache-2.0), ONNX Runtime (MIT), Silero VAD (MIT), Jetpack Compose and AndroidX (Apache-2.0), Gemma 4 (Apache-2.0).",
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
private fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalRow(t: ToolName, level: Approval, onChange: (Approval) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(toolIcon(t), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(t.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (t in ApprovalGate.ALLOWED_WHEN_LOCKED) Text("works locked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Approval.entries.forEachIndexed { i, v ->
                SegmentedButton(selected = level == v, onClick = { onChange(v) }, shape = SegmentedButtonDefaults.itemShape(i, Approval.entries.size)) { Text(v.title) }
            }
        }
    }
}

@Composable
fun SetupScreen(f: AssistantFacts?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    Scaffold(topBar = { BackBar("Assistant setup", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (f == null) {
                Text("Checking…")
                return@Column
            }
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
                StatusRow(
                    if (f.recognizerIsRobot) Mark.OK else Mark.WARN,
                    "Default speech recognizer",
                    if (f.recognizerIsRobot) "Set to Robot, so Android keeps Robot as the assistant after a restart."
                    else "Not set to Robot (${if (f.recognizer == "unset") "none set" else "another app"}; ${f.recognizersFound} installed). After a restart Android may turn Robot off: see below.",
                )
                StatusRow(if (f.micGranted) Mark.OK else Mark.TODO, "Microphone", if (f.micGranted) "Allowed" else "Needed for “hey robot” and voice questions.")
                StatusRow(if (f.notificationsGranted) Mark.OK else Mark.TODO, "Notifications", if (f.notificationsGranted) "Allowed" else "Needed for the restart reminder.")
                StatusRow(
                    when (f.listening) { true -> Mark.OK; else -> Mark.TODO },
                    "Listening for “hey robot”",
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
            SectionCard("After a restart") {
                Text(
                    "Android 17 keeps a non-system assistant across a restart only when a default speech recognizer is set, and it never " +
                        "picks one from an app like Robot by itself. GrapheneOS has no screen to choose one. So after a restart, Android may " +
                        "quietly turn Robot off as your assistant.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Robot checks 3 minutes after every restart. If it was turned off, you get a notification: tap it, choose " +
                        "“Digital assistant app” and pick Robot again. A one-time fix with a computer (adb) that makes this permanent " +
                        "comes in a later version.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
