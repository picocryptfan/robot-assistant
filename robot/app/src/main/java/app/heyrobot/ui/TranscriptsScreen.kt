// SPDX-License-Identifier: GPL-3.0-only
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.heyrobot.R
import app.heyrobot.core.ExportKind
import app.heyrobot.core.ExportOptions
import app.heyrobot.core.SessionStatus
import app.heyrobot.core.SummaryCheckFor
import app.heyrobot.core.SummaryRecord
import app.heyrobot.core.Transcript
import app.heyrobot.core.TranscriptEdits
import app.heyrobot.core.TranscriptEvent
import app.heyrobot.core.TranscriptExport
import app.heyrobot.core.TranscriptGap
import app.heyrobot.core.TranscriptHit
import app.heyrobot.core.TranscriptIndex
import app.heyrobot.core.TranscriptLine
import app.heyrobot.core.TranscriptMeta
import app.heyrobot.core.TranscriptTitles
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.files.IntakeProblem
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.Exports
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.IntakeStep
import app.heyrobot.sys.Transcripts
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. The Transcripts library (docs/conversation-mode.md 5.1, 6.3, phase 5.5): the transcripts on this phone,
// newest first, a search over their words (accents and case ignored), one transcript to read, rename, export as
// Markdown or text (saved where you choose, or shared), copy, and delete; and "Delete all". It reads the files the
// voice process writes; the one being recorded can't be renamed until it stops, and deleting it stops the recording
// (the voice process is told, so it can't write it back). Speakers (phase 5.3): tap a line's chip to name the speaker
// (in this transcript only), merge them with another or say "this is me"; long-press a line to give it to someone else
// or split it; and the speakers who sounded alike when the session ended are offered as merges. Not while it records:
// then the voice process is the transcript's only writer, and its own screen makes these changes. Phase 5.4 and 5.6:
// each line's translation under it ("fast" for the fast engine), the summaries kept with the transcript (each with its
// label, its [14:03] references taking you to their lines, and Delete), and Summarize, which opens the summary screen in
// the voice process (Robot's model and the helpers' keys are reached from there only).

private data class Row(val meta: TranscriptMeta, val live: Boolean, val bytes: Long)

/** How a transcript stands, in a few words (a RUNNING one nobody records any more stopped unexpectedly). */
@Composable
private fun statusText(m: TranscriptMeta, live: Boolean): String? = when {
    live -> stringResource(R.string.conv_recording_now)
    m.status == SessionStatus.INTERRUPTED || m.status == SessionStatus.RUNNING -> stringResource(R.string.conv_stopped_unexpectedly)
    else -> null
}

private fun durationOf(m: TranscriptMeta, t: Transcript?): Long = when {
    m.endedAt > m.createdAt -> m.endedAt - m.createdAt
    t != null && t.lines.isNotEmpty() -> (t.lines.maxOf { it.t1 } * 1000).toLong()
    else -> 0
}

