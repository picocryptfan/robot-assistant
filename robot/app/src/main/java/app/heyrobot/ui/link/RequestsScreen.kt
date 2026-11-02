// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import androidx.compose.material3.OutlinedTextField
import app.heyrobot.core.link.LinkIpcProtocol
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.Person
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.link.DeskCard
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskLine
import app.heyrobot.core.link.DeskRules
import app.heyrobot.core.link.DeskView
import app.heyrobot.core.link.DeskWay
import app.heyrobot.core.link.LinkCardText
import app.heyrobot.core.link.LinkNames
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.sys.AppLanguage
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.FooterPreference
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

// MAIN PROCESS. 1.5, Robot to Robot › Requests (robot-to-robot-plan.md §4.2, §4.4, §6.4, §6.5; job L6): one screen, three
// lists. Waiting for you: each card, its summary drawn by code and the other person's words in a framed box, Decline and
// its Yes (what that request's handler does; a card no job answers yet has Decline only). Robot suggests: "Robot proposes
// the switch", Turn on (a tap, the level set through :link, which refuses it while locked) or Not now. Handled by Robot,
// for 7 days, with Undo where it can be undone. Sent: what you asked other Robots and their last answer. Opened from a
// notification, the panel's Open Requests, and Settings › Robot to Robot. Never while locked, and out of screenshots and
// Recents (SecureWindow). Nothing here reads a request's JSON: the desk crosses as core DeskView, checked again.

/** What the page read: the desk, the pairings and your People, or why there's nothing (locked, :link not answering). */
private class RequestsPage(val desk: DeskView?, val pairings: List<PairingView>, val people: List<Person>, val settings: LinkSettings, val locked: Boolean)

@Composable
fun RequestsScreen(port: LinkPort, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<RequestsPage?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        var first = true
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { if (!first) tick++; first = false }
    }
    LaunchedEffect(tick) {
        page = withContext(Dispatchers.IO) {
            val s = port.settings()
            if (port.locked()) RequestsPage(null, emptyList(), emptyList(), s, true)
            else RequestsPage(if (s.on) port.desk() else DeskView.EMPTY, port.pairings().orEmpty(), port.people(), s, false)
        }
    }
    fun work(block: () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { block() }
            busy = false
            a.say(ctx.getString(if (ok) R.string.link_requests_done else R.string.link_requests_not_done))
            tick++
        }
    }

    val l: Langs = AppLanguage.langs(ctx)
    val zone = ZoneId.systemDefault()
    val p = page
    val titles = listOf(stringResource(R.string.link_requests_suggests), stringResource(R.string.link_requests_waiting),
        stringResource(R.string.link_requests_handled), stringResource(R.string.link_requests_sent))
    val unlock = stringResource(R.string.link_requests_unlock)
    val unavailable = stringResource(R.string.link_requests_unavailable)
    val empty = stringResource(R.string.link_requests_empty)
    val window = stringResource(R.string.link_requests_undo_window)
    PreferenceScreen(stringResource(R.string.link_requests), onBack, snackbar = snackbar) {
        if (p == null) return@PreferenceScreen
        if (p.locked) { item(key = "locked") { IntroPreference(unlock) }; return@PreferenceScreen }
        val d = p.desk
        if (d == null) { item(key = "unavailable") { IntroPreference(unavailable) }; return@PreferenceScreen }
        fun name(pairing: String): Phrase = LinkNames.of(p.pairings.firstOrNull { it.id == pairing }, p.people)
        val now = System.currentTimeMillis() / 1000
        val handled = d.lines.filter { it.entry.way == DeskWay.IN }
        val sent = d.lines.filter { it.entry.way == DeskWay.OUT }
        // L7: their Robots' answers to what you sent (and changes to your plans), under Sent.
        val replies = d.lines.filter { it.entry.way == DeskWay.REPLY && it.request != null }
        if (d.cards.isEmpty() && d.proposals.isEmpty() && handled.isEmpty() && sent.isEmpty() && replies.isEmpty()) { item(key = "empty") { IntroPreference(empty) }; return@PreferenceScreen }
        if (d.proposals.isNotEmpty()) preferenceGroup(titles[0], "suggests") {
            for (pr in d.proposals) {
                val text = LinkCardText.proposal(pr.capability, name(pr.pairing), p.settings.hours) ?: continue
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(text.render(l), style = MaterialTheme.typography.bodyLarge)
                    Buttons(LinkStrings.notNow().render(l), LinkStrings.turnOn().render(l), busy,
                        no = { work { port.notNow(pr.pairing, pr.capability) } },
                        // Loosening a level: your tap, and :link refuses it while locked.
                        yes = { work { port.setLevel(pr.pairing, pr.capability, app.heyrobot.core.link.LinkLevel.AUTO) } })
                }
            }
        }
        if (d.cards.isNotEmpty()) preferenceGroup(titles[1], "waiting") {
            for (c in d.cards) CardRow(c, name(c.pairing), l, zone, busy, decline = { work { port.answer(c.id, DeskChoice.DECLINE) } },
                // L8: Send a file: you pick it in Robot Files, and the card is answered when its offer goes.
                yes = if (c.yes == null) null else if (c.yes == app.heyrobot.core.link.DeskYes.SEND_FILE) ({
                    app.heyrobot.ui.files.FilesRequests.robotAsk.value = app.heyrobot.sys.RobotAsk(c.id, c.pairing, c.request.id) to name(c.pairing)
                    a.openTab(app.heyrobot.ui.Tab.FILES)
                }) else ({ work { port.answer(c.id, DeskChoice.YES) } }),
                // L10: a question waits for your words (never the model's, D17).
                reply = if (c.type != RequestType.Q_ASK) null else ({ text -> work { port.reply(c.id, text) } }),
                // L8: or with a file, picked in Robot Files.
                file = if (c.type != RequestType.Q_ASK) null else ({
                    app.heyrobot.ui.files.FilesRequests.robotAsk.value = app.heyrobot.sys.RobotAsk(c.id, c.pairing, c.request.id, question = true) to name(c.pairing)
                    a.openTab(app.heyrobot.ui.Tab.FILES)
                }),
                // L7: a plan with 2 or 3 times: one button per time (Yes alone takes the first you're free at).
                times = planTimes(c, l, zone).map { (label, choice) -> label to { work { port.answer(c.id, choice) } } })
        }
        for ((pairing, n) in d.more) item(key = "more-$pairing") { FooterPreference(LinkStrings.more(n, "name" to name(pairing)).render(l)) } // l10n: not text (a list key)
        if (handled.isNotEmpty()) preferenceGroup(titles[2], "handled") {
            for (line in handled) HandledRow(line, name(line.entry.pairing), l, zone, DeskRules.undoable(line.entry, now), busy) { work { port.undo(line.entry.id) } }
        }
        if (sent.isNotEmpty() || replies.isNotEmpty()) preferenceGroup(titles[3], "sent") {
            for (line in replies) {
                val text = LinkCardText.reply(line.request!!, name(line.entry.pairing), zone) ?: continue
                PreferenceItem(text.render(l), dateText(ctx, line.entry.done), RIcons.Robot)
            }
            for (line in sent) {
                val who = name(line.entry.pairing)
                val what = RequestType.ofCode(line.entry.type)?.capability?.let { capabilityName(it) } ?: ""
                PreferenceItem(stringResource(R.string.link_requests_to, what, who.render(l)),
                    stringResource(R.string.link_requests_line, LinkCardText.state(line.entry.state, who).render(l), dateText(ctx, line.entry.done)), RIcons.Robot)
            }
        }
        preferenceFooter(window)
    }
}

