// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.anthropic

import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaTextBlockParam

// VOICE PROCESS ONLY. The rules a Claude conversation's history must keep, as pure functions over the SDK's request
// types: checked before every request (a history that breaks them would only get a 400, so Claude mode starts over
// instead), and used to build Claude's turns the way the API wants them echoed back.

internal object AnthropicHistory {
    /** A message's blocks (a plain string becomes one text block). */
    fun blocks(m: BetaMessageParam): List<BetaContentBlockParam> {
        val c = m.content()
        return if (c.isBetaContentBlockParams()) c.asBetaContentBlockParams() else listOf(text(c.asString()))
    }

    fun text(t: String): BetaContentBlockParam = BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(t).build())

    fun message(role: BetaMessageParam.Role, blocks: List<BetaContentBlockParam>): BetaMessageParam =
        BetaMessageParam.builder().role(role).contentOfBetaContentBlockParams(blocks).build()

    /** What identifies a thinking block exactly as it came: its signature and its text (a redacted one, its data). */
    fun seal(blocks: List<BetaContentBlockParam>): List<String> = blocks.mapNotNull { b ->
        when {
            b.isThinking() -> b.asThinking().let { "t:${it.signature()}:${it.thinking().length}:${it.thinking().hashCode()}" }
            b.isRedactedThinking() -> b.asRedactedThinking().let { "r:${it.data().length}:${it.data().hashCode()}" }
            else -> null
        }
    }

    private fun serverUseIds(blocks: List<BetaContentBlockParam>) = blocks.filter { it.isServerToolUse() }.map { it.asServerToolUse().id() }

    /**
     * The server_tool_use a result block answers. Web search's dynamic filtering runs Claude's code: its
     * `code_execution` call is answered by a code execution result, and the searches the code makes by search results.
     */
    private fun resultOf(b: BetaContentBlockParam): String? =
        if (b.isWebSearchToolResult() || b.isWebFetchToolResult() || b.isCodeExecutionToolResult() || b.isBashCodeExecutionToolResult() ||
            b.isTextEditorCodeExecutionToolResult() || b.isToolSearchToolResult() || b.isAdvisorToolResult()) b.toolUseId().orElse(null) else null

    private fun serverResultIds(blocks: List<BetaContentBlockParam>) = blocks.mapNotNull(::resultOf)

    /**
     * An assistant turn as it is sent back after a mid-answer server-side fallback (the refusal section of the API's
     * model-migration notes, "Echoing fallback turns back"): before the last `fallback` block, thinking, redacted
     * thinking, tool_use, a server_tool_use without its result, and any other model-internal block are left out; text
     * and paired server-tool blocks stay. Everything after the boundary stays. The fallback block itself is dropped.
     */
    fun echo(blocks: List<BetaContentBlockParam>): List<BetaContentBlockParam> {
        val boundary = blocks.indexOfLast { it.isFallback() }
        if (boundary < 0) return blocks
        val before = blocks.subList(0, boundary)
        val paired = serverUseIds(before).toSet() intersect serverResultIds(before).toSet()
        val kept = before.filter { b -> b.isText() || (b.isServerToolUse() && b.asServerToolUse().id() in paired) || resultOf(b) in paired }
        return kept + blocks.subList(boundary + 1, blocks.size)
    }

    /**
     * An assistant turn that ends without its calls answered (cut at max_tokens, or a search paused too often): the
     * calls at its end go, with what they started (the searches Claude's code made and their results), so no tool_use
     * or server_tool_use is left without a result. Only blocks after the last thinking block and the last text are
     * removed, so the thinking stays exactly as it came. If a call sits elsewhere, only the text is kept. A turn left
     * with nothing to say gets "…".
     */
    fun closed(blocks: List<BetaContentBlockParam>): List<BetaContentBlockParam> {
        var out = blocks
        while (true) {
            val results = serverResultIds(out).toSet()
            val first = out.indexOfFirst { b -> b.isToolUse() || (b.isServerToolUse() && b.asServerToolUse().id() !in results) }
            if (first < 0) break
            if (out.drop(first).any { it.isText() || it.isThinking() || it.isRedactedThinking() }) {
                out = out.filter { it.isText() }
                break
            }
            out = out.take(first)
        }
        return if (out.any { !it.isThinking() && !it.isRedactedThinking() }) out else out + text("…")
    }

    /**
     * Null when [messages] is a request the API takes; otherwise what is wrong, as a short code for the log. The rules:
     * it starts with the user, roles alternate, every tool_use is answered by a tool_result (first in the next user
     * message) and every tool_result answers one, server tool calls have their results (in the same message; in
     * Claude's next one when they were made alongside Robot's calls, [resumed]; or, in a paused turn, still to come), the
     * last message is the user's (or, when [paused], Claude's paused turn, which the API resumes), and every thinking
     * block is exactly as it came ([seals], one list per message, recorded when the turn arrived).
     */
    fun check(messages: List<BetaMessageParam>, seals: List<List<String>>, paused: Boolean = false): String? {
        if (messages.isEmpty()) return "empty"
        if (seals.size != messages.size) return "seals"
        if (messages.first().role() != BetaMessageParam.Role.USER) return "first_not_user"
        for (i in messages.indices) {
            val m = messages[i]
            val b = blocks(m)
            if (b.isEmpty()) return "empty_message"
            if (i > 0 && messages[i - 1].role() == m.role()) return "roles_repeat"
            val user = m.role() == BetaMessageParam.Role.USER
            if (user) {
                if (b.any { it.isThinking() || it.isRedactedThinking() || it.isToolUse() || it.isServerToolUse() }) return "assistant_block_in_user"
                val results = b.filter { it.isToolResult() }.map { it.asToolResult().toolUseId() }
                if (b.take(results.size).any { !it.isToolResult() }) return "tool_result_not_first"
                val asked = messages.getOrNull(i - 1)?.let(::blocks).orEmpty().filter { it.isToolUse() }.map { it.asToolUse().id() }
                if (results.sorted() != asked.sorted()) return if (results.size < asked.size) "tool_result_missing" else "tool_result_unasked"
                if (seals[i].isNotEmpty()) return "seals"
            } else {
                val uses = b.filter { it.isToolUse() }.map { it.asToolUse().id() }
                if (uses.isNotEmpty() && i == messages.lastIndex) return "tool_use_unanswered"
                if (uses.toSet().size != uses.size) return "tool_use_repeated"
                val pausedTail = paused && i == messages.lastIndex
                val unpaired = serverUseIds(b) - serverResultIds(b).toSet()
                if (unpaired.isNotEmpty() && !pausedTail && !resumed(messages, i, unpaired)) return "server_tool_unpaired"
                if (b.any { it.isFallback() }) return "fallback_block"
                if (seal(b) != seals[i]) return "thinking_changed"
                if (i == messages.lastIndex && !pausedTail) return "ends_with_assistant"
            }
        }
        return null
    }

    /**
     * Server tool calls Claude made alongside Robot's own (stop_reason tool_use) run when Robot's results go back: the
     * next message holds nothing but those results (anything after them would end the turn with the calls unrun), and
     * their result blocks open Claude's following message, if it came yet.
     */
    private fun resumed(messages: List<BetaMessageParam>, i: Int, unpaired: List<String>): Boolean {
        if (blocks(messages[i]).none { it.isToolUse() }) return false
        val next = messages.getOrNull(i + 1) ?: return false
        if (!blocks(next).all { it.isToolResult() }) return false
        val after = messages.getOrNull(i + 2) ?: return true
        return serverResultIds(blocks(after)).containsAll(unpaired)
    }
}