@Composable
fun TranscriptsScreen(snackbar: SnackbarHostState, onOpen: (String, Int?) -> Unit, onBack: () -> Unit) {
    NoRecentsPicture()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Row>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TranscriptHit>>(emptyList()) }
    var confirmAll by remember { mutableStateOf(false) }
    val resumed = resumed()
    // Read again every few seconds while in front: one may be being recorded, or have just stopped.
    LaunchedEffect(tick, resumed) {
        do {
            rows = withContext(Dispatchers.IO) {
                val s = Transcripts.store(ctx)
                val live = Transcripts.live(ctx)
                s.list().map { Row(it, it.id == live, s.bytes(it.id)) }
            }
            delay(3_000)
        } while (resumed)
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
            BackBar(stringResource(R.string.conv_transcripts), onBack) {
                if (!rows.isNullOrEmpty()) TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.conv_delete_all)) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                try { ctx.startActivity(Transcripts.startIntent(ctx, app.heyrobot.core.ConvEntry.APP.name)) } catch (_: Exception) {}
            }, icon = { Icon(RIcons.Mic, null) }, text = { Text(stringResource(R.string.conv_transcribe)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.conv_library_intro),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.conv_search_words)) },
                leadingIcon = { Icon(RIcons.Search, null) }, modifier = Modifier.fillMaxWidth())
            val list = rows
            when {
                list == null -> {}
                query.trim().length >= 2 -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp, horizontal = 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hits.isEmpty()) item { Text(stringResource(R.string.conv_nothing_found), style = MaterialTheme.typography.bodyMedium) }
                    items(hits, key = { "${it.transcriptId}/${it.line}" }) { h ->
                        val m = list.firstOrNull { it.meta.id == h.transcriptId }?.meta
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(h.transcriptId, h.line) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(m?.let { TranscriptTitles.shown(it, l = LocalLangs.current) } ?: stringResource(R.string.conv_transcript), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(h.snippet, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(RIcons.Message, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.conv_no_transcripts), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.conv_no_transcripts_hint), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.meta.id }) { r ->
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(r.meta.id, null) }) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(TranscriptTitles.shown(r.meta, l = LocalLangs.current), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                val parts = listOfNotNull(
                                    DateUtils.getRelativeTimeSpanString(r.meta.createdAt).toString(),
                                    TranscriptTitles.durationText(durationOf(r.meta, null)).str().takeIf { r.meta.endedAt > r.meta.createdAt },
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
        title = { Text(stringResource(R.string.conv_delete_all_title)) },
        text = { Text(stringResource(R.string.conv_delete_all_text)) },
        confirmButton = {
            TextButton(onClick = {
                confirmAll = false
                scope.launch {
                    val n = withContext(Dispatchers.IO) { Transcripts.delete(ctx, null) }
                    tick++
                    snackbar.showSnackbar(ctx.resources.getQuantityString(R.plurals.conv_deleted_count, n, n))
                }
            }) { Text(stringResource(R.string.conv_delete_all)) }
        },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.conv_cancel)) } },
    )
}

@Composable
private fun resumed(): Boolean {
    val st by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return st.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
}

/** Lines and gap markers in time order. */
/**
 * Recents keeps no picture of the app while a transcript or the list is on screen (other people's words, which a picture
 * would keep after you delete them). Counted, so moving from the list to a transcript doesn't turn it back on early.
 */
@Composable
private fun NoRecentsPicture() {
    val activity = LocalContext.current.let { c ->
        var x: android.content.Context? = c
        while (x is android.content.ContextWrapper && x !is android.app.Activity) x = x.baseContext
        x as? android.app.Activity
    }
    DisposableEffect(activity) {
        if (activity != null && noRecents++ == 0) activity.setRecentsScreenshotEnabled(false)
        onDispose { if (activity != null && --noRecents == 0) activity.setRecentsScreenshotEnabled(true) }
    }
}

private var noRecents = 0

