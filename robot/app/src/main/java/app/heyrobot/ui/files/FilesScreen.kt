// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.files

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.R
import app.heyrobot.core.files.DrawerRow
import app.heyrobot.core.files.Drawers
import app.heyrobot.core.files.FieldBy
import app.heyrobot.core.files.FileDeleteCode
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileMoveCode
import app.heyrobot.core.files.FileOut
import app.heyrobot.core.files.FileRuleCode
import app.heyrobot.core.files.FilingRule
import app.heyrobot.core.files.FilingRules
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.files.FileTags
import app.heyrobot.core.files.FilePack
import app.heyrobot.core.files.PackKind
import app.heyrobot.core.files.FiledBy
import app.heyrobot.core.files.FilingRun
import app.heyrobot.core.files.FilingStep
import app.heyrobot.core.files.FilingWhy
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.OutPlan
import app.heyrobot.core.memory.MemoryFiling
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.OutgoingCopy
import app.heyrobot.sys.FileDeletes
import app.heyrobot.core.files.PendingDeletes
import app.heyrobot.sys.FilePixels
import app.heyrobot.sys.FilingMemory
import app.heyrobot.sys.OutCopy
import app.heyrobot.sys.OutFailure
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureDialog
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.RobotTokens
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

// MAIN PROCESS. Robot Files' screens (files-1.4-plan.md §6.2 to §6.5, D17, D19; job F7): the Files tab (Q1: in the Model
// tab's place), its Inbox with Robot's questions as chips and Ask me first's "File them", Recently filed with Undo and Move,
// the starter drawers and yours, a drawer's files with their totals, a file's page (its pages, name, fields, drawer, tags,
// people, where it came from; Share, Open with another app, Delete) and search. What they show and change is core
// FileViews' and FilingRun's; the phone is behind [FilesPort].
//
//   - FLAG_SECURE on all of it (SecureWindow), and every dialog and menu secure too (each is a window of its own).
//   - The lock gate: the phone locked or a phone lock on, checked when the screen comes back and every second while it
//     shows (a phone lock leaves Robot's own screens open), and before every change. Locked, nothing is shown: the index
//     and every page and thumbnail leave the screen, :files' link ends, any copy handed out is taken back and a delete
//     waiting for its Undo is done.
//   - The index is read off the main thread, only once this tab shows (never at the app's start: RobotFiles.use).
//   - Delete (Q2): a tap, then its dialog; the file is gone from every list at once, with Undo for 10 seconds; then (or
//     when you leave, Robot goes to the background, or the phone locks) its key is destroyed (core FileStore.delete). A
//     crash within those 10 seconds keeps the file.
//   - The page stack is saved as ids only (file ids and starter drawers' ids); a search is never saved. An open dialog
//     (rename, a field, a tag, a drawer's name) keeps what you typed across a recreation, as Settings › Memory's do.

/** A page of the Files tab. Your drawers are named by their position, so a saved stack never holds their names. */
sealed class FilesView {
    data object Home : FilesView()
    data class Drawer(val id: String) : FilesView()
    data class File(val id: FileId) : FilesView()
    /** F12: a tax pack's or an expense report's page for [year]. */
    data class Pack(val kind: PackKind, val year: Int) : FilesView()

    companion object {
        /** The stack survives a recreation as ids only ("d:receipts", "f:<hex>"); your drawers' pages aren't kept. */
        val Saver: Saver<List<FilesView>, String> = Saver(
            save = { list -> list.mapNotNull { v -> when (v) {
                Home -> null
                is Drawer -> if (v.id in Drawers.STARTER) "d:${v.id}" else null // l10n: not text (saved route)
                is File -> "f:${v.id.hex}" // l10n: not text (saved route)
                is Pack -> "p:${v.kind.name}:${v.year}" // l10n: not text (saved route)
            } }.joinToString("\n") },
            restore = { s -> s.split('\n').mapNotNull { l -> when {
                l.startsWith("d:") -> l.removePrefix("d:").takeIf { it in Drawers.STARTER }?.let(::Drawer)
                l.startsWith("f:") -> FileId.of(l.removePrefix("f:"))?.let(::File)
                l.startsWith("p:") -> l.split(':').let { p -> PackKind.entries.firstOrNull { it.name == p.getOrNull(1) }?.let { k -> p.getOrNull(2)?.toIntOrNull()?.let { y -> Pack(k, y) } } }
                else -> null
            } } },
        )
    }
}

/** Where deletes and copies run when the screen's own scope may be gone (leaving, the background, a lock). */
private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-files-screen").apply { isDaemon = true } }  // l10n: not text (a thread's name)

/** The Files tab on the phone (MainActivity). */
@Composable
fun FilesRoute(a: AppActions, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val port = remember { PhoneFilesPort(ctx) }
    FilesScreen(port, a, snackbar, modifier, launch = { i ->
        try { ctx.startActivity(i); true } catch (_: ActivityNotFoundException) { false }
    })
}

