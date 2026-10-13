package app.heyrobot.helpers

import app.heyrobot.helpers.anthropic.ClaudeListener
import app.heyrobot.helpers.anthropic.ClaudeOutcome
import app.heyrobot.helpers.anthropic.ClaudeTurnReport
import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ToolName

// VOICE PROCESS ONLY, :helpers module. One conversation with a helper (0.6.0): Claude through the Anthropic SDK
// (ClaudeSession), or another company's model or your own server through Chat Completions or the Responses API
// (openai/OpenAiSession). Every implementation keeps the same promises: a question that fails, is declined or is stopped
// leaves no trace; tool rounds that really ran stay, with their results; the helper's own turns go back exactly as they
// came; a history the server would refuse ends the conversation with a plain message instead of a request; usage is
// counted however a request ends; one retry for an overloaded or failing server before anything was shown. The
// outcome, listener and report types keep their Claude names from when Claude was the only helper.

interface HelperSession {
    /** Nothing has been said to the helper in this conversation yet (a joined conversation's first question carries what came before). */
    val fresh: Boolean

    /** The numbers of the last turn ([ask] starts a new one). */
    val lastReport: ClaudeTurnReport

    /** The tools this helper was given: the panel runs no call to any other (its reply is untrusted). */
    val offered: Set<ToolName>

    /** A new question: [blocks] from ClaudeContext. */
    suspend fun ask(blocks: List<ClaudeBlock>, plan: ClaudePlan, listener: ClaudeListener): ClaudeOutcome

    /** The results of the helper's calls, in the order it asked. */
    suspend fun answer(results: List<Map<String, Any?>>, listener: ClaudeListener): ClaudeOutcome

    /**
     * The turn ends without the helper's calls all answered: another app opened ([results]: what ran, in call order), too
     * many rounds, or a stop while a card waited ([stopped]).
     */
    fun abandon(results: List<Map<String, Any?>> = emptyList(), stopped: Boolean = false)
}
