// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.SpeakerClusterer
import app.heyrobot.core.SpeakerLabels
import app.heyrobot.core.Transcript
import app.heyrobot.core.TranscriptEdits
import app.heyrobot.core.TranscriptLine
import app.heyrobot.ui.theme.str

// Main and voice processes (phase 5.3, docs/conversation-mode.md 5.3). Speakers on a transcript screen, shared by the
// live transcript (voice process, through the session) and the library's transcript (main app, through the store): a
// chip per line with the speaker's colour and name; tap a chip to name the speaker, say they are the same person as
// another, or "this is me"; long-press a line to give it to another speaker or split it where someone else started; and
// the card for two speakers who sound alike. Every choice is one more event in the transcript's file. 1.1: its words are
// strings_conversation.xml's, in the menus' language; your own lines are "You", « Moi » (core SpeakerLabels.you).

/** What a screen does with your choices (the session's writer, or the store when nothing records the transcript). */
interface SpeakerActions {
    fun rename(speaker: String, name: String)
    fun merge(from: String, to: String)
    fun thisIsMe(speaker: String)
    fun move(i: Int, speaker: String)
    fun split(i: Int, at: Int, speaker: String?)
    fun decline(from: String, to: String)
}

/**
 * The transcript's speakers as a screen shows them; [retired]: labels no line has that a new person must not get
 * (merged away, or already handed out in the session).
 */
data class SpeakerView(val speakers: List<String>, val names: Map<String, String>, val me: String?, val retired: Set<String> = emptySet()) {
    private val t = Transcript(names = names, me = me)

    fun nameOf(speaker: String): String = t.nameOf(speaker)

    /** [nameOf] in the screen's language ("You", « Moi »). */
    @Composable
    fun name(speaker: String): String = t.nameText(speaker).str()

    /** Who a line or speaker can go to: the transcript's speakers without "?", you first. */
    val targets: List<String> get() = TranscriptEdits.targets(speakers, me)

    fun isMe(speaker: String): Boolean = speaker == SpeakerClusterer.ME || speaker == me

    /** The label for "someone else": none the transcript ever used. */
    val next: String get() = SpeakerLabels.next(speakers + retired)

    companion object {
        fun of(t: Transcript) = SpeakerView(t.speakers, t.names, t.me, t.merges.keys)
    }
}

/** What is being changed, one dialog at a time. */
sealed class SpeakerEdit {
    data class Menu(val speaker: String) : SpeakerEdit()
    data class Rename(val speaker: String) : SpeakerEdit()
    data class Merge(val speaker: String) : SpeakerEdit()
    data class LineMenu(val line: TranscriptLine) : SpeakerEdit()
    data class Move(val line: TranscriptLine) : SpeakerEdit()
    data class Split(val line: TranscriptLine) : SpeakerEdit()
}

private val PALETTE = listOf(
    Color(0xFF1E88E5), Color(0xFFD81B60), Color(0xFF43A047), Color(0xFFF4511E), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFF6D4C41),
)

/** A speaker's colour: blue for you, then a fixed colour per label; grey for "?". */
@Composable
fun speakerColour(speaker: String): Color {
    val k = SpeakerLabels.colour(speaker)
    return if (k < 0) MaterialTheme.colorScheme.outline else PALETTE[k % PALETTE.size]
}

/** The chip on a line: the speaker's colour and name ("Speaker" when there is one voice). Tappable when [onClick] isn't null. */
@Composable
fun SpeakerChip(speaker: String, shown: String, onClick: (() -> Unit)?) {
    val desc = if (onClick != null) stringResource(R.string.conv_speaker_tap_desc, shown) else stringResource(R.string.conv_speaker_desc, shown)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // 1.0: a tappable chip reserves a 48 dp target around its 24 dp look.
        modifier = (if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .heightIn(min = 24.dp).clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = desc }) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(speakerColour(speaker)))
            Spacer(Modifier.width(6.dp))
            Text(shown, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** "S3 sounds like S1. Same person?" with Merge and "Not the same". */
@Composable
fun SimilarCard(view: SpeakerView, from: String, to: String, onMerge: () -> Unit, onDecline: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.conv_sounds_like, view.name(from), view.name(to)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.conv_merging_gives, view.name(from), view.name(to)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onMerge, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conv_merge)) }
                OutlinedButton(onClick = onDecline, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conv_not_the_same)) }
            }
        }
    }
}

