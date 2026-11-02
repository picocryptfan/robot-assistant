// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.lists

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import app.heyrobot.ui.common.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ListsStrings
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ItemOp
import app.heyrobot.core.lists.ItemOutcome
import app.heyrobot.core.lists.ItemText
import app.heyrobot.core.lists.ItemsRead
import app.heyrobot.core.lists.ListChange
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkNames
import app.heyrobot.core.link.ListShareRec
import app.heyrobot.core.link.ListShareState
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListsShared
import app.heyrobot.core.lists.ListItem
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsLimits
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsNames
import app.heyrobot.core.lists.ListsRefusal
import app.heyrobot.core.lists.ListsShare
import app.heyrobot.core.lists.ListsWho
import app.heyrobot.core.lists.ItemProblem
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.LinkClient
import app.heyrobot.sys.ListShareSync
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.ListsFiles
import app.heyrobot.sys.ListsSettingsStore
import app.heyrobot.sys.ListsWrite
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.BackBar
import app.heyrobot.ui.Page
import app.heyrobot.ui.ScreenTitle
import app.heyrobot.ui.common.DropdownMenu
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureDialog
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

// MAIN PROCESS. Robot Lists' screens (lists-plan.md §7.1 to §7.3, Q1, D13; job LS5): the Lists section of the Notes tab
// (each list with what's left on it, New list) and a list's page (the add field, tick boxes, Done, edit, Move to top, Move
// to another list, Delete with a 10-second Undo, an item's details with its history; Rename, Share as text, Delete list).
// Every read and change goes through the vault (sys/ListsVault.kt) off the main thread: while the phone is locked it
// answers Locked and the screens say to unlock. Both screens are FLAG_SECURE (SecureWindow), their dialogs too, as Memory's
// and Files' are. Every change tells :voice (Cmd.LISTS), so the panel reads the lists again. Words shown come from
// strings_lists.xml and core ListsStrings; nothing here logs a list's name or an item.

/** What the screens do with your lists; the phone's is [PhoneListsPort], tests keep theirs. Every call off the main thread. */
interface ListsPort {
    /** The lists as they can be opened now; the first-use lists (Shopping and To-do) are made the first time, with Lists on. */
    fun load(): ListsLoad<ListsFile>
    /** One change by you on this screen ([how]); locked: nothing (ListsLoad.Locked). */
    fun update(how: ItemHow, change: (ListsFile, ListsWho) -> ListsEdit): ListsWrite
    /** Settings › Lists' Lists switch. */
    fun on(): Boolean
    /** The lists changed: :voice drops what it holds. */
    fun changed()
    /** 1.5: the Robots a list can be shared with and where its shares stand (blocking: :link's answer). */
    fun sharing(): ListSharing = ListSharing()
    /** 1.5: offer [l], called [name], to [pairing]; false when it couldn't go. */
    fun share(l: ListsList, name: String, pairing: String): Boolean = false
    /** 1.5: your copy stays; nothing more goes either way. */
    fun stopSharing(list: ListsId, pairing: String): Boolean = false
}

/** A paired Robot, by the name you know it by. */
class ListRobot(val id: String, val name: String)

/** [robots] can be offered a list; [named] is everyone paired, for a share made before a level changed. */
class ListSharing(val robots: List<ListRobot> = emptyList(), val shares: List<ListShareRec> = emptyList(), val named: Map<String, String> = emptyMap()) {
    fun name(pairing: String): String? = named[pairing]
}

/** The phone's: this process's vault. */
class PhoneListsPort(ctx: Context) : ListsPort {
    private val app = ctx.applicationContext
    private val vault = ListsFiles.vault(app)

    override fun load() = vault.load(firstUse = true)
    override fun update(how: ItemHow, change: (ListsFile, ListsWho) -> ListsEdit) = vault.update(how, change = change)
    override fun on() = ListsSettingsStore.current(app).on
    override fun changed() = Cmd.send(app, Cmd.LISTS)

