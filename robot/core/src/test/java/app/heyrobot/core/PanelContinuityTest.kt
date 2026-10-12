package app.heyrobot.core

import app.heyrobot.core.PanelContinuity.Open
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0 audit ipc-2 and physical-locked-conversation-carryover: the lock screen never shows an earlier conversation. */
class PanelContinuityTest {
    private fun open(locked: Boolean, startedLocked: Boolean, stale: Boolean = false, empty: Boolean = false, load: Boolean = false, returning: Boolean = false) =
        PanelContinuity.open(locked, startedLocked, stale, empty, load, returning)

    @Test fun aCaptureReturnOnTheLockScreenStillStartsAfresh() {
        // ipc-2: a forged (or stale) "capture" show used to skip the lock-screen rule.
        assertEquals(Open.NEW_LOCKED, open(locked = true, startedLocked = false, returning = true))
        assertEquals(Open.NEW_LOCKED, open(locked = true, startedLocked = true, stale = true, returning = true))
        // A real return, unlocked or in a conversation begun on the lock screen, keeps the picture's conversation.
        assertEquals(Open.CONTINUE, open(locked = false, startedLocked = false, stale = true, returning = true))
        assertEquals(Open.CONTINUE, open(locked = true, startedLocked = true, returning = true))
    }

    @Test fun lockedStartThenUnlockedUseThenLockedShowIsANewEmptyConversation() {
        // 1) "hey robot, set a timer" on the lock screen.
        var startedLocked = true
        // 2) Unlocked within five minutes: the conversation continues ("what's on my calendar this afternoon?").
        assertEquals(Open.CONTINUE, open(locked = false, startedLocked = startedLocked))
        startedLocked = PanelContinuity.stillLocked(startedLocked, lockedNow = false)
        assertFalse(startedLocked)
        // 3) Locked again within five minutes: someone else's "hey robot" sees none of it.
        assertEquals(Open.NEW_LOCKED, open(locked = true, startedLocked = startedLocked))
    }

    @Test fun aConversationOnlyEverUsedLockedContinuesThere() {
        var startedLocked = true
        startedLocked = PanelContinuity.stillLocked(startedLocked, lockedNow = true)
        assertTrue(startedLocked)
        assertEquals(Open.CONTINUE, open(locked = true, startedLocked = startedLocked))
        assertEquals(Open.NEW_LOCKED, open(locked = true, startedLocked = startedLocked, stale = true))
    }

    @Test fun unlockedShowsAsBefore() {
        assertEquals(Open.LOAD, open(locked = false, startedLocked = false, load = true))
        assertEquals(Open.NEW_LOCKED, open(locked = true, startedLocked = false, load = true))
        assertEquals(Open.NEW, open(locked = false, startedLocked = false, stale = true))
        assertEquals(Open.NEW, open(locked = false, startedLocked = true, empty = true))
        assertEquals(Open.CONTINUE, open(locked = false, startedLocked = false))
    }
}
