package app.heyrobot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import app.heyrobot.core.SafetyNotice
import app.heyrobot.core.ToolName
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.toolIcon

// MAIN PROCESS. Settings › Safety & privacy (0.4.0): every rule about when Robot asks first, what works on the lock
// screen, what helpers (Claude, or any other since 0.6.0) may get without a tap, how direct the local model is, and what History keeps. Nothing is
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
                Text("When something Robot read (a screen, a photo, a note, an event, a contact, the weather, a helper's answer) makes an action ask first.",
                    style = MaterialTheme.typography.bodyMedium, color = muted)
                for (m in SafetyCards.entries) {
                    ChoiceRow(m.title + if (m == SafetyCards.UNASKED) " (default)" else "", m.detail, s.safetyCards == m) { update(s.copy(safetyCards = m)) }
                }
                SwitchRow("Card when a helper asks Robot to do something", "A helper's input may come from web pages. Off: a helper's actions follow the rules above.",
                    s.claudeActionCards) { update(s.copy(claudeActionCards = it)) }
                SwitchRow("Card when who-to isn't certain", "Several people or numbers fit, a name only nearly matches, the model wrote a number " +
                    "or address itself, or an app name matched loosely. Off: Robot takes the best match.", s.whoCards) { update(s.copy(whoCards = it)) }
                SwitchRow("Card for names learned from Cheogram", "Whoever sent the message chose that name.", s.learnedCards) { update(s.copy(learnedCards = it)) }
                SwitchRow("Answer cards by voice", "While a card waits, say “yes”, “do it”, “send it” or “oui”, or “no”, “cancel” or " +
                    "“annule” (or type it). Robot listens when replies are spoken or you asked by voice; the mic answers the card too. " +
                    "A card for a name learned from Cheogram, or when Robot isn't sure who or which, still needs a tap (a no works). " +
                    "Off: a tap, or a typed yes or no.",
                    s.voiceCards) { update(s.copy(voiceCards = it)) }
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
                SwitchRow("Send personal content to helpers automatically", "For every helper. When Robot checks with a helper by itself, it may " +
                    "include the conversation's screen text, photos and what it read (your calendar, notes, contacts, location), and what Robot " +
                    "reads for a helper goes without “Share with …”. Off: only your question goes, and anything more needs a tap. " +
                    "“Go get Claude” and “ask Claude about my screen” always ask first.", s.personalToHelpers) { update(s.copy(personalToHelpers = it)) }
                SwitchRow("Voice yes can send personal content", "For every helper. Off: a spoken or typed “yes” never sends your screen, a " +
                    "photo or what Robot read to a helper; only a tap does.", s.voiceYesPersonal) { update(s.copy(voiceYesPersonal = it)) }
                Text("A helper you trust with personal content (on its page in Settings › Helpers) gets what Robot read without asking, " +
                    "but your screen and photos still need a tap. These two switches go further, for every helper.",
                    style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            SectionCard("Answers") {
                SwitchRow("Direct answers", "Robot helps with whatever you ask without refusing or adding warnings; it still says when something isn't possible.",
                    s.directAnswers) { update(s.copy(directAnswers = it)) }
            }
            SectionCard("History") {
                SwitchRow("Keep screenshots and photos in history", "Off: Robot saves only that a question had a screen or a photo. On: a " +
                    "small thumbnail and the screen's text are saved with the conversation, in Robot's private storage on this phone, for you to " +
                    "see in History (a screen conversation is still saved only after Keep). They are never sent to a helper or read to the model again.",
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

/**
 * The one-time line after updating to 0.4.0 (core RobotSettings.safetyNotice, SafetyNotice.TEXT), on Home and Settings:
 * tapping it opens Safety & privacy, the close button dismisses it; either way it never shows again. Nothing is logged.
 */
@Composable
fun SafetyNoticeLine(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClickLabel = "Open Safety & privacy", onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RIcons.Lock, null)
            Spacer(Modifier.width(12.dp))
            Text(SafetyNotice.TEXT, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(RIcons.Close, "Dismiss") }
        }
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
