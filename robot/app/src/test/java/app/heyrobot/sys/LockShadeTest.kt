package app.heyrobot.sys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-126-review 1: each quiet run's notice under its own id, so one never replaces another unread. */
class LockShadeTest {
    private val base = LockShade.SILENT_BASE

    @Test fun aSecondQuietNoticeTakesAnotherId() {
        assertEquals(base, LockShade.silentId(emptyMap()))
        assertEquals(base + 1, LockShade.silentId(mapOf(base to 1_000L)))
        // One read and gone: its id is free again.
        assertEquals(base, LockShade.silentId(mapOf(base + 1 to 1_000L)))
    }

    @Test fun allShownTheOldestGoes() {
        val shown = (0 until LockShade.SILENT_SLOTS).associate { base + it to 10_000L - it }
        assertEquals(base + LockShade.SILENT_SLOTS - 1, LockShade.silentId(shown))
    }

    @Test fun theIdsClashWithNoOtherNotice() {
        val ids = base until base + LockShade.SILENT_SLOTS
        val others = listOf(LockShade.READY, LockShade.SUMMARY, LockShade.PLAY, LockShade.CONTINUE, LockShade.SILENT) +
            (LockShade.SKILL_BASE until LockShade.SKILL_BASE + LockShade.SKILL_SLOTS)
        assertTrue(others.none { it in ids })
    }
}
