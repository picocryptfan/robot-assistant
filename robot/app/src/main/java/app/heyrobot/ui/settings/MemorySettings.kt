// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.core.files.TravelMode
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.TravelReveal
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import app.heyrobot.ui.common.NoImeLearning
import app.heyrobot.ui.common.SecureDialog
import app.heyrobot.ui.common.SecureWindow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.i18n.MemoryStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.memory.HelperMemory
import app.heyrobot.core.memory.ImportRead
import app.heyrobot.core.memory.MemImportCode
import app.heyrobot.core.memory.MemForget
import app.heyrobot.core.memory.MemKindCode
import app.heyrobot.core.memory.MemOrigin
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemoryDates
import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryImport
import app.heyrobot.core.memory.MemoryImported
import app.heyrobot.core.memory.MemoryIds
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryKind
import app.heyrobot.core.memory.MemoryLimits
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.core.memory.MemorySuggestion
import app.heyrobot.core.memory.MemoryText
import app.heyrobot.core.memory.SuggestionStyle
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.MemoryFiles
import app.heyrobot.sys.MemorySettingsStore
import app.heyrobot.sys.Paths
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PrefSwitch
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TwoTargetPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.pref.preferenceItems
import app.heyrobot.ui.stampChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// MAIN PROCESS. Settings › Memory (0.7): what Robot remembers, each item you can see, search,
// edit, pin (six at most), make private or delete; what waits in Suggestions (a quiet list, never spoken); the switches,
// whose defaults are the design's (memory on, used in answers, nothing on the lock screen, private items never spoken,
// helpers only through their card); Export to a file you pick (plain JSON, after a warning), Import (1.0: an export from 0.9
// or 1.0, previewed first, MemoryImportScreen.kt) and Delete all (after a confirm). The items are decrypted only while
// this page is open, off the main thread, through the vault both app processes share (sys/MemoryVault.kt); its switches
// live in memory.json, not settings.json. Every change tells the voice process (Cmd.MEMORY_CHANGED), so the panel never
// uses a deleted item again.

/** What the page does with memory; the phone's is [PhoneMemoryStore], tests keep theirs in RAM. Every call off the main thread. */
interface MemoryStore {
    fun settings(): MemorySettings
    fun saveSettings(s: MemorySettings)
    fun load(): MemoryLoad
    fun update(change: (MemoryFile) -> MemoryFile): MemoryLoad
    fun deleteAll(): Boolean
    /** A file that can't be read, kept aside as memory.enc.bad. */
    fun startOver(): Boolean
    /** "Use memory on the lock screen": memory sealed again with the lock screen's key (on) or the unlock-only one (off). */
    fun rekey(lockScreen: Boolean): MemoryLoad
    fun export(uri: Uri, text: String): Boolean
    /** Memory changed: the voice process drops what it holds. */
    fun changed()
    /** Codes and counts for the voice log (the main app's file). */
    fun log(e: VoiceEvent, a: Int, b: Int = 0, c: Int = 0)
    /** 1.0: the phone is locked now. Import refuses then ("Unlock first"), as every memory write does. */
    fun locked(): Boolean
    /**
     * 1.0.2: emits when the file changed (the panel saved or forgot something in the voice process while this page is
     * open). None by default.
     */
    fun changes(): Flow<Long> = emptyFlow()
    /** Import's file: at most [MemoryImport.MAX_BYTES] + 1 bytes (one more says it is too big); null when it can't be read. */
    fun readImport(uri: Uri): ByteArray?
    /** After an import, when you agree: the file deleted where it is (its app may not allow it). */
    fun deleteImport(uri: Uri): Boolean
    /**
     * FX1 (files-1.4-review.md finding 6): which drawers Robot Files' travel mode hides now, read off the main thread: a
     * filing rule naming one isn't listed or counted here until your PIN shows them, or travel mode is off. None by default.
     */
    fun hiddenDrawers(): (String) -> Boolean = { false }
}

/** FX3: what Export writes of [all]: every item but the filing rules naming a drawer travel mode hides now ([hidden]). */
internal fun exported(all: MemoryFile, hidden: (String) -> Boolean): MemoryFile = all.copy(items = all.items.filter { it.filing?.drawer?.let(hidden) != true })

