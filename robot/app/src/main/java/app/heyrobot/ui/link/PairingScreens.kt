// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(ExperimentalLayoutApi::class)

package app.heyrobot.ui.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.People
import app.heyrobot.core.Person
import app.heyrobot.core.link.BlockedView
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPolicy
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.SafetyNumber
import app.heyrobot.core.link.Verified
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import app.heyrobot.ui.common.AlertDialog
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.link.LinkCardText
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. 1.5, a pairing's pages (robot-to-robot-plan.md §2.6, §2.7, §6.2, §6.3; job L5): its Robot link (mode,
// verified or not and since when, Compare safety number, Permissions, Raise to Anonymous or Ask to switch to Private,
// Unpair, Block; and what waits for you: who this is, their ask to switch to Private, a move to a new phone, an ENDED note),
// the permissions page (a level per capability, its limit as one line, the levels Android's ListPreference offers in place
// of segmented buttons, as Robot's other settings do), and the safety number in 12 groups of 5. Also the blocks the pairing
// screen shares: who is this, the safety number, the presets. The section on a person's page in People says how the
// pairing stands and opens its Robot link. Sent and received (§6.3) comes with L6 and L8.

/** What a pairing's pages read: its view (null: gone), your People, and the working hours shown in the events limit. */
private class PairingRead(val view: PairingView?, val people: List<Person>, val hours: Pair<String, String>)

@Composable
private fun rememberPairing(port: LinkPort, id: String, tick: Int): PairingRead? {
    var read by remember { mutableStateOf<PairingRead?>(null) }
    val owner = LocalLifecycleOwner.current
    var again by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner) {
        var first = true
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { if (!first) again++; first = false }
    }
    val lang = app.heyrobot.ui.theme.LocalLangs.current.say
    LaunchedEffect(tick, again, lang) {
        read = withContext(Dispatchers.IO) {
            val h = port.settings().hours
            val hours = Fmt.clock(h.start, lang) to Fmt.clock(h.end, lang)
            PairingRead(port.pairings()?.firstOrNull { it.id == id }, port.people(), hours)
        }
    }
    return read
}

