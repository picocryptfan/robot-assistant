package app.heyrobot.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Markdown
import app.heyrobot.core.ToolName
import app.heyrobot.ui.theme.LocalLook
import app.heyrobot.ui.theme.LookStyle
import app.heyrobot.ui.theme.RobotTokens

// Main and voice processes: what the assistant panel and History show of a conversation besides its text (ui/common
// Transcript.kt): an action's card, the offer to ask a helper, Markdown. The cards are standard Material cards
// (ui/common Cards.kt): a first row like a list item, the text it carries in an inset, choices as 56 dp rows like a
// system chooser, the buttons at the end. The Classic look's bubbles and cards are in ui/classic/ClassicPanel.kt.

/**
 * [icon]: the launcher icon of the app an open_app card will open (shown instead of the tool's symbol). [body]: the
 * full message, email or note, or what a read tool found. [choices]: the people or numbers to tap while it waits.
 * [confirm]: the button's words ("Open in Cheogram"). [undo]: the card offers Undo.
 */
data class ActionView(
    val id: String,
    val tool: ToolName?,
    val summary: String,
    val status: ActionStatus,
    val detail: String?,
    val icon: Bitmap? = null,
    val body: String? = null,
    val choices: List<String> = emptyList(),
    val confirm: String? = null,
    val undo: Boolean = false,
    /** More people or numbers fitted than [choices] lists ("…and 3 more"). */
    val more: Int = 0,
    /** The Cancel button's own words ("Start fresh"). */
    val cancel: String? = null,
    /** The words of a button that opens another app (only its launcher screen): "Open Cheogram". */
    val openApp: String? = null,
    /** The picture the card would send (a photo, or a screenshot), shown so you can see what goes. */
    val picture: Bitmap? = null,
    /** A tick box over [choices] that remembers the one tapped ("Always use this for “mom”"). */
    val remember: String? = null,
    /** While it waits, an "Allow contacts" button ([CardActions.allow]): the person asked for may be in the contacts. */
    val allowContacts: Boolean = false,
    /** 0.9: a Termux command or script, drawn as its own card (TermuxCard). */
    val termux: TermuxCardView? = null,
)

/** What a card can ask the panel for besides "Do it" and "Cancel". */
class CardActions(
    /** The card's id, the choice's index, and whether "Always use this for …" was ticked. */
    val choose: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    val undo: (String) -> Unit = {},
    /** Opens the main app's permission screen. */
    val allow: () -> Unit = {},
    /** Opens the app a card names ([ActionView.openApp]), by the card's id. */
    val openApp: (String) -> Unit = {},
    /** 0.9: a Termux card's Edit, time limit, Open in Termux and Stop, by the card's id. */
    val termux: (String, TermuxCardChange) -> Unit = { _, _ -> },
)

/**
 * "Ask Claude?" under a reply (any helper, by its [name]): what Send would send ([sends], with the start of the screen's
 * text in [preview]), or, without an API key ([api] false), the Claude app opened with [text]. [asWell]: under Robot's
 * own answer, after the local model first handed the question over.
 */
data class OfferView(val api: Boolean, val text: String, val sends: List<String>, val preview: String? = null, val asWell: Boolean = false,
    val name: String = "Claude")

fun toolIcon(t: ToolName?): ImageVector = when (t) {
    ToolName.SET_TIMER -> RIcons.Timer
    ToolName.SET_ALARM -> RIcons.Alarm
    ToolName.OPEN_APP -> RIcons.Apps
    ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE -> RIcons.Place
    ToolName.FLASHLIGHT -> RIcons.Flash
    ToolName.SEND_TEXT -> RIcons.Message
    ToolName.DRAFT_EMAIL -> RIcons.Email
    ToolName.LOOKUP_CONTACT -> RIcons.Person
    ToolName.CALL -> RIcons.Call
    ToolName.ADD_NOTE, ToolName.FIND_NOTES -> RIcons.Note
    ToolName.ADD_EVENT, ToolName.READ_CALENDAR -> RIcons.Event
    ToolName.ASK_CLAUDE -> RIcons.Cloud
    ToolName.WEATHER -> RIcons.Sunny
    ToolName.PHONE_STATUS -> RIcons.Phone
    ToolName.FREE_MEMORY -> RIcons.Chip
    ToolName.FREE_STORAGE -> RIcons.Folder
    ToolName.REMEMBER, ToolName.FORGET -> RIcons.BookmarkAdded
    ToolName.WEB_SEARCH, ToolName.BROWSER_SEARCH -> RIcons.Search
    ToolName.TERMUX_SCRIPT, ToolName.TERMUX_COMMAND -> RIcons.Terminal
    null -> RIcons.Warning
}

