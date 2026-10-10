package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainPolicyTest {
    // Error texts in the shape LiteRT-LM 0.18.0 throws them (absl status inside a LiteRtLmJniException message).
    private val parseError = "Failed to parse tool calls from response. Status Code: 3"
    private val overflow = "INVALID_ARGUMENT: Input token ids exceeds max sequence length 4096. Status Code: 3"
    private val npuOverflow = "Prefill failed: input exceeds NPU context size"
    private val native = "INTERNAL: NPU execution failed. Status Code: 13"

    @Test fun errorsAreClassifiedAsTheLabDoes() {
        assertTrue(EngineErrors.isToolParseError(parseError))
        assertTrue(EngineErrors.isToolParseError("could not parse FC calls"))
        // An overflow also carries "Status Code: 3" but is never a tool problem.
        assertTrue(EngineErrors.isContextFull(overflow))
        assertFalse(EngineErrors.isToolParseError(overflow))
        assertTrue(EngineErrors.isContextFull(npuOverflow))
        assertFalse(EngineErrors.isToolParseError(native))
        assertFalse(EngineErrors.isToolParseError(null))
        assertTrue(EngineErrors.isToolSetupError("Constrained decoding is not supported for this model"))
        assertFalse(EngineErrors.isToolSetupError(npuOverflow))
        assertFalse(EngineErrors.isToolSetupError(native))
    }

    @Test fun toolModesStepDownOneLevelAtATime() {
        assertEquals(ToolMode.TOOLS, ToolMode.TOOLS_CONSTRAINED.lower)
        assertEquals(ToolMode.TEXT_CALLS, ToolMode.TOOLS.lower)
        assertNull(ToolMode.TEXT_CALLS.lower)
    }

    @Test fun onlyTheToolParseErrorStepsDown() {
        val m = ToolMode.TOOLS_CONSTRAINED
        assertEquals(BrainPolicy.Next.StepDown(ToolMode.TOOLS), BrainPolicy.afterSendError(parseError, m, false, 4, 1_000, 1))
        // 0.1.0 stepped down on any error before output: an overflow or a native failure left tools off for good.
        assertEquals(BrainPolicy.Next.Shorten(500), BrainPolicy.afterSendError(overflow, m, false, 4, 1_000, 1))
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), BrainPolicy.afterSendError(native, m, false, 4, 1_000, 1))
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), BrainPolicy.afterSendError(null, m, false, 4, 1_000, 1))
        // At the bottom there is nothing to step down to.
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), BrainPolicy.afterSendError(parseError, ToolMode.TEXT_CALLS, false, 4, 1_000, 1))
    }

    @Test fun aTurnTooLongOnItsOwnFailsInsteadOfRetrying() {
        val m = ToolMode.TOOLS_CONSTRAINED
        assertEquals(BrainPolicy.Next.Fail(BrainError.TOO_LONG), BrainPolicy.afterSendError(overflow, m, false, 0, 0, 1))
        assertEquals(BrainPolicy.Next.Fail(BrainError.TOO_LONG), BrainPolicy.afterSendError(npuOverflow, m, false, 2, 300, BrainPolicy.MAX_SENDS))
    }

    @Test fun nothingIsSentTwiceOnceOutputArrived() {
        val m = ToolMode.TOOLS_CONSTRAINED
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), BrainPolicy.afterSendError(parseError, m, true, 4, 1_000, 1))
        // A context that fills up mid-reply keeps the reply, as stopped.
        assertEquals(BrainPolicy.Next.Stop(DoneReason.LENGTH), BrainPolicy.afterSendError(overflow, m, true, 4, 1_000, 1))
        // But a genuine parse error still lowers the level for the next questions.
        assertEquals(ToolMode.TOOLS, BrainPolicy.modeAfter(parseError, m))
        assertEquals(m, BrainPolicy.modeAfter(overflow, m))
        assertEquals(m, BrainPolicy.modeAfter(native, m))
    }

    @Test fun sendsAreBounded() {
        // A pasted article in 0.1.0: three prefills and the tool mode stuck at text calls. Now: bounded, mode kept.
        var mode = ToolMode.TOOLS_CONSTRAINED
        var budget = 2_000
        var turns = 6
        var sends = 1
        val seen = mutableListOf<BrainPolicy.Next>()
        while (true) {
            val next = BrainPolicy.afterSendError(overflow, mode, false, turns, budget, sends)
            seen += next
            when (next) {
                is BrainPolicy.Next.Shorten -> { budget = next.historyBudget; turns /= 2; sends++ }
                is BrainPolicy.Next.StepDown -> { mode = next.to; sends++ }
                else -> break
            }
        }
        assertEquals(ToolMode.TOOLS_CONSTRAINED, mode)
        assertTrue(sends <= BrainPolicy.MAX_SENDS)
        assertEquals(BrainPolicy.Next.Fail(BrainError.TOO_LONG), seen.last())
    }

    @Test fun creatingAConversationStepsDownOnlyForTheToolSetup() {
        val m = ToolMode.TOOLS_CONSTRAINED
        assertEquals(BrainPolicy.Next.StepDown(ToolMode.TOOLS), BrainPolicy.afterCreateError("constrained decoding unavailable", m, 2, 900, 0))
        assertEquals(BrainPolicy.Next.StepDown(ToolMode.TEXT_CALLS), BrainPolicy.afterCreateError(parseError, ToolMode.TOOLS, 2, 900, 1))
        assertEquals(BrainPolicy.Next.Shorten(450), BrainPolicy.afterCreateError(overflow, m, 2, 900, 0))
        assertEquals(BrainPolicy.Next.Fail(BrainError.TOO_LONG), BrainPolicy.afterCreateError(overflow, m, 0, 0, 0))
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), BrainPolicy.afterCreateError(native, m, 2, 900, 0))
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), BrainPolicy.afterCreateError("tool registry broken", ToolMode.TEXT_CALLS, 2, 900, 0))
        assertEquals(BrainPolicy.Next.Fail(BrainError.ENGINE), BrainPolicy.afterCreateError(parseError, m, 2, 900, 99))
    }

    private fun live(
        historyCount: Int = 4, system: String = "S", mode: ToolMode = ToolMode.TOOLS_CONSTRAINED,
        dirty: Boolean = false, awaitingTool: Boolean = false, tokens: Int = 1_000,
    ) = LiveConv(historyCount, system, mode, dirty, awaitingTool, tokens)

    @Test fun aLiveConversationIsReusedOnlyWhenNothingChanged() {
        val m = ToolMode.TOOLS_CONSTRAINED
        assertTrue(BrainPolicy.canReuse(live(), 4, "S", m, 100))
        assertFalse(BrainPolicy.canReuse(null, 4, "S", m, 100))
        assertFalse(BrainPolicy.canReuse(live(dirty = true), 4, "S", m, 100))
        assertFalse(BrainPolicy.canReuse(live(awaitingTool = true), 4, "S", m, 100))
        assertFalse(BrainPolicy.canReuse(live(historyCount = 2), 4, "S", m, 100))
        // A new day or the lock state changed the system prompt.
        assertFalse(BrainPolicy.canReuse(live(), 4, "S2", m, 100))
        // Tools stepped down since it was built: rebuild at the working level.
        assertFalse(BrainPolicy.canReuse(live(), 4, "S", ToolMode.TOOLS, 100))
        // No room for the new turn and a reply (the engine's own count), or the count is unknown.
        assertFalse(BrainPolicy.canReuse(live(tokens = 3_300), 4, "S", m, 100))
        assertFalse(BrainPolicy.canReuse(live(tokens = Int.MAX_VALUE / 2), 4, "S", m, 100))
    }
}