/**
 * The Files tab. [launch] starts a chooser (Share, Open with another app); [now] is the clock (Undo's 7 days); [undoMs]
 * how long a delete waits for its Undo (10 s).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(
    port: FilesPort,
    a: AppActions,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
    undoMs: Long = UNDO_DELETE_MS,
    deletes: PendingDeletes = FileDeletes.here,
    launch: (Intent) -> Boolean,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<IndexLoad?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var stack by rememberSaveable(stateSaver = FilesView.Saver) { mutableStateOf(emptyList<FilesView>()) }
    var query by remember { mutableStateOf("") }
    /** F8: your filing rules, read again when the index or memory changes (never held past the screen). */
    var rules by remember { mutableStateOf(emptyList<FilingRule>()) }
    /**
     * Deleted, their Undo still running: out of every list. FX2 (files-1.4-review.md K1): this process's one holder (core
     * PendingDeletes, sys/FileDeletes), which Settings › Files' clean-up uses too.
     */
    var hidden by remember { mutableStateOf(deletes.hidden()) }
    DisposableEffect(deletes) {
        val stop = deletes.watch { h -> hidden = h }
        onDispose { stop() }
    }
    // Copies handed to another app: the questions before one goes.
    var askStrip by remember { mutableStateOf<Pair<FileEntry, Boolean>?>(null) }
    var askOpen by remember { mutableStateOf<FileEntry?>(null) }

    fun say(text: String) = a.say(text)

    /** Deletes what waits for its Undo now (leaving, the background, a lock): off the main thread, whatever happens to this screen. */
    fun commitDeletes() = deletes.commitAll()

    /** Nothing of the files stays on the screen: the index, the search, the questions open on a file. */
    fun forget(to: IndexLoad?) {
        load = to
        query = ""
        askOpen = null
        askStrip = null
    }

    /** Locked or gone: nothing of the files stays on the screen or in :files, and what waits is done. */
    fun close() {
        commitDeletes()
        forget(IndexLoad.Locked)
        // A delete's Undo, a move's: too late now.
        snackbar.currentSnackbarData?.dismiss()
        work.execute {
            port.endReader()
            port.takeBack()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    val resumed = lstate.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> {
                    commitDeletes()
                    // In the background nothing of the files is kept on the screen: coming back reads it again, past the gate
                    // (a phone lock may have come on meanwhile).
                    forget(null)
                    port.stopped()
                    work.execute { port.endReader() }
                }
                Lifecycle.Event.ON_RESUME -> work.execute { port.resumed() }
                else -> {}
            }
        }
        lifecycle.addObserver(o)
        onDispose {
            lifecycle.removeObserver(o)
            // Leaving the tab: the deletes waiting go through, the reader ends, copies handed out come back.
            commitDeletes()
            work.execute {
                port.endReader()
                port.takeBack()
            }
        }
    }
    // The gate and the index: when the screen comes back, every second while it shows, and after each change.
    LaunchedEffect(resumed, tick) {
        while (resumed) {
            val l = withContext(Dispatchers.IO) { if (port.locked()) null else port.load() }
            if (l == null || l == IndexLoad.Locked) {
                if (load != IndexLoad.Locked) close()
            } else if (l !== load) {
                load = l
                rules = withContext(Dispatchers.IO) { port.rules() }
            }
            delay(1_000)
        }
    }

    /** One change of the index ([f] null: nothing to change), the gate checked first; [done] gets whether it applied. */
    fun change(f: (FileIndex) -> FileIndex?, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (port.locked()) return@withContext null
                var applied = false
                val l = port.update { idx -> f(idx)?.also { applied = true } ?: idx }
                if (l.current == null) null else l to applied
            }
            if (r == null) {
                say(ctx.getString(R.string.files_locked))
                tick++
                return@launch
            }
            load = r.first
            if (r.second) port.changed()
            done(r.second)
        }
    }

    /** A change with Undo on the snackbar: [undo] puts it back when tapped. */
    fun withUndo(message: String, undo: (FileIndex) -> FileIndex?, undone: String, code: FileMoveCode) {
        scope.launch {
            val r = snackbar.showSnackbar(message, FilesStrings.undo().render(langsOf(ctx)), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) change(undo) { ok ->
                if (ok) port.logMove(code, 1)
                say(if (ok) undone else FilesStrings.notUndone().render(langsOf(ctx)))
            }
        }
    }

    /**
     * F8 (§4.5, the 12 Oct decision): a rule your move or your tags taught, saved at once and confirmed once: "Got it: from
     * now on, Esso receipts go in Car." Its Undo drops the rule and keeps what you did. When it isn't kept (Memory off or
     * full, the same rule already, memory closed), [otherwise] says what a plain move or tag says ([full]: Memory is full).
     */
    fun learn(rule: MemoryFiling, dropped: String, otherwise: (full: Boolean) -> Unit) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { if (port.locked()) null else port.saveRule(rule) }
            when (saved) {
                is FilingMemory.Saved.Kept -> {
                    port.logRule(FileRuleCode.SAVED)
                    rules = withContext(Dispatchers.IO) { port.rules() }
                    val langs = langsOf(ctx)
                    val r = snackbar.showSnackbar(FilesStrings.ruleSaved("rule" to FilingRules.clause(rule, langs.ui)).render(langs),
                        FilesStrings.undo().render(langs), duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) {
                        val ok = withContext(Dispatchers.IO) { !port.locked() && port.undoRule(saved) }
                        if (ok) port.logRule(FileRuleCode.UNDONE)
                        rules = withContext(Dispatchers.IO) { port.rules() }
                        say(if (ok) dropped else ctx.getString(R.string.files_not_now))
                    }
                }
                is FilingMemory.Saved.Already -> { port.logRule(FileRuleCode.ALREADY); otherwise(false) }
                FilingMemory.Saved.Off -> { port.logRule(FileRuleCode.OFF); otherwise(false) }
                FilingMemory.Saved.Full -> { port.logRule(FileRuleCode.FULL); otherwise(true) }
                FilingMemory.Saved.NotOpen, null -> { port.logRule(FileRuleCode.NOT_OPEN); otherwise(false) }
            }
        }
    }

    fun move(e: FileEntry, drawer: String) {
        val was = e
        val taught = FilingRules.fromMove(was, drawer, rules)
        change({ idx -> FilingRun.move(idx, e.id, drawer, now()) }) { ok ->
            if (!ok) return@change say(ctx.getString(R.string.files_not_now))
            port.logMove(FileMoveCode.MOVED, 1)
            val moved = FilesStrings.moved("drawer" to FilesStrings.drawer(drawer))
            val plain = { full: Boolean ->
                val said = if (!full) moved else app.heyrobot.core.i18n.Phrase.join(moved,
                    FilesStrings.ruleMemoryFull("page" to app.heyrobot.core.i18n.Arg.Ui(app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.memory))))
                withUndo(said.render(langsOf(ctx)), { idx -> FilingRun.restore(idx, e.id, was) }, FilesStrings.putBack().render(langsOf(ctx)), FileMoveCode.PUT_BACK)
            }
            if (taught == null) plain(false) else learn(taught, FilesStrings.ruleDropped().render(langsOf(ctx)), plain)
        }
    }

    /** You added [tag] to [e]: the second file of its merchant with it teaches a rule (§4.5). */
    fun tagged(e: FileEntry, tag: String) {
        val idx = load?.current ?: return
        val t = FileTags.of(tag)?.takeIf { it in idx.entry(e.id)?.tags.orEmpty() } ?: return
        val taught = FilingRules.fromTag(idx, e.id, t, rules) ?: return
        learn(taught, FilesStrings.ruleDroppedSaid().render(langsOf(ctx))) { full ->
            if (full) say(FilesStrings.ruleMemoryFull("page" to app.heyrobot.core.i18n.Arg.Ui(app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.memory))).render(langsOf(ctx)))
        }
    }

    fun delete(e: FileEntry) {
        scope.launch {
            // Checked again at the tap: the phone may have locked while the dialog showed.
            if (withContext(Dispatchers.IO) { port.locked() }) return@launch say(ctx.getString(R.string.files_locked))
            // FX2 (K1): the holder deletes it when its Undo runs out, or sooner (leaving, the background, a lock).
            val token = deletes.start(listOf(e.id), undoMs, delete = { ids -> port.delete(ids) }) { ok ->
                port.logDelete(if (ok) FileDeleteCode.DELETED else FileDeleteCode.NOT_NOW, 1)
                port.changed()
            }
            work.execute { port.takeBack(e.id) }
            if (stack.lastOrNull() == FilesView.File(e.id)) stack = stack.dropLast(1)
            val r = withTimeoutOrNull(undoMs) {
                snackbar.showSnackbar(ctx.getString(R.string.files_deleted), FilesStrings.undo().render(langsOf(ctx)), duration = SnackbarDuration.Indefinite)
            }
            if (r == SnackbarResult.ActionPerformed) {
                if (deletes.undo(token)) {
                    port.logDelete(FileDeleteCode.UNDONE, 1)
                    say(ctx.getString(R.string.files_undeleted))
                } else {
                    // Its key was already destroyed (Robot went to the background, or the phone locked): nothing to bring back.
                    say(FilesStrings.notUndone().render(langsOf(ctx)))
                }
            }
        }
    }

    fun handOut(e: FileEntry, open: Boolean, allowOriginal: Boolean) {
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                if (port.locked()) return@withContext OutCopy.Failed(OutFailure.LOCKED) to null
                val type = port.type(e)
                val dangerous = type.group == FileGroup.DANGEROUS || e.kind == FileKind.DANGEROUS
                when (val plan = FileOut.plan(e, type, port.settings().stripLocation)) {
                    is OutPlan.Go -> port.copy(e, plan.copy, plan.name, plan.mime, dangerous) to null
                    is OutPlan.AskFirst -> if (allowOriginal) port.copy(e, OutgoingCopy.ORIGINAL, plan.name, plan.mime, dangerous) to null else null to plan
                }
            }
            val (c, ask) = made
            if (ask != null) {
                askStrip = e to open
                return@launch
            }
            when (c) {
                is OutCopy.Ready -> {
                    val title = ctx.getString(if (open) R.string.files_open_with_title else R.string.files_share)
                    if (!launch(port.handOut(c, open, title))) {
                        work.execute { port.takeBack(e.id) }
                        say(ctx.getString(R.string.files_no_app))
                    } else if (open && !e.from.opened) change({ idx -> FileViews.opened(idx, e.id) })
                }
                is OutCopy.Failed -> say(ctx.getString(when (c.why) {
                    OutFailure.LOCKED -> R.string.files_locked
                    OutFailure.LOW_SPACE -> R.string.files_out_low_space
                    OutFailure.NO_COPY, OutFailure.FAILED -> R.string.files_out_failed
                }))
                null -> {}
            }
        }
    }

    // F12: a tax pack or an expense report goes out only after your tap on Export, to the folder you pick here. The picker
    // is registered on this screen (never on the pack's page, which leaves while Robot is in the background), and what goes
    // is worked out again behind the gate when the folder comes back.
    var exporting by remember { mutableStateOf<FilesView.Pack?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val what = exporting
        exporting = null
        if (tree == null || what == null) return@rememberLauncherForActivityResult
        exportBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { port.export(what.kind, what.year, tree) }
            exportBusy = false
            say(exportText(ctx, r))
            tick++
        }
    }
    fun export(p: FilesView.Pack) {
        scope.launch {
            // Checked at the tap: nothing goes while the phone is locked.
            if (withContext(Dispatchers.IO) { port.locked() }) return@launch say(ctx.getString(R.string.files_locked))
            exporting = p
            try { pickFolder.launch(null) } catch (_: ActivityNotFoundException) { exporting = null; say(ctx.getString(R.string.files_no_app)) }
        }
    }
    // F12: the panel's card for "make my 2026 tax pack" opens its page here.
    val asked by FilesRequests.pack.collectAsState()
    LaunchedEffect(asked) {
        val r = asked ?: return@LaunchedEffect
        FilesRequests.pack.value = null
        app.heyrobot.sys.AppPages.filesPack(r)?.let { (k, y) -> PackKind.entries.firstOrNull { it.name.lowercase() == k }?.let { stack = listOf(FilesView.Pack(it, y)) } }
    }

    SecureWindow()
    val view = stack.lastOrNull() ?: FilesView.Home
    BackHandler(enabled = stack.isNotEmpty() || query.isNotEmpty()) {
        if (stack.isNotEmpty()) stack = stack.dropLast(1) else query = ""
    }
    fun open(v: FilesView) { stack = stack + v }
    fun back() { stack = stack.dropLast(1) }

    val l = load
    val idx = l?.current
    when {
        l == null -> FilesMessage(stringResource(R.string.files_opening), modifier)
        idx == null -> FilesMessage(problemText(l), modifier,
            action = if (l == IndexLoad.Locked) null else stringResource(R.string.files_open_settings, UiStrings.inSettings(UiStrings.files).render(LocalLangs.current.ui)) to {
                a.openPage(Page.Settings(SettingsRoutes.FILES))
            })
        else -> when (view) {
            FilesView.Home -> HomePage(idx, hidden, port, now(), query, onQuery = { query = it }, modifier = modifier, a = a, rules = rules,
                onOpen = ::open, onChange = { f, d -> change(f, d) }, onMove = ::move,
                onUndo = { e ->
                    change({ i -> FilingRun.undo(i, e.id) }) { ok ->
                        if (ok) port.logMove(FileMoveCode.UNDO, 1)
                        say(if (ok) FilesStrings.backInInbox().render(langsOf(ctx)) else FilesStrings.notUndone().render(langsOf(ctx)))
                    }
                })
            // F12: a drawer travel mode hides isn't there (a saved page, a reveal that ended): back to where you were.
            is FilesView.Drawer -> if (view.id in idx.outOfSight) LaunchedEffect(view) { back() } else DrawerPage(idx, view.id, hidden, port, modifier, clock = now, onBack = ::back, onOpen = ::open,
                onChange = { f, d -> change(f, d) }, onRenamed = { new ->
                    // F8: your rules that name the drawer follow it (memory, off the main thread).
                    val old = view.id
                    work.execute { if (!port.locked()) { port.renameRules(old, new); port.renameHidden(old, new) } }
                    stack = stack.dropLast(1) + FilesView.Drawer(new)
                }, say = ::say)
            is FilesView.Pack -> PackPage(idx, view.kind, view.year, port, modifier, busy = exportBusy, onBack = ::back, onOpen = ::open,
                onYear = { y -> stack = stack.dropLast(1) + FilesView.Pack(view.kind, y) }, onExport = { export(view) })
            is FilesView.File -> {
                val e = idx.entry(view.id)?.takeIf { it.id !in hidden }
                if (e == null) LaunchedEffect(view) { back() }
                else FilePage(e, idx, port, modifier, onBack = ::back, onChange = { f, d -> change(f, d) }, onMove = ::move, rules = rules, onTagged = ::tagged,
                    onShare = { handOut(e, open = false, allowOriginal = false) }, onOpenWith = { askOpen = e }, onDelete = ::delete, a = a, clock = now,
                    onCalendar = { d ->
                        // F10: the calendar app's own editor, prefilled: you save the event there (Robot writes nothing to the calendar).
                        val langs = langsOf(ctx)
                        val i = port.calendar(app.heyrobot.core.files.FileMoneyText.eventTitle(d).render(langs), d.date)
                        if (i == null || !launch(i)) say(app.heyrobot.core.i18n.ToolStrings.noCalendarApp().render(langs))
                    })
            }
        }
    }

    askOpen?.let { e ->
        val dangerous = e.kind == FileKind.DANGEROUS
        SecureAlert(stringResource(R.string.files_open_title),
            stringResource(R.string.files_open_text) + if (dangerous) "\n\n" + stringResource(R.string.files_dangerous_open) else "", // l10n: not text (two paragraphs joined)
            confirm = stringResource(R.string.files_open), onDismiss = { askOpen = null }) {
            askOpen = null
            handOut(e, open = true, allowOriginal = false)
        }
    }
    askStrip?.let { (e, open) ->
        SecureAlert(stringResource(R.string.files_cant_strip_title), stringResource(R.string.files_cant_strip_text),
            confirm = stringResource(R.string.files_send_anyway), onDismiss = { askStrip = null }) {
            askStrip = null
            handOut(e, open, allowOriginal = true)
        }
    }
}

