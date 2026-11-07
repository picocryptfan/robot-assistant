// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix-124c (phone-1.2.4-c): a turn taller than the panel's list shows its top (the reply's first line), not its card's foot. */
class NewestTopOffsetTest {
    @Test fun aTallTurnScrollsToItsTop() {
        // The reply over a three-item Shopping card, 900 px, in 700 px of list with the keyboard up: 200 px in from its foot.
        assertEquals(200, newestTopOffset(900, 700))
    }

    @Test fun aTurnThatFitsKeepsItsFoot() {
        assertEquals(0, newestTopOffset(400, 700))
        assertEquals(0, newestTopOffset(700, 700))
        assertEquals(0, newestTopOffset(900, 0))
    }
}
