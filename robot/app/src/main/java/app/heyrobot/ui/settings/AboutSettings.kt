package app.heyrobot.ui.settings

import androidx.compose.runtime.Composable
import app.heyrobot.BuildConfig
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › About (0.4.x redesign): the version, the Licences (0.4.5), and room for more rows in the same
// group (Settings › About › Updates goes there).

@Composable
fun AboutSettingsScreen(a: AppActions, onBack: () -> Unit) {
    PreferenceScreen("About", onBack) {
        preferenceGroup(null, "about") {
            PreferenceItem("Version", "Robot ${BuildConfig.VERSION_NAME}")
            PreferenceItem("Licences", "Robot's licence and source code, and every part it uses with its licence and notices",
                onClick = { a.openPage(Page.Licences) })
            // More rows of About go here, one line each (Updates: its entry row).
        }
        preferenceFooter(
            "Free software under the GNU GPL version 3, with NO WARRANTY. It uses LiteRT-LM, sherpa-onnx with espeak-ng, ONNX Runtime, " +
                "Jetpack Compose and more, and downloads models such as Gemma 4 and Parakeet when you ask: each with its licence, " +
                "credits and notices under Licences.",
        )
    }
}