/** The states a card shows as a problem: in the error colour, after an error symbol. */
private val BAD = setOf(ActionStatus.FAILED, ActionStatus.INVALID, ActionStatus.NEEDS_UNLOCK, ActionStatus.DISABLED, ActionStatus.SKIPPED,
    ActionStatus.NEEDS_PERMISSION, ActionStatus.NOT_FOUND, ActionStatus.NO_APP)

/** The line under a card's summary: what it waits for, or how it went. */
fun cardStatusLine(a: ActionView): String = when (a.status) {
    ActionStatus.PENDING -> a.detail ?: if (a.choices.isNotEmpty()) "Which one?" else "Do you want Robot to do this?"
    ActionStatus.DONE, ActionStatus.DRAFT_OPENED -> a.detail ?: a.status.label
    else -> listOfNotNull(a.status.label, a.detail).joinToString(". ")
}

/**
 * One action, as a standard card read top to bottom (TalkBack too): what it does (light Markdown: the person or title
 * in bold) with the tool's symbol or the app's icon, its state under it (a check with its label when done, a lock when
 * the phone must be unlocked), the picture it would send, the text it carries (at most about 8 lines, scrolling inside,
 * so the choices and buttons stay close), then while it waits the people or numbers as 56 dp rows (with "Always use
 * this for …" above them) or Cancel and "Do it" (or its own words, "Open in Cheogram for Mom"), and Undo, Open app,
 * Allow. The panel passes [onConfirm] and [more]; History shows no buttons.
 */
