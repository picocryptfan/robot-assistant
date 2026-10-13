package app.heyrobot.helpers

import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.TokenUsage
import app.heyrobot.core.ToolName
import app.heyrobot.helpers.anthropic.AnthropicSession

// VOICE PROCESS ONLY, :helpers module. One conversation with a helper (0.6.0): Claude through the Anthropic SDK
// (AnthropicSession), or another company's model or your own server through Chat Completions or the Responses API
// (openai/OpenAiSession). Every implementation keeps the same promises: a question that fails, is declined or is stopped
// leaves no trace; tool rounds that really ran stay, with their results; the helper's own turns go back exactly as they
// came; a history the server would refuse ends the conversation with a plain message instead of a request; usage is
// counted however a request ends; one retry for an overloaded or failing server before anything was shown. The
// outcome, listener and report types keep their Claude names from when Claude was the only helper.

interface HelperSession {
    /** Nothing has been said to the helper in this conversation yet (a joined conversation's first question carries what came before). */
    val fresh: Boolean

    /** The numbers of the last turn ([ask] starts a new one). */
    val lastReport: HelperTurnReport

    /** The tools this helper was given: the panel runs no call to any other (its reply is untrusted). */
    val offered: Set<ToolName>

    /** A new question: [blocks] from HelperContext. */
    suspend fun ask(blocks: List<HelperBlock>, plan: HelperPlan, listener: HelperTurnListener): HelperOutcome

    /** The results of the helper's calls, in the order it asked. */
    suspend fun answer(results: List<Map<String, Any?>>, listener: HelperTurnListener): HelperOutcome

    /**
     * The turn ends without the helper's calls all answered: another app opened ([results]: what ran, in call order), too
     * many rounds, or a stop while a card waited ([stopped]).
     */
    fun abandon(results: List<Map<String, Any?>> = emptyList(), stopped: Boolean = false)
}

/**
 * What a helper's turn tells the panel while it streams (every helper since 0.6.0). Called on the caller's coroutine
 * (the panel's main thread).
 */
interface HelperTurnListener {
    /** More of Claude's reply. */
    fun text(delta: String)

    /** Claude declined (`stop_reason == "refusal"`): what was shown of this reply is withdrawn. */
    fun declined()

    /** Claude started thinking (a thinking block opened): "Claude is thinking…" until its words arrive. */
    fun thinking() {}

    /** 0.6.0: a server on your network sent nothing for a while: it is probably loading its model ("… is loading its model…"). */
    fun loading() {}

    /**
     * More of the turn's estimated price became known (a request's input as it starts, the rest as it ends, or an
     * estimate when it was stopped or failed): the panel adds it to today's spend at once.
     */
    fun spent(dollars: Double) {}
}

/** How one ask or answer ended (every helper's, since 0.6.0). */
sealed class HelperOutcome {
    /** [cut]: stopped at max_tokens (or the context window), or after too many search continuations. */
    data class Done(val cut: Boolean = false) : HelperOutcome()

    /** Claude asks Robot to act; the results go back through [AnthropicSession.answer]. */
    data class Calls(val calls: List<RawToolCall>) : HelperOutcome()

    data object Declined : HelperOutcome()

    data class Failed(val failure: HelperFailure) : HelperOutcome()
}

/** A turn's numbers, for the cost line, the daily budget and the log (no content). */
data class HelperTurnReport(
    val dollars: Double = 0.0,
    val usage: TokenUsage = TokenUsage(),
    val requests: Int = 0,
    /** The web domains Claude's searches returned ("Claude searched the web"). */
    val domains: List<String> = emptyList(),
    val searches: Int = 0,
    /** Web search results that came back as an error object. */
    val searchErrors: Int = 0,
    /**
     * 0.6.0: Robot could tell what the turn cost. False for a helper whose price isn't known (nothing is added to the
     * spend, and the cost line says "cost unknown").
     */
    val costKnown: Boolean = true,
)
