// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.web.WebGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The job's loop (review-signal-later 3 and 4): claimed in one change before anything goes, stopped when Android stops
// it, the connection checked again before each question, and nothing sent while web search is off.
class LaterRunTest {
    private class Fake(items: List<LaterItem>) : LaterPort {
        var items = items
        var online = true
        var stopAfter = Int.MAX_VALUE
        var gateOf: (LaterItem) -> LaterGate = { LaterGate.Goes }
        var during: (LaterItem) -> Unit = {}
        val searched = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val answered = mutableListOf<String>()
        var updates = 0

        @Synchronized override fun update(change: (List<LaterItem>) -> List<LaterItem>): List<LaterItem>? { updates++; items = change(items); return items }
        override fun online() = online
        override fun stopped() = searched.size >= stopAfter
        override fun gate(item: LaterItem) = gateOf(item)
        override fun search(item: LaterItem): String { searched += item.id; during(item); return "Sailings at 9" }
        override fun ask(item: LaterItem) { asked += item.id }
        override fun answered(item: LaterItem, text: String) { answered += item.id }
        override fun now() = 1_000L
    }

    private fun item(id: String, at: Long = 0, asks: Boolean = false) = LaterItem(id, "ferry $id", "ferry $id", Lang.EN, at, asks)
    private fun state(f: Fake, id: String) = f.items.single { it.id == id }.state

    @Test fun eachRunsOnceOldestFirstAndIsAnswered() {
        val f = Fake(listOf(item("b", at = 20), item("a", at = 10)))
        assertFalse(LaterRun.run(f))
        assertEquals(listOf("a", "b"), f.searched)
        assertEquals(listOf("a", "b"), f.answered)
        assertEquals("Sailings at 9", f.items.single { it.id == "a" }.answer)
        assertFalse(LaterRun.run(f))
        assertEquals(2, f.searched.size)
    }

    @Test fun claimedBeforeTheSearchSoASecondRunNeverSearchesItAgain() {
        val f = Fake(listOf(item("a", at = 10), item("b", at = 20)))
        // A restarted job runs while the first is mid-search (a dropped connection, then a new one).
        var nested = false
        f.during = { if (!nested) { nested = true; assertEquals(LaterState.ANSWERED, state(f, "a")); LaterRun.run(f) } }
        LaterRun.run(f)
        assertEquals(listOf("a", "b"), f.searched.sorted())
        assertEquals(2, f.searched.size)
    }

    @Test fun aCrashMidSearchLeavesItAnsweredWithNothingAndItNeverGoesAgain() {
        val f = Fake(listOf(item("a")))
        f.during = { throw IllegalStateException("killed") }
        try { LaterRun.run(f) } catch (_: IllegalStateException) {}
        assertEquals(LaterState.ANSWERED, state(f, "a"))
        assertNull(f.items.single().answer)
        f.during = {}
        LaterRun.run(f)
        assertEquals(listOf("a"), f.searched)
    }

    @Test fun aQuestionCancelledWhileAnotherRunsNeverGoes() {
        val f = Fake(listOf(item("a", at = 10), item("b", at = 20)))
        f.during = { if (it.id == "a") f.update { all -> Later.drop(all, setOf("b")) } }
        LaterRun.run(f)
        assertEquals(listOf("a"), f.searched)
    }

    @Test fun androidStoppingTheJobStopsTheLoop() {
        val f = Fake(listOf(item("a", at = 10), item("b", at = 20)))
        f.stopAfter = 1
        assertTrue(LaterRun.run(f))
        assertEquals(listOf("a"), f.searched)
        assertEquals(LaterState.WAITING, state(f, "b"))
    }

    @Test fun theConnectionIsCheckedAgainBeforeAnythingGoes() {
        val f = Fake(listOf(item("a", at = 10), item("b", at = 20)))
        f.online = false
        assertTrue(LaterRun.run(f))
        assertTrue(f.searched.isEmpty())
        assertEquals(0, f.updates)
        f.online = true
        f.during = { f.online = false }
        assertTrue(LaterRun.run(f))
        assertEquals(listOf("a"), f.searched)
        assertEquals(LaterState.WAITING, state(f, "b"))
    }

    @Test fun webSearchOffDropsItAndNothingGoes() {
        val f = Fake(listOf(item("a")))
        f.gateOf = { LaterGate.Off(WebGate.OFF_TURN_ON) }
        assertFalse(LaterRun.run(f))
        assertTrue(f.searched.isEmpty() && f.asked.isEmpty())
        assertTrue(f.items.isEmpty())
    }

    @Test fun anAskFirstQuestionWaitsForThePanel() {
        val f = Fake(listOf(item("a", at = 10, asks = true), item("b", at = 20)))
        f.gateOf = { if (it.id == "b") LaterGate.Asks else LaterGate.Goes }
        LaterRun.run(f)
        assertTrue(f.searched.isEmpty())
        assertEquals(listOf("a", "b"), f.asked)
        assertEquals(listOf(LaterState.READY, LaterState.READY), f.items.map { it.state })
    }
}
