package app.heyrobot.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.memory.HelperMemory
import app.heyrobot.core.memory.MemForget
import app.heyrobot.core.memory.MemKindCode
import app.heyrobot.core.memory.MemOrigin
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemoryDates
import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryFile
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// MAIN PROCESS. Settings › Memory (0.7, design §A3.2 and §A4): what Robot remembers, each item you can see, search,
// edit, pin (six at most), make private or delete; what waits in Suggestions (a quiet list, never spoken); the switches,
// whose defaults are the design's (memory on, used in answers, nothing on the lock screen, private items never spoken,
// helpers only through their card); Export to a file you pick (plain JSON, after a warning) and Delete all (after a
// confirm). The items are decrypted only while this page is open, off the main thread, through the vault both app
// processes share (sys/MemoryVault.kt); its switches live in memory.json, not settings.json. Every change tells the
// voice process (Cmd.MEMORY_CHANGED), so the panel never uses a deleted item again.

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
}

/** The phone's: the vault in files/memory, memory.json, the voice log's main-process file. */
class PhoneMemoryStore(ctx: Context) : MemoryStore {
    private val app = ctx.applicationContext
    private val vault = MemoryFiles.vault(app)

    override fun settings() = MemorySettingsStore.current(app)
    override fun saveSettings(s: MemorySettings) = MemorySettingsStore.save(app, s)
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
    LaunchedEffect(tick) { load = withContext(Dispatchers.IO) { store.load() } }

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
            if (r !is MemoryLoad.Loaded) a.say(problem(r) ?: "Robot couldn't change its memory.") else done?.let(a.say)
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                val f = (store.load() as? MemoryLoad.Loaded)?.file ?: return@withContext -1
                if (store.export(uri, JSONObject(MemoryCodec.toJson(f)).toString(2))) f.items.size else -1
            }
            a.say(if (n < 0) "The file couldn't be written." else "Exported ${if (n == 1) "1 thing" else "$n things"}.")
        }
    }

    val file = (load as? MemoryLoad.Loaded)?.file ?: if (load == MemoryLoad.Empty) MemoryFile() else null
    val items = file?.items.orEmpty()
    val pinnedCount = items.count { it.pinned }
    val shown = if (query.isBlank()) items else items.filter { MemoryText.fold(it.text).contains(MemoryText.fold(query.trim())) }

    // 1.0 (secrets-memory-private-items-not-secure): private items are shown here in full: out of screenshots, recordings,
    // casting and Recents, and no keyboard learns from the fields, the dialogs' included (ui/common Secure).
    SecureWindow()
    NoImeLearning {
    PreferenceScreen("Memory", onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar("Use memory", s.on) { saveSettings(s.copy(on = it)) } }
        when {
            load == null -> item(key = "reading") { IntroPreference("Opening…") }
            file == null -> preferenceGroup(null, "problem") {
                PreferenceBlock { Text(problem(load!!) ?: "", style = MaterialTheme.typography.bodyLarge) }
                if (load == MemoryLoad.KeyGone || load == MemoryLoad.Unreadable) {
                    PreferenceItem("Start over", "The file is kept aside as memory.enc.bad", RIcons.Refresh, onClick = { confirmStartOver = true })
                    PreferenceItem("Delete the file", "Everything Robot remembered", RIcons.Delete, onClick = { confirmAll = true })
                } else {
                    PreferenceItem("Try again", null, RIcons.Refresh, onClick = { tick++ })
                }
            }
            else -> {
                item(key = "intro") {
                    IntroPreference(when (items.size) {
                        0 -> "Robot remembers nothing yet. Say “remember that …” to keep something."
                        1 -> "Robot remembers 1 thing."
                        else -> "Robot remembers ${items.size} things."
                    } + " It stays on this phone, encrypted, and never goes to a helper unless you include it." +
                        if (items.size >= MemoryLimits.NOTE_AT) " Memory holds ${MemoryLimits.MAX_ITEMS} at most." else "")
                }
                if (items.isNotEmpty()) item(key = "search") {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search memory") }, leadingIcon = { Icon(RIcons.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(RIcons.Close, "Clear search") } },
                        shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
                val pinned = shown.filter { it.pinned }
                val rest = shown.filter { !it.pinned }.sortedByDescending { it.createdAt }
                if (items.isNotEmpty() && shown.isEmpty()) item(key = "nomatch") { IntroPreference("Nothing matches.") }
                if (pinned.isNotEmpty()) preferenceItems("Pinned ($pinnedCount of ${MemoryLimits.MAX_PINNED}): in every answer", "pinned", pinned, { it.id }) { i ->
                    ItemRow(i, now()) { editing = i }
                }
                if (rest.isNotEmpty()) preferenceItems(if (pinned.isEmpty() && query.isBlank()) "Everything" else "Everything else", "items", rest, { it.id }) { i ->
                    ItemRow(i, now()) { editing = i }
                }
                val suggestions = file.suggestions
                if (suggestions.isEmpty()) {
                    preferenceGroup("Suggestions", "suggestions") {
                        PreferenceBlock {
                            Text("Nothing waiting. Robot never says a suggestion aloud and never keeps one without your tap.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    preferenceItems("Suggestions (${suggestions.size})", "suggestions", suggestions, { it.id }) { sg ->
                        SuggestionRow(sg,
                            keep = { change("Kept.") { f -> MemoryEdits.keep(f, sg.id, now()).file }; log(VoiceEvent.MEM_SUGGEST, 0, 1, 0) },
                            dismiss = { change(null) { f -> MemoryEdits.dismiss(f, sg.id, dontSuggest = false) }; log(VoiceEvent.MEM_SUGGEST, 0, 0, 1) })
                    }
                }
                preferenceGroup("Your memory", "actions") {
                    PreferenceItem("Add something", "As if you'd said “remember that …”", RIcons.Add, enabled = items.size < MemoryLimits.MAX_ITEMS,
                        onClick = { adding = true })
                    PreferenceItem("Export", "To a file you pick: not encrypted", RIcons.Download, enabled = items.isNotEmpty(), onClick = { warnExport = true })
                    PreferenceItem("Delete all", "Everything Robot remembers", RIcons.Delete, enabled = items.isNotEmpty() || file.suggestions.isNotEmpty(),
                        onClick = { confirmAll = true })
                }
            }
        }
        preferenceGroup("Answers", "answers") {
            SwitchPreference("Use memory in answers", "Pinned things in every answer, the rest when a question needs it", s.useInAnswers, enabled = s.on) {
                saveSettings(s.copy(useInAnswers = it))
            }
            ListPreference("Suggest things to remember", s.suggestions, SuggestionStyle.entries, { suggestionLabel(it) }, enabled = s.on,
                detail = { suggestionDetail(it) }) { saveSettings(s.copy(suggestions = it)) }
            SwitchPreference("Say private items aloud", "Only one you ask for by name, on an unlocked phone", s.speakSecrets, enabled = s.on) {
                saveSettings(s.copy(speakSecrets = it))
            }
        }
        preferenceGroup("Privacy", "privacy") {
            SwitchPreference("Use memory on the lock screen", "Anyone who can talk to your locked phone can hear what Robot remembers", s.onLockScreen,
                enabled = s.on) { on ->
                // Memory is sealed again with the key that matches first; the switch moves only once that worked.
                scope.launch {
                    val r = withContext(Dispatchers.IO) { store.rekey(on) }
                    val ok = r is MemoryLoad.Loaded || r == MemoryLoad.Empty
                    log(VoiceEvent.MEM_KEY, if (on) 1 else 0, if (ok) 1 else 0)
                    if (ok) saveSettings(s.copy(onLockScreen = on)) else a.say(problem(r) ?: "Robot couldn't change memory's key.")
                }
            }
            ListPreference("Share memory with helpers", s.helpers, HelperMemory.entries, { it.title }, enabled = s.on,
                detail = { if (it == HelperMemory.ONLY_WHEN_INCLUDED) "A card asks every time" else "Helpers you trust get what an answer used; private items never" }) {
                saveSettings(s.copy(helpers = it))
            }
        }
        preferenceFooter(
            "Robot keeps what you ask it to (“remember that …”, “souviens-toi que …”) in one file on this phone, encrypted with a key " +
                (if (s.onLockScreen) "that also works while the phone is locked (the lock screen's switch above)" else "that works only while the phone is unlocked") +
                ", and never backed up. “Forget that” removes the last thing; Undo puts it back. " +
                "Passwords, PINs and card numbers you ask it to keep stay private: never spoken unless you ask for one by name, never in " +
                "an answer unless the question names it, never sent to a helper. Robot doesn't keep them from a conversation by itself. " +
                "History keeps which things an answer used, never their words.",
        )
    }

    editing?.let { item ->
        ItemDialog(item, pinFull = pinnedCount >= MemoryLimits.MAX_PINNED && !item.pinned, now = now(), onDismiss = { editing = null },
            onDelete = {
                editing = null
                change("Deleted.") { f -> MemoryEdits.forget(f, setOf(item.id)).first }
                log(VoiceEvent.MEM_FORGET, 1, MemForget.SETTINGS.id)
            },
            onSave = { text, pinned, secret ->
                editing = null
                change("Saved.") { f ->
                    var g = if (text != item.text) MemoryEdits.edit(f, item.id, text, now()) ?: f else f
                    g = g.copy(items = g.items.map { if (it.id == item.id && secret != it.secret) it.copy(secret = secret, pinned = it.pinned && !secret) else it })
                    MemoryEdits.pin(g, item.id, pinned && !secret) ?: g
                }
            })
    }
    if (adding) {
        EditDialog("Add something", "", "What to remember", "Your words, as you'd say them", canSave = { it.isNotBlank() },
            maxLength = MemoryLimits.MAX_CHARS, secure = true, onDismiss = { adding = false }) { text ->
            adding = false
            val t = now()
            val kind = if (app.heyrobot.core.memory.MemoryCommands.prefers(text)) MemoryKind.PREFERENCE else MemoryKind.FACT
            val item = MemoryItem(MemoryIds.newId(t), text, kind, source = MemorySource.SETTINGS, createdAt = t)
            change("Remembered.") { f -> MemoryEdits.add(f, item).file }
            log(VoiceEvent.MEM_SAVE, MemOrigin.SETTINGS.id, MemKindCode.of(item).id)
        }
    }
    if (warnExport) {
        ConfirmDialog("Export memory?", "The file isn't encrypted: anyone who opens it can read everything Robot remembers, private items " +
            "and what you said about other people included. Keep it somewhere safe.", "Choose a file", onDismiss = { warnExport = false }) {
            warnExport = false
            try { exporter.launch("robot-memory.json") } catch (_: Exception) { a.say("No app here can save a file.") }
        }
    }
    if (confirmAll) {
        ConfirmDialog("Delete all memory?", "Robot forgets everything it remembers about you, and its suggestions. This can't be undone.",
            "Delete all", onDismiss = { confirmAll = false }) {
            confirmAll = false
            scope.launch {
                val n = items.size
                val ok = withContext(Dispatchers.IO) { store.deleteAll().also { if (it) store.changed() } }
                if (ok) log(VoiceEvent.MEM_FORGET, n, MemForget.ALL.id)
                a.say(if (ok) "Memory deleted." else "Memory couldn't be deleted.")
                tick++
            }
        }
    }
    if (confirmStartOver) {
        ConfirmDialog("Start over?", "Robot keeps the file it can't read aside, as memory.enc.bad, and starts an empty memory.", "Start over",
            onDismiss = { confirmStartOver = false }) {
            confirmStartOver = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { store.startOver().also { if (it) store.changed() } }
                a.say(if (ok) "Memory is empty now." else "Robot couldn't set the file aside.")
                tick++
            }
        }
    }
    }
}

