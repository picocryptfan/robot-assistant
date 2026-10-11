package app.heyrobot.assistant.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heyrobot.core.InterpreterVoice
import app.heyrobot.core.TranscriptLine
import app.heyrobot.ui.common.RIcons

// VOICE PROCESS. The Interpreter (docs/conversation-mode.md 5.2, phase 5.4): face to face, the phone flat between two
// people. The top half is turned to the other person, in their language; the bottom half is yours. Each half shows,
// large, what the other side last said translated into its language (with "fast" when the fast engine made it), and,
// small, what Robot heard its own side say, so each can check. The direction follows each line's language; a half's
// microphone button forces its language (hold it while you speak, or tap it on and off). A tap anywhere else stops the
// translation being read aloud. Robot never translates itself: it doesn't listen while it speaks.

@Composable
fun InterpreterScreen(ui: ConversationSession.Ui, modifier: Modifier = Modifier) {
    val theirs = ui.theirs
    val mine = ui.mine
    val lastTheirs = ui.lines.lastOrNull { it.lang == theirs }
    val lastMine = ui.lines.lastOrNull { it.lang == mine }
    Column(modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { ConversationSession.stopSpeaking() }) }) {
        // Turned to face the other person across the table.
        Half(ui, lang = theirs, other = lastMine, own = lastTheirs, ownLabel = "Robot heard you",
            modifier = Modifier.weight(1f).fillMaxWidth().graphicsLayer { rotationZ = 180f })
        HorizontalDivider()
        Half(ui, lang = mine, other = lastTheirs, own = lastMine, ownLabel = "Robot heard you", modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** One side: [other]'s last line translated into [lang], large; this side's own last line ([own]), small; its push-to-talk. */
@Composable
private fun Half(ui: ConversationSession.Ui, lang: String, other: TranscriptLine?, own: TranscriptLine?, ownLabel: String, modifier: Modifier) {
    val tr = other?.translations?.get(lang)
    val working = other != null && other.i in ui.translating && tr == null
    val failed = other != null && ui.untranslated == other.i && tr == null
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(InterpreterVoice.name(lang), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (tr?.fast == true) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("fast", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text(
                when {
                    tr != null -> tr.text
                    working -> "…"
                    failed -> if (lang == "fr") "Robot n'a pas pu traduire cela." else "Robot couldn't translate that."
                    other == null -> if (lang == "fr") "Parlez : Robot traduit." else "Speak: Robot translates."
                    else -> ""
                },
                fontSize = 30.sp, lineHeight = 36.sp, style = MaterialTheme.typography.headlineMedium,
                color = if (tr != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (own != null) {
                    Text(if (lang == "fr") "Robot vous a entendu" else ownLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(own.text, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 3)
                }
            }
            Spacer(Modifier.width(12.dp))
            PushToTalk(lang, held = ui.ptt == lang)
        }
    }
}

/**
 * Hold it while you speak (released: back to telling languages apart), or tap it on and off. While it is on, what is
 * said is taken as [lang] without language ID.
 */
@Composable
private fun PushToTalk(lang: String, held: Boolean) {
    val on by rememberUpdatedState(held)
    val label = if (lang == "fr") "Parler en français" else "Speak English"
    Box(
        Modifier.size(64.dp).clip(CircleShape)
            .background(if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = label + if (held) ", on" else "" }
            .pointerInput(lang) {
                detectTapGestures(onPress = {
                    val wasOn = on
                    if (!wasOn) ConversationSession.pushToTalk(lang)
                    val t0 = System.currentTimeMillis()
                    tryAwaitRelease()
                    // A hold ends with the release; a tap leaves it on, and a tap on it turns it off.
                    if (wasOn || System.currentTimeMillis() - t0 >= HOLD_MS) ConversationSession.pushToTalk(null)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(RIcons.Mic, null, tint = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private const val HOLD_MS = 400L
