package app.heyrobot.people

import app.heyrobot.core.Person
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-seal-14 M2: while locked, People reads back as nobody; HoldTap goes by the last unlocked read, kept in memory. */
class HoldPeopleTest {
    private val ann = Person("p1", "Ann Smith", aliases = listOf("Mom"))
    private val hour = 3_600_000L

    @Test fun lockedWithACachedListSomeoneInPeopleIsntHeld() {
        val h = HoldPeople(60_000L)
        assertTrue(h.knows("Ann Smith", 0L) { listOf(ann) })
        assertTrue(h.knows("Ann Smith", hour) { null })
        assertTrue(h.knows("Mom", hour + 1) { null })
        assertFalse(h.knows("Bob Jones", hour + 2) { null })
    }

    /** fix-bug-card-14-b L1: locked 24 hours since the last unlocked read, the list is dropped and messages are held again. */
    @Test fun lockedADayTheListIsDroppedAndTheMessageIsHeld() {
        val h = HoldPeople(60_000L)
        assertTrue(h.knows("Ann Smith", 0L) { listOf(ann) })
        assertTrue(h.knows("Ann Smith", 23 * hour) { null })
        assertFalse(h.knows("Ann Smith", 24 * hour + 1) { null })
        assertFalse(h.knows("Ann Smith", 23 * hour) { null })
        assertTrue(h.knows("Ann Smith", 24 * hour + 2) { listOf(ann) })
    }

    @Test fun lockedWithNoCacheTheMessageIsHeld() {
        assertFalse(HoldPeople().knows("Ann Smith", 0L) { null })
    }

    @Test fun aChangeOrTheNextUnlockedReadReplacesIt() {
        val h = HoldPeople(60_000L)
        assertTrue(h.knows("Ann Smith", 0L) { listOf(ann) })
        h.remember(emptyList(), 1_000L)
        assertFalse(h.knows("Ann Smith", hour) { null })
        assertTrue(h.knows("Ann Smith", hour + 1) { listOf(ann) })
    }
}
