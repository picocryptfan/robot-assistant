package app.heyrobot.ui.settings

import android.content.Intent
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.heyrobot.core.RepairWhen
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.RecognizerKeeper
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.TOOLS_SETUP_NOTE
import app.heyrobot.ui.ToolsSetupRows
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.core.headset.HeadsetEntry
import app.heyrobot.core.headset.HeadsetSetup
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Assistant setup: is Robot the digital assistant and does it stay one after a restart, the
// permissions, People, notes and calendar's, the optional weather and phone status ones, and what the helpers need. A
// status row each (0.4.x redesign: done, to do or a problem, with the button that fixes it); what happens after a restart
// is the footer.

/** A button at the end of a status row, named for TalkBack ([label]: "Allow the microphone"). */
@Composable
private fun RowButton(text: String, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) { Text(text) }
}

@Composable
fun SetupScreen(f: AssistantFacts?, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit, peopleFirst: Boolean = false,
    helpers: List<HelperConfig> = emptyList()) {
    val ctx = LocalContext.current
    // Opening Assistant setup checks the speech recognizer setting (Robot repairs it itself with the adb grant).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecognizerKeeper.check(ctx, RepairWhen.SETUP) }
    }
    PreferenceScreen("Assistant setup", onBack, snackbar = snackbar) {
        if (f == null) {
            item(key = "checking") { IntroPreference("Checking…") }
            return@PreferenceScreen
        }
        // Opened from a card's "Allow": the permissions first.
        fun people() = preferenceGroup("People, notes and calendar", "people") { PeopleRows(f, a) }
        fun tools() = preferenceGroup("Weather and phone status", "tools") { ToolsSetupRows(f, a) }
        if (peopleFirst) { people(); tools() }
        preferenceGroup("Assistant", "assistant") {
            StatusPreference("Robot is the digital assistant",
                if (f.roleHeld) "Android lists Robot as your assistant" else "Pick Robot in Settings › Apps › Default apps › Digital assistant app",
                if (f.roleHeld) Mark.OK else Mark.TODO)
            StatusPreference("The assistant is running",
                when (f.active) {
                    true -> "The corner swipe and “hey robot” open Robot"
                    false -> if (f.roleHeld) "Android chose Robot but isn't running it. This happens after a restart: pick Robot again." else "Not yet"
                    null -> "Android didn't say"
                },
                when (f.active) { true -> Mark.OK; false -> if (f.roleHeld) Mark.WARN else Mark.TODO; null -> Mark.TODO })
            // "Ask about this screen" needs both of the assistant's screen settings (core ScreenAccess).
            val screen = ScreenAccess.setup(f.screenTextOn, f.screenshotOn)
            StatusPreference("Screen sharing", screen.text,
                when (screen.level) { ScreenAccess.Level.OK -> Mark.OK; ScreenAccess.Level.INFO -> Mark.TODO; ScreenAccess.Level.WARN -> Mark.WARN },
                action = if (screen.level == ScreenAccess.Level.OK) null else ({ TextButton(onClick = a.openAssistantSettings,
                    modifier = Modifier.semantics { contentDescription = "Open the assistant's screen settings" }) { Text("Open") } }))
            StatusPreference("Default speech recognizer",
                if (f.recognizerIsRobot) "Set to Robot, so Android keeps Robot as the assistant after a restart"
                else "Not set to Robot (${if (f.recognizer == "unset") "none set" else "another app"}; ${f.recognizersFound} installed). " +
                    "After a restart Android may turn Robot off.",
                if (f.recognizerIsRobot) Mark.OK else Mark.WARN)
            StatusPreference("Survives restarts",
                (if (f.recognizerIsRobot) "Yes: Robot is the speech recognizer, so Android keeps it the assistant."
                else "Not yet: Android may turn Robot off at the next restart. A one-time fix with a computer solves it.") +
                    (if (f.secureSettingsGranted) " Robot keeps this set itself." else ""),
                if (f.recognizerIsRobot) Mark.OK else Mark.WARN,
                action = if (f.recognizerIsRobot && f.secureSettingsGranted) null else ({
                    RowButton("Fix", "Fix it with ADB fixes") { a.openPage(Page.AdbFixes) }
                }))
            StatusPreference("Listening for wake words",
                when (f.listening) { true -> "Yes"; false -> f.listeningNote ?: "No"; null -> "Starts when Robot is the running assistant" },
                if (f.listening == true) Mark.OK else Mark.TODO)
            PreferenceItem("Assistant settings", "Android's Digital assistant app page", RIcons.SettingsVoice, onClick = a.openAssistantSettings)
            // 0.8: who answers the headset button's three intents; Test presses it (VOICE_COMMAND), so Android's chooser shows if it would.
            f.headsetButton?.let { h ->
                val row = HeadsetSetup.row(f.headsetButtonOn, h)
                StatusPreference(row.title, row.text.removePrefix("Headset button "), when (row.ok) { true -> Mark.OK; false -> Mark.TODO; null -> Mark.TODO },
                    onClick = { a.openPage(Page.Settings(SettingsRoutes.HEADSET)) },
                    action = if (!f.headsetButtonOn) null else ({ RowButton("Test", "Test the headset button") {
                        try { ctx.startActivity(Intent(HeadsetEntry.VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    } }))
            }
        }
        preferenceGroup("Permissions", "permissions") {
            StatusPreference("Microphone", if (f.micGranted) "Allowed" else "Needed for wake words and voice questions",
                if (f.micGranted) Mark.OK else Mark.TODO,
                action = if (f.micGranted) null else ({ RowButton("Allow", "Allow the microphone", a.askPermissions) }))
            StatusPreference("Notifications", if (f.notificationsGranted) "Allowed" else "Needed for the restart reminder",
                if (f.notificationsGranted) Mark.OK else Mark.TODO,
                action = if (f.notificationsGranted) null else ({ RowButton("Allow", "Allow notifications", a.askPermissions) }))
        }
        if (!peopleFirst) { people(); tools() }
        preferenceGroup("Helpers", "helpers") { HelperRows(f, helpers, a) }
        // 0.9: the Termux agent, optional (its checklist is Settings › Termux's).
        preferenceGroup("Termux", "termux") { TermuxSetupRow(a) }
        preferenceGroup(null, "adb") {
            PreferenceItem("ADB fixes", "Make Robot stay the assistant after a restart", RIcons.Terminal, onClick = { a.openPage(Page.AdbFixes) })
        }
        preferenceFooter(
            "After a restart: Android 17 keeps a non-system assistant across a restart only when a default speech recognizer is set, and " +
                "it never picks one from an app like Robot by itself. GrapheneOS has no screen to choose one. So after a restart, Android " +
                "may quietly turn Robot off as your assistant. Robot checks 3 minutes after every restart. If it was turned off, you get a " +
                "notification: tap it, choose “Digital assistant app” and pick Robot again. To make it stay, use the one-time fix in " +
                "Settings › Advanced › ADB fixes.\n\n" +
                "People, notes and calendar: Robot asks for these only when you tap Allow. What it reads stays on the phone; texts and " +
                "emails open as drafts and you press Send. " + (if (f.learnFromCheogram) "" else "Optional: Settings › People can learn " +
                "names from Cheogram notifications. It is off, and needs no access while it is off.") + "\n\n" + TOOLS_SETUP_NOTE + "\n\n" +
                "Helpers: Robot uses the network for four things only: downloading its models, a helper when you ask for one, the " +
                "weather (Open-Meteo) when you ask for it, and your own AI server, if you add one.",
        )
    }
}

/** Assistant setup › People, notes and calendar: the two permissions with Allow, the hand-off apps, and Cheogram's notifications. */
@Composable
private fun PeopleRows(f: AssistantFacts, a: AppActions) {
    StatusPreference("Contacts",
        if (f.contactsGranted) "Allowed: Robot finds the person you name" else "To text or email someone by name, and to look up a number",
        if (f.contactsGranted) Mark.OK else Mark.TODO,
        action = if (f.contactsGranted) null else ({ RowButton("Allow", "Allow contacts", a.askContacts) }))
    StatusPreference("Calendar",
        if (f.calendarGranted) "Allowed: Robot reads your events and adds new ones"
        else "To read your events and add new ones (without it, your calendar app opens to add one)",
        if (f.calendarGranted) Mark.OK else Mark.TODO,
        action = if (f.calendarGranted) null else ({ RowButton("Allow", "Allow the calendar", a.askCalendar) }))
    StatusPreference("Cheogram", if (f.cheogramInstalled) "Installed: texts open there as drafts" else "Not installed: texts use the SMS app",
        if (f.cheogramInstalled) Mark.OK else Mark.TODO)
    StatusPreference("Proton Mail", if (f.protonInstalled) "Installed: emails open there as drafts" else "Not installed: Android asks which email app to use",
        if (f.protonInstalled) Mark.OK else Mark.TODO)
    // 0.4.0: notification access is asked for only once "Learn names from Cheogram notifications" is on (Settings › People).
    when {
        f.learnFromCheogram && f.notificationAccess -> StatusPreference("Cheogram notifications",
            "Notification access allowed: Robot keeps who messages you in Cheogram, never the text", Mark.OK)
        f.learnFromCheogram -> StatusPreference("Cheogram notifications", "Needs notification access: three steps", Mark.TODO,
            onClick = { a.openPage(Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)) })
    }
    if (!f.contactsGranted || !f.calendarGranted) {
        PreferenceItem("Android doesn't ask?", "Open Robot's app settings", RIcons.Settings, onClick = a.openAppSettings)
    }
}

/**
 * Assistant setup › Helpers: GrapheneOS's Network toggle, each helper's key, and the Claude app with which hand-off its
 * installed version accepts (a claude:// link, a claude.ai link, a share), in words, so you can tell without adb.
 */
@Composable
private fun HelperRows(f: AssistantFacts, helpers: List<HelperConfig>, a: AppActions) {
    StatusPreference("Network",
        if (f.networkGranted) "Allowed, as Android reports it. If a helper says “No internet” while you're online, check Robot's Network permission."
        else "Turned off for Robot: no helper can be reached",
        if (f.networkGranted) Mark.OK else Mark.WARN,
        action = if (f.networkGranted) null else ({ RowButton("Open", "Open Robot's app settings", a.openAppSettings) }))
    for (h in helpers.filter { it.presetOf.keyRequired }) {
        StatusPreference("${h.name} API key", f.keys[h.id]
            ?: if (h.presetOf.app != null) "None: questions open the ${h.presetOf.defaultName} app instead (Settings › Helpers › ${h.name})"
            else "None (Settings › Helpers › ${h.name})",
            if (f.keys[h.id] != null) Mark.OK else Mark.TODO)
    }
    val c = f.claudeApp
    val ways = listOfNotNull("claude:// link".takeIf { c.appLink }, "claude.ai link".takeIf { c.webLink }, "share".takeIf { c.send })
    StatusPreference("Claude app",
        if (!c.installed) "Not installed"
        else if (ways.isEmpty()) "Installed. Robot opens the app and puts your question on the clipboard."
        else "Installed · opens with: " + ways.joinToString(", "),
        if (c.installed) Mark.OK else Mark.TODO)
}
