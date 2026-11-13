// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.heyrobot.core.helpers.HelperChoice

class ScreenQuestionTest {
    @Test fun thePlainWaysToAskAreTheScreenQuestion() {
        for (q in listOf("What's on my screen?", "what’s on my screen", "What is on the screen?", "whats on this screen", "what's on screen",
            "Robot, what's on my screen right now?", "can you read my screen please", "Tell me what's on my screen.", "describe the screen",
            "summarize this screen", "what do you see on my screen?", "what does my screen say", "what's this screen about?", "What am I looking at?",
            "Qu'est-ce qu'il y a sur mon écran ?", "Qu’y a-t-il sur mon écran?", "dis-moi ce qu'il y a sur l'écran", "lis mon écran", "décris cet écran",
            "c'est quoi sur mon écran", "que vois-tu sur mon écran", "qu'est-ce que je regarde")) {
            assertTrue(q, ScreenQuestion.asks(q))
        }
    }

    /** fix-stage-choice-route: the helper-choice phrases and the screen question never take each other's words. */
    @Test fun helperChoicePhrasesAndTheScreenQuestionDontOverlap() {
        val names = listOf(HelperName.CLAUDE, HelperName("jarvis", listOf(listOf("jarvis")), phonetic = true))
        for (q in listOf("always ask Jarvis", "always ask the helper for these", "stop asking Claude", "ask me again", "keep these here",
            "ask Jarvis instead", "try the helper", "what does the helper think", "demande toujours à Jarvis", "redemande-moi")) {
            assertTrue(q, HelperChoice.command(q, names) != null || HelperChoice.instead(q, names) != null)
            assertFalse(q, ScreenQuestion.asks(q))
        }
        for (q in listOf("What's on my screen?", "can you read my screen please", "what's this screen about?", "What am I looking at?",
            "Qu'est-ce qu'il y a sur mon écran ?", "lis mon écran", "qu'est-ce que je regarde")) {
            assertTrue(q, ScreenQuestion.asks(q))
            assertEquals(q, null, HelperChoice.command(q, names))
            assertEquals(q, null, HelperChoice.instead(q, names))
        }
    }

    @Test fun anythingElseIsNot() {
        for (q in listOf("", "open Home", "open my screen recorder", "what's on my calendar?", "what's on my screen and open Signal", "ask Jarvis about my screen",
            "ask Jarvis what's on my screen", "turn off my screen", "screen time", "how much screen time today", "what is a screen", "read my messages",
            "ouvre mon écran", "what's on TV")) {
            assertFalse(q, ScreenQuestion.asks(q))
        }
    }

    @Test fun onlyWithTheScreenAttachedIsItAnswerOnly() {
        assertTrue(ScreenQuestion.answerOnly("What's on my screen?", Attachment.SCREEN))
        assertTrue(ScreenQuestion.answerOnly(Prompt.defaultQuestion(Attachment.SCREEN, false), Attachment.SCREEN))
        assertFalse(ScreenQuestion.answerOnly("save that as a note", Attachment.SCREEN))
        assertFalse(ScreenQuestion.answerOnly("What's on my screen?", Attachment.NONE))
        assertFalse(ScreenQuestion.answerOnly("What's on my screen?", Attachment.PHOTO))
    }

    @Test fun whereNoChipShowsItSaysWhy() {
        assertEquals(ScreenAccess.swipeHint(true), ScreenAccess.hiddenHint(locked = false, textOn = true))
        assertEquals("Unlock your phone first, then ask about your screen.", ScreenAccess.hiddenHint(locked = true, textOn = true).text)
    }
}
