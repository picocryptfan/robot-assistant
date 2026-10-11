package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.RobotSettings
import app.heyrobot.sys.LookStore
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.NotificationAccessScreen
import app.heyrobot.ui.WeatherSettingsScreen
import app.heyrobot.ui.Snapshot

// MAIN PROCESS. Settings' sub-pages by route (Page.Settings, which survives a recreation as "settings:<route>"). New
// pages are added here, with what they need already passed in, so MainActivity never changes for them.

object SettingsRoutes {
    const val DISPLAY = "display"
    const val WEATHER = "weather"
    /** Settings › People › Notification access: the three steps. */
    const val NOTIFICATION_ACCESS = "people/notification-access"
}

/** The page for [route]; an unknown one (from a newer version's saved state) goes back to Settings. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsNav(
    route: String,
    s: RobotSettings,
    snap: Snapshot,
    calendars: List<CalendarInfo>,
    update: (RobotSettings) -> Unit,
    a: AppActions,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    when (route) {
        SettingsRoutes.DISPLAY -> {
            val ctx = LocalContext.current
            DisplaySettingsScreen(LookStore.current(ctx), onChange = { LookStore.save(ctx, it) }, onBack = onBack)
        }
        SettingsRoutes.WEATHER -> WeatherSettingsScreen(s, snap, update, a, snackbar, onBack)
        SettingsRoutes.NOTIFICATION_ACCESS -> NotificationAccessScreen(snap.facts, onBack)
        else -> LaunchedEffect(route) { onBack() }
    }
}
