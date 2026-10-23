// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.ui.common.RIcons
import app.heyrobot.core.Person
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.AfterReading
import app.heyrobot.core.messages.KeepMessages
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessagePeople
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.MessagesLocked
import app.heyrobot.core.messages.ReplyRoute
import app.heyrobot.people.MessageAppSigners
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.NotificationAccess
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PrefSwitch
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import app.heyrobot.core.i18n.MessageStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings

// MAIN PROCESS. Settings › Messages (0.7): "Read my messages" and each app (all off), group chats, how
// replies go and whether a card comes first, what happens after Robot reads, translations, the lock screen, and the
// switches for later. Kept in messages.json (MessagesStore), not settings.json. Turning it on says once what Robot will
// do and what stays on the phone, pre-ticks your texting app (Cheogram), then the notification access steps People has.

/**
 * The page; [file] and [onEdit] are MessagesStore's (a test passes its own): each edit is a change to the file as it is
 * when written (MessagesStore.update), so the bridge's "Always for …" and People's switch are never lost. [installed]
 * says which apps are on the phone; [pinsNow] what Android says of an app's packages now, kept when you turn it on;
 * [problems] the lines for apps whose signing key changed or that were installed after you turned them on.
 */
@Composable
fun MessagesSettingsScreen(
    file: MessagesFile,
    facts: AssistantFacts?,
    onEdit: ((MessagesFile) -> MessagesFile) -> Unit,
    a: AppActions,
    onBack: () -> Unit,
    installed: (MessageApp) -> Boolean = rememberInstalled(),
    pinsNow: (MessageApp) -> Map<String, AppPin> = rememberPinsNow(),
    problems: (MessagesFile) -> List<String> = rememberProblems(),
) {
    val ctx = LocalContext.current
    val s = file.settings
    var setup by rememberSaveable { mutableStateOf(false) }
    fun set(change: (MessageSettings) -> MessageSettings) = onEdit { it.copy(settings = change(it.settings)) }
    fun turn(app: MessageApp, on: Boolean) = onEdit { it.withApp(app, on, if (on) pinsNow(app) else emptyMap()) }
    val granted = facts?.notificationAccess == true
    val untrusted = remember(file) { if (s.on) problems(file) else emptyList() }
    val langs = LocalLangs.current
    val routeDetails = ReplyRoute.entries.associateWith { routeDetail(it) }
    val keepDetails = KeepMessages.entries.associateWith { keepDetail(it) }
    val afterDetails = AfterReading.entries.associateWith { afterDetail(it) }
    val lockedDetails = MessagesLocked.entries.associateWith { lockedDetail(it) }
    val footer = stringResource(R.string.messages_footer)
    val apps = stringResource(R.string.messages_apps)
    val replies = stringResource(R.string.messages_replies)
    val reading = stringResource(R.string.messages_reading)
    val announcements = stringResource(R.string.messages_announcements)
    PreferenceScreen(UiStrings.messages().str(), onBack) {
        item(key = "intro") {
            IntroPreference(stringResource(R.string.messages_intro))
        }
        item(key = "main") {
            MainSwitchBar(stringResource(R.string.messages_main), s.on) { on ->
                if (on && s.allowlist.apps.isEmpty()) setup = true
                else {
                    set { it.copy(on = on) }
                    if (on) NotificationAccess.rebind(ctx)
                }
            }
        }
        // Off with access still allowed (name learning may use it): how to take it back.
        if (!s.on && granted) {
            preferenceGroup(null, "still") {
                StatusPreference(stringResource(R.string.messages_still_allowed), stringResource(R.string.messages_still_allowed_detail), Mark.TODO,
                    action = { TextButton(onClick = { app.heyrobot.ui.openFirst(ctx, NotificationAccess.settings(ctx), a) }) { Text(stringResource(R.string.messages_open)) } })
            }
        }
        if (s.on) {
            preferenceGroup(null, "access") {
                if (!granted) StatusPreference(stringResource(R.string.messages_access), stringResource(R.string.messages_access_needed), Mark.TODO,
                    onClick = { a.openPage(Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)) })
                else StatusPreference(stringResource(R.string.messages_access_allowed), stringResource(R.string.messages_access_allowed_detail), Mark.OK)
                PreferenceItem(stringResource(R.string.messages_quiet), stringResource(R.string.messages_quiet_detail),
                    onClick = { app.heyrobot.ui.openFirst(ctx, NotificationAccess.settings(ctx), a) })
            }
        }
        preferenceGroup(apps, "apps") {
            // An app Robot no longer reads: its signing key changed, or this build came after you turned it on.
            for (line in untrusted) StatusPreference(line, stringResource(R.string.messages_untrusted_detail), Mark.WARN)
            for (app in MessageApp.entries) {
                val on = app in s.allowlist.apps
                val here = installed(app)
                SwitchPreference(app.titleText.str(), appSummary(app, here), on, enabled = s.on) { turn(app, it) }
            }
            SwitchPreference(stringResource(R.string.messages_groups), stringResource(R.string.messages_groups_detail), s.allowlist.groups, enabled = s.on) { g ->
                set { it.copy(allowlist = it.allowlist.copy(groups = g)) }
            }
        }
        preferenceGroup(replies, "replies") {
            ListPreference(stringResource(R.string.messages_how_replies), s.replyRoute, ReplyRoute.entries, { it.text.render(langs) },
                detail = { routeDetails[it] }, enabled = s.on) { r ->
                set { it.copy(replyRoute = r) }
            }
            SwitchPreference(stringResource(R.string.messages_card), stringResource(if (s.replyCard) R.string.messages_card_on else R.string.messages_card_off),
                s.replyCard, enabled = s.on && s.replyRoute == ReplyRoute.SEND_AFTER_CARD) { c ->
                set { it.copy(replyCard = c) }
            }
            SwitchPreference(stringResource(R.string.messages_voice_yes), stringResource(R.string.messages_voice_yes_detail),
                s.voiceYesSends, enabled = s.on && s.replyRoute == ReplyRoute.SEND_AFTER_CARD) { v -> set { it.copy(voiceYesSends = v) } }
            SwitchPreference(stringResource(R.string.messages_their_language), stringResource(R.string.messages_their_language_detail), s.offerTheirLanguage,
                enabled = s.on) { o ->
                set { it.copy(offerTheirLanguage = o) }
            }
        }
        preferenceGroup(reading, "reading") {
            ListPreference(stringResource(R.string.messages_keep), s.keep, KeepMessages.entries, { it.text.render(langs) }, detail = { keepDetails[it] },
                enabled = s.on) { k -> set { it.copy(keep = k) } }
            ListPreference(stringResource(R.string.messages_after), s.afterReading, AfterReading.entries, { it.text.render(langs) },
                detail = { afterDetails[it] }, enabled = s.on) { r ->
                set { it.copy(afterReading = r) }
            }
            SwitchPreference(stringResource(R.string.messages_translate), stringResource(R.string.messages_translate_detail), s.translate, enabled = s.on) { t ->
                set { it.copy(translate = t) }
            }
            ListPreference(stringResource(R.string.messages_locked), s.locked, MessagesLocked.entries, { it.text.render(langs) },
                detail = { lockedDetails[it] }, enabled = s.on) { l ->
                set { it.copy(locked = l) }
            }
        }
        // 0.8: off by default; where and from whom are Settings › Headset's and Car mode's.
        preferenceGroup(announcements, "announce") {
            SwitchPreference(stringResource(R.string.messages_announce), stringResource(if (s.announce) R.string.messages_announce_on else R.string.messages_announce_off),
                s.announce, enabled = s.on) { an -> set { it.copy(announce = an) } }
            if (s.on && s.announce) {
                PreferenceItem(stringResource(R.string.messages_in_headset), stringResource(R.string.messages_in_headset_detail), RIcons.Headset,
                    onClick = { a.openPage(Page.Settings(SettingsRoutes.HEADSET)) })
                PreferenceItem(stringResource(R.string.messages_in_car), stringResource(R.string.messages_in_car_detail), RIcons.Car,
                    onClick = { a.openPage(Page.Settings(SettingsRoutes.CAR)) })
            }
        }
        preferenceFooter(footer)
    }
    if (setup) {
        MessagesSetupDialog(installed, onDismiss = { setup = false }) { apps ->
            setup = false
            // Each ticked app's packages installed now are pinned to their signing keys (MessageSigners).
            val pins = apps.flatMap { pinsNow(it).entries }.associate { it.toPair() }
            onEdit { it.turnedOn(apps, pins) }
            NotificationAccess.rebind(ctx)
        }
    }
}

