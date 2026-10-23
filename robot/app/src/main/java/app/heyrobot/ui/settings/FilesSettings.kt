// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.files.Cleanup
import app.heyrobot.core.files.FileCleanup
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileMoneyText
import app.heyrobot.core.files.FileReminders
import app.heyrobot.core.files.FileUsage
import app.heyrobot.core.files.FilesSettings
import app.heyrobot.core.files.FileLevel
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.sys.DevicePin
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.PinAnswer
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.SwitchPreference
import java.time.LocalDate
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.Recovery
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.FilesOut
import app.heyrobot.sys.RobotFiles
import app.heyrobot.sys.TravelReveal
import app.heyrobot.core.files.TravelMode
import app.heyrobot.core.files.FileViews
import android.app.KeyguardManager
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.sys.FilingMemory
import app.heyrobot.sys.MemorySettingsStore
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import app.heyrobot.core.files.PendingDeletes
import app.heyrobot.sys.FileDeletes

// MAIN PROCESS. Settings › Files (1.4, files-1.4-plan.md §6.6, F2): the rows that work before anything can add a file.
// What Robot Files takes (from the index when it can be read, else counted from the sealed files without the key); the
// states of §2.4 (the phone locked: "not now"; the key gone; the list damaged or a newer Robot's, with Start over); Delete
// them all behind its dialog (refused while locked); and the backup reminder's link. The index is read off the main
// thread through the vault (sys/FilesVault.kt), which answers Locked while the phone is locked. FLAG_SECURE (D17): from
// F7 on this page and the Files screens show your files' names. Every change tells :voice (Cmd.FILES).

/** What the page does with Robot Files; the phone's is [PhoneFilesStore], tests keep theirs. Every call off the main thread. */
interface FilesStore {
    fun load(): IndexLoad
    /** What the sealed files take, counted without the key. */
    fun usage(): FileUsage
    fun deleteAll(): Boolean
    fun startOver(): Recovery?
    /** The phone is locked now: nothing is read, and Delete them all waits. */
    fun locked(): Boolean
    /** Robot Files changed: :voice drops what it holds. */
    fun changed()
    /** F8: how many filing rules memory holds (null: memory can't be read now); the phone's reads memory (sys/FilingMemory). */
    fun rules(): Int? = null
    /** F8: Memory is on (Settings › Memory): off, Robot keeps no filing rules. */
    fun memoryOn(): Boolean = true
    /** F10: Settings › Files' values (files_settings.json). */
    fun settings(): FilesSettings = FilesSettings()
    /**
     * F10: saved; false for a newer Robot's file or a failed write. FX1: [pin], your PIN just confirmed it (travel mode
     * off, a drawer unhidden while it is on); without it, travel mode as the file has it stays on (FilesSettingsStore).
     * FX3: [change] is applied to the file as it is then (under its lock), so only the fields it changes are written.
     */
    fun save(pin: Boolean = false, change: (FilesSettings) -> FilesSettings): Boolean = false
    /** F10: a clean-up suggestion's Delete, after its dialog: [id] deleted (false: it wasn't). */
    fun delete(id: FileId): Boolean = false

    /** F12: the phone has a screen lock (a PIN, pattern or password) to confirm travel mode's changes with. */
    fun secure(): Boolean = true
    /**
     * FX1 (files-1.4-review.md finding 1): asks for the phone's PIN, pattern or password only, never a fingerprint or a face
     * (sys/DevicePin), over [host] (the activity in front); [done] gets the answer on the main thread. Every confirm of
     * travel mode goes through here.
     */
    fun confirm(host: Context, title: String, text: String, done: (PinAnswer) -> Unit) = done(PinAnswer.NO_SCREEN)
    /** F12: your PIN was just confirmed: hidden drawers show in this app's Files screens for a while (TravelReveal). */
    fun reveal() {}
    /** F12: hidden drawers show now (TravelReveal). */
    fun revealed(): Boolean = false
    /** F12: travel mode was turned on: whatever was shown is hidden again at once. */
    fun hideAgain() {}
}

