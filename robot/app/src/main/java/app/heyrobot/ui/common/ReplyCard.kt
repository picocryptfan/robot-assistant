package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.core.Markdown
import app.heyrobot.ui.theme.RobotTokens

// Main and voice processes. 0.7, the reply card (design §B5): "Reply to **Mom** · Cheogram", your words as they will go,
// the tick box that sends them in her language (with what Robot made of them), the card's notes (a group sees it, an
// SMS may cost, Signal marks the chat read, Cheogram turns an emoji alone into a reaction), and Send, Edit, Open chat,
// Cancel. A tap on Send sends; a spoken yes only where the card says so. The panel (assistant/PanelMessages) owns what
// each button does; this only draws the card and hands the taps back as [ReplyOp].

/** Where the card is. */
enum class ReplyCardState {
    /** Several conversations fit "Mom": tap the one you mean. */
    PICK,
    /** Waiting for Send (or a yes where allowed). */
    WAITING,
    /** "Send my replies to Mom without a card": it goes in 3 seconds unless you stop it. */
    STOP_WINDOW,
    SENDING,
    SENT,
    /** The chat opened with your reply as a draft: nothing is sent until you press Send there. */
    OPENED,
    /** The notification went (or isn't the same conversation): the chat can open with your reply instead. */
    GONE,
    CANCELLED,
    FAILED,
}

/** A tap on the card. */
sealed class ReplyOp {
    data object Send : ReplyOp()
    data object Cancel : ReplyOp()
    data object OpenChat : ReplyOp()
    /** Stop a reply in its 3 seconds. */
    data object Stop : ReplyOp()
    data class Edit(val text: String) : ReplyOp()
    /** "Send in French" ticked or not. */
    data class Translate(val on: Boolean) : ReplyOp()
    /** "Always for Mom" ticked or not. */
    data class Always(val on: Boolean) : ReplyOp()
    data class Pick(val index: Int) : ReplyOp()
}

/**
 * The card as drawn. [headline]: "Reply to **Mom** · Cheogram" (light Markdown). [line]: its state in words, under the
 * headline ("Tap Send to send it.", "Mom wrote again: «…». Still send?"). [text]: your words. [translation]: the same
 * in their language with [translateLabel] ("Send in French") ticked when [translate]; [alwaysLabel]: "Always for Mom".
 * [notes]: the lines about what sending does. [choices]: the conversations to pick from ([ReplyCardState.PICK]).
 */
@Immutable
data class ReplyCardView(
    val headline: String,
    val state: ReplyCardState,
    val line: String? = null,
    val text: String = "",
    val translation: String? = null,
    val translateLabel: String? = null,
    val translate: Boolean = false,
    val alwaysLabel: String? = null,
    val always: Boolean = false,
    val notes: List<String> = emptyList(),
    val choices: List<String> = emptyList(),
    /** Open chat is offered (a hand-off route exists for this app). */
    val openChat: Boolean = true,
)

@Composable
fun ReplyCard(r: ReplyCardView, onOp: ((ReplyOp) -> Unit)?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    var editing by remember(r.text) { mutableStateOf(false) }
    var draft by remember(r.text) { mutableStateOf(r.text) }
    val waiting = r.state == ReplyCardState.WAITING && onOp != null
    PanelCard(modifier) {
        CardHeader(
            headline = spans(Markdown.inline(r.headline), Color.Unspecified, caret = false),
            supporting = r.line,
            error = r.state == ReplyCardState.FAILED || r.state == ReplyCardState.GONE,
            leading = { CardIcon(RIcons.Message) },
            trailing = when (r.state) {
                ReplyCardState.SENT -> { { Icon(RIcons.Check, "Sent", tint = c.primary) } }
                else -> null
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (r.state == ReplyCardState.PICK) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                r.choices.forEachIndexed { i, label -> CardChoiceRow(label, onClick = { onOp?.invoke(ReplyOp.Pick(i)) }, icon = RIcons.Person) }
            }
            if (onOp != null) CardButtons { TextButton(onClick = { onOp(ReplyOp.Cancel) }) { Text("Cancel") } }
            return@PanelCard
        }
        if (editing && waiting) {
            OutlinedTextField(draft, { draft = it }, label = { Text("Your reply") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            CardButtons {
                TextButton(onClick = { editing = false; draft = r.text }) { Text("Cancel") }
                Button(onClick = { editing = false; onOp(ReplyOp.Edit(draft)) }, enabled = draft.isNotBlank()) { Text("Done") }
            }
            return@PanelCard
        }
        if (r.text.isNotBlank()) CardInset("«${r.text}»")
        val tl = r.translation
        if (tl != null && r.translateLabel != null) {
            Tick("${r.translateLabel}: «$tl»", r.translate, enabled = waiting) { onOp?.invoke(ReplyOp.Translate(it)) }
            r.alwaysLabel?.let { a -> if (r.translate) Tick(a, r.always, enabled = waiting) { onOp?.invoke(ReplyOp.Always(it)) } }
        }
        for (n in r.notes) {
            Text(n, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (onOp == null) return@PanelCard
        when (r.state) {
            ReplyCardState.WAITING -> {
                // The secondary actions on their own row, so the four fit at twice the text size too.
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editing = true }) { Text("Edit") }
                    if (r.openChat) TextButton(onClick = { onOp(ReplyOp.OpenChat) }) { Text("Open chat") }
                }
                CardButtons {
                    TextButton(onClick = { onOp(ReplyOp.Cancel) }) { Text("Cancel") }
                    Button(onClick = { onOp(ReplyOp.Send) }, modifier = Modifier.weight(1f, fill = false)) { Text("Send") }
                }
            }
            ReplyCardState.STOP_WINDOW -> CardButtons { Button(onClick = { onOp(ReplyOp.Stop) }) { Text("Stop") } }
            ReplyCardState.GONE, ReplyCardState.FAILED -> CardButtons {
                TextButton(onClick = { onOp(ReplyOp.Cancel) }) { Text("Cancel") }
                if (r.openChat) FilledTonalButton(onClick = { onOp(ReplyOp.OpenChat) }) { Text("Open chat") }
            }
            else -> {}
        }
    }
}

/** A tick box row, 48 dp, the whole row the target. */
@Composable
private fun Tick(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = RobotTokens.minTouch).clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { contentDescription = label })
    }
}
