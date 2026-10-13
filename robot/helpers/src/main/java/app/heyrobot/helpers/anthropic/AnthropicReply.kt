package app.heyrobot.helpers.anthropic

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaContentBlock
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaTextCitation
import java.util.TreeMap

// VOICE PROCESS ONLY. Claude's reply put together from the stream's events, never failing on a part Robot doesn't
// know. The SDK's own accumulator (BetaMessageAccumulator, 2.71.0) throws "Missing input JSON" when a call's input
// comes whole with its content_block_start instead of in input_json_delta events. The API streams input in pieces for
// the calls Claude makes directly ("Streaming server-tool events" in the server tools docs); the searches Claude's own
// code makes under web search's dynamic filtering (web_search_20260209, a `caller` of code_execution) come whole, and
// the whole answer then failed as "Something went wrong with Claude". Here a call keeps the input it started with when
// no pieces follow; a block or delta type Robot doesn't know is left out (counted); citations stay on their text.

internal class AnthropicReply {
    /** What the stream said, once it ended. */
    class Done(
        val content: List<BetaContentBlock>,
        val stop: String?,
        val model: String?,
        /** Calls (tool_use, server_tool_use, mcp_tool_use) whose input came whole with their block. */
        val whole: Int,
        /** Server tool calls made from Claude's code (a `caller` other than direct). */
        val fromCode: Int,
        /** Blocks Robot couldn't use and events it couldn't read: left out. */
        val leftOut: Int,
        /** The stream said how the reply ended (a stop reason, or message_stop); false: it was cut off. */
        val complete: Boolean,
    )

    private class Part(val start: BetaContentBlock) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        var signature: String? = null
        /** The input's pieces; null when none came (the input came whole with the block). */
        var json: StringBuilder? = null
        val citations = mutableListOf<BetaTextCitation>()
    }

    private val parts = TreeMap<Long, Part>()
    private var stop: String? = null
    private var model: String? = null
    private var skipped = 0
    private var stopped = false

    /** One event. Throws only on an event it can't read; the caller counts that ([skip]) and goes on. */
    fun add(e: BetaRawMessageStreamEvent) {
        e.messageStart().ifPresent { model = it.message().model().asString() }
        e.contentBlockStart().ifPresent { s -> parts[s.index()] = Part(JSON.convertValue(s.contentBlock(), BetaContentBlock::class.java)) }
        e.contentBlockDelta().ifPresent { d ->
            val p = parts[d.index()] ?: throw IllegalStateException("delta before its block")
            val x = d.delta()
            when {
                x.isText() -> p.text.append(x.asText().text())
                x.isInputJson() -> (p.json ?: StringBuilder().also { p.json = it }).append(x.asInputJson().partialJson())
                x.isCitations() -> p.citations += JSON.convertValue(x.asCitations().citation(), BetaTextCitation::class.java)
                x.isThinking() -> p.thinking.append(x.asThinking().thinking())
                x.isSignature() -> p.signature = x.asSignature().signature()
                // Compaction (never asked for) and any delta type newer than Robot: nothing to keep.
                else -> {}
            }
        }
        e.messageDelta().ifPresent { d -> d.delta().stopReason().ifPresent { stop = it.asString() } }
        if (e.isMessageStop()) stopped = true
    }

    /** An event that couldn't be read. */
    fun skip() {
        skipped++
    }

    fun done(): Done {
        var whole = 0
        var fromCode = 0
        var leftOut = skipped
        val content = parts.values.mapNotNull { p ->
            try {
                val b = finish(p)
                // A block type Robot doesn't know can't go back to the API: it is left out (the history check sees to the rest).
                b.toParam()
                if (p.json == null && (b.isToolUse() || b.isServerToolUse() || b.isMcpToolUse())) whole++
                if (b.serverToolUse().flatMap { it.caller() }.map { !it.isDirect() }.orElse(false)) fromCode++
                b
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                leftOut++
                null
            }
        }
        return Done(content, stop, model, whole, fromCode, leftOut, complete = stop != null || stopped)
    }

    private fun finish(p: Part): BetaContentBlock {
        val b = p.start
        val json = p.json
        return when {
            // citations is required (nullable): a block that started without the field gets null, or what came.
            b.isText() && (p.text.isNotEmpty() || p.citations.isNotEmpty()) -> b.asText().let { t ->
                BetaContentBlock.ofText(t.toBuilder().text(t.text() + p.text)
                    .citations(t.citations().map { it + p.citations }.orElse(p.citations.ifEmpty { null })).build())
            }
            b.isThinking() && (p.thinking.isNotEmpty() || p.signature != null) -> b.asThinking().let { t ->
                BetaContentBlock.ofThinking(t.toBuilder().thinking(t.thinking() + p.thinking).signature(p.signature ?: t.signature()).build())
            }
            json == null -> b
            b.isToolUse() -> BetaContentBlock.ofToolUse(b.asToolUse().toBuilder().input(input(json)).build())
            b.isServerToolUse() -> BetaContentBlock.ofServerToolUse(b.asServerToolUse().toBuilder().input(input(json)).build())
            b.isMcpToolUse() -> BetaContentBlock.ofMcpToolUse(b.asMcpToolUse().toBuilder().input(input(json)).build())
            else -> b
        }
    }

    companion object {
        private val JSON = jsonMapper()

        /** The pieces of a call's input, joined; none at all (a call without arguments) is an empty object. */
        private fun input(s: CharSequence): JsonValue =
            if (s.isBlank()) JsonValue.from(emptyMap<String, Any>()) else JSON.readValue(s.toString(), JsonValue::class.java)
    }
}
