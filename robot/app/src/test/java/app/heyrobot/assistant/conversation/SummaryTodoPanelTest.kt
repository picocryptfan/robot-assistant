// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryTodoPanelTest {
    private val ask = SummaryTodoPanel.Ask("Book the furnace check", "2026-11-14")

    @Test fun takenOnceOnly() {
        val id = SummaryTodoPanel.offer(ask, 1_000)
        assertEquals(ask, SummaryTodoPanel.take(id, locked = false, now = 2_000))
        assertNull(SummaryTodoPanel.take(id, locked = false, now = 2_000))
    }

    @Test fun droppedWhenOldOrUnknown() {
        val id = SummaryTodoPanel.offer(ask, 10_000)
        assertNull(SummaryTodoPanel.take(id, locked = false, now = 10_000 + SummaryTodoPanel.WAIT_MS + 1))
        assertNull(SummaryTodoPanel.take("not-an-id", locked = false, now = 10_000))
        assertNull(SummaryTodoPanel.take(null, locked = false, now = 10_000))
    }

    @Test fun neverOverTheLockScreen() {
        val id = SummaryTodoPanel.offer(ask, 1_000)
        assertNull(SummaryTodoPanel.take(id, locked = true, now = 1_500))
        assertNull("a locked take drops it", SummaryTodoPanel.take(id, locked = false, now = 1_600))
    }

    @Test fun atMostAFewWait() {
        val ids = (1..SummaryTodoPanel.MAX + 1).map { SummaryTodoPanel.offer(ask.copy(title = "t$it"), 5_000) }
        assertNull("the oldest goes", SummaryTodoPanel.take(ids.first(), locked = false, now = 5_000))
        assertEquals("t${SummaryTodoPanel.MAX + 1}", SummaryTodoPanel.take(ids.last(), locked = false, now = 5_000)?.title)
    }

    @Test fun aLockedTakeSaysLockedNotGone() {
        val id = SummaryTodoPanel.offer(ask, 1_000)
        assertEquals(SummaryTodoPanel.Taken.Locked, SummaryTodoPanel.open(id, locked = true, now = 1_500))
        assertEquals("then it is gone", SummaryTodoPanel.Taken.Gone, SummaryTodoPanel.open(id, locked = false, now = 1_600))
        assertEquals("an unknown id while locked is gone", SummaryTodoPanel.Taken.Gone, SummaryTodoPanel.open("x", locked = true, now = 1_600))
    }

    @Test fun theEventCarriesTheAlertItsCardNames() {
        assertEquals(mapOf("title" to ask.title, "when" to ask.whenArg, "alert" to true), ask.args())
    }
}
