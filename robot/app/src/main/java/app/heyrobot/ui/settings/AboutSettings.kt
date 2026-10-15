// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

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

// MAIN PROCESS. Settings › About (0.4.x redesign): the version, the setup guide (1.0), Updates (the built-in updater, 0.6.0), Send feedback (0.7)
// and the Licences (0.4.5), and room for more rows in the same group.

@Composable
fun AboutSettingsScreen(a: AppActions, onBack: () -> Unit) {
    PreferenceScreen("About", onBack) {
        preferenceGroup(null, "about") {
            PreferenceItem("Version", "Robot™ ${BuildConfig.VERSION_NAME}")
            // 1.0: the setup guide again, where Finish later left it or from Welcome.
            PreferenceItem(stringResource(R.string.guide_about_row), stringResource(R.string.guide_about_detail), onClick = a.openGuide)
            // The built-in updater (0.6.0): an update on offer, or when Robot last checked.
            UpdatesEntry(a)
            // 0.7: a bug report or an idea, as plain text you send with the app you choose.
            PreferenceItem("Send feedback", "A bug or an idea, to the developer: you choose the app it goes with",
                onClick = { a.openPage(Page.Settings(SettingsRoutes.feedback(FeedbackEntry.ABOUT))) })
            PreferenceItem("Licences", "Robot's licence and source code, and every part it uses with its licence and notices",
                onClick = { a.openPage(Page.Licences) })
            // More rows of About go here, one line each.
        }
        preferenceFooter(
            "Free software under the GNU GPL version 3, with NO WARRANTY. It uses LiteRT-LM, sherpa-onnx with espeak-ng, ONNX Runtime, " +
                "Jetpack Compose and more, and downloads models such as Gemma 4 and Parakeet when you ask: each with its licence, " +
                "credits and notices under Licences.",
        )
        // Trademark claim (13 Oct 2026): unregistered marks, so ™ and never ®.
        preferenceFooter("Robot™ and Robot Assistant™ are trademarks of the Robot project.", "trademark")
    }
}