/** The phone's: the vault in files/memory, memory.json, the voice log's main-process file. */
class PhoneMemoryStore(ctx: Context) : MemoryStore {
    private val app = ctx.applicationContext
    private val vault = MemoryFiles.vault(app)

    override fun settings() = MemorySettingsStore.current(app)
    override fun saveSettings(s: MemorySettings) = MemorySettingsStore.save(app, s)
    override fun hiddenDrawers(): (String) -> Boolean {
        val s = FilesSettingsStore.current(app)
        val revealed = TravelReveal.on()
        return { d -> TravelMode.hidden(d, s, revealed) }
    }
    override fun load() = vault.load()
    override fun update(change: (MemoryFile) -> MemoryFile) = vault.update(change)
    override fun deleteAll() = vault.deleteAll()
    override fun startOver() = vault.startOver()
    override fun rekey(lockScreen: Boolean) = vault.rekey(lockScreen)
    override fun export(uri: Uri, text: String): Boolean = try {
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    } catch (t: Exception) {
        RLog.w("memory_export", t)
        false
    }
    override fun changed() = Cmd.send(app, Cmd.MEMORY_CHANGED)
    override fun locked() = Assistant.locked(app)
    /** The vault's stamp, read once a second (ui/HistoryWatch's stampChanges, as History follows its folder). */
    override fun changes(): Flow<Long> = stampChanges({ vault.stamp() })
    override fun readImport(uri: Uri): ByteArray? = try {
        app.contentResolver.openInputStream(uri)?.use { readUpTo(it, MemoryImport.MAX_BYTES + 1) }
    } catch (t: Exception) {
        RLog.w("memory_import_read", t)
        null
    }
    override fun deleteImport(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(app.contentResolver, uri)
    } catch (t: Exception) {
        RLog.w("memory_import_delete", t)
        false
    }
    override fun log(e: VoiceEvent, a: Int, b: Int, c: Int) {
        try {
            VoiceLog.init(Paths.voiceLogMain(app))
            VoiceLog.add(e, a, b, c)
            VoiceLog.flush()
        } catch (t: Throwable) { RLog.w("memory_log", t) }
    }
}

/** Settings › Memory on the phone (SettingsNav). */
@Composable
fun MemorySettingsRoute(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PhoneMemoryStore(ctx) }
    MemorySettingsScreen(store, a, snackbar, onBack)
}

