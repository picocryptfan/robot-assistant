// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's taps, read from the source (no Android here). RemoteViews sends every tap through the host view's context
 * with options that, since Android 14, lend the host's right to start a screen from the background
 * (setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED) in RemoteResponse.getLaunchOptions).
 */
class CarWidgetTapsSourceTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val src = File(root, "app/src/main/java/app/heyrobot/assistant/car/CarWidgetHost.kt").readText()

    private fun body(start: String): String {
        val i = src.indexOf(start)
        assertTrue(start, i >= 0)
        return src.substring(i, src.indexOf("\n\n", i).takeIf { it > 0 } ?: src.length)
    }

    @Test fun aBroadcastOrServiceGoesOutWithNoBackgroundStartModeSet() {
        val send = body("    override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?)")
        assertTrue(send.contains("if (screen(intent)) open(CarWidgetScreen.own(intent, fillInIntent)) else super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, noStarts())"))
        assertFalse("RemoteViews' own options never reach the send", send.contains("extraFlags, options)"))
        val no = body("        fun noStarts(): Bundle")
        assertTrue(no.contains("fun noStarts(): Bundle = ActivityOptions.makeBasic().toBundle()"))
        // DENIED also takes the right to start a foreground service: play on a stopped player would do nothing.
        assertFalse(no.contains("setPendingIntentBackgroundActivityStartMode"))
        assertFalse(no.contains("setPendingIntentBackgroundActivityLaunchAllowedByPermission"))
    }

    @Test fun everySendGoesThroughTheOneThatDecides() {
        assertEquals(2, Regex("override fun startIntentSender\\(").findAll(src).count())
        assertTrue(src.contains("extraFlags: Int) =\n        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null)"))
        assertEquals(1, Regex("super\\.startIntentSender\\(").findAll(src).count())
    }
}
