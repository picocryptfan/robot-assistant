// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.Person
import app.heyrobot.core.link.BlockedView
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.LinkText
import app.heyrobot.core.link.PairHello
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.sys.LinkStatus
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.CheckboxPreference
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. 1.5, Settings › Robot to Robot (robot-to-robot-plan.md §6.1; job L5): Off until you turn it on; turning it on
// asks the name other Robots see, writes link_settings.json and has :link make the keys (SET_UP). Then the people you're
// paired with (each opens its Robot link), Pair with someone, codes still open, the Robots you blocked with Unblock, the
// lines that aren't switches, and Start over. L6 adds Requests, "Answer while my phone is locked" (Q3, which :link's desk
// reads) and "Show in morning brief". L7 adds the working hours for plans on their own (D18, which :link's plans read) and
// the calendars other Robots see as busy (§4.1, link_settings.json's busyFrom). L-bridges adds Tor bridges (its own page,
// BridgesScreen). The other rows of §6.1 (how other Robots reach
// yours while you aren't using it, payments, the connection log) come with the jobs that make :link read them: a switch
// nothing reads would only mislead.

/** What the page read: link_settings.json, :link's status, the pairings, the blocked Robots, your People; L7: your calendars. */
private class LinkPage(val settings: LinkSettings, val status: LinkStatus?, val pairings: List<PairingView>, val blocked: List<BlockedView>, val people: List<Person>,
    val calendars: List<app.heyrobot.core.CalendarInfo> = emptyList(), val holdsState: Boolean = false, val push: app.heyrobot.core.link.PushApps? = null)

/** L7: the working hours' choices for plans on their own (D18; 9:00 to 21:00 by default). */
private val HOURS_FROM = (6..12).map { java.time.LocalTime.of(it, 0) }
private val HOURS_TO = (17..23).map { java.time.LocalTime.of(it, 0) }

