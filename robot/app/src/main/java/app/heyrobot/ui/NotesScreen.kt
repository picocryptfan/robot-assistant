// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.core.Note
import app.heyrobot.core.NoteSearch
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.sys.NotesFiles
import app.heyrobot.ui.common.RIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// MAIN PROCESS. Robot Notes (docs/phase-2.md "Notes"): the list with search, one note to read and edit, Delete and
// Share. The assistant adds notes from the panel; this screen re-reads the folder whenever it is shown.

@Composable
fun NotesScreen(notes: List<Note>, a: AppActions, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    // Search: the same ranking find_notes uses, or every note (newest first) while the field is empty.
    val shown = remember(notes, query) { if (query.isBlank()) notes else NoteSearch.find(query, notes, limit = notes.size) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            ScreenTitle(stringResource(R.string.notes_name), stringResource(R.string.notes_subtitle))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.notes_search)) }, leadingIcon = { Icon(RIcons.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(RIcons.Close, stringResource(R.string.notes_clear)) } },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(RIcons.Note, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(if (notes.isEmpty()) R.string.notes_none else R.string.notes_no_match), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (notes.isEmpty()) R.string.notes_none_detail else R.string.notes_no_match_detail),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { n -> NoteRow(n) { a.openNote(n.id) } }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { a.openNote(null) }, icon = { Icon(RIcons.Add, null) }, text = { Text(stringResource(R.string.notes_new)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun NoteRow(n: Note, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(n.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val preview = if (n.title.isBlank()) n.text.lines().drop(1).joinToString(" ").trim() else n.text
            if (preview.isNotBlank()) Text(preview.replace(Regex("\\s+"), " "), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(DateUtils.getRelativeTimeSpanString(n.updated).toString(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * One note: title and text to edit, saved as you type (about 0.6 s after the last change), when the app goes to the
 * background and when the editor closes, so nothing typed is lost to a rotation, a route from the launcher or the
 * panel, or the process being killed. A new note keeps its id after its first save (one note, not one per save).
 * If the note disappears underneath (the panel's Undo), the editor says so and offers Restore: it never brings the
 * note back by itself, nor blanks what is shown. Share goes through the system chooser (Easy Notes or anything else).
 */
@Composable
fun NoteScreen(noteId: String?, version: Int, a: AppActions, snackbar: SnackbarHostState, onCreated: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { NotesFiles.store(ctx) }
    var id by rememberSaveable { mutableStateOf(noteId) }
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var created by rememberSaveable { mutableLongStateOf(0L) }
    var updated by rememberSaveable { mutableLongStateOf(0L) }
    /** Title and text as last written; the editor writes only when they change. */
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    /** False until an existing note was read (saveable: after a rotation the edited text is kept, not re-read). */
    var loaded by rememberSaveable { mutableStateOf(noteId == null) }
    var deleted by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val created1 by rememberUpdatedState(onCreated)

    fun key(t: String, x: String) = t.trim() + "\u0000" + x.trim()

    /** Writes the note now if it changed (a small file: on the main thread, also from onDispose and onStop). */
    fun save() {
        if (!loaded || deleted) return
        val k = key(title, text)
        if (k == saved) return
        val cur = id
        if (cur == null && title.isBlank() && text.isBlank()) return
        val now = System.currentTimeMillis()
        if (cur == null) {
            val n = store.add(text.trim(), title.trim(), now) ?: return
            id = n.id
            created = now
            created1(n.id)
        } else {
            // Gone underneath (Undo in the panel): never written back silently.
            if (store.get(cur) == null) { deleted = true; return }
            if (!store.save(Note(cur, title.trim(), text.trim(), if (created > 0) created else now, now))) return
        }
        saved = k
        updated = now
    }

    LaunchedEffect(Unit) {
        val cur = id
        if (!loaded && cur != null) {
            val n = withContext(Dispatchers.IO) { store.get(cur) }
            if (n == null) deleted = true
            else {
                title = n.title; text = n.text; created = n.created; updated = n.updated
                saved = key(n.title, n.text)
            }
            loaded = true
        }
    }
    // The app came back to the front (the panel may have undone this note): is it still there?
    LaunchedEffect(version) {
        val cur = id
        if (loaded && !deleted && cur != null && withContext(Dispatchers.IO) { store.get(cur) } == null) deleted = true
    }
    LaunchedEffect(title, text) {
        delay(AUTOSAVE_MS)
        save()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_STOP) save() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            save()
        }
    }

    fun leave() {
        save()
        onBack()
    }
    BackHandler { leave() }
    Scaffold(
        topBar = {
            BackBar(stringResource(if (id == null) R.string.notes_new else R.string.notes_note), ::leave) {
                IconButton(onClick = {
                    val subject = title.trim()
                    val body = listOf(subject, text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
                    if (body.isEmpty()) return@IconButton
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                    if (subject.isNotEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
                    try { ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.notes_share_title))) } catch (_: Exception) {
                        a.say(ctx.getString(R.string.notes_share_nothing))
                    }
                }) { Icon(RIcons.Share, stringResource(R.string.notes_share)) }
                if (!deleted) IconButton(onClick = { confirmDelete = true }) { Icon(RIcons.Delete, stringResource(R.string.notes_delete)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        // Scrolls, so in landscape with the keyboard up the note field keeps a usable height.
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad).imePadding()) {
            val noteMin = (maxHeight - 150.dp).coerceAtLeast(120.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (deleted) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.notes_deleted_banner), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val cur = id
                                val now = System.currentTimeMillis()
                                val ok = if (cur == null) false else store.save(Note(cur, title.trim(), text.trim(), if (created > 0) created else now, now))
                                if (ok) { deleted = false; saved = key(title, text); updated = now } else a.say(ctx.getString(R.string.notes_not_restored))
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.notes_restore)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true, label = { Text(stringResource(R.string.notes_title_field)) }, enabled = loaded,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.notes_note)) }, enabled = loaded,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = noteMin),
                )
                if (updated > 0) {
                    Text(if (deleted) stringResource(R.string.notes_not_saved) else stringResource(R.string.notes_saved_when, DateUtils.getRelativeTimeSpanString(updated)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.notes_delete_title)) },
            text = { Text(stringResource(R.string.notes_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleted = true
                    id?.let { store.delete(it) }
                    a.say(ctx.getString(R.string.notes_deleted))
                    onBack()
                }) { Text(stringResource(R.string.notes_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.notes_cancel)) } },
        )
    }
}

/** Typing pauses this long before the note is written. */
private const val AUTOSAVE_MS = 600L
