// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** fix-what-did-i-miss, finding 2: with Messages off, What did I miss? still gets its access row, and "still allowed" never invites taking it back. */
class MissedAccessRowTest {
    @Test fun missedOnWithMessagesOffShowsTheRowItNeeds() {
        assertEquals(AccessRow.NEEDED_FOR_MISSED, AccessRow.of(messagesOn = false, missedOn = true, granted = false))
        // Access in use by What did I miss?: no row inviting you to take it back.
        assertNull(AccessRow.of(messagesOn = false, missedOn = true, granted = true))
    }

    @Test fun theMessagesRowsAreAsBefore() {
        assertEquals(AccessRow.NEEDED, AccessRow.of(true, true, false))
        assertEquals(AccessRow.ALLOWED, AccessRow.of(true, false, true))
        assertEquals(AccessRow.STILL_ALLOWED, AccessRow.of(false, false, true))
        assertNull(AccessRow.of(false, false, false))
    }
}

/** fix-what-did-i-miss, finding 8: an app picked and then uninstalled leaves the pick at Done (it's no longer listed to untick). */
class MissedPickTest {
    @Test fun doneDropsAnAppNoLongerInstalled() {
        assertEquals(listOf("a.mail", "b.signal"), MissedPick.done(listOf("a.mail", "b.signal", "c.bank"), setOf("b.signal", "gone.app", "a.mail")))
    }
}
