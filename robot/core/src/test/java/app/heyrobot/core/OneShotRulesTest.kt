// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A one-shot request (docs/conversation-mode.md 4.2, phase 5.4): its own conversation, isolated from the assistant's
 * chat, with no tools whatever the model or the engine's tool level, and budgets from the loaded model's context.
 */
class OneShotRulesTest {
    private val line = BrainMsg.OneShot(OneShotKind.TRANSLATE, Priority.INTERPRETER, Prompt.TRANSLATE_FR_EN, "On se reprend-tu demain?", 120)

    @Test fun itsConversationHoldsNothingButItsOwnSystemPrompt() {
        val c = OneShotRules.conversation(line)
        assertEquals(Prompt.TRANSLATE_FR_EN, c.system)
        assertTrue(c.history.isEmpty())
        assertTrue(c.tools.isEmpty())
        assertFalse(c.constrained)
        // Chat only: a call it writes never reaches the app, and the tool ladder doesn't apply to it.
        assertEquals(ToolMode.CHAT_ONLY, c.mode)
        assertFalse(BrainPolicy.acceptsCalls(c.mode))
        assertTrue(BrainPolicy.toolsFor(c.mode, ToolName.entries).isEmpty())
        // Whatever the request says it is for, the same: a summary step too.
        val map = BrainMsg.OneShot(OneShotKind.SUMMARY_MAP, Priority.SUMMARY, Prompt.SUMMARY_MAP, "Part 1 of 1 (14:00–14:05):\n[14:00] User (en): hi", 250)
        assertEquals(OneShotRules.Conv(Prompt.SUMMARY_MAP, emptyList(), ToolMode.CHAT_ONLY, emptyList(), false), OneShotRules.conversation(map))
    }

    @Test fun theAssistantsLiveConversationIsNeverReusedForIt() {
        // A one-shot never enters :brain's table of live conversations; and a conversation built for one would never be
        // taken for the panel's (another system prompt, no tools, chat only, no history).
        val c = OneShotRules.conversation(line)
        val asIfLive = LiveConv(historyCount = 0, system = c.system, mode = c.mode, dirty = false, awaitingTool = false, tokens = 60, tools = emptyList())
        assertFalse(BrainPolicy.canReuse(asIfLive, historyCount = 4, system = Prompt.system(java.time.ZonedDateTime.of(2026, 10, 11, 14, 0, 0, 0, java.time.ZoneOffset.UTC), locked = false), mode = ToolMode.TOOLS_CONSTRAINED,
            newTurnTokens = 20))
    }

    @Test fun itsReplyCapAndFitFollowTheLoadedContext() {
        assertEquals(120, OneShotRules.maxChunks(line, 4096))
        val long = line.copy(maxChunks = 700)
        assertEquals(700, OneShotRules.maxChunks(long, 4096))
        // A short-context model (0.4.3): at most a quarter of its context for the reply.
        assertEquals(512, OneShotRules.maxChunks(long, 2048))
        assertTrue(OneShotRules.fits(line, 2048))
        val part = line.copy(kind = OneShotKind.SUMMARY_MAP, text = "mot ".repeat(1_200), system = Prompt.SUMMARY_MAP)
        assertTrue(OneShotRules.fits(part, 4096))
        assertFalse(OneShotRules.fits(part, 2048))
    }

    @Test fun aFailureIsNeverAStepDownNorSentAgain() {
        assertEquals(BrainPolicy.Next.Stop(DoneReason.LENGTH), OneShotRules.afterError("context is full", hadOutput = true))
        assertEquals(BrainPolicy.Next.Fail(BrainError.TOO_LONG), OneShotRules.afterError("Context window exceeded", hadOutput = false))
        // The tool-parse signature means nothing here: no tool was described.
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), OneShotRules.afterError("Failed to parse tool calls", hadOutput = false))
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), OneShotRules.afterError(null, hadOutput = true))
    }
}
