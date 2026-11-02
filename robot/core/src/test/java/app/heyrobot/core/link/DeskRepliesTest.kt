// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** fb13: a lock clears the shade with the answer already marked said; owed, the newest comes back, and a newer unsaid one comes first. */
class DeskRepliesTest {
    private val pairing = LinkIds.newId()
    private fun line(done: Long, seen: Boolean) = DeskLine(DeskEntry(LinkIds.newId(), pairing, DeskWay.REPLY, 0, DeskState.REPLIED, 1, done, seen = seen), null)

    @Test fun theNewestNotYetSaidFirst() {
        val new = line(2, false)
        val lines = listOf(line(1, false), new, line(3, true))
        assertEquals(new, DeskReplies.due(lines, owed = false))
        assertEquals(new, DeskReplies.due(lines, owed = true))
    }

    @Test fun anAnswerAlreadySaidComesBackOnlyWhenOwed() {
        val newest = line(2, true)
        val lines = listOf(line(1, true), newest)
        assertNull(DeskReplies.due(lines, owed = false))
        assertEquals(newest, DeskReplies.due(lines, owed = true))
        assertNull(DeskReplies.due(emptyList(), owed = true))
    }
}