/** The page; [now] for the dates (tests pass a fixed one). */
@Composable
fun MemorySettingsScreen(store: MemoryStore, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit, now: () -> Long = System::currentTimeMillis) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(store.settings()) }
    var load by remember { mutableStateOf<MemoryLoad?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var warnExport by rememberSaveable { mutableStateOf(false) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    // Import (1.0): the file picked (kept across a rotation, read again then), its preview, the tick box, and afterwards the
    // count and the file to offer to delete.
    var importUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var importing by remember { mutableStateOf<ImportPreview?>(null) }
    var importMine by rememberSaveable { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var askMemoryOn by rememberSaveable { mutableStateOf(false) }
    var importedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var importedCount by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    /** FX1: the drawers travel mode hides, read before the list (finding 6). */
    var hiddenDrawer by remember { mutableStateOf<(String) -> Boolean>({ false }) }
    LaunchedEffect(tick) {
        val (h, l) = withContext(Dispatchers.IO) { store.hiddenDrawers() to store.load() }
        hiddenDrawer = h
        load = l
    }
    // 1.0.2: the list follows the file while the page is in view (the panel adds or forgets items over it); back in view, it
    // is read again for what changed meanwhile.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(store, owner) {
        var first = true
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!first) tick++
            first = false
            store.changes().collect { tick++ }
        }
    }

    /** Codes and counts for the voice log, off the main thread. */
    fun log(e: VoiceEvent, a1: Int, b: Int = 0, c: Int = 0) {
        scope.launch(Dispatchers.IO) { store.log(e, a1, b, c) }
    }

    fun saveSettings(n: MemorySettings) {
        s = n
        store.saveSettings(n)
    }

    /** One change to memory, then the list again and the voice process told; [done] says what happened. */
    fun change(done: String?, change: (MemoryFile) -> MemoryFile) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { store.update(change).also { if (it is MemoryLoad.Loaded) store.changed() } }
            load = r
            if (r !is MemoryLoad.Loaded) a.say(problem(ctx, r) ?: ctx.getString(R.string.memory_not_changed)) else done?.let(a.say)
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                val all = (store.load() as? MemoryLoad.Loaded)?.file ?: return@withContext -1
                // FX3: as the list shows it: a filing rule naming a drawer travel mode hides now stays out of the file
                // (read again here: travel mode may have changed since the page opened).
                val f = exported(all, store.hiddenDrawers())
                if (store.export(uri, JSONObject(MemoryCodec.toJson(f)).toString(2))) f.items.size else -1
            }
            a.say(if (n < 0) ctx.getString(R.string.memory_export_failed) else ctx.resources.getQuantityString(R.plurals.memory_exported, n, n))
        }
    }

    // Import (1.0): Android's file picker; never on the lock screen, and with Memory off only once you turn it on.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importMine = false
            importUri = uri
        }
    }
    fun pickImport() {
        try { importer.launch(IMPORT_TYPES) } catch (_: ActivityNotFoundException) { a.say(ctx.getString(R.string.memory_import_no_picker)) }
    }
    fun startImport() {
        when {
            store.locked() -> {
                a.say(ctx.getString(R.string.memory_import_unlock))
                log(VoiceEvent.MEM_IMPORT, 0, MemImportCode.LOCKED.id)
            }
            !s.on -> askMemoryOn = true
            else -> pickImport()
        }
    }
    // The file read (5 MB at most) and checked off the main thread, then planned against memory as it is now.
    LaunchedEffect(importUri) {
        val uri = importUri ?: return@LaunchedEffect
        if (importing != null) return@LaunchedEffect
        val bytes = withContext(Dispatchers.IO) { store.readImport(uri) }
        val read = bytes?.let { withContext(Dispatchers.Default) { MemoryImport.read(it) } }
        val have = if (read is ImportRead.Ok && read.entries.isNotEmpty()) withContext(Dispatchers.IO) { store.load() } else null
        val refusal = when {
            read == null -> R.string.memory_import_unreadable.also { log(VoiceEvent.MEM_IMPORT, 0, MemImportCode.UNREADABLE.id) }
            read is ImportRead.Refused -> importProblem(read.problem).also { log(VoiceEvent.MEM_IMPORT, 0, MemImportCode.of(read.problem).id) }
            read is ImportRead.Ok && read.entries.isEmpty() -> R.string.memory_import_empty
            else -> null
        }
        val f = have?.current
        if (refusal != null || f == null || read !is ImportRead.Ok) {
            importUri = null
            a.say(refusal?.let { ctx.getString(it) } ?: have?.let { problem(ctx, it) } ?: ctx.getString(R.string.memory_import_not_saved))
            return@LaunchedEffect
        }
        importing = withContext(Dispatchers.Default) { ImportPreview(read, MemoryImport.plan(read, f, mine = true, now = now())) }
    }

    /**
     * Import, after its tap: unlocked and with Memory on (checked again: the phone may have locked while the preview
     * showed), planned again under the vault's lock against memory as it is then, with your tick, and written as any
     * change is (one writer, encrypted). The voice log gets counts only.
     */
    fun runImport(pv: ImportPreview, uri: Uri) {
        if (importBusy) return
        if (store.locked()) {
            a.say(ctx.getString(R.string.memory_import_unlock))
            log(VoiceEvent.MEM_IMPORT, 0, MemImportCode.LOCKED.id)
            return
        }
        if (!s.on) {
            a.say(ctx.getString(R.string.memory_import_off_title))
            return
        }
        val mine = importMine
        importBusy = true
        scope.launch {
            var done: app.heyrobot.core.memory.ImportPlan? = null
            val r = withContext(Dispatchers.IO) {
                store.update { f -> MemoryImport.plan(pv.read, f, mine, now()).also { done = it }.let { MemoryImport.into(f, it) } }
                    .also { if (it is MemoryLoad.Loaded && done?.add?.isNotEmpty() == true) store.changed() }
            }
            importBusy = false
            load = r
            val d = done
            if (r !is MemoryLoad.Loaded || d == null) {
                log(VoiceEvent.MEM_IMPORT, 0, MemImportCode.NOT_SAVED.id)
                a.say(problem(ctx, r) ?: ctx.getString(R.string.memory_import_not_saved))
                return@launch
            }
            log(VoiceEvent.MEM_IMPORT, d.add.size, (if (mine) MemImportCode.YOURS else MemImportCode.CONTENT).id, d.skipped)
            importing = null
            importUri = null
            importedCount = d.add.size
            importedUri = uri
        }
    }

    val file = (load as? MemoryLoad.Loaded)?.file ?: if (load == MemoryLoad.Empty) MemoryFile() else null
    // FX1 (finding 6): a filing rule naming a drawer travel mode hides isn't listed or counted (it stays in the file).
    val items = file?.items.orEmpty().filter { it.filing?.drawer?.let(hiddenDrawer) != true }
    val pinnedCount = items.count { it.pinned }
    val shown = if (query.isBlank()) items else items.filter { MemoryText.fold(it.text).contains(MemoryText.fold(query.trim())) }

    // Import's preview takes the page's place until Import or Cancel (secure too).
    val preview = importing
    val previewUri = importUri
    if (preview != null && previewUri != null) {
        MemoryImportScreen(preview, now(), importMine, onMine = { importMine = it }, busy = importBusy,
            onImport = { runImport(preview, previewUri) }, onCancel = { importing = null; importUri = null })
        return
    }

    // 1.0 (secrets-memory-private-items-not-secure): private items are shown here in full: out of screenshots, recordings,
    // casting and Recents, and no keyboard learns from the fields, the dialogs' included (ui/common Secure).
    // The page's words its list builder can't look up itself (it isn't composable).
    val title = UiStrings.memory().str()
    val pinnedTitle = stringResource(R.string.memory_pinned_group, pinnedCount, MemoryLimits.MAX_PINNED)
    val everything = stringResource(R.string.memory_everything)
    val everythingElse = stringResource(R.string.memory_everything_else)
    val suggestionsTitle = UiStrings.suggestions().str()
    val suggestionsCount = stringResource(R.string.memory_suggestions_count, file?.suggestions?.size ?: 0)
    val kept = stringResource(R.string.memory_kept)
    val yours = stringResource(R.string.memory_yours)
    val answers = stringResource(R.string.memory_answers)
    val privacy = stringResource(R.string.memory_privacy)
    val footer = stringResource(if (s.onLockScreen) R.string.memory_footer_lock_screen else R.string.memory_footer_unlocked)
    val langs = LocalLangs.current
    val suggestionLabels = SuggestionStyle.entries.associateWith { suggestionLabel(it) }
    val suggestionDetails = SuggestionStyle.entries.associateWith { suggestionDetail(it) }
    val shareDetails = HelperMemory.entries.associateWith {
        stringResource(if (it == HelperMemory.ONLY_WHEN_INCLUDED) R.string.memory_share_included_detail else R.string.memory_share_trusted_detail)
    }

    // 1.0 (secrets-memory-private-items-not-secure): private items are shown here in full: out of screenshots, recordings,
    // casting and Recents, and no keyboard learns from the fields, the dialogs' included (ui/common Secure). (The title is
    // a template so ProcessIsolationTest still reads this order.)
    SecureWindow()
    NoImeLearning {
    PreferenceScreen("$title", onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar(stringResource(R.string.memory_use), s.on) { saveSettings(s.copy(on = it)) } }
        when {
            load == null -> item(key = "reading") { IntroPreference(stringResource(R.string.memory_opening)) }
            file == null -> preferenceGroup(null, "problem") {
                PreferenceBlock { Text(problem(ctx, load!!) ?: "", style = MaterialTheme.typography.bodyLarge) }
                if (load == MemoryLoad.KeyGone || load == MemoryLoad.Unreadable) {
                    PreferenceItem(stringResource(R.string.memory_start_over), stringResource(R.string.memory_start_over_detail), RIcons.Refresh,
                        onClick = { confirmStartOver = true })
                    PreferenceItem(stringResource(R.string.memory_delete_file), stringResource(R.string.memory_delete_file_detail), RIcons.Delete,
                        onClick = { confirmAll = true })
                } else {
                    PreferenceItem(stringResource(R.string.memory_try_again), null, RIcons.Refresh, onClick = { tick++ })
                }
            }
            else -> {
                item(key = "intro") {
                    IntroPreference(listOfNotNull(
                        if (items.isEmpty()) stringResource(R.string.memory_intro_none)
                        else pluralStringResource(R.plurals.memory_intro_count, items.size, items.size),
                        stringResource(R.string.memory_intro_stays),
                        if (items.size >= MemoryLimits.NOTE_AT) stringResource(R.string.memory_intro_max, MemoryLimits.MAX_ITEMS) else null,
                    ).joinToString(" "))
                }
                if (items.isNotEmpty()) item(key = "search") {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.memory_search)) }, leadingIcon = { Icon(RIcons.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(RIcons.Close, stringResource(R.string.memory_clear_search)) } },
                        shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
                val pinned = shown.filter { it.pinned }
                val rest = shown.filter { !it.pinned }.sortedByDescending { it.createdAt }
                if (items.isNotEmpty() && shown.isEmpty()) item(key = "nomatch") { IntroPreference(stringResource(R.string.memory_no_match)) }
                if (pinned.isNotEmpty()) preferenceItems(pinnedTitle, "pinned", pinned, { it.id }) { i ->
                    ItemRow(i, now()) { editing = i }
                }
                if (rest.isNotEmpty()) preferenceItems(if (pinned.isEmpty() && query.isBlank()) everything else everythingElse, "items", rest, { it.id }) { i ->
                    ItemRow(i, now()) { editing = i }
                }
                val suggestions = file.suggestions
                if (suggestions.isEmpty()) {
                    preferenceGroup(suggestionsTitle, "suggestions") {
                        PreferenceBlock {
                            Text(stringResource(R.string.memory_suggestions_none),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    preferenceItems(suggestionsCount, "suggestions", suggestions, { it.id }) { sg ->
                        SuggestionRow(sg,
                            keep = { change(kept) { f -> MemoryEdits.keep(f, sg.id, now()).file }; log(VoiceEvent.MEM_SUGGEST, 0, 1, 0) },
                            dismiss = { change(null) { f -> MemoryEdits.dismiss(f, sg.id, dontSuggest = false) }; log(VoiceEvent.MEM_SUGGEST, 0, 0, 1) })
                    }
                }
                preferenceGroup(yours, "actions") {
                    PreferenceItem(stringResource(R.string.memory_add), stringResource(R.string.memory_add_detail), RIcons.Add,
                        enabled = items.size < MemoryLimits.MAX_ITEMS, onClick = { adding = true })
                    PreferenceItem(stringResource(R.string.memory_export), stringResource(R.string.memory_export_detail), RIcons.Download,
                        enabled = items.isNotEmpty(), onClick = { warnExport = true })
                    PreferenceItem(stringResource(R.string.memory_import), stringResource(R.string.memory_import_detail), RIcons.Folder,
                        enabled = items.size < MemoryLimits.MAX_ITEMS, onClick = { startImport() })
                    PreferenceItem(stringResource(R.string.memory_delete_all), stringResource(R.string.memory_delete_all_detail), RIcons.Delete,
                        enabled = items.isNotEmpty() || file.suggestions.isNotEmpty(),
                        onClick = { confirmAll = true })
                }
            }
        }
        preferenceGroup(answers, "answers") {
            SwitchPreference(stringResource(R.string.memory_use_in_answers), stringResource(R.string.memory_use_in_answers_detail), s.useInAnswers,
                enabled = s.on) {
                saveSettings(s.copy(useInAnswers = it))
            }
            ListPreference(stringResource(R.string.memory_suggest), s.suggestions, SuggestionStyle.entries, { suggestionLabels.getValue(it) }, enabled = s.on,
                detail = { suggestionDetails[it] }) { saveSettings(s.copy(suggestions = it)) }
            SwitchPreference(stringResource(R.string.memory_speak_private), stringResource(R.string.memory_speak_private_detail), s.speakSecrets,
                enabled = s.on) {
                saveSettings(s.copy(speakSecrets = it))
            }
        }
        preferenceGroup(privacy, "privacy") {
            SwitchPreference(stringResource(R.string.memory_lock_screen), stringResource(R.string.memory_lock_screen_detail), s.onLockScreen,
                enabled = s.on) { on ->
                // Memory is sealed again with the key that matches first; the switch moves only once that worked.
                scope.launch {
                    val r = withContext(Dispatchers.IO) { store.rekey(on) }
                    val ok = r is MemoryLoad.Loaded || r == MemoryLoad.Empty
                    log(VoiceEvent.MEM_KEY, if (on) 1 else 0, if (ok) 1 else 0)
                    if (ok) saveSettings(s.copy(onLockScreen = on)) else a.say(problem(ctx, r) ?: ctx.getString(R.string.memory_key_failed))
                }
            }
            ListPreference(stringResource(R.string.memory_share), s.helpers, HelperMemory.entries, { it.text.render(langs) }, enabled = s.on,
                detail = { shareDetails[it] }) {
                saveSettings(s.copy(helpers = it))
            }
        }
        preferenceFooter(footer)
    }

    editing?.let { item ->
        ItemDialog(item, pinFull = pinnedCount >= MemoryLimits.MAX_PINNED && !item.pinned, now = now(), onDismiss = { editing = null },
            onMine = {
                editing = null
                change(ctx.getString(R.string.memory_marked_mine)) { f -> MemoryEdits.mine(f, item.id) ?: f }
            },
            onDelete = {
                editing = null
                change(ctx.getString(R.string.memory_deleted)) { f -> MemoryEdits.forget(f, setOf(item.id)).first }
                log(VoiceEvent.MEM_FORGET, 1, MemForget.SETTINGS.id)
            },
            onSave = { text, pinned, secret ->
                editing = null
                change(ctx.getString(R.string.memory_saved)) { f ->
                    var g = if (text != item.text) MemoryEdits.edit(f, item.id, text, now()) ?: f else f
                    g = g.copy(items = g.items.map { if (it.id == item.id && secret != it.secret) it.copy(secret = secret, pinned = it.pinned && !secret) else it })
                    MemoryEdits.pin(g, item.id, pinned && !secret) ?: g
                }
            })
    }
    if (adding) {
        EditDialog(stringResource(R.string.memory_add), "", stringResource(R.string.memory_add_label), stringResource(R.string.memory_add_hint), canSave = { it.isNotBlank() },
            maxLength = MemoryLimits.MAX_CHARS, secure = true, onDismiss = { adding = false }) { text ->
            adding = false
            val t = now()
            val kind = if (app.heyrobot.core.memory.MemoryCommands.prefers(text)) MemoryKind.PREFERENCE else MemoryKind.FACT
            val item = MemoryItem(MemoryIds.newId(t), text, kind, source = MemorySource.SETTINGS, createdAt = t)
            change(ctx.getString(R.string.memory_remembered)) { f -> MemoryEdits.add(f, item).file }
            log(VoiceEvent.MEM_SAVE, MemOrigin.SETTINGS.id, MemKindCode.of(item).id)
        }
    }
    if (warnExport) {
        ConfirmDialog(stringResource(R.string.memory_export_title), stringResource(R.string.memory_export_text), stringResource(R.string.memory_export_choose),
            onDismiss = { warnExport = false }) {
            warnExport = false
            try { exporter.launch("robot-memory.json") } catch (_: Exception) { a.say(ctx.getString(R.string.memory_export_no_app)) }
        }
    }
    if (confirmAll) {
        ConfirmDialog(stringResource(R.string.memory_delete_all_title), stringResource(R.string.memory_delete_all_text),
            stringResource(R.string.memory_delete_all), onDismiss = { confirmAll = false }) {
            confirmAll = false
            scope.launch {
                val n = items.size
                val ok = withContext(Dispatchers.IO) { store.deleteAll().also { if (it) store.changed() } }
                if (ok) log(VoiceEvent.MEM_FORGET, n, MemForget.ALL.id)
                a.say(ctx.getString(if (ok) R.string.memory_all_deleted else R.string.memory_not_deleted))
                tick++
            }
        }
    }
    if (askMemoryOn) {
        AlertDialog(
            onDismissRequest = { askMemoryOn = false },
            properties = SecureDialog,
            title = { Text(stringResource(R.string.memory_import_off_title)) },
            text = { Text(stringResource(R.string.memory_import_off_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askMemoryOn = false
                    saveSettings(s.copy(on = true))
                    if (importing == null) pickImport()
                }) { Text(stringResource(R.string.memory_import_off_confirm)) }
            },
            dismissButton = { TextButton(onClick = { askMemoryOn = false }) { Text(stringResource(R.string.memory_import_cancel)) } },
        )
    }
    importedUri?.let { uri ->
        // "Added 3 memories.", and the export isn't encrypted: offer to delete it.
        AlertDialog(
            onDismissRequest = { importedUri = null },
            properties = SecureDialog,
            title = { Text(if (importedCount == 0) stringResource(R.string.memory_import_added_none)
                else pluralStringResource(R.plurals.memory_import_added, importedCount, importedCount)) },
            text = { Text(stringResource(R.string.memory_import_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    importedUri = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { store.deleteImport(uri) }
                        a.say(ctx.getString(if (ok) R.string.memory_import_deleted else R.string.memory_import_not_deleted))
                    }
                }) { Text(stringResource(R.string.memory_import_delete)) }
            },
            dismissButton = { TextButton(onClick = { importedUri = null }) { Text(stringResource(R.string.memory_import_keep)) } },
        )
    }
    if (confirmStartOver) {
        ConfirmDialog(stringResource(R.string.memory_start_over_title), stringResource(R.string.memory_start_over_text), stringResource(R.string.memory_start_over),
            onDismiss = { confirmStartOver = false }) {
            confirmStartOver = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { store.startOver().also { if (it) store.changed() } }
                a.say(ctx.getString(if (ok) R.string.memory_empty_now else R.string.memory_not_set_aside))
                tick++
            }
        }
    }
    }
}

