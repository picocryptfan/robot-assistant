// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationForgottenTest {
    // review-week: a forgotten conversation held its wake lock with no end but the battery.
    @Test fun aSessionWithNothingHeardEndsAndTheWakeLockCantOutliveIt() {
        assertFalse(ConversationRules.forgotten(1_000, 1_000 + ConversationRules.FORGOTTEN_MS - 1))
        assertTrue(ConversationRules.forgotten(1_000, 1_000 + ConversationRules.FORGOTTEN_MS))
        // Renewed each minute: a stuck session lets go on its own, a running one never gaps.
        assertTrue(ConversationRules.WAKE_HOLD_MS in 120_000..ConversationRules.FORGOTTEN_MS)
    }

    @Test fun itsStopReasonHasACode() {
        assertEquals(ConvStop.FORGOTTEN, ConvStop.entries.single { it.id == 9 })
    }
}
