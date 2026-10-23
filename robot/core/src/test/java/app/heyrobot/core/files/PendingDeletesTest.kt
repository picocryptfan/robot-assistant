// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4 FX2 (files-1.4-review.md K1): a delete you tapped waits for its Undo, out of every list, then goes, or goes at once when asked. */
class PendingDeletesTest {
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val held = PendingDeletes(runner = { it() }, later = { ms, f -> timers += ms to f; { timers.removeAll { it.second === f } } })
    private val deleted = ArrayList<List<FileId>>()
    private val a = FileId.of("a".repeat(32))!!
    private val b = FileId.of("b".repeat(32))!!

    @Test fun hiddenUntilItsUndoRunsOutThenDeleted() {
        val seen = ArrayList<Set<FileId>>()
        held.watch { seen += it }
        val done = ArrayList<Boolean>()
        val t = held.start(listOf(a), delete = { deleted += it; true }) { done += it }
        assertEquals(PendingDeletes.UNDO_MS, timers.single().first)
        assertEquals(setOf(a), held.hidden())
        assertTrue(deleted.isEmpty())
        assertTrue(held.waiting(t))
        timers.single().second()
        assertEquals(listOf(listOf(a)), deleted)
        assertEquals(listOf(true), done)
        assertFalse(held.waiting(t))
        // Gone stays out of the lists (its id is never listed again), and its Undo is too late.
        assertEquals(setOf(a), held.hidden())
        assertFalse(held.undo(t))
        assertEquals(setOf(a), seen.last())
    }

    @Test fun undoInTimeDeletesNothing() {
        val t = held.start(listOf(a), delete = { deleted += it; true })
        assertTrue(held.undo(t))
        assertTrue(timers.isEmpty())
        assertEquals(emptySet<FileId>(), held.hidden())
        held.commitAll()
        assertTrue(deleted.isEmpty())
        assertFalse(held.undo(t))
    }

    @Test fun commitAllDeletesWhatWaitsNow() {
        held.start(listOf(a), delete = { deleted += it; true })
        held.start(listOf(b), delete = { deleted += it; true })
        held.commitAll()
        assertEquals(listOf(listOf(a), listOf(b)), deleted)
        assertTrue(timers.isEmpty())
        assertEquals(setOf(a, b), held.hidden())
    }

    @Test fun aDeleteThatFailsIsListedAgain() {
        val done = ArrayList<Boolean>()
        held.start(listOf(a), delete = { false }) { done += it }
        held.start(listOf(b), delete = { throw IllegalStateException() }) { done += it }
        held.commitAll()
        assertEquals(listOf(false, false), done)
        assertEquals(emptySet<FileId>(), held.hidden())
    }
}
