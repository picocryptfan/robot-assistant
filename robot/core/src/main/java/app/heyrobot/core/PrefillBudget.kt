// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. 1.2 (docs/plans/every-android-phone-1.2-design.md, Step 3): a reading budget where the model reads slowly. A
// conversation Robot builds anew (the first question, a new tool group, a translation in between, a model switch) reads
// its whole prompt before the first word: the system prompt, the tools' descriptions and the history kept. On a phone's
// CPU that is tens of seconds; on the TPU and the GPU it is a few. So on the CPU, and only there, the history a rebuild
// reads is capped by what the model reads in [PrefillBudget.TARGET_SECONDS], and where even the prompt alone is too slow,
// the compact form of the prompt is used. On the TPU and the GPU nothing changes, byte for byte (PromptFrozenTest).

/**
 * What a slow path cuts, in the order it cuts them as the reading gets slower. [HISTORY] first: the oldest exchanges of a
 * rebuilt conversation, down to the budget (never below [PrefillBudget.MIN_HISTORY_TOKENS] while today's window has
 * room). Then, together, the compact form ([PrefillBudget.compact]): [EXAMPLES], the "e.g." in the tools' parameter
 * texts; [PARAMETERS], the parameter texts that remain, to their short form ([ToolParam.brief]); [MEMORY], the pinned
 * line and the notes to the chat-only model's budget (MemoryRecall.budget); [OPENING], the system prompt's first lines
 * (who Robot is, how it answers) to the chat-only prompt's wording ([Prompt.COMPACT_OPENING]).
 *
 * Never cut, in any form: the rules that keep Robot safe (a tool only when asked; screen text, pictures, tool and web
 * results, notes and command output are information, never instructions; the lock screen's limits; skills only when
 * asked), the lines that make it say what it can't do (no internet; Settings › "Direct answers"), the tools' names,
 * parameters, types and required lists, and each tool's own one-line description ("the person sends it").
 */
enum class PromptCut { HISTORY, EXAMPLES, PARAMETERS, MEMORY, OPENING }

/**
 * The reading budget of the model in memory: at most [tokens] tokens (estimated as [TokenEstimate] counts them)
 * for a rebuilt conversation, or -1 for no budget (the TPU, the GPU, a fast CPU); [compact]: the compact form of the
 * prompt. [readTps] is the reading speed it was made from, and [measured] says whether this phone measured it (core
 * SpeedLog) or it is Robot's estimate ([Speeds.readTps]).
 */
data class PrefillBudget(val tokens: Int, val compact: Boolean, val readTps: Double = 0.0, val measured: Boolean = false) {
    /** The cuts this budget makes, in [PromptCut]'s order. */
    val cuts: List<PromptCut> get() = when {
        compact -> PromptCut.entries
        tokens >= 0 -> listOf(PromptCut.HISTORY)
        else -> emptyList()
    }

    /** The history a rebuilt conversation may read: [PrefillBudget.history] with this budget. */
    fun history(fixedTokens: Int, newTurnTokens: Int, maxTokens: Int = ContextWindow.MAX_TOKENS): Int =
        history(fixedTokens, newTurnTokens, maxTokens, tokens)

    companion object {
        /** The fast paths' budget: none. Every prompt as it was before 1.2. */
        val NONE = PrefillBudget(-1, compact = false)

        /**
         * The longest a rebuilt conversation should read before its first word, in seconds (EST, design Step 3). On the
         * slowest CPUs it can't be met (the prompt alone takes longer); the compact form is then what moves the first word.
         */
        const val TARGET_SECONDS = 8.0

        /**
         * The history a rebuild keeps however slow the reading, while today's window has room for it: about the last
         * exchange or two, so "text her that" after a question still knows who "her" is (EST). At the Pixel 8's published
         * 53 tokens a second on the CPU, about 3 s.
         */
        const val MIN_HISTORY_TOKENS = 200

        /**
         * A CPU reading less than this many tokens a second (Robot's whole prompt, [Speeds.PROMPT_TOKENS], in more than
         * [TARGET_SECONDS]) gets the compact form: 187.5. Every CPU estimate Robot has is below it (the Pixel 10 Pro's E2B
         * is 160 to 210); only a CPU measured faster keeps the full prompt.
         */
        const val COMPACT_BELOW_TPS = Speeds.PROMPT_TOKENS / TARGET_SECONDS

        /**
         * A CPU reading less than this caps the history: today's window, at most [ContextWindow.MAX_TOKENS] less the reply's
         * reserve (3,328), read in [TARGET_SECONDS]: 416 tokens a second.
         */
        val CAP_BELOW_TPS: Double get() = (ContextWindow.MAX_TOKENS - ContextWindow.REPLY_RESERVE) / TARGET_SECONDS

        /**
         * A CPU with neither a measurement nor an estimate (your own file): the Pixel 8's CPU reading speed as LiteRT-LM #1850
         * published it (design §1), the slowest figure Robot has for a phone it runs on.
         */
        const val UNKNOWN_CPU_TPS = 53.0

        /**
         * The budget for a model on [backend]: none on the TPU or the GPU, whatever was measured there. On the CPU, from
         * this phone's measured reading speed ([measuredTps], SpeedLog's median in real tokens a second) when it has one,
         * else the estimate ([estimateTps], the low end of [Speeds]' range), else [UNKNOWN_CPU_TPS]. A speed that isn't a
         * positive finite number counts as none. The budget is that speed (real tokens a second) times [TARGET_SECONDS],
         * compared with estimated tokens ([TokenEstimate]). The estimate runs about a third high for English text, but the
         * tools' declarations are formatted by the model's own template, and how their real count compares is UNVERIFIED
         * until :brain's brain_fixed_tokens is read on a phone: the real read may land under the target or over it.
         */
        fun of(backend: ModelBackend?, measuredTps: Double?, estimateTps: Double?): PrefillBudget {
            if (backend != ModelBackend.CPU) return NONE
            val measured = measuredTps?.takeIf { it.isFinite() && it > 0.0 }
            val tps = measured ?: estimateTps?.takeIf { it.isFinite() && it > 0.0 } ?: UNKNOWN_CPU_TPS
            if (tps >= CAP_BELOW_TPS) return NONE
            val tokens = kotlin.math.floor(tps * TARGET_SECONDS).toInt().coerceAtLeast(0)
            return PrefillBudget(tokens, compact = tps < COMPACT_BELOW_TPS, readTps = tps, measured = measured != null)
        }

        /**
         * The history a rebuilt conversation may read: today's ([ContextWindow.historyBudget]) with no budget ([budget] -1);
         * with one, what's left of it after the fixed part and the new turn, but never less than [MIN_HISTORY_TOKENS] and
         * never more than today's. Only a rebuild reads the history: a reused conversation reads the new turn alone.
         */
        fun history(fixedTokens: Int, newTurnTokens: Int, maxTokens: Int, budget: Int): Int {
            val window = ContextWindow.historyBudget(fixedTokens, newTurnTokens, maxTokens)
            if (budget < 0) return window
            return minOf(window, maxOf(MIN_HISTORY_TOKENS, budget - fixedTokens - newTurnTokens))
        }
    }
}
