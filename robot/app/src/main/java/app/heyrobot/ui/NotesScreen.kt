package app.heyrobot.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
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
import app.heyrobot.core.Note
import app.heyrobot.core.NoteSearch
import app.heyrobot.ui.common.RIcons

// MAIN PROCESS. Robot Notes (docs/phase-2.md "Notes"): the list with search, one note to read and edit, Delete and
// Share. The assistant adds notes from the panel; this screen re-reads the folder whenever it is shown.

@Composable
fun NotesScreen(notes: List<Note>, a: AppActions, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    // Search: the same ranking find_notes uses, or every note (newest first) while the field is empty.
    val shown = remember(notes, query) { if (query.isBlank()) notes else NoteSearch.find(query, notes, limit = notes.size) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            ScreenTitle("Robot Notes", "Saved on this phone only. Say “note: …” to add one.")
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search notes") }, leadingIcon = { Icon(RIcons.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(RIcons.Close, "Clear") } },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(RIcons.Note, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(if (notes.isEmpty()) "No notes yet" else "No note matches", style = MaterialTheme.typography.titleMedium)
                        Text(if (notes.isEmpty()) "Ask Robot to note something, or tap New note." else "Try other words.",
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
            onClick = { a.openNote(null) }, icon = { Icon(RIcons.Add, null) }, text = { Text("New note") },
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
 * One note: title and text to edit, saved when you leave (an empty new note is dropped), Share (the system chooser,
 * so it can go to Easy Notes or anything else) and Delete.
 */
@Composable
fun NoteScreen(note: Note?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var title by rememberSaveable(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var text by rememberSaveable(note?.id) { mutableStateOf(note?.text.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun leave() {
        val changed = title.trim() != note?.title.orEmpty() || text.trim() != note?.text.orEmpty()
        if (changed && (text.isNotBlank() || title.isNotBlank())) a.saveNote(note, title.trim(), text.trim())
        onBack()
    }
    BackHandler { leave() }
    Scaffold(
        topBar = {
            BackBar(if (note == null) "New note" else "Note", ::leave) {
                IconButton(onClick = {
                    val subject = title.trim()
                    val body = listOf(subject, text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
                    if (body.isEmpty()) return@IconButton
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                    if (subject.isNotEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
                    try { ctx.startActivity(Intent.createChooser(send, "Share the note")) } catch (_: Exception) { a.say("Nothing on this phone can take the note.") }
                }) { Icon(RIcons.Share, "Share") }
                if (note != null) IconButton(onClick = { confirmDelete = true }) { Icon(RIcons.Delete, "Delete") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Title") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("Note") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (note != null) {
                Text("Changed ${DateUtils.getRelativeTimeSpanString(note.updated)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
    if (confirmDelete && note != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("It can't be brought back.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; a.deleteNote(note.id); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
