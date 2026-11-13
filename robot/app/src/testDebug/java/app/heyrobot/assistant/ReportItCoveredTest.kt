package app.heyrobot.assistant

import android.view.MotionEvent
import app.heyrobot.ui.common.CoveredTouches
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review L3: the panel's Report it refuses a tap another app's window hid part of the screen for, as Car mode's Yes does. */
class ReportItCoveredTest {
    @Test fun aCoveredTapOnReportItOpensNoCard() {
        val asked = ArrayList<String?>()
        val actions = object : PanelActions by RecordingActions() {
            override fun report(replyId: String?) { asked += replyId }
        }
        val covered = CoveredTouches()
        val tap = noticeAction(NoticeAction.REPORT, actions, replyId = "r1", covered = covered)!!.second
        covered.see(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        tap()
        assertEquals(emptyList<String?>(), asked)
        covered.see(MotionEvent.ACTION_DOWN, 0)
        tap()
        assertEquals(listOf<String?>("r1"), asked)
        covered.see(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        tap()
        assertEquals(listOf<String?>("r1"), asked)
    }
}