/** A pairing's Robot link (link/p/<id>). */
@Composable
fun PairingScreen(port: LinkPort, id: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit, pollMs: Long = POLL_MS) {
    SecureWindow()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    var confirmBlock by rememberSaveable { mutableStateOf(false) }
    val read = rememberPairing(port, id, tick)
    // A move or an ask to switch may arrive while the page shows: read again every few seconds.
    LaunchedEffect(id, pollMs) { while (pollMs > 0) { delay(pollMs); tick++ } }

    fun work(block: () -> Boolean, done: Int?, then: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { block() }
            busy = false
            if (ok) { done?.let { a.say(ctx.getString(it)) }; then() } else a.say(ctx.getString(R.string.link_not_saved))
            tick++
        }
    }

    val v = read?.view
    val people = read?.people.orEmpty()
    val name = v?.let { displayName(it, people) } ?: stringResource(R.string.link_section)
    // Read here: the list's builder isn't composable.
    val linkTitle = stringResource(R.string.link_section)
    val modeTitle = stringResource(R.string.link_mode)
    PreferenceScreen(name, onBack, snackbar = snackbar) {
        if (read == null) return@PreferenceScreen
        if (v == null) { item(key = "gone") { IntroPreference(stringResource(R.string.link_gone)) }; return@PreferenceScreen }
        // What waits for you first.
        when (v.phase) {
            PairingPhase.MOVED -> item(key = "moved") {
                Attention(stringResource(R.string.link_moved_title, name), stringResource(R.string.link_moved_text)) {
                    Button(onClick = { a.openPage(Page.Settings(LinkRoutes.safety(id))) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_compare)) }
                    OutlinedButton(onClick = { work({ port.answerMoved(id, false) }, R.string.link_moved_kept) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.link_moved_keep))
                    }
                }
            }
            PairingPhase.ENDED -> item(key = "ended") {
                Attention(stringResource(R.string.link_ended_title, name), stringResource(R.string.link_ended_text)) {
                    OutlinedButton(onClick = { work({ port.unpair(id) }, null, onBack) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_remove)) }
                }
            }
            PairingPhase.MOVING -> item(key = "moving") { IntroPreference(stringResource(R.string.link_moving_text, name)) }
            PairingPhase.SENT -> item(key = "sent") { IntroPreference(stringResource(R.string.link_sent_text)) }
            PairingPhase.ACTIVE -> if (v.lowerOffered) item(key = "lower") {
                Attention(stringResource(R.string.link_lower_offered, name), modeDetail(LinkMode.PRIVATE)) {
                    Button(onClick = { work({ port.lower(id) }, R.string.link_lowered) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_switch_private)) }
                    OutlinedButton(onClick = { work({ port.keepMode(id) }, null) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_keep_anonymous)) }
                }
            }
        }
        if (v.phase == PairingPhase.ACTIVE && (v.person == null || choosing)) item(key = "who") {
            WhoIsThis(port, v, maker = null) { person ->
                choosing = false
                if (person != null) work({ port.setPerson(id, person.id) }, null)
            }
        }
        preferenceGroup(linkTitle, "link") {
            PreferenceItem(stringResource(R.string.link_who), personOf(v, people)?.name ?: stringResource(R.string.link_row_unlinked), RIcons.Person,
                enabled = v.phase != PairingPhase.ENDED, onClick = { choosing = true })
            app.heyrobot.core.link.LinkNames.claim(v.claimed).takeIf { it.isNotEmpty() }?.let { PreferenceItem(stringResource(R.string.link_row_says, it), null) }
            PreferenceItem(stringResource(R.string.link_mode), modeName(v.mode))
            StatusPreference(verifiedLine(v, since = true), stringResource(R.string.link_paired_since, android.text.format.DateUtils.getRelativeTimeSpanString(v.created * 1000).toString()),
                if (v.verified == Verified.NO) Mark.TODO else Mark.OK)
            if (v.phase == PairingPhase.ACTIVE) {
                PreferenceItem(stringResource(R.string.link_compare), null, RIcons.Key, onClick = { a.openPage(Page.Settings(LinkRoutes.safety(id))) })
                PreferenceItem(stringResource(R.string.link_permissions), presetLine(v), RIcons.Shield, onClick = { a.openPage(Page.Settings(LinkRoutes.permissions(id))) })
            }
        }
        if (v.phase == PairingPhase.ACTIVE) preferenceGroup(modeTitle, "mode") {
            if (v.mode == LinkMode.PRIVATE) {
                PreferenceItem(stringResource(R.string.link_raise), stringResource(R.string.link_raise_detail), RIcons.Lock, enabled = !busy,
                    onClick = { work({ port.raise(id) }, R.string.link_raised) })
            } else {
                val line = when {
                    v.lowerAsked -> stringResource(R.string.link_lower_waiting)
                    v.lowerDeclinedAt > 0 -> stringResource(R.string.link_lower_declined)
                    else -> stringResource(R.string.link_lower_ask_detail)
                }
                PreferenceItem(stringResource(R.string.link_lower_ask), line, null, enabled = !busy && !v.lowerAsked,
                    onClick = { work({ port.lower(id) }, R.string.link_lower_asked) })
            }
        }
        if (v.phase != PairingPhase.ENDED) preferenceGroup(null, "end") {
            PreferenceItem(stringResource(R.string.link_unpair), null, RIcons.Close, enabled = !busy, onClick = { confirmUnpair = true })
            PreferenceItem(stringResource(R.string.link_block), null, RIcons.Shield, enabled = !busy, onClick = { confirmBlock = true })
        } else preferenceGroup(null, "end") {
            PreferenceItem(stringResource(R.string.link_block), null, RIcons.Shield, enabled = !busy, onClick = { confirmBlock = true })
        }
    }
    if (confirmUnpair) {
        ConfirmDialog(stringResource(R.string.link_unpair_title, name), stringResource(R.string.link_unpair_text), stringResource(R.string.link_unpair),
            onDismiss = { confirmUnpair = false }) { confirmUnpair = false; work({ port.unpair(id) }, R.string.link_unpaired, onBack) }
    }
    if (confirmBlock) {
        val label = v?.let { personOf(it, people)?.name ?: app.heyrobot.core.link.LinkNames.claim(it.claimed) }.orEmpty()
        ConfirmDialog(stringResource(R.string.link_block_title, name), stringResource(R.string.link_block_text), stringResource(R.string.link_block),
            onDismiss = { confirmBlock = false }) { confirmBlock = false; work({ port.block(id, BlockedView.label(label)) }, R.string.link_blocked_done, onBack) }
    }
}