/** The phone's: this process's vault. */
class PhoneFilesStore(ctx: Context) : FilesStore {
    private val app = ctx.applicationContext
    private val vault = RobotFiles.vault(app)

    init {
        // The main app reads Robot Files' index from its first use on (F7).
        RobotFiles.use(app)
    }

    // F12: the index as travel mode shows it (its counts, the clean-up suggestions, the drawers you can hide).
    override fun load(): IndexLoad = vault.load().let { l -> if (l is IndexLoad.Loaded) IndexLoad.Loaded(TravelMode.shown(l.index, settings(), TravelReveal.on()), l.dropped) else l }
    override fun usage() = vault.usage()
    override fun deleteAll() = vault.deleteAll().also { if (it) FilesOut.clear(app) }
    override fun startOver() = vault.startOver()
    override fun locked() = RobotFiles.locked(app)
    override fun changed() = Cmd.send(app, Cmd.FILES)
    // FX1 (finding 6): rules naming a drawer travel mode hides now aren't counted.
    override fun rules(): Int? = settings().let { s -> FilingMemory.count(app) { d -> TravelMode.hidden(d, s, TravelReveal.on()) } }
    override fun memoryOn(): Boolean = MemorySettingsStore.current(app).on
    override fun settings(): FilesSettings = FilesSettingsStore.current(app)
    override fun save(pin: Boolean, change: (FilesSettings) -> FilesSettings): Boolean = FilesSettingsStore.update(app, pin, change)
    // FX1 (finding 8): an exception counts as a screen lock (fail closed): turning travel mode off then still asks for the PIN.
    override fun secure(): Boolean = try { app.getSystemService(KeyguardManager::class.java).isDeviceSecure } catch (_: Exception) { true }
    override fun confirm(host: Context, title: String, text: String, done: (PinAnswer) -> Unit) = DevicePin.ask(host, title, text, done)
    override fun reveal() = TravelReveal.start()
    override fun revealed(): Boolean = TravelReveal.on()
    override fun hideAgain() = TravelReveal.end()

    override fun delete(id: FileId): Boolean {
        FilesOut.revoke(app, id)
        return try { (vault.delete(listOf(id))?.removed ?: 0) > 0 } catch (t: Exception) { RLog.w("files_settings_delete", t); false }
    }
}

/** Settings › Files on the phone (SettingsNav). */
@Composable
fun FilesSettingsRoute(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PhoneFilesStore(ctx) }
    FilesSettingsScreen(store, a, snackbar, onBack)
}

