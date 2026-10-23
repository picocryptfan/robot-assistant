// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.str
import androidx.compose.runtime.Composable
import app.heyrobot.BuildConfig
import app.heyrobot.R
import androidx.compose.ui.res.stringResource
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › About (0.4.x redesign): the version, the setup guide (1.0), Updates (the built-in updater, 0.6.0), Send feedback (0.7),
// Support Robot (1.1) and the Licences (0.4.5), and room for more rows in the same group.

@Composable
fun AboutSettingsScreen(a: AppActions, onBack: () -> Unit) {
    val footer = stringResource(R.string.about_footer)
    val trademark = stringResource(R.string.about_trademark)
    PreferenceScreen(UiStrings.about().str(), onBack) {
        preferenceGroup(null, "about") {
            PreferenceItem(stringResource(R.string.about_version), "Robot™ ${BuildConfig.VERSION_NAME}") // l10n: not text (the app's name and version)
            // 1.0: the setup guide again, where Finish later left it or from Welcome.
            PreferenceItem(stringResource(R.string.guide_about_row), stringResource(R.string.guide_about_detail), onClick = a.openGuide)
            // The built-in updater (0.6.0): an update on offer, or when Robot last checked.
            UpdatesEntry(a)
            // 0.7: a bug report or an idea, as plain text you send with the app you choose.
            PreferenceItem(UiStrings.sendFeedback().str(), stringResource(R.string.about_feedback_detail),
                onClick = { a.openPage(Page.Settings(SettingsRoutes.feedback(FeedbackEntry.ABOUT))) })
            // 1.1: Ko-fi and the donation addresses.
            PreferenceItem(stringResource(R.string.about_support_title), stringResource(R.string.about_support_detail),
                onClick = { a.openPage(Page.Settings(SettingsRoutes.SUPPORT)) })
            PreferenceItem(UiStrings.licences().str(), stringResource(R.string.about_licences_detail),
                onClick = { a.openPage(Page.Licences) })
            // More rows of About go here, one line each.
        }
        preferenceFooter(footer)
        // Trademark claim (13 Oct 2026): unregistered marks, so ™ and never ®.
        preferenceFooter(trademark, "trademark")
    }
}
