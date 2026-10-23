// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.ui.theme.str
import app.heyrobot.core.memory.ImportPlan
import app.heyrobot.core.memory.ImportProblem
import app.heyrobot.core.memory.ImportRead
import app.heyrobot.core.memory.MemoryDates
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.CheckboxPreference
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.TwoTargetPreference
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.pref.preferenceItems
import app.heyrobot.ui.pref.settingsBackground
import java.io.InputStream

// MAIN PROCESS. Settings › Memory › Import's preview (1.0), one screen before anything is written: how many memories the
// file holds, what is skipped and why (already in Memory, a password, a PIN, a code or a card number, ended, unreadable, no
// room), what will be added, private ones masked until tapped, and "These are my own memories, from my own export", off by
// default (core MemoryImport: until then the items count as content, as memory kept from something Robot read does). Only
// a tap on Import writes, through Settings › Memory's store and the vault's lock (MemorySettings.kt). FLAG_SECURE, as
// Memory is; nothing here logs.

/**
 * A file read for Import and what importing it would do now. [plan] is made as if "These are my own memories" were ticked,
 * so the items the file itself marks as content show it; the write plans again with your choice, under the vault's lock.
 */
class ImportPreview(val read: ImportRead.Ok, val plan: ImportPlan)

/** The preview: [mine] is the tick box, [busy] while the import is written. Back and Cancel are [onCancel]. */
@Composable
fun MemoryImportScreen(preview: ImportPreview, now: Long, mine: Boolean, onMine: (Boolean) -> Unit, onImport: () -> Unit, onCancel: () -> Unit,
    busy: Boolean = false) {
    // What the file holds is shown here, private items when tapped: out of screenshots, recordings, casting and Recents.
    SecureWindow()
    BackHandler(onBack = onCancel)
    val p = preview.plan
    var shown by remember(preview) { mutableStateOf(emptySet<String>()) }
    val listTitle = stringResource(R.string.memory_import_list)
    val nothing = stringResource(R.string.memory_import_nothing)
    PreferenceScreen(stringResource(R.string.memory_import_title), onCancel, bottomBar = {
        Surface(color = settingsBackground()) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.memory_import_cancel)) }
                Button(onClick = onImport, enabled = p.add.isNotEmpty() && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.memory_import_button))
                }
            }
        }
    }) {
        preferenceGroup(null, "counts") {
            CountRow(R.string.memory_import_in_file, p.inFile)
            CountRow(R.string.memory_import_duplicates, p.duplicates)
            CountRow(R.string.memory_import_secrets, p.secrets, if (p.secrets > 0) stringResource(R.string.memory_import_secrets_detail) else null)
            if (p.ended > 0) CountRow(R.string.memory_import_ended, p.ended)
            if (p.damaged > 0) CountRow(R.string.memory_import_damaged, p.damaged)
            if (p.noRoom > 0) CountRow(R.string.memory_import_no_room, p.noRoom, stringResource(R.string.memory_import_no_room_detail))
            CountRow(R.string.memory_import_to_add, p.add.size, if (p.private > 0) stringResource(R.string.memory_import_private, p.private) else null)
        }
        preferenceGroup(null, "mine") {
            CheckboxPreference(stringResource(R.string.memory_import_mine), stringResource(R.string.memory_import_mine_detail), mine, onMine)
        }
        if (p.add.isEmpty()) item(key = "nothing") { IntroPreference(nothing) }
        else preferenceItems(listTitle, "items", p.add, { it.id }) { item ->
            ImportRow(item, now, item.id in shown) { shown = if (item.id in shown) shown - item.id else shown + item.id }
        }
    }
}

/** "Already in Memory, skipped" and its count, read as one. */
@Composable
private fun CountRow(@StringRes title: Int, count: Int, detail: String? = null) {
    PreferenceItem(stringResource(title), detail, trailing = {
        Text("%,d".format(count), style = MaterialTheme.typography.titleMedium)
    })
}

/**
 * One item to add: its words, or for a private one "Private: tap to show" until tapped (a tap hides it again); under it,
 * its state as Settings › Memory shows it, and whether the file marks it as something Robot read.
 */
@Composable
private fun ImportRow(item: MemoryItem, now: Long, revealed: Boolean, toggle: () -> Unit) {
    val line = listOfNotNull(MemoryDates.lineText(item, now).str(), if (item.tainted) stringResource(R.string.memory_import_from_content) else null).joinToString(" · ")
    if (!item.secret) {
        PreferenceItem(item.text, line)
    } else {
        TwoTargetPreference(if (revealed) item.text else stringResource(R.string.memory_import_masked), line, onClick = toggle, icon = RIcons.Lock,
            onClickLabel = stringResource(if (revealed) R.string.memory_import_hide else R.string.memory_import_show)) { Spacer(Modifier.width(8.dp)) }
    }
}

/** Memory's list (1.0): the small label on an item that came from an import, until "This is mine". */
@Composable
fun ImportedChip(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    // Read with its row ("…, Imported"), not as a stop of its own.
    Surface(color = c.secondaryContainer, contentColor = c.onSecondaryContainer, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(stringResource(R.string.memory_imported), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/**
 * What Android's picker offers for Import: JSON, and any file, since some apps save a .json file as plain text or bytes.
 * The second is "any type" written with an escape, so the source scans (ProcessIsolationTest) don't read a comment in it.
 */
internal val IMPORT_TYPES = arrayOf("application/json", "*\u002F*")

/** What Robot says when a file isn't imported at all. */
@StringRes
internal fun importProblem(p: ImportProblem): Int = when (p) {
    ImportProblem.TOO_BIG -> R.string.memory_import_too_big
    ImportProblem.NOT_EXPORT -> R.string.memory_import_not_export
    ImportProblem.TOO_NEW -> R.string.memory_import_too_new
    ImportProblem.TOO_MANY -> R.string.memory_import_too_many
}

/** At most [max] bytes of [input]: a file longer than that comes back cut at [max], which core reads as too big. */
internal fun readUpTo(input: InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 shl 10)
    while (out.size() < max) {
        val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
