package app.heyrobot.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import app.heyrobot.core.Approval
import app.heyrobot.core.LockScreen
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearbyServer
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.toolIcon
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › Actions (0.4.x redesign; before it, a row of three segmented buttons per action): one row per
// action, its level and whether it works locked or goes online under the title, a radio dialog to change it; "Find the
// nearest place online" and its search server under Maps. When "Just do it" still shows a card is in Safety & privacy.

/** What each level means, one line in the dialog. */
internal fun approvalDetail(a: Approval): String = when (a) {
    Approval.AUTO -> "Robot does it at once (Safety & privacy may still show a card)"
    Approval.ASK -> "A card first: you confirm or cancel"
    Approval.OFF -> "Robot doesn't do it"
}

/** "Ask first · works locked · uses the internet". */
internal fun approvalSummary(t: ToolName, level: Approval, locked: Boolean): String =
    listOfNotNull(level.title, "works locked".takeIf { locked }, "uses the internet".takeIf { t.online }).joinToString(" · ")

/** The actions in groups, as the panel's tools are (core ToolGroup). */
private val GROUPS = listOf(
    "Timers, apps and maps" to listOf(ToolGroup.CORE),
    "Texts, calls and email" to listOf(ToolGroup.PEOPLE),
    "Notes" to listOf(ToolGroup.NOTES),
    "Calendar" to listOf(ToolGroup.CALENDAR),
    "Weather and the phone" to listOf(ToolGroup.WEATHER, ToolGroup.PHONE),
)

@Composable
fun ActionsSettingsScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val server = s.nearbyServer
    PreferenceScreen("Actions", onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference("What Robot may do when you ask.") }
        for ((title, groups) in GROUPS) {
            // The helpers' own settings decide about ask_claude (it has no level of its own).
            val tools = ToolName.entries.filter { !it.internal && it.group in groups }
            preferenceGroup(title, "group-$title") {
                for (t in tools) {
                    val level = s.approval(t)
                    ListPreference(t.title, level, Approval.entries, { it.title }, ::approvalDetail, toolIcon(t),
                        summary = approvalSummary(t, level, t in s.lockedTools)) { lvl -> update(s.copy(approvals = s.approvals + (t to lvl))) }
                    // Part of the maps actions (0.4.0): "the nearest …" found online, asked the first time by default.
                    if (t == ToolName.MAPS_NAVIGATE) {
                        ListPreference("Find the nearest place online", Nearby.mode(s, snap.nearestAnswer), NearestOnline.entries, { it.title },
                            { (if (it == NearestOnline.ASK) "The default. " else "") + it.detail }, RIcons.Search) {
                            update(s.copy(nearestOnline = it, nearestOnlineAt = System.currentTimeMillis()))
                        }
                        TextPreference("Search server", if (server.isDefault) "" else server.base, "OpenStreetMap's Nominatim (${Nearby.HOST})",
                            "Nominatim server", "Its address, such as nominatim.example.org. Encrypted (HTTPS) only. Empty: OpenStreetMap's.",
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            validate = { NearbyServer.check(it).problem }) { typed ->
                            NearbyServer.check(typed).server?.let { update(s.copy(nearestServer = it.saved)) }
                        }
                        if (!server.isDefault) {
                            PreferenceItem("Reset to OpenStreetMap's", "Use ${Nearby.HOST} again", RIcons.Refresh, onClick = { update(s.copy(nearestServer = "")) })
                        }
                    }
                }
            }
        }
        val maps = s.approval(ToolName.MAPS_NAVIGATE) != Approval.OFF || s.approval(ToolName.MAPS_SEARCH) != Approval.OFF
        preferenceFooter(
            "On the lock screen: ${LockScreen.allowed(s.lockedTools)}; everything else asks you to unlock. When “Just do it” still " +
                "shows a card (after Robot read something, a name it isn't sure of, a helper's requests) is in Safety & privacy. " +
                (if (s.claudeActionCards) "Anything a helper asks Robot to do always shows a card first."
                else "A helper's requests follow the levels above and Safety cards.") +
                "\n\nFind the nearest place online: for “directions to the nearest pharmacy”, what would go is the words and your area " +
                "(about 1 km), to ${server.label}." + (if (maps) "" else " Maps search and directions are Off.") + " " +
                (if (server.isDefault) "The search server is OpenStreetMap's Nominatim (${Nearby.HOST}), the default; to use another " +
                    "Nominatim, such as your own, type its address."
                else "${server.name}: the words and your area go to this server instead of OpenStreetMap's."),
        )
    }
}
