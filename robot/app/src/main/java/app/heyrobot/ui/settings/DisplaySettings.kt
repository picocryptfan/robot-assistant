// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SettingsStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.str
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
    val colours = stringResource(R.string.display_colours)
    val footer = stringResource(R.string.display_footer)
    PreferenceScreen(UiStrings.display().str(), onBack) {
        preferenceGroup(colours, "colours") {
            SwitchPreference(stringResource(R.string.display_wallpaper),
                stringResource(if (look.wallpaperColors) R.string.display_wallpaper_on else R.string.display_wallpaper_off), look.wallpaperColors) {
                onChange(look.copy(wallpaperColors = it))
            }
        }
        preferenceFooter(footer)
    }
}

/** [displaySummary] in words without a language yet: "Wallpaper colours" or "Robot's colours". */
fun displaySummaryText(l: LookSettings): Phrase =
    if (l.wallpaperColors) SettingsStrings.wallpaperColours() else SettingsStrings.robotColours()