@Composable
fun FilesSettingsScreen(store: FilesStore, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit,
    deletes: PendingDeletes = FileDeletes.here, undoMs: Long = PendingDeletes.UNDO_MS) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<IndexLoad?>(null) }
    var usage by remember { mutableStateOf<FileUsage?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    /** F8: the filing rules' line (§6.6): Memory on and how many, read only while unlocked. */
    var rules by remember { mutableStateOf<Pair<Boolean, Int?>?>(null) }
    /** F10: the reminders, brief and clean-up rows' values, and the clean-up suggestions (read only while unlocked). */
    var fs by remember { mutableStateOf<FilesSettings?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<Cleanup>()) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    // FX1 (finding 9): back in view, read again: shown hidden drawers end when Robot goes to the background (MainActivity).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // FX2 (files-1.4-review.md K1): a suggestion deleted waits 10 seconds for its Undo, out of the list meanwhile, in the same
    // holder as the Files tab's deletes (core PendingDeletes); it goes through sooner when you leave or Robot goes to the background.
    var hidden by remember { mutableStateOf(deletes.hidden()) }
    androidx.compose.runtime.DisposableEffect(deletes, owner) {
        val stop = deletes.watch { h -> hidden = h }
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) deletes.commitAll() }
        owner.lifecycle.addObserver(o)
        onDispose {
            stop()
            owner.lifecycle.removeObserver(o)
            deletes.commitAll()
        }
    }
    LaunchedEffect(owner) {
        var first = true
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            if (!first) tick++
            first = false
        }
    }
    LaunchedEffect(tick) {
        rules = withContext(Dispatchers.IO) { if (store.locked()) null else store.memoryOn() to store.rules() }
        fs = withContext(Dispatchers.IO) { store.settings() }
        val (l, u) = withContext(Dispatchers.IO) {
            val l = store.load()
            // Locked (the lock screen, or a phone lock: someone else may hold the phone): nothing about the files, not even
            // how many. The other states come only while unlocked; there the sealed files are counted without the key.
            l to when (l) {
                is IndexLoad.Loaded -> FileUsage.of(l.index)
                IndexLoad.Empty -> FileUsage(0, 0)
                IndexLoad.Locked -> null
                // F12: no count with travel mode on (the sealed files include the hidden drawers').
                else -> if (TravelMode.active(store.settings())) null else store.usage()
            }
        }
        load = l
        usage = u
        // F10 (§7.4): worked out when the page opens, never a notification; nothing about the files while locked.
        suggestions = if (l is IndexLoad.Loaded && fs?.cleanup == true) withContext(Dispatchers.Default) { FileCleanup.suggest(l.index, LocalDate.now()) } else emptyList()
    }

    // FX3: a change, not the page's copy: applied to the file as it is when saved (a restore's finish may have written
    // other fields since the page read it), and to the page's copy for the rows meanwhile.
    fun saveSettings(pin: Boolean = false, change: (FilesSettings) -> FilesSettings) {
        fs = fs?.let(change)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.save(pin, change) }
            if (!ok) a.say(ctx.getString(R.string.files_not_saved))
            tick++
        }
    }

    // F12, travel mode: hiding needs nothing; turning it off, no longer hiding a drawer while it is on, and showing hidden
    // drawers need the phone's PIN, every time. With no screen lock left (it was removed after travel mode was turned on),
    // turning it off needs nothing: removing the lock already took the PIN. FX1 (finding 1): the PIN, pattern or password
    // only, through the store's one confirm (sys/DevicePin: Android's BiometricPrompt allowing DEVICE_CREDENTIAL alone).
    fun confirmed(ask: TravelAsk) {
        when (ask) {
            TravelAsk.Off -> saveSettings(pin = true) { it.copy(travel = false) }
            is TravelAsk.Unhide -> saveSettings(pin = true) { it.copy(hidden = it.hidden - ask.drawer) }
            TravelAsk.Show -> {
                store.reveal()
                a.say(ctx.getString(R.string.files_hidden_shown))
                tick++
            }
        }
    }
    fun confirm(ask: TravelAsk) {
        val (title, text) = ctx.getString(R.string.files_confirm_title) to ctx.getString(when (ask) {
            TravelAsk.Off -> R.string.files_confirm_off
            is TravelAsk.Unhide -> R.string.files_confirm_unhide
            TravelAsk.Show -> R.string.files_confirm_show
        })
        // No screen lock (removed since travel mode was turned on): only turning it off goes on, nothing else can be confirmed.
        if (!store.secure()) {
            if (ask == TravelAsk.Off) saveSettings(pin = true) { it.copy(travel = false) } else a.say(ctx.getString(R.string.files_travel_need_lock))
            return
        }
        // A screen lock but no confirm screen (Android refused it): nothing changes, never without the PIN.
        store.confirm(ctx, title, text) { r ->
            when (r) {
                PinAnswer.CONFIRMED -> confirmed(ask)
                PinAnswer.NOT_CONFIRMED -> a.say(ctx.getString(R.string.files_not_confirmed))
                PinAnswer.NO_SCREEN -> a.say(ctx.getString(R.string.files_no_pin_screen))
            }
        }
    }

    val title = UiStrings.files().str()
    val lang = LocalLangs.current.ui
    val storage = stringResource(R.string.files_storage)
    val yours = stringResource(R.string.files_yours)
    val usageText = usage?.let { usageText(ctx, it, lang) }
    val footer = stringResource(R.string.files_footer)
    val cleanupTitle = stringResource(R.string.files_cleanup_list)
    val filingTitle = stringResource(R.string.files_filing_title)
    val travelTitle = stringResource(R.string.files_travel_title)
    val hiddenTitle = stringResource(R.string.files_hidden_title)

    SecureWindow()
    PreferenceScreen(title, onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.files_intro)) }
        val l = load
        when {
            l == null -> item(key = "reading") { IntroPreference(stringResource(R.string.files_opening)) }
            l is IndexLoad.Loaded || l == IndexLoad.Empty -> {
                preferenceGroup(null, "storage") {
                    PreferenceItem(storage, usageText, RIcons.Folder)
                }
                // F10 (§6.6): reminders from files, the brief, clean-up suggestions.
                fs?.let { f ->
                    preferenceGroup(null, "money") {
                        val askDetail = stringResource(R.string.files_reminders_detail)
                        ListPreference(stringResource(R.string.files_reminders_row), f.reminders, FileReminders.entries, { ctx.getString(remindersTitle(it)) },
                            icon = RIcons.Alarm, summary = ctx.getString(remindersTitle(f.reminders)) + " · " + askDetail) { v -> saveSettings { it.copy(reminders = v) } } // l10n: not text (a separator)
                        SwitchPreference(stringResource(R.string.files_brief_row), stringResource(R.string.files_brief_detail), f.brief) { v -> saveSettings { it.copy(brief = v) } }
                        SwitchPreference(stringResource(R.string.files_cleanup_row), stringResource(R.string.files_cleanup_detail), f.cleanup) { v -> saveSettings { it.copy(cleanup = v) } }
                    }
                }
                if (fs?.cleanup == true && suggestions.any { it.entry.id !in hidden }) preferenceGroup(cleanupTitle, "cleanup") {
                    // A tap opens the delete dialog: a suggestion never deletes by itself (D9).
                    for (c in suggestions) if (c.entry.id !in hidden) PreferenceItem(c.entry.name.ifBlank { FilesStrings.noName().str() }, FileMoneyText.why(c).str(), RIcons.Delete,
                        enabled = !busy, onClick = { deleting = c.entry })
                }
                if ((usage?.files ?: 0) > 0) preferenceGroup(yours, "actions") {
                    PreferenceItem(stringResource(R.string.files_delete_all), stringResource(R.string.files_delete_all_detail), RIcons.Delete,
                        enabled = !busy, onClick = { confirmAll = true })
                }
                // FX2 (files-1.4-review.md finding 4): §6.6's decided rows (beside the filing rules' line below), read by filing, intake and copies handed out but with
                // no row until now: Robot Files on or off, each kind's filing level, reading text in images, stripping location.
                // Saved as the rows below are (travel mode as the file has it stays: FilesSettingsStore); none of them shows a file.
                fs?.let { f ->
                    preferenceGroup(null, "on") {
                        SwitchPreference(stringResource(R.string.files_on_row), stringResource(R.string.files_on_detail), f.on) { v -> saveSettings { it.copy(on = v) } }
                    }
                    preferenceGroup(filingTitle, "filing") {
                        LevelRow(stringResource(R.string.files_level_receipts), null, f.receipts) { v -> saveSettings { it.copy(receipts = v) } }
                        LevelRow(stringResource(R.string.files_level_bills), null, f.bills) { v -> saveSettings { it.copy(bills = v) } }
                        LevelRow(stringResource(R.string.files_level_documents), stringResource(R.string.files_level_documents_detail), f.documents) { v -> saveSettings { it.copy(documents = v) } }
                        LevelRow(stringResource(R.string.files_level_photos), null, f.photos) { v -> saveSettings { it.copy(photos = v) } }
                        LevelRow(stringResource(R.string.files_level_other), stringResource(R.string.files_level_other_detail), f.other) { v -> saveSettings { it.copy(other = v) } }
                        SwitchPreference(stringResource(R.string.files_read_text_row), stringResource(R.string.files_read_text_detail), f.readImageText) { v -> saveSettings { it.copy(readImageText = v) } }
                        SwitchPreference(stringResource(R.string.files_strip_row), stringResource(R.string.files_strip_detail), f.stripLocation) { v -> saveSettings { it.copy(stripLocation = v) } }
                    }
                }
            }
            else -> preferenceGroup(null, "problem") {
                PreferenceBlock { Text(problemText(ctx, l), style = MaterialTheme.typography.bodyLarge) }
                if (usage != null) PreferenceItem(storage, usageText, RIcons.Folder)
                when (l) {
                    IndexLoad.KeyGone -> {
                        PreferenceItem(stringResource(R.string.files_restore), stringResource(R.string.files_restore_detail, UiStrings.backup().str()), RIcons.Refresh,
                            onClick = { a.openPage(Page.Settings(SettingsRoutes.BACKUP)) })
                        PreferenceItem(stringResource(R.string.files_delete_all), stringResource(R.string.files_delete_all_detail), RIcons.Delete,
                            enabled = !busy, onClick = { confirmAll = true })
                    }
                    IndexLoad.Unreadable, is IndexLoad.TooNew -> {
                        PreferenceItem(stringResource(R.string.files_start_over), stringResource(R.string.files_start_over_detail), RIcons.Refresh,
                            enabled = !busy, onClick = { confirmStartOver = true })
                        PreferenceItem(stringResource(R.string.files_delete_all), stringResource(R.string.files_delete_all_detail), RIcons.Delete,
                            enabled = !busy, onClick = { confirmAll = true })
                    }
                    // Locked, or the Keystore busy: not now. Nothing to delete or start over from here.
                    else -> PreferenceItem(stringResource(R.string.files_try_again), null, RIcons.Refresh, onClick = { tick++ })
                }
            }
        }
        // F8: your filing rules live in memory: their count and a link there, or why Robot keeps none.
        val r = rules
        if (r != null && l != null && l != IndexLoad.Locked && (!r.first || r.second != null)) preferenceGroup(null, "rules") {
            val page = app.heyrobot.core.i18n.Arg.Ui(UiStrings.inSettings(UiStrings.memory))
            PreferenceItem(app.heyrobot.core.i18n.FilesStrings.rules().str(),
                if (!r.first) app.heyrobot.core.i18n.FilesStrings.ruleMemoryOff().str() else app.heyrobot.core.i18n.FilesStrings.ruleCount(r.second ?: 0, "page" to page).str(),
                RIcons.Folder, onClick = { a.openPage(Page.Settings(SettingsRoutes.MEMORY)) })
        }
        preferenceGroup(null, "backup") {
            PreferenceItem(stringResource(R.string.files_backup), stringResource(R.string.files_backup_detail, UiStrings.backup().str()), RIcons.Alarm,
                onClick = { a.openPage(Page.Settings(SettingsRoutes.BACKUP)) })
        }
        // F12 (§6.6): travel mode and the drawers it hides, at the page's end. While it hides them, they aren't listed (the
        // view leaves them out) until your PIN shows them; hiding another drawer needs nothing.
        if (l is IndexLoad.Loaded || l == IndexLoad.Empty) fs?.let { f ->
            val idx = (l as? IndexLoad.Loaded)?.index ?: app.heyrobot.core.files.FileIndex()
            val all = !f.travel || store.revealed()
            preferenceGroup(travelTitle, "travel") {
                SwitchPreference(travelTitle, stringResource(R.string.files_travel_detail), f.travel) { on ->
                    when {
                        !on -> confirm(TravelAsk.Off)
                        !store.secure() -> a.say(ctx.getString(R.string.files_travel_need_lock))
                        f.hidden.isEmpty() -> a.say(ctx.getString(R.string.files_travel_choose))
                        else -> { store.hideAgain(); saveSettings { it.copy(travel = true) } }
                    }
                }
                if (!all) PreferenceItem(stringResource(R.string.files_show_hidden), stringResource(R.string.files_show_hidden_detail), RIcons.Lock,
                    onClick = { confirm(TravelAsk.Show) })
            }
            preferenceGroup(hiddenTitle, "hidden") {
                for (d in FileViews.drawers(idx)) SwitchPreference(FilesStrings.drawer(d.id).str(), null, d.id in f.hidden) { hide ->
                    if (hide) { if (TravelMode.hide(f, d.id) != null) saveSettings { TravelMode.hide(it, d.id) ?: it } }
                    else if (f.travel) confirm(TravelAsk.Unhide(d.id))
                    else saveSettings { it.copy(hidden = it.hidden - d.id) }
                }
                if (!all) PreferenceBlock { Text(stringResource(R.string.files_hidden_more), style = MaterialTheme.typography.bodyMedium) }
                PreferenceBlock { Text(stringResource(R.string.files_travel_note), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        preferenceFooter(footer)
    }

    if (confirmAll) {
        ConfirmDialog(stringResource(R.string.files_delete_all_title), stringResource(R.string.files_delete_all_text), stringResource(R.string.files_delete_all),
            onDismiss = { confirmAll = false }) {
            confirmAll = false
            if (busy) return@ConfirmDialog
            busy = true
            scope.launch {
                // Checked again at the tap: the phone may have locked while the dialog showed.
                val ok = withContext(Dispatchers.IO) { if (store.locked()) null else store.deleteAll().also { if (it) store.changed() } }
                busy = false
                a.say(ctx.getString(when (ok) {
                    null -> R.string.files_locked
                    true -> R.string.files_all_deleted
                    false -> R.string.files_not_deleted
                }))
                tick++
            }
        }
    }
    deleting?.let { e ->
        // F10: one file, its own dialog (secure: it names the file); the lock checked again at the tap, as Delete them all.
        SecureConfirm(stringResource(R.string.files_delete_title, e.name.ifBlank { FilesStrings.noName().str() }), stringResource(R.string.files_delete_text),
            stringResource(R.string.files_delete), stringResource(R.string.files_keep), onDismiss = { deleting = null }) {
            deleting = null
            scope.launch {
                // Checked again at the tap: the phone may have locked while the dialog showed.
                if (withContext(Dispatchers.IO) { store.locked() }) {
                    a.say(ctx.getString(R.string.files_locked))
                    tick++
                    return@launch
                }
                // FX2 (K1): deleted when its Undo runs out (or sooner: leaving, the background, the screen off), never before.
                val token = deletes.start(listOf(e.id), undoMs, delete = { ids -> store.delete(ids.single()).also { if (it) store.changed() } }) { ok ->
                    if (!ok) scope.launch { a.say(ctx.getString(R.string.files_not_deleted_one)); tick++ }
                }
                val r = withTimeoutOrNull(undoMs) {
                    snackbar.showSnackbar(ctx.getString(R.string.files_deleted), FilesStrings.undo().render(app.heyrobot.sys.AppLanguage.langs(ctx)), duration = SnackbarDuration.Indefinite)
                }
                if (r == SnackbarResult.ActionPerformed) {
                    a.say(if (deletes.undo(token)) ctx.getString(R.string.files_undeleted) else FilesStrings.notUndone().render(app.heyrobot.sys.AppLanguage.langs(ctx)))
                }
            }
        }
    }
    if (confirmStartOver) {
        ConfirmDialog(stringResource(R.string.files_start_over_title), stringResource(R.string.files_start_over_text), stringResource(R.string.files_start_over),
            onDismiss = { confirmStartOver = false }) {
            confirmStartOver = false
            if (busy) return@ConfirmDialog
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { store.startOver() }.getOrNull().also { if (it != null) store.changed() } }
                busy = false
                val n = r?.let { (it.load as? IndexLoad.Loaded)?.index?.entries?.size ?: 0 }
                a.say(if (n == null) ctx.getString(R.string.files_not_started_over) else ctx.resources.getQuantityString(R.plurals.files_started_over, n, n))
                tick++
            }
        }
    }
}

