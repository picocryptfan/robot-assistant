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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.i18n.SettingsStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.lists.ListsSettingsFile
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.ListsFiles
import app.heyrobot.sys.ListsFollow
import app.heyrobot.sys.ListsSettingsStore
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Lists (1.4, lists-plan.md §4.5, §7.5; LS2): Lists on or off; adding while the phone is locked
// (on, Q3) and hearing and ticking on the lock screen (off), each of which first changes how the lists are sealed and
// whether the journal is kept (ListsVault.follow), then is saved, so the switch moves only once that worked; what the lists
// take; Delete all lists behind its dialog (refused while locked); and the states of §4.5 (not now; the key gone, with
// Restore and Start over; damaged or a newer Robot's, with Start over). The lists are read off the main thread through the
// vault (sys/ListsVault.kt), which answers Locked while the phone is locked; the page shows counts, never a list's name or
// an item. Every change tells :voice (Cmd.LISTS).

/** What the page does with your lists; the phone's is [PhoneListsStore], tests keep theirs. Every call off the main thread. */
interface ListsStore {
    /** The lists as they can be opened now (never made here: the first-use lists come with the first use). */
    fun load(): ListsLoad<ListsFile>
    fun settings(): ListsSettings
    /** Whether lists_settings.json may be written (never over a newer Robot's). */
    fun canSave(): Boolean
    /** [change] saved over the file as it is; false when it wasn't. */
    fun save(change: (ListsSettings) -> ListsSettings): Boolean
    /** The lock switches turned to [s]: the seal and the journal made to match first (ListsVault.follow). */
    fun follow(s: ListsSettings): ListsFollow
    fun deleteAll(): Boolean
    fun startOver(): Boolean
    /** The phone is locked now: nothing is read, and Delete all lists waits. */
    fun locked(): Boolean
    /** The lists or their settings changed: :voice drops what it holds. */
    fun changed()
}

/** The phone's: this process's vault and lists_settings.json. */
class PhoneListsStore(ctx: Context) : ListsStore {
    private val app = ctx.applicationContext
    private val vault = ListsFiles.vault(app)

    override fun load() = vault.load(firstUse = false)
    override fun settings() = ListsSettingsStore.current(app)
    override fun canSave() = ListsSettingsStore.read(app) !is ListsSettingsFile.TooNew
    override fun save(change: (ListsSettings) -> ListsSettings) = ListsSettingsStore.update(app, change)
    override fun follow(s: ListsSettings) = vault.follow(s)
    override fun deleteAll() = vault.deleteAll()
    override fun startOver() = vault.startOver()
    override fun locked() = ListsFiles.locked(app)
    override fun changed() = Cmd.send(app, Cmd.LISTS)
}

/** Settings › Lists on the phone (SettingsNav). */
@Composable
fun ListsSettingsRoute(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PhoneListsStore(ctx) }
    ListsSettingsScreen(store, a, snackbar, onBack)
}

