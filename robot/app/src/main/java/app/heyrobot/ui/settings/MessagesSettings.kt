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

// MAIN PROCESS. Settings › Messages (0.7, design §B11): "Read my messages" and each app (all off), group chats, how
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
    PreferenceScreen("Messages", onBack) {
        item(key = "intro") {
            IntroPreference("Robot reads, summarizes, translates and answers your messages from their notifications, on this phone only. " +
                "Messages are kept in memory only, never written to storage, and never sent anywhere unless you send them.")
        }
        item(key = "main") {
            MainSwitchBar("Read my messages", s.on) { on ->
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
                StatusPreference("Notification access is still allowed", "Robot reads no message while this is off", Mark.TODO,
                    action = { TextButton(onClick = { app.heyrobot.ui.openFirst(ctx, NotificationAccess.settings(ctx), a) }) { Text("Open") } })
            }
        }
        if (s.on) {
            preferenceGroup(null, "access") {
                if (!granted) StatusPreference("Notification access", "Needed: three steps", Mark.TODO,
                    onClick = { a.openPage(Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)) })
                else StatusPreference("Notification access allowed", "Messages from the apps below only", Mark.OK)
                PreferenceItem("Quiet notifications", "Android sends Robot conversations only. To read quiet-hours messages too, allow Silent " +
                    "in Robot's notification access page.", onClick = { app.heyrobot.ui.openFirst(ctx, NotificationAccess.settings(ctx), a) })
            }
        }
        preferenceGroup("Apps", "apps") {
            // An app Robot no longer reads: its signing key changed, or this build came after you turned it on.
            for (line in untrusted) StatusPreference(line, "Robot ignores its notifications until then", Mark.WARN)
            for (app in MessageApp.entries) {
                val on = app in s.allowlist.apps
                val here = installed(app)
                SwitchPreference(app.title, appSummary(app, here), on, enabled = s.on) { turn(app, it) }
            }
            SwitchPreference("Include group chats", "Replies to a group always need a tap", s.allowlist.groups, enabled = s.on) { g ->
                set { it.copy(allowlist = it.allowlist.copy(groups = g)) }
            }
        }
        preferenceGroup("Replies", "replies") {
            ListPreference("How replies go", s.replyRoute, ReplyRoute.entries, { it.title }, detail = { routeDetail(it) }, enabled = s.on) { r ->
                set { it.copy(replyRoute = r) }
            }
            SwitchPreference("Card before sending a reply", if (s.replyCard) "Robot shows what goes where; you tap Send"
                else "Your own words to one person go after a 3-second stop", s.replyCard, enabled = s.on && s.replyRoute == ReplyRoute.SEND_AFTER_CARD) { c ->
                set { it.copy(replyCard = c) }
            }
            SwitchPreference("Answer reply cards by voice", "A spoken yes sends your words to one person; a group, a draft or a translation always needs a tap",
                s.voiceYesSends, enabled = s.on && s.replyRoute == ReplyRoute.SEND_AFTER_CARD) { v -> set { it.copy(voiceYesSends = v) } }
            SwitchPreference("Offer to send my reply in their language", "A tick box on the card, unticked", s.offerTheirLanguage, enabled = s.on) { o ->
                set { it.copy(offerTheirLanguage = o) }
            }
        }
        preferenceGroup("Reading", "reading") {
            ListPreference("Keep messages for", s.keep, KeepMessages.entries, { it.title }, detail = { keepDetail(it) }, enabled = s.on) { k -> set { it.copy(keep = k) } }
            ListPreference("After Robot reads a message", s.afterReading, AfterReading.entries, { it.title }, detail = { afterDetail(it) }, enabled = s.on) { r ->
                set { it.copy(afterReading = r) }
            }
            SwitchPreference("Translate messages not in my languages", "English and French are read as written", s.translate, enabled = s.on) { t ->
                set { it.copy(translate = t) }
            }
            ListPreference("Messages on the lock screen", s.locked, MessagesLocked.entries, { it.title }, detail = { lockedDetail(it) }, enabled = s.on) { l ->
                set { it.copy(locked = l) }
            }
        }
        // 0.8: off by default; where and from whom are Settings › Headset's and Car mode's.
        preferenceGroup("Announcements", "announce") {
            SwitchPreference("Announce new messages", if (s.announce) "In private headphones, and in the car while car mode runs; never on the phone's speaker"
                else "Off", s.announce, enabled = s.on) { an -> set { it.copy(announce = an) } }
            if (s.on && s.announce) {
                PreferenceItem("In the headset", "Who, how, and on the lock screen", RIcons.Headset, onClick = { a.openPage(Page.Settings(SettingsRoutes.HEADSET)) })
                PreferenceItem("In the car", "Who, reading aloud and replies by voice", RIcons.Car, onClick = { a.openPage(Page.Settings(SettingsRoutes.CAR)) })
            }
        }
        preferenceFooter(FOOTER)
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
                        Text(app.title, style = MaterialTheme.typography.bodyLarge)
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

