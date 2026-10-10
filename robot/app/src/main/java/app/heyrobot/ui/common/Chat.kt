package app.heyrobot.ui.common

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.Markdown
import app.heyrobot.core.ToolName

// Main and voice processes: the chat bubbles shared by the assistant panel and the history screen.

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
)

/** What a card can ask the panel for besides "Do it" and "Cancel". */
class CardActions(
    val choose: (String, Int) -> Unit = { _, _ -> },
    val undo: (String) -> Unit = {},
    /** Opens the main app's permission screen. */
    val allow: () -> Unit = {},
    /** Opens the app a card names ([ActionView.openApp]), by the card's id. */
    val openApp: (String) -> Unit = {},
)

fun toolIcon(t: ToolName?): ImageVector = when (t) {
    ToolName.SET_TIMER -> RIcons.Timer
    ToolName.SET_ALARM -> RIcons.Alarm
    ToolName.OPEN_APP -> RIcons.Apps
    ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE -> RIcons.Place
    ToolName.FLASHLIGHT -> RIcons.Flash
    ToolName.SEND_TEXT -> RIcons.Message
    ToolName.DRAFT_EMAIL -> RIcons.Email
    ToolName.LOOKUP_CONTACT -> RIcons.Person
    ToolName.ADD_NOTE, ToolName.FIND_NOTES -> RIcons.Note
    ToolName.ADD_EVENT, ToolName.READ_CALENDAR -> RIcons.Event
    ToolName.ASK_CLAUDE -> RIcons.Cloud
    null -> RIcons.Warning
}

@Composable
fun UserBubble(text: String, attachment: Attachment, thumb: Bitmap?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(), contentDescription = if (attachment == Attachment.SCREEN) "Screen" else "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(bottom = 4.dp).size(132.dp).clip(RoundedCornerShape(18.dp)),
            )
        } else if (attachment != Attachment.NONE) {
            AttachmentTag(attachment)
        }
        if (text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
    }
}

@Composable
private fun AttachmentTag(a: Attachment) {
    Row(
        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (a == Attachment.SCREEN) RIcons.Phone else RIcons.Image, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(6.dp))
        Text(if (a == Attachment.SCREEN) "Screen" else "Photo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * A reply. [claude]: Claude wrote it (Ask Claude): a small "Claude" label ("Claude, from the app" when [readBack]) and the
 * dynamic colour's tertiary tone, so a Claude turn is never mistaken for Robot's. [footer]: "Claude searched the web ·
 * nasa.gov" and the cost line, under the bubble.
 */
@Composable
fun AssistantBubble(
    text: String,
    streaming: Boolean,
    stopped: Boolean,
    error: Boolean,
    actions: List<ActionView>,
    modifier: Modifier = Modifier,
    onConfirm: ((String, Boolean) -> Unit)? = null,
    cardActions: CardActions? = null,
    claude: Boolean = false,
    readBack: Boolean = false,
    footer: List<String> = emptyList(),
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (claude && !error) {
            Row(Modifier.padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(RIcons.Cloud, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(5.dp))
                Text(if (readBack) "Claude, from the Claude app" else "Claude", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        for (a in actions) {
            ActionCard(a, onConfirm, cardActions)
            Spacer(Modifier.size(6.dp))
        }
        if (text.isNotBlank() || (streaming && actions.isEmpty())) {
            Surface(
                color = when {
                    error -> MaterialTheme.colorScheme.errorContainer
                    claude -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = when {
                    error -> MaterialTheme.colorScheme.onErrorContainer
                    claude -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                shape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp),
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    if (text.isBlank()) TypingDots() else MarkdownText(text, streaming)
                }
            }
        }
        if (stopped) {
            Text("Stopped", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
        for (line in footer) {
            Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
    }
}

/** The lines under a Claude reply: the search and its domains, then the cost. */
fun claudeFooter(sources: List<String>, cost: String?): List<String> = listOfNotNull(
    if (sources.isNotEmpty()) "Claude searched the web · " + sources.joinToString(", ") else null,
    cost,
)

/**
 * One action: what it does (light Markdown: the person or title in bold), its state, and right under it the buttons
 * while it waits: "Do it" (or its own words, "Open in Cheogram for Mom") or the people and numbers to pick from. The
 * full text it carries comes last, at most about 8 lines tall and scrolling inside the card, so a long message never
 * pushes who it goes to and the buttons apart. The panel passes [onConfirm] and [more]; the history shows no buttons.
 */
@Composable
fun ActionCard(a: ActionView, onConfirm: ((String, Boolean) -> Unit)?, more: CardActions? = null) {
    val bad = a.status in setOf(ActionStatus.FAILED, ActionStatus.INVALID, ActionStatus.NEEDS_UNLOCK, ActionStatus.DISABLED, ActionStatus.SKIPPED,
        ActionStatus.NEEDS_PERMISSION, ActionStatus.NOT_FOUND, ActionStatus.NO_APP)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.icon != null) {
                    Image(a.icon.asImageBitmap(), null, Modifier.size(40.dp))
                } else {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(toolIcon(a.tool), null, Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(spans(Markdown.inline(a.summary), Color.Unspecified, caret = false), style = MaterialTheme.typography.titleSmall)
                    val line = when (a.status) {
                        ActionStatus.PENDING -> a.detail ?: if (a.choices.isNotEmpty()) "Which one?" else "Do you want Robot to do this?"
                        ActionStatus.DONE, ActionStatus.DRAFT_OPENED -> a.detail ?: a.status.label
                        else -> listOfNotNull(a.status.label, a.detail).joinToString(". ")
                    }
                    Text(line, style = MaterialTheme.typography.bodyMedium,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                }
                if (a.status.succeeded) Icon(RIcons.Check, a.status.label, Modifier.size(22.dp))
                if (a.status == ActionStatus.NEEDS_UNLOCK) Icon(RIcons.Lock, "Locked", Modifier.size(22.dp))
            }
            a.picture?.let {
                Image(it.asImageBitmap(), "The picture that would be sent", Modifier.padding(top = 10.dp).heightIn(max = 160.dp).clip(RoundedCornerShape(12.dp)))
            }
            if (a.status == ActionStatus.PENDING && onConfirm != null) {
                if (a.choices.isNotEmpty() && more != null) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.choices.forEachIndexed { i, label ->
                            FilledTonalButton(onClick = { more.choose(a.id, i) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
                        }
                        if (a.more > 0) {
                            Text("…and ${a.more} more. Say the full name to pick one of those.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                        }
                        OutlinedButton(onClick = { onConfirm(a.id, false) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Cancel") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { onConfirm(a.id, false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(a.cancel ?: "Cancel") }
                        Button(onClick = { onConfirm(a.id, true) }, modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp)) { Text(a.confirm ?: "Do it") }
                    }
                }
            }
            if (more != null && (a.undo || a.status == ActionStatus.NEEDS_PERMISSION || a.openApp != null)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    if (a.undo) TextButton(onClick = { more.undo(a.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
                    a.openApp?.let { label -> FilledTonalButton(onClick = { more.openApp(a.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) } }
                    if (a.status == ActionStatus.NEEDS_PERMISSION) FilledTonalButton(onClick = more.allow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") }
                }
            }
            if (!a.body.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(a.body, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = BODY_MAX).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** About 8 lines of body text on a card; more scrolls inside it. */
private val BODY_MAX = 176.dp

@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        for (i in 0 until 3) {
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}

/** Light Markdown (core's parser) with a caret while the reply streams. */
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