/**
 * The "Read your messages?" window: what Robot will do, then the apps it knows that are installed, your texting app
 * (Cheogram) ticked; [onTurnOn] with the apps ticked. Settings › Messages' main switch opens it, and (1.0) so does the
 * setup guide's, with [needsApp]: Turn on waits until an app is ticked.
 */
@Composable
internal fun MessagesSetupDialog(installed: (MessageApp) -> Boolean, onDismiss: () -> Unit, needsApp: Boolean = false, onTurnOn: (Set<MessageApp>) -> Unit) {
    val texting = if (installed(MessageApp.CHEOGRAM)) MessageApp.CHEOGRAM else null
    var apps by remember { mutableStateOf(MessageAllowlist.atSetup(texting).apps) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.messages_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.messages_setup_text), style = MaterialTheme.typography.bodyMedium)
                for (app in MessageApp.entries.filter { installed(it) || it in apps }) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(app in apps, role = Role.Checkbox) { on -> apps = if (on) apps + app else apps - app },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = app in apps, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(app.titleText.str(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (MessageApp.entries.none { installed(it) }) {
                    Text(stringResource(R.string.messages_setup_none), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTurnOn(apps) }, enabled = !needsApp || apps.isNotEmpty()) { Text(stringResource(R.string.messages_setup_turn_on)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.messages_setup_cancel)) } },
    )
}

