// SPDX-License-Identifier: GPL-3.0-only
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.heyrobot.R
import app.heyrobot.core.RepairWhen
import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.AskRoute
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.RecognizerKeeper
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
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
    val langs = LocalLangs.current
    val peopleTitle = UiStrings.peopleNotesCalendar().str()
    val toolsTitle = stringResource(R.string.settings_setup_tools)
    val assistantTitle = stringResource(R.string.settings_setup_assistant)
    val permissionsTitle = stringResource(R.string.settings_setup_permissions)
    val helpersTitle = UiStrings.helpers().str()
    val termuxTitle = UiStrings.termux().str()
    // 1.2: with another installed recognizer (a stock phone's own) the restart paragraph and its fix don't apply.
    val restart = if (f?.restartKept == app.heyrobot.core.RecognizerRepair.Kept.OTHER) ""
        else stringResource(R.string.settings_setup_footer_restart, UiStrings.inSettings(UiStrings.advanced, UiStrings.adbFixes).render(langs.ui, pseudo = langs.pseudo)) + "\n\n"
    val footer = f?.let {
        restart + stringResource(R.string.settings_setup_footer_people) + " " +
            (if (f.learnFromCheogram) "" else stringResource(R.string.settings_setup_footer_cheogram)) + "\n\n" +
            stringResource(R.string.settings_tools_note) + "\n\n" + stringResource(R.string.settings_setup_footer_helpers)
    }
    val checking = stringResource(R.string.settings_root_setup_checking)
    PreferenceScreen(UiStrings.assistantSetup().str(), onBack, snackbar = snackbar) {
        if (f == null || footer == null) {
            item(key = "checking") { IntroPreference(checking) }
            return@PreferenceScreen
        }
        // Opened from a card's "Allow": the permissions first.
        fun people() = preferenceGroup(peopleTitle, "people") { PeopleRows(f, a) }
        fun tools() = preferenceGroup(toolsTitle, "tools") { ToolsSetupRows(f, a) }
        if (peopleFirst) { people(); tools() }
        preferenceGroup(assistantTitle, "assistant") {
            StatusPreference(stringResource(R.string.settings_setup_role),
                if (f.roleHeld) stringResource(R.string.settings_setup_role_ok)
                else stringResource(R.string.settings_setup_role_todo, AndroidPath.DIGITAL_ASSISTANT.render(langs.system)),
                if (f.roleHeld) Mark.OK else Mark.TODO)
            StatusPreference(stringResource(R.string.settings_setup_running),
                when (f.active) {
                    true -> stringResource(R.string.settings_setup_running_ok)
                    false -> stringResource(if (f.roleHeld) R.string.settings_setup_running_lost else R.string.settings_setup_not_yet)
                    null -> stringResource(R.string.settings_setup_unknown)
                },
                when (f.active) { true -> Mark.OK; false -> if (f.roleHeld) Mark.WARN else Mark.TODO; null -> Mark.TODO })
            // "Ask about this screen" needs both of the assistant's screen settings (core ScreenAccess).
            val screen = ScreenAccess.setup(f.screenTextOn, f.screenshotOn)
            val screenOpen = stringResource(R.string.settings_setup_screen_open)
            StatusPreference(stringResource(R.string.settings_setup_screen), screen.phrase.str(),
                when (screen.level) { ScreenAccess.Level.OK -> Mark.OK; ScreenAccess.Level.INFO -> Mark.TODO; ScreenAccess.Level.WARN -> Mark.WARN },
                action = if (screen.level == ScreenAccess.Level.OK) null else ({ TextButton(onClick = a.openAssistantSettings,
                    modifier = Modifier.semantics { contentDescription = screenOpen }) { Text(UiStrings.open().str()) } }))
            // 1.2: another installed recognizer (a stock phone's own) keeps Robot the assistant too: no warning, no fix to replace it.
            val other = !f.recognizerIsRobot && f.restartKept == app.heyrobot.core.RecognizerRepair.Kept.OTHER
            StatusPreference(stringResource(R.string.settings_setup_recognizer),
                if (f.recognizerIsRobot) stringResource(R.string.settings_setup_recognizer_ok)
                else if (other) pluralStringResource(R.plurals.settings_setup_recognizer_other_kept, f.recognizersFound, f.recognizersFound)
                else pluralStringResource(if (f.recognizer == "unset") R.plurals.settings_setup_recognizer_none else R.plurals.settings_setup_recognizer_other,
                    f.recognizersFound, f.recognizersFound),
                if (f.recognizerIsRobot || other) Mark.OK else Mark.WARN)
            StatusPreference(stringResource(R.string.settings_setup_survives),
                if (other) stringResource(R.string.settings_setup_survives_other)
                else stringResource(if (f.recognizerIsRobot) R.string.settings_setup_survives_yes else R.string.settings_setup_survives_no) +
                    (if (f.secureSettingsGranted) " " + stringResource(R.string.settings_setup_keeps_itself) else ""),
                if (f.recognizerIsRobot || other) Mark.OK else Mark.WARN,
                action = if (other || f.recognizerIsRobot && f.secureSettingsGranted) null else ({
                    RowButton(stringResource(R.string.settings_setup_fix), stringResource(R.string.settings_setup_fix_label)) { a.openPage(Page.AdbFixes) }
                }))
            StatusPreference(stringResource(R.string.settings_setup_listening),
                when (f.listening) {
                    true -> stringResource(R.string.settings_setup_yes)
                    false -> f.listeningNote ?: stringResource(R.string.settings_setup_no)
                    null -> stringResource(R.string.settings_setup_listening_later)
                },
                if (f.listening == true) Mark.OK else Mark.TODO)
            PreferenceItem(stringResource(R.string.settings_setup_android), stringResource(R.string.settings_setup_android_detail), RIcons.SettingsVoice,
                onClick = a.openAssistantSettings)
            // 0.8: who answers the headset button's three intents; Test presses it (VOICE_COMMAND), so Android's chooser shows if it would.
            f.headsetButton?.let { h ->
                val row = HeadsetSetup.row(f.headsetButtonOn, h)
                val title = row.titleText.str()
                StatusPreference(title, row.textPhrase.str().removePrefix("$title "), when (row.ok) { true -> Mark.OK; false -> Mark.TODO; null -> Mark.TODO },
                    onClick = { a.openPage(Page.Settings(SettingsRoutes.HEADSET)) },
                    action = if (!f.headsetButtonOn) null else ({ RowButton(stringResource(R.string.settings_setup_test), stringResource(R.string.settings_setup_test_label)) {
                        try { ctx.startActivity(Intent(HeadsetEntry.VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    } }))
            }
        }
        preferenceGroup(permissionsTitle, "permissions") {
            val allowed = stringResource(R.string.settings_setup_allowed)
            val allow = UiStrings.allow().str()
            StatusPreference(stringResource(R.string.settings_setup_mic), if (f.micGranted) allowed else stringResource(R.string.settings_setup_mic_todo),
                if (f.micGranted) Mark.OK else Mark.TODO,
                action = if (f.micGranted) null else ({ RowButton(allow, stringResource(R.string.settings_setup_mic_allow), a.askPermissions) }))
            StatusPreference(stringResource(R.string.settings_setup_notifications),
                if (f.notificationsGranted) allowed else stringResource(R.string.settings_setup_notifications_todo),
                if (f.notificationsGranted) Mark.OK else Mark.TODO,
                action = if (f.notificationsGranted) null else ({ RowButton(allow, stringResource(R.string.settings_setup_notifications_allow), a.askPermissions) }))
        }
        if (!peopleFirst) { people(); tools() }
        preferenceGroup(helpersTitle, "helpers") { HelperRows(f, helpers, a) }
        // 0.9: the Termux agent, optional (its checklist is Settings › Termux's).
        preferenceGroup(termuxTitle, "termux") { TermuxSetupRow(a) }
        preferenceGroup(null, "adb") {
            PreferenceItem(UiStrings.adbFixes().str(), stringResource(R.string.settings_setup_adb_detail), RIcons.Terminal, onClick = { a.openPage(Page.AdbFixes) })
        }
        preferenceFooter(footer)
    }
}

/** Assistant setup › People, notes and calendar: the two permissions with Allow, the hand-off apps, and message notifications. */
@Composable
private fun PeopleRows(f: AssistantFacts, a: AppActions) {
    val allow = UiStrings.allow().str()
    StatusPreference(stringResource(R.string.settings_setup_contacts),
        stringResource(if (f.contactsGranted) R.string.settings_setup_contacts_ok else R.string.settings_setup_contacts_todo),
        if (f.contactsGranted) Mark.OK else Mark.TODO,
        action = if (f.contactsGranted) null else ({ RowButton(allow, stringResource(R.string.settings_setup_contacts_allow), a.askContacts) }))
    StatusPreference(stringResource(R.string.settings_setup_calendar),
        stringResource(if (f.calendarGranted) R.string.settings_setup_calendar_ok else R.string.settings_setup_calendar_todo),
        if (f.calendarGranted) Mark.OK else Mark.TODO,
        action = if (f.calendarGranted) null else ({ RowButton(allow, stringResource(R.string.settings_setup_calendar_allow), a.askCalendar) }))
    StatusPreference("Cheogram", stringResource(if (f.cheogramInstalled) R.string.settings_setup_cheogram_ok else R.string.settings_setup_cheogram_todo),
        if (f.cheogramInstalled) Mark.OK else Mark.TODO)
    StatusPreference("Proton Mail", stringResource(if (f.protonInstalled) R.string.settings_setup_proton_ok else R.string.settings_setup_proton_todo),
        if (f.protonInstalled) Mark.OK else Mark.TODO)
    // 0.4.0: notification access is asked for only once "Learn names from Cheogram notifications" is on (Settings › People).
    val cheogram = stringResource(R.string.settings_setup_cheogram_notifications)
    when {
        f.learnFromCheogram && f.notificationAccess -> StatusPreference(cheogram, stringResource(R.string.settings_setup_cheogram_notifications_ok), Mark.OK)
        f.learnFromCheogram -> StatusPreference(cheogram, stringResource(R.string.settings_setup_cheogram_notifications_todo), Mark.TODO,
            onClick = { a.openPage(Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)) })
    }
    if (!f.contactsGranted || !f.calendarGranted) {
        PreferenceItem(stringResource(R.string.settings_setup_no_ask), stringResource(R.string.settings_setup_app_settings), RIcons.Settings, onClick = a.openAppSettings)
    }
}

/**
 * Assistant setup › Helpers: GrapheneOS's Network toggle, each helper's key, and (1.0.0) the app of each helper that opens
 * one, with which hand-off its installed version accepts (the Claude app's claude:// and claude.ai links, a share), in
 * words, so you can tell without adb.
 */
@Composable
private fun HelperRows(f: AssistantFacts, helpers: List<HelperConfig>, a: AppActions) {
    StatusPreference(stringResource(R.string.settings_setup_network),
        stringResource(if (f.networkGranted) R.string.settings_setup_network_ok else R.string.settings_setup_network_off),
        if (f.networkGranted) Mark.OK else Mark.WARN,
        action = if (f.networkGranted) null else ({ RowButton(UiStrings.open().str(), stringResource(R.string.settings_setup_app_settings), a.openAppSettings) }))
    for (h in helpers.filter { it.presetOf.keyRequired }) {
        StatusPreference(stringResource(R.string.settings_setup_key, h.name), f.keys[h.id]
            ?: if (h.presetOf.app != null) stringResource(R.string.settings_setup_key_none_app, h.presetOf.defaultName, h.name)
            else stringResource(R.string.settings_setup_key_none, h.name),
            if (f.keys[h.id] != null) Mark.OK else Mark.TODO)
    }
    // An app's row only for a helper that opens it (on, without a key or set to its app): nobody else needs it installed.
    val viaApp = helpers.filter { it.on && it.presetOf.app != null && HelperSettings.route(it, f.keys[it.id] != null) == AskRoute.APP }
        .distinctBy { it.presetOf.app }
    val wayApp = stringResource(R.string.settings_setup_way_app)
    val wayWeb = stringResource(R.string.settings_setup_way_web)
    val wayShare = stringResource(R.string.settings_setup_way_share)
    for (h in viaApp) {
        val c = f.app(h.presetOf.app) ?: continue
        val ways = listOfNotNull(wayApp.takeIf { c.appLink }, wayWeb.takeIf { c.webLink }, wayShare.takeIf { c.send })
        StatusPreference(stringResource(R.string.settings_setup_helper_app, h.presetOf.defaultName),
            if (!c.installed) stringResource(R.string.settings_setup_not_installed)
            else if (ways.isEmpty()) stringResource(R.string.settings_setup_claude_clipboard)
            else stringResource(R.string.settings_setup_claude_ways, ways.joinToString(", ")),
            if (c.installed) Mark.OK else Mark.TODO)
    }
}
