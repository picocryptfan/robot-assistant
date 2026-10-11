package app.heyrobot.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.heyrobot.core.ExportKind
import app.heyrobot.core.ExportOptions
import app.heyrobot.core.SessionStatus
import app.heyrobot.core.Transcript
import app.heyrobot.core.TranscriptExport
import app.heyrobot.core.TranscriptGap
import app.heyrobot.core.TranscriptHit
import app.heyrobot.core.TranscriptIndex
import app.heyrobot.core.TranscriptLine
import app.heyrobot.core.TranscriptMeta
import app.heyrobot.core.TranscriptTitles
import app.heyrobot.core.VoiceEvent
import app.heyrobot.sys.Exports
import app.heyrobot.sys.Transcripts
import app.heyrobot.ui.common.RIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

// MAIN PROCESS. The Transcripts library (docs/conversation-mode.md 5.1, 6.3, phase 5.5): the transcripts on this phone,
// newest first, a search over their words (accents and case ignored), one transcript to read, rename, export as
// Markdown or text (saved where you choose, or shared), copy, and delete; and "Delete all". It reads the files the
// voice process writes; the one being recorded can't be renamed until it stops, and deleting it stops the recording
// (the voice process is told, so it can't write it back).

private data class Row(val meta: TranscriptMeta, val live: Boolean, val bytes: Long)

/** How a transcript stands, in a few words (a RUNNING one nobody records any more stopped unexpectedly). */
private fun statusText(m: TranscriptMeta, live: Boolean): String? = when {
    live -> "Recording now"
    m.status == SessionStatus.INTERRUPTED || m.status == SessionStatus.RUNNING -> "Stopped unexpectedly"
    else -> null
}

private fun durationOf(m: TranscriptMeta, t: Transcript?): Long = when {
    m.endedAt > m.createdAt -> m.endedAt - m.createdAt
    t != null && t.lines.isNotEmpty() -> (t.lines.maxOf { it.t1 } * 1000).toLong()
    else -> 0
}

