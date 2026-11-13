// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import app.heyrobot.assistant.car.CarWidgetScreen.Kind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which widget taps open their own screen beside the car screen, and the launcher entry on everything else. */
class CarWidgetScreenTest {
    private val pinned = "com.example.player"
    private var sent = 0

    private fun send(kind: Kind?, sdk: Int = 34, start: () -> Unit = { sent++ }) = CarWidgetScreen.sendIf(kind, pinned, sdk, start)

    @Test fun thePinnedAppsOwnMutableScreenIsSentBeside() {
        assertTrue(send(Kind(activity = true, immutable = false, creator = pinned)))
        assertEquals(1, sent)
    }

    @Test fun aBroadcastOrServiceKeepsTheLauncherEntry() {
        assertFalse(send(Kind(activity = false, immutable = false, creator = pinned)))
        assertEquals(0, sent)
    }

    @Test fun anotherAppsScreenKeepsTheLauncherEntry() {
        assertFalse(send(Kind(activity = true, immutable = false, creator = "com.example.other")))
        assertFalse(send(Kind(activity = true, immutable = false, creator = null)))
        assertEquals(0, sent)
    }

    @Test fun anImmutableScreenKeepsTheLauncherEntryAsItsFlagsWouldBeDropped() {
        assertFalse(send(Kind(activity = true, immutable = true, creator = pinned)))
        assertEquals(0, sent)
    }

    @Test fun belowAndroid12OrUnreadableKeepsTheLauncherEntry() {
        assertFalse(send(Kind(activity = true, immutable = false, creator = pinned), sdk = 30))
        assertFalse(send(null))
        assertEquals(0, sent)
    }

    /** review-car-widget-taps L2: a list row's tap carries its item as a fill-in, which Robot doesn't send: the launcher entry. */
    @Test fun aListRowsTapOpensTheLauncherEntry() {
        assertEquals("tap", CarWidgetScreen.own("tap", null))
        assertEquals(null, CarWidgetScreen.own("tap", Any()))
        assertTrue(src("CarWidgetHost.kt").contains("if (screen(intent)) open(CarWidgetScreen.own(intent, fillInIntent)) else"))
    }

    @Test fun aFailedSendFallsBackToTheLauncherEntry() {
        // Android's own exceptions can't be made without Android here; any Exception from the send is one.
        assertFalse(send(Kind(true, false, pinned)) { throw SecurityException("not exported") })
        assertFalse(send(Kind(true, false, pinned)) { throw Exception("canceled") })
    }

    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun src(p: String) = File(root, "app/src/main/java/app/heyrobot/assistant/car/$p").readText()

    @Test fun itIsSentAdjacentWithNoFillInAndTheLauncherEntryIsTheFallback() {
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, CarWidgetScreen.FLAGS)
        val host = src("CarWidgetHost.kt")
        assertTrue(host.contains("ctx.startIntentSender(s, null, FLAGS, FLAGS, 0, options())"))
        val car = src("CarModeActivity.kt")
        assertTrue(car.contains("CarWidgetHost(this) { s -> layout().widget?.let { openApp(it.app, s) } }"))
        val open = car.substringAfter("private fun openApp(").substringBefore("\n    }\n")
        // The locks first, then the widget's screen, then the launcher entry when it wasn't sent.
        assertTrue(open.indexOf("closed(CarOpen.app(") in 0 until open.indexOf("CarWidgetScreen.send("))
        assertTrue(open.contains("val opened = i != null && (screen != null && CarWidgetScreen.send(this, screen, app.pkg) || CarSplitOpen.start(this, i))"))
    }
}
