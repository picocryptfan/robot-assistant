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
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.People
import app.heyrobot.core.PeopleEdit
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.Person
import app.heyrobot.core.PersonSource
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.Preferred
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

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
    var tick by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    /** A contacts file read and waiting for "Import": what it holds, and what importing it into the list as it was would do. */
    var preview by remember { mutableStateOf<Preview?>(null) }
    var askLegacy by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    val resumed = lstate.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(resumed, tick) {
        if (resumed) people = withContext(Dispatchers.IO) { People.sorted(store.list()) }
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
        val pick = CheogramIntents.picked(r.data) ?: return@rememberLauncherForActivityResult a.say("Nobody was picked in Cheogram.")
        scope.launch {
            var id: String? = null
            withContext(Dispatchers.IO) { store.update { l -> PeopleEdit.rememberPick(l, null, pick, now()).let { (list, p) -> id = p.id; list } } }
            tick++
            id?.let(onOpen) ?: a.say("Robot couldn't save them.")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
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

    Scaffold(topBar = { BackBar("People", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        val shown = remember(people, query) { if (query.isBlank()) people else people.filter { People.matches(it, query) } }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("People Robot knows besides your contacts: who to text or email, and what you call them. Kept on this phone only, " +
                    "never sent to Claude, and not in Android's backups.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SectionCard("Add people") {
                    NavRow(RIcons.Add, "Add person", "Type a name, numbers, emails and XMPP addresses") { onOpen(null) }
                    NavRow(RIcons.Folder, "Import contacts file (.vcf)",
                        "Proton Mail: on the web, Contacts › select all › Export selection as .VCF. Robot keeps names, nicknames, numbers, " +
                            "emails and XMPP addresses only.") {
                        launch({ importer.launch(arrayOf("text/vcard", "text/x-vcard", "text/directory", "*/*")) }, "No app on this phone opens files.")
                    }
                    val pkg = HandOffApps.cheogram(ctx)
                    if (pkg != null && remember(pkg) { CheogramIntents.canPick(ctx, pkg) }) {
                        NavRow(RIcons.Message, "Add from Cheogram", "Pick someone in Cheogram's list (it says “Create shortcut”: nothing goes on your home screen)") {
                            launch({ cheogram.launch(CheogramIntents.pick(pkg)) }, "Cheogram's list didn't open.")
                        }
                    }
                    NavRow(RIcons.Person, "Add from phone contacts", "Pick one contact; Robot needs no permission for this") {
                        try { picker.launch(PhoneContacts.picker()) } catch (_: ActivityNotFoundException) { askLegacy = true }
                    }
                    NavRow(RIcons.Download, "Export (.vcf)", "A backup, and a file any contacts app can import") {
                        launch({ exporter.launch("Robot People.vcf") }, "No app on this phone saves files.")
                    }
                }
            }
            item {
                SectionCard("Remembering") {
                    SwitchRow("Remember who I pick", "Robot offers to remember who you pick in Cheogram, and the number or address you tap on a card " +
                        "(“Always use this for mom”).", s.rememberPicks) { update(s.copy(rememberPicks = it)) }
                }
            }
            item {
                val learned = people.filter { !it.confirmed }
                SectionCard("Learn from Cheogram") {
                    SwitchRow("Learn names from Cheogram notifications",
                        "When someone messages you in Cheogram, Robot keeps their name, their address and your account, never the message. " +
                            "A name learned this way always shows the card before a text, since whoever sends a message chooses it.",
                        s.learnFromCheogram) { on ->
                        update(s.copy(learnFromCheogram = on))
                        if (on) NotificationAccess.rebind(ctx)
                    }
                    val granted = facts?.notificationAccess == true
                    when {
                        s.learnFromCheogram && !granted -> NotificationAccessSteps()
                        s.learnFromCheogram -> StatusRow(Mark.OK, "Notification access allowed",
                            "Robot looks at Cheogram's notifications only, and of those only the sender of a 1:1 chat.")
                        granted -> StatusRow(Mark.TODO, "Notification access is still allowed",
                            "Robot ignores notifications while this is off. You can take the access back in Android's settings.") {
                            TextButton(onClick = { openFirst(ctx, NotificationAccess.settings(ctx), a) }) { Text("Open") }
                        }
                    }
                    if (learned.isNotEmpty()) {
                        Text("Learned from Cheogram (${learned.size})", style = MaterialTheme.typography.titleSmall)
                        for (p in learned.take(MAX_LEARNED_SHOWN)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { onOpen(p.id) }) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(People.summary(p), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { scope.launch {
                                    withContext(Dispatchers.IO) { store.update { PeopleEdit.confirm(it, p.id, now()) } }
                                    tick++
                                } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") }
                                TextButton(onClick = { scope.launch {
                                    withContext(Dispatchers.IO) { store.delete(p.id) }
                                    tick++
                                } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
                            }
                        }
                        if (learned.size > MAX_LEARNED_SHOWN) Text("…and ${learned.size - MAX_LEARNED_SHOWN} more in the list below.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text(if (people.isEmpty()) "No one yet" else "Your people (${people.size})", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            if (people.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search people") }, leadingIcon = { Icon(RIcons.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(RIcons.Close, "Clear") } },
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (people.isNotEmpty() && shown.isEmpty()) item { Text("No one matches.", style = MaterialTheme.typography.bodyMedium) }
            items(shown, key = { it.id }) { p -> PersonRow(p) { onOpen(p.id) } }
            if (people.isNotEmpty()) {
                item { TextButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete all People") } }
            }
        }
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
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all People?") },
            text = { Text("Robot forgets everyone here and what you call them. The phone's contacts aren't touched. This can't be undone.") },
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

/** The first of [intents] that opens (Android's page for Robot, else the general one). */
internal fun openFirst(ctx: android.content.Context, intents: List<android.content.Intent>, a: AppActions) {
    for (i in intents) {
        try { ctx.startActivity(i); return } catch (_: Exception) {}
    }
    a.say("Open Settings › Notifications › Device & app notifications, or Settings › Apps › Special app access › Notification access.")
}

/**
 * Notification access, step by step. Android blocks it for apps installed from a file (Robot, from its release page)
 * until "Allow restricted settings" is on in the app's App info, and that menu item only appears once Android has
 * refused the access, hence the order.
 */
@Composable
internal fun NotificationAccessSteps() {
    val ctx = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Allow notification access", style = MaterialTheme.typography.titleSmall)
        Text("1. Tap Notification access and turn Robot on. If Android says it's a restricted setting, tap OK.", style = MaterialTheme.typography.bodyMedium, color = muted)
        Text("2. Tap App info, then ⋮ (top right) › Allow restricted settings, and confirm. No such item? Android isn't blocking Robot: skip this.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        Text("3. Tap Notification access again and turn Robot on.", style = MaterialTheme.typography.bodyMedium, color = muted)
        Text("Notification access lets Robot see every app's notifications; it looks at Cheogram's only, and keeps names and addresses, never text.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                for (i in NotificationAccess.settings(ctx)) { try { ctx.startActivity(i); return@FilledTonalButton } catch (_: Exception) {} }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Notification access") }
            OutlinedButton(onClick = { try { ctx.startActivity(NotificationAccess.appInfo(ctx)) } catch (_: Exception) {} },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("App info") }
        }
    }
}

@Composable
private fun PersonRow(p: Person, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.aliases.isNotEmpty() || p.nicknames.isNotEmpty()) Text("Also called " + (p.aliases + p.nicknames).joinToString(", "), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(People.summary(p).takeIf { it.isNotEmpty() }, if (!p.confirmed) "seen in Cheogram" else null).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
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
    var original by remember { mutableStateOf<Person?>(null) }
    var loaded by rememberSaveable { mutableStateOf(id == null) }
    var draft by rememberSaveable(stateSaver = Draft.Saver) { mutableStateOf(Draft(phones = listOf(PhoneKind.MOBILE to ""))) }
    var saved by rememberSaveable(stateSaver = Draft.Saver) { mutableStateOf(draft) }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }

    LaunchedEffect(pid) {
        val cur = pid ?: return@LaunchedEffect
        if (original?.id == cur) return@LaunchedEffect
        val p = withContext(Dispatchers.IO) { store.get(cur) }
        if (p == null) { gone = true; return@LaunchedEffect }
        original = p
        if (!loaded) {
            draft = Draft.of(p)
            saved = draft
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

    fun person(now: Long): Person {
        val o = original
        val phones = draft.phones.filter { it.second.isNotBlank() }.map { (k, n) -> ContactPhone(n.trim(), k, People.label(k)) }
        val emails = draft.emails.map { it.trim() }.filter { it.isNotEmpty() }
        val xmpp = draft.xmpp.filter { it.first.isNotBlank() }.map { (j, acct) -> XmppAddress(j.trim(), acct.trim().ifEmpty { null }) }
        // A remembered choice that was removed here is forgotten.
        val text = o?.preferredText?.takeIf { pref -> phones.any { Preferred.same(it.number, pref) } || xmpp.any { Preferred.same(it.jid, pref) } }
        val mail = o?.preferredEmail?.takeIf { pref -> emails.any { it.equals(pref, ignoreCase = true) } }
        return People.clean(Person(o?.id ?: pid ?: PeopleStore.newId(), draft.name, aliases = draft.aliasList(), nicknames = draft.nicknameList(),
            phones = phones, emails = emails, xmpp = xmpp, preferredText = text, preferredEmail = mail, source = o?.source ?: PersonSource.MANUAL,
            confirmed = true, systemLookupKey = o?.systemLookupKey, updatedAt = now))
    }

    fun save(then: () -> Unit = {}) {
        if (!valid) { showErrors = true; a.say("Check what's marked in red."); return }
        val p = person(System.currentTimeMillis())
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.save(p) }
            if (!ok) return@launch a.say("Robot couldn't save them.")
            original = p
            pid = p.id
            draft = Draft.of(p)
            saved = draft
            then()
        }
    }

    fun leave() = if (dirty && !gone) confirmLeave = true else onBack()
    BackHandler { leave() }
    Scaffold(
        topBar = {
            BackBar(if (pid == null) "New person" else "Person", ::leave) {
                TextButton(onClick = { save() }, enabled = loaded && !gone && (dirty || original?.confirmed == false)) { Text("Save") }
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
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
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
                        supportingText = { Text("The account this chat is in, so Cheogram opens it without a search.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
                    )
                }
            }
            TextButton(onClick = { draft = draft.copy(xmpp = draft.xmpp + ("" to "")) }, enabled = loaded) { Text("Add an XMPP address") }
            if (o != null) {
                o.preferredText?.let { pref ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Texts always go to $pref", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch {
                            val p = o.copy(preferredText = null, updatedAt = System.currentTimeMillis())
                            if (withContext(Dispatchers.IO) { store.save(p) }) original = p
                        } }) { Text("Forget") }
                    }
                }
                o.preferredEmail?.let { pref ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Emails always go to $pref", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch {
                            val p = o.copy(preferredEmail = null, updatedAt = System.currentTimeMillis())
                            if (withContext(Dispatchers.IO) { store.save(p) }) original = p
                        } }) { Text("Forget") }
                    }
                }
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
                    val pid = original?.id ?: return@TextButton onBack()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { store.delete(pid) }
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