    override fun sharing(): ListSharing {
        if (!LinkSettingsStore.current(app).on) return ListSharing()
        val client = LinkClient(app)
        try {
            val views = client.pairings().orEmpty().filter { it.phase == PairingPhase.ACTIVE }
            val people = try { PeopleFiles.store(app).list() } catch (_: Throwable) { emptyList() }
            val l = AppLanguage.langs(app)
            fun named(v: PairingView) = LinkNames.of(v, people).render(l)
            val on = views.filter { it.levels[LinkCapability.LISTS].let { x -> x != null && x != LinkLevel.OFF } }
            return ListSharing(on.map { ListRobot(it.id, named(it)) }, client.listShares().orEmpty().map { it.rec }, views.associate { it.id to named(it) })
        } finally {
            client.close()
        }
    }

    override fun share(l: ListsList, name: String, pairing: String): Boolean {
        val done = ListShareSync.offer(app, l, name, pairing, AppLanguage.langs(app).ui.id)
        changed()
        return done
    }

    override fun stopSharing(list: ListsId, pairing: String): Boolean {
        val done = ListShareSync.stop(app, list, pairing)
        changed()
        return done
    }
}

private val random = SecureRandom()

/** The screens' menus: secure, as their dialogs (a popup is a window of its own). */
private val SecureMenu = androidx.compose.ui.window.PopupProperties(focusable = true, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)

/** A fresh random id for a list or an item. */
internal val listIds: () -> ListsId = { ListsId.new(random) }

/** How [l] is called: its name, or its role's in [langs] (Shopping, Épicerie). */
fun listLabel(l: ListsList, langs: Langs): String = l.name ?: when (l.role) {
    ListRole.SHOPPING -> ListsStrings.shopping()
    ListRole.TODO -> ListsStrings.todo()
    null -> ListsStrings.page()
}.render(langs)

/** A change refused, in words, about the list [list] (its name as shown) and what was asked ([what]). */
internal fun refusalText(ctx: Context, r: ListsRefusal, list: String, what: String = list): String {
    val l = AppLanguage.langs(ctx)
    return when (r) {
        ListsRefusal.SECRET -> ListsStrings.secret().render(l)
        ListsRefusal.TOO_MANY -> ListsStrings.tooMany("max" to ListsLimits.MAX_ADD).render(l)
        ListsRefusal.TOO_LONG, ListsRefusal.EMPTY -> ListsStrings.tooLong("max" to ListsLimits.MAX_TEXT, "name" to ListsLimits.MAX_NAME).render(l)
        ListsRefusal.LIST_FULL -> ListsStrings.listFull("list" to list, "max" to ListsLimits.MAX_ITEMS).render(l)
        ListsRefusal.LISTS_FULL -> ListsStrings.listsFull("max" to ListsLimits.MAX_LISTS).render(l)
        ListsRefusal.FILE_FULL -> ListsStrings.fileFull().render(l)
        ListsRefusal.NAME_TAKEN, ListsRefusal.ROLE_TAKEN -> ListsStrings.exists("list" to what).render(l)
        ListsRefusal.PEER_NAME -> ListsStrings.peerList("name" to what).render(l)
        ListsRefusal.ALREADY -> ctx.getString(R.string.lists_already_item, what)
        ListsRefusal.NO_LIST, ListsRefusal.NO_ITEM -> ctx.getString(R.string.lists_changed)
    }
}

private fun problemOf(p: ItemProblem): ListsRefusal = when (p) {
    ItemProblem.EMPTY -> ListsRefusal.EMPTY
    ItemProblem.TOO_LONG -> ListsRefusal.TOO_LONG
    ItemProblem.TOO_MANY -> ListsRefusal.TOO_MANY
    ItemProblem.SECRET -> ListsRefusal.SECRET
}

/** The lists as a screen holds them, read again whenever the screen comes back into view, and its changes. */
@Stable
internal class ListsHolder(private val port: ListsPort, private val scope: CoroutineScope) {
    var load by mutableStateOf<ListsLoad<ListsFile>?>(null)
    var busy by mutableStateOf(false)
    private val order = Mutex()

    fun reload() {
        scope.launch { load = withContext(Dispatchers.IO) { port.load() } }
    }

