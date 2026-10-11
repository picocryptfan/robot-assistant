package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Settings › Model › "Let the model think first" (0.4.5): what each kind of model is told, and that its thinking never
 * reaches the bubble or the voice, however the reply is cut into chunks.
 */
class ThinkingTest {
    private val sys = "You are Robot."
    private fun gguf(b: ByteArray) = GgufCheck.check(ArrayByteSource(b))

    @Test fun builtInModelsAreLeftAsTheyWere() {
        for (m in listOf(BrainModel.FAST, BrainModel.SMARTER, BrainModel.UNCENSORED)) for (think in listOf(false, true)) {
            assertEquals(ThinkingPlan(), Thinking.liteRt(m, "gemma4", think))
            assertEquals(sys, Thinking.liteRt(m, "qwen3", think).system(sys))
        }
    }

    @Test fun yourOwnLiteRtLmFileIsToldWhatTheSwitchSays() {
        // Qwen3: "/no_think" at the end of the system prompt when off; nothing when on.
        assertEquals("You are Robot. /no_think", Thinking.liteRt(BrainModel.OWN, "qwen3", false).system(sys))
        assertEquals(sys, Thinking.liteRt(BrainModel.OWN, "qwen3", true).system(sys))
        assertEquals(null, Thinking.liteRt(BrainModel.OWN, "qwen3", false).enableThinking)
        // MiniCPM 5: its template's enable_thinking (LiteRT-LM 0.18's ThinkingConfig), the system prompt untouched.
        assertEquals(ThinkingPlan("", false, true), Thinking.liteRt(BrainModel.OWN, "minicpm5", false))
        assertEquals(ThinkingPlan("", true, true), Thinking.liteRt(BrainModel.OWN, "minicpm5", true))
        // Anything else is left to itself, its reply still cleaned of a <think> block.
        for (t in listOf("gemma4", "generic_model", null)) assertEquals(ThinkingPlan(stripThinking = true), Thinking.liteRt(BrainModel.OWN, t, false))
    }

    @Test fun aGgufFileIsToldThroughItsTemplateOrQwensSwitch() {
        val qwen = gguf(FakeGguf.qwen3())
        assertEquals(ThinkingPlan("", false, true), Thinking.gguf(qwen, false))
        assertEquals(ThinkingPlan("", true, true), Thinking.gguf(qwen, true))
        // A Qwen3 file whose template doesn't take enable_thinking: the soft switch instead.
        val qwenPlain = gguf(FakeGguf.qwen3(template = FakeGguf.GEMMA3_TEMPLATE))
        assertEquals("You are Robot. /no_think", Thinking.gguf(qwenPlain, false).system(sys))
        assertEquals(sys, Thinking.gguf(qwenPlain, true).system(sys))
        val moe = gguf(FakeGguf.qwen3(template = FakeGguf.GEMMA3_TEMPLATE, arch = "qwen3moe"))
        assertEquals("You are Robot. /no_think", Thinking.gguf(moe, false).system(sys))
        // MedGemma (Gemma 3) has no thinking: nothing changes, either way.
        val med = gguf(FakeGguf.medgemma())
        for (think in listOf(false, true)) assertEquals(ThinkingPlan(stripThinking = true), Thinking.gguf(med, think))
        assertEquals(ThinkingPlan(stripThinking = true), Thinking.gguf(null, false))
    }

    // ------------------------------------------------------------------ the filter

    private fun streamed(text: String, cuts: List<Int>, startsInside: Boolean = false): String {
        val f = ThinkFilter(startsInside)
        val out = StringBuilder()
        var at = 0
        for (c in cuts.sorted() + text.length) {
            out.append(f.feed(text.substring(at, c)))
            at = c
        }
        return out.append(f.flush()).toString()
    }

    @Test fun thinkingIsRemovedWholeAndTheAnswerKept() {
        val reply = "<think>\nThe user wants a timer. 5 minutes.\n</think>\n\nSure, 5 minutes."
        assertEquals("Sure, 5 minutes.", ThinkFilter.strip(reply))
        // Qwen3 with thinking off: an empty block, then the answer.
        assertEquals("Hi there!", ThinkFilter.strip("<think>\n\n</think>\n\nHi there!"))
        assertEquals("No tags at all.", ThinkFilter.strip("No tags at all."))
        // A template that opened the block in the prompt: the reply starts inside it.
        assertEquals("Answer.", ThinkFilter.strip("reasoning here\n</think>\nAnswer.", startsInside = true))
        // A block that never closes hides the rest.
        assertEquals("Before.", ThinkFilter.strip("Before.<think>and then it ran out of room"))
        assertEquals("", ThinkFilter.strip("still thinking", startsInside = true))
        // A stray closing tag never shows; text around it does.
        assertEquals("Answer.", ThinkFilter.strip("</think>\n\nAnswer."))
        // Text that only looks like a tag passes.
        assertEquals("Use <thinking> tags or <th", ThinkFilter.strip("Use <thinking> tags or <th"))
        assertEquals("a < b and c </ d", ThinkFilter.strip("a < b and c </ d"))
        // Two blocks.
        assertEquals("One. Two.", ThinkFilter.strip("<think>a</think>One. <think>b</think>Two."))
    }

    @Test fun noPartOfATagOrOfTheThinkingIsEverReleasedEarly() {
        val reply = "<think>\nPlan: call set_timer.\n</think>\n\ncall:set_timer{minutes:5}"
        val rnd = Random(7)
        repeat(300) {
            val cuts = (0 until rnd.nextInt(8)).map { rnd.nextInt(reply.length + 1) }
            assertEquals(cuts.toString(), "call:set_timer{minutes:5}", streamed(reply, cuts))
        }
        // Chunk by chunk, nothing of the thinking is released at any point.
        val f = ThinkFilter()
        val shown = StringBuilder()
        for (ch in reply) {
            shown.append(f.feed(ch.toString()))
            assertFalse(shown.toString(), shown.contains("Plan") || shown.contains("<") || shown.contains("think"))
            if (shown.isNotEmpty()) assertTrue("call:set_timer{minutes:5}".startsWith(shown.toString()))
        }
        assertTrue(f.thought)
        assertEquals("call:set_timer{minutes:5}", shown.append(f.flush()).toString())
    }

    @Test fun thoughtSaysWhetherTheModelThought() {
        val f = ThinkFilter()
        f.feed("Plain answer.")
        assertFalse(f.thought)
        f.feed("<thi")
        assertFalse(f.thought)
        f.feed("nk>x")
        assertTrue(f.thought)
        assertTrue(ThinkFilter(startsInside = true).thought)
    }

    /** A text call is still the whole reply once the thinking is gone (Robot's text-call protocol, ToolCalls.wholeReplyCalls). */
    @Test fun aTextCallAfterThinkingStillRuns() {
        val visible = ThinkFilter.strip("<think>The user wants a 5 minute timer.</think>\n\ncall:set_timer{minutes:5}")
        val calls = ToolCalls.wholeReplyCalls(visible)
        assertEquals(1, calls.size)
        assertEquals("set_timer", calls[0].call.name)
        assertEquals(5L, calls[0].call.args["minutes"])
    }
}