/** What a memory that couldn't be opened means, in words (the menus' language); null when it opened. */
internal fun problem(ctx: android.content.Context, l: MemoryLoad): String? = when (l) {
    MemoryLoad.Locked -> ctx.getString(R.string.memory_problem_locked)
    MemoryLoad.KeyGone -> ctx.getString(R.string.memory_problem_key_gone)
    MemoryLoad.Unreadable -> ctx.getString(R.string.memory_problem_unreadable)
    is MemoryLoad.TooNew -> ctx.getString(R.string.memory_problem_too_new)
    is MemoryLoad.Loaded, MemoryLoad.Empty -> null
}

@Composable
internal fun suggestionLabel(s: SuggestionStyle): String = stringResource(when (s) {
    SuggestionStyle.QUIET_LIST -> R.string.memory_suggest_quiet
    SuggestionStyle.END_CHIP -> R.string.memory_suggest_end_chip
    SuggestionStyle.OFF -> R.string.memory_suggest_off
})

@Composable
private fun suggestionDetail(s: SuggestionStyle): String = stringResource(when (s) {
    SuggestionStyle.QUIET_LIST -> R.string.memory_suggest_quiet_detail
    SuggestionStyle.END_CHIP -> R.string.memory_suggest_end_chip_detail
    SuggestionStyle.OFF -> R.string.memory_suggest_off_detail
})

