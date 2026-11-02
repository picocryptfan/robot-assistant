// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.Person
import app.heyrobot.core.link.FolderRec
import app.heyrobot.core.link.FolderState
import app.heyrobot.core.link.GroupAnswer
import app.heyrobot.core.link.GroupLimits
import app.heyrobot.core.link.GroupMember
import app.heyrobot.core.link.GroupSeenPlan
import app.heyrobot.core.link.GroupState
import app.heyrobot.core.link.GroupStanding
import app.heyrobot.core.link.GroupsView
import app.heyrobot.core.link.LinkGroup
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.LinkNames
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.GrpWho
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.sys.AppLanguage
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.RadioPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. 1.5, Settings › Robot to Robot › Shared folders and Group plans (robot-to-robot-plan.md, job L11). What :link
// keeps, as records crossing with their limits checked again. Never while locked, out of screenshots and Recents. What another
// Robot says stays framed as its word: nothing here reaches the model, People or your calendar.

/** What a page read: null when locked or when :link didn't answer. */
private class TogetherPage<T>(val data: T?, val pairings: List<PairingView>, val people: List<Person>, val locked: Boolean)

private class Together<T>(val page: TogetherPage<T>?, val busy: Boolean, val work: (() -> Boolean, (() -> Unit)?) -> Unit)

/** The load (on each return to the page), and the one job at a time that changes something, saying when it couldn't. */
@Composable
private fun <T> together(port: LinkPort, a: AppActions, read: () -> T?): Together<T> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<TogetherPage<T>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        var first = true
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { if (!first) tick++; first = false }
    }
    LaunchedEffect(tick) {
        page = withContext(Dispatchers.IO) {
            if (port.locked()) TogetherPage(null, emptyList(), emptyList(), true)
            else TogetherPage(read(), port.pairings().orEmpty(), port.people(), false)
        }
    }
    return Together(page, busy) { block, after ->
        if (!busy) {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { block() }
                busy = false
                a.say(ctx.getString(if (ok) R.string.link_requests_done else R.string.link_requests_not_done))
                if (ok && after != null) after() else tick++
            }
        }
    }
}

private fun nameOf(p: TogetherPage<*>, pairing: String): app.heyrobot.core.i18n.Phrase = LinkNames.of(p.pairings.firstOrNull { it.id == pairing }, p.people)

/** F142: under a name only their Robot gave, on a list you pick from: two Robots can claim the same one. */
@Composable
private fun unlinked(p: TogetherPage<*>, v: app.heyrobot.core.link.PairingView): String? =
    if (LinkNames.person(v, p.people) == null) stringResource(R.string.link_row_unlinked) else null

/** F147: a group member's name as LinkGroup.valid takes it, however the shown name came out: NFC, no space at the end of the cut. */
internal fun memberName(shown: String): String = app.heyrobot.core.link.LinkText.clean(shown, RequestCaps.NAME).orEmpty()

/** A plan's day and time, as this phone says them. */
internal fun whenText(ctx: android.content.Context, seconds: Long): String = android.text.format.DateUtils.formatDateTime(ctx, seconds * 1000,
    android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL)

