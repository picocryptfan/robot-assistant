// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.BriefItem
import app.heyrobot.core.BriefLock
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.RunOrigin
import app.heyrobot.core.StepGo
import app.heyrobot.core.SpokenPart
import app.heyrobot.core.StepCode
import app.heyrobot.core.lists.ListsSettings
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
    @Test fun routinesReadAtTimeAndWeatherAtMostDuringSomeoneElsesLock() {
        val all = RoutineSettings(briefLock = BriefLock.EVERYTHING, runLocked = false)
        assertEquals(all.copy(briefLock = BriefLock.TIME_WEATHER, runLocked = true), SomeoneElseLock.routines(all, someoneElse = true))
        assertEquals(BriefLock.NOTHING, SomeoneElseLock.routines(all.copy(briefLock = BriefLock.NOTHING), someoneElse = true).briefLock)
        assertSame(all, SomeoneElseLock.routines(all, someoneElse = false))
    }

    /** fb11, F6: what "run <routine>" asks the gate during someone else's lock: nothing of yours, whatever the setting says. */
    @Test fun anAskedRoutinesOwnWordsWaitDuringSomeoneElsesLock() {
        val r = Routine("r", "Test", RoutineTrigger.AlarmDismissed(), emptyList(), created = 0)
        val rs = SomeoneElseLock.routines(RoutineSettings(briefLock = BriefLock.EVERYTHING), someoneElse = true)
        for (step in listOf(RoutineStep.Say("Dentist at 9."), RoutineStep.Reminders))
            assertEquals(StepGo.WAIT_UNLOCK, RoutineGate.decide(step, RunOrigin.ASKED, true, r, RobotSettings(), rs))
        assertEquals(StepGo.RUN_PUBLIC, RoutineGate.decide(RoutineStep.Brief, RunOrigin.ASKED, true, r, RobotSettings(), rs))
    }

    @Test fun routinesDropPersonalPartsDuringSomeoneElsesLock() {
        val theirs = SpokenPart(listOf("It's 8:00."), false, StepCode.BRIEF)
        val yours = SpokenPart(listOf("Dentist at 9."), true, StepCode.BRIEF, BriefItem.CALENDAR)
        assertEquals(listOf(theirs), SomeoneElseLock.spoken(listOf(theirs, yours), someoneElse = true))
        assertEquals(listOf(theirs, yours), SomeoneElseLock.spoken(listOf(theirs, yours), someoneElse = false))
    }

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

    /** 1.4 Lists (lists-plan.md §5): during a lock for someone else both lock switches read as off; Lists itself as chosen. */
    @Test fun listsLockSwitchesOffDuringSomeoneElsesLock() {
        for (on in listOf(true, false)) for (add in listOf(true, false)) for (ls in listOf(true, false)) {
            val s = ListsSettings(on = on, addLocked = add, lockScreen = ls)
            val narrowed = SomeoneElseLock.lists(s, someoneElse = true)
            assertEquals(ListsSettings(on = on, addLocked = false, lockScreen = false), narrowed)
            assertEquals(false, narrowed.lockKey)
            assertSame(s, SomeoneElseLock.lists(s, someoneElse = false))
        }
        val off = ListsSettings(addLocked = false, lockScreen = false)
        assertSame(off, SomeoneElseLock.lists(off, someoneElse = true))
    }
}