@Composable
private fun presetLine(v: PairingView): String {
    val preset = v.preset ?: return stringResource(R.string.link_preset_until)
    return when {
        v.levels == LinkLevels.of(preset) -> stringResource(if (preset == LinkPreset.FAMILY_OR_FRIEND) R.string.link_preset_family else R.string.link_preset_else)
        else -> stringResource(R.string.link_preset_custom)
    }
}

/** Something waiting for your answer: its title, a line, its buttons. */
@Composable
internal fun Attention(title: String, text: String, buttons: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }
}

/** The permissions page (link/p/<id>/permissions): a preset, then each capability's level and its limit. */
@Composable
fun PermissionsScreen(port: LinkPort, id: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val read = rememberPairing(port, id, tick)
    fun save(block: () -> Boolean) = scope.launch {
        if (!withContext(Dispatchers.IO) { block() }) a.say(ctx.getString(R.string.link_not_saved))
        tick++
    }
    val v = read?.view
    val presetTitle = stringResource(R.string.link_preset_title)
    PreferenceScreen(stringResource(R.string.link_permissions), onBack, snackbar = snackbar) {
        if (read == null) return@PreferenceScreen
        if (v == null || v.phase == PairingPhase.ENDED) { item(key = "gone") { IntroPreference(stringResource(R.string.link_gone)) }; return@PreferenceScreen }
        val name = personOf(v, read.people)?.name ?: ctx.getString(R.string.link_someone)
        item(key = "intro") { IntroPreference(stringResource(R.string.link_permissions_intro, name)) }
        preferenceGroup(presetTitle, "preset") {
            PresetRows(v.preset?.takeIf { v.levels == LinkLevels.of(it) }) { p -> save { port.setPreset(id, p) } }
        }
        preferenceGroup(null, "levels") {
            val names = app.heyrobot.core.link.LinkLevel.entries.associateWith { levelName(it) }
            val details = app.heyrobot.core.link.LinkLevel.entries.associateWith { levelDetail(it) }
            val askLists = stringResource(R.string.link_level_ask_detail_lists)
            val askFolders = stringResource(R.string.link_level_ask_detail_folders)
            for (cap in LinkLevels.SHOWN) {
                val level = v.levels.getValue(cap)
                ListPreference(capabilityName(cap), level, LinkPolicy.levels(cap), label = { names.getValue(it) }, detail = { if (cap == app.heyrobot.core.link.LinkCapability.LISTS && it == app.heyrobot.core.link.LinkLevel.ASK) askLists else if (cap == app.heyrobot.core.link.LinkCapability.FOLDERS && it == app.heyrobot.core.link.LinkLevel.ASK) askFolders else details.getValue(it) },
                    summary = stringResource(R.string.link_row_summary, levelName(level), limitLine(cap, read.hours))) { to ->
                    if (to != level) save { port.setLevel(id, cap, to) }
                }
            }
        }
        item(key = "note") { app.heyrobot.ui.pref.FooterPreference(stringResource(R.string.link_preset_note)) }
    }
}

/** The two presets (D8), each with its line; [current] the one the levels still match, if any. */
@Composable
internal fun PresetRows(current: LinkPreset?, onPick: (LinkPreset) -> Unit) {
    for (p in listOf(LinkPreset.FAMILY_OR_FRIEND, LinkPreset.SOMEONE_ELSE)) {
        app.heyrobot.ui.pref.RadioPreference(
            stringResource(if (p == LinkPreset.FAMILY_OR_FRIEND) R.string.link_preset_family else R.string.link_preset_else),
            stringResource(if (p == LinkPreset.FAMILY_OR_FRIEND) R.string.link_preset_family_detail else R.string.link_preset_else_detail),
            selected = current == p,
            onClick = { onPick(p) },
        )
    }
}