@Composable
fun ListsSettingsScreen(store: ListsStore, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<ListsLoad<ListsFile>?>(null) }
    var s by remember { mutableStateOf<ListsSettings?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    // Back in view, read again (the panel may have changed the lists meanwhile).
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        var first = true
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            if (!first) tick++
            first = false
        }
    }
    LaunchedEffect(tick) {
        s = withContext(Dispatchers.IO) { store.settings() }
        load = withContext(Dispatchers.IO) { store.load() }
    }

    fun save(change: (ListsSettings) -> ListsSettings) {
        s = s?.let(change)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.save(change) }
            if (!ok) a.say(ctx.getString(R.string.lists_not_saved))
            tick++
        }
    }

    /** A lock switch: the seal and the journal first, then the switch saved (a crash between leaves it as it was). */
    fun lockSwitch(change: (ListsSettings) -> ListsSettings) {
        val now = s ?: return
        if (busy) return
        busy = true
        scope.launch {
            // Never over a newer Robot's settings file: the seal isn't changed when the switch couldn't be saved.
            val (savable, r) = withContext(Dispatchers.IO) {
                when {
                    store.locked() -> true to null
                    !store.canSave() -> false to null
                    else -> true to store.follow(change(now))
                }
            }
            busy = false
            when {
                !savable -> a.say(ctx.getString(R.string.lists_not_saved))
                r == null -> a.say(ctx.getString(R.string.lists_locked))
                // Saved in this same step, then :voice told (it drops its RAM copy and reads the switch again).
                r is ListsFollow.Done -> {
                    s = s?.let(change)
                    if (!withContext(Dispatchers.IO) { store.save(change) }) a.say(ctx.getString(R.string.lists_not_saved))
                    store.changed()
                }
                r is ListsFollow.Waiting -> a.say(ctx.getString(R.string.lists_waiting))
                else -> a.say(ctx.getString(R.string.lists_key_failed))
            }
            tick++
        }
    }

    val title = stringResource(R.string.lists_title)
    val storage = stringResource(R.string.lists_storage)
    val yours = stringResource(R.string.lists_yours)
    val whenLocked = stringResource(R.string.lists_when_locked)
    // LS6: and what a home-screen widget shows to the app that draws it.
    val footer = stringResource(R.string.lists_two_lines, stringResource(R.string.lists_footer), stringResource(R.string.lists_widget_footer))

    PreferenceScreen(title, onBack, snackbar = snackbar) {
        val now = s
        if (now != null) item(key = "main") { MainSwitchBar(stringResource(R.string.lists_use), now.on) { v -> save { it.copy(on = v) } } }
        item(key = "intro") { IntroPreference(stringResource(R.string.lists_intro)) }
        val l = load
        when {
            l == null -> item(key = "reading") { IntroPreference(stringResource(R.string.lists_opening)) }
            l is ListsLoad.Loaded || l == ListsLoad.Empty -> preferenceGroup(yours, "yours") {
                val file = (l as? ListsLoad.Loaded)?.value
                PreferenceItem(storage, usageText(ctx, file), RIcons.Checklist)
                if (file != null && file.lists.isNotEmpty()) PreferenceItem(stringResource(R.string.lists_delete_all), stringResource(R.string.lists_delete_all_detail),
                    RIcons.Delete, enabled = !busy, onClick = { confirmAll = true })
            }
            else -> preferenceGroup(null, "problem") {
                PreferenceBlock { Text(problemText(ctx, l), style = MaterialTheme.typography.bodyLarge) }
                when (l) {
                    ListsLoad.KeyGone, ListsLoad.Unreadable, is ListsLoad.TooNew -> {
                        if (l == ListsLoad.KeyGone) PreferenceItem(stringResource(R.string.lists_restore), stringResource(R.string.lists_restore_detail, UiStrings.backup().str()),
                            RIcons.Refresh, onClick = { a.openPage(Page.Settings(SettingsRoutes.BACKUP)) })
                        PreferenceItem(stringResource(R.string.lists_start_over), stringResource(R.string.lists_start_over_detail), RIcons.Refresh,
                            enabled = !busy, onClick = { confirmStartOver = true })
                        PreferenceItem(stringResource(R.string.lists_delete_all), stringResource(R.string.lists_delete_all_detail), RIcons.Delete,
                            enabled = !busy, onClick = { confirmAll = true })
                    }
                    // Locked, or the Keystore busy: not now. Nothing to delete or start over from here.
                    else -> PreferenceItem(stringResource(R.string.lists_try_again), null, RIcons.Refresh, onClick = { tick++ })
                }
            }
        }
        if (now != null) preferenceGroup(whenLocked, "locked") {
            SwitchPreference(stringResource(R.string.lists_add_locked), stringResource(R.string.lists_add_locked_detail), now.addLocked, enabled = now.on && !busy) { v ->
                lockSwitch { it.copy(addLocked = v) }
            }
            SwitchPreference(stringResource(R.string.lists_lock_screen), stringResource(R.string.lists_lock_screen_detail), now.lockScreen, enabled = now.on && !busy) { v ->
                lockSwitch { it.copy(lockScreen = v) }
            }
        }
        preferenceFooter(footer)
    }

    if (confirmAll) {
        ConfirmDialog(stringResource(R.string.lists_delete_all_title), stringResource(R.string.lists_delete_all_text), stringResource(R.string.lists_delete_all),
            onDismiss = { confirmAll = false }) {
            confirmAll = false
            if (busy) return@ConfirmDialog
            busy = true
            scope.launch {
                // Checked again at the tap: the phone may have locked while the dialog showed.
                val ok = withContext(Dispatchers.IO) { if (store.locked()) null else store.deleteAll().also { if (it) store.changed() } }
                busy = false
                a.say(ctx.getString(when (ok) {
                    null -> R.string.lists_locked
                    true -> R.string.lists_all_deleted
                    false -> R.string.lists_not_deleted
                }))
                tick++
            }
        }
    }
    if (confirmStartOver) {
        ConfirmDialog(stringResource(R.string.lists_start_over_title), stringResource(R.string.lists_start_over_text), stringResource(R.string.lists_start_over),
            onDismiss = { confirmStartOver = false }) {
            confirmStartOver = false
            if (busy) return@ConfirmDialog
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { if (store.locked()) null else store.startOver().also { if (it) store.changed() } }
                busy = false
                a.say(ctx.getString(when (ok) {
                    null -> R.string.lists_locked
                    true -> R.string.lists_started_over
                    false -> R.string.lists_not_started_over
                }))
                tick++
            }
        }
    }
}

/** What lists that couldn't be opened mean, in words (the menus' language). */
private fun problemText(ctx: Context, l: ListsLoad<*>): String = ctx.getString(when (l) {
    ListsLoad.KeyGone -> R.string.lists_key_gone
    ListsLoad.Unreadable -> R.string.lists_unreadable
    is ListsLoad.TooNew -> R.string.lists_too_new
    else -> R.string.lists_not_now
})

/** "4 lists · 63 items" (items not removed, done ones included), or "No lists yet". */
private fun usageText(ctx: Context, f: ListsFile?): String {
    val lists = f?.lists.orEmpty()
    if (lists.isEmpty()) return ctx.getString(R.string.lists_none_yet)
    val items = lists.sumOf { l -> l.items.count { it.live } }
    return ctx.resources.getQuantityString(R.plurals.lists_usage, lists.size, lists.size, ctx.resources.getQuantityString(R.plurals.lists_items, items, items))
}

/**
 * Settings' Lists row: Off, or what the lists take, read off the main thread through the vault (never making the first-use
 * lists); nothing while the phone is locked or the lists can't be opened.
 */
@Composable
internal fun listsSummary(): String {
    val ctx = LocalContext.current
    val row by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            when {
                !ListsSettingsStore.current(ctx).on -> SettingsStrings.off().render(app.heyrobot.sys.AppLanguage.langs(ctx))
                ListsFiles.locked(ctx) -> null
                else -> when (val l = ListsFiles.vault(ctx).load(firstUse = false)) {
                    is ListsLoad.Loaded -> usageText(ctx, l.value)
                    ListsLoad.Empty -> usageText(ctx, null)
                    else -> null
                }
            }
        }
    }
    return row ?: ""
}