/** F12: what the phone's PIN is asked to confirm in Settings › Files. */
sealed class TravelAsk {
    data object Off : TravelAsk()
    data class Unhide(val drawer: String) : TravelAsk()
    data object Show : TravelAsk()
}

/** FX2 (finding 4): a kind's filing level (§4.1): File it on its own, Ask me first, Off; [detail] says which kinds it covers. */
@Composable
private fun LevelRow(title: String, detail: String?, value: FileLevel, onPick: (FileLevel) -> Unit) {
    val ctx = LocalContext.current
    val now = ctx.getString(levelTitle(value))
    ListPreference(title, value, FileLevel.entries, { ctx.getString(levelTitle(it)) }, summary = if (detail == null) now else "$now · $detail", onPick = onPick) // l10n: not text (a separator)
}

private fun levelTitle(l: FileLevel): Int = when (l) {
    FileLevel.AUTO -> R.string.files_level_auto
    FileLevel.ASK -> R.string.files_level_ask
    FileLevel.OFF -> R.string.files_level_off
}

/** F10: Reminders from files' choices, in words. */
private fun remindersTitle(r: FileReminders): Int = when (r) {
    FileReminders.ASK -> R.string.files_reminders_ask
    FileReminders.BRIEF -> R.string.files_reminders_brief
    FileReminders.OFF -> R.string.files_reminders_off
}

