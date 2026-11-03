// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * fix-car-1.2.1: car mode's screen is singleTask, so starting it while it's up only brings its task to the front, never
 * beside another app (phone-1.2.1: the map never showed). Only what starts car mode when its screen isn't showing may
 * start it: the controller (car connected, "car mode") and its notification. Anything else needs a phone test first.
 */
class CarScreenStartsTest {
    @Test fun onlyTheControllerAndTheNotificationStartTheCarScreen() {
        val root = File("src/main/java")
        val starters = root.walk().filter { it.isFile && it.extension == "kt" && it.name != "CarModeActivity.kt" }
            .filter { f -> f.readText().let { "CarModeActivity::class" in it || "car.CarModeActivity\"" in it } }
            .map { it.name }.toSortedSet()
        assertEquals(sortedSetOf("CarModeController.kt", "CarNotices.kt"), starters)
    }
}
