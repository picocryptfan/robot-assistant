// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.online.Later
import app.heyrobot.core.online.LaterItem
import app.heyrobot.core.online.LaterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The panel's take-off (review-signal-later-b 3): a question waiting for your OK is sent once, never twice from two
// overlapping shows, and never after it was cancelled.
class PanelLaterTakeOffTest {
    private fun i(id: String, state: LaterState) = LaterItem(id, "Look up the $id times", "$id times", Lang.EN, 0, true, state, null)

    @Test fun sentOnceAndNeverAfterACancel() {
        val ready = i("ferry", LaterState.READY)
        val waiting = i("bus", LaterState.WAITING)
        val next = Later.Send(setOf(ready.id), ready)
        val (left, sent) = PanelLater.takeOff(listOf(ready, waiting), next)
        assertEquals(ready, sent)
        assertEquals(listOf(waiting), left)
        // A second show read the same queue before the first took it off: nothing more goes.
        assertNull(PanelLater.takeOff(left, next).second)
        // Cancelled between the read and the take-off: gone, so nothing goes.
        assertNull(PanelLater.takeOff(listOf(waiting), next).second)
    }
}