/** The safety number page (link/p/<id>/safety): while a move waits, the new phone's number, and accepting it. */
@Composable
fun SafetyScreen(port: LinkPort, id: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val read = rememberPairing(port, id, tick)
    val v = read?.view
    PreferenceScreen(stringResource(R.string.link_safety_title), onBack, snackbar = snackbar) {
        if (read == null) return@PreferenceScreen
        if (v == null) { item(key = "gone") { IntroPreference(stringResource(R.string.link_gone)) }; return@PreferenceScreen }
        val moved = v.phase == PairingPhase.MOVED
        item(key = "number") {
            SafetyNumberBlock(port, v, displayName(v, read.people), accept = stringResource(if (moved) R.string.link_moved_accept else R.string.link_they_match),
                later = null) { match ->
                if (!match) return@SafetyNumberBlock
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { if (moved) port.answerMoved(id, true) else port.verify(id) }
                    a.say(ctx.getString(if (!ok) R.string.link_not_saved else if (moved) R.string.link_moved_accepted else R.string.link_match_saved))
                    if (ok) onBack() else tick++
                }
            }
        }
    }
}

/** The number in 12 groups of 5, what to do with it, and [accept] ("They match") and [later] ("Compare later"). */
@Composable
internal fun SafetyNumberBlock(port: LinkPort, v: PairingView, name: String, accept: String, later: String?, onAnswer: (Boolean) -> Unit) {
    var digits by remember(v.id, v.phase) { mutableStateOf<String?>(null) }
    var read by remember(v.id, v.phase) { mutableStateOf(false) }
    LaunchedEffect(v.id, v.phase) {
        digits = withContext(Dispatchers.IO) { port.safety(v.id) }
        read = true
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val d = digits
        if (!read) return@Column
        if (d == null || !SafetyNumber.ok(d)) {
            Text(stringResource(R.string.link_safety_none), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Text(stringResource(R.string.link_safety_detail, name), style = MaterialTheme.typography.bodyLarge)
        val groups = SafetyNumber.groups(d)
        val spoken = stringResource(R.string.link_safety_number_description, groups.joinToString(" "))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in groups.chunked(3)) {
                    Text(row.joinToString("   "), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Text(stringResource(R.string.link_safety_differ), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(accept) }
            if (later != null) OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(later) }
        }
    }
}

/**
 * "Who is this" (§2.3, §6.2 step 3): pick from your People or add someone. The name their Robot sent is shown as what it
 * says, never picks anyone and never fills a field. [maker]: you made the code (true), you used theirs (false), or neither
 * said (null). [onDone] gets the person chosen, or null for "Decide later".
 */
@Composable
internal fun WhoIsThis(port: LinkPort, v: PairingView, maker: Boolean?, onDone: (Person?) -> Unit) {
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<Person>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { people = withContext(Dispatchers.IO) { port.people() } }
    val shown = remember(people, query) { if (query.isBlank()) people else people.filter { People.matches(it, query) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.link_who_title), style = MaterialTheme.typography.titleMedium)
        val said = app.heyrobot.core.link.LinkNames.claim(v.claimed)
        Text(when {
            maker == true && said.isNotEmpty() -> stringResource(R.string.link_who_maker, said)
            maker == true -> stringResource(R.string.link_who_maker_unnamed)
            said.isNotEmpty() -> stringResource(R.string.link_who_sender, said)
            else -> stringResource(R.string.link_who_sender_unnamed)
        }, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.link_who_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (adding) {
            OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text(stringResource(R.string.link_add_name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val n = newName.trim()
                    scope.launch { withContext(Dispatchers.IO) { port.addPerson(n) }?.let { onDone(it) } }
                }, enabled = newName.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_add_button)) }
                TextButton(onClick = { adding = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_cancel)) }
            }
            return@Column
        }
        if (people.size > SEARCH_FROM) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.link_who_search)) }, modifier = Modifier.fillMaxWidth())
        }
        for (p in shown.take(MAX_SHOWN)) PreferenceItem(p.name, null, RIcons.Person, onClick = { onDone(p) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_add_someone)) }
            TextButton(onClick = { onDone(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_who_later)) }
        }
    }
}

