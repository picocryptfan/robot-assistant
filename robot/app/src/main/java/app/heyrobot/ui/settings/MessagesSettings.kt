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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heyrobot.core.Person
import app.heyrobot.core.messages.AfterReading
import app.heyrobot.core.messages.KeepMessages
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessagePeople
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.MessagesLocked
import app.heyrobot.core.messages.ReplyRoute
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

/** The page; [file] and [onChange] are MessagesStore's (a test passes its own); [installed] says which apps are on the phone. */
@Composable
fun MessagesSettingsScreen(
    file: MessagesFile,
    facts: AssistantFacts?,
    onChange: (MessagesFile) -> Unit,
    a: AppActions,
    onBack: () -> Unit,
    installed: (MessageApp) -> Boolean = rememberInstalled(),
) {
    val ctx = LocalContext.current
    val s = file.settings
    var setup by rememberSaveable { mutableStateOf(false) }
    fun set(n: MessageSettings) = onChange(file.copy(settings = n))
    val granted = facts?.notificationAccess == true
    PreferenceScreen("Messages", onBack) {
        item(key = "intro") {
            IntroPreference("Robot reads, summarizes, translates and answers your messages from their notifications, on this phone only. " +
                "Messages are kept in memory only, never written to storage, and never sent anywhere unless you send them.")
        }
        item(key = "main") {
            MainSwitchBar("Read my messages", s.on) { on ->
                if (on && s.allowlist.apps.isEmpty()) setup = true
                else {
                    set(s.copy(on = on))
                    if (on) NotificationAccess.rebind(ctx)
                }
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
            for (app in MessageApp.entries) {
                val on = app in s.allowlist.apps
                val here = installed(app)
                SwitchPreference(app.title, appSummary(app, here), on, enabled = s.on) { set(s.copy(allowlist = s.allowlist.with(app, it))) }
            }
            SwitchPreference("Include group chats", "Replies to a group always need a tap", s.allowlist.groups, enabled = s.on) {
                set(s.copy(allowlist = s.allowlist.copy(groups = it)))
            }
        }
        preferenceGroup("Replies", "replies") {
            ListPreference("How replies go", s.replyRoute, ReplyRoute.entries, { it.title }, detail = { routeDetail(it) }, enabled = s.on) {
                set(s.copy(replyRoute = it))
            }
            SwitchPreference("Card before sending a reply", if (s.replyCard) "Robot shows what goes where; you tap Send"
                else "Your own words to one person go after a 3-second stop", s.replyCard, enabled = s.on && s.replyRoute == ReplyRoute.SEND_AFTER_CARD) {
                set(s.copy(replyCard = it))
            }
            SwitchPreference("Answer reply cards by voice", "A spoken yes sends your words to one person; a group, a draft or a translation always needs a tap",
                s.voiceYesSends, enabled = s.on && s.replyRoute == ReplyRoute.SEND_AFTER_CARD) { set(s.copy(voiceYesSends = it)) }
            SwitchPreference("Offer to send my reply in their language", "A tick box on the card, unticked", s.offerTheirLanguage, enabled = s.on) {
                set(s.copy(offerTheirLanguage = it))
            }
        }
        preferenceGroup("Reading", "reading") {
            ListPreference("Keep messages for", s.keep, KeepMessages.entries, { it.title }, detail = { keepDetail(it) }, enabled = s.on) { set(s.copy(keep = it)) }
            ListPreference("After Robot reads a message", s.afterReading, AfterReading.entries, { it.title }, detail = { afterDetail(it) }, enabled = s.on) {
                set(s.copy(afterReading = it))
            }
            SwitchPreference("Translate messages not in my languages", "English and French are read as written", s.translate, enabled = s.on) {
                set(s.copy(translate = it))
            }
            ListPreference("Messages on the lock screen", s.locked, MessagesLocked.entries, { it.title }, detail = { lockedDetail(it) }, enabled = s.on) {
                set(s.copy(locked = it))
            }
        }
        preferenceGroup("Later", "later") {
            SwitchPreference("Announce new messages", "Headphones only; comes with headset mode", s.announce, enabled = false) {}
        }
        preferenceFooter(FOOTER)
    }
    if (setup) {
        val texting = if (installed(MessageApp.CHEOGRAM)) MessageApp.CHEOGRAM else null
        var apps by remember { mutableStateOf(MessageAllowlist.atSetup(texting).apps) }
        AlertDialog(
            onDismissRequest = { setup = false },
            title = { Text("Read your messages?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(SETUP, style = MaterialTheme.typography.bodyMedium)
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
                        Text("None of the apps Robot knows is installed. Turn one on below once it is.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    setup = false
                    set(s.copy(on = true, allowlist = s.allowlist.copy(apps = apps)))
                    NotificationAccess.rebind(ctx)
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { setup = false }) { Text("Cancel") } },
        )
    }
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
    AfterReading.CLEAR -> "The notification goes; the chat stays unread in the app."
    AfterReading.MARK_READ -> "The sender may see you read it (Cheogram's read marker, Signal's receipts)."
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

internal const val SETUP = "Robot will read the notifications of the apps you tick here, keep their messages in memory only while their " +
    "notifications are there, and read, summarize, translate or answer them when you ask. Nothing is written to storage, and nothing " +
    "leaves the phone unless you send it. A message can never make Robot do anything: after one is read, every action asks first."

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
            .toggleable(on, role = Role.Switch) { v -> MessagesStore.save(ctx, f.copy(noCard = if (v) f.noCard + p.id else f.noCard - p.id)) }
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

/** For the row in Settings (MessagesStore's file; the page shows what it holds). */
@Composable
internal fun messagesFile(): MessagesFile {
    val ctx = LocalContext.current
    remember { MessagesStore.current(ctx) }
    return MessagesStore.state.value
}
