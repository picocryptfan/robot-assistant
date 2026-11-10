// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.view.MotionEvent
import app.heyrobot.Source
import app.heyrobot.ui.common.CoveredTouches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * review-panel-covered M1: car mode's Yes and the summary's Send refuse a tap while another app's window hides part of their
 * screen (MotionEvent's FLAG_WINDOW_IS_PARTIALLY_OBSCURED), which filterObscuredTouches lets through when the button itself
 * is clear. The holder, and a source check that each activity feeds it and each button asks it before it acts.
 */
class CoveredTouchesTest {
    private val partly = 0x2 // MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED (API)
    private val whole = 0x1 // MotionEvent.FLAG_WINDOW_IS_OBSCURED (API)

    @Test fun aMarkedTouchIsHeldUntilTheNextDown() {
        val c = CoveredTouches()
        c.see(MotionEvent.ACTION_DOWN, 0)
        assertFalse(c.now)
        c.see(MotionEvent.ACTION_DOWN, partly)
        assertTrue(c.now)
        c.see(MotionEvent.ACTION_MOVE, 0)
        c.see(MotionEvent.ACTION_UP, 0)
        assertTrue("held to the gesture's end", c.now)
        c.see(MotionEvent.ACTION_DOWN, 0)
        assertFalse("cleared on the next down", c.now)
        c.see(MotionEvent.ACTION_UP, whole)
        assertTrue("marked later in a gesture", c.now)
    }

    private val src = File(System.getProperty("robot.rootDir"), "app/src/main/java/app/heyrobot/assistant")
    private fun code(name: String) = Source.strip(File(src, name).readText(), keepStrings = false)

    private fun feeds(c: String, name: String) {
        val d = c.indexOf("override fun dispatchTouchEvent(")
        assertTrue("$name overrides dispatchTouchEvent", d >= 0)
        val body = c.substring(d, c.indexOf("super.dispatchTouchEvent(", d).also { assertTrue("$name calls super", it > d) })
        assertTrue("$name feeds CoveredTouches before its views see the touch", "covered.see(" in body)
    }

    /** [from]..[until] in [c], where the button's code is; [ask] must come before every way it acts. */
    private fun asksFirst(c: String, name: String, from: String, until: String, acts: List<String>) {
        val a = c.indexOf(from).also { assertTrue("$name: $from", it >= 0) }
        val part = c.substring(a, c.indexOf(until, a).also { assertTrue("$name: $until", it > a) })
        val ask = part.indexOf("covered.now")
        assertTrue("$name's button asks CoveredTouches", ask >= 0)
        for (x in acts) assertTrue("$name asks before $x", part.indexOf(x).let { it < 0 || it > ask })
        assertTrue("$name acts in that part", acts.any { it in part })
    }

    @Test fun carModesYesAsksFirst() {
        val c = code("car/CarModeActivity.kt")
        feeds(c, "CarModeActivity")
        asksFirst(c, "CarModeActivity", "private fun answer(", "private fun fourth(", listOf("PanelController.confirm(", "PanelController.reply("))
        assertTrue("only Yes is refused: Cancel and No still act", "yes && covered.now" in c)
    }

    @Test fun summarysSendAsksFirst() {
        val c = code("conversation/SummaryActivity.kt")
        feeds(c, "SummaryActivity")
        asksFirst(c, "SummaryActivity", "onSend = {", "SummaryJobs.send(", listOf("ask = null", "shareToApp(", "unlocked()"))
    }
}
