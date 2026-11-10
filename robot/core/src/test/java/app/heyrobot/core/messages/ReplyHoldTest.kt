package app.heyrobot.core.messages

import app.heyrobot.core.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-car-132: taps run through a reply's 3 seconds. A fake window stands in for PanelMessages' coroutine and makes the
 * calls PanelMessages makes on its ReplyHold, in the order PanelController makes them (CarStaysTest pins that wiring).
 */
class ReplyHoldTest {
    private enum class S { WINDOW, WAITING, SENDING, DONE }

    private class Run(val inSession: Boolean = true) {
        val hold = ReplyHold()
        var state = S.WINDOW
        var windowOn = true
        var sends = 0
        var hides = 0
        val aloud = mutableListOf<Boolean>()

        fun inWindow() = state == S.WINDOW && windowOn
        /** PanelMessages.stop(forApp), from PanelController.stopGenerating. */
        fun stop(forApp: Boolean) { if (hold.stopStops(forApp, inWindow())) stopWindow() }
        private fun stopWindow() { if (state == S.WINDOW) { windowOn = false; state = S.WAITING } }
        /** PanelMessages.onHide(forApp). */
        fun hide(forApp: Boolean) { if (hold.hide(forApp, inWindow())) { hides++; stopWindow() } }
        fun show() = hold.shown()
        /** fire: once per card (a second tap or a yes finds it no longer waiting); the result said by name or not. */
        fun fire(sessionOn: Boolean) {
            if (state != S.WAITING && state != S.WINDOW) return
            state = S.SENDING
            sends++
            aloud += hold.aloud(inSession, sessionOn)
            state = S.DONE
        }
        /** The 3 seconds end: the reply goes unless stopped, then afterWindow. */
        fun windowEnds(sessionOn: Boolean = true) {
            if (state == S.WINDOW && windowOn) fire(sessionOn)
            windowOn = false
            if (hold.windowEnded()) hide(forApp = false)
        }
        /** A car screen tap that opens an app beside Robot (an app, Maps, the fourth button): leaveForRobot's stop, the hide, the hide's own stop. */
        fun tapOpensApp() {
            stop(forApp = true)
            hide(forApp = true)
            stop(forApp = true)
        }
    }

    @Test fun aTapThatOpensAnAppLetsTheReplyGoOnceAndNamesNoOneAloud() {
        val r = Run()
        r.tapOpensApp()
        assertEquals(S.WINDOW, r.state)
        assertEquals(0, r.hides)
        r.windowEnds()
        assertEquals(1, r.sends)
        // You're in the app: "wrote again" or "gone" says no name and no message.
        assertEquals(listOf(false), r.aloud)
        // The hide it held runs once the reply went.
        assertEquals(1, r.hides)
        r.fire(sessionOn = true)
        assertEquals(1, r.sends)
    }

    @Test fun twoTapsInTheWindowSendOnce() {
        val r = Run()
        r.tapOpensApp()
        r.tapOpensApp()
        r.windowEnds()
        r.windowEnds()
        assertEquals(1, r.sends)
        assertEquals(1, r.hides)
    }

    @Test fun theStopButtonOrAClosedPanelStopsTheReply() {
        val b = Run()
        b.stop(forApp = false)
        b.windowEnds()
        assertEquals(0, b.sends)
        assertEquals(S.WAITING, b.state)
        val c = Run()
        c.hide(forApp = false)
        c.windowEnds()
        assertEquals(0, c.sends)
        assertEquals(1, c.hides)
    }

    @Test fun inRobotTheResultIsSaidByName() {
        val r = Run()
        r.windowEnds()
        assertEquals(listOf(true), r.aloud)
        // The headset went: no name.
        val gone = Run()
        gone.windowEnds(sessionOn = false)
        assertEquals(listOf(false), gone.aloud)
        // Back in Robot before the 3 seconds end: said by name, and no hide left to run.
        val back = Run()
        back.tapOpensApp()
        back.show()
        back.windowEnds()
        assertEquals(listOf(true), back.aloud)
        assertEquals(0, back.hides)
    }

    @Test fun wordsThatCleanToNothingWerentHeard() {
        assertFalse(SpeechText.heardWhole("\uD83C\uDF89\uD83C\uDF89"))
        assertTrue(SpeechText.heardWhole("On my way \uD83C\uDF89"))
        assertTrue(SpeechText.heardWhole(" "))
    }

    /** fix-car-133: a hide while the reply is on its way (after its 3 seconds, Send or a yes): its result names no one aloud, its notice waits for the next open. */
    @Test fun aHideWhileTheReplyIsOnItsWayNamesNoOneAndKeepsItsNotice() {
        for (forApp in listOf(true, false)) {
            val h = ReplyHold()
            assertTrue(h.hide(forApp, inWindow = false, sending = true))
            assertFalse(h.aloud(inSession = false, sessionOn = false))
            h.notice("r1", S.SENDING, "Not sent")
            assertEquals("Not sent", h.shown())
            assertTrue(h.aloud(inSession = false, sessionOn = false))
            assertNull(h.shown())
        }
        // Nothing on its way: a close says the result as before and keeps no notice.
        val h = ReplyHold()
        assertTrue(h.hide(forApp = false, inWindow = false, sending = false))
        assertTrue(h.aloud(inSession = false, sessionOn = false))
        h.notice("r1", S.DONE, "Not sent")
        assertNull(h.shown())
    }

    /** fix-car-133: "Unlock your phone, then tap Send." goes once its card is sent another way (the car screen's Yes). */
    @Test fun aHeldNoticeGoesWhenItsCardMovesOn() {
        val h = ReplyHold()
        h.hide(forApp = true, inWindow = true)
        h.notice("r1", S.WAITING, "Unlock")
        h.card("r2", S.SENDING)
        h.card("r1", S.WAITING)
        h.card("r1", S.SENDING)
        assertNull(h.shown())
        h.hide(forApp = true, inWindow = true)
        h.notice("r1", S.WAITING, "Unlock")
        h.card("r2", S.SENDING)
        h.card("r1", S.WAITING)
        assertEquals("Unlock", h.shown())
    }
}
