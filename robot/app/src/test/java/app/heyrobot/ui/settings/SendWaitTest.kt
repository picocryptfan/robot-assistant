// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.SentReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// RP6: Send counts only once an app is picked (Reports you sent holds the reference). Back in the share sheet keeps the
// card as it was: no "Sent", the turn and the screenshot still there for a second Send.
class SendWaitTest {
    private fun sent(ref: String) = SentReport(ref, 1L, FeedbackKind.BUG, emptySet())

    @Test fun backInTheShareSheetIsNotSent() {
        val w = SendWait()
        var done = 0
        w.start("RFB-AAAAAAAA") { done++ }
        assertFalse(w.check(emptyList()))
        assertFalse(w.check(listOf(sent("RFB-BBBBBBBB"))))
        assertEquals(0, done)
        assertTrue(w.check(listOf(sent("RFB-AAAAAAAA"))))
        assertFalse(w.check(listOf(sent("RFB-AAAAAAAA"))))
        assertEquals(1, done)
    }

    @Test fun theChooserOpeningDropsNothing() {
        val src = File("src/main/java/app/heyrobot/ui/settings/FeedbackSettings.kt").readText()
        val ok = src.substringAfter("send = { o, done ->").substringBefore("share = { o ->").substringAfter("ok -> ").substringBefore("\n")
        assertTrue(ok, ok.startsWith("{ ReportOut.schedule(app); wait.start(ref)"))
    }
}
