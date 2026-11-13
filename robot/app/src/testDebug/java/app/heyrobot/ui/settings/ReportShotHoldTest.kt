// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.core.feedback.ReportShot
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** RP4: the card's screenshot goes only on a tap, and nothing of it is kept once the card closes or the report goes. */
class ReportShotHoldTest {
    private val shot = ReportShot(4, 4, IntArray(16) { -1 })

    @Test fun noScreenshotWithoutTheTap() {
        val hold = ReportShotHold()
        hold.offer(shot, "Notes")
        assertNull(hold.png())
        hold.addHeld()
        assertNotNull(hold.png())
    }

    @Test fun theHeldScreenshotIsGoneAfterClose() {
        val hold = ReportShotHold()
        hold.offer(shot, "Notes")
        hold.addHeld()
        hold.cover(ReportShot.Box(0, 0, 2, 2))
        hold.close()
        assertNull(hold.offered)
        assertNull(hold.added)
        assertNull(hold.png())
        hold.addHeld()
        assertNull(hold.png())
    }
}