/** What a memory that couldn't be opened means, in words; null when it opened. */
internal fun problem(l: MemoryLoad): String? = when (l) {
    MemoryLoad.Locked -> "Robot couldn't open its memory just now. Try again in a moment."
    MemoryLoad.KeyGone -> "Robot's memory key is gone (the screen lock was removed, or the phone reset it), so its memory can't be read. " +
        "Start over keeps the file aside; Delete removes it."
    MemoryLoad.Unreadable -> "Robot can't read its memory: the file is damaged. Start over keeps it aside and starts an empty memory."
    is MemoryLoad.TooNew -> "A newer version of Robot saved your memory, so this one can't read or change it."
    is MemoryLoad.Loaded, MemoryLoad.Empty -> null
}

internal fun suggestionLabel(s: SuggestionStyle): String = when (s) {
    SuggestionStyle.QUIET_LIST -> "A quiet list here"
    SuggestionStyle.END_CHIP -> "Also at the end of a conversation"
    SuggestionStyle.OFF -> "Off"
}

private fun suggestionDetail(s: SuggestionStyle): String = when (s) {
    SuggestionStyle.QUIET_LIST -> "Never spoken, no pop-up"
    SuggestionStyle.END_CHIP -> "A “Remember: …?” chip in the panel"
    SuggestionStyle.OFF -> "Robot suggests nothing"
}

