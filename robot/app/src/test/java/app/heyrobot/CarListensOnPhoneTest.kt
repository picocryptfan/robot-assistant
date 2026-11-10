// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * bugs-1.2-voice: car mode on with no car or headset connected. "hey robot" opened a panel that didn't listen until it was
 * said again, and the car screen's mic closed its panel as it opened: a session began, found no route and ended, and
 * nothing took the question. core's CarTurn.listensOnPhone says when; this holds the three places that act on it.
 */
class CarListensOnPhoneTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.let { if (File(it, "app").isDirectory) it else it.parentFile }
    private fun code(path: String) = Source.strip(File(root, "app/src/main/java/app/heyrobot/assistant/$path").readText(), keepStrings = false)

    @Test fun thePanelListensWhenTheShowAsksIt() {
        assertTrue(code("PanelController.kt").contains("handsFree?.listenNow == true ||") && code("PanelController.kt").contains("CarTurn.listensQuietly("))
    }

    @Test fun noRouteAsksForListeningInsteadOfASession() {
        val onShow = code("handsfree/PanelHandsFree.kt").substringAfter("fun onShow(").substringBefore("HandsFreeHub.begin(entry, locked || args?.getBoolean")
        assertTrue(onShow.contains("CarTurn.listensOnPhone(") && onShow.contains("listenNow = "))
    }

    /** fix-car-stay: with the car screen up, no route no longer opens the panel over it; the phone's mic hears the turn with none. */
    @Test fun noRouteKeepsTheCarScreen() {
        val prepare = code("car/CarModeController.kt").substringAfter("fun prepareShow(").substringBefore("notifyWatchers()")
        assertTrue(prepare.contains("CarTurn.quiet(on, screenUp, trusted") && !prepare.contains("route = !phone"))
    }
}