@Composable
fun LinkSettingsScreen(port: LinkPort, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<LinkPage?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var askName by rememberSaveable { mutableStateOf(false) }
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    var pickPush by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        var first = true
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { if (!first) tick++; first = false }
    }
    LaunchedEffect(tick) {
        page = withContext(Dispatchers.IO) {
            val s = port.settings()
            // Off: :link isn't bound at all (nothing of it runs until you turn it on).
            if (!s.on) LinkPage(s, null, emptyList(), emptyList(), emptyList(), holdsState = port.holdsState())
            else {
                val st = port.status()
                val ready = st?.vault == LinkIpcProtocol.VAULT_READY
                LinkPage(s, st, if (ready) port.pairings().orEmpty() else emptyList(), if (ready) port.blocked().orEmpty() else emptyList(), port.people(),
                    if (ready) port.calendars() else emptyList(), push = if (ready) port.pushApps() else null)
            }
        }
    }

    fun work(block: () -> Boolean, done: Int?, failed: Int = R.string.link_not_saved) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { block() }
            busy = false
            if (ok) done?.let { a.say(ctx.getString(it)) } else a.say(ctx.getString(failed))
            tick++
        }
    }

    fun turnOn(name: String) = work({
        port.saveSettings { it.copy(on = true, name = name.trim()) } && (port.setUp() || port.status()?.vault.let { it == LinkIpcProtocol.VAULT_GONE || it == LinkIpcProtocol.VAULT_NEWER } // F59: stays on, so the warning shows
            || run { port.saveSettings { s -> s.copy(on = false) }; false })
    }, null, R.string.link_setup_failed)

    val p = page
    // Read here: the list's builder isn't composable.
    val peopleTitle = stringResource(R.string.link_people)
    val plansTitle = stringResource(R.string.link_plans_group)
    val busyTitle = stringResource(R.string.link_busy_from)
    val busyDetail = stringResource(R.string.link_busy_detail)
    val blockedTitle = stringResource(R.string.link_blocked)
    val lockLine = stringResource(R.string.link_line_lock)
    PreferenceScreen(stringResource(R.string.link_title), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.link_intro)) }
        if (p == null) return@PreferenceScreen
        val on = p.settings.on
        item(key = "switch") {
            MainSwitchBar(stringResource(R.string.link_use), on, enabled = !busy) { want ->
                if (want) { if (p.settings.name.isEmpty()) askName = true else turnOn(p.settings.name) }
                else work({ port.saveSettings { it.copy(on = false) } }, null)
            }
        }
        if (!on) {
            item(key = "modes") { IntroPreference(stringResource(R.string.link_modes_explained)) }
            // F59: what's sealed here stays until you say, and saying needs no switch.
            if (p.holdsState) preferenceGroup(null, "reset") {
                PreferenceItem(stringResource(R.string.link_start_over), stringResource(R.string.link_start_over_off_detail), RIcons.Delete, onClick = { confirmStartOver = true })
            }
            return@PreferenceScreen
        }
        val vault = p.status?.vault
        when (vault) {
            LinkIpcProtocol.VAULT_READY -> {}
            LinkIpcProtocol.VAULT_GONE -> preferenceGroup(null, "gone") {
                StatusPreference(stringResource(R.string.link_keys_gone), null, Mark.WARN, onClick = { confirmStartOver = true })
            }
            LinkIpcProtocol.VAULT_NEWER -> preferenceGroup(null, "newer") { StatusPreference(stringResource(R.string.link_newer), null, Mark.WARN) }
            LinkIpcProtocol.VAULT_LOCKED -> preferenceGroup(null, "locked") { StatusPreference(stringResource(R.string.link_locked), null, Mark.WARN) }
            // On, not set up (the keys couldn't be made), or :link not answering.
            else -> preferenceGroup(null, "unavailable") {
                StatusPreference(stringResource(if (vault == LinkIpcProtocol.VAULT_OFF) R.string.link_setup_failed else R.string.link_unavailable), null, Mark.WARN,
                    action = { TextButton(onClick = { if (vault == LinkIpcProtocol.VAULT_OFF) turnOn(p.settings.name) else tick++ }) { Text(stringResource(R.string.link_try_again)) } })
            }
        }
        if (p.status?.network == false) preferenceGroup(null, "network") { StatusPreference(stringResource(R.string.link_line_network), null, Mark.WARN) }
        preferenceGroup(null, "name") {
            TextPreference(stringResource(R.string.link_name), p.settings.name, stringResource(R.string.link_name_unset), stringResource(R.string.link_name_field),
                stringResource(R.string.link_name_help), RIcons.Person, validate = { nameProblem(ctx, it) }) { v -> work({ port.saveSettings { it.copy(name = v.trim()) } }, null) }
        }
        if (vault != LinkIpcProtocol.VAULT_READY) return@PreferenceScreen
        // 1.5 L6: Requests, and the two rows :link now reads (Q3's answer while locked, the morning brief).
        preferenceGroup(null, "requests") {
            PreferenceItem(stringResource(R.string.link_requests), stringResource(R.string.link_requests_detail), RIcons.Robot,
                onClick = { a.openPage(Page.Settings(LinkRoutes.REQUESTS)) })
            PreferenceItem(stringResource(R.string.link_folders), stringResource(R.string.link_folders_detail), RIcons.Folder,
                onClick = { a.openPage(Page.Settings(LinkRoutes.FOLDERS)) })
            PreferenceItem(stringResource(R.string.link_groups), stringResource(R.string.link_groups_detail), RIcons.Robot,
                onClick = { a.openPage(Page.Settings(LinkRoutes.GROUPS)) })
            p.push?.let { push ->
                val chosen = push.apps.firstOrNull { it.pkg == push.chosen }
                val line = when {
                    chosen != null && push.waiting > 0 -> pluralStringResource(R.plurals.link_push_waiting, push.waiting, chosen.label, push.waiting)
                    chosen != null -> stringResource(R.string.link_push_chosen, chosen.label)
                    push.apps.isEmpty() -> stringResource(R.string.link_push_none_installed)
                    else -> stringResource(R.string.link_push_none)
                }
                PreferenceItem(stringResource(R.string.link_push_app), line, RIcons.Robot, onClick = if (push.apps.isEmpty()) null else ({ pickPush = true }))
                if (pickPush) {
                    val none = stringResource(R.string.link_push_none_name)
                    val noneDetail = stringResource(R.string.link_push_none)
                    app.heyrobot.ui.pref.RadioDialog(stringResource(R.string.link_push_choose), chosen, listOf<app.heyrobot.core.link.PushApp?>(null) + push.apps,
                        label = { it?.label ?: none }, detail = { it?.pkg ?: noneDetail }, onDismiss = { pickPush = false }) { app ->
                        pickPush = false
                        work({ port.choosePush(app?.pkg.orEmpty()) }, R.string.link_push_saved)
                    }
                }
            }
            SwitchPreference(stringResource(R.string.link_while_locked),
                stringResource(if (p.settings.whileLocked) R.string.link_while_locked_on else R.string.link_while_locked_off), p.settings.whileLocked, enabled = !busy) { v ->
                work({ port.saveSettings { it.copy(whileLocked = v) } }, null)
            }
            SwitchPreference(stringResource(R.string.link_brief), stringResource(R.string.link_brief_detail), p.settings.brief, enabled = !busy) { v ->
                work({ port.saveSettings { it.copy(brief = v) } }, null)
            }
        }
        val live = p.pairings.filter { it.phase != PairingPhase.ENDED }
        val shown = p.pairings.sortedWith(compareBy({ it.phase == PairingPhase.ENDED }, { it.person == null }, { it.created }))
        preferenceGroup(peopleTitle, "people") {
            PreferenceItem(stringResource(R.string.link_pair), stringResource(R.string.link_pair_detail), RIcons.Add,
                onClick = { a.openPage(Page.Settings(LinkRoutes.PAIR)) })
            for (v in shown) {
                val said = app.heyrobot.core.link.LinkNames.claim(v.claimed)
                val who = personOf(v, p.people)?.name ?: if (said.isNotEmpty()) stringResource(R.string.link_row_says, said) else stringResource(R.string.link_someone)
                val line = if (v.person == null && v.phase == PairingPhase.ACTIVE) stringResource(R.string.link_row_unlinked) else stateLine(v)
                PreferenceItem(who, line, RIcons.Robot, onClick = { a.openPage(Page.Settings(LinkRoutes.pairing(v.id))) })
            }
        }
        item(key = "count") { app.heyrobot.ui.pref.FooterPreference(pluralStringResource(R.plurals.link_people_count, live.size, live.size)) }
        // L12: pairings from a restore wait for this tap: telling them goes over the network, so it is yours to start.
        val moving = p.pairings.filter { it.phase == PairingPhase.MOVING }
        if (moving.isNotEmpty()) item(key = "tell_moved") {
            Attention(stringResource(R.string.link_tell_moved), stringResource(R.string.link_tell_moved_text)) {
                TextButton(onClick = { work({ moving.map { port.sendMoved(it.id) }.all { it } }, R.string.link_tell_moved_done, R.string.link_tell_moved_failed) }) {
                    Text(stringResource(R.string.link_tell_moved))
                }
            }
        }
        val codes = p.status?.codes ?: 0
        if (codes > 0) preferenceGroup(null, "codes") {
            StatusPreference(pluralStringResource(R.plurals.link_codes_open, codes, codes), null, Mark.TODO,
                action = { TextButton(onClick = { work({ port.cancelCodes() }, R.string.link_codes_cancelled) }) { Text(stringResource(R.string.link_cancel_codes)) } })
        }
        if (p.blocked.isNotEmpty()) preferenceGroup(blockedTitle, "blocked") {
            for (b in p.blocked) {
                PreferenceItem(b.label.ifEmpty { stringResource(R.string.link_someone) }, stringResource(R.string.link_blocked_on, dateText(ctx, b.at)), RIcons.Shield,
                    trailing = { TextButton(onClick = { work({ port.unblock(b.id) }, R.string.link_unblocked) }) { Text(stringResource(R.string.link_unblock)) } })
            }
        }
        // 1.5 L7: plans on their own only inside these hours (D18), and the calendars free/busy reads (§4.1).
        preferenceGroup(plansTitle, "plans") {
            val fmt = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
            val h = p.settings.hours
            val lang = app.heyrobot.ui.theme.LocalLangs.current.say
            val clock = { t: java.time.LocalTime -> if (lang == app.heyrobot.core.i18n.Lang.FR) app.heyrobot.core.i18n.Fmt.clock(t, lang) else fmt.format(t) }
            ListPreference(stringResource(R.string.link_hours_from), h.start, HOURS_FROM.filter { it < h.end }, label = { clock(it) }, enabled = !busy) { v ->
                work({ port.saveSettings { it.copy(hours = app.heyrobot.core.link.EventAlone.Hours(v, it.hours.end)) } }, null)
            }
            ListPreference(stringResource(R.string.link_hours_to), h.end, HOURS_TO.filter { it > h.start }, label = { clock(it) }, enabled = !busy) { v ->
                work({ port.saveSettings { it.copy(hours = app.heyrobot.core.link.EventAlone.Hours(it.hours.start, v)) } }, null)
            }
        }
        preferenceGroup(busyTitle, "busy") {
            if (p.calendars.isEmpty()) IntroPreference(stringResource(R.string.link_busy_none))
            for (c in p.calendars) {
                val chosen = p.settings.busyCalendars
                CheckboxPreference(c.name, null, chosen == null || c.id in chosen, { on ->
                    val all = p.calendars.map { it.id }.toSet()
                    val now = (chosen ?: all).let { if (on) it + c.id else it - c.id }
                    work({ port.saveSettings { it.copy(busyCalendars = if (now.containsAll(all)) null else now.take(LinkSettings.MAX_CALENDARS).toSet()) } }, null)
                }, enabled = !busy)
            }
        }
        preferenceFooter(busyDetail, "busy-footer") // l10n: not text (a list key)
        // L-bridges: how Tor gets through a network that blocks it (Off by default).
        preferenceGroup(null, "bridges") {
            PreferenceItem(stringResource(R.string.link_bridges), stringResource(bridgesLabel(p.settings.bridges)), RIcons.Navigate,
                onClick = { a.openPage(Page.Settings(LinkRoutes.BRIDGES)) })
        }
        preferenceGroup(null, "reset") {
            PreferenceItem(stringResource(R.string.link_start_over), stringResource(R.string.link_start_over_detail), RIcons.Delete, onClick = { confirmStartOver = true })
        }
        preferenceFooter(lockLine)
    }
    if (askName) {
        EditDialog(stringResource(R.string.link_name), "", stringResource(R.string.link_name_field), stringResource(R.string.link_name_help),
            validate = { nameProblem(ctx, it) }, onDismiss = { askName = false }, onSave = { askName = false; turnOn(it) })
    }
    if (confirmStartOver) {
        ConfirmDialog(stringResource(R.string.link_start_over_title),
            // F59: only an open node can tell anyone.
            stringResource(if (p?.settings?.on == true && p.status?.vault == LinkIpcProtocol.VAULT_READY) R.string.link_start_over_text else R.string.link_start_over_silent_text),
            stringResource(R.string.link_start_over),
            onDismiss = { confirmStartOver = false }) {
            confirmStartOver = false
            work({ port.startOver().also { if (it) port.saveSettings { s -> s.copy(on = false) } } }, R.string.link_started_over)
        }
    }
}

/** The name other Robots see: one line of 1 to 40 characters that a hello can carry (core PairHello's rule). */
internal fun nameProblem(ctx: android.content.Context, s: String): String? =
    if (LinkText.ok(s.trim(), PairHello.MAX_NAME) && s.trim().toByteArray(Charsets.UTF_8).size <= PairHello.MAX_NAME_BYTES) null else ctx.getString(R.string.link_name_bad)