private fun timeline(t: Transcript): List<Any> = (t.lines.map { it.t0 to it } + t.gaps.map { it.t0 to it }).sortedBy { it.first }.map { it.second }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptScreen(id: String, focusLine: Int?, snackbar: SnackbarHostState, onBack: () -> Unit) {
    NoRecentsPicture()
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
    var edit by remember { mutableStateOf<SpeakerEdit?>(null) }
    var summaries by remember { mutableStateOf<List<Pair<Int, SummaryRecord>>>(emptyList()) }
    var jump by remember { mutableStateOf<Int?>(null) }
    /** 1.4: a transcript saved to Robot Files that is a duplicate, waiting for your answer. */
    var duplicate by remember { mutableStateOf<IntakeStep.Duplicate?>(null) }
    val zone = ZoneId.systemDefault()
    /** Your changes to speakers, appended to the transcript's file here (main app) when nothing records it. */
    val edits = remember(id) {
        object : SpeakerActions {
            fun change(make: (Transcript, Int) -> TranscriptEvent?) {
                scope.launch {
                    val done = withContext(Dispatchers.IO) {
                        if (Transcripts.live(ctx) == id) null else Transcripts.store(ctx).edit(id, make) != null
                    }
                    tick++
                    if (done == null) snackbar.showSnackbar(ctx.getString(R.string.conv_being_recorded))
                }
            }

            override fun rename(speaker: String, name: String) = change { t, _ -> TranscriptEdits.rename(t, speaker, name) }
            override fun merge(from: String, to: String) = change { t, _ -> TranscriptEdits.merge(t, from, to) }
            override fun thisIsMe(speaker: String) = change { t, _ -> TranscriptEdits.me(t, speaker) }
            override fun move(i: Int, speaker: String) = change { t, _ -> TranscriptEdits.move(t, i, speaker) }
            override fun split(i: Int, at: Int, speaker: String?) = change { t, n -> TranscriptEdits.split(t, i, at, n, speaker) }
            override fun decline(from: String, to: String) {
                scope.launch {
                    withContext(Dispatchers.IO) { if (Transcripts.live(ctx) != id) Transcripts.store(ctx).decline(id, from, to) }
                    tick++
                }
            }
        }
    }
    val resumed = resumed()
    LaunchedEffect(id, tick, resumed) {
        while (true) {
            val (m, tr, l) = withContext(Dispatchers.IO) {
                val s = Transcripts.store(ctx)
                summaries = s.summaries(id)
                Triple(s.meta(id), s.load(id), Transcripts.live(ctx) == id)
            }
            meta = m
            t = tr
            live = l
            gone = m == null
            // A transcript being recorded grows: read it again every few seconds while in front.
            if (!l || !resumed) break
            delay(3_000)
        }
    }

    // Exports and titles in the menus' language, as the screen shows them.
    val langs = LocalLangs.current

    fun shown(m: TranscriptMeta) = m.copy(title = TranscriptTitles.shown(m, zone, langs))

    fun export(kind: ExportKind, m: TranscriptMeta, tr: Transcript): String =
        if (kind == ExportKind.TEXT) TranscriptExport.text(shown(m), tr, ExportOptions(), zone, langs)
        else TranscriptExport.markdown(shown(m), tr, ExportOptions(), zone, langs)

    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let { save(ctx, scope, snackbar, it, meta, t, ExportKind.MARKDOWN, ::export) } }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { save(ctx, scope, snackbar, it, meta, t, ExportKind.TEXT, ::export) } }

    Scaffold(
        topBar = {
            BackBar(meta?.let { TranscriptTitles.shown(it, zone, langs) } ?: stringResource(R.string.conv_transcript), onBack) {
                val m = meta
                val tr = t
                if (m != null && tr != null) {
                    IconButton(onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { Exports.write(ctx, TranscriptExport.fileName(m, "md", zone, langs), export(ExportKind.MARKDOWN, m, tr)) }
                            val uri = FileProvider.getUriForFile(ctx, Exports.AUTHORITY, f)
                            val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, TranscriptTitles.shown(m, zone, langs)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            Transcripts.log(ctx, VoiceEvent.CONV_EXPORT, ExportKind.SHARE.id)
                            // One line on the share sheet: what you send leaves Robot's care.
                            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.conv_share_chooser)))
                        }
                    }) { Icon(RIcons.Share, stringResource(R.string.conv_share)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(RIcons.MoreVert, stringResource(R.string.conv_more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.conv_summarize)) }, enabled = tr.lines.isNotEmpty(), onClick = {
                                menu = false
                                try { ctx.startActivity(Transcripts.summaryIntent(ctx, id)) } catch (_: Exception) {}
                            })
                            // Not while it records, as Rename: the session's own copy of meta.json is written back when it ends.
                            DropdownMenuItem(text = { Text(stringResource(if (m.important) R.string.conv_not_important else R.string.conv_mark_important)) },
                                enabled = !live, onClick = {
                                menu = false
                                scope.launch {
                                    withContext(Dispatchers.IO) { Transcripts.store(ctx).setImportant(id, !m.important) }
                                    tick++
                                }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conv_rename)) }, enabled = !live, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conv_save_md)) },
                                onClick = { menu = false; saveMd.launch(TranscriptExport.fileName(m, "md", zone, langs)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conv_save_txt)) },
                                onClick = { menu = false; saveTxt.launch(TranscriptExport.fileName(m, "txt", zone, langs)) })
                            // 1.4: into Robot Files' Inbox, as its Markdown export (not while it records, as Rename).
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_save_transcript)) }, enabled = !live, onClick = {
                                menu = false
                                scope.launch {
                                    val name = TranscriptExport.fileName(m, "md", zone, langs)
                                    val md = export(ExportKind.MARKDOWN, m, tr)
                                    val step = withContext(Dispatchers.IO) {
                                        FilesIntake.gateText(FilesIntake.gate(ctx))?.let { IntakeStep.Failed(IntakeProblem.LOCKED) to it }
                                            ?: (FilesIntake.phone(ctx).addTranscript(name, md) to null)
                                    }
                                    savedToFiles(ctx, snackbar, step.first, step.second, langs) { duplicate = it }
                                }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conv_copy)) }, onClick = {
                                menu = false
                                val clip = ClipData.newPlainText(TranscriptTitles.shown(m, zone, langs), export(ExportKind.TEXT, m, tr))
                                // Other people's words: kept out of the clipboard's preview.
                                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                                Transcripts.log(ctx, VoiceEvent.CONV_EXPORT, ExportKind.COPY.id)
                            })
                            DropdownMenuItem(text = { Text(stringResource(if (live) R.string.conv_stop_and_delete else R.string.conv_delete)) },
                                onClick = { menu = false; confirm = true })
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
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(if (gone) stringResource(R.string.conv_deleted) else "") }
            return@Scaffold
        }
        val all = remember(tr) { timeline(tr) }
        val list = rememberLazyListState()
        // Speakers who sounded alike when the session ended: offered, one at a time, never merged by themselves.
        val similar = m.similar.firstOrNull { (a, b) -> a in tr.speakers && b in tr.speakers }?.takeIf { !live }
        val focus = jump ?: focusLine
        LaunchedEffect(focus, all.size) {
            val k = focus?.let { f -> all.indexOfFirst { it is TranscriptLine && it.i == f } } ?: -1
            // After the header, the summaries and the merge card when there is one.
            if (k >= 0) list.scrollToItem(k + 1 + summaries.size + if (similar != null) 1 else 0)
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val spoken = tr.langs.joinToString(", ") { TranscriptExport.langNameText(it).render(langs) }.ifEmpty { stringResource(R.string.conv_none_yet) }
                val parts = listOfNotNull(
                    DateUtils.formatDateTime(ctx, m.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                    TranscriptTitles.durationText(durationOf(m, tr)).str(),
                    pluralStringResource(R.plurals.conv_lines, tr.lines.size, tr.lines.size),
                    spoken,
                    statusText(m, live),
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.conv_credit_nothing_leaves, TranscriptExport.credit.str()), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The summaries kept with it, newest first: the label says who made it; [14:03] goes to that line.
            for ((n, r) in summaries.asReversed()) item(key = "summary$n") { // l10n: not text (a list key)
                SummaryCard(r, m, tr, zone, onLine = { jump = it }, onDelete = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Transcripts.store(ctx).deleteSummary(id, n) }
                        tick++
                    }
                })
            }
            if (similar != null) item(key = "similar") {
                SimilarCard(SpeakerView.of(tr), similar.first, similar.second, onMerge = { edits.merge(similar.first, similar.second) },
                    onDecline = { edits.decline(similar.first, similar.second) })
            }
            items(all.size) { k ->
                val changeWho = stringResource(R.string.conv_change_who)
                when (val x = all[k]) {
                    is TranscriptGap -> Text(TranscriptExport.gapPhrase(m, x, zone).str(), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is TranscriptLine -> Column(if (!live) Modifier.combinedClickable(onClick = {}, onLongClick = { edit = SpeakerEdit.LineMenu(x) },
                        onLongClickLabel = changeWho) else Modifier) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(TranscriptExport.clock(m, x.t0, zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            SpeakerChip(x.speaker, if (tr.oneVoice) stringResource(R.string.conv_speaker) else tr.nameText(x.speaker).str(),
                                if (!live) ({ edit = SpeakerEdit.Menu(x.speaker) }) else null)
                            Spacer(Modifier.width(8.dp))
                            Text(TranscriptExport.langTag(x), style = MaterialTheme.typography.labelMedium)
                            if (x.edited) {
                                Spacer(Modifier.width(8.dp))
                                Text(ConversationStrings.edited().str(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(x.text, style = MaterialTheme.typography.bodyLarge,
                            color = if (x.i == focus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        for (tl in x.translations.values) Row(verticalAlignment = Alignment.CenterVertically) {
                            if (tl.fast) {
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                    Text(ConversationStrings.fast().str(), Modifier.padding(horizontal = 5.dp), style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(tl.text, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    t?.let { tr -> SpeakerEditDialog(edit.takeIf { !live }, SpeakerView.of(tr), edits) { edit = it } }
    if (renaming) {
        var name by remember { mutableStateOf(meta?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.conv_rename)) },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.conv_title_hint)) }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Transcripts.live(ctx) != id && Transcripts.store(ctx).rename(id, name) }
                        tick++
                        if (!ok) snackbar.showSnackbar(ctx.getString(R.string.conv_rename_failed))
                    }
                }) { Text(stringResource(R.string.conv_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.conv_cancel)) } },
        )
    }
    duplicate?.let { d ->
        AlertDialog(
            onDismissRequest = {},
            text = { Text(FilesStrings.duplicate("where" to Arg.Sub(
                FilesStrings.where(d.of.drawer, d.of.name))).render(langs)) },
            confirmButton = {
                TextButton(onClick = {
                    duplicate = null
                    scope.launch {
                        val step = withContext(Dispatchers.IO) { FilesIntake.phone(ctx).anyway(d) }
                        savedToFiles(ctx, snackbar, step, null, langs) { duplicate = it }
                    }
                }) { Text(stringResource(R.string.files_add_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    duplicate = null
                    scope.launch { withContext(Dispatchers.IO) { FilesIntake.phone(ctx).drop(d) } }
                }) { Text(stringResource(R.string.files_dont_add)) }
            },
        )
    }
    // The page goes with a duplicate still unanswered: its staged copy goes too.
    DisposableEffect(Unit) {
        onDispose { duplicate?.let { d -> Thread({ FilesIntake.phone(ctx).drop(d) }, "robot-files-transcript").start() } }  // l10n: not text (a thread's name)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(if (live) R.string.conv_stop_delete_title else R.string.conv_delete_title)) },
        text = { Text(stringResource(R.string.conv_delete_text)) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    withContext(Dispatchers.IO) { Transcripts.delete(ctx, id) }
                    onBack()
                }
            }) { Text(stringResource(R.string.conv_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.conv_cancel)) } },
    )
}

/** One summary kept with the transcript: its label, its text with [14:03] links to the lines, and Delete. */
@Composable
private fun SummaryCard(r: SummaryRecord, m: TranscriptMeta, t: Transcript, zone: ZoneId, onLine: (Int) -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.label(zone, LocalLangs.current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val links = SummaryCheckFor.links(r.text, m, t, zone)
            val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
            Text(buildAnnotatedString {
                var at = 0
                for ((range, line) in links) {
                    append(r.text.substring(at, range.first))
                    withLink(LinkAnnotation.Clickable("line$line", style) { onLine(line) }) { append(r.text.substring(range)) } // l10n: not text (a link's tag)
                    at = range.last + 1
                }
                append(r.text.substring(at))
            }, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.conv_delete_summary)) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.conv_delete_summary_title)) },
        text = { Text(stringResource(R.string.conv_delete_summary_text)) },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(stringResource(R.string.conv_delete)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.conv_cancel)) } },
    )
}

/**
 * 1.4: what saving a transcript to Robot Files came to, in the snackbar: added, a duplicate to ask about ([ask]), or why not
 * ([gate]: the phone locked, a phone lock, Robot Files off).
 */
private suspend fun savedToFiles(ctx: Context, snackbar: SnackbarHostState, step: IntakeStep, gate: Phrase?,
    langs: Langs, ask: (IntakeStep.Duplicate) -> Unit) {
    when (step) {
        is IntakeStep.Added -> {
            Cmd.send(ctx, Cmd.FILES)
            snackbar.showSnackbar(FilesStrings.addedOne().render(langs))
        }
        is IntakeStep.Duplicate -> ask(step)
        is IntakeStep.Failed -> snackbar.showSnackbar((gate ?: FilesStrings.problem(step.problem, step.need)).render(langs))
    }
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
        snackbar.showSnackbar(ctx.getString(if (ok) R.string.conv_saved_there else R.string.conv_not_saved_there))
    }
}