/** The other Robot's word, in the frame every such word has. */
@Composable
private fun Framed(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

// Shared folders ---------------------------------------------------------------------------------------------------------------

/** F71: one sentence for the folder list's row and the folder's own screen: what counts toward the limits, so the two never differ. */
private fun folderUse(ctx: android.content.Context, f: FolderRec, who: String): String =
    ctx.getString(R.string.link_folder_use, who, f.used, LinkLimits.FOLDER_FILES, Formatter.formatShortFileSize(ctx, f.bytes))

@Composable
fun FoldersScreen(port: LinkPort, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val t = together(port, a) { port.folders() }
    val ctx = LocalContext.current
    val l = AppLanguage.langs(ctx)
    val locked = stringResource(R.string.link_requests_unlock)
    val unavailable = stringResource(R.string.link_requests_unavailable)
    val intro = stringResource(R.string.link_folders_intro)
    val none = stringResource(R.string.link_folders_none)
    PreferenceScreen(stringResource(R.string.link_folders), onBack, snackbar = snackbar) {
        val p = t.page ?: return@PreferenceScreen
        if (p.locked) { item(key = "locked") { IntroPreference(locked) }; return@PreferenceScreen }
        val all = p.data
        if (all == null) { item(key = "unavailable") { IntroPreference(unavailable) }; return@PreferenceScreen }
        item(key = "intro") { IntroPreference(intro) }
        preferenceGroup(null, "new") {
            PreferenceItem(stringResource(R.string.link_folders_new), stringResource(R.string.link_folders_new_detail), RIcons.Folder,
                onClick = { a.openPage(Page.Settings(LinkRoutes.FOLDER_NEW)) })
        }
        if (all.isEmpty()) item(key = "none") { IntroPreference(none) }
        else preferenceGroup(null, "folders") {
            for (f in all.sortedBy { it.at }) {
                val who = nameOf(p, f.pairing).render(l)
                PreferenceItem(f.name, if (f.state == FolderState.INVITED) stringResource(R.string.link_folders_waiting, who)
                    else folderUse(ctx, f, who), RIcons.Folder,
                    onClick = { a.openPage(Page.Settings(LinkRoutes.folder(f.pairing, f.fold))) })
            }
        }
    }
}

/** A new shared folder: its name and one Robot, asked first. */
@Composable
fun FolderNewScreen(port: LinkPort, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val t = together(port, a) { true }
    val l = AppLanguage.langs(LocalContext.current)
    var name by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    val locked = stringResource(R.string.link_requests_unlock)
    val footer = stringResource(R.string.link_folders_new_footer)
    val who = stringResource(R.string.link_folders_who)
    val nameLabel = stringResource(R.string.link_folders_name)
    val send = stringResource(R.string.link_folders_invite)
    val noRobots = stringResource(R.string.link_together_no_robots)
    PreferenceScreen(stringResource(R.string.link_folders_new), onBack, snackbar = snackbar) {
        val p = t.page ?: return@PreferenceScreen
        if (p.locked) { item(key = "locked") { IntroPreference(locked) }; return@PreferenceScreen }
        val robots = p.pairings.filter { it.phase == PairingPhase.ACTIVE }
        if (robots.isEmpty()) { item(key = "none") { IntroPreference(noRobots) }; return@PreferenceScreen }
        item(key = "name") {
            OutlinedTextField(name, { name = it.take(RequestCaps.NAME) }, Modifier.fillMaxWidth().padding(16.dp, 8.dp), label = { Text(nameLabel) }, singleLine = true)
        }
        preferenceGroup(who, "who") {
            for (v in robots) RadioPreference(nameOf(p, v.id).render(l), unlinked(p, v), to == v.id) { to = v.id }
        }
        preferenceGroup(null, "send") {
            val pick = to
            PreferenceItem(send, null, RIcons.Share, enabled = !t.busy && pick != null && name.isNotBlank(),
                onClick = { if (pick != null) t.work({ port.folderInvite(pick, name.trim()) != null }, onBack) })
        }
        preferenceFooter(footer)
    }
}

@Composable
fun FolderScreen(port: LinkPort, pairing: String, fold: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val t = together(port, a) { port.folders() }
    val ctx = LocalContext.current
    val l = AppLanguage.langs(ctx)
    var leaving by rememberSaveable { mutableStateOf(false) }
    val p = t.page
    val f: FolderRec? = p?.data?.firstOrNull { it.pairing == pairing && it.fold == fold }
    val locked = stringResource(R.string.link_requests_unlock)
    val footer = stringResource(R.string.link_folder_footer)
    val empty = stringResource(R.string.link_folder_empty)
    val title = stringResource(R.string.link_folders)
    PreferenceScreen(f?.name ?: title, onBack, snackbar = snackbar) {
        if (p == null) return@PreferenceScreen
        if (p.locked) { item(key = "locked") { IntroPreference(locked) }; return@PreferenceScreen }
        if (f == null) { item(key = "gone") { IntroPreference(empty) }; return@PreferenceScreen }
        val who = nameOf(p, pairing).render(l)
        item(key = "with") {
            IntroPreference(if (f.state == FolderState.INVITED) stringResource(R.string.link_folders_waiting, who) else folderUse(ctx, f, who))
        }
        if (f.live.isEmpty()) item(key = "empty") { IntroPreference(empty) }
        else preferenceGroup(null, "items") {
            for (x in f.live.sortedByDescending { it.at }) {
                val size = Formatter.formatShortFileSize(ctx, x.size)
                PreferenceItem(x.name, if (x.mine) stringResource(R.string.link_folder_yours, size) else stringResource(R.string.link_folder_theirs, who, size), RIcons.Folder,
                    trailing = if (!x.mine) null else ({
                        TextButton(onClick = { t.work({ port.folderRemove(pairing, fold, x.item) }, null) }, enabled = !t.busy) { Text(stringResource(R.string.link_folder_take_out)) }
                    }))
            }
        }
        preferenceGroup(null, "leave") {
            PreferenceItem(stringResource(R.string.link_folder_leave), null, RIcons.Delete, enabled = !t.busy, onClick = { leaving = true })
        }
        preferenceFooter(footer)
    }
    if (leaving) ConfirmDialog(stringResource(R.string.link_folder_leave_title), stringResource(R.string.link_folder_leave_text), stringResource(R.string.link_folder_leave),
        onDismiss = { leaving = false }) {
        leaving = false
        t.work({ port.folderLeave(pairing, fold) }, onBack)
    }
}

// Group plans -------------------------------------------------------------------------------------------------------------------

@Composable
fun GroupsScreen(port: LinkPort, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val t = together(port, a) { port.groups() }
    val ctx = LocalContext.current
    val l = AppLanguage.langs(ctx)
    val locked = stringResource(R.string.link_requests_unlock)
    val unavailable = stringResource(R.string.link_requests_unavailable)
    val intro = stringResource(R.string.link_groups_intro)
    val yoursTitle = stringResource(R.string.link_groups_yours)
    val seenTitle = stringResource(R.string.link_groups_seen)
    PreferenceScreen(stringResource(R.string.link_groups), onBack, snackbar = snackbar) {
        val p = t.page ?: return@PreferenceScreen
        if (p.locked) { item(key = "locked") { IntroPreference(locked) }; return@PreferenceScreen }
        val v: GroupsView? = p.data
        if (v == null) { item(key = "unavailable") { IntroPreference(unavailable) }; return@PreferenceScreen }
        item(key = "intro") { IntroPreference(intro) }
        preferenceGroup(null, "new") {
            PreferenceItem(stringResource(R.string.link_groups_new), null, RIcons.Robot, onClick = { a.openPage(Page.Settings(LinkRoutes.GROUP_NEW)) })
        }
        if (v.mine.isNotEmpty()) preferenceGroup(yoursTitle, "yours") {
            for ((g, s) in v.mine.sortedByDescending { it.first.at }) {
                val detail = when (g.state) {
                    GroupState.ASKING -> stringResource(R.string.link_groups_asking)
                    GroupState.PROPOSED -> stringResource(R.string.link_groups_proposed, whenText(ctx, g.time), s.who.count { it == GrpWho.IN }, g.members.size)
                    GroupState.CANCELLED -> stringResource(R.string.link_groups_cancelled)
                }
                PreferenceItem(g.title, detail, RIcons.Robot, onClick = { a.openPage(Page.Settings(LinkRoutes.group(g.id))) })
            }
        }
        if (v.seen.isNotEmpty()) preferenceGroup(seenTitle, "seen") { for (s in v.seen) SeenPlan(s, nameOf(p, s.seen.pairing).render(l)) }
    }
}

/** A plan someone else organizes: what you agreed with them, then their word on who's in, which Robot can't check. */
@Composable
private fun SeenPlan(s: GroupSeenPlan, organizer: String) {
    val ctx = LocalContext.current
    PreferenceItem(s.title, whenText(ctx, s.time), RIcons.Robot)
    Framed {
        Text(stringResource(R.string.link_groups_word, organizer), style = MaterialTheme.typography.labelMedium)
        for (w in s.seen.named) Text(stringResource(R.string.link_groups_person, w.name, stringResource(whoText(w.who))), style = MaterialTheme.typography.bodyMedium)
        if (s.seen.outCount > 0) Text(stringResource(R.string.link_groups_person, stringResource(R.string.link_groups_out), s.seen.outCount.toString()), style = MaterialTheme.typography.bodyMedium)
        if (s.seen.waitingCount > 0) Text(stringResource(R.string.link_groups_person, stringResource(R.string.link_groups_no_answer), s.seen.waitingCount.toString()), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.link_groups_word_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun whoText(w: GrpWho) = when (w) {
    GrpWho.IN -> R.string.link_groups_in
    GrpWho.OUT -> R.string.link_groups_out
    GrpWho.WAITING -> R.string.link_groups_no_answer
}

/** A new group plan: what, how long, within how many days, and who. */
@Composable
fun GroupNewScreen(port: LinkPort, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val t = together(port, a) { true }
    val l = AppLanguage.langs(LocalContext.current)
    var title by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf(60L) }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    val locked = stringResource(R.string.link_requests_unlock)
    val noRobots = stringResource(R.string.link_together_no_robots)
    val whatLabel = stringResource(R.string.link_groups_what)
    val placeLabel = stringResource(R.string.link_groups_place)
    val lengthTitle = stringResource(R.string.link_groups_length)
    val withinTitle = stringResource(R.string.link_groups_within)
    val whoTitle = stringResource(R.string.link_groups_who, GroupLimits.OTHERS)
    val footer = stringResource(R.string.link_groups_new_footer)
    val ask = stringResource(R.string.link_groups_ask)
    PreferenceScreen(stringResource(R.string.link_groups_new), onBack, snackbar = snackbar) {
        val p = t.page ?: return@PreferenceScreen
        if (p.locked) { item(key = "locked") { IntroPreference(locked) }; return@PreferenceScreen }
        val robots = p.pairings.filter { it.phase == PairingPhase.ACTIVE }
        if (robots.isEmpty()) { item(key = "none") { IntroPreference(noRobots) }; return@PreferenceScreen }
        item(key = "fields") {
            Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                OutlinedTextField(title, { title = it.take(RequestCaps.TITLE) }, Modifier.fillMaxWidth(), label = { Text(whatLabel) }, singleLine = true)
                OutlinedTextField(place, { place = it.take(RequestCaps.PLACE) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(placeLabel) }, singleLine = true)
            }
        }
        preferenceGroup(lengthTitle, "length") {
            for (m in LENGTHS) RadioPreference(stringResource(R.string.link_groups_minutes, m), null, minutes == m) { minutes = m }
        }
        preferenceGroup(withinTitle, "within") {
            for (d in WITHIN) RadioPreference(stringResource(R.string.link_groups_days, d), null, days == d) { days = d }
        }
        preferenceGroup(whoTitle, "who") {
            for (v in robots) {
                val on = v.id in chosen
                SwitchPreference(nameOf(p, v.id).render(l), unlinked(p, v), on, enabled = on || chosen.size < GroupLimits.OTHERS) { yes -> chosen = if (yes) chosen + v.id else chosen - v.id }
            }
        }
        preferenceGroup(null, "ask") {
            PreferenceItem(ask, null, RIcons.Share, enabled = !t.busy && title.isNotBlank() && chosen.isNotEmpty(), onClick = {
                val members = chosen.map { id -> GroupMember(id, memberName(nameOf(p, id).render(l))) }
                t.work({ port.groupStart(newGroup(title.trim(), place.trim().ifEmpty { null }, minutes, days, members)) != null }, onBack)
            })
        }
        preferenceFooter(footer)
    }
}

private val LENGTHS = listOf(30L, 60L, 90L, 120L) // l10n: not text (minutes)
private val WITHIN = listOf(3, 7, 14) // l10n: not text (days, up to FbAsk's 14)

/** The window starts at the next whole hour. */
internal fun newGroup(title: String, place: String?, minutes: Long, days: Int, members: List<GroupMember>, now: Long = System.currentTimeMillis() / 1000): LinkGroup {
    val from = (now / 3600 + 1) * 3600
    val to = (from + days * 86_400L).coerceAtMost(from + RequestCaps.FB_WINDOW_SECONDS)
    check(FbAsk.windowOk(from, to))
    return LinkGroup(LinkIds.newId(), GroupState.ASKING, title, place, null, minutes, from, to, now, 0, members)
}

/** Your group plan: where each person stands, the times that suit, and Cancel. */
@Composable
fun GroupScreen(port: LinkPort, id: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val t = together(port, a) { port.groups() }
    val ctx = LocalContext.current
    var cancelling by rememberSaveable { mutableStateOf(false) }
    var pick by rememberSaveable { mutableStateOf<Long?>(null) }
    val p = t.page
    val found: Pair<LinkGroup, GroupStanding>? = p?.data?.mine?.firstOrNull { it.first.id == id }
    val locked = stringResource(R.string.link_requests_unlock)
    val titleAll = stringResource(R.string.link_groups)
    val whoTitle = stringResource(R.string.link_groups_people)
    val timesTitle = stringResource(R.string.link_groups_times)
    val noTimes = stringResource(R.string.link_groups_no_times)
    val timesFooter = stringResource(R.string.link_groups_times_footer)
    val timesFooterNoCalendar = stringResource(R.string.link_groups_times_footer_no_calendar)
    val gone = stringResource(R.string.link_requests_unavailable)
    PreferenceScreen(found?.first?.title ?: titleAll, onBack, snackbar = snackbar) {
        if (p == null) return@PreferenceScreen
        if (p.locked) { item(key = "locked") { IntroPreference(locked) }; return@PreferenceScreen }
        if (found == null) { item(key = "gone") { IntroPreference(gone) }; return@PreferenceScreen }
        val (g, s) = found
        if (g.state == GroupState.PROPOSED) item(key = "when") { IntroPreference(whenText(ctx, g.time)) }
        preferenceGroup(whoTitle, "people") {
            g.members.forEachIndexed { i, m ->
                val detail = when {
                    g.state == GroupState.ASKING && m.answer == GroupAnswer.KNOWN -> stringResource(R.string.link_groups_answered)
                    g.state == GroupState.ASKING && m.answer == GroupAnswer.WONT_SAY -> stringResource(R.string.link_groups_wont_say)
                    g.state == GroupState.ASKING -> stringResource(R.string.link_groups_no_answer)
                    else -> stringResource(whoText(s.who.getOrElse(i) { GrpWho.WAITING }))
                }
                PreferenceItem(m.name, detail, RIcons.Person)
            }
        }
        if (g.state == GroupState.ASKING) {
            if (s.times.isEmpty()) item(key = "no-times") { IntroPreference(noTimes) }
            else preferenceGroup(timesTitle, "times") {
                for (time in s.times) PreferenceItem(whenText(ctx, time), null, RIcons.Robot, enabled = !t.busy, onClick = { pick = time })
            }
            preferenceFooter(if (s.calendar) timesFooter else timesFooterNoCalendar)
        }
        if (g.state != GroupState.CANCELLED) preferenceGroup(null, "cancel") {
            PreferenceItem(stringResource(R.string.link_groups_cancel), null, RIcons.Delete, enabled = !t.busy, onClick = { cancelling = true })
        }
    }
    pick?.let { time ->
        ConfirmDialog(stringResource(R.string.link_groups_propose_title), stringResource(R.string.link_groups_propose_text, whenText(ctx, time)), stringResource(R.string.link_groups_propose),
            onDismiss = { pick = null }) {
            pick = null
            t.work({ port.groupPropose(id, time) }, null)
        }
    }
    if (cancelling) ConfirmDialog(stringResource(R.string.link_groups_cancel_title), stringResource(R.string.link_groups_cancel_text), stringResource(R.string.link_groups_cancel),
        onDismiss = { cancelling = false }) {
        cancelling = false
        t.work({ port.groupCancel(id) }, null)
    }
}
