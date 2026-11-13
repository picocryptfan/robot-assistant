// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.SearchModel
import app.heyrobot.core.memory.SearchModelLogic.STATUS_FAILED
import app.heyrobot.core.memory.SearchModelLogic.STATUS_PAUSED
import app.heyrobot.core.memory.SearchModelLogic.STATUS_RUNNING
import app.heyrobot.core.memory.SearchModelLogic.STATUS_SUCCESSFUL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchModelLogicTest {
    private fun st(verified: Boolean = false, checking: Boolean = false, dl: Int? = null, wifi: Boolean = false, staged: Long = 0, failed: Boolean = false) =
        SearchModelLogic.state(verified, checking, dl, wifi, staged, failed)

    @Test fun states() {
        assertEquals(SearchModelState.NONE, st())
        assertEquals(SearchModelState.DOWNLOADING, st(dl = STATUS_RUNNING))
        assertEquals(SearchModelState.DOWNLOADING, st(dl = STATUS_PAUSED))
        assertEquals(SearchModelState.WAITING_FOR_WIFI, st(dl = STATUS_PAUSED, wifi = true))
        assertEquals(SearchModelState.DOWNLOADED, st(dl = STATUS_SUCCESSFUL, staged = SearchModel.BYTES))
        // A finished download of the wrong size is never checked as the model.
        assertEquals(SearchModelState.FAILED, st(dl = STATUS_SUCCESSFUL, staged = 10))
        assertEquals(SearchModelState.FAILED, st(dl = STATUS_FAILED))
        assertEquals(SearchModelState.FAILED, st(failed = true))
        assertEquals(SearchModelState.CHECKING, st(checking = true, dl = STATUS_SUCCESSFUL, staged = SearchModel.BYTES))
        assertEquals(SearchModelState.READY, st(verified = true, failed = true))
    }

    @Test fun markNamesTheHashAndTheFile() {
        val m = SearchModelLogic.mark(SearchModel.BYTES, 1234)
        assertTrue(m.startsWith(SearchModel.SHA256))
        assertTrue(SearchModelLogic.vouched(m, SearchModel.BYTES, 1234))
        assertFalse(SearchModelLogic.vouched(m, SearchModel.BYTES, 1235))
        assertFalse(SearchModelLogic.vouched(m, SearchModel.BYTES - 1, 1234))
        assertFalse(SearchModelLogic.vouched(null, SearchModel.BYTES, 1234))
    }

    @Test fun roomAndPercent() {
        assertTrue(SearchModelLogic.room(1L shl 32, 0))
        assertFalse(SearchModelLogic.room(1L shl 32, (1L shl 32) - SearchModel.BYTES))
        assertFalse(SearchModelLogic.room(-1, 0))
        assertEquals(0, SearchModelLogic.percent(-5))
        assertEquals(50, SearchModelLogic.percent(SearchModel.BYTES / 2))
        assertEquals(100, SearchModelLogic.percent(SearchModel.BYTES * 2))
    }
}