    /** One change; [done] with its answer when it was made, [refused] when it wasn't. */
    fun edit(how: ItemHow, change: (ListsFile, ListsWho) -> ListsEdit, refused: (ListsRefusal) -> Unit = {}, done: (ListsEdit.Done) -> Unit = {}) {
        // In the order tapped, one at a time: a quick second tick waits for the first, never dropped.
        scope.launch {
            busy = true
            val w = order.withLock { withContext(Dispatchers.IO) { port.update(how, change).also { if (it.done?.changed == true) port.changed() } } }
            busy = false
            load = w.load
            when (val e = w.edit) {
                is ListsEdit.Done -> done(e)
                is ListsEdit.No -> refused(e.why)
                null -> {}
            }
        }
    }
}

@Composable
internal fun rememberLists(port: ListsPort): ListsHolder {
    val scope = rememberCoroutineScope()
    val h = remember(port) { ListsHolder(port, scope) }
    // Back in view, read again (the panel may have changed the lists meanwhile). LS13 (LS11c on the Pixel 8): and each time
    // the window has the focus back while in view, once it had it: the panel is a window over Robot, which stays started
    // under it, so a count stayed stale after an add made there.
    val owner = LocalLifecycleOwner.current
    val window = LocalWindowInfo.current
    LaunchedEffect(owner, port, window) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            h.reload()
            var had = false
            var lost = false
            snapshotFlow { window.isWindowFocused }.collect { focused ->
                if (!focused) lost = had
                else {
                    if (lost) h.reload()
                    had = true
                    lost = false
                }
            }
        }
    }
    return h
}

/** The lists couldn't be opened: why, and what can be done. */
@Composable
private fun ListsProblem(l: ListsLoad<*>?, a: AppActions, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(when (l) {
            null -> R.string.lists_opening
            ListsLoad.Locked -> R.string.lists_unlock_to_see
            ListsLoad.KeyGone -> R.string.lists_key_gone
            ListsLoad.Unreadable -> R.string.lists_unreadable
            is ListsLoad.TooNew -> R.string.lists_too_new
            else -> R.string.lists_not_now
        }), style = MaterialTheme.typography.bodyLarge)
        when (l) {
            null -> {}
            ListsLoad.KeyGone, ListsLoad.Unreadable, is ListsLoad.TooNew ->
                OutlinedButton(onClick = { a.openPage(Page.Settings(SettingsRoutes.LISTS)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.lists_settings_button))
                }
            else -> OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lists_try_again)) }
        }
    }
}

// ------------------------------------------------------------------ the Notes tab (Q1)

/** Which section of the Notes tab shows. */
enum class NotesSection { LISTS, NOTES }

/**
 * The Notes tab (Q1, §7.1): with Lists on, two sections at its top, Lists and Notes (Material's segmented buttons, Lists
 * first); with Lists off, Notes alone, as before. [notes] draws the Notes section.
 */
@Composable
fun NotesTab(section: NotesSection, onSection: (NotesSection) -> Unit, a: AppActions, snackbar: SnackbarHostState, modifier: Modifier,
    port: () -> ListsPort, notes: @Composable (Modifier) -> Unit) {
    val p = remember { port() }
    var on by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { on = withContext(Dispatchers.IO) { runCatching { p.on() }.getOrDefault(false) } }
    if (on != true) {
        notes(modifier)
        return
    }
    Column(modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), space = 0.dp) {
            val labels = listOf(stringResource(R.string.lists_title), app.heyrobot.ui.Tab.NOTES.label().str())
            NotesSection.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = s == section, onClick = { onSection(s) },
                    shape = SegmentedButtonDefaults.itemShape(i, NotesSection.entries.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(labels[i]) },
                )
            }
        }
        val m = Modifier.weight(1f)
        if (section == NotesSection.LISTS) {
            ListsSection(p, a, onOpen = { a.openPage(Page.ListPage(it.hex)) }, modifier = m)
        } else notes(m)
    }
}

// ------------------------------------------------------------------ the lists screen (§7.2)

