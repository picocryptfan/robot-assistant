package app.heyrobot.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.core.Addresses
import app.heyrobot.core.CheogramLinks
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.People
import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.PeopleEdit
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.Person
import app.heyrobot.core.PersonSource
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.PickOutcome
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VCard
import app.heyrobot.core.VCardFile
import app.heyrobot.core.XmppAddress
import app.heyrobot.sys.CheogramIntents
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.NotificationAccess
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.PhoneContacts
import app.heyrobot.ui.common.RIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceCategory
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TwoTargetPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.pref.preferenceItems
import app.heyrobot.ui.settings.SettingsRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

// MAIN PROCESS. Settings › People (docs/people.md): Robot's own list of people, for texts and emails to someone who
// isn't in the phone's contacts, or whom you call something else ("mom"). Ways in: by hand, a contacts file (.vcf, as
// Proton Mail's web app exports it), Cheogram's contact list and the phone's contact picker, none of which needs a
// permission. Ways out: a .vcf export and, per person, the Contacts app's own "new contact" screen. Everything stays on
// the phone and is never sent to Claude; the assistant changes the list too (a Cheogram pick, "Always use this"), so the
// screen reads it again whenever it comes back to the front.

/** A contacts file Robot reads at most (a few thousand cards). */
private const val MAX_VCF_BYTES = 8 shl 20

private fun readAtMost(input: InputStream, max: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 shl 10)
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        out.write(buf, 0, n)
        if (out.size() > max) return null
    }
}