/** The Memory row's state in Settings: "On", "On · on the lock screen", "Off". */
fun memorySummary(s: MemorySettings): String = when {
    !s.on -> "Off"
    s.onLockScreen -> "On · also on the lock screen"
    !s.useInAnswers -> "On · not used in answers"
    else -> "On"
}

/** One item: its words (a private one too: this page is behind your unlock), its state under them. */
@Composable
private fun ItemRow(i: MemoryItem, now: Long, onClick: () -> Unit) {
    PreferenceItem(i.text, MemoryDates.line(i, now), if (i.secret) RIcons.Lock else null, onClick = onClick)
}

/** A suggestion with Keep and Not this, each named after it for TalkBack. */
@Composable
private fun SuggestionRow(s: MemorySuggestion, keep: () -> Unit, dismiss: () -> Unit) {
    TwoTargetPreference(s.text, "Suggested from your own words", onClick = keep, onClickLabel = "Keep") {
        TextButton(onClick = keep, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Keep: ${s.text}" }) { Text("Keep") }
        TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Not this: ${s.text}" }) { Text("Not this") }
    }
}

/** An item: its words to edit, Pinned and Private, how often answers used it; Delete, Cancel, Save. */
@Composable
private fun ItemDialog(item: MemoryItem, pinFull: Boolean, now: Long, onDismiss: () -> Unit, onDelete: () -> Unit,
    onSave: (text: String, pinned: Boolean, secret: Boolean) -> Unit) {
    var text by rememberSaveable(item.id) { mutableStateOf(item.text) }
    var pinned by rememberSaveable(item.id) { mutableStateOf(item.pinned) }
    var secret by rememberSaveable(item.id) { mutableStateOf(item.secret) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(if (item.kind == MemoryKind.PREFERENCE) "Preference" else "Remembered") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(MemoryLimits.MAX_CHARS) }, label = { Text("What Robot remembers") },
                    modifier = Modifier.fillMaxWidth())
                DialogSwitch("Pinned", if (pinFull) "Six are pinned: unpin one first" else "Used in every answer", pinned && !secret,
                    enabled = !secret && !pinFull) { pinned = it }
                DialogSwitch("Private", "Never spoken or sent; only here", secret) { secret = it; if (it) pinned = false }
                Text(MemoryDates.line(item, now) + (item.sourceLabel?.let { " · from $it" } ?: if (item.source == MemorySource.CONTENT) " · not your own words" else ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim(), pinned, secret) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
