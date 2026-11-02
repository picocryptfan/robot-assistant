// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.lists

import android.content.Context
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsNames
import app.heyrobot.core.lists.ListsRefusal
import app.heyrobot.core.lists.NoteToList
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. "Make a list from this note" on a note's page (lists-plan.md §7.3; LS5): the note's lines, bullets or (on
// one line) commas become the items of a new list, by code (core NoteToList, the spoken rules for articles and
// quantities); the note is kept. The list is called by the note's title; with no title, or a name you already have, a
// dialog asks for one. Lists made from notes before 1.4 ("add vitamin D to my shopping list" became a note) come over
// this way. Shown only while Lists is on.

/** The note page's button: [title] and [text] as the editor holds them. */
@Composable
fun MakeListButton(title: String, text: String, a: AppActions, snackbar: SnackbarHostState, port: () -> ListsPort = rememberPhonePort()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { port() }
    var on by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { on = withContext(Dispatchers.IO) { runCatching { p.on() }.getOrDefault(false) } }
    if (!on || text.isBlank()) return

    fun make(name: String) {
        val read = NoteToList.read(text)
        if (read.items.isEmpty()) return a.say(ctx.getString(R.string.lists_note_no_items))
        scope.launch {
            val w = withContext(Dispatchers.IO) {
                p.update(ItemHow.NOTE) { f, who ->
                    when (val m = ListsNames.make(f, name, who, listIds)) {
                        is ListsEdit.No -> m
                        is ListsEdit.Done -> NoteToList.add(m.file, m.list, read.items, who, listIds)
                    }
                }.also { if (it.done?.changed == true) p.changed() }
            }
            val d = w.done
            when {
                d != null -> {
                    val made = d.file.list(d.list)
                    val label = made?.let { listLabel(it, app.heyrobot.sys.AppLanguage.langs(ctx)) } ?: name
                    val n = d.items.size
                    val done = ctx.resources.getQuantityString(R.plurals.lists_made_from_note, n, label, n)
                    val msg = if (read.skipped == 0) done
                        else ctx.getString(R.string.lists_two_lines, done, ctx.resources.getQuantityString(R.plurals.lists_skipped, read.skipped, read.skipped))
                    val r = snackbar.showSnackbar(msg, actionLabel = ctx.getString(R.string.lists_open), duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) a.openPage(Page.ListPage(d.list.hex))
                }
                (w.edit as? ListsEdit.No)?.why == ListsRefusal.NAME_TAKEN || (w.edit as? ListsEdit.No)?.why == ListsRefusal.ROLE_TAKEN -> {
                    a.say(refusalText(ctx, ListsRefusal.NAME_TAKEN, name))
                    naming = name
                }
                w.edit is ListsEdit.No -> a.say(refusalText(ctx, (w.edit as ListsEdit.No).why, name))
                else -> a.say(ctx.getString(if (w.load == ListsLoad.Locked) R.string.lists_unlock_to_see else R.string.lists_not_now))
            }
        }
    }

    IconButton(onClick = { if (title.isBlank()) naming = "" else make(title.trim()) }) { Icon(RIcons.Checklist, stringResource(R.string.lists_from_note)) }
    naming?.let { start ->
        NameDialog(stringResource(R.string.lists_new), start, onDismiss = { naming = null }) { name ->
            naming = null
            make(name)
        }
    }
}

@Composable
private fun rememberPhonePort(): () -> ListsPort {
    val ctx: Context = LocalContext.current
    return { PhoneListsPort(ctx) }
}