@Composable
fun ListsSection(port: ListsPort, a: AppActions, onOpen: (ListsId) -> Unit, modifier: Modifier = Modifier) {
    SecureWindow()
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    val h = rememberLists(port)
    var naming by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenTitle(stringResource(R.string.lists_yours), stringResource(R.string.lists_subtitle))
        val l = h.load
        if (l !is ListsLoad.Loaded && l != ListsLoad.Empty) {
            ListsProblem(l, a, onRetry = h::reload)
            return@Column
        }
        val lists = (l as? ListsLoad.Loaded)?.value?.lists.orEmpty()
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lists.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.lists_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp))
            }
            items(lists, key = { it.id.hex }) { x -> ListRow(x, listLabel(x, langs)) { onOpen(x.id) } }
            item(key = "new") {
                OutlinedButton(onClick = { naming = true }, enabled = !h.busy, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                    Icon(RIcons.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.lists_new))
                }
            }
        }
    }
    if (naming) NameDialog(stringResource(R.string.lists_new), "", onDismiss = { naming = false }) { name ->
        naming = false
        h.edit(ItemHow.TYPED, { f, who -> ListsNames.make(f, name, who, listIds) },
            refused = { a.say(refusalText(ctx, it, name)) }) { onOpen(it.list) }
    }
}

@Composable
private fun ListRow(l: ListsList, name: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val open = l.open
            Text(pluralStringResource(R.plurals.lists_left, open.size, open.size), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The first three, as a line: "Oat milk, eggs, bread".
            if (open.isNotEmpty()) Text(open.take(3).joinToString(", ") { it.text }.let(ItemText::shown), style = MaterialTheme.typography.bodyMedium, // l10n: not text (items joined)
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (l.from == ListFrom.BACKUP) Text(stringResource(R.string.lists_from_backup), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (l.from == ListFrom.PEER) Text(stringResource(R.string.lists_from_peer), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A name to type: a new list's, or a list's new one. */
@Composable
internal fun NameDialog(title: String, start: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss, properties = SecureDialog,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it.take(ListsLimits.MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.lists_name_field)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name.trim()) }))
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.lists_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lists_cancel)) } },
    )
}

// ------------------------------------------------------------------ a list's page (§7.3)

/** A list's page on the phone (MainActivity's Page.ListPage). */
@Composable
fun ListRoute(id: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val port = remember { PhoneListsPort(ctx) }
    ListPage(port, ListsId.of(id), a, snackbar, onBack)
}