/** The apps turned on whose packages Robot doesn't trust now (MessageAppSigners.problems). */
@Composable
private fun rememberProblems(): (MessagesFile) -> List<String> {
    val ctx = LocalContext.current
    return remember { { f -> MessageAppSigners.problems(ctx, f) } }
}

/** Which of the apps are on this phone (by their package names, <queries> in the manifest). */
@Composable
private fun rememberInstalled(): (MessageApp) -> Boolean {
    val ctx = LocalContext.current
    val here = remember { MessageApp.entries.associateWith { app -> app.packages.any { HandOffApps.installed(ctx, it) } } }
    return { here[it] == true }
}

/** "Installed · replies checked", "Not installed". */
internal fun appSummary(app: MessageApp, installed: Boolean): String = listOfNotNull(
    if (installed) "Installed" else "Not installed",
    when {
        !app.verified -> "how it replies isn't checked yet"
        app.sms -> "replies are SMS"
        app.replyMarksRead -> "a reply marks the chat read"
        app.markReadTellsSender -> "Mark as read tells the sender"
        else -> null
    },
).joinToString(" · ")

internal fun routeDetail(r: ReplyRoute): String = when (r) {
    ReplyRoute.SEND_AFTER_CARD -> "Through the app's own reply action, after its card. A sent message can't be recalled."
    ReplyRoute.OPEN_CHAT -> "The chat opens with your words; nothing is sent until you press Send there."
}

internal fun keepDetail(k: KeepMessages): String = when (k) {
    KeepMessages.UNTIL_GONE -> "Read in the app, dismissed or cleared: gone from Robot too."
    KeepMessages.ONE_HOUR, KeepMessages.ONE_DAY -> "Still in memory only; a restart forgets them."
}

internal fun afterDetail(a: AfterReading): String = when (a) {
    AfterReading.LEAVE -> "Nothing changes in the app; Robot only remembers it read it."
    AfterReading.CLEAR -> "The notification goes; the chat stays unread in the app. A reply then opens the chat."
    AfterReading.MARK_READ -> "The sender may see you read it (Cheogram's read marker, Signal's receipts). A reply then opens the chat."
}

internal fun lockedDetail(l: MessagesLocked): String = when (l) {
    MessagesLocked.OFF -> "“Unlock first”."
    MessagesLocked.WHO_MESSAGED -> "Names only: who wrote, not what."
    MessagesLocked.READ_AND_REPLY -> "Anyone holding your locked phone can hear your messages and reply."
}

/** The Settings row's state: "Off", "On · Cheogram, Signal", "On · no app yet". */
internal fun messagesSummary(f: MessagesFile): String {
    val s = f.settings
    if (!s.on) return "Off"
    val apps = MessageApp.entries.filter { it in s.allowlist.apps }.map { it.title }
    return "On · " + if (apps.isEmpty()) "no app yet" else apps.joinToString(", ")
}

internal const val FOOTER = "Robot reads the shade, not a database: no SMS permission, no message history. A message's text stays in memory " +
    "until its notification goes; History keeps a line such as “Robot read 2 messages from Mom”, never the words, and the voice log keeps " +
    "numbers only.\n\nReplies go to the conversation the notification belongs to, never to anyone else, and only after you tap Send on the " +
    "card (or, for someone you chose in People, after a 3-second stop). A sent message can't be recalled. An SMS reply may cost what your " +
    "carrier charges. Signal marks the chat read and may send a read receipt when you reply from its notification. Cheogram sends an emoji " +
    "alone as a reaction, and its Mark as read tells the sender.\n\nSignal shows other apps only “New message” unless Signal › Settings › " +
    "Notifications › Show is “Name and message”. Android hides messages that look like login codes from apps like Robot."

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
            Text("Send my replies to $who without a card", style = MaterialTheme.typography.bodyLarge)
            Text("Your own words, one to one, phone unlocked: Robot says them and sends after 3 seconds unless you say stop.",
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
            Text("Announce messages from $who", style = MaterialTheme.typography.bodyLarge)
            Text("In private headphones, and in the car in car mode, when “Announce from” is “People you choose”.",
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
