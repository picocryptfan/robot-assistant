// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.heyrobot.core.RobotSettings
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › History (0.4.x redesign): saving conversations as the page's main switch, whether pictures are
// kept (set in Safety & privacy), and Delete all conversations, which works whether saving is on or not.

@Composable
fun HistorySettingsScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    PreferenceScreen("History", onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar("Save conversations", s.saveHistory) { update(s.copy(saveHistory = it)) } }
        preferenceGroup(null, "rows") {
            PreferenceItem("Screenshots and photos", if (s.keepMedia) "Kept with the conversations" else "Not kept", RIcons.Image,
                enabled = s.saveHistory, onClick = { a.openPage(Page.Safety) })
            PreferenceItem("Delete all conversations", "Everything in History", RIcons.Delete, onClick = { confirm = true })
        }
        preferenceFooter(
            "Kept in Robot's private storage on this phone; a conversation about your screen only once you tap Keep. " +
                (if (s.keepMedia) "Thumbnails and screen text are kept too (Safety & privacy)."
                else "Photos and screen contents aren't saved (Safety & privacy)."),
        )
    }
    if (confirm) {
        ConfirmDialog("Delete all conversations?", "This can't be undone.", "Delete all", onDismiss = { confirm = false }) {
            confirm = false
            a.deleteAllConversations()
        }
    }
}
