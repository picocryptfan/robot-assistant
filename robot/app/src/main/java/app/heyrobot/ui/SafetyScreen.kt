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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heyrobot.core.LockScreen
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.ToolName
import app.heyrobot.ui.common.toolIcon

// MAIN PROCESS. Settings › Safety & privacy (0.4.0): every rule about when Robot asks first, what works on the lock
// screen, what helpers (Claude) may get without a tap, how direct the local model is, and what History keeps. Nothing is
// fixed in code: each is a switch here with a sensible default (core Safety.kt, ApprovalGate).

@Composable
fun SafetyScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmRemove by remember { mutableStateOf(false) }
    Scaffold(topBar = { BackBar("Safety & privacy", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Choose how careful Robot is. Each action's own level (Just do it, Ask first, Off) is in Settings › Actions; these rules " +
                "decide when “Just do it” still shows a card first.", style = MaterialTheme.typography.bodyMedium, color = muted)
            SectionCard("Safety cards") {
                Text("When something Robot read (a screen, a photo, a note, an event, a contact, the weather, Claude's answer) makes an action ask first.",
                    style = MaterialTheme.typography.bodyMedium, color = muted)
                for (m in SafetyCards.entries) {
                    ChoiceRow(m.title + if (m == SafetyCards.UNASKED) " (default)" else "", m.detail, s.safetyCards == m) { update(s.copy(safetyCards = m)) }
                }
                SwitchRow("Card when Claude asks Robot to do something", "Claude's input may come from web pages. Off: Claude's actions follow the rules above.",
                    s.claudeActionCards) { update(s.copy(claudeActionCards = it)) }
                SwitchRow("Card when who-to isn't certain", "Several people or numbers fit, a name only nearly matches, the model wrote a number " +
                    "or address itself, or an app name matched loosely. Off: Robot takes the best match.", s.whoCards) { update(s.copy(whoCards = it)) }
                SwitchRow("Card for names learned from Cheogram", "Whoever sent the message chose that name.", s.learnedCards) { update(s.copy(learnedCards = it)) }
                SwitchRow("Card before deleting files", "A large photo or video (Android still asks too), or Robot's cache. Off: Robot goes " +
                    "straight to Android's confirmation for the largest one.", s.deleteCards) { update(s.copy(deleteCards = it)) }
            }
            SectionCard("Works on the lock screen") {
                Text("A locked phone can't tell who is asking. Questions always work; these actions too. Everything else asks you to unlock.",
                    style = MaterialTheme.typography.bodyMedium, color = muted)
                for (t in LockScreen.CHOICES) LockRow(t, t in s.lockedTools) { on ->
                    update(s.copy(lockedTools = if (on) s.lockedTools + t else s.lockedTools - t))
                }
                if (s.lockedTools != LockScreen.DEFAULT) TextButton(onClick = { update(s.copy(lockedTools = LockScreen.DEFAULT)) }) { Text("Back to timers and alarms") }
            }
            SectionCard("Helpers") {
                SwitchRow("Send personal content to helpers automatically", "When Robot checks with Claude by itself, it may include the " +
                    "conversation's screen text, photos and what it read (your calendar, notes, contacts, location), and what Robot reads for " +
                    "Claude goes without “Share with Claude”. Off: only your question goes, and anything more needs a tap. " +
                    "“Go get Claude” and “ask Claude about my screen” always ask first.", s.personalToHelpers) { update(s.copy(personalToHelpers = it)) }
                SwitchRow("Voice yes can send personal content", "Off: a spoken or typed “yes” never sends your screen, a photo or what " +
                    "Robot read to Claude; only a tap does.", s.voiceYesPersonal) { update(s.copy(voiceYesPersonal = it)) }
            }
            SectionCard("Answers") {
                SwitchRow("Direct answers", "Robot helps with whatever you ask without refusing or adding warnings; it still says when something isn't possible.",
                    s.directAnswers) { update(s.copy(directAnswers = it)) }
            }
            SectionCard("History") {
                SwitchRow("Keep screenshots and photos in history", "Off: Robot saves only that a question had a screen or a photo. On: a " +
                    "small thumbnail and the screen's text are saved with the conversation, in Robot's private storage on this phone, for you to " +
                    "see in History (a screen conversation is still saved only after Keep). They are never sent to Claude or read to the model again.",
                    s.keepMedia, enabled = s.saveHistory) { update(s.copy(keepMedia = it)) }
                if (!s.saveHistory) Text("Saving conversations is off (Settings › History).", style = MaterialTheme.typography.bodyMedium, color = muted)
                OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove saved screenshots and photos") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove saved screenshots and photos?") },
            text = { Text("The conversations stay; their thumbnails and screen text are removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; a.removeSavedMedia() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LockRow(t: ToolName, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(toolIcon(t), null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge)
            if (t in LockScreen.DEFAULT) Text("On by default", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
