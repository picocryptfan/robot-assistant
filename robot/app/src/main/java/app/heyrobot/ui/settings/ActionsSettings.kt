// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.heyrobot.core.Approval
import app.heyrobot.core.LockScreen
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearbyServer
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.R
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SettingsStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
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
@Composable
internal fun approvalDetail(a: Approval): String = stringResource(when (a) {
    Approval.AUTO -> R.string.actions_auto_detail
    Approval.ASK -> R.string.actions_ask_detail
    Approval.OFF -> R.string.actions_off_detail
})

/** [approvalSummary] in words without a language yet. */
internal fun approvalSummaryText(t: ToolName, level: Approval, locked: Boolean): Phrase =
    Phrase.Join(listOfNotNull(level.titleText, SettingsStrings.worksLocked().takeIf { locked }, SettingsStrings.usesInternet().takeIf { t.online }), " · ")

/** The actions in groups, as the panel's tools are (core ToolGroup): each group's key, its title and its tools. */
private val GROUPS = listOf(
    Triple("core", R.string.actions_group_core, listOf(ToolGroup.CORE)),
    Triple("people", R.string.actions_group_people, listOf(ToolGroup.PEOPLE)),
    Triple("notes", R.string.actions_group_notes, listOf(ToolGroup.NOTES)),
    Triple("calendar", R.string.actions_group_calendar, listOf(ToolGroup.CALENDAR)),
    Triple("weather", R.string.actions_group_weather, listOf(ToolGroup.WEATHER, ToolGroup.PHONE)),
    // 0.9: Web searches (Settings › Web search turns web search itself on and off).
    Triple("web", R.string.actions_group_web, listOf(ToolGroup.WEB)),
)

@Composable
fun ActionsSettingsScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val server = s.nearbyServer
    val langs = LocalLangs.current
    val details = Approval.entries.associateWith { approvalDetail(it) }
    val titles = GROUPS.associate { (key, title, _) -> key to stringResource(title) }
    val nearest = stringResource(R.string.actions_nearest)
    val nearestTitles = NearestOnline.entries.associateWith { it.titleText.str() }
    val nearestDetails = NearestOnline.entries.associateWith { (if (it == NearestOnline.ASK) SettingsStrings.theDefault("detail" to it.detailText) else it.detailText).str() }
    val searchServer = UiStrings.searchServer().str()
    val serverDefault = stringResource(R.string.actions_server_default, Nearby.HOST)
    val serverField = stringResource(R.string.actions_server_field)
    val serverHint = stringResource(R.string.actions_server_hint)
    val reset = stringResource(R.string.actions_server_reset)
    val resetDetail = stringResource(R.string.actions_server_reset_detail, Nearby.HOST)
    val maps = s.approval(ToolName.MAPS_NAVIGATE) != Approval.OFF || s.approval(ToolName.MAPS_SEARCH) != Approval.OFF
    val footer = stringResource(R.string.actions_footer_locked, LockScreen.allowed(s.lockedTools, langs)) + " " +
        stringResource(if (s.helperActionCards) R.string.actions_footer_helper_cards else R.string.actions_footer_helper_levels) + "\n\n" +
        stringResource(R.string.actions_footer_nearest, server.labelText.str()) + (if (maps) "" else " " + stringResource(R.string.actions_footer_maps_off)) + " " +
        (if (server.isDefault) stringResource(R.string.actions_footer_default_server, Nearby.HOST)
        else stringResource(R.string.actions_footer_own_server, server.name))
    PreferenceScreen(UiStrings.actions().str(), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.actions_intro)) }
        for ((key, _, groups) in GROUPS) {
            // The helpers' own settings decide about ask_claude (it has no level of its own).
            val tools = ToolName.entries.filter { !it.internal && it.group in groups }
            preferenceGroup(titles.getValue(key), "group-$key") { // l10n: not text (a list key)
                for (t in tools) {
                    val level = s.approval(t)
                    ListPreference(t.titleText.str(), level, Approval.entries, { it.titleText.render(langs) }, { details.getValue(it) }, toolIcon(t),
                        summary = approvalSummaryText(t, level, t in s.lockedTools).str()) { lvl -> update(s.copy(approvals = s.approvals + (t to lvl))) }
                    // Part of the maps actions (0.4.0): "the nearest …" found online, asked the first time by default.
                    if (t == ToolName.MAPS_NAVIGATE) {
                        ListPreference(nearest, Nearby.mode(s, snap.nearestAnswer), NearestOnline.entries, { nearestTitles.getValue(it) },
                            { nearestDetails.getValue(it) }, RIcons.Search) {
                            update(s.copy(nearestOnline = it, nearestOnlineAt = System.currentTimeMillis()))
                        }
                        TextPreference(searchServer, if (server.isDefault) "" else server.base, serverDefault, serverField, serverHint,
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            validate = { NearbyServer.check(it).problemText?.render(langs) }) { typed ->
                            NearbyServer.check(typed).server?.let { update(s.copy(nearestServer = it.saved)) }
                        }
                        if (!server.isDefault) {
                            PreferenceItem(reset, resetDetail, RIcons.Refresh, onClick = { update(s.copy(nearestServer = "")) })
                        }
                    }
                }
            }
        }
        preferenceFooter(footer)
    }
}
