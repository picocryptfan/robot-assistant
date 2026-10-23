// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.str
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
    val footer = stringResource(if (s.keepMedia) R.string.history_footer_media else R.string.history_footer_no_media)
    PreferenceScreen(UiStrings.history().str(), onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar(stringResource(R.string.history_save), s.saveHistory) { update(s.copy(saveHistory = it)) } }
        preferenceGroup(null, "rows") {
            PreferenceItem(stringResource(R.string.history_media),
                stringResource(if (s.keepMedia) R.string.history_media_kept else R.string.history_media_not_kept), RIcons.Image,
                enabled = s.saveHistory, onClick = { a.openPage(Page.Safety) })
            PreferenceItem(stringResource(R.string.history_delete_all), stringResource(R.string.history_delete_all_detail), RIcons.Delete,
                onClick = { confirm = true })
        }
        preferenceFooter(footer)
    }
    if (confirm) {
        ConfirmDialog(stringResource(R.string.history_delete_title), stringResource(R.string.history_delete_text),
            stringResource(R.string.history_delete_confirm), onDismiss = { confirm = false }) {
            confirm = false
            a.deleteAllConversations()
        }
    }
}