/** [memorySummary] in words without a language yet (Settings' row: `memorySummaryText(s).str()`). */
fun memorySummaryText(s: MemorySettings): Phrase = when {
    !s.on -> MemoryStrings.summaryOff()
    s.onLockScreen -> MemoryStrings.summaryLockScreen()
    !s.useInAnswers -> MemoryStrings.summaryNotInAnswers()
    else -> MemoryStrings.summaryOn()
}

/** One item: its words (a private one too: this page is behind your unlock), its state under them, and "Imported" (1.0). */
@Composable
private fun ItemRow(i: MemoryItem, now: Long, onClick: () -> Unit) {
    // 1.4: a filing rule says so (Robot Files reads it; it never goes into an answer).
    val line = listOfNotNull(if (i.filing != null) app.heyrobot.core.i18n.FilesStrings.ruleLabel().str() else null, MemoryDates.lineText(i, now).str())
        .joinToString(" · ")
    PreferenceItem(i.text, line, if (i.secret) RIcons.Lock else if (i.filing != null) RIcons.Folder else null, onClick = onClick,
        trailing = if (i.imported != null) { { ImportedChip() } } else null)
}

/** A suggestion with Keep and Not this, each named after it for TalkBack. */
@Composable
private fun SuggestionRow(s: MemorySuggestion, keep: () -> Unit, dismiss: () -> Unit) {
    val keepNamed = stringResource(R.string.memory_keep_named, s.text)
    val notThisNamed = stringResource(R.string.memory_not_this_named, s.text)
    TwoTargetPreference(s.text, stringResource(R.string.memory_suggested), onClick = keep, onClickLabel = stringResource(R.string.memory_keep)) {
        TextButton(onClick = keep, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = keepNamed }) { Text(stringResource(R.string.memory_keep)) }
        TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = notThisNamed }) {
            Text(stringResource(R.string.memory_not_this))
        }
    }
}

