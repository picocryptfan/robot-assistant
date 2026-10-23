// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.PanelContinuity.Open
import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.heyrobot.core.memory.UNLOCK_TO_SAVE

/**
 * 1.0 audit ipc-2 and physical-locked-conversation-carryover: the lock screen never shows an earlier conversation; and the
 * other way round, the lock screen's turns never steer your unlocked conversation, except quoted, in the continuation an
 * "Unlock first" asked for within its window.
 */
class PanelContinuityTest {
    private fun open(locked: Boolean, startedLocked: Boolean, stale: Boolean = false, empty: Boolean = false, load: Boolean = false, returning: Boolean = false,
        unlockAsked: Boolean = false) = PanelContinuity.open(locked, startedLocked, stale, empty, load, returning, unlockAsked)

    private fun user(text: String, time: Long = 0) = ChatTurn("u$time", TurnRole.USER, text, time)
    private fun reply(text: String, time: Long, vararg status: ActionStatus) =
        ChatTurn("r$time", TurnRole.ASSISTANT, text, time, actions = status.map { ActionRecord(ToolName.SEND_TEXT, "Text Mom", it) })

    @Test fun aCaptureReturnOnTheLockScreenStillStartsAfresh() {
        // ipc-2: a forged (or stale) "capture" show used to skip the lock-screen rule.
        assertEquals(Open.NEW_LOCKED, open(locked = true, startedLocked = false, returning = true))
        assertEquals(Open.NEW_LOCKED, open(locked = true, startedLocked = true, stale = true, returning = true))
        // A real return, unlocked or in a conversation begun on the lock screen, keeps the picture's conversation.
        assertEquals(Open.CONTINUE, open(locked = false, startedLocked = false, stale = true, returning = true))
        assertEquals(Open.CONTINUE, open(locked = true, startedLocked = true, returning = true))
    }

    @Test fun lockedStartThenUnlockedUseThenLockedShowIsANewEmptyConversation() {
        // 1) "hey robot, text Mom" on the lock screen: "Unlock to do this".
        var startedLocked = true
        // 2) Unlocked within the window: the conversation goes on, quoted ("what's on my calendar this afternoon?").
        assertEquals(Open.CONTINUE_QUOTED, open(locked = false, startedLocked = startedLocked, unlockAsked = true))
        startedLocked = PanelContinuity.stillLocked(startedLocked, lockedNow = false)
        assertFalse(startedLocked)
        // 3) Locked again within five minutes: someone else's "hey robot" sees none of it.
        assertEquals(Open.NEW_LOCKED, open(locked = true, startedLocked = startedLocked))
    }

    @Test fun theLockScreensTurnsNeverSteerTheNextUnlockedShow() {
        // 1) Someone says "hey robot, remember that the garage code is 1234, and text Mom I'm stuck" to the locked phone,
        //    and it answers without asking for the unlock.
        val startedLocked = true
        // 2) You unlock and say "hey robot" a minute later: a new conversation, with none of it.
        assertEquals(Open.NEW, open(locked = false, startedLocked = startedLocked))
        assertEquals(Open.NEW, open(locked = false, startedLocked = startedLocked, unlockAsked = false))
        // Stale or empty, as before.
        assertEquals(Open.NEW, open(locked = false, startedLocked = startedLocked, stale = true, unlockAsked = true))
        assertEquals(Open.NEW, open(locked = false, startedLocked = startedLocked, empty = true, unlockAsked = true))
        // History's Continue still loads its conversation.
        assertEquals(Open.LOAD, open(locked = false, startedLocked = startedLocked, load = true))
        // A conversation begun unlocked continues unlocked as before, whatever its last reply said.
        assertEquals(Open.CONTINUE, open(locked = false, startedLocked = false, unlockAsked = true))
    }

