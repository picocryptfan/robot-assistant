// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

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
    PreferenceScreen("Advanced", onBack) {
        preferenceGroup(null, "rows") {
            PreferenceItem("ADB fixes", "One-time fixes with a computer, such as staying the assistant after a restart", RIcons.Terminal,
                onClick = { a.openPage(Page.AdbFixes) })
            PreferenceItem("Voice log", "What the voice side did, for when Robot didn't respond", RIcons.History, onClick = { a.openPage(Page.VoiceLog) })
        }
        preferenceFooter("The voice log keeps no words and no audio, only what happened and how long it took.")
    }
}
