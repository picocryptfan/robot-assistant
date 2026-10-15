// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import app.heyrobot.core.LookSettings
import app.heyrobot.ui.theme.ClassicTheme
import app.heyrobot.ui.theme.RobotTheme
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * Screenshots use Robot's own colours (Robolectric's "wallpaper" palette is fixed, not the phone's), so the goldens in
 * src/testDebug/screenshots only change when the drawing does. Record them with -Droborazzi.record=true.
 */
const val SHOTS = "src/testDebug/screenshots"

/** The phone the screenshots are taken on: about a Pixel's width in dp, at 2x. */
const val PHONE = "w411dp-h891dp-xhdpi"

/**
 * Shows [content] in Robot's theme ([dark] or light) on the theme's surface; in the classic look's type when [classic],
 * as the panel's parts are drawn.
 */
fun ComposeContentTestRule.show(dark: Boolean = false, classic: Boolean = false, content: @Composable () -> Unit) {
    setContent {
        RobotTheme(dark = dark, look = LookSettings(wallpaperColors = false)) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxWidth()) { if (classic) ClassicTheme(content) else content() }
            }
        }
    }
}

/** Compares the whole screen (dialogs included) with the golden [name].png. */
fun ComposeContentTestRule.shot(name: String) {
    waitForIdle()
    onRoot().captureRoboImage("$SHOTS/$name.png")
}
