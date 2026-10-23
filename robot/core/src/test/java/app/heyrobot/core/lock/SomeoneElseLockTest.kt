// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.memory.LockedMemory
import app.heyrobot.core.memory.MemoryAccess
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.LockedMessages
import app.heyrobot.core.messages.MessageAccess
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SomeoneElseLockTest {
    @Test fun messagesReadAsUnlockFirstDuringSomeoneElsesLock() {
        for (l in MessagesLocked.entries) {
            val s = MessageSettings(locked = l)
            val narrowed = SomeoneElseLock.messages(s, someoneElse = true)
            assertEquals(MessagesLocked.OFF, narrowed.locked)
            assertEquals(LockedMessages.UNLOCK_FIRST, MessageAccess.whenLocked(true, narrowed))
            // Everything else stays as chosen.
            assertEquals(s.copy(locked = MessagesLocked.OFF), narrowed)
        }
    }

    @Test fun messagesAsChosenOtherwise() {
        for (l in MessagesLocked.entries) {
            val s = MessageSettings(locked = l)
            assertSame(s, SomeoneElseLock.messages(s, someoneElse = false))
        }
    }

    @Test fun memoryOffTheLockScreenDuringSomeoneElsesLock() {
        val s = MemorySettings(onLockScreen = true, speakSecrets = true)
        val narrowed = SomeoneElseLock.memory(s, someoneElse = true)
        assertEquals(false, narrowed.onLockScreen)
        assertEquals(s.copy(onLockScreen = false), narrowed)
        assertNotEquals(LockedMemory.USE, MemoryAccess.whenLocked(true, narrowed, "what do you remember about me"))
        assertEquals(LockedMemory.USE, MemoryAccess.whenLocked(true, s, "what do you remember about me"))
        assertSame(s, SomeoneElseLock.memory(s, someoneElse = false))
        val off = MemorySettings(onLockScreen = false)
        assertSame(off, SomeoneElseLock.memory(off, someoneElse = true))
    }
}
