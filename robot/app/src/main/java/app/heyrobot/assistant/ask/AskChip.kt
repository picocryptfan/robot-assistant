// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.ask.AskCaps
import app.heyrobot.core.ask.AskShare
import app.heyrobot.core.ask.SharedKind
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.str

// VOICE PROCESS (the panel). Ask Robot in the panel (docs/plans/everyday-set-plan.md §7.3, E7): what another app shared,
// shown above the question as a chip ("Shared text: “The meeting moved to…”", "Shared photo", "Shared voice message
// (0:42)"), never naming the app, and under it the usual requests as buttons. Each button is your request in words, sent
// as if you said it (Q4: Robot is already listening, so a button stops that first). The chip's text is one clean line
// (core AskShare.preview): what was shared can't lay out the panel or hide words with bidi or zero-width characters.

/** The chip's line for [item]. */
fun chipText(item: SharedItem): Phrase = when (item.kind) {
    SharedKind.TEXT -> EverydayStrings.askChipText("text" to Arg.Plain(AskShare.preview(item.text.orEmpty())))
    SharedKind.PHOTO -> EverydayStrings.askChipPhoto()
    SharedKind.VOICE -> EverydayStrings.askChipVoice("time" to Arg.Plain(AskShare.duration(item.seconds)))
}

/** The line under it: what Robot left out, else that it goes with your next question. */
fun cutText(item: SharedItem): Phrase? = when {
    !item.cut -> null
    item.kind == SharedKind.VOICE -> EverydayStrings.askVoiceCut("n" to Arg.Count((AskCaps.MAX_SECONDS / 60).toLong()))
    else -> EverydayStrings.askTextCut("n" to Arg.Count(AskCaps.MAX_TEXT.toLong()))
}

/** The buttons, in order: what each says, and the request it sends. */
val QUICK: List<Pair<Int, Msg>> = listOf(
    R.string.everyday_ask_explain to EverydayStrings.askExplain,
    R.string.everyday_ask_translate to EverydayStrings.askTranslate,
    R.string.everyday_ask_summarize to EverydayStrings.askSummarize,
    R.string.everyday_ask_reply to EverydayStrings.askReply,
    R.string.everyday_ask_remember to EverydayStrings.askRemember,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskChip(item: SharedItem, busy: Boolean, onRemove: () -> Unit, onAsk: (Phrase) -> Unit, onRead: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("ask-chip")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val thumb = item.thumb
            if (thumb != null) {
                Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
            } else {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (item.kind == SharedKind.VOICE) RIcons.RecordVoiceOver else RIcons.Share, null)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(chipText(item).str(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(cutText(item)?.str() ?: stringResource(R.string.panel_goes_with_next), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(RIcons.Close, stringResource(R.string.everyday_ask_remove))
            }
        }
        if (!busy) {
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1.3 point and read (P3): a shared photo's words, read aloud as the panel's Read aloud reads one (PanelRead).
                if (item.kind == SharedKind.PHOTO) OutlinedButton(onClick = onRead, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.panel_read_aloud)) }
                for ((label, request) in QUICK) {
                    OutlinedButton(onClick = { onAsk(request()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) }
                }
                // Read it to me (read-it-to-me-plan.md §1): a shared text only, read whole by PanelRead, nothing kept after.
                if (item.kind == SharedKind.TEXT) OutlinedButton(onClick = { onAsk(app.heyrobot.core.i18n.HandsFreeStrings.readThisAsk()) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.read_it_to_me)) }
            }
        }
    }
}
