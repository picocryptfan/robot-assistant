// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 (review-wikipedia-p4): an answer from the copy is content, so every later action asks first, as after a read tool's. */
class WikiContentTest {
    @Test fun anAnswerFromTheCopyMakesLaterActionsAskFirst() {
        val q = ChatTurn("q", TurnRole.USER, "Who was Louis Riel?", 0)
        assertFalse(TurnRules.untrusted(listOf(q)))
        assertTrue(TurnRules.untrusted(listOf(q, ChatTurn("a", TurnRole.ASSISTANT, "Louis Riel was a Métis leader.", 1, content = true))))
    }

    /** High (review-wikipedia-p4-b): the copy's own answer never keeps the next question of fact from the copy. */
    @Test fun theNextQuestionOfFactStillGoesToTheCopy() {
        val chat = listOf(ChatTurn("q", TurnRole.USER, "Who was Louis Riel?", 0), ChatTurn("a", TurnRole.ASSISTANT, "Louis Riel was a Métis leader.", 1, content = true))
        assertTrue(TurnRules.untrusted(chat))
        assertFalse(TurnRules.untrustedForCopy(chat))
        assertTrue(WikiTurn.asks("When was the Red River Resistance?", 1, false, false, TurnRules.untrustedForCopy(chat)))
        // Anything else that isn't Robot's still keeps it out: a helper's answer, messages read back.
        assertTrue(TurnRules.untrustedForCopy(chat + ChatTurn("h", TurnRole.ASSISTANT, "x", 2, fromHelper = true)))
        assertTrue(TurnRules.untrustedForCopy(chat + ChatTurn("m", TurnRole.ASSISTANT, "x", 2, readBack = true)))
    }

    /** L3 (review-wikipedia-p4): a turn on the lock screen gets the copy too (public text, nothing personal); nothing else unlocks. */
    @Test fun aLockedTurnGetsTheCopyToo() {
        val locked = listOf(ChatTurn("l", TurnRole.USER, "Who was Louis Riel?", 0, locked = true))
        assertTrue(TurnRules.untrusted(locked))
        assertFalse(TurnRules.untrustedForCopy(locked))
        assertTrue(WikiTurn.asks("Who was Louis Riel?", 1, false, false, TurnRules.untrustedForCopy(locked)))
        // Still kept out with a helper's answer or messages read on the lock screen.
        assertTrue(TurnRules.untrustedForCopy(locked + ChatTurn("h", TurnRole.ASSISTANT, "x", 1, fromHelper = true, locked = true)))
        assertTrue(TurnRules.untrustedForCopy(locked + ChatTurn("m", TurnRole.ASSISTANT, "x", 1, messages = true, locked = true)))
    }
}