/** 1.0, the setup guide's "Read my messages": off, or on again with the apps you had, as the main switch does; never on with no app. */
internal fun turnMessagesOn(ctx: android.content.Context, on: Boolean) {
    MessagesStore.update(ctx) { if (on && it.settings.allowlist.apps.isEmpty()) it else it.copy(settings = it.settings.copy(on = on)) }
    if (on && MessagesStore.current(ctx).settings.on) NotificationAccess.rebind(ctx)
}

/** 1.0, the setup guide: the set-up window's Turn on with [apps] ticked, each pinned to its signing key now, as on this page. */
internal fun turnMessagesOn(ctx: android.content.Context, apps: Set<MessageApp>) {
    if (apps.isEmpty()) return
    val pins = apps.flatMap { MessageAppSigners.pinsNow(ctx, it).entries }.associate { it.toPair() }
    MessagesStore.update(ctx) { it.turnedOn(apps, pins) }
    NotificationAccess.rebind(ctx)
}

/** 1.0, the setup guide: the apps Robot reads that are on this phone now (their package names, <queries> in the manifest). */
internal fun installedMessageApps(ctx: android.content.Context): Set<MessageApp> =
    MessageApp.entries.filter { app -> app.packages.any { HandOffApps.installed(ctx, it) } }.toSet()

/** 1.0, the setup guide: "Announce new messages", as this page's switch. */
internal fun announceMessages(ctx: android.content.Context, on: Boolean) = MessagesStore.update(ctx) { it.copy(settings = it.settings.copy(announce = on)) }

/** What Android says of an app's packages now (their signing keys and installer), for turning it on. */
@Composable
private fun rememberPinsNow(): (MessageApp) -> Map<String, AppPin> {
    val ctx = LocalContext.current
    return remember { { app -> MessageAppSigners.pinsNow(ctx, app) } }
}

/** The apps turned on whose packages Robot doesn't trust now (MessageAppSigners.problems), in the menus' language. */
@Composable
private fun rememberProblems(): (MessagesFile) -> List<String> {
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    return remember(langs) { { f -> MessageAppSigners.problems(ctx, f, langs) } }
}

/** Which of the apps are on this phone (by their package names, <queries> in the manifest). */
@Composable
private fun rememberInstalled(): (MessageApp) -> Boolean {
    val ctx = LocalContext.current
    val here = remember { MessageApp.entries.associateWith { app -> app.packages.any { HandOffApps.installed(ctx, it) } } }
    return { here[it] == true }
}