@Composable
fun TranscriptsScreen(snackbar: SnackbarHostState, onOpen: (String, Int?) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Row>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TranscriptHit>>(emptyList()) }
    var confirmAll by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        while (true) {
            rows = withContext(Dispatchers.IO) {
                val s = Transcripts.store(ctx)
                val live = Transcripts.live(ctx)
                s.list().map { Row(it, it.id == live, s.bytes(it.id)) }
            }
            delay(3_000)
        }
    }
    LaunchedEffect(query, rows?.size) {
        val q = query.trim()
        if (q.length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(250)
        hits = withContext(Dispatchers.IO) {
            val s = Transcripts.store(ctx)
            TranscriptIndex.search(q, s.list().mapNotNull { m -> s.load(m.id)?.let { m to it } })
        }
    }
    Scaffold(
        topBar = {
            BackBar("Transcripts", onBack) {
                if (!rows.isNullOrEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Delete all") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                try { ctx.startActivity(Transcripts.startIntent(ctx, app.heyrobot.core.ConvEntry.APP.name)) } catch (_: Exception) {}
            }, icon = { Icon(RIcons.Mic, null) }, text = { Text("Transcribe") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Text("Conversations transcribed on this phone. They stay here unless you export or share them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search the words") },
                leadingIcon = { Icon(RIcons.Search, null) }, modifier = Modifier.fillMaxWidth())
            val list = rows
            when {
                list == null -> {}
                query.trim().length >= 2 -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp, horizontal = 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hits.isEmpty()) item { Text("Nothing found.", style = MaterialTheme.typography.bodyMedium) }
                    items(hits, key = { "${it.transcriptId}/${it.line}" }) { h ->
                        val m = list.firstOrNull { it.meta.id == h.transcriptId }?.meta
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(h.transcriptId, h.line) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(m?.let { TranscriptTitles.shown(it) } ?: "Transcript", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(h.snippet, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(RIcons.Message, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text("No transcripts yet", style = MaterialTheme.typography.titleMedium)
                        Text("Tap Transcribe, or say “hey robot, transcribe this conversation”.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.meta.id }) { r ->
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(r.meta.id, null) }) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(TranscriptTitles.shown(r.meta), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val parts = listOfNotNull(
                                    DateUtils.getRelativeTimeSpanString(r.meta.createdAt).toString(),
                                    TranscriptTitles.duration(durationOf(r.meta, null)).takeIf { r.meta.endedAt > r.meta.createdAt },
                                    statusText(r.meta, r.live),
                                )
                                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("Delete all transcripts?") },
        text = { Text("This removes every transcript from this phone, and stops one being recorded. It can't be undone. (Deleting on flash storage isn't a secure erase.)") },
        confirmButton = {
            TextButton(onClick = {
                confirmAll = false
                scope.launch {
                    val n = withContext(Dispatchers.IO) { Transcripts.delete(ctx, null) }
                    tick++
                    snackbar.showSnackbar(if (n == 1) "1 transcript deleted." else "$n transcripts deleted.")
                }
            }) { Text("Delete all") }
        },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
    )
}

/** Lines and gap markers in time order. */
private fun timeline(t: Transcript): List<Any> = (t.lines.map { it.t0 to it } + t.gaps.map { it.t0 to it }).sortedBy { it.first }.map { it.second }

@Composable
fun TranscriptScreen(id: String, focusLine: Int?, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf<TranscriptMeta?>(null) }
    var t by remember { mutableStateOf<Transcript?>(null) }
    var gone by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    LaunchedEffect(id, tick) {
        while (true) {
            val (m, tr, l) = withContext(Dispatchers.IO) {
                val s = Transcripts.store(ctx)
                Triple(s.meta(id), s.load(id), Transcripts.live(ctx) == id)
            }
            meta = m
            t = tr
            live = l
            gone = m == null
            // A transcript being recorded grows: read it again every few seconds.
            if (!l) break
            delay(3_000)
        }
    }

    fun shown(m: TranscriptMeta) = m.copy(title = TranscriptTitles.shown(m, zone))

    fun export(kind: ExportKind, m: TranscriptMeta, tr: Transcript): String =
        if (kind == ExportKind.TEXT) TranscriptExport.text(shown(m), tr, ExportOptions(), zone) else TranscriptExport.markdown(shown(m), tr, ExportOptions(), zone)

    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let { save(ctx, scope, snackbar, it, meta, t, ExportKind.MARKDOWN, ::export) } }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { save(ctx, scope, snackbar, it, meta, t, ExportKind.TEXT, ::export) } }

    Scaffold(
        topBar = {
            BackBar(meta?.let { TranscriptTitles.shown(it, zone) } ?: "Transcript", onBack) {
                val m = meta
                val tr = t
                if (m != null && tr != null) {
                    IconButton(onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { Exports.write(ctx, TranscriptExport.fileName(m, "md", zone), export(ExportKind.MARKDOWN, m, tr)) }
                            val uri = FileProvider.getUriForFile(ctx, Exports.AUTHORITY, f)
                            val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, TranscriptTitles.shown(m, zone)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            Transcripts.log(ctx, VoiceEvent.CONV_EXPORT, ExportKind.SHARE.id)
                            // One line on the share sheet: what you send leaves Robot's care.
                            ctx.startActivity(Intent.createChooser(send, "Share the transcript (the app you pick keeps its own copy)"))
                        }
                    }) { Icon(RIcons.Share, "Share") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(RIcons.Settings, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, enabled = !live, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Save as Markdown (.md)") }, onClick = { menu = false; saveMd.launch(TranscriptExport.fileName(m, "md", zone)) })
                            DropdownMenuItem(text = { Text("Save as text (.txt)") }, onClick = { menu = false; saveTxt.launch(TranscriptExport.fileName(m, "txt", zone)) })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = {
                                menu = false
                                val clip = ClipData.newPlainText(TranscriptTitles.shown(m, zone), export(ExportKind.TEXT, m, tr))
                                // Other people's words: kept out of the clipboard's preview.
                                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                                Transcripts.log(ctx, VoiceEvent.CONV_EXPORT, ExportKind.COPY.id)
                            })
                            DropdownMenuItem(text = { Text(if (live) "Stop and delete" else "Delete") }, onClick = { menu = false; confirm = true })
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val m = meta
        val tr = t
        if (gone || m == null || tr == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(if (gone) "This transcript was deleted." else "") }
            return@Scaffold
        }
        val all = remember(tr) { timeline(tr) }
        val list = rememberLazyListState()
        LaunchedEffect(focusLine, all.size) {
            val k = focusLine?.let { f -> all.indexOfFirst { it is TranscriptLine && it.i == f } } ?: -1
            if (k >= 0) list.scrollToItem(k + 1)
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val langs = tr.langs.joinToString(", ") { TranscriptExport.langName(it) }.ifEmpty { "none yet" }
                val parts = listOfNotNull(
                    DateUtils.formatDateTime(ctx, m.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                    TranscriptTitles.duration(durationOf(m, tr)),
                    if (tr.lines.size == 1) "1 line" else "${tr.lines.size} lines",
                    langs,
                    statusText(m, live),
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${TranscriptExport.CREDIT} Nothing leaves the phone unless you share or export it.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(all.size) { k ->
                when (val x = all[k]) {
                    is TranscriptGap -> Text(TranscriptExport.gapText(m, x, zone), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is TranscriptLine -> Column {
                        val one = tr.speakers.size <= 1 && tr.names.isEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(TranscriptExport.clock(m, x.t0, zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(if (one) "Speaker" else tr.nameOf(x.speaker), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(TranscriptExport.langTag(x), style = MaterialTheme.typography.labelMedium)
                            if (x.edited) {
                                Spacer(Modifier.width(8.dp))
                                Text("edited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(x.text, style = MaterialTheme.typography.bodyLarge,
                            color = if (x.i == focusLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(meta?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Title (empty: the date)") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Transcripts.live(ctx) != id && Transcripts.store(ctx).rename(id, name) }
                        tick++
                        if (!ok) snackbar.showSnackbar("It couldn't be renamed (it may be recording).")
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(if (live) "Stop and delete this transcript?" else "Delete this transcript?") },
        text = { Text("It is removed from this phone. Copies you shared or saved elsewhere stay where they are.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    withContext(Dispatchers.IO) { Transcripts.delete(ctx, id) }
                    onBack()
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

/** Writes the export to the file you chose (Android's file picker: no storage permission). */
private fun save(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, snackbar: SnackbarHostState, uri: Uri, meta: TranscriptMeta?, t: Transcript?, kind: ExportKind,
    export: (ExportKind, TranscriptMeta, Transcript) -> String) {
    val m = meta ?: return
    val tr = t ?: return
    scope.launch {
        val ok = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(export(kind, m, tr).toByteArray(Charsets.UTF_8)) } != null
            } catch (_: Exception) { false }
        }
        if (ok) Transcripts.log(ctx, VoiceEvent.CONV_EXPORT, kind.id)
        snackbar.showSnackbar(if (ok) "Saved." else "It couldn't be saved there.")
    }
}