/** A dialog in a window of its own that is never in a screenshot (it names a file), whatever the page under it. */
@Composable
private fun SecureConfirm(title: String, text: String, confirm: String, dismiss: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(dismiss) } },
        title = { Text(title) }, text = { Text(text) },
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn))
}

/** What an index that couldn't be read means, in words (the menus' language). */
private fun problemText(ctx: Context, l: IndexLoad): String = ctx.getString(when (l) {
    IndexLoad.KeyGone -> R.string.files_key_gone
    IndexLoad.Unreadable -> R.string.files_unreadable
    is IndexLoad.TooNew -> R.string.files_too_new
    else -> R.string.files_locked
})

/** "212 files · 1.3 GB", or "No files yet". */
private fun usageText(ctx: Context, u: FileUsage, lang: app.heyrobot.core.i18n.Lang): String =
    if (u.files == 0) ctx.getString(R.string.files_none_yet)
    else ctx.resources.getQuantityString(R.plurals.files_usage, u.files, u.files, Fmt.bytes(u.bytes, lang))

/**
 * Settings' Files row: what Robot Files takes, counted from the sealed files without the key, off the main thread once
 * there is anything (nothing to count before the first file). FX1 (files-1.4-review.md finding 7): travel mode is read
 * first, and the row says nothing until it is known, so it never shows a count that holds the hidden drawers' files.
 */
@Composable
internal fun filesSummary(): String {
    val ctx = LocalContext.current
    val vault = remember { RobotFiles.vault(ctx) }
    val lang = LocalLangs.current.ui
    // F12: with travel mode on, no count (the sealed files include the hidden drawers'): it says travel mode is on.
    val row by produceState<FilesRow?>(null) {
        value = withContext(Dispatchers.IO) {
            when {
                TravelMode.active(app.heyrobot.sys.FilesSettingsStore.current(ctx)) -> FilesRow.Travel
                !vault.exists() -> FilesRow.Usage(FileUsage(0, 0))
                // Nothing about the files while the phone is locked (a phone lock leaves Robot's own screens open).
                RobotFiles.locked(ctx) -> null
                else -> FilesRow.Usage(vault.usage())
            }
        }
    }
    return when (val r = row) {
        FilesRow.Travel -> FilesStrings.travelOnRow().str()
        is FilesRow.Usage -> usageText(ctx, r.usage, lang)
        null -> ""
    }
}

/** FX1: what Settings' Files row says, once read: travel mode is on, or what the files take. */
private sealed class FilesRow {
    data object Travel : FilesRow()
    data class Usage(val usage: FileUsage) : FilesRow()
}