/** An item: its words to edit, Pinned and Private, how often answers used it; Delete, Cancel, Save. */
@Composable
private fun ItemDialog(item: MemoryItem, pinFull: Boolean, now: Long, onDismiss: () -> Unit, onMine: () -> Unit, onDelete: () -> Unit,
    onSave: (text: String, pinned: Boolean, secret: Boolean) -> Unit) {
    var text by rememberSaveable(item.id) { mutableStateOf(item.text) }
    var pinned by rememberSaveable(item.id) { mutableStateOf(item.pinned) }
    var secret by rememberSaveable(item.id) { mutableStateOf(item.secret) }
    if (item.filing != null) return RuleDialog(item, now, onDismiss, onMine, onDelete)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(stringResource(if (item.kind == MemoryKind.PREFERENCE) R.string.memory_preference else R.string.memory_remembered_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(MemoryLimits.MAX_CHARS) }, label = { Text(stringResource(R.string.memory_what_robot_remembers)) },
                    modifier = Modifier.fillMaxWidth())
                DialogSwitch(stringResource(R.string.memory_pinned), stringResource(if (pinFull) R.string.memory_pinned_full else R.string.memory_pinned_detail),
                    pinned && !secret, enabled = !secret && !pinFull) { pinned = it }
                DialogSwitch(stringResource(R.string.memory_private), stringResource(R.string.memory_private_detail), secret) { secret = it; if (it) pinned = false }
                val from = item.sourceLabel?.let { stringResource(R.string.memory_from, it) }
                    ?: if (item.source == MemorySource.CONTENT) stringResource(R.string.memory_not_your_words) else null
                Text(listOfNotNull(MemoryDates.lineText(item, now).str(), from).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 1.0: an imported item, until you say it is yours.
                if (item.imported != null) {
                    if (item.imported == MemoryImported.CONTENT) Text(stringResource(R.string.memory_imported_content),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DialogAction(stringResource(R.string.memory_this_is_mine), stringResource(if (item.source == MemorySource.CONTENT)
                        R.string.memory_this_is_mine_content_detail else R.string.memory_this_is_mine_detail), onMine)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim(), pinned, secret) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.memory_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.memory_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) }
            }
        },
    )
}

