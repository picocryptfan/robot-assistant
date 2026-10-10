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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

data class ActionView(val id: String, val tool: ToolName?, val summary: String, val status: ActionStatus, val detail: String?)

fun toolIcon(t: ToolName?): ImageVector = when (t) {
    ToolName.SET_TIMER -> RIcons.Timer
    ToolName.SET_ALARM -> RIcons.Alarm
    ToolName.OPEN_APP -> RIcons.Apps
    ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE -> RIcons.Place
    ToolName.FLASHLIGHT -> RIcons.Flash
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

@Composable
fun AssistantBubble(
    text: String,
    streaming: Boolean,
    stopped: Boolean,
    error: Boolean,
    actions: List<ActionView>,
    modifier: Modifier = Modifier,
    onConfirm: ((String, Boolean) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        for (a in actions) {
            ActionCard(a, onConfirm)
            Spacer(Modifier.size(6.dp))
        }
        if (text.isNotBlank() || (streaming && actions.isEmpty())) {
            Surface(
                color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
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
    }
}

@Composable
fun ActionCard(a: ActionView, onConfirm: ((String, Boolean) -> Unit)?) {
    val bad = a.status in setOf(ActionStatus.FAILED, ActionStatus.INVALID, ActionStatus.NEEDS_UNLOCK, ActionStatus.DISABLED)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(toolIcon(a.tool), null, Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.summary, style = MaterialTheme.typography.titleSmall)
                    val line = when (a.status) {
                        ActionStatus.PENDING -> "Do you want Robot to do this?"
                        ActionStatus.DONE -> a.detail ?: "Done"
                        else -> listOfNotNull(a.status.label, a.detail).joinToString(". ")
                    }
                    Text(line, style = MaterialTheme.typography.bodyMedium,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                }
                if (a.status == ActionStatus.DONE) Icon(RIcons.Check, "Done", Modifier.size(22.dp))
                if (a.status == ActionStatus.NEEDS_UNLOCK) Icon(RIcons.Lock, "Locked", Modifier.size(22.dp))
            }
            if (a.status == ActionStatus.PENDING && onConfirm != null) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    OutlinedButton(onClick = { onConfirm(a.id, false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    Button(onClick = { onConfirm(a.id, true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Do it") }
                }
            }
        }
    }
}

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
    val blocks = remember(text) { Markdown.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { i, b ->
            val caret = streaming && i == blocks.lastIndex
            val body = spans(b.spans, codeBg, caret)
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

private fun spans(spans: List<Markdown.Span>, codeBg: Color, caret: Boolean): AnnotatedString = buildAnnotatedString {
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
