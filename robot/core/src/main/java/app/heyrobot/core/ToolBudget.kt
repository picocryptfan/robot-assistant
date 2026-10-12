package app.heyrobot.core

// Pure. 0.9: with every tool group a conversation can call for (memory, car mode, web search, Termux, a helper), Gemma's
// 4,096 tokens may not hold the system prompt, the tools, the new turn and the reply. The groups give way, one at a time,
// until the new turn has at least MIN_TURN_TOKENS beside the reply's reserve: first the ones this question's words don't
// call for (an earlier turn added them), then the ones it does; in each, the biggest first (web search, memory's tools),
// then the others. The core tools, the system prompt's lines (ask_claude's, the car line, the lock screen's) and memory's
// pinned line always stay: alone they leave far more (PromptTest's worst case).

object ToolBudget {
    /** The least room a new turn always keeps (estimated tokens, as ContextWindow counts them). */
    const val MIN_TURN_TOKENS = 240

    /** The groups that may give way, first to last: web search, memory's tools, then the rest but the core tools. */
    val OPTIONAL: List<ToolGroup> = listOf(ToolGroup.WEB, ToolGroup.MEMORY).let { big -> big + (ToolGroup.entries - ToolGroup.CORE - big.toSet()).reversed() }

    /**
     * The groups to describe this turn: [groups] (the conversation's) less the optional ones that would leave the new turn
     * under [MIN_TURN_TOKENS]. [now]: the groups this question's words call for (ToolRouter.route), kept longest. [fixed]:
     * the system prompt's and the tools' estimated tokens with a set of groups (Prompt.fixedTokens).
     */
    fun fit(groups: Set<ToolGroup>, now: Set<ToolGroup>, maxTokens: Int = ContextWindow.MAX_TOKENS, fixed: (Set<ToolGroup>) -> Int): Set<ToolGroup> {
        var kept = groups
        if (room(fixed(kept), maxTokens)) return kept
        val order = OPTIONAL.filter { it in groups && it !in now } + OPTIONAL.filter { it in groups && it in now }
        for (g in order) {
            kept = kept - g
            if (room(fixed(kept), maxTokens)) return kept
        }
        return kept
    }

    /** With [fixedTokens] of system prompt and tools, the new turn keeps its [MIN_TURN_TOKENS]. */
    fun room(fixedTokens: Int, maxTokens: Int = ContextWindow.MAX_TOKENS): Boolean = ContextWindow.newTurnLimit(fixedTokens, maxTokens) >= MIN_TURN_TOKENS
}
