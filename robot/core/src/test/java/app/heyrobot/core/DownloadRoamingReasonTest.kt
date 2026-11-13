// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.SearchModelLogic
import app.heyrobot.core.memory.SearchModelState
import org.junit.Assert.assertEquals
import org.junit.Test

/** fix-roaming-download: what a download held back by roaming shows (model, voice and translation lines; the search model). */
class DownloadRoamingReasonTest {
    @Test fun pausedForRoamingSaysSo() {
        assertEquals("waiting until the phone isn't roaming", Download.reasonText(Download.STATUS_PAUSED, Download.PAUSED_ROAMING))
        assertEquals("waiting for a network connection", Download.reasonText(Download.STATUS_PAUSED, 2))
    }

    @Test fun searchModelWaitsForRoamingToEnd() {
        assertEquals(SearchModelState.WAITING_ROAMING,
            SearchModelLogic.state(false, false, SearchModelLogic.STATUS_PAUSED, false, 0, false, roaming = true))
        assertEquals(SearchModelState.DOWNLOADING,
            SearchModelLogic.state(false, false, SearchModelLogic.STATUS_RUNNING, false, 0, false, roaming = true))
        assertEquals(SearchModelState.DOWNLOADING,
            SearchModelLogic.state(false, false, SearchModelLogic.STATUS_PAUSED, false, 0, false))
    }
}
