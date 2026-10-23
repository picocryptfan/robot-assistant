// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.str
import androidx.compose.runtime.Composable
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › Advanced (0.4.x redesign): the one-time fixes with a computer, and the voice log.

@Composable
fun AdvancedSettingsScreen(a: AppActions, onBack: () -> Unit) {
    val footer = stringResource(R.string.settings_advanced_footer)
    PreferenceScreen(UiStrings.advanced().str(), onBack) {
        preferenceGroup(null, "rows") {
            PreferenceItem(UiStrings.adbFixes().str(), stringResource(R.string.settings_advanced_adb_detail), RIcons.Terminal,
                onClick = { a.openPage(Page.AdbFixes) })
            PreferenceItem(UiStrings.voiceLog().str(), stringResource(R.string.settings_advanced_log_detail), RIcons.History, onClick = { a.openPage(Page.VoiceLog) })
        }
        preferenceFooter(footer)
    }
}