@Composable
fun ActionCard(a: ActionView, onConfirm: ((String, Boolean) -> Unit)?, more: CardActions? = null, modifier: Modifier = Modifier) {
    a.termux?.let { return TermuxCard(a, it, onConfirm, more?.termux, modifier) }
    val c = MaterialTheme.colorScheme
    PanelCard(modifier) {
        CardHeader(
            headline = spans(Markdown.inline(a.summary), Color.Unspecified, caret = false),
            supporting = cardStatusLine(a),
            error = a.status in BAD,
            leading = {
                if (a.icon != null) Image(a.icon.asImageBitmap(), null, Modifier.size(40.dp))
                else CardIcon(toolIcon(a.tool))
            },
            trailing = when {
                a.status.succeeded -> { { Icon(RIcons.Check, a.status.label, tint = c.primary) } }
                a.status == ActionStatus.NEEDS_UNLOCK -> { { Icon(RIcons.Lock, "Locked", tint = c.onSurfaceVariant) } }
                else -> null
            },
        )
        a.picture?.let {
            Image(it.asImageBitmap(), "The picture that would be sent", Modifier.padding(top = 12.dp).heightIn(max = 160.dp).clip(MaterialTheme.shapes.small))
        }
        if (!a.body.isNullOrBlank()) CardInset(a.body)
        if (a.status == ActionStatus.PENDING && onConfirm != null) {
            if (a.choices.isNotEmpty() && more != null) {
                var keep by remember(a.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    // Ticked before the choice is tapped, so it sits above them.
                    a.remember?.let { words ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = RobotTokens.minTouch).clip(MaterialTheme.shapes.small)
                                .toggleable(value = keep, role = Role.Checkbox) { keep = it }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = keep, onCheckedChange = null)
                            Spacer(Modifier.width(16.dp))
                            Text(words, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    a.choices.forEachIndexed { i, label -> CardChoiceRow(label, onClick = { more.choose(a.id, i, keep) }, icon = RIcons.Person) }
                    if (a.more > 0) {
                        Text("…and ${a.more} more. Say the full name to pick one of those.", style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                CardButtons { TextButton(onClick = { onConfirm(a.id, false) }) { Text(a.cancel ?: "Cancel") } }
            } else {
                CardButtons {
                    TextButton(onClick = { onConfirm(a.id, false) }) { Text(a.cancel ?: "Cancel") }
                    Button(onClick = { onConfirm(a.id, true) }, modifier = Modifier.weight(1f, fill = false)) { Text(a.confirm ?: "Do it") }
                }
            }
        }
        val allowContacts = a.allowContacts && a.status == ActionStatus.PENDING
        if (more != null && (a.undo || a.status == ActionStatus.NEEDS_PERMISSION || a.openApp != null || allowContacts)) {
            CardButtons {
                if (a.undo) TextButton(onClick = { more.undo(a.id) }) { Text("Undo") }
                if (allowContacts) TextButton(onClick = more.allow) { Text("Allow contacts") }
                a.openApp?.let { label -> FilledTonalButton(onClick = { more.openApp(a.id) }, modifier = Modifier.weight(1f, fill = false)) { Text(label) } }
                if (a.status == ActionStatus.NEEDS_PERMISSION) FilledTonalButton(onClick = more.allow) { Text("Allow") }
            }
        }
    }
}

/**
 * "Ask Claude?" (any helper, by its name; "Also ask Claude?" under Robot's own answer), what Send would send and the
 * start of the screen's text; or, without an API key, the Claude app opened with the question. Nothing goes until a tap
 * on Send (or a yes, when nothing personal goes).
 */
@Composable
fun OfferCard(o: OfferView, onAccept: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    PanelCard(modifier, style = cardStyle(CardKind.OFFER)) {
        CardHeader(
            headline = when {
                !o.api -> "Ask in the Claude app?"
                o.asWell -> "Also ask ${o.name}?"
                else -> "Ask ${o.name}?"
            },
            supporting = if (o.sends.isNotEmpty()) "Sends: " + o.sends.joinToString(", ").lowercase() + "." else null,
            leading = { CardIcon(RIcons.Cloud) },
        )
        val quote = if (!o.api) o.text else o.preview
        quote?.let {
            Text("“" + it.take(300).trim() + (if (it.length > 300) "…" else "") + "”", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 52.dp))
        }
        CardButtons {
            TextButton(onClick = onDismiss) { Text("Not now") }
            FilledTonalButton(onClick = onAccept) { Text(if (o.api) "Send" else "Open Claude") }
        }
    }
}

/** A thumbnail kept with a question ([app.heyrobot.core.ChatTurn.picture], Base64 JPEG), or null if it can't be read. */
fun keptPicture(b64: String?): Bitmap? = try {
    b64?.let { java.util.Base64.getDecoder().decode(it) }?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
} catch (_: Exception) {
    null
}

/** The lines under a helper's reply ([by]: its name): the search and its domains, then the cost. */
fun claudeFooter(sources: List<String>, cost: String?, by: String? = null): List<String> = listOfNotNull(
    if (sources.isNotEmpty()) "${by ?: "Claude"} searched the web · " + sources.joinToString(", ") else null,
    cost,
)

/** Light Markdown (core's parser); with a caret while the reply streams when [streaming] (the Classic look only). */
@Composable
fun MarkdownText(text: String, streaming: Boolean = false) {
    // A long reply is parsed again only from its last paragraph as it streams.
    val parser = remember { Markdown.Incremental() }
    val blocks = remember(text) { parser.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeShape = if (LocalLook.current == LookStyle.CLASSIC) RoundedCornerShape(10.dp) else MaterialTheme.shapes.small
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { i, b ->
            val caret = streaming && i == blocks.lastIndex
            val body = remember(b, codeBg, caret) { spans(b.spans, codeBg, caret) }
            when (b.kind) {
                Markdown.BlockKind.HEADING -> Text(body, style = MaterialTheme.typography.titleMedium)
                Markdown.BlockKind.BULLET, Markdown.BlockKind.NUMBERED -> Row {
                    Text(b.marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(24.dp))
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                }
                Markdown.BlockKind.CODE -> Text(body, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().clip(codeShape).background(codeBg).padding(10.dp))
                Markdown.BlockKind.PARAGRAPH -> Text(body, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (blocks.isEmpty() && streaming) Text("▍", style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun spans(spans: List<Markdown.Span>, codeBg: Color, caret: Boolean): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        withStyle(SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) codeBg else Color.Unspecified,
        )) { append(s.text) }
    }
    if (caret) append(" ▍")
}