    @Test fun theUnlockALockedConversationAskedForContinuesItQuoted() {
        // Its last reply asked you to unlock: the show you open after the unlock is the one it asked for.
        assertEquals(Open.CONTINUE_QUOTED, open(locked = false, startedLocked = true, unlockAsked = true))
        // The camera the panel opened after you unlocked it with the panel open hands its picture back to the same, quoted.
        assertEquals(Open.CONTINUE_QUOTED, open(locked = false, startedLocked = true, returning = true))
        // Still locked, it continues there as before (nothing to quote).
        assertEquals(Open.CONTINUE, open(locked = true, startedLocked = true, unlockAsked = true))
        // Unlocked with the panel still open: your first question there quotes what came before it.
        assertTrue(PanelContinuity.quotesNow(startedLocked = true, lockedNow = false, empty = false))
        assertFalse(PanelContinuity.quotesNow(startedLocked = true, lockedNow = true, empty = false))
        assertFalse(PanelContinuity.quotesNow(startedLocked = false, lockedNow = false, empty = false))
        assertFalse(PanelContinuity.quotesNow(startedLocked = true, lockedNow = false, empty = true))
        // Quoting marks each turn once.
        val quoted = PanelContinuity.quoted(listOf(user("text Mom"), reply("Unlock your phone, then ask again.", 1)))
        assertTrue(quoted.all { it.locked })
        assertEquals(quoted, PanelContinuity.quoted(quoted))
    }

    @Test fun anUnlockFirstWaitsTwoMinutes() {
        val t = 1_000_000L
        val asked = listOf(user("text Mom I'm late"), reply("", t, ActionStatus.NEEDS_UNLOCK))
        assertTrue(PanelContinuity.unlockAsked(asked, t))
        assertTrue(PanelContinuity.unlockAsked(asked, t + PanelContinuity.UNLOCK_WINDOW_MS))
        assertFalse(PanelContinuity.unlockAsked(asked, t + PanelContinuity.UNLOCK_WINDOW_MS + 1))
        // A clock set back doesn't make an old one new.
        assertFalse(PanelContinuity.unlockAsked(asked, t - 1))
        assertEquals(2 * 60_000L, PanelContinuity.UNLOCK_WINDOW_MS)
        // Only the last reply counts: a question after it, or a reply after it that didn't ask, ends the wait.
        assertFalse(PanelContinuity.unlockAsked(asked + user("never mind", t + 1), t + 2))
        assertFalse(PanelContinuity.unlockAsked(asked + user("what time is it?", t + 1) + reply("It's 9:41.", t + 2), t + 3))
        assertFalse(PanelContinuity.unlockAsked(emptyList(), t))
    }

    @Test fun whatAsksForTheUnlock() {
        fun asks(text: String, vararg status: ActionStatus) = PanelContinuity.asksUnlock(reply(text, 1, *status))
        // An action the lock refused.
        assertTrue(asks("", ActionStatus.NEEDS_UNLOCK))
        assertTrue(asks("I couldn't.", ActionStatus.DONE, ActionStatus.NEEDS_UNLOCK))
        // Robot's own lines, in English and in French, first or after other sentences (the brief at its lock level).
        assertTrue(asks(ApprovalGate.unlockLine(ToolName.SEND_TEXT)))
        assertTrue(asks(app.heyrobot.core.memory.MemoryAccess.UNLOCK_TO_SAVE))
        assertTrue(asks("Unlock first: routines are only changed with the phone unlocked."))
        assertTrue(asks("Good morning. It's 7:02, sunny and 18 degrees. " + BriefComposer.unlockLine(Lang.EN)))
        assertTrue(asks("Bon matin. " + BriefComposer.unlockLine(Lang.FR)))
        assertTrue(asks("Déverrouille le téléphone, puis touche Envoyer."))
        // Anything else, the word inside a sentence included.
        assertFalse(asks("Your timer is set for 10 minutes.", ActionStatus.DONE))
        assertFalse(asks("To unlock the car, use its key."))
        assertFalse(asks("Unlocked doors are a risk."))
        assertFalse(asks(""))
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