/** How long a delete waits for its Undo (Q2: 10 seconds; core PendingDeletes, FX2). */
const val UNDO_DELETE_MS = app.heyrobot.core.files.PendingDeletes.UNDO_MS

/** The app's languages, for words said outside composition (a snackbar's). */
private fun langsOf(ctx: android.content.Context) = app.heyrobot.sys.AppLanguage.langs(ctx)

@Composable
private fun problemText(l: IndexLoad): String = stringResource(when (l) {
    IndexLoad.KeyGone -> R.string.files_key_gone
    IndexLoad.Unreadable -> R.string.files_unreadable
    is IndexLoad.TooNew -> R.string.files_too_new
    else -> R.string.files_locked
})

/** A screen of one message (opening, locked, a problem), with a button when there is something to do. */
@Composable
private fun FilesMessage(text: String, modifier: Modifier, action: Pair<String, () -> Unit>? = null) {
    PreferenceScreen(UiStrings.files().str(), onBack = null, modifier = modifier) {
        item(key = "message") { IntroPreference(text) }
        if (action != null) item(key = "action") {
            Box(Modifier.padding(top = 8.dp)) { OutlinedButton(onClick = action.second) { Text(action.first) } }
        }
    }
}

// Home.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePage(
    idx: FileIndex, hidden: Set<FileId>, port: FilesPort, now: Long, query: String, onQuery: (String) -> Unit, modifier: Modifier, a: AppActions,
    onOpen: (FilesView) -> Unit, onChange: ((FileIndex) -> FileIndex?, (Boolean) -> Unit) -> Unit, onMove: (FileEntry, String) -> Unit, onUndo: (FileEntry) -> Unit,
    rules: List<FilingRule> = emptyList(),
) {
    val ctx = LocalContext.current
    val home = remember(idx, hidden) { FileViews.home(idx, hidden) }
    val found = remember(idx, hidden, query) { if (query.isBlank()) null else FileViews.search(idx, query, hidden) }
    var moving by remember { mutableStateOf<FileEntry?>(null) }
    var newDrawer by remember { mutableStateOf(false) }
    val needYou = pluralStringResource(R.plurals.files_need_you, home.needYou.size, home.needYou.size)
    val beingRead = stringResource(R.string.files_being_read)
    val recentTitle = stringResource(R.string.files_recent)
    val drawersTitle = stringResource(R.string.files_drawers)
    val exportTitle = stringResource(R.string.files_export)
    val inboxTitle = FilesStrings.inbox().str()
    val foundTitle = found?.let { pluralStringResource(R.plurals.files_found, it.size, it.size) }
    val scan = stringResource(R.string.files_scan_button)
    Box(modifier) {
        PreferenceScreen(UiStrings.files().str(), onBack = null, actions = {
            // Scanning (F5's screen): the tab's one action, in its bar so it never covers a row.
            TextButton(onClick = { a.openPage(Page.Settings(SettingsRoutes.FILES_SCAN)) }) {
                Icon(RIcons.Camera, null)
                Spacer(Modifier.width(8.dp))
                Text(scan)
            }
        }) {
            item(key = "search") { SearchField(query, onQuery) }
            if (found != null) {
                preferenceGroup(foundTitle, "found") {
                    if (found.isEmpty()) PreferenceBlock { Text(stringResource(R.string.files_search_none), style = MaterialTheme.typography.bodyLarge) }
                    for (e in found) FileRow(e, port, filedSummary(e)) { onOpen(FilesView.File(e.id)) }
                }
                return@PreferenceScreen
            }
            if (home.empty) item(key = "empty") { IntroPreference(stringResource(R.string.files_empty)) }
            if (home.inbox > 0) {
                if (home.needYou.isNotEmpty()) preferenceGroup("$inboxTitle · $needYou", "inbox") { // l10n: not text (two labels joined)
                    if (home.fileThem.size >= 2 || (home.fileThem.isNotEmpty() && home.fileThem.size == home.needYou.size)) PreferenceBlock {
                        Text(FilesStrings.newFiles(home.fileThem.size, "kinds" to Arg.Sub(FilesStrings.kinds(home.fileThem.map { it.kind }))).str(),
                            style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = {
                            val ids = home.fileThem.map { it.id }
                            onChange({ i0 ->
                                var i: FileIndex = i0
                                var any = false
                                for (id in ids) FilingRun.fileThem(i, id, port.settings(), rules, port.words(), System.currentTimeMillis())?.let { i = it; any = true }
                                if (any) i else null
                            }) { ok -> if (ok) port.logMove(FileMoveCode.FILE_THEM, ids.size) }
                        }) { Text(if (home.fileThem.size == 1) stringResource(R.string.files_file_it) else FilesStrings.fileThem().str()) }
                    }
                    for (e in home.needYou) InboxRow(e, port, onOpen = { onOpen(FilesView.File(e.id)) }, onChange = onChange, onMove = { moving = e }, rules = rules)
                }
                if (home.reading.isNotEmpty()) preferenceGroup("$inboxTitle · $beingRead", "reading") { // l10n: not text (two labels joined)
                    for (e in home.reading) FileRow(e, port, FilesStrings.why(e.filing?.why).str()) { onOpen(FilesView.File(e.id)) }
                }
            }
            if (home.recent.isNotEmpty()) preferenceGroup(recentTitle, "recent") {
                for (e in home.recent) FileBlock(e, port, e.drawer?.let { FilesStrings.drawer(it).str() }, onClick = { onOpen(FilesView.File(e.id)) }) {
                    if (FileViews.canUndo(e, now)) AssistChip(onClick = { onUndo(e) }, label = { Text(FilesStrings.undo().str()) })
                    AssistChip(onClick = { moving = e }, label = { Text(FilesStrings.move().str()) })
                }
            }
            preferenceGroup(drawersTitle, "drawers") {
                for (d in home.drawers) PreferenceItem(drawerTitle(d.id), pluralStringResource(R.plurals.files_count, d.count, d.count), RIcons.Folder,
                    onClick = { onOpen(FilesView.Drawer(d.id)) })
                PreferenceItem(stringResource(R.string.files_new_drawer), null, RIcons.Add, onClick = { newDrawer = true })
            }
            // F12 (§7.2): the tax pack and the expense report, last year's first; their page says what goes before anything does.
            if (!home.empty) preferenceGroup(exportTitle, "export") {
                val year = FilePack.defaultYear(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
                PreferenceItem(stringResource(R.string.files_pack_tax), stringResource(R.string.files_pack_tax_detail), RIcons.Folder,
                    onClick = { onOpen(FilesView.Pack(PackKind.TAX, year)) })
                PreferenceItem(stringResource(R.string.files_pack_expenses), stringResource(R.string.files_pack_expenses_detail), RIcons.Folder,
                    onClick = { onOpen(FilesView.Pack(PackKind.EXPENSES, year)) })
            }
        }
    }
    moving?.let { e ->
        MoveDialog(idx, e, onDismiss = { moving = null }, onNew = { moving = null; newDrawer = true }) { d -> moving = null; onMove(e, d) }
    }
    if (newDrawer) NewDrawerDialog(idx, onDismiss = { newDrawer = false }) { name ->
        newDrawer = false
        onChange({ i -> FileViews.addDrawer(i, name) }) { ok -> if (!ok) a.say(takenText(ctx, idx)) }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val hint = stringResource(R.string.files_search_hint)
    app.heyrobot.ui.common.NoImeLearning {
        OutlinedTextField(
            value = query,
            onValueChange = { onQuery(it.take(200)) },
            placeholder = { Text(hint) },
            leadingIcon = { Icon(RIcons.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics { contentDescription = hint },
        )
    }
}

/** A file in Inbox that needs you: Robot's question as chips (the kinds it weighs, Check the total, File it), or why it stays. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxRow(e: FileEntry, port: FilesPort, onOpen: () -> Unit, onChange: ((FileIndex) -> FileIndex?, (Boolean) -> Unit) -> Unit, onMove: () -> Unit,
    rules: List<FilingRule> = emptyList()) {
    val f = e.filing
    val why = when {
        e.kind == FileKind.DANGEROUS -> FilesStrings.dangerous().str()
        e.damaged -> stringResource(R.string.files_damaged)
        f?.step == FilingStep.KEPT -> null
        else -> FilesStrings.why(f?.why, f?.choices.orEmpty()).str()
    }
    FileBlock(e, port, why, onClick = onOpen) {
            when {
                e.kind == FileKind.DANGEROUS || e.damaged -> {}
                f?.step == FilingStep.ASK && f.why == FilingWhy.KIND -> {
                    val choices = f.choices.ifEmpty { app.heyrobot.core.files.Filer.DEFAULT_CHOICES }
                    for (k in choices) AssistChip(onClick = {
                        onChange({ i -> FilingRun.answer(i, e.id, k, port.settings(), rules, port.words(), System.currentTimeMillis()) }) { ok ->
                            if (ok) port.logMove(FileMoveCode.MOVED, 1)
                        }
                    }, label = { Text(kindNoun(k)) })
                }
                f?.step == FilingStep.ASK && f.why == FilingWhy.TOTAL -> AssistChip(onClick = onOpen, label = { Text(stringResource(R.string.files_check_total)) })
                f?.step == FilingStep.ASK && f.why == FilingWhy.FILE_THEM -> AssistChip(onClick = {
                    onChange({ i -> FilingRun.fileThem(i, e.id, port.settings(), rules, port.words(), System.currentTimeMillis()) }) { ok ->
                        if (ok) port.logMove(FileMoveCode.FILE_THEM, 1)
                    }
                }, label = { Text(stringResource(R.string.files_file_it)) })
                // F11: someone else's file, offered the drawer it was in there; your tap files it (no rule is learned from it).
                f?.step == FilingStep.ASK && f.why == FilingWhy.NOT_YOURS && f.suggest != null -> {
                    val drawer = f.suggest.orEmpty()
                    AssistChip(onClick = {
                        onChange({ i -> FilingRun.move(i, e.id, drawer, System.currentTimeMillis()) }) { ok -> if (ok) port.logMove(FileMoveCode.MOVED, 1) }
                    }, label = { Text(stringResource(R.string.files_file_in, drawerTitle(drawer))) })
                }
            }
            if (e.kind != FileKind.DANGEROUS && !e.damaged) AssistChip(onClick = onMove, label = { Text(FilesStrings.move().str()) })
    }
}

/**
 * A file with what you can do about it ([chips]: Robot's question, Undo, Move), in one block: the file (its thumbnail, name
 * and line, one target that opens it) and the chips under it, each a target of its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileBlock(e: FileEntry, port: FilesPort, summary: String?, onClick: () -> Unit, chips: @Composable () -> Unit) {
    PreferenceBlock {
        Row(Modifier.fillMaxWidth().heightIn(min = RobotTokens.minTouch).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically) {
            Thumb(e, port)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name.ifBlank { FilesStrings.noName().str() }, style = app.heyrobot.ui.pref.prefTitleStyle())
                if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(start = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

/** One file: its thumbnail (from :files' pixels) or an icon, its name and one line under it. */
@Composable
internal fun FileRow(e: FileEntry, port: FilesPort, summary: String?, onClick: () -> Unit) {
    PreferenceItem(e.name.ifBlank { FilesStrings.noName().str() }, summary, leading = { Thumb(e, port) }, onClick = onClick)
}

@Composable
private fun Thumb(e: FileEntry, port: FilesPort) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, e.id, e.thumb) {
        value = if (!e.thumb) null else withContext(Dispatchers.IO) { port.thumb(e)?.let { FilePixels.bitmap(it, thumb = true) } }
    }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(kindIcon(e), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun kindIcon(e: FileEntry) = when (e.kind) {
    FileKind.DANGEROUS -> RIcons.Warning
    FileKind.PHOTO, FileKind.VIDEO -> RIcons.Image
    FileKind.TRANSCRIPT -> RIcons.Transcribe
    else -> RIcons.Note
}

/** A filed file's line: "$84.12 · August 14, 2026", or its drawer. */
@Composable
internal fun filedSummary(e: FileEntry): String? {
    val lang = LocalLangs.current.ui
    val total = e.fields["total"]?.value?.let(app.heyrobot.core.files.FileFields::amountOf)?.let { Fmt.money(it, e.fields["currency"]?.value, lang) }
    val date = FileViews.dateField(e, "date")?.let { Fmt.date(it, lang) }
    val parts = listOfNotNull(total, date)
    return if (parts.isNotEmpty()) parts.joinToString(" · ") else e.drawer?.let { FilesStrings.drawer(it).str() } ?: FilesStrings.inbox().str() // l10n: not text (a separator)
}

@Composable
private fun drawerTitle(id: String): String = FilesStrings.drawer(id).str()

@Composable
private fun kindNoun(k: FileKind): String = stringResource(kindRes(k))

/** A kind's noun's resource ("Receipt"), for the screens and a tax pack's table (F12). */
internal fun kindRes(k: FileKind): Int = when (k) {
    FileKind.RECEIPT -> R.string.files_kind_receipt
    FileKind.BILL -> R.string.files_kind_bill
    FileKind.DOCUMENT -> R.string.files_kind_document
    FileKind.ID -> R.string.files_kind_id
    FileKind.TAX_SLIP -> R.string.files_kind_tax_slip
    FileKind.TICKET -> R.string.files_kind_ticket
    FileKind.MEDICAL -> R.string.files_kind_medical
    FileKind.PHOTO -> R.string.files_kind_photo
    FileKind.VIDEO -> R.string.files_kind_video
    FileKind.NOTE -> R.string.files_kind_note
    FileKind.TRANSCRIPT -> R.string.files_kind_transcript
    else -> R.string.files_kind_other
}

// A drawer.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawerPage(
    idx: FileIndex, id: String, hidden: Set<FileId>, port: FilesPort, modifier: Modifier, clock: () -> Long = System::currentTimeMillis, onBack: () -> Unit, onOpen: (FilesView) -> Unit,
    onChange: ((FileIndex) -> FileIndex?, (Boolean) -> Unit) -> Unit, onRenamed: (String) -> Unit, say: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val lang = LocalLangs.current.ui
    val files = remember(idx, hidden, id) { FileViews.inDrawer(idx, id, hidden) }
    val months = remember(files) { FileViews.months(files) }
    var month by remember(id) { mutableStateOf<Pair<Int, Int>?>(null) }
    val shown = month?.let { (y, m) -> FileViews.inMonth(files, y, m) } ?: files
    // F10: subscriptions in your bills (§7.2), found by code: a line, never an action.
    val subs = remember(idx, hidden, id) {
        if (id == Drawers.BILLS) app.heyrobot.core.files.FileSubscriptions.find(idx, java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), hidden)
        else emptyList()
    }
    val yours = id !in Drawers.STARTER
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    PreferenceScreen(drawerTitle(id), onBack, modifier = modifier, actions = {
        if (yours) Box {
            IconButton(onClick = { menu = true }) { Icon(RIcons.MoreVert, stringResource(R.string.files_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = SecureMenu) {
                DropdownMenuItem(text = { Text(stringResource(R.string.files_rename_drawer)) }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.files_delete_drawer)) }, onClick = {
                    menu = false
                    if (files.isNotEmpty()) say(ctx.getString(R.string.files_drawer_not_empty))
                    else onChange({ i -> FileViews.removeDrawer(i, id) }) { ok -> if (ok) onBack() }
                })
            }
        }
    }) {
        if (months.size > 1) item(key = "months") {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = month == null, onClick = { month = null }, label = { Text(stringResource(R.string.files_all)) })
                for (m in months.take(12)) FilterChip(selected = month == m, onClick = { month = m }, label = { Text(Fmt.month(m.first, m.second, lang)) })
            }
        }
        if (id == Drawers.RECEIPTS || id == Drawers.BILLS) {
            val t = FileViews.totals(shown)
            if (t.byCurrency.isNotEmpty() || t.check > 0) item(key = "totals") {
                val res = if (id == Drawers.RECEIPTS) R.plurals.files_total_receipts else R.plurals.files_total_bills
                val lines = t.byCurrency.map { (cur, v) -> ctx.resources.getQuantityString(res, v.second, Fmt.money(v.first, cur, lang), v.second) } +
                    listOfNotNull(if (t.check > 0) ctx.resources.getQuantityString(R.plurals.files_to_check, t.check, t.check) else null)
                Text(lines.joinToString(" · "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) // l10n: not text (a separator)
            }
        }
        // F10: subscriptions in your bills (§7.2), found by code: a line, never an action.
        if (subs.isNotEmpty()) item(key = "subscriptions") {
            Text(app.heyrobot.core.files.FileMoneyText.subscriptions(subs).text.str(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        }
        preferenceGroup(null, "files") {
            if (shown.isEmpty()) PreferenceBlock { Text(stringResource(R.string.files_drawer_empty), style = MaterialTheme.typography.bodyLarge) }
            for (e in shown) FileRow(e, port, filedSummary(e)) { onOpen(FilesView.File(e.id)) }
        }
    }
    if (renaming) EditDialog(stringResource(R.string.files_rename_drawer), id, stringResource(R.string.files_drawer_name), "",
        validate = { n -> FileViews.drawerProblem(idx, n, except = id)?.let { p -> if (p == FileViews.DrawerProblem.TAKEN) takenText(ctx, idx) else null } },
        canSave = { FileViews.drawerProblem(idx, it, except = id) == null }, maxLength = 40, secure = true, onDismiss = { renaming = false }) { n ->
        renaming = false
        val name = FileViews.drawerName(n) ?: return@EditDialog
        onChange({ i -> FileViews.renameDrawer(i, id, name) }) { ok -> if (ok) onRenamed(name) else say(takenText(ctx, idx)) }
    }
}

// A file's page.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilePage(
    e: FileEntry, idx: FileIndex, port: FilesPort, modifier: Modifier, onBack: () -> Unit,
    onChange: ((FileIndex) -> FileIndex?, (Boolean) -> Unit) -> Unit, onMove: (FileEntry, String) -> Unit,
    onShare: () -> Unit, onOpenWith: () -> Unit, onDelete: (FileEntry) -> Unit, a: AppActions,
    rules: List<FilingRule> = emptyList(), onTagged: (FileEntry, String) -> Unit = { _, _ -> },
    onCalendar: (app.heyrobot.core.files.FileDate) -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
) {
    val ctx = LocalContext.current
    val lang = LocalLangs.current.ui
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val filing = e.filing
    val asksTotal = filing?.step == FilingStep.ASK && filing.why == FilingWhy.TOTAL
    val checkTotal = e.fields["check"]?.value == "total" || asksTotal // l10n: not text (a field's code)
    val detailsTitle = stringResource(R.string.files_details)
    val tagsTitle = stringResource(R.string.files_tags)
    val peopleTitle = UiStrings.people().str()
    val fromTitle = stringResource(R.string.files_where_from)
    val remindersTitle = stringResource(R.string.files_reminders_title)
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
    val dates = remember(e, today) { app.heyrobot.core.files.FileDates.of(e).filter { !it.date.isBefore(today) } }
    val remindersOn by produceState(true) { value = withContext(Dispatchers.IO) { port.settings().reminders != app.heyrobot.core.files.FileReminders.OFF } }
    val people by produceState(emptyList<Pair<String, String>>(), e.people) { value = withContext(Dispatchers.IO) { port.people() } }
    PreferenceScreen(e.name.ifBlank { FilesStrings.noName().str() }, onBack, modifier = modifier) {
        item(key = "pages") { Pages(e, port) }
        if (checkTotal) item(key = "check") {
            PreferenceBlock {
                Text(FilesStrings.askTotal().str(), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { editing = "total" }) { Text(stringResource(R.string.files_check_total)) }
            }
        }
        preferenceGroup(null, "name") {
            PreferenceItem(stringResource(R.string.files_name), e.name.ifBlank { FilesStrings.noName().str() } +
                if (e.named) " · " + stringResource(R.string.files_named_by_you) else "", onClick = { renaming = true }) // l10n: not text (a separator)
            PreferenceItem(stringResource(R.string.files_drawer), e.drawer?.let { drawerTitle(it) } ?: FilesStrings.inbox().str(), RIcons.Folder,
                enabled = e.kind != FileKind.DANGEROUS && !e.damaged, onClick = { moving = true })
            if (e.kind != FileKind.UNKNOWN) PreferenceItem(stringResource(R.string.files_kind), kindNoun(e.kind))
        }
        preferenceGroup(detailsTitle, "fields") {
            for ((k, f) in e.fields) {
                if (k == "check" || k == app.heyrobot.core.files.FileRemind.FIELD) continue // l10n: not text (a field's code)
                PreferenceItem(fieldLabel(k), fieldValue(k, f.value, e, lang) + " · " + stringResource(if (f.by == FieldBy.YOU) R.string.files_set_by_you else R.string.files_read_by_robot), // l10n: not text (a separator)
                    onClick = if (k in FileViews.EDITABLE) ({ editing = k }) else null)
            }
            if (FileViews.EDITABLE.any { it !in e.fields }) PreferenceItem(stringResource(R.string.files_add_detail), null, RIcons.Add, onClick = { adding = true })
            // F10: a tax file's keeping time (§7.4).
            app.heyrobot.core.files.FileMoneyText.keepUntil(e)?.let { keep -> PreferenceBlock { Text(keep.str(), style = MaterialTheme.typography.bodyMedium) } }
        }
        // F10: its dates coming up (§6.4, §7.2), from its fields only: a reminder in your brief (kept in this file's entry) or the
        // calendar app's own editor. Reminders from files Off: no offer, only the reminders you set (with Remove).
        if (e.state == FileState.FILED && dates.isNotEmpty()) preferenceGroup(remindersTitle, "reminders") {
            val set = app.heyrobot.core.files.FileRemind.of(e)
            for (d in dates) PreferenceBlock {
                Text(app.heyrobot.core.files.FileMoneyText.dateLine(d).str(), style = MaterialTheme.typography.bodyLarge)
                val on = set[d.kind]
                if (on != null) {
                    Text(stringResource(R.string.files_in_brief, Fmt.day(on, lang)), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onChange({ i -> app.heyrobot.core.files.FileRemind.remove(i, e.id, d.kind) }) {} }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.files_remove_reminder))
                    }
                } else if (remindersOn) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val day = app.heyrobot.core.files.FileDates.remindOn(d, app.heyrobot.core.files.FileDates.nextBrief(java.time.Instant.ofEpochMilli(clock()).atZone(zone)))
                        if (day == null) a.say(FilesStrings.datePassed().render(langsOf(ctx)))
                        else onChange({ i -> app.heyrobot.core.files.FileRemind.set(i, d, day) }) {}
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_add_to_brief)) }
                    OutlinedButton(onClick = { onCalendar(d) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.files_add_to_calendar)) }
                }
            }
        }
        preferenceGroup(tagsTitle, "tags") {
            PreferenceBlock {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // F12: business or personal, one tap each (never both); the second file of a merchant with one teaches a rule.
                    for ((t, res) in listOf(FileTags.BUSINESS to R.string.files_tag_business, FileTags.PERSONAL to R.string.files_tag_personal)) {
                        val on = t in e.tags
                        FilterChip(selected = on, label = { Text(stringResource(res)) }, onClick = {
                            if (on) onChange({ i -> FileViews.removeTag(i, e.id, t) }) {}
                            else onChange({ i -> FileViews.addTag(i, e.id, t) }) { ok -> if (ok) onTagged(e, t) }
                        })
                    }
                    for (t in e.tags) if (FileTags.other(t) == null) RemovableChip(FilesStrings.tag(t).str()) { onChange({ i -> FileViews.removeTag(i, e.id, t) }) {} }
                    AssistChip(onClick = { tagging = true }, label = { Text(stringResource(R.string.files_add_tag)) }, leadingIcon = { Icon(RIcons.Add, null) })
                }
            }
        }
        preferenceGroup(peopleTitle, "people") {
            PreferenceBlock {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in e.people) RemovableChip(people.firstOrNull { it.first == p }?.second ?: p) { onChange({ i -> FileViews.removePerson(i, e.id, p) }) {} }
                    AssistChip(onClick = { linking = true }, label = { Text(stringResource(R.string.files_link_person)) }, leadingIcon = { Icon(RIcons.Person, null) })
                }
            }
        }
        preferenceGroup(fromTitle, "from") { Provenance(e) }
        preferenceGroup(null, "actions") {
            PreferenceItem(stringResource(R.string.files_share), null, RIcons.Share, onClick = onShare)
            PreferenceItem(stringResource(R.string.files_open_with), null, RIcons.Folder, onClick = onOpenWith)
            PreferenceItem(stringResource(R.string.files_delete), null, RIcons.Delete, onClick = { deleting = true })
        }
    }
    if (renaming) EditDialog(stringResource(R.string.files_rename), e.name, stringResource(R.string.files_name), stringResource(R.string.files_named_by_you),
        canSave = { app.heyrobot.core.files.FileIndexCodec.cleanName(it).isNotEmpty() }, maxLength = app.heyrobot.core.files.FileIndexCodec.MAX_NAME,
        secure = true, onDismiss = { renaming = false }) { n ->
        renaming = false
        onChange({ i -> FileViews.rename(i, e.id, n) }) {}
    }
    editing?.let { k ->
        val amount = k == "total"
        val date = k in FileViews.DATE_FIELDS
        val invalid = stringResource(R.string.files_field_invalid)
        EditDialog(fieldLabel(k), e.fields[k]?.value.orEmpty(), fieldLabel(k),
            when { amount -> stringResource(R.string.files_field_hint_amount); date -> stringResource(R.string.files_field_hint_date); else -> "" },
            validate = { v -> if (v.isBlank() || FileViews.cleanField(k, v) != null) null else invalid },
            keyboard = KeyboardOptions(keyboardType = if (amount) KeyboardType.Decimal else KeyboardType.Text),
            maxLength = app.heyrobot.core.files.FileIndexCodec.MAX_FIELD_VALUE, secure = true, onDismiss = { editing = null }) { v ->
            editing = null
            onChange({ i ->
                if (k == "total" && asksTotal && v.isNotBlank()) FileViews.answerTotal(i, e.id, v, port.settings(), rules, port.words(), System.currentTimeMillis())
                else FileViews.setField(i, e.id, k, v)
            }) {}
        }
    }
    if (adding) PickDialog(stringResource(R.string.files_add_detail), FileViews.EDITABLE.filter { it !in e.fields }, { fieldLabel(it) },
        onDismiss = { adding = false }) { k -> adding = false; editing = k }
    if (tagging) EditDialog(stringResource(R.string.files_add_tag), "", stringResource(R.string.files_tag), "",
        canSave = { FileTags.of(it)?.let { t -> t !in e.tags } == true },
        maxLength = 40, secure = true, onDismiss = { tagging = false }) { t ->
        tagging = false
        onChange({ i -> FileViews.addTag(i, e.id, t) }) { ok -> if (ok) onTagged(e, t) }
    }
    if (linking) {
        val choices = people.filter { it.first !in e.people }
        if (choices.isEmpty()) {
            linking = false
            a.say(ctx.getString(R.string.files_no_people, UiStrings.inSettings(UiStrings.peopleNotesCalendar).render(lang)))
        } else PickDialog(stringResource(R.string.files_link_person), choices, { it.second }, onDismiss = { linking = false }) { p ->
            linking = false
            onChange({ i -> FileViews.addPerson(i, e.id, p.first) }) {}
        }
    }
    if (moving) MoveDialog(idx, e, onDismiss = { moving = false }, onNew = null) { d -> moving = false; onMove(e, d) }
    if (deleting) SecureAlert(stringResource(R.string.files_delete_title, e.name.ifBlank { FilesStrings.noName().str() }), stringResource(R.string.files_delete_text),
        confirm = stringResource(R.string.files_delete), dismiss = stringResource(R.string.files_keep), onDismiss = { deleting = false }) {
        deleting = false
        onDelete(e)
    }
}