/**
 * 1.4: a filing rule (files-1.4-plan.md §4.5): its words as Robot wrote them, whether Robot Files follows it, Delete. Never
 * edited, pinned or made private here: its words are a template, and it never goes into an answer anyway.
 */
@Composable
private fun RuleDialog(item: MemoryItem, now: Long, onDismiss: () -> Unit, onMine: () -> Unit, onDelete: () -> Unit) {
    val rule = item.filing!!
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(app.heyrobot.core.i18n.FilesStrings.ruleLabel().str()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge)
                Text(when {
                    rule.foreign != null -> app.heyrobot.core.i18n.FilesStrings.ruleNewer().str()
                    item.tainted -> app.heyrobot.core.i18n.FilesStrings.ruleNotYours().str()
                    else -> app.heyrobot.core.i18n.FilesStrings.ruleFollowed().str()
                }, style = MaterialTheme.typography.bodyMedium)
                Text(MemoryDates.lineText(item, now).str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.imported != null) {
                    if (item.imported == MemoryImported.CONTENT) Text(stringResource(R.string.memory_imported_content),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DialogAction(stringResource(R.string.memory_this_is_mine), stringResource(if (item.source == MemorySource.CONTENT)
                        R.string.memory_this_is_mine_content_detail else R.string.memory_this_is_mine_detail), onMine)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) } },
        dismissButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.memory_delete)) } },
    )
}

/** A row inside a dialog that does one thing when tapped (one TalkBack node, a button). */
@Composable
private fun DialogAction(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A switch row inside a dialog: the whole row toggles it (one TalkBack node). */
@Composable
private fun DialogSwitch(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrefSwitch(checked, enabled)
    }
}
