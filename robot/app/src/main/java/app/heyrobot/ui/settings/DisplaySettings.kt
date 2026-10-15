// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.runtime.Composable
import app.heyrobot.core.LookSettings
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › Display (0.4.x UI redesign): the wallpaper's colours or Robot's own, kept in look.json
// (sys/LookStore), not in settings.json. The app follows a change at once; the panel at its next opening. Light or dark
// always follows the phone. 1.0: the Classic look switch is gone (the panel is always the classic one).

/**
 * The Display page. [look] as it is now; [onChange] gets the new one (SettingsNav saves it with LookStore, tests capture
 * it).
 */
@Composable
fun DisplaySettingsScreen(look: LookSettings, onChange: (LookSettings) -> Unit, onBack: () -> Unit) {
    PreferenceScreen("Display", onBack) {
        preferenceGroup("Colours", "colours") {
            SwitchPreference("Use wallpaper colours", if (look.wallpaperColors) "Match your wallpaper" else "Robot's blue", look.wallpaperColors) {
                onChange(look.copy(wallpaperColors = it))
            }
        }
        preferenceFooter("Light or dark follows your phone.")
    }
}

/** The Display row's state: "Wallpaper colours" or "Robot's colours". */
fun displaySummary(l: LookSettings): String = if (l.wallpaperColors) "Wallpaper colours" else "Robot's colours"
