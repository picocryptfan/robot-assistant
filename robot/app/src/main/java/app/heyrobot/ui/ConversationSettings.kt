package app.heyrobot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.RobotSettings
import app.heyrobot.ui.common.RIcons

// MAIN PROCESS. Settings › Conversation (0.5.0, docs/conversation-mode.md 9.3): the switches for transcribing (each with
// the default the plan gives), the consent reminder, and the ways to the transcripts and the Transcription check. The
// session itself runs in the voice process, which re-reads the settings when they change.

@Composable
fun ConversationSettingsScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
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
            Text("Coming later: speaker labels, translation and the interpreter, summaries.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }
}
