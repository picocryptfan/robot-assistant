// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Markdown
import app.heyrobot.core.ToolName

// Main and voice processes: what the assistant panel and History show of a conversation besides its text (ui/common
// Transcript.kt): what an action's card and the offer to ask a helper show, and Markdown. The bubbles and their cards are
// drawn in ui/classic/ClassicPanel.kt.

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
 * "Want me to check with Jarvis?" under a reply (any helper, by its [name]): what Send would send ([sends], with the start of the screen's
 * text in [preview]), or, without an API key ([api] false), its [app] opened with [text]. [asWell]: under Robot's
 * own answer, after the local model first handed the question over.
 */
data class OfferView(val api: Boolean, val text: String, val sends: List<String>, val preview: String? = null, val asWell: Boolean = false,
    val name: String = "Claude", val app: String = "Claude")

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
    ToolName.ASK_HELPER -> RIcons.Cloud
    ToolName.WEATHER -> RIcons.Sunny
    ToolName.PHONE_STATUS -> RIcons.Phone
    ToolName.FREE_MEMORY -> RIcons.Chip
    ToolName.FREE_STORAGE -> RIcons.Folder
    ToolName.REMEMBER, ToolName.FORGET -> RIcons.BookmarkAdded
    ToolName.WEB_SEARCH, ToolName.BROWSER_SEARCH -> RIcons.Search
    ToolName.TERMUX_SCRIPT, ToolName.TERMUX_COMMAND -> RIcons.Terminal
    null -> RIcons.Warning
}

/** The line under a card's summary: what it waits for, or how it went. */
fun cardStatusLine(a: ActionView): String = when (a.status) {
    ActionStatus.PENDING -> a.detail ?: if (a.choices.isNotEmpty()) "Which one?" else "Do you want Robot to do this?"
    ActionStatus.DONE, ActionStatus.DRAFT_OPENED -> a.detail ?: a.status.label
    else -> listOfNotNull(a.status.label, a.detail).joinToString(". ")
}

/** A thumbnail kept with a question ([app.heyrobot.core.ChatTurn.picture], Base64 JPEG), or null if it can't be read. */
fun keptPicture(b64: String?): Bitmap? = try {
    b64?.let { java.util.Base64.getDecoder().decode(it) }?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
} catch (_: Exception) {
    null
}

/** The lines under a helper's reply ([by]: its name): the search and its domains, then the cost. */
fun replyFooter(sources: List<String>, cost: String?, by: String? = null): List<String> = listOfNotNull(
    if (sources.isNotEmpty()) "${by ?: "The helper"} searched the web · " + sources.joinToString(", ") else null,
    cost,
)

/** Light Markdown (core's parser); with a caret while the reply streams when [streaming]. */
@Composable
fun MarkdownText(text: String, streaming: Boolean = false) {
    // A long reply is parsed again only from its last paragraph as it streams.
    val parser = remember { Markdown.Incremental() }
    val blocks = remember(text) { parser.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
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
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg).padding(10.dp))
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
