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

    @Test fun aQuestionThatAddsAToolGroupRebuildsTheConversationOnce() {
        val m = ToolMode.TOOLS_CONSTRAINED
        val core = Prompt.CORE_TOOLS.map { it.id }
        val people = ToolRouter.tools(setOf(ToolGroup.CORE, ToolGroup.PEOPLE), RobotSettings()).map { it.id }
        assertTrue(BrainPolicy.canReuse(live(), 4, "S", m, 100, core))
        assertFalse(BrainPolicy.canReuse(live(), 4, "S", m, 100, people))
        // Built with the new tools, the next question reuses it again.
        assertTrue(BrainPolicy.canReuse(live().copy(tools = people), 4, "S", m, 100, people))
    }

    /** 0.1.2 review, minor 5: a parked engine must be closed even after its service is gone, and never doubled. */
    @Test fun aParkedEngineOutlivesTheServiceButNotForEver() {
        assertEquals(EngineTeardown.Step.QUIT_THREAD, EngineTeardown.afterDestroy(parked = false, sinceDestroyMs = 0))
        // Parked at destroy: the work thread stays for LiteRT's callback (quitting it dropped that callback in 0.1.2).
        assertEquals(EngineTeardown.Step.WAIT, EngineTeardown.afterDestroy(parked = true, sinceDestroyMs = 0))
        assertEquals(EngineTeardown.Step.WAIT, EngineTeardown.afterDestroy(parked = true, sinceDestroyMs = EngineTeardown.PARKED_LIMIT_MS - 1))
        assertEquals(EngineTeardown.Step.END_PROCESS, EngineTeardown.afterDestroy(parked = true, sinceDestroyMs = EngineTeardown.PARKED_LIMIT_MS))
        // The callback closed it in time.
        assertEquals(EngineTeardown.Step.QUIT_THREAD, EngineTeardown.afterDestroy(parked = false, sinceDestroyMs = 5_000))
        // A new BrainService in the same process does not load a second engine beside a parked one.
        assertTrue(EngineTeardown.mayLoad(0))
        assertFalse(EngineTeardown.mayLoad(1))
    }

    /**
     * 0.4.1 review: nor beside one an earlier BrainService is still loading or letting go of (its unload runs once its
     * current call returns); if that never happens, the process ends rather than holding two models or none for ever.
     */
    @Test fun oneEngineInTheProcessWhicheverServiceLoadsIt() {
        assertEquals(EngineTeardown.LoadStep.LOAD, EngineTeardown.forLoad(heldByAnother = false, sinceHolderDestroyedMs = -1))
        assertEquals(EngineTeardown.LoadStep.WAIT, EngineTeardown.forLoad(heldByAnother = true, sinceHolderDestroyedMs = -1))
        assertEquals(EngineTeardown.LoadStep.WAIT, EngineTeardown.forLoad(heldByAnother = true, sinceHolderDestroyedMs = 0))
        assertEquals(EngineTeardown.LoadStep.WAIT, EngineTeardown.forLoad(heldByAnother = true, sinceHolderDestroyedMs = EngineTeardown.PARKED_LIMIT_MS - 1))
        assertEquals(EngineTeardown.LoadStep.END_PROCESS, EngineTeardown.forLoad(heldByAnother = true, sinceHolderDestroyedMs = EngineTeardown.PARKED_LIMIT_MS))
    }

    /**
     * 0.4.4: two engines, one at a time. A GGUF file goes to llama.cpp, anything else to LiteRT-LM; asking for the other
     * engine's kind of file unloads the one in use first, and the process's one slot (above) still keeps the new one
     * waiting while the old one lets go (an engine of another kind holds it like any other).
     */
    @Test fun eachKindOfFileHasItsEngineAndTheOtherUnloadsFirst() {
        assertEquals(EngineKind.LLAMA_CPP, EngineKind.of(ModelFormat.GGUF))
        assertEquals(EngineKind.LITERT_LM, EngineKind.of(ModelFormat.LITERT_LM))
        assertEquals(EngineSwitch.Step.LOAD, EngineSwitch.step(null, EngineKind.LLAMA_CPP))
        assertEquals(EngineSwitch.Step.LOAD, EngineSwitch.step(EngineKind.LITERT_LM, EngineKind.LITERT_LM))
        assertEquals(EngineSwitch.Step.LOAD, EngineSwitch.step(EngineKind.LLAMA_CPP, EngineKind.LLAMA_CPP))
        assertEquals(EngineSwitch.Step.UNLOAD_OTHER_FIRST, EngineSwitch.step(EngineKind.LITERT_LM, EngineKind.LLAMA_CPP))
        assertEquals(EngineSwitch.Step.UNLOAD_OTHER_FIRST, EngineSwitch.step(EngineKind.LLAMA_CPP, EngineKind.LITERT_LM))
        // The other engine still letting its model go holds the slot: the load waits ("still stopping"), as in 0.4.1.
        assertEquals(EngineTeardown.LoadStep.WAIT, EngineTeardown.forLoad(heldByAnother = true, sinceHolderDestroyedMs = -1))
    }

    /** llama.cpp's real count (the model's own tokenizer) decides what fits in Robot's 4,096 tokens, with room for the reply. */
    @Test fun aGgufPromptFitsByTheModelsOwnCount() {
        val fits = ContextWindow.MAX_TOKENS - ContextWindow.REPLY_RESERVE
        assertEquals(PromptFit.Step.Send, PromptFit.after(fits, historyTurns = 6, budget = 2000, rebuilds = 0))
        // Over by 100 with history: shorter history, by the overflow and at least a tenth.
        assertEquals(PromptFit.Step.Shorten(1800), PromptFit.after(fits + 100, historyTurns = 6, budget = 2000, rebuilds = 0))
        assertEquals(PromptFit.Step.Shorten(1500), PromptFit.after(fits + 500, historyTurns = 6, budget = 2000, rebuilds = 2))
        // Out of rebuilds: no history at all, then the question alone.
        assertEquals(PromptFit.Step.Shorten(0), PromptFit.after(fits + 100, historyTurns = 2, budget = 500, rebuilds = BrainPolicy.MAX_REBUILDS))
        // The question alone, a little over the app's estimate: sent with a shorter reply; with no room for one, too long.
        assertEquals(PromptFit.Step.Send, PromptFit.after(fits + 200, historyTurns = 0, budget = 0, rebuilds = 0))
        assertEquals(PromptFit.Step.Send, PromptFit.after(ContextWindow.MAX_TOKENS - PromptFit.MIN_REPLY, historyTurns = 0, budget = 0, rebuilds = 0))
        assertEquals(PromptFit.Step.TooLong, PromptFit.after(ContextWindow.MAX_TOKENS - PromptFit.MIN_REPLY + 1, historyTurns = 0, budget = 0, rebuilds = 0))
        // Each rebuild drops something, so the loop ends.
        var budget = 3000
        var rebuilds = 0
        var turns = 10
        while (true) {
            when (val step = PromptFit.after(4000, turns, budget, rebuilds)) {
                is PromptFit.Step.Shorten -> { assertTrue(step.historyBudget < budget); budget = step.historyBudget; rebuilds++; if (budget == 0) turns = 0 }
                else -> { assertEquals(PromptFit.Step.TooLong, step); break }
            }
            assertTrue(rebuilds <= BrainPolicy.MAX_REBUILDS + 1)
        }
    }

    /** Robot's llama.cpp library needs dotprod, i8mm and fp16; :brain reads /proc/cpuinfo before it loads it. */
    @Test fun theLibraryLoadsOnlyOnACpuWithItsFeatures() {
        // A Tensor G5 core (Cortex-X4 / A725 / A520, Armv9.2-A), as /proc/cpuinfo lists them.
        val g5 = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 " +
            "sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm " +
            "svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 dgh bti mte ecv afp mte3 wfxt"
        val cpuinfo = (0 until 8).joinToString("\n\n") { "processor\t: $it\nBogoMIPS\t: 49.15\n$g5\nCPU implementer\t: 0x41" }
        assertEquals(emptyList<String>(), LlamaCpu.missing(cpuinfo))
        // A Cortex-A55 (Armv8.2-A, dotprod but no i8mm): refused.
        val a55 = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
        assertEquals(listOf("i8mm"), LlamaCpu.missing(a55))
        // Every core must have them (a big core with i8mm beside a little one without isn't enough).
        assertEquals(listOf("i8mm"), LlamaCpu.missing(cpuinfo + "\n\n" + a55))
        // "svei8mm" isn't "i8mm".
        assertEquals(listOf("i8mm"), LlamaCpu.missing("Features : fp asimd asimddp fphp asimdhp svei8mm"))
        assertEquals(LlamaCpu.REQUIRED, LlamaCpu.missing(null))
        assertEquals(LlamaCpu.REQUIRED, LlamaCpu.missing("processor : 0"))
    }

    /** A chat template gets user and model turns alternating, whatever Robot's history holds. */
    @Test fun aGgufTemplateGetsAlternatingTurns() {
        fun u(t: String) = PromptTurn(PromptRole.USER, t)
        fun m(t: String) = PromptTurn(PromptRole.MODEL, t)
        assertEquals(listOf(u("a"), m("b")), PromptFit.alternating(listOf(u("a"), m("b"))))
        // A question whose reply failed (not replayed), then another: only the later one, with its reply.
        assertEquals(listOf(u("q2"), m("r2")), PromptFit.alternating(listOf(u("q1"), u("q2"), m("r2"))))
        // Robot's reply, then Claude's: one model turn.
        assertEquals(listOf(u("q"), m("mine\n\n[Claude answered] theirs")), PromptFit.alternating(listOf(u("q"), m("mine"), m("[Claude answered] theirs"))))
        // A leading model turn, and a last question that got no reply, never reach the template.
        assertEquals(listOf(u("q"), m("r")), PromptFit.alternating(listOf(m("hello"), u("q"), m("r"), u("unanswered"))))
        assertEquals(emptyList<PromptTurn>(), PromptFit.alternating(listOf(u("unanswered"))))
        assertEquals(emptyList<PromptTurn>(), PromptFit.alternating(emptyList()))
        // Whatever the mix, the result alternates, starts with a user turn and ends with a model turn.
        val rnd = java.util.Random(5)
        repeat(200) {
            val h = (0 until rnd.nextInt(12)).map { if (rnd.nextBoolean()) u("u$it") else m("m$it") }
            val a = PromptFit.alternating(h)
            a.forEachIndexed { i, t -> assertEquals(h.toString(), if (i % 2 == 0) PromptRole.USER else PromptRole.MODEL, t.role) }
            assertTrue(a.isEmpty() || a.last().role == PromptRole.MODEL)
        }
    }
}