/** A list of speakers to pick one from (and, when [someoneElse], a new label for a person not heard yet). */
@Composable
private fun Pick(view: SpeakerView, except: String?, someoneElse: Boolean, pick: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (s in view.targets.filter { it != except }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable { pick(s) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(speakerColour(s)))
                Spacer(Modifier.width(10.dp))
                val shown = view.name(s)
                Text(if (view.nameOf(s) != s && s != SpeakerClusterer.ME) "$shown ($s)" else shown, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (someoneElse) {
            val next = view.next
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable { pick(next) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(speakerColour(next)))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.conv_someone_else_label, next), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** The dialog for [edit] (null: none). [done] closes it, or moves on to the next step. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakerEditDialog(edit: SpeakerEdit?, view: SpeakerView, actions: SpeakerActions, done: (SpeakerEdit?) -> Unit) {
    when (edit) {
        null -> {}
        is SpeakerEdit.Menu -> AlertDialog(
            onDismissRequest = { done(null) },
            title = { Text(view.name(edit.speaker)) },
            text = {
                Column {
                    if (edit.speaker != SpeakerClusterer.UNKNOWN) {
                        TextButton(onClick = { done(SpeakerEdit.Rename(edit.speaker)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.conv_name_speaker), Modifier.fillMaxWidth())
                        }
                        if (view.targets.size > 1) TextButton(onClick = { done(SpeakerEdit.Merge(edit.speaker)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.conv_same_person_as), Modifier.fillMaxWidth())
                        }
                        if (!view.isMe(edit.speaker)) TextButton(onClick = { actions.thisIsMe(edit.speaker); done(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.conv_this_is_me), Modifier.fillMaxWidth())
                        }
                    } else Text(stringResource(R.string.conv_unknown_speaker))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { done(null) }) { Text(stringResource(R.string.conv_close)) } },
        )
        is SpeakerEdit.Rename -> {
            var name by remember(edit) { mutableStateOf(view.names[edit.speaker].orEmpty()) }
            AlertDialog(
                onDismissRequest = { done(null) },
                title = { Text(if (view.isMe(edit.speaker)) stringResource(R.string.conv_your_name_here) else stringResource(R.string.conv_name_label, edit.speaker)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it.take(SpeakerLabels.MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.conv_name)) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.conv_name_kept, if (view.isMe(edit.speaker)) SpeakerLabels.you.str() else edit.speaker),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { actions.rename(edit.speaker, name); done(null) }) { Text(stringResource(R.string.conv_save)) } },
                dismissButton = { TextButton(onClick = { done(null) }) { Text(stringResource(R.string.conv_cancel)) } },
            )
        }
        is SpeakerEdit.Merge -> AlertDialog(
            onDismissRequest = { done(null) },
            title = { Text(if (edit.speaker == SpeakerClusterer.ME) stringResource(R.string.conv_who_else_is_you)
                else stringResource(R.string.conv_same_person_title, view.name(edit.speaker))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (edit.speaker == SpeakerClusterer.ME) stringResource(R.string.conv_lines_become_yours)
                        else stringResource(R.string.conv_lines_go_to, view.name(edit.speaker)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Pick(view, except = edit.speaker, someoneElse = false) { other ->
                        // "Me" is never merged into someone else: the other way round.
                        if (edit.speaker == SpeakerClusterer.ME) actions.merge(other, SpeakerClusterer.ME) else actions.merge(edit.speaker, other)
                        done(null)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { done(null) }) { Text(stringResource(R.string.conv_cancel)) } },
        )
        is SpeakerEdit.LineMenu -> AlertDialog(
            onDismissRequest = { done(null) },
            title = { Text(stringResource(R.string.conv_this_line)) },
            text = {
                Column {
                    TextButton(onClick = { done(SpeakerEdit.Move(edit.line)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.conv_someone_else_said), Modifier.fillMaxWidth())
                    }
                    if (TranscriptEdits.wordStarts(edit.line.text).size >= 2) TextButton(onClick = { done(SpeakerEdit.Split(edit.line)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.conv_two_people_split), Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { done(null) }) { Text(stringResource(R.string.conv_close)) } },
        )
        is SpeakerEdit.Move -> AlertDialog(
            onDismissRequest = { done(null) },
            title = { Text(stringResource(R.string.conv_who_said_it)) },
            text = { Pick(view, except = edit.line.speaker, someoneElse = true) { s -> actions.move(edit.line.i, s); done(null) } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { done(null) }) { Text(stringResource(R.string.conv_cancel)) } },
        )
        is SpeakerEdit.Split -> {
            val starts = remember(edit) { TranscriptEdits.wordStarts(edit.line.text) }
            var at by remember(edit) { mutableStateOf<Int?>(null) }
            AlertDialog(
                onDismissRequest = { done(null) },
                title = { Text(stringResource(if (at == null) R.string.conv_where_other_started else R.string.conv_who_said_rest)) },
                text = {
                    val cut = at
                    if (cut == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Every word but the first can start the second part.
                        starts.forEachIndexed { k, s ->
                            val word = edit.line.text.substring(s, starts.getOrNull(k + 1) ?: edit.line.text.length).trim()
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).then(if (k > 0) Modifier.clickable { at = s } else Modifier)) {
                                Text(word, Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge,
                                    color = if (k > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.conv_quoted, edit.line.text.substring(cut).trim()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Pick(view, except = null, someoneElse = true) { s -> actions.split(edit.line.i, cut, s); done(null) }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { done(null) }) { Text(stringResource(R.string.conv_cancel)) } },
            )
        }
    }
}
