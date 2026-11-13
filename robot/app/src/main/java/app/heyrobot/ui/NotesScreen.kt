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
import app.heyrobot.ui.common.AlertDialog
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
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
import app.heyrobot.sys.NotesRead
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// MAIN PROCESS. Robot Notes (docs/phase-2.md "Notes"): the list with search, one note to read and edit, Delete and
// Share. The assistant adds notes from the panel; this screen re-reads the folder whenever it is shown.

@Composable
fun NotesScreen(notes: List<Note>, a: AppActions, modifier: Modifier, kept: NotesRead = NotesRead(emptyList()), onDeleteAside: () -> Unit = {}) {
    SecureWindow()  // A note is what you told Robot: out of screenshots, casting and Recents.
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
            // seal-notes: notes kept aside (their key gone, or damaged) are counted here, never hidden; seal-notes-b: with a
            // newer Robot's (Delete these reaches them) and those not openable now, so a backup's refusal has its why here.
            if (kept.aside + kept.tooNew + kept.notNow > 0) NotesAsideCard(kept, onDeleteAside)
            if (shown.isEmpty() && notesEmptyShown(notes, kept)) {
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
        // Material hides the button's text from TalkBack (it can collapse to the icon), so the icon carries the name.
        ExtendedFloatingActionButton(
            onClick = { a.openNote(null) }, icon = { Icon(RIcons.Add, stringResource(R.string.notes_new)) }, text = { Text(stringResource(R.string.notes_new)) },
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
fun NoteScreen(noteId: String?, version: Int, a: AppActions, snackbar: SnackbarHostState, onCreated: (String) -> Unit, onBack: () -> Unit, onSaved: () -> Unit = {}) {
    SecureWindow()
    val ctx = LocalContext.current
    val store = remember { NotesFiles.store(ctx) }
    var id by rememberSaveable { mutableStateOf(noteId) }
    // seal-notes-b: a new note's id is chosen before its first write, which runs off the main thread (NoteWriter).
    val target = rememberSaveable { noteId ?: java.util.UUID.randomUUID().toString() }
    // After the id: a note too big for saved state comes back with its id and is read again.
    val words = rememberSaveable(saver = NoteWordsSaver) { NoteWords(loaded = id == null) }
    var title by words::title
    var text by words::text
    var created by rememberSaveable { mutableLongStateOf(0L) }
    var updated by rememberSaveable { mutableLongStateOf(0L) }
    var saved by words::saved
    var loaded by words::loaded
    var deleted by rememberSaveable { mutableStateOf(false) }
    // seal-notes-b: an existing note that can't be opened now (locked, a newer Robot's): no Restore, nothing written over it.
    var notNow by remember { mutableStateOf(false) }
    var tooNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val created1 by rememberUpdatedState(onCreated)
    val saved1 by rememberUpdatedState(onSaved)
    val writer = remember { NoteWriter.of(store, target, id != null, created, saved) }
    // seal-notes-c: set as the page goes; a write landing after it never takes over a New note page opened since.
    val closed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun key(t: String, x: String) = noteKey(t, x)

    fun wrote(r: NoteWriter.Result) {
        when (r) {
            is NoteWriter.Result.Saved -> {
                saved = r.key; created = r.created; updated = r.updated; deleted = false
                val l = NoteWriter.landed(first = id == null, closed = closed.get())
                if (id == null) id = target
                if (l.created) created1(target)
                if (l.refresh) saved1()
            }
            // Gone underneath (Undo in the panel): never written back silently.
            NoteWriter.Result.Gone -> deleted = true
            NoteWriter.Result.Failed -> {}
        }
    }

    /** Asks for the note to be written if it changed (also from onDispose and onStop); the write itself runs off the main thread. */
    fun save() {
        if (!loaded || deleted) return
        if (key(title, text) == saved) return
        writer.save(title, text, done = ::wrote)
    }

    // Read at first and again each time the app comes back to the front (the panel may have undone this note).
    LaunchedEffect(version) {
        val cur = id
        if (loaded && !deleted && cur != null && withContext(Dispatchers.IO) { store.gone(cur) }) deleted = true
        if (!loaded && cur != null) {
            val n = withContext(Dispatchers.IO) { store.get(cur) }
            if (n == null) { if (withContext(Dispatchers.IO) { store.gone(cur) }) deleted = true else { notNow = true; tooNew = withContext(Dispatchers.IO) { store.tooNew(cur) } }; return@LaunchedEffect }
            notNow = false
            tooNew = false
            run {
                title = n.title; text = n.text; created = n.created; updated = n.updated
                saved = key(n.title, n.text)
                writer.loaded(n)
            }
            loaded = true
        }
    }
    LaunchedEffect(title, text) {
        delay(AUTOSAVE_MS)
        save()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e in NoteWriter.SAVES_ON) save() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            closed.set(true)
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
                // 1.4 Lists (LS5): the note's lines as a new list's items; the note is kept.
                if (!deleted) app.heyrobot.ui.lists.MakeListButton(title, text, a, snackbar)
                if (NoteWriter.offersDelete(deleted, loaded)) IconButton(onClick = { confirmDelete = true }) { Icon(RIcons.Delete, stringResource(R.string.notes_delete)) }
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
                            // Never for a note whose words were never read: Restore would write an empty note over it.
                            if (loaded) TextButton(onClick = {
                                if (id == null) a.say(ctx.getString(R.string.notes_not_restored))
                                else writer.save(title, text, restore = true) { r -> if (r is NoteWriter.Result.Saved) wrote(r) else a.say(ctx.getString(R.string.notes_not_restored)) }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.notes_restore)) }
                        }
                    }
                }
                if (notNow && !deleted) Text(stringResource(if (tooNew) R.string.notes_too_new_banner else R.string.notes_not_now_banner), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    // seal-notes: off the main thread (it waits for Notes' lock), and finished even as this page closes;
                    // seal-notes-b: after any save still waiting, so none brings the note back.
                    writer.delete()
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

/** Title and text compared when saving. */
internal fun noteKey(title: String, text: String) = title.trim() + "\u0000" + text.trim()

/** The note being edited, kept across a rotation by [NoteWordsSaver]. */
internal class NoteWords(title: String = "", text: String = "", saved: String? = null, loaded: Boolean = false) {
    var title by mutableStateOf(title)
    var text by mutableStateOf(text)
    /** Title and text as last written; the editor writes only when they change. */
    var saved by mutableStateOf(saved)
    /** False until an existing note was read. */
    var loaded by mutableStateOf(loaded)
}

/** Android's saved state crashes the app above about 1 MB, and it holds more than this note: bigger notes aren't put in it. */
internal const val NOTE_MAX_SAVED_CHARS = 32_000

/**
 * Saved state holds a note's words while they fit (after a rotation the edited text is kept, not re-read). A bigger one saves
 * nothing: it was written when the app stopped, so it is read again by its id. Only whether it matched the file is kept, not
 * a second copy of the words.
 */
internal val NoteWordsSaver: Saver<NoteWords, Any> = listSaver(
    save = { w ->
        if (w.title.length + w.text.length > NOTE_MAX_SAVED_CHARS) emptyList()
        else listOf(w.title, w.text, w.saved == noteKey(w.title, w.text), w.loaded)
    },
    restore = { l -> NoteWords(l[0] as String, l[1] as String, if (l[2] as Boolean) noteKey(l[0] as String, l[1] as String) else null, l[3] as Boolean) },
)

/** seal-notes: the notes that can't be opened again, and Delete these (with a confirm) for them alone. */
@Composable
private fun NotesAsideCard(kept: NotesRead, onDeleteAside: () -> Unit) {
    val aside = kept.aside
    val newer = kept.tooNew
    var confirm by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (aside > 0) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.notes_aside, aside, aside), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.notes_aside_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (newer > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.notes_too_new, newer, newer), style = MaterialTheme.typography.bodyMedium)
            if (kept.notNow > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.notes_not_now, kept.notNow, kept.notNow), style = MaterialTheme.typography.bodyMedium)
            if (aside + newer > 0) TextButton(onClick = { confirm = true }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.home_delete_these)) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.notes_delete_aside_title)) },
        text = { Text(stringResource(R.string.notes_delete_aside_detail)) },
        confirmButton = { TextButton(onClick = { confirm = false; onDeleteAside() }) { Text(stringResource(R.string.home_delete_these)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.notes_cancel)) } },
    )
}

/** fix-seal-lows-c 5: no "no notes yet" while notes are held (locked, a newer Robot's): the card above says why. */
internal fun notesEmptyShown(notes: List<Note>, kept: NotesRead): Boolean = notes.isNotEmpty() || kept.notNow + kept.tooNew == 0
