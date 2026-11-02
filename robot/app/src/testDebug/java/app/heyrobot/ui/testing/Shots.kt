// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
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
 * setContent for a test that takes screenshots: a focused field's cursor stays on instead of blinking. The blink runs on
 * the test clock, and waitUntil moves that clock for as long as a real thread takes (a list's write on Dispatchers.IO, on
 * a busy machine), so an unsteady cursor made a golden pass or fail by the machine's load (docs/ops/reports/flakes.md).
 */
fun ComposeContentTestRule.setShotContent(content: @Composable () -> Unit) {
    registerIdlingResource(IoIdling)
    setContent { CompositionLocalProvider(LocalCursorBlinkEnabled provides false, content = content) }
}

/**
 * The rule's idle doesn't count work on `Dispatchers.IO`, where every page reads its files, so a test could read a page
 * before the page had read its file. Idle now also means no worker is running: parked with nothing to do, or waiting on
 * something a test holds (a latch), counts as free, and it must stay so for [QUIET_MS], as a task just sent can take that
 * long to wake its worker.
 */
internal object IoIdling : androidx.compose.ui.test.IdlingResource {
    private const val QUIET_MS = 3L
    private var quietSince = 0L
    private var lastLook = 0L

    private fun busy(): Boolean = Thread.getAllStackTraces().any { (t, stack) ->
        t.name.startsWith("DefaultDispatcher-worker") && t.state != Thread.State.WAITING && t.state != Thread.State.BLOCKED &&
            stack.none { it.className.endsWith("CoroutineScheduler\$Worker") && (it.methodName == "park" || it.methodName == "tryPark") }
    }

    override val isIdleNow: Boolean
        get() {
            val now = System.nanoTime() / 1_000_000
            val gap = now - lastLook
            lastLook = now
            if (busy() || gap > 50) { quietSince = now; return false }
            return now - quietSince >= QUIET_MS
        }
}

/**
 * Shows [content] in Robot's theme ([dark] or light) on the theme's surface; in the classic look's type when [classic],
 * as the panel's parts are drawn.
 */
fun ComposeContentTestRule.show(dark: Boolean = false, classic: Boolean = false, content: @Composable () -> Unit) {
    setShotContent {
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
