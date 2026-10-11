package app.heyrobot.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heyrobot.core.Attachment
import app.heyrobot.ui.classic.ClassicBubbleTurn
import app.heyrobot.ui.theme.LocalLook
import app.heyrobot.ui.theme.LookStyle
import app.heyrobot.ui.theme.RobotTokens

// Main and voice processes. One turn of a conversation, as the panel and History › a conversation show it. The
// standard look is a transcript, like a system app's: your words as a quieter line, Robot's answer as plain text you
// can select and copy, a helper's answer under its name, cards as standard cards; no bubbles, no typing dots, no caret.
// The Classic look draws the same turn as 0.6.0's bubbles (ui/classic).

/** One turn, as both screens hand it over. [helper]: a helper wrote it ([by] its name, [home] a server on your network). */
data class TurnUi(
    val id: String,
    /** Your words (else a reply). */
    val user: Boolean,
    val text: String,
    val attachment: Attachment = Attachment.NONE,
    /** A kept photo or screenshot. */
    val thumb: Bitmap? = null,
    /** The screen's text kept with the question ("Keep screenshots and photos in history"). */
    val screenText: String? = null,
    val actions: List<ActionView> = emptyList(),
    val streaming: Boolean = false,
    val stopped: Boolean = false,
    val error: Boolean = false,
    val helper: Boolean = false,
    /** Read off the Claude app's screen. */
    val readBack: Boolean = false,
    val by: String? = null,
    val home: Boolean = false,
    /** "Claude searched the web · nasa.gov", the cost. */
    val footer: List<String> = emptyList(),
    val offer: OfferView? = null,
)

/** What a turn's buttons do. History passes [None]: its cards have no buttons. */
class TurnActions(
    /** A card's "Do it" (true) or "Cancel" (false), by its id. */
    val confirm: ((String, Boolean) -> Unit)? = null,
    val card: CardActions? = null,
    /** The offer's Send and Not now, by the reply's id. */
    val acceptOffer: (String) -> Unit = {},
    val dismissOffer: (String) -> Unit = {},
) {
    companion object {
        val None = TurnActions()
    }
}

/**
 * One turn in the screen's look. [startsExchange]: a question after an earlier answer, which the transcript sets apart
 * (24 dp between exchanges, 8 dp between a question and its answer).
 */
@Composable
fun TurnView(t: TurnUi, actions: TurnActions, modifier: Modifier = Modifier, startsExchange: Boolean = false) = when (LocalLook.current) {
    LookStyle.STOCK -> TranscriptTurn(t, actions, modifier, startsExchange)
    LookStyle.CLASSIC -> ClassicBubbleTurn(t, actions, modifier)
}

/** The space between turns in a list of them, in the screen's look. */
@Composable
fun transcriptGap(): Dp = if (LocalLook.current == LookStyle.CLASSIC) 10.dp else 8.dp

@Composable
fun TranscriptTurn(t: TurnUi, actions: TurnActions, modifier: Modifier = Modifier, startsExchange: Boolean = false) {
    if (t.user) UserLine(t, modifier.padding(top = if (startsExchange) 16.dp else 0.dp))
    else ReplyText(t, actions, modifier)
}

/** Your words: a quieter line, with the photo or screen kept with them. One TalkBack heading per exchange, "You: …". */
@Composable
private fun UserLine(t: TurnUi, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        val c = MaterialTheme.colorScheme
        val what = if (t.attachment == Attachment.SCREEN) "Screen" else "Photo"
        if (t.thumb != null) {
            Image(t.thumb.asImageBitmap(), contentDescription = what, contentScale = ContentScale.Crop,
                modifier = Modifier.padding(bottom = 8.dp).size(56.dp).clip(MaterialTheme.shapes.small))
        } else if (t.attachment != Attachment.NONE) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (t.attachment == Attachment.SCREEN) RIcons.ScreenShare else RIcons.Image, null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(what, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            }
        }
        if (!t.screenText.isNullOrBlank()) {
            var open by remember { mutableStateOf(false) }
            Surface(
                color = c.surfaceContainer, contentColor = c.onSurfaceVariant, shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth().heightIn(min = RobotTokens.minTouch)
                    .clickable(onClickLabel = if (open) "Show less" else "Show all") { open = !open },
            ) {
                Text(t.screenText, style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (t.text.isNotBlank()) {
            Text(t.text, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "You: ${t.text}"; heading() })
        }
    }
}

/**
 * A reply: a helper's name and symbol above it when a helper wrote it, its cards, its text (selectable, so a long
 * press copies), "Stopped", the footer lines and the offer to ask a helper. While it is written the text just grows;
 * the panel's header says what is happening.
 */
@Composable
private fun ReplyText(t: TurnUi, actions: TurnActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        if (t.helper && !t.error) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (t.home) RIcons.Home else RIcons.Cloud, null, Modifier.size(16.dp), tint = c.tertiary)
                Spacer(Modifier.width(6.dp))
                Text(if (t.readBack) "Claude, from the Claude app" else t.by ?: "Claude", style = MaterialTheme.typography.labelLarge, color = c.tertiary)
            }
        }
        for (a in t.actions) ActionCard(a, actions.confirm, actions.card, Modifier.padding(bottom = 8.dp))
        if (t.text.isNotBlank()) {
            if (t.error) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(RIcons.Error, "Error", Modifier.padding(top = 2.dp).size(20.dp), tint = c.error)
                    Spacer(Modifier.width(8.dp))
                    Text(t.text, style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
                }
            } else {
                SelectionContainer { MarkdownText(t.text) }
            }
        }
        if (t.stopped) {
            Text("Stopped", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        for (line in t.footer) {
            Text(line, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        t.offer?.let { OfferCard(it, onAccept = { actions.acceptOffer(t.id) }, onDismiss = { actions.dismissOffer(t.id) }, modifier = Modifier.padding(top = 8.dp)) }
    }
}
