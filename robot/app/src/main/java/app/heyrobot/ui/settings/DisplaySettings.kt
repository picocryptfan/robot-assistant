package app.heyrobot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.heyrobot.core.LookSettings
import app.heyrobot.sys.LookStore
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.NavRow
import app.heyrobot.ui.Page
import app.heyrobot.ui.SectionCard
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup

// MAIN PROCESS. Settings › Display (0.4.x UI redesign): the wallpaper's colours or Robot's own, and the Classic look,
// kept in look.json (sys/LookStore), not in settings.json. The app follows a change at once; the panel at its next
// opening. Light or dark always follows the phone.

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
        preferenceGroup("Look", "look") {
            SwitchPreference("Classic look", "The panel as in Robot 0.4: round mic, chat bubbles", look.classic) {
                onChange(look.copy(classic = it))
            }
        }
        preferenceFooter(
            "Light or dark follows your phone. The classic look brings back the assistant panel of Robot 0.4 and its type; " +
                "Settings and the other screens keep the standard look. The classic look may be removed in a later version.",
        )
    }
}

/** The Display row's state: "Wallpaper colours", or "Robot's colours · Classic look". */
fun displaySummary(l: LookSettings): String =
    listOfNotNull(if (l.wallpaperColors) "Wallpaper colours" else "Robot's colours", if (l.classic) "Classic look" else null).joinToString(" · ")

/**
 * The way into Display from today's Settings screen, a group after Weather, until Settings becomes a list of pages
 * (the redesign's next step builds Settings › Display into it and drops this).
 */
@Composable
internal fun DisplaySettingsEntry(a: AppActions) {
    val look = LookStore.current(LocalContext.current)
    SectionCard("Display") {
        NavRow(RIcons.Palette, "Display", displaySummary(look)) { a.openPage(Page.Settings(SettingsRoutes.DISPLAY)) }
    }
}