/** L7: a plan's times as buttons (2 or 3 times; one time needs none), each with the choice that takes it. */
private fun planTimes(c: DeskCard, l: Langs, zone: ZoneId): List<Pair<String, DeskChoice>> {
    val times = when (val b = c.request.body) {
        is app.heyrobot.core.link.requests.EvPropose -> b.event.times
        is app.heyrobot.core.link.requests.EvChange -> b.event.times
        else -> return emptyList()
    }
    if (times.size < 2 || c.yes == null) return emptyList()
    val choices = listOf(DeskChoice.FIRST, DeskChoice.SECOND, DeskChoice.THIRD)
    val now = System.currentTimeMillis() / 1000
    return times.take(choices.size).mapIndexedNotNull { i, t -> // A time that has passed gets no button.
        if (t <= now) null else java.time.Instant.ofEpochSecond(t).atZone(zone).let { z -> LinkStrings.time("day" to z.toLocalDate(), "time" to z.toLocalTime()).render(l) } to choices[i]
    }
}

/** A card: its summary, the other person's words framed, Decline and its Yes (L7: or one button per time of a plan). */
@Composable
private fun CardRow(c: DeskCard, name: Phrase, l: Langs, zone: ZoneId, busy: Boolean, decline: () -> Unit, yes: (() -> Unit)?, times: List<Pair<String, () -> Unit>> = emptyList(),
                    reply: ((String) -> Unit)? = null, file: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(LinkCardText.summary(c.request, name, zone).render(l), style = MaterialTheme.typography.bodyLarge)
        val words = LinkCardText.theirWords(c.request)
        if (words.isNotEmpty()) {
            Text(LinkStrings.theirWords("name" to name).render(l), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(words.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        } else if (yes == null && reply == null) {
            Text(LinkStrings.onlyDecline().render(l), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (reply != null) {
            var text by remember(c.id) { mutableStateOf("") }
            OutlinedTextField(text, { text = it.take(LinkIpcProtocol.MAX_ANSWER) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.link_requests_answer)) }, enabled = !busy)
            Buttons(LinkStrings.decline().render(l), stringResource(R.string.link_requests_send), busy, no = decline,
                yes = if (text.isBlank()) null else ({ reply(text) }))
            if (file != null) TextButton(onClick = file, enabled = !busy) { Text(LinkStrings.sendFile().render(l)) }
        } else if (times.isEmpty()) Buttons(LinkStrings.decline().render(l), LinkCardText.yes(c.yes)?.render(l), busy, no = decline, yes = yes)
        else {
            for ((label, pick) in times) Button(onClick = pick, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Text(label) }
            Buttons(LinkStrings.decline().render(l), null, busy, no = decline, yes = null)
        }
    }
}

/** What Robot did on its own (or you answered), when, and Undo while it can be undone. */
@Composable
private fun HandledRow(line: DeskLine, name: Phrase, l: Langs, zone: ZoneId, undoable: Boolean, busy: Boolean, undo: () -> Unit) {
    val ctx = LocalContext.current
    val title = line.request?.let { LinkCardText.summary(it, name, zone).render(l) } ?: LinkStrings.askOther("name" to name).render(l)
    PreferenceItem(title, stringResource(R.string.link_requests_line, LinkCardText.state(line.entry.state, name).render(l), dateText(ctx, line.entry.done)), RIcons.Robot,
        trailing = if (!undoable) null else ({ TextButton(onClick = undo, enabled = !busy) { Text(LinkStrings.undo().render(l)) } }))
}

@Composable
private fun Buttons(noLabel: String, yesLabel: String?, busy: Boolean, no: () -> Unit, yes: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = no, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(noLabel) }
        if (yesLabel != null && yes != null) Button(onClick = yes, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(yesLabel) }
    }
}