/** A file's pages, as :files draws them (D19: raw pixels, never decoded here), one at a time, pinch to zoom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pages(e: FileEntry, port: FilesPort) {
    val type = FileOut.typeOf(e)
    val shows = !e.damaged && type != null && (type.group == FileGroup.IMAGE || type.group == FileGroup.PDF)
    var page by remember(e.id) { mutableIntStateOf(0) }
    /** Null while :files draws it; then the page and how many there are, or nothing (it couldn't be shown). */
    val shown by produceState<Result<Pair<android.graphics.Bitmap, Int>?>?>(null, e.id, page, shows) {
        value = null
        if (shows) value = Result.success(withContext(Dispatchers.IO) {
            port.page(e, page)?.let { p -> FilePixels.bitmap(p.pixels, thumb = false)?.let { it to p.pages } }
        })
    }
    PreferenceBlock {
        val s = shown?.getOrNull()
        when {
            e.damaged -> Text(stringResource(R.string.files_damaged), style = MaterialTheme.typography.bodyLarge)
            !shows -> Text(stringResource(R.string.files_no_preview), style = MaterialTheme.typography.bodyLarge)
            shown == null -> Text(stringResource(R.string.files_opening), style = MaterialTheme.typography.bodyMedium)
            s == null -> Text(stringResource(R.string.files_preview_failed), style = MaterialTheme.typography.bodyLarge)
            else -> {
                var scale by remember(e.id, page) { mutableFloatStateOf(1f) }
                val zoom = rememberTransformableState { z, _, _ -> scale = (scale * z).coerceIn(1f, 4f) }
                val desc = stringResource(R.string.files_page_of, page + 1, s.second)
                Image(s.first.asImageBitmap(), contentDescription = desc, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(8.dp)).transformable(zoom)
                        .graphicsLayer(scaleX = scale, scaleY = scale))
                if (s.second > 1) {
                    Text(desc, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { page-- }, enabled = page > 0) { Text(stringResource(R.string.files_previous)) }
                        OutlinedButton(onClick = { page++ }, enabled = page + 1 < s.second) { Text(stringResource(R.string.files_next)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    val remove = stringResource(R.string.files_remove_named, label)
    InputChip(selected = false, onClick = onRemove, label = { Text(label) },
        trailingIcon = { Icon(RIcons.Close, null, Modifier.size(18.dp)) },
        modifier = Modifier.semantics { contentDescription = remove })
}

/** Where a file came from (§4.4): its source, when, its size and sniffed type, SHA-256, whether you opened it, who filed it. */
@Composable
private fun Provenance(e: FileEntry) {
    val ctx = LocalContext.current
    val lang = LocalLangs.current.ui
    val p = e.from
    val appLabel by produceState<String?>(null, p.app) {
        value = p.app?.let { pkg -> withContext(Dispatchers.IO) {
            try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        } }
    }
    val source = when (p.source) {
        FileSource.SHARE -> (appLabel ?: p.app)?.let { FilesStrings.sharedFrom("sender" to it).str() } ?: FilesStrings.sharedFromAnother().str()
        FileSource.SCAN -> stringResource(R.string.files_from_scan)
        FileSource.PANEL -> stringResource(R.string.files_from_panel)
        FileSource.TRANSCRIPT -> stringResource(R.string.files_from_transcript)
        FileSource.BACKUP -> stringResource(R.string.files_from_backup)
        FileSource.RECOVERED -> stringResource(R.string.files_from_recovered)
        FileSource.ROBOT, FileSource.UNKNOWN -> stringResource(R.string.files_from_unknown)
    }
    fun day(t: Long) = Fmt.date(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate(), lang)
    PreferenceItem(stringResource(R.string.files_source), source)
    PreferenceItem(stringResource(R.string.files_added), day(p.at))
    PreferenceItem(stringResource(R.string.files_size), Fmt.bytes(p.size, lang))
    PreferenceItem(stringResource(R.string.files_file_type), p.type ?: stringResource(R.string.files_from_unknown))
    p.sha256?.let { h ->
        PreferenceBlock {
            Text(stringResource(R.string.files_sha256), style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(h.chunked(16).joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    PreferenceItem(stringResource(R.string.files_opened), stringResource(if (p.opened) R.string.files_yes else R.string.files_no))
    val filedAt = p.filedAt
    if (e.state == FileState.FILED && filedAt != null) {
        val by = when (p.filedBy) {
            FiledBy.RULE -> R.string.files_filed_by_rule
            FiledBy.YOU -> R.string.files_filed_by_you
            else -> R.string.files_filed_by_robot
        }
        PreferenceItem(stringResource(R.string.files_filed), stringResource(by, day(filedAt)))
    }
}

@Composable
private fun fieldLabel(k: String): String = when {
    // F13: a tax by its name in the menus' language (« TPS », « TVQ »), as the table names it; an unknown one by its code.
    k.startsWith("tax_") -> stringResource(R.string.files_field_tax, when (k) {
        "tax_gst" -> FilesStrings.csvGst() // l10n: not text (a field's code)
        "tax_hst" -> FilesStrings.csvHst() // l10n: not text (a field's code)
        "tax_pst" -> FilesStrings.csvPst() // l10n: not text (a field's code)
        "tax_qst" -> FilesStrings.csvQst() // l10n: not text (a field's code)
        else -> null
    }?.str() ?: k.removePrefix("tax_").uppercase())
    else -> stringResource(when (k) {
        "merchant" -> R.string.files_field_merchant
        "date" -> R.string.files_field_date
        "total" -> R.string.files_field_total
        "currency" -> R.string.files_field_currency
        "due" -> R.string.files_field_due
        "expiry" -> R.string.files_field_expiry
        "return_by" -> R.string.files_field_return_by
        "warranty" -> R.string.files_field_warranty
        "account" -> R.string.files_field_account
        "card" -> R.string.files_field_card
        "slip" -> R.string.files_field_slip
        "year" -> R.string.files_field_year
        "period" -> R.string.files_field_period
        "category" -> R.string.files_field_category
        else -> R.string.files_details
    })
}

/** A field's value as the page shows it: amounts and dates in the menus' language, the rest as it is. */
private fun fieldValue(k: String, v: String, e: FileEntry, lang: app.heyrobot.core.i18n.Lang): String = when {
    k == "total" || k.startsWith("tax_") -> app.heyrobot.core.files.FileFields.amountOf(v)?.let { Fmt.money(it, e.fields["currency"]?.value, lang) } ?: v
    k in FileViews.DATE_FIELDS -> FileViews.dateField(e, k)?.let { Fmt.date(it, lang) } ?: v
    else -> v
}

// Dialogs: each a window of its own, secure whatever the screen under it.

/** Menus over a Files screen: secure, as its dialogs. */
private val SecureMenu = PopupProperties(focusable = true, securePolicy = SecureFlagPolicy.SecureOn)

@Composable
private fun SecureAlert(title: String, text: String?, confirm: String, onDismiss: () -> Unit, dismiss: String? = null, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss ?: UiStrings.cancel().str()) } },
    )
}

/** One choice of [entries] (a detail to add, a person); a pick closes it. */
@Composable
private fun <T> PickDialog(title: String, entries: List<T>, label: @Composable (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (e in entries) {
                    Row(Modifier.fillMaxWidth().heightIn(min = RobotTokens.choiceRowHeight).clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button) { onPick(e) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label(e), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}

/** Move to: every drawer (where it is now selected), and New drawer when [onNew]. */
@Composable
private fun MoveDialog(idx: FileIndex, e: FileEntry, onDismiss: () -> Unit, onNew: (() -> Unit)?, onPick: (String) -> Unit) {
    val drawers: List<DrawerRow> = remember(idx) { FileViews.drawers(idx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(stringResource(R.string.files_move_to)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (d in drawers) {
                    val on = e.state == FileState.FILED && e.drawer == d.id
                    Row(Modifier.fillMaxWidth().heightIn(min = RobotTokens.choiceRowHeight).clip(MaterialTheme.shapes.small)
                        .selectable(selected = on, role = Role.RadioButton) { if (!on) onPick(d.id) else onDismiss() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = on, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(FilesStrings.drawer(d.id).str(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
        dismissButton = if (onNew == null) null else ({ TextButton(onClick = onNew) { Text(stringResource(R.string.files_new_drawer)) } }),
    )
}

/**
 * FX1 (files-1.4-review.md finding 6): a drawer name that can't be used, in words. While travel mode hides drawers ([idx] is
 * its view), the change runs on the whole index and can find a hidden drawer's name: then every taken name, seen or not,
 * gets the same line, which never says a hidden drawer has it.
 */
private fun takenText(ctx: android.content.Context, idx: FileIndex): String =
    ctx.getString(if (idx.outOfSight.isEmpty()) R.string.files_drawer_taken else R.string.files_drawer_name_unused)

@Composable
private fun NewDrawerDialog(idx: FileIndex, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val ctx = LocalContext.current
    EditDialog(stringResource(R.string.files_new_drawer), "", stringResource(R.string.files_drawer_name), "",
        validate = { n -> if (FileViews.drawerProblem(idx, n) == FileViews.DrawerProblem.TAKEN) takenText(ctx, idx) else null },
        canSave = { FileViews.drawerProblem(idx, it) == null }, confirm = stringResource(R.string.files_create), maxLength = 40, secure = true,
        onDismiss = onDismiss, onSave = onCreate)
}