/** What an item's menu or a dialog is about. */
private sealed class ItemAsk {
    data class Edit(val item: ListsId) : ItemAsk()
    data class Move(val item: ListsId) : ItemAsk()
    data class Details(val item: ListsId) : ItemAsk()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ListPage(port: ListsPort, id: ListsId?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    val scope = rememberCoroutineScope()
    val h = rememberLists(port)
    val file = (h.load as? ListsLoad.Loaded)?.value
    val list = id?.let { file?.list(it) }
    val name = list?.let { listLabel(it, langs) } ?: ""
    var field by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var stopping by rememberSaveable { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(ListSharing()) }
    val sharedWith = list?.let { ListsShared.with(it).firstOrNull() }
    LaunchedEffect(id, sharedWith, picking) { sharing = withContext(Dispatchers.IO) { try { port.sharing() } catch (_: Exception) { ListSharing() } } }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var ask by remember { mutableStateOf<ItemAsk?>(null) }

    fun refused(r: ListsRefusal, what: String = name) = a.say(refusalText(ctx, r, name, what))

    /** "Deleted eggs." with Undo for 10 seconds (Files' Q2): the items come back from their tombstones. */
    fun undoable(message: String, list: ListsId, gone: List<ListsId>) {
        scope.launch {
            val r = snackbar.showSnackbar(message, actionLabel = ctx.getString(R.string.lists_undo), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) h.edit(ItemHow.TYPED, { f, who -> ListsEdits.restore(f, list, gone, who) }, refused = { refused(it) })
        }
    }

    fun add() {
        val l = list ?: return
        if (field.isBlank()) return
        val items = when (val r = ItemText.read(field)) {
            is ItemsRead.Ok -> r.items
            is ItemsRead.No -> {
                // A secret isn't left on screen.
                if (r.why == ItemProblem.SECRET) field = ""
                return refused(problemOf(r.why))
            }
        }
        h.edit(ItemHow.TYPED, { f, who -> ListsEdits.add(f, l.id, items, who, listIds) }, refused = { refused(it) }) { d ->
            field = ""
            val already = d.items.filter { it.outcome == ItemOutcome.ALREADY }.mapNotNull { x -> d.file.list(l.id)?.item(x.item) }
            if (already.isNotEmpty() && already.size == d.items.size) a.say(ctx.getString(R.string.lists_already_item, already.joinToString(", ") { ItemText.shown(it.text) })) // l10n: not text (items joined)
        }
    }

    fun tick(x: ListItem, done: Boolean) {
        val l = list ?: return
        h.edit(ItemHow.TYPED, { f, who -> if (done) ListsEdits.tick(f, l.id, x.id, who) else ListsEdits.untick(f, l.id, x.id, who) }, refused = { refused(it) })
    }

    fun delete(x: ListItem) {
        val l = list ?: return
        h.edit(ItemHow.TYPED, { f, who -> ListsEdits.remove(f, l.id, listOf(x.id), who) }, refused = { refused(it) }) {
            undoable(ctx.getString(R.string.lists_item_deleted, ItemText.shown(x.text)), l.id, listOf(x.id))
        }
    }

    fun clearDone() {
        val l = list ?: return
        h.edit(ItemHow.TYPED, { f, who -> ListsEdits.clearDone(f, l.id, who) }, refused = { refused(it) }) { d ->
            val gone = d.items.filter { it.outcome == ItemOutcome.REMOVED }.map { it.item }
            if (gone.isNotEmpty()) undoable(ctx.resources.getQuantityString(R.plurals.lists_cleared, gone.size, gone.size), l.id, gone)
        }
    }

    fun share() {
        val l = list ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ListsShare.text(name, l)).putExtra(Intent.EXTRA_SUBJECT, name) // l10n: not text (a MIME type)
        try { ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.lists_share_title))) } catch (_: Exception) { a.say(ctx.getString(R.string.lists_share_failed)) }
    }

    Scaffold(
        topBar = {
            BackBar(name, onBack) {
                if (list != null) Box {
                    IconButton(onClick = { menu = true }) { Icon(RIcons.MoreVert, stringResource(R.string.lists_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = SecureMenu) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.lists_rename)) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lists_share)) }, onClick = { menu = false; share() })
                        if (sharedWith == null) DropdownMenuItem(text = { Text(stringResource(R.string.lists_share_robot)) }, onClick = { menu = false; picking = true })
                        else DropdownMenuItem(text = { Text(stringResource(R.string.lists_share_stop)) }, onClick = { menu = false; stopping = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lists_delete_list)) }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val l = h.load
        Box(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 20.dp)) {
            when {
                l !is ListsLoad.Loaded -> ListsProblem(l, a, onRetry = h::reload)
                list == null -> Text(stringResource(R.string.lists_gone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "add") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = field, onValueChange = { field = it }, singleLine = true,
                                placeholder = { Text(stringResource(R.string.lists_add_hint)) },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { add() }),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = ::add, enabled = field.isNotBlank()) { Icon(RIcons.Add, stringResource(R.string.lists_add)) }
                        }
                    }
                    if (list.from == ListFrom.PEER || sharedWith != null) item(key = "shared") {
                        val who = sharedWith?.let { sharing.name(it) ?: stringResource(R.string.lists_share_someone) }
                        val waiting = sharing.shares.any { it.list == list.id.hex && it.state == ListShareState.OFFERED }
                        Text(when {
                            who == null -> stringResource(R.string.lists_from_peer)
                            waiting -> stringResource(R.string.lists_share_waiting, who)
                            else -> stringResource(R.string.lists_shared_with, who)
                        }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    if (ListsShared.PEER_FULL in list.extra) item(key = "peerFull") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(stringResource(R.string.lists_peer_full, sharedWith?.let { sharing.name(it) } ?: stringResource(R.string.lists_share_someone)), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { h.edit(ItemHow.TYPED, { f, _ -> ListsEdit.Done(app.heyrobot.core.link.ListShare.fullSeen(f, list.id), list.id) }, refused = { refused(it) }) },
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lists_peer_full_dismiss)) }
                        }
                    }
                    if (list.from == ListFrom.BACKUP) item(key = "backup") {
                        Text(stringResource(R.string.lists_from_backup), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    val open = list.open
                    val done = list.done
                    if (open.isEmpty()) item(key = "none") {
                        Text(stringResource(if (done.isEmpty()) R.string.lists_nothing_yet else R.string.lists_all_ticked), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    items(open, key = { it.id.hex }) { x ->
                        ItemRow(x, first = x.id == open.first().id, hasOther = (file?.lists?.size ?: 0) > 1, onTick = { tick(x, it) }, onAsk = { ask = it }, onTop = {
                            h.edit(ItemHow.TYPED, { f, who -> ListsEdits.moveToTop(f, list.id, x.id, who) }, refused = { refused(it) })
                        }, onDelete = { delete(x) })
                    }
                    val takenRows = app.heyrobot.core.link.ListShare.takenByPeer(list)
                    if (takenRows.isNotEmpty()) {
                        item(key = "taken") { Text(stringResource(R.string.lists_taken_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() }) }
                        items(takenRows, key = { "taken" + it.id.hex }) { x -> // l10n: not text (a row's key)
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ItemText.shown(x.text))
                                    (x.extra[app.heyrobot.core.lists.ListsShared.WAS] as? String)?.let { Text(stringResource(R.string.lists_taken_was, ItemText.shown(it)), style = MaterialTheme.typography.bodySmall) }
                                }
                                TextButton(onClick = { h.edit(ItemHow.TYPED, { f, who -> app.heyrobot.core.link.ListShare.putBack(f, list.id, x.id, who) }, refused = { refused(it) }) }) { Text(stringResource(R.string.lists_put_back)) }
                            }
                        }
                    }
                    if (done.isNotEmpty()) {
                        item(key = "done") {
                            val label = stringResource(R.string.lists_done_count, done.size)
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).clickable { showDone = !showDone }.semantics { heading() },
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Icon(RIcons.Chevron, null, Modifier.rotate(if (showDone) 270f else 90f))
                            }
                        }
                        if (showDone) {
                            items(done, key = { it.id.hex }) { x ->
                                ItemRow(x, first = true, hasOther = (file?.lists?.size ?: 0) > 1, onTick = { tick(x, it) }, onAsk = { ask = it }, onTop = {}, onDelete = { delete(x) })
                            }
                            item(key = "clear") {
                                TextButton(onClick = ::clearDone, enabled = !h.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lists_clear_done)) }
                            }
                        }
                    }
                }
            }
        }
    }

    val l = list
    if (l != null) {
        if (renaming) NameDialog(stringResource(R.string.lists_rename_title, name), name, onDismiss = { renaming = false }) { new ->
            renaming = false
            h.edit(ItemHow.TYPED, { f, who -> ListsEdits.rename(f, l.id, new, who) }, refused = { refused(it, new) })
        }
        if (picking) {
            AlertDialog(
                onDismissRequest = { picking = false }, properties = SecureDialog,
                title = { Text(stringResource(R.string.lists_share_robot)) },
                text = {
                    Column {
                        Text(stringResource(if (sharing.robots.isEmpty()) R.string.lists_share_none else R.string.lists_share_how), style = MaterialTheme.typography.bodyMedium)
                        for (r in sharing.robots) TextButton(onClick = {
                            picking = false
                            scope.launch {
                                val sent = withContext(Dispatchers.IO) { port.share(l, name, r.id) }
                                a.say(ctx.getString(if (sent) R.string.lists_share_sent else R.string.lists_share_not_sent, r.name))
                                h.reload()
                            }
                        }) { Text(r.name) }
                    }
                },
                confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.lists_cancel)) } },
            )
        }
        if (stopping && sharedWith != null) {
            AlertDialog(
                onDismissRequest = { stopping = false }, properties = SecureDialog,
                title = { Text(stringResource(R.string.lists_share_stop_title)) },
                text = { Text(stringResource(R.string.lists_share_stop_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        stopping = false
                        scope.launch { withContext(Dispatchers.IO) { port.stopSharing(l.id, sharedWith) }; h.reload() }
                    }) { Text(stringResource(R.string.lists_share_stop)) }
                },
                dismissButton = { TextButton(onClick = { stopping = false }) { Text(stringResource(R.string.lists_cancel)) } },
            )
        }
        if (deleting) {
            val n = l.live.size
            AlertDialog(
                onDismissRequest = { deleting = false }, properties = SecureDialog,
                title = { Text(stringResource(R.string.lists_delete_list)) },
                text = { Text(if (n == 0) stringResource(R.string.lists_delete_list_empty, name) else pluralStringResource(R.plurals.lists_delete_list_text, n, name, n)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = false
                        h.edit(ItemHow.TYPED, { f, _ -> ListsEdits.deleteList(f, l.id) }, refused = { refused(it) }) {
                            a.say(ctx.getString(R.string.lists_list_deleted, name))
                            onBack()
                        }
                    }) { Text(stringResource(R.string.lists_delete)) }
                },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.lists_cancel)) } },
            )
        }
        when (val q = ask) {
            null -> {}
            is ItemAsk.Edit -> l.item(q.item)?.takeIf { it.live }?.let { x ->
                EditDialog(x, onDismiss = { ask = null }) { text, qty ->
                    ask = null
                    h.edit(ItemHow.TYPED, { f, who ->
                        // The words as shown ("Oat milk" for "oat milk") are the words unchanged.
                        val words = ItemText.clean(text)
                        val e = if (words != x.text && words != ItemText.shown(x.text)) ListsEdits.edit(f, l.id, x.id, text, who) else ListsEdit.Done(f, l.id, changed = false)
                        if (e !is ListsEdit.Done) e
                        else when (val q2 = ListsEdits.quantity(e.file, l.id, x.id, qty, who)) {
                            is ListsEdit.No -> q2
                            is ListsEdit.Done -> ListsEdit.Done(q2.file, l.id, e.items + q2.items, e.changed || q2.changed)
                        }
                    }, refused = { refused(it, ItemText.shown(ItemText.clean(text))) })
                }
            } ?: run { ask = null }
            is ItemAsk.Move -> l.item(q.item)?.takeIf { it.live }?.let { x ->
                val others = file?.lists.orEmpty().filter { it.id != l.id }
                MoveDialog(ItemText.shown(x.text), others.map { it.id to listLabel(it, langs) }, onDismiss = { ask = null }) { to, toName ->
                    ask = null
                    h.edit(ItemHow.TYPED, { f, who -> ListsEdits.moveTo(f, l.id, x.id, to, who, listIds) }, refused = { refused(it, toName) }) {
                        a.say(ctx.getString(R.string.lists_moved, ItemText.shown(x.text), toName))
                    }
                }
            } ?: run { ask = null }
            is ItemAsk.Details -> l.item(q.item)?.let { x -> DetailsDialog(x, onDismiss = { ask = null }) } ?: run { ask = null }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(x: ListItem, first: Boolean, hasOther: Boolean, onTick: (Boolean) -> Unit, onAsk: (ItemAsk) -> Unit, onTop: () -> Unit, onDelete: () -> Unit) {
    val shown = ItemText.shown(x.text)
    var menu by remember { mutableStateOf(false) }
    val options = stringResource(R.string.lists_item_options, shown)
    val editLabel = stringResource(R.string.lists_edit)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = x.done, onCheckedChange = onTick, modifier = Modifier.semantics { contentDescription = shown })
        Column(Modifier.weight(1f).combinedClickable(onClickLabel = editLabel, onLongClickLabel = options, onLongClick = { menu = true }) {
            if (x.done) menu = true else onAsk(ItemAsk.Edit(x.id))
        }.padding(vertical = 8.dp)) {
            Text(shown, style = MaterialTheme.typography.bodyLarge, color = if (x.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            marks(x).forEach { Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        x.qty?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(RIcons.MoreVert, options) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = SecureMenu) {
                if (!x.done) DropdownMenuItem(text = { Text(editLabel) }, onClick = { menu = false; onAsk(ItemAsk.Edit(x.id)) })
                if (!x.done && !first) DropdownMenuItem(text = { Text(stringResource(R.string.lists_move_top)) }, onClick = { menu = false; onTop() })
                if (hasOther) DropdownMenuItem(text = { Text(stringResource(R.string.lists_move_to)) }, onClick = { menu = false; onAsk(ItemAsk.Move(x.id)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.lists_details)) }, onClick = { menu = false; onAsk(ItemAsk.Details(x.id)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.lists_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/**
 * Where an item came from, when it isn't simply you: added while locked, from content, or from someone else's backup. LS10
 * (M3): and "Changed while the phone was locked" when its last change was made locked after it was added (an add said while
 * locked that put it back, or a tick or a put back with "Hear and tick on the lock screen" on).
 */
private fun marks(x: ListItem): List<Int> = listOfNotNull(when (x.by) {
    ItemBy.LOCKED -> R.string.lists_mark_locked
    ItemBy.CONTENT -> R.string.lists_mark_content
    ItemBy.BACKUP -> R.string.lists_mark_backup
    else -> null
}, R.string.lists_mark_changed_locked.takeIf { x.hist.lastOrNull()?.let { it.by == ItemBy.LOCKED && it.op != ItemOp.ADD } == true })

@Composable
private fun EditDialog(x: ListItem, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(ItemText.shown(x.text)) }
    var qty by rememberSaveable { mutableStateOf(x.qty.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss, properties = SecureDialog,
        title = { Text(stringResource(R.string.lists_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(ListsLimits.MAX_TEXT) }, singleLine = true, label = { Text(stringResource(R.string.lists_item_field)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(qty, { qty = it.take(ListsLimits.MAX_QTY) }, singleLine = true, label = { Text(stringResource(R.string.lists_qty_field)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, qty.ifBlank { null }) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.lists_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lists_cancel)) } },
    )
}

@Composable
private fun MoveDialog(item: String, lists: List<Pair<ListsId, String>>, onDismiss: () -> Unit, onMove: (ListsId, String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, properties = SecureDialog,
        title = { Text(stringResource(R.string.lists_move_title, item)) },
        text = {
            Column {
                if (lists.isEmpty()) Text(stringResource(R.string.lists_no_other))
                for ((id, name) in lists) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onMove(id, name) }
                        .padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lists_cancel)) } },
    )
}

/** An item's details (§7.3): where it came from, and its last changes, each with who, how and when. */
@Composable
private fun DetailsDialog(x: ListItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss, properties = SecureDialog,
        title = { Text(x.qty?.let { q -> ListsStrings.itemQty("thing" to ItemText.shown(x.text), "qty" to q).str() } ?: ItemText.shown(x.text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in x.hist) Text(changeLine(ctx, c), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lists_close)) } },
    )
}

/** "Added by voice, while the phone was locked · 22 Oct, 18:40". */
internal fun changeLine(ctx: Context, c: ListChange): String {
    val op = ctx.getString(when (c.op) {
        ItemOp.ADD -> R.string.lists_op_add
        ItemOp.TICK -> R.string.lists_op_tick
        ItemOp.UNTICK -> R.string.lists_op_untick
        ItemOp.MOVE -> R.string.lists_op_move
        ItemOp.REMOVE -> R.string.lists_op_remove
        ItemOp.RESTORE -> R.string.lists_op_restore
        ItemOp.EDIT, ItemOp.UNKNOWN -> R.string.lists_op_edit
    })
    val how = when (c.how) {
        ItemHow.VOICE -> R.string.lists_how_voice
        ItemHow.TYPED -> R.string.lists_how_typed
        ItemHow.CARD -> R.string.lists_how_card
        ItemHow.WIDGET -> R.string.lists_how_widget
        ItemHow.MODEL -> R.string.lists_how_model
        ItemHow.NOTE -> R.string.lists_how_note
        ItemHow.RESTORE -> R.string.lists_how_restore
        ItemHow.LINK -> R.string.lists_how_link
        ItemHow.UNKNOWN -> null
    }
    val by = when (c.by) {
        ItemBy.LOCKED -> R.string.lists_by_locked
        ItemBy.CONTENT -> R.string.lists_by_content
        ItemBy.BACKUP -> R.string.lists_by_backup
        ItemBy.PEER -> R.string.lists_by_peer
        ItemBy.YOU, ItemBy.UNKNOWN -> null
    }
    var what = how?.let { ctx.getString(R.string.lists_hist_how, op, ctx.getString(it)) } ?: op
    if (by != null) what = ctx.getString(R.string.lists_hist_by, what, ctx.getString(by))
    val time = DateUtils.formatDateTime(ctx, c.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    return ctx.getString(R.string.lists_hist_when, what, time)
}
