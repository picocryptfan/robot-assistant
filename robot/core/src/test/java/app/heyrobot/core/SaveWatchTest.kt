// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveWatchTest {
    // review-week: a full disk made every save fail in silence. It is told once a run, not at every save.
    @Test fun aRunOfFailedSavesIsToldOnceAndAgainOnlyAfterOneWorked() {
        val w = SaveWatch()
        assertEquals(listOf(false, true, false, false, false, true), listOf(true, false, false, false, true, false).map(w::tell))
    }
}