/** "Installed · replies checked", "Not installed". */
@Composable
internal fun appSummary(app: MessageApp, installed: Boolean): String = listOfNotNull(
    stringResource(if (installed) R.string.messages_installed else R.string.messages_not_installed),
    when {
        !app.verified -> stringResource(R.string.messages_app_unverified)
        app.sms -> stringResource(R.string.messages_app_sms)
        app.replyMarksRead -> stringResource(R.string.messages_app_marks_read)
        app.markReadTellsSender -> stringResource(R.string.messages_app_tells_sender)
        else -> null
    },
).joinToString(" · ")

@Composable
internal fun routeDetail(r: ReplyRoute): String = stringResource(when (r) {
    ReplyRoute.SEND_AFTER_CARD -> R.string.messages_route_send_detail
    ReplyRoute.OPEN_CHAT -> R.string.messages_route_open_detail
})

@Composable
internal fun keepDetail(k: KeepMessages): String = stringResource(when (k) {
    KeepMessages.UNTIL_GONE -> R.string.messages_keep_gone_detail
    KeepMessages.ONE_HOUR, KeepMessages.ONE_DAY -> R.string.messages_keep_ram_detail
})

@Composable
internal fun afterDetail(a: AfterReading): String = stringResource(when (a) {
    AfterReading.LEAVE -> R.string.messages_after_leave_detail
    AfterReading.CLEAR -> R.string.messages_after_clear_detail
    AfterReading.MARK_READ -> R.string.messages_after_mark_detail
})

@Composable
internal fun lockedDetail(l: MessagesLocked): String = stringResource(when (l) {
    MessagesLocked.OFF -> R.string.messages_locked_off_detail
    MessagesLocked.WHO_MESSAGED -> R.string.messages_locked_who_detail
    MessagesLocked.READ_AND_REPLY -> R.string.messages_locked_read_detail
})

/** [messagesSummary] in words without a language yet (Settings' row: `messagesSummaryText(f).str()`). */
internal fun messagesSummaryText(f: MessagesFile): Phrase {
    val s = f.settings
    if (!s.on) return MessageStrings.summaryOff()
    val apps = MessageApp.entries.filter { it in s.allowlist.apps }.map { it.titleText }
    return if (apps.isEmpty()) MessageStrings.summaryOnNone() else MessageStrings.summaryOn("appNames" to Phrase.Join(apps, ", "))
}

/**
 * People › a person (0.7): "Send my replies to … without a card". Only while Settings › Messages is on, for someone you
 * confirmed; your own words to them in a 1:1 chat go after a 3-second stop on an unlocked phone. Written to messages.json.
 */
@Composable
fun NoCardSwitch(p: Person, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val f = remember { MessagesStore.current(ctx) }.let { MessagesStore.state.value }
    if (!f.settings.on || !p.confirmed) return
    val on = p.id in f.noCard
    val who = MessagePeople.callName(p)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(on, role = Role.Switch) { v -> MessagesStore.update(ctx) { it.copy(noCard = if (v) it.noCard + p.id else it.noCard - p.id) } }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.messages_no_card, who), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.messages_no_card_detail),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        PrefSwitch(on)
    }
}

/**
 * People › a person (0.8): "Announce messages from …", for Settings › Headset's and Car mode's "Announce from: People you
 * choose". Only while Settings › Messages reads and announces messages, for someone you confirmed. Written to messages.json.
 */
@Composable
fun AnnounceSwitch(p: Person, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val f = remember { MessagesStore.current(ctx) }.let { MessagesStore.state.value }
    if (!f.settings.on || !f.settings.announce || !p.confirmed) return
    val on = p.id in f.announcePeople
    val who = MessagePeople.callName(p)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(on, role = Role.Switch) { v -> MessagesStore.update(ctx) { it.copy(announcePeople = if (v) it.announcePeople + p.id else it.announcePeople - p.id) } }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.messages_announce_from, who), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.messages_announce_from_detail),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        PrefSwitch(on)
    }
}

/** For the row in Settings (MessagesStore's file; the page shows what it holds). */
@Composable
internal fun messagesFile(): MessagesFile {
    val ctx = LocalContext.current
    remember { MessagesStore.current(ctx) }
    return MessagesStore.state.value
}
