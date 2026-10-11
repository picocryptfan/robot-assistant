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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ConversationSettings
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.ui.common.RIcons

// MAIN PROCESS. Settings › Conversation (0.5.0, docs/conversation-mode.md 9.3): the switches for transcribing (each with
// the default the plan gives), speaker labels (phase 5.3: the speaker model's download, "Tell speakers apart", the most
// speakers, and your optional voiceprint), the consent reminder, and the ways to the transcripts and the Transcription
// check. The session itself runs in the voice process, which re-reads the settings when they change; the speaker model
// is downloaded here, like the voice models (the voice process never goes online).

@Composable
fun ConversationSettingsScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var confirmModel by remember { mutableStateOf(false) }
    var confirmVoice by remember { mutableStateOf(false) }
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
            SpeakersCard(s, snap, update, a, onDeleteModel = { confirmModel = true }, onDeleteVoice = { confirmVoice = true })
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
            Text("Coming later: translation and the interpreter, summaries.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }
    val b = VoiceBundle.SPEAKERS
    if (confirmModel) AlertDialog(
        onDismissRequest = { confirmModel = false },
        title = { Text("Delete the speaker model?") },
        text = { Text("Transcripts stop telling speakers apart (every line is “Speaker”) until you download it again (${VoiceModelLogic.size(b.bytes)}). " +
            "Names you gave in transcripts stay. Your voiceprint, if you made one, stays until you delete it below.") },
        confirmButton = { TextButton(onClick = { confirmModel = false; a.voiceDelete(b) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmModel = false }) { Text("Cancel") } },
    )
    if (confirmVoice) AlertDialog(
        onDismissRequest = { confirmVoice = false },
        title = { Text("Delete your voiceprint?") },
        text = { Text("Robot stops recognising your voice at once, also in a transcript being recorded; lines already labelled “You” stay as they are.") },
        confirmButton = { TextButton(onClick = { confirmVoice = false; a.deleteVoiceprint() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmVoice = false }) { Text("Cancel") } },
    )
}

/** Speaker labels: the model's download (pinned, checked), the switch, how many speakers, and your voice. */
@Composable
private fun SpeakersCard(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, onDeleteModel: () -> Unit, onDeleteVoice: () -> Unit) {
    val b = VoiceBundle.SPEAKERS
    val v = snap.voice(b)
    val ready = v?.status == ModelStatus.VERIFIED
    SectionCard("Speakers") {
        Text("Robot can label who says what in a transcript, by their voices: S1, S2… or the names you give (tap a line's speaker). " +
            "It all happens on this phone; the voices are forgotten when the transcript stops.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Tell speakers apart", when {
            s.convSpeakers == false -> "Off: every line is “Speaker”, and the speaker model isn't offered."
            ready -> "On: each line says who spoke."
            else -> "Needs the speaker model below (${VoiceModelLogic.size(b.bytes)}). Until then every line is “Speaker”."
        }, s.convSpeakers != false) { update(s.copy(convSpeakers = it)) }
        if (v != null) {
            StatusRow(when (v.status) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO },
                "Speaker model · ${VoiceModelLogic.size(b.bytes)}", VoiceModelLogic.headline(v.status))
            Text(b.about + " Pinned file, checked (SHA-256) before Robot uses it.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.progress?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
                if (v.percent >= 0) LinearProgressIndicator(progress = { v.percent / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            v.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            when (v.status) {
                ModelStatus.MISSING, ModelStatus.BAD -> Button(onClick = { a.voiceDownload(b) }, enabled = s.convSpeakers != false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(RIcons.Download, null); Spacer(Modifier.width(8.dp)); Text("Download (${VoiceModelLogic.size(b.bytes)})")
                }
                ModelStatus.DOWNLOADING -> OutlinedButton(onClick = { a.voiceCancel(b) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Cancel download") }
                ModelStatus.VERIFIED -> OutlinedButton(onClick = onDeleteModel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete the speaker model")
                }
                else -> {}
            }
        }
        Text("Most speakers in a transcript", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in ConversationSettings.SPEAKER_CHOICES) {
                FilterChip(selected = s.convMaxSpeakers == n, onClick = { update(s.copy(convMaxSpeakers = n)) }, label = { Text("$n") },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text("Past that, a new voice goes to the nearest speaker. The interpreter always tells two people apart.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Your voice", style = MaterialTheme.typography.titleSmall)
        if (snap.voiceprint) {
            StatusRow(Mark.OK, "Your voiceprint is saved", "Your lines say “You”. Encrypted on this phone, used only for this, never sent anywhere.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = a.openVoiceprint, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text("Record again") }
                OutlinedButton(onClick = onDeleteVoice, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete")
                }
            }
        } else {
            Text("Optional: read three short sentences so your lines say “You” instead of S1 or S2. Robot keeps a voiceprint (numbers made " +
                "from your voice, not the recording), encrypted on this phone. A voiceprint is biometric data; delete it here any time." +
                if (ready) "" else " Needs the speaker model first.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = a.openVoiceprint, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set up your voice") }
        }
    }
}
