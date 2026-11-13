// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.logbook

import app.heyrobot.core.logbook.LogEntry
import app.heyrobot.core.logbook.LogbookCommand
import app.heyrobot.core.logbook.LogbookHeard
import app.heyrobot.core.logbook.LogbookRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZonedDateTime

/** fix-stage-spoken-log M1: the panel shown locked and kept through the unlock never answers "unlock first" from a stale read. */
class PanelLogbookLockedTest {
    private val now = ZonedDateTime.now()
    private val stale = LogbookHeard(on = true, empty = false, stems = emptySet(), units = emptySet(), locked = true)
    private val unlocked = LogbookHeard.of(true, listOf(LogEntry("a", now.minusHours(1).toInstant().toEpochMilli(), "walked the dog", added = 1_000)))

    @Test fun lockedWhenReadButUnlockedNowReadsAgain() {
        var reads = 0
        val h = PanelLogbook.current(stale, lockedNow = false) { reads++; unlocked }
        assertEquals(1, reads)
        assertFalse(h.locked)
        // No entry matches: memory and LastTime keep it, as with any unlocked log.
        assertNull(LogbookRouter.route("when did I last take my pills?", h, now))
        assertTrue(LogbookRouter.route("when did I last walk the dog?", h, now) is LogbookCommand.Last)
    }

    @Test fun stillLockedKeepsTheLockedAnswerWithoutARead() {
        val h = PanelLogbook.current(stale, lockedNow = true) { fail("read while locked"); unlocked }
        assertTrue(h.locked)
        assertTrue(LogbookRouter.route("when did I last take my pills?", h, now) is LogbookCommand.Last)
    }

    @Test fun anUnlockedReadIsNotReadAgain() {
        assertSame(unlocked, PanelLogbook.current(unlocked, lockedNow = false) { fail("read again"); null })
        assertSame(LogbookHeard.OFF, PanelLogbook.current(null, lockedNow = false) { fail("read before the first refresh"); null })
    }
}