/**
 * A person's page in People: their Robot link in one row (how it stands, and anything waiting for you), which opens its
 * page. Nothing shows while Robot to Robot is off, and :link is only asked when it is on.
 */
@Composable
fun PersonLinkSection(personId: String, a: AppActions) {
    val ctx = LocalContext.current
    var views by remember { mutableStateOf<List<PairingView>>(emptyList()) }
    var files by remember { mutableStateOf<List<app.heyrobot.core.link.TransferView>>(emptyList()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(personId, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            views = withContext(Dispatchers.IO) {
                if (!app.heyrobot.sys.LinkSettingsStore.current(ctx).on) emptyList()
                else {
                    val port = LinkPorts.make(ctx)
                    try {
                        port.pairings().orEmpty().filter { it.person == personId }.also { mine ->
                            // L8 (§6.3): what went between you, newest first.
                            files = if (mine.isEmpty()) emptyList() else port.transfers().filter { t -> mine.any { it.id == t.pairing } }.sortedByDescending { it.at }.take(SENT_RECEIVED)
                        }
                    } finally { port.close() }
                }
            }
        }
    }
    if (views.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.link_section), style = MaterialTheme.typography.titleSmall)
        for (v in views) {
            val waiting = when {
                v.phase == PairingPhase.MOVED -> Mark.WARN
                v.lowerOffered -> Mark.TODO
                v.verified == Verified.NO && v.phase == PairingPhase.ACTIVE -> Mark.TODO
                else -> Mark.OK
            }
            StatusPreference(stateLine(v), if (v.lowerOffered) stringResource(R.string.link_lower_offered_short) else null, waiting,
                onClick = { a.openPage(Page.Settings(LinkRoutes.pairing(v.id))) })
        }
        views.firstOrNull { it.phase == PairingPhase.ACTIVE }?.let { v -> AskForFile(v, a) }
        if (files.isNotEmpty()) {
            Text(LinkStrings.sentAndReceived().str(), style = MaterialTheme.typography.titleSmall)
            val zone = java.time.ZoneId.systemDefault()
            for (t in files) StatusPreference(t.offer.name, LinkStrings.xferLine("what" to LinkCardText.transfer(t),
                "day" to java.time.Instant.ofEpochSecond(t.at).atZone(zone).toLocalDate()).str(), if (t.state == app.heyrobot.core.link.XferState.FAILED) Mark.WARN else Mark.OK)
        }
    }
}

/** The newest of Sent and received on a person's page. */
private const val SENT_RECEIVED = 20

/** L8: your words to their Robot, sent when you tap Send; what comes back is an offer on a card. */
@Composable
private fun AskForFile(v: PairingView, a: AppActions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var what by remember { mutableStateOf("") }
    PreferenceItem(LinkStrings.askForFile().str(), null, onClick = { open = true })
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        properties = app.heyrobot.ui.common.SecureDialog,
        title = { Text(LinkStrings.askForFile().str()) },
        text = {
            OutlinedTextField(what, { what = it.take(app.heyrobot.core.link.requests.RequestCaps.FILE_ASK) }, label = { Text(LinkStrings.askForFileWhat().str()) })
        },
        confirmButton = {
            TextButton(enabled = what.isNotBlank(), onClick = {
                open = false
                val text = what.trim()
                what = ""
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        val port = LinkPorts.make(ctx)
                        try { port.askFile(v.id, text) } finally { port.close() }
                    }
                    a.say(ctx.getString(if (ok) R.string.link_requests_done else R.string.link_requests_not_done))
                }
            }) { Text(stringResource(R.string.link_requests_send)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(UiStrings.cancel().str()) } },
    )
}

internal const val POLL_MS = 3_000L
/** A search field over People from this many people. */
private const val SEARCH_FROM = 8
/** Rows shown at once; search for the rest. */
private const val MAX_SHOWN = 50