@Composable
fun PeopleScreen(s: RobotSettings, update: (RobotSettings) -> Unit, facts: AssistantFacts?, a: AppActions, snackbar: SnackbarHostState,
    onOpen: (String?) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PeopleFiles.store(ctx) }
    var people by remember { mutableStateOf<List<Person>>(emptyList()) }
    /** The file is there but can't be read: nothing is written until "Start over". */
    var damaged by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    /** A contacts file read and waiting for "Import": what it holds, and what importing it into the list as it was would do. */
    var preview by remember { mutableStateOf<Preview?>(null) }
    var askLegacy by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    val resumed = lstate.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(resumed, tick) {
        if (resumed) withContext(Dispatchers.IO) { People.sorted(store.list()) to store.damaged() }.let { (l, d) -> people = l; damaged = d }
    }
    // The panel opens over this window without pausing it: read again when the window gets the focus back.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) { snapshotFlow { windowInfo.isWindowFocused }.collect { if (it) tick++ } }

    fun now() = System.currentTimeMillis()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_VCF_BYTES) } } catch (t: Exception) { RLog.w("people_import", t); null }
            }
            if (bytes == null) return@launch a.say("That file couldn't be read, or it is over 8 MB.")
            val p = withContext(Dispatchers.Default) {
                val f = VCard.parse(VCard.decode(bytes))
                val incoming = f.entries.map { VCard.person(it, PeopleStore.newId(), now()) }
                val before = store.list()
                Preview(f, incoming, before, PeopleEdit.merge(before, incoming, now()))
            }
            if (p.file.entries.isEmpty()) a.say("No one in that file has a number, an email or an XMPP address.") else preview = p
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vcard")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                val list = People.sorted(store.list().filter { it.confirmed })
                try {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(VCard.write(list).toByteArray(Charsets.UTF_8)) } ?: return@withContext -1
                    list.size
                } catch (t: Exception) { RLog.w("people_export", t); -1 }
            }
            a.say(if (n < 0) "The file couldn't be written." else "Exported ${plural(n, "person", "people")}.")
        }
    }
    val cheogram = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = r.resultCode == android.app.Activity.RESULT_OK
        val (picked, readable) = if (ok) CheogramIntents.read(r.data) else null to true
        val pick = when (CheogramLinks.outcome(ok, readable, picked)) {
            PickOutcome.PICKED -> picked
            PickOutcome.UNREADABLE -> null.also { a.say("Cheogram's answer couldn't be read.") }
            PickOutcome.NO_ADDRESS, PickOutcome.NO_LIST -> null.also { a.say("Cheogram didn't say who was picked, or Robot can't use that address.") }
            PickOutcome.CANCELLED -> null.also { a.say("Nobody was picked in Cheogram.") }
        } ?: return@rememberLauncherForActivityResult
        scope.launch {
            var id: String? = null
            withContext(Dispatchers.IO) { store.update { l -> PeopleEdit.rememberPick(l, null, pick, now()).let { (list, p) -> id = p.id; list } } }
            tick++
            id?.let(onOpen) ?: a.say("Robot couldn't save them.")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        // Android 17's picker may hand back its session URI as ClipData only.
        val uri = r.data?.data ?: r.data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: return@rememberLauncherForActivityResult a.say("The contact picker didn't return a contact.")
        scope.launch {
            val p = withContext(Dispatchers.IO) { try { PhoneContacts.read(ctx, uri, now()) } catch (t: Exception) { RLog.w("people_pick", t); null } }
                ?: return@launch a.say("Robot couldn't read that contact.")
            var id: String? = null
            withContext(Dispatchers.IO) { store.update { l -> PeopleEdit.merge(l, listOf(p), now()).also { id = it.landed.firstOrNull() }.people } }
            tick++
            id?.let(onOpen) ?: a.say("That contact has no number or email to add.")
        }
    }

    fun launch(block: () -> Unit, missing: String) = try { block() } catch (_: ActivityNotFoundException) { a.say(missing) }

    // Searched off the main thread, a moment after the last key (thousands of people).
    var shown by remember { mutableStateOf<List<Person>>(emptyList()) }
    LaunchedEffect(people, query) {
        if (query.isBlank()) { shown = people; return@LaunchedEffect }
        delay(SEARCH_DELAY_MS)
        shown = withContext(Dispatchers.Default) { people.filter { People.matches(it, query) } }
    }
    var menu by remember { mutableStateOf(false) }
    val learned = people.filter { !it.confirmed }
    val granted = facts?.notificationAccess == true
    PreferenceScreen("People", onBack, snackbar = snackbar, actions = {
        // Import, export and Delete all: the overflow, as in Android's Contacts.
        Box {
            IconButton(onClick = { menu = true }) { Icon(RIcons.MoreVert, "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Import contacts file (.vcf)") }, leadingIcon = { Icon(RIcons.Folder, null) }, onClick = {
                    menu = false
                    launch({ importer.launch(arrayOf("text/vcard", "text/x-vcard", "text/directory", "*/*")) }, "No app on this phone opens files.")
                })
                DropdownMenuItem(text = { Text("Export (.vcf)") }, leadingIcon = { Icon(RIcons.Download, null) }, onClick = {
                    menu = false
                    launch({ exporter.launch("Robot People.vcf") }, "No app on this phone saves files.")
                })
                if (people.isNotEmpty()) {
                    DropdownMenuItem(text = { Text("Delete all People") }, leadingIcon = { Icon(RIcons.Delete, null) }, onClick = {
                        menu = false
                        confirmDeleteAll = true
                    })
                }
            }
        }
    }) {
        item(key = "intro") {
            IntroPreference("People Robot knows besides your contacts: who to text or email, and what you call them. Kept on this phone " +
                "only, never sent to Claude, and not in Android's backups.")
        }
        if (damaged) item(key = "damaged") {
            Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(RIcons.Error, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Robot can't read its People file", style = MaterialTheme.typography.titleSmall)
                        Text("It may be damaged. Robot changes nothing in it, and knows no one here, until you start over. Starting over keeps " +
                            "the old file aside (people.json.bad, in Robot's files) and begins an empty list; import your last export to get everyone back.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { confirmReset = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start over") }
                }
            }
        }
        preferenceGroup(null, "add") {
            PreferenceItem("Add person", "Type a name, numbers, emails and XMPP addresses", RIcons.Add, onClick = { onOpen(null) })
            PreferenceItem("Add from phone contacts", "One contact, no permission needed", RIcons.Person, onClick = {
                try { picker.launch(PhoneContacts.picker()) } catch (_: ActivityNotFoundException) { askLegacy = true }
            })
            val pkg = HandOffApps.cheogram(ctx)
            if (pkg != null && remember(pkg) { CheogramIntents.canPick(ctx, pkg) }) {
                PreferenceItem("Add from Cheogram", "Pick someone in Cheogram's list", RIcons.Message, onClick = {
                    launch({ cheogram.launch(CheogramIntents.pick(pkg)) }, "Cheogram's list didn't open.")
                })
            }
        }
        preferenceGroup("Remembering", "remember") {
            SwitchPreference("Remember who you pick", "In Cheogram, or on a card (“Always use this for mom”)", s.rememberPicks) {
                update(s.copy(rememberPicks = it))
            }
        }
        preferenceGroup("Learn from Cheogram", "learn") {
            SwitchPreference("Learn names from Cheogram notifications", "Who messages you, never the message", s.learnFromCheogram) { on ->
                update(s.copy(learnFromCheogram = on))
                // Turned on: the Cheogram installed now is the one trusted (its signer is kept at its first notification).
                if (on) { scope.launch(Dispatchers.IO) { PeopleFiles.forgetCheogramSigner(ctx) }; NotificationAccess.rebind(ctx) }
            }
            when {
                s.learnFromCheogram && !granted -> StatusPreference("Notification access", "Needed: three steps", Mark.TODO,
                    onClick = { a.openPage(Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)) })
                s.learnFromCheogram -> StatusPreference("Notification access allowed", "Cheogram's one-to-one senders only", Mark.OK)
                granted -> StatusPreference("Notification access is still allowed", "Robot ignores notifications while this is off", Mark.TODO,
                    action = { TextButton(onClick = { openFirst(ctx, NotificationAccess.settings(ctx), a) }) { Text("Open") } })
            }
            // 0.4.6: what Robot reads, where it is kept, and how to take it back, before and after turning it on.
            PreferenceBlock { CheogramPrivacy(canTakeBack = granted && s.learnFromCheogram) { openFirst(ctx, NotificationAccess.settings(ctx), a) } }
        }
        if (learned.isNotEmpty()) {
            preferenceGroup("Learned from Cheogram (${learned.size})", "learned") {
                for (p in learned.take(MAX_LEARNED_SHOWN)) {
                    TwoTargetPreference(p.name, People.summary(p).ifEmpty { null }, onClick = { onOpen(p.id) }, onClickLabel = "Open") {
                        TextButton(onClick = { scope.launch {
                            withContext(Dispatchers.IO) { store.update { PeopleEdit.confirm(it, p.id, now()) } }
                            tick++
                        } }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Keep ${p.name}" }) { Text("Keep") }
                        // Their address isn't learned again, so the next message doesn't bring them back.
                        TextButton(onClick = { scope.launch {
                            withContext(Dispatchers.IO) { store.remove(p.id, ignore = true) }
                            tick++
                        } }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Remove ${p.name}" }) { Text("Remove") }
                    }
                }
                if (learned.size > MAX_LEARNED_SHOWN) {
                    PreferenceBlock { Text("…and ${learned.size - MAX_LEARNED_SHOWN} more in the list below.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (people.isEmpty()) {
            item(key = "none") { PreferenceCategory("No one yet") }
        } else {
            item(key = "search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search people") }, leadingIcon = { Icon(RIcons.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(RIcons.Close, "Clear search") } },
                    shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
            if (shown.isEmpty()) item(key = "nomatch") { IntroPreference("No one matches.") }
            preferenceItems("Your people (${people.size})", "people", shown, { it.id }) { p -> PersonRow(p) { onOpen(p.id) } }
        }
        preferenceFooter(
            "Import contacts file (.vcf), in the menu: from Proton Mail on the web, Contacts › select all › Export selection as " +
                ".VCF. Robot keeps names, nicknames, numbers, emails and XMPP addresses only. Export (.vcf) makes a backup, and a " +
                "file any contacts app can import.\n\nAdd from Cheogram: Cheogram's list says “Create shortcut”; nothing goes on " +
                "your home screen.",
        )
    }
    preview?.let { pv ->
        val file = pv.file
        val merged = pv.merged
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Import ${plural(file.entries.size, "person", "people")}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("In the file: ${plural(file.phones, "number", "numbers")}, ${plural(file.emails, "email", "emails")}, " +
                        "${plural(file.xmpp, "XMPP address", "XMPP addresses")}." +
                        (if (file.skipped > 0) " ${plural(file.skipped, "card has", "cards have")} nothing to reach them by and stay out." else ""))
                    Text("${plural(merged.added, "new person", "new people")}; ${plural(merged.updated, "person", "people")} already here " +
                        "(the same number, email or XMPP address) get what's new.")
                    Text("Robot keeps names, nicknames, numbers, emails and XMPP addresses only: no postal addresses, birthdays, notes or photos.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    scope.launch {
                        // The merge the preview worked out is written as it is, unless the list changed meanwhile.
                        var done: PeopleEdit.Merged? = merged
                        val ok = withContext(Dispatchers.IO) {
                            store.commit(pv.before, merged.people) { l -> PeopleEdit.merge(l, pv.incoming, now()).also { done = it }.people }
                        } != null
                        tick++
                        val d = done
                        a.say(if (!ok || d == null) "The import couldn't be saved." else "Imported: ${d.added} new, ${d.updated} updated.")
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }
    if (askLegacy) {
        AlertDialog(
            onDismissRequest = { askLegacy = false },
            title = { Text("Add from phone contacts") },
            text = { Text("Pick a number or an email address; Robot adds it with the contact's name.") },
            confirmButton = {
                TextButton(onClick = { askLegacy = false; launch({ picker.launch(PhoneContacts.legacy(email = false)) }, "No contacts app on this phone.") }) { Text("A number") }
            },
            dismissButton = {
                TextButton(onClick = { askLegacy = false; launch({ picker.launch(PhoneContacts.legacy(email = true)) }, "No contacts app on this phone.") }) { Text("An email") }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Start over?") },
            text = { Text("Robot keeps the file it can't read aside, as people.json.bad, and starts an empty People list.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { store.reset() }
                        tick++
                        a.say(if (ok) "People is empty now." else "Robot couldn't set the file aside.")
                    }
                }) { Text("Start over") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all People?") },
            text = { Text("Robot forgets everyone here and what you call them, and may learn anyone you removed again. The phone's contacts " +
                "aren't touched. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { store.deleteAll() }
                        tick++
                        a.say(if (ok) "People deleted." else "People couldn't be deleted.")
                    }
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

/** A contacts file read for import: its cards as people, the list they were merged into, and the result. */
private class Preview(val file: VCardFile, val incoming: List<Person>, val before: List<Person>, val merged: PeopleEdit.Merged)

internal fun plural(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"

/** Learned people listed with Keep and Remove at most; the rest are in the list. */
private const val MAX_LEARNED_SHOWN = 20

/** How long the search waits after a key before it runs. */
private const val SEARCH_DELAY_MS = 200L

/** The person the editor loaded, across a rotation or the process being killed (as one entry of the People file). */
private val PersonSaver: Saver<Person?, String> = Saver(
    save = { p -> p?.let { PeopleCodec.toJson(listOf(it)) } },
    restore = { PeopleCodec.fromJson(it).firstOrNull() },
)

/** The first of [intents] that opens (Android's page for Robot, else the general one). */
internal fun openFirst(ctx: android.content.Context, intents: List<android.content.Intent>, a: AppActions) {
    for (i in intents) {
        try { ctx.startActivity(i); return } catch (_: Exception) {}
    }
    a.say("Open Settings › Notifications › Device & app notifications, or Settings › Apps › Special app access › Notification access.")
}

/**
 * Settings › People › Notification access (0.4.x redesign: a page of its own, from the row under "Learn names from
 * Cheogram notifications"): the three steps, each with the button that opens its page.
 */
@Composable
fun NotificationAccessScreen(facts: AssistantFacts?, onBack: () -> Unit) {
    PreferenceScreen("Notification access", onBack) {
        if (facts?.notificationAccess == true) {
            preferenceGroup(null, "done") { StatusPreference("Notification access allowed", "What People and Messages turn on, nothing else", Mark.OK) }
        }
        preferenceGroup(null, "steps") { PreferenceBlock { NotificationAccessSteps() } }
        // 0.7: Settings › Messages opens this page too.
        preferenceFooter("To learn names, Robot looks at Cheogram's notifications only, and of those only the sender of a one-to-one chat. " +
            "With Settings › Messages on, it also reads the messages of the apps you turn on there, in memory only. Settings › People and " +
            "Settings › Messages say what Robot reads, where it is kept, and how to take it back.")
    }
}

/**
 * Notification access, step by step. Android blocks it for apps installed from a file (Robot, from its release page)
 * until "Allow restricted settings" is on in the app's App info, and that menu item only appears once Android has
 * refused the access, hence the order. 0.4.6: says why Android calls it a restricted setting, and each step has the
 * button that opens its page.
 */
@Composable
internal fun NotificationAccessSteps() {
    val ctx = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun access() { for (i in NotificationAccess.settings(ctx)) { try { ctx.startActivity(i); return } catch (_: Exception) {} } }
    fun appInfo() { try { ctx.startActivity(NotificationAccess.appInfo(ctx)) } catch (_: Exception) {} }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Allow notification access", style = MaterialTheme.typography.titleSmall)
        Text("Android calls notification access a restricted setting for apps installed from a file instead of an app store, as Robot " +
            "is: harmful apps installed that way have used it to read people's messages and login codes. So it takes three steps.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        Text("1. Tap Notification access and turn on Robot's access (in a list, tap Robot first). If Android says “Restricted " +
            "setting”, tap OK.", style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = ::access, modifier = Modifier.heightIn(min = 48.dp)) { Text("Notification access") }
        Text("2. Tap App info, then ⋮ (top right) › Allow restricted settings, and confirm; Android may ask for your PIN or " +
            "fingerprint. No such item? Android isn't blocking Robot: skip this step.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = ::appInfo, modifier = Modifier.heightIn(min = 48.dp)) { Text("App info") }
        Text("3. Tap Notification access again, turn on Robot's access and tap Allow.", style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = ::access, modifier = Modifier.heightIn(min = 48.dp)) { Text("Notification access again") }
    }
}

/**
 * What "Learn names from Cheogram notifications" reads, where it is kept, and how to take it back (0.4.6), each claim as
 * the code does it: CheogramListener (Cheogram's packages only, signed as first seen, a 1:1 chat's sender name and key
 * and the shortcut's account; the manifest asks for conversations only), PeopleStore (files/people, left out of backups),
 * ClaudeContext and HelperTrust (0.6.0: People and lookups never go to Claude or another helper unless a conversation that
 * has one is shared: Include, a helper trusted with personal content, or "Send personal content to helpers
 * automatically"; no helper is given lookup_contact, and what a text it asks for answers it doesn't say who got it).
 */
@Composable
internal fun CheogramPrivacy(canTakeBack: Boolean, openAccess: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What Robot reads, and where it goes", style = MaterialTheme.typography.titleSmall)
        Text("Notification access lets an app see every app's notifications. Robot asks Android for conversations only, drops all " +
            "but Cheogram's at once, and learns nothing from them while this switch is off. Settings › Messages, if you turn it on, " +
            "uses the same access to read the apps you choose there, in memory only.", style = MaterialTheme.typography.bodyMedium, color = muted)
        Text("From a Cheogram message in a one-to-one chat, Robot keeps only the sender's name and address and which of your accounts " +
            "got it. It never looks at, keeps or logs what the message says, and group chats teach it nothing. It checks Cheogram's " +
            "signature too, so an app pretending to be Cheogram teaches nothing.", style = MaterialTheme.typography.bodyMedium, color = muted)
        Text("Names stay on this phone, in Robot's private storage, left out of Android's backups. Robot never sends them anywhere by " +
            "itself: not to Claude, not to any other helper or online AI model. Robot's own model, on the phone, sees a name only when " +
            "it looks someone up for you. Only if you choose to share with Claude or another helper a conversation in which Robot " +
            "looked someone up (Include, a helper you trust with personal content, or Safety & privacy › Send personal content to " +
            "helpers automatically) can what Robot said about them go with it.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        Text("To take it back: turn this switch off, and Robot stops learning at once. Then turn Robot off in Notification access. " +
            "Names already learned stay until you tap Remove on them, or Delete all People.", style = MaterialTheme.typography.bodyMedium, color = muted)
        // While the switch is off and access is still allowed, the row above already has the button.
        if (canTakeBack) TextButton(onClick = openAccess, modifier = Modifier.heightIn(min = 48.dp)) { Text("Notification access") }
    }
}

/** One person in the list: a letter avatar (decoration, hidden from TalkBack), the name, what you call them and how to reach them. */
@Composable
private fun PersonRow(p: Person, onClick: () -> Unit) {
    val also = (p.aliases + p.nicknames).takeIf { it.isNotEmpty() }?.let { "Also called " + it.joinToString(", ") }
    val line = listOfNotNull(also, People.summary(p).takeIf { it.isNotEmpty() }, if (!p.confirmed) "seen in Cheogram" else null).joinToString(" · ")
    PreferenceItem(p.name, line.ifEmpty { null }, onClick = onClick, leading = {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).clearAndSetSemantics {},
            contentAlignment = Alignment.Center) {
            Text(p.name.trim().take(1).uppercase(Locale.ROOT), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    })
}

// ------------------------------------------------------------------ one person

/** The person being edited, as typed (rows may be empty or wrong until saved). */
private data class Draft(
    val name: String = "",
    val aliases: String = "",
    val nicknames: String = "",
    val phones: List<Pair<PhoneKind, String>> = emptyList(),
    val emails: List<String> = emptyList(),
    /** Address and Cheogram account. */
    val xmpp: List<Pair<String, String>> = emptyList(),
) {
    fun aliasList(): List<String> = split(aliases)
    fun nicknameList(): List<String> = split(nicknames)

    companion object {
        private fun split(s: String): List<String> = s.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        fun of(p: Person) = Draft(p.name, p.aliases.joinToString(", "), p.nicknames.joinToString(", "), p.phones.map { it.kind to it.number }, p.emails,
            p.xmpp.map { it.jid to it.account.orEmpty() })

        /** Survives a rotation or the process being killed (rememberSaveable). */
        val Saver: Saver<Draft, String> = Saver(
            save = { d ->
                JSONObject().put("name", d.name).put("aliases", d.aliases).put("nicknames", d.nicknames)
                    .put("phones", JSONArray(d.phones.map { JSONArray(listOf(it.first.name, it.second)) }))
                    .put("emails", JSONArray(d.emails))
                    .put("xmpp", JSONArray(d.xmpp.map { JSONArray(listOf(it.first, it.second)) })).toString()
            },
            restore = { v ->
                val o = JSONObject(v)
                fun pairs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getString(0) to it.getString(1) } } }.orEmpty()
                Draft(o.optString("name"), o.optString("aliases"), o.optString("nicknames"),
                    pairs("phones").map { (k, n) -> (PhoneKind.entries.firstOrNull { it.name == k } ?: PhoneKind.OTHER) to n },
                    o.optJSONArray("emails")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    pairs("xmpp"))
            },
        )
    }
}

private fun nextKind(k: PhoneKind): PhoneKind = PhoneKind.entries[(k.ordinal + 1) % PhoneKind.entries.size]

/**
 * One person: name, what you call them, numbers, emails, XMPP addresses (with the Cheogram account), the choices a
 * card remembered, "Save to phone's contacts" and Delete. Saved with Save; Back with changes asks first. Saving (or
 * "Keep") confirms someone learned from a Cheogram notification.
 */
@Composable
fun PersonScreen(id: String?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PeopleFiles.store(ctx) }
    /** The person's id: [id], or the one a new person got at its first save (kept across a rotation). */
    var pid by rememberSaveable { mutableStateOf(id) }
    /** A new person's id, the same for every Save (a double tap never makes two people). */
    val newId = rememberSaveable { PeopleStore.newId() }
    /** The person as the file holds them now (the banner, remembered choices). */
    var original by remember { mutableStateOf<Person?>(null) }
    /** The person the draft was loaded from: Save applies only what was changed since. */
    var base by rememberSaveable(stateSaver = PersonSaver) { mutableStateOf<Person?>(null) }
    var loaded by rememberSaveable { mutableStateOf(id == null) }
    var draft by rememberSaveable(stateSaver = Draft.Saver) { mutableStateOf(Draft(phones = listOf(PhoneKind.MOBILE to ""))) }
    var saved by rememberSaveable(stateSaver = Draft.Saver) { mutableStateOf(draft) }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    // Read again whenever the screen comes back (the assistant or a notification may have changed them): with no
    // unsaved changes the fields show what is stored now.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    val resumed = lstate.isAtLeast(Lifecycle.State.RESUMED)
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) { snapshotFlow { windowInfo.isWindowFocused }.collect { if (it) tick++ } }
    LaunchedEffect(pid, resumed, tick) {
        val cur = pid ?: return@LaunchedEffect
        if (!resumed || saving) return@LaunchedEffect
        val p = withContext(Dispatchers.IO) { store.get(cur) }
        if (p == null) { gone = true; return@LaunchedEffect }
        gone = false
        original = p
        if (!loaded || draft == saved) {
            if (!loaded || Draft.of(p) != saved) { draft = Draft.of(p); saved = draft }
            base = p
            loaded = true
        }
    }

    val nameOk = draft.name.isNotBlank()
    val phoneOk = draft.phones.map { it.second.isBlank() || Addresses.isPhone(it.second) }
    val emailOk = draft.emails.map { it.isBlank() || Addresses.isAddress(it) }
    val jidOk = draft.xmpp.map { it.first.isBlank() || Addresses.isJid(it.first) }
    val accountOk = draft.xmpp.map { it.second.isBlank() || Addresses.isAddress(it.second) }
    val valid = nameOk && (phoneOk + emailOk + jidOk + accountOk).all { it }
    val dirty = draft != saved

    /** [d] as a person, over [o] (the person it was loaded from): what Save compares, row by row, with [o] as a draft. */
    fun person(d: Draft, o: Person?, now: Long): Person {
        val phones = d.phones.filter { it.second.isNotBlank() }.map { (k, n) -> ContactPhone(n.trim(), k, People.label(k)) }
        val emails = d.emails.map { it.trim() }.filter { it.isNotEmpty() }
        // An address picked in Cheogram's list stays picked while its account is unchanged.
        val xmpp = d.xmpp.filter { it.first.isNotBlank() }.map { (j, acct) ->
            val a = acct.trim().lowercase(Locale.ROOT).ifEmpty { null }
            val was = o?.xmpp?.firstOrNull { it.jid.equals(j.trim(), ignoreCase = true) }
            XmppAddress(j.trim(), a, picked = was?.picked == true && was.account == a)
        }
        return People.clean(Person(o?.id ?: pid ?: newId, d.name, aliases = d.aliasList(), nicknames = d.nicknameList(),
            phones = phones, emails = emails, xmpp = xmpp, source = o?.source ?: PersonSource.MANUAL,
            confirmed = true, systemLookupKey = o?.systemLookupKey, updatedAt = now))
    }

    fun save(then: () -> Unit = {}) {
        if (saving) return
        if (!valid) { showErrors = true; a.say("Check what's marked in red."); return }
        val now = System.currentTimeMillis()
        val b = base
        // Only what was typed here is applied, to the person as stored now: a choice remembered meanwhile is kept.
        val from = b?.let { person(Draft.of(it), it, now) }
        val edited = person(draft, b, now)
        saving = true
        scope.launch {
            var result: Person? = null
            var deleted = false
            val ok = withContext(Dispatchers.IO) {
                store.update { l -> PeopleEdit.applyEdit(l, from, edited, now)?.let { (list, p) -> result = p; list } ?: l.also { deleted = true } }
            } != null
            saving = false
            val p = result
            if (deleted) { gone = true; return@launch a.say("They were deleted meanwhile: Robot didn't bring them back.") }
            if (!ok || p == null) return@launch a.say("Robot couldn't save them.")
            original = p
            base = p
            pid = p.id
            draft = Draft.of(p)
            saved = draft
            then()
        }
    }

    /** "Forget" on a remembered choice. */
    fun forget(email: Boolean) {
        val cur = pid ?: return
        scope.launch {
            withContext(Dispatchers.IO) { store.update { PeopleEdit.forgetChoice(it, cur, email, System.currentTimeMillis()) }; store.get(cur) }
                ?.let { original = it }
        }
    }

    fun leave() = if (dirty && !gone) confirmLeave = true else onBack()
    BackHandler { leave() }
    Scaffold(
        topBar = {
            BackBar(if (pid == null) "New person" else "Person", ::leave) {
                TextButton(onClick = { save() }, enabled = loaded && !gone && !saving && (dirty || original?.confirmed == false)) { Text("Save") }
                if (original != null) IconButton(onClick = { confirmDelete = true }) { Icon(RIcons.Delete, "Delete") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (gone) {
                Text("This person was deleted.", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            val o = original
            if (o != null && !o.confirmed) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Seen in a Cheogram notification", style = MaterialTheme.typography.titleSmall)
                        Text("Whoever sent the message chose this name. Until you keep or edit them, Robot always shows a card first, and never offers them in place of someone you know.",
                            style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { save { a.say("Kept.") } }) { Text("Keep") }
                            OutlinedButton(onClick = { confirmDelete = true }) { Text("Remove") }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft.name, onValueChange = { draft = draft.copy(name = it) }, singleLine = true, label = { Text("Name") }, enabled = loaded,
                isError = showErrors && !nameOk, supportingText = if (showErrors && !nameOk) { { Text("A name is needed.") } } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.aliases, onValueChange = { draft = draft.copy(aliases = it) }, singleLine = true, label = { Text("Also called") }, enabled = loaded,
                supportingText = { Text("What you call them, separated by commas: mom, maman. Said exactly, it means this person.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.nicknames, onValueChange = { draft = draft.copy(nicknames = it) }, singleLine = true, label = { Text("Nicknames") }, enabled = loaded,
                supportingText = { Text("Other names they go by, as in a contacts file. They match like any name, not ahead of others.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Numbers", style = MaterialTheme.typography.titleSmall)
            draft.phones.forEachIndexed { i, (kind, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = n, onValueChange = { v -> draft = draft.copy(phones = draft.phones.mapIndexed { j, x -> if (j == i) x.first to v else x }) },
                        singleLine = true, enabled = loaded, isError = showErrors && !phoneOk[i], placeholder = { Text("+1 514 555 0101") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = { draft = draft.copy(phones = draft.phones.mapIndexed { j, x -> if (j == i) nextKind(x.first) to x.second else x }) },
                        label = { Text(People.label(kind)) }, modifier = Modifier.heightIn(min = 48.dp))
                    IconButton(onClick = { draft = draft.copy(phones = draft.phones.filterIndexed { j, _ -> j != i }) }) { Icon(RIcons.Close, "Remove the number") }
                }
            }
            TextButton(onClick = { draft = draft.copy(phones = draft.phones + (PhoneKind.MOBILE to "")) }, enabled = loaded) { Text("Add a number") }
            Text("Emails", style = MaterialTheme.typography.titleSmall)
            draft.emails.forEachIndexed { i, e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = e, onValueChange = { v -> draft = draft.copy(emails = draft.emails.mapIndexed { j, x -> if (j == i) v else x }) },
                        singleLine = true, enabled = loaded, isError = showErrors && !emailOk[i], placeholder = { Text("name@example.org") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { draft = draft.copy(emails = draft.emails.filterIndexed { j, _ -> j != i }) }) { Icon(RIcons.Close, "Remove the email") }
                }
            }
            TextButton(onClick = { draft = draft.copy(emails = draft.emails + "") }, enabled = loaded) { Text("Add an email") }
            Text("XMPP", style = MaterialTheme.typography.titleSmall)
            draft.xmpp.forEachIndexed { i, (jid, acct) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = jid, onValueChange = { v -> draft = draft.copy(xmpp = draft.xmpp.mapIndexed { j, x -> if (j == i) v to x.second else x }) },
                            singleLine = true, enabled = loaded, isError = showErrors && !jidOk[i], label = { Text("Address") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { draft = draft.copy(xmpp = draft.xmpp.filterIndexed { j, _ -> j != i }) }) { Icon(RIcons.Close, "Remove the address") }
                    }
                    OutlinedTextField(
                        value = acct, onValueChange = { v -> draft = draft.copy(xmpp = draft.xmpp.mapIndexed { j, x -> if (j == i) x.first to v else x }) },
                        singleLine = true, enabled = loaded, isError = showErrors && !accountOk[i], label = { Text("Your Cheogram account (optional)") },
                        supportingText = { Text("Filled in when you pick them in Cheogram: Robot then opens their chat in that account directly.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
                    )
                }
            }
            TextButton(onClick = { draft = draft.copy(xmpp = draft.xmpp + ("" to "")) }, enabled = loaded) { Text("Add an XMPP address") }
            if (o != null) {
                o.preferredText?.let { pref ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Texts always go to $pref", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { forget(email = false) }) { Text("Forget") }
                    }
                }
                o.preferredEmail?.let { pref ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Emails always go to $pref", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { forget(email = true) }) { Text("Forget") }
                    }
                }
                // 0.7: Settings › Messages on: replies to them without a card (3-second stop).
                app.heyrobot.ui.settings.NoCardSwitch(o)
                app.heyrobot.ui.settings.AnnounceSwitch(o)
                Text("${o.source.title} · changed ${DateUtils.getRelativeTimeSpanString(o.updatedAt)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    if (dirty) return@OutlinedButton a.say("Save first.")
                    try { ctx.startActivity(PhoneContacts.insert(o)) } catch (_: ActivityNotFoundException) { a.say("No contacts app on this phone takes new contacts.") }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save to phone's contacts") }
                Text("Opens your Contacts app with everything filled in: choose the account and save there. Cheogram, Proton Mail and the " +
                    "dialer then know the name too. Robot itself never writes to your contacts.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${original?.name ?: "this person"}?") },
            text = { Text("Robot forgets them and what you call them. The phone's contacts aren't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val o = original ?: return@TextButton onBack()
                    scope.launch {
                        // Someone learned from a notification isn't learned again.
                        val ok = withContext(Dispatchers.IO) { store.remove(o.id, ignore = !o.confirmed) }
                        a.say(if (ok) "Deleted." else "Robot couldn't delete them.")
                        if (ok) onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Save your changes?") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; save(onBack) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Discard") } },
        )
    }
}
