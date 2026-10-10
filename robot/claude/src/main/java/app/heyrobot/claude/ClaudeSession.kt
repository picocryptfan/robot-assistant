package app.heyrobot.claude

import app.heyrobot.core.ClaudeAttempt
import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudeCost
import app.heyrobot.core.ClaudeEffortSetting
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudeModel
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ClaudeUsage
import app.heyrobot.core.DoneReason
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolSpec
import app.heyrobot.core.TurnModel
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaRawMessageDeltaEvent
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// VOICE PROCESS ONLY. One conversation with Claude: a one-shot question, or Claude mode (docs/ask-claude.md). Every
// request streams; text deltas go to the listener as they arrive (the panel feeds them to the sentence splitter and the
// voice, as for local replies). Claude's turns are kept as they came (thinking blocks with their signatures, server tool
// blocks), so the prompt cache and the preserved thinking stay valid: a turn paused by a long search continues in the
// same assistant message, and only blocks at the very end are ever removed. A question that fails, is declined or is
// stopped leaves no trace; tool rounds that really ran stay, with their results, until a later request carries them.
// Before every request the history is checked (ClaudeHistory.check): one the API would refuse ends the conversation
// with a plain message instead.

/** What a Claude turn tells the panel while it streams. Called on the caller's coroutine (the panel's main thread). */
interface ClaudeListener {
    /** More of Claude's reply. */
    fun text(delta: String)

    /** Claude declined (`stop_reason == "refusal"`): what was shown of this reply is withdrawn. */
    fun declined()

    /** Claude started thinking (a thinking block opened): "Claude is thinking…" until its words arrive. */
    fun thinking() {}

    /**
     * More of the turn's estimated price became known (a request's input as it starts, the rest as it ends, or an
     * estimate when it was stopped or failed): the panel adds it to today's spend at once.
     */
    fun spent(dollars: Double) {}
}

/** How one ask or answer ended. */
sealed class ClaudeOutcome {
    /** [cut]: stopped at max_tokens (or the context window), or after too many search continuations. */
    data class Done(val cut: Boolean = false) : ClaudeOutcome()

    /** Claude asks Robot to act; the results go back through [ClaudeSession.answer]. */
    data class Calls(val calls: List<RawToolCall>) : ClaudeOutcome()

    data object Declined : ClaudeOutcome()

    data class Failed(val failure: ClaudeFailure) : ClaudeOutcome()
}

/** A turn's numbers, for the cost line, the daily budget and the log (no content). */
data class ClaudeTurnReport(
    val dollars: Double = 0.0,
    val usage: ClaudeUsage = ClaudeUsage(),
    val requests: Int = 0,
    /** The web domains Claude's searches returned ("Claude searched the web"). */
    val domains: List<String> = emptyList(),
    val searches: Int = 0,
    /** Web search results that came back as an error object. */
    val searchErrors: Int = 0,
)

class ClaudeSession(
    private val client: ClaudeClient,
    val model: ClaudeModel,
    private val tools: List<ToolSpec>,
    private val search: Boolean,
    /** Claude mode: the request's automatic cache breakpoint follows the last message, and the effort stays the same. */
    private val conversation: Boolean,
    /** 60 s without any stream event (docs/ask-claude.md "Errors"); a thinking block in progress counts as one. */
    private val idleMs: Long = IDLE_MS,
    private val retryDelayMs: Long = 1_000,
) {
    private val messages = mutableListOf<BetaMessageParam>()
    /** Each message's thinking blocks as they came ([ClaudeHistory.seal]); empty for the user's. */
    private val seals = mutableListOf<List<String>>()
    /** The history as the question under way found it: a failed, declined or stopped question goes back to this. */
    private var before: List<BetaMessageParam> = emptyList()
    private var beforeSeals: List<List<String>> = emptyList()
    /** Once a tool round of the question under way ran, a later failure keeps the history up to here (its results). */
    private var keep = -1
    /** Claude's tool_use ids waiting for results, in order. */
    private var pending: List<String> = emptyList()
    private var plan: ClaudePlan = ClaudePlan.of(ClaudeEffortSetting.AUTO, voice = true, deep = false)
    /** Claude mode's first plan: its effort holds for the whole conversation (a change would restart the cache). */
    private var firstPlan: ClaudePlan? = null
    private var report = ClaudeTurnReport()

    /** Messages so far (tests read the shape; the panel never does). */
    internal val history: List<BetaMessageParam> get() = messages.toList()

    /** What [ClaudeHistory.check] would say about the next question's request (tests). */
    internal fun problem(): String? = when {
        messages.isEmpty() -> null
        messages.last().role() == BetaMessageParam.Role.USER -> ClaudeHistory.check(messages, seals)
        else -> ClaudeHistory.check(messages + ClaudeHistory.message(BetaMessageParam.Role.USER, listOf(ClaudeHistory.text("next"))), seals + listOf(emptyList()))
    }

    /** Nothing has been said to Claude in this conversation yet (Claude mode's first question carries what came before). */
    val fresh: Boolean get() = messages.isEmpty()

    /** The numbers of the last turn ([ask] starts a new one). */
    val lastReport: ClaudeTurnReport get() = report

    /** A new question: [blocks] from ClaudeContext. */
    suspend fun ask(blocks: List<ClaudeBlock>, plan: ClaudePlan, listener: ClaudeListener): ClaudeOutcome {
        // The last turn was stopped while a card waited and nobody said what ran: nothing did.
        if (pending.isNotEmpty()) abandon(emptyList(), stopped = true)
        this.plan = if (!conversation) plan else firstPlan?.let { plan.keeping(it) } ?: plan.also { firstPlan = it }
        report = ClaudeTurnReport()
        before = messages.toList()
        beforeSeals = seals.toList()
        keep = -1
        val last = messages.lastOrNull()
        if (last != null && last.role() == BetaMessageParam.Role.USER) {
            // Results of a turn that ended before Claude answered them: the question joins their message.
            messages[messages.lastIndex] = ClaudeRequests.user(blocks, before = ClaudeHistory.blocks(last))
        } else {
            messages += ClaudeRequests.user(blocks)
            seals += emptyList<String>()
        }
        return run(listener)
    }

    /** The results of Claude's calls, in the order it asked ([TurnModel.answer]). */
    suspend fun answer(results: List<Map<String, Any?>>, listener: ClaudeListener): ClaudeOutcome {
        val ids = pending
        pending = emptyList()
        messages += ClaudeHistory.message(BetaMessageParam.Role.USER, ClaudeRequests.results(ids, results))
        seals += emptyList<String>()
        // This round really ran: whatever happens to the rest of the turn, Claude will hear about it.
        keep = messages.size
        return run(listener)
    }

    /**
     * The turn ends without Claude's calls all answered: another app opened ([results]: what ran, in call order), too
     * many rounds, or a stop while a card waited ([stopped]). The results go with the next question; the calls that
     * never ran say so. A stop before anything of the turn ran leaves no trace of it.
     */
    fun abandon(results: List<Map<String, Any?>> = emptyList(), stopped: Boolean = false) {
        if (pending.isEmpty()) return
        if (stopped && results.isEmpty() && keep < 0) {
            rollBack()
            return
        }
        val why = if (stopped) "The user stopped before this ran." else "The turn ended before this ran."
        val filled = pending.indices.map { i -> results.getOrNull(i) ?: mapOf("status" to "not_done", "detail" to why) }
        messages += ClaudeHistory.message(BetaMessageParam.Role.USER, ClaudeRequests.results(pending, filled))
        seals += emptyList<String>()
        pending = emptyList()
        keep = messages.size
    }

    /** Back to before the question (or to the last tool round that ran): Claude never sees a failed, declined or stopped exchange. */
    private fun rollBack() {
        if (keep >= 0) {
            while (messages.size > keep) {
                messages.removeAt(messages.lastIndex)
                seals.removeAt(seals.lastIndex)
            }
        } else {
            messages.clear()
            messages += before
            seals.clear()
            seals += beforeSeals
        }
        pending = emptyList()
        if (messages.isEmpty()) firstPlan = null
    }

    private suspend fun run(listener: ClaudeListener): ClaudeOutcome {
        var continuations = 0
        var retried = false
        try {
            while (true) {
                // A request the API would refuse is never sent: Claude mode starts over instead.
                ClaudeHistory.check(messages, seals)?.let { problem ->
                    RLog.i("claude_history", "problem" to RLog.Code(problem))
                    rollBack()
                    return ClaudeOutcome.Failed(ClaudeFailure.RESET)
                }
                val r = stream(listener)
                report = report.copy(requests = report.requests + 1)
                when (r) {
                    is Streamed.Failure -> {
                        // Overloaded or 5xx before anything was shown: once more, after a moment (a Stop meanwhile rolls back).
                        if (r.failure.retry && !r.shown && !retried) {
                            retried = true
                            RLog.i("claude_retry", "failure" to r.failure)
                            delay(retryDelayMs)
                            continue
                        }
                        rollBack()
                        return ClaudeOutcome.Failed(r.failure)
                    }
                    is Streamed.Ok -> {
                        report = report.copy(
                            domains = (report.domains + r.domains).distinct().take(MAX_DOMAINS),
                            searchErrors = report.searchErrors + r.searchErrors,
                        )
                        // stop_reason first, before any content is read.
                        when (r.stop) {
                            "refusal" -> {
                                rollBack()
                                listener.declined()
                                return ClaudeOutcome.Declined
                            }
                            "pause_turn" -> {
                                // A server-side search loop paused: the turn goes back as it is and continues in the same
                                // assistant message, with no extra user message.
                                append(r.blocks)
                                if (++continuations > MAX_CONTINUATIONS) {
                                    closeLast()
                                    return ClaudeOutcome.Done(cut = true)
                                }
                                continue
                            }
                            "tool_use" -> if (r.calls.isNotEmpty()) {
                                append(r.blocks)
                                pending = r.calls.map { it.first }
                                return ClaudeOutcome.Calls(r.calls.map { it.second })
                            }
                            // A reply cut off mid-call never runs its calls, and the cut call leaves the history.
                            "max_tokens", "model_context_window_exceeded" -> {
                                append(r.blocks)
                                closeLast()
                                return ClaudeOutcome.Done(cut = true)
                            }
                        }
                        append(r.blocks)
                        if (r.calls.isNotEmpty()) closeLast()
                        return ClaudeOutcome.Done()
                    }
                }
            }
        } catch (e: CancellationException) {
            rollBack()
            throw e
        }
    }

    /** Claude's turn into the history: a continuation joins the paused assistant message it continues. */
    private fun append(blocks: List<BetaContentBlockParam>) {
        val last = messages.lastOrNull()
        if (last != null && last.role() == BetaMessageParam.Role.ASSISTANT) {
            messages[messages.lastIndex] = ClaudeHistory.message(BetaMessageParam.Role.ASSISTANT, ClaudeHistory.blocks(last) + blocks)
            seals[seals.lastIndex] = seals.last() + ClaudeHistory.seal(blocks)
        } else {
            messages += ClaudeHistory.message(BetaMessageParam.Role.ASSISTANT, blocks)
            seals += ClaudeHistory.seal(blocks)
        }
    }

    /** The last assistant message without the calls left unanswered at its end ([ClaudeHistory.closed]). */
    private fun closeLast() {
        val i = messages.lastIndex
        if (i < 0 || messages[i].role() != BetaMessageParam.Role.ASSISTANT) return
        val b = ClaudeHistory.closed(ClaudeHistory.blocks(messages[i]))
        messages[i] = ClaudeHistory.message(BetaMessageParam.Role.ASSISTANT, b)
        seals[i] = ClaudeHistory.seal(b)
    }

    /** One request's usage as the stream reported it so far: the model that served it, the totals, the attempts. */
    private class Usage(val model: String?, val total: ClaudeUsage, val attempts: List<ClaudeAttempt>, val final: Boolean)

    private sealed class Streamed {
        /**
         * [blocks]: Claude's turn as it goes back (ClaudeHistory.echo). [calls]: its tool_use blocks (id, call), never one
         * from an attempt a fallback replaced.
         */
        class Ok(val blocks: List<BetaContentBlockParam>, val stop: String?, val calls: List<Pair<String, RawToolCall>>,
            val domains: List<String>, val searchErrors: Int) : Streamed()

        /** [shown]: some text had reached the user already (then it is never retried: it would be said twice). */
        class Failure(val failure: ClaudeFailure, val shown: Boolean) : Streamed()
    }

    private sealed class Event {
        class Text(val delta: String) : Event()
        /** Anything else from the stream; [thinking]: a thinking block is open (Claude is working, silently). */
        class Alive(val thinking: Boolean, val started: Boolean) : Event()
        class Used(val usage: Usage) : Event()
        class Finished(val ok: Streamed) : Event()
        class Error(val t: Throwable) : Event()
    }

    /**
     * One request, streamed on its own thread (the SDK's stream is blocking): its events come back through a channel
     * read on the caller's coroutine, so a Stop cancels at once; the stream is closed off the main thread. Its usage is
     * counted however it ends.
     */
    private suspend fun stream(listener: ClaudeListener): Streamed {
        val params = ClaudeRequests.params(model, plan, tools, search, conversation, messages.toList())
        val events = Channel<Event>(Channel.UNLIMITED)
        val gone = AtomicBoolean(false)
        val open = AtomicReference<StreamResponse<BetaRawMessageStreamEvent>?>(null)
        val worker = Thread({
            try {
                val resp = client.sdk.beta().messages().createStreaming(params)
                open.set(resp)
                // Stopped (or timed out) while the request was opening.
                if (gone.get()) {
                    resp.close()
                    return@Thread
                }
                val acc = BetaMessageAccumulator.create()
                val watch = UsageWatch()
                var thinking = false
                resp.use { s ->
                    s.stream().forEach { e ->
                        if (gone.get()) throw CancellationException()
                        acc.accumulate(e)
                        var started = false
                        e.messageStart().ifPresent { m -> watch.start(m.message())?.let { events.trySend(Event.Used(it)) } }
                        e.messageDelta().ifPresent { d -> events.trySend(Event.Used(watch.delta(d))) }
                        e.contentBlockStart().ifPresent { b ->
                            val c = b.contentBlock()
                            thinking = c.isThinking() || c.isRedactedThinking()
                            started = thinking
                        }
                        e.contentBlockStop().ifPresent { thinking = false }
                        val text = e.contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse("")
                        events.trySend(if (text.isNotEmpty()) Event.Text(text) else Event.Alive(thinking, started))
                    }
                }
                events.trySend(Event.Finished(finish(acc)))
            } catch (t: Throwable) {
                if (!gone.get()) events.trySend(Event.Error(t))
            } finally {
                events.close()
            }
        }, "robot-claude")
        worker.isDaemon = true
        worker.start()
        var shown = false
        var thinking = false
        var usage: Usage? = null
        var counted = 0.0
        val said = StringBuilder()
        // The request's price so far: added to the report and to today's spend as soon as it is known.
        fun count(u: Usage) {
            val d = ClaudeCost.request(model, u.model, u.total, u.attempts)
            if (d > counted) {
                listener.spent(d - counted)
                report = report.copy(dollars = report.dollars + (d - counted))
                counted = d
            }
        }
        try {
            while (true) {
                // Silence for 60 s is a timeout, except while Claude thinks (then the connection's own read timeout,
                // which the API's keep-alive pings reset, watches for a dead line).
                val next: Event? = if (thinking) {
                    events.receiveCatching().getOrNull()
                } else {
                    val r = withTimeoutOrNull(idleMs) { events.receiveCatching() } ?: return Streamed.Failure(ClaudeFailure.TIMEOUT, shown)
                    r.getOrNull()
                }
                when (next) {
                    null -> return Streamed.Failure(ClaudeFailure.UNKNOWN, shown)
                    is Event.Alive -> {
                        thinking = next.thinking
                        if (next.started && !shown) listener.thinking()
                    }
                    is Event.Used -> {
                        usage = next.usage
                        count(next.usage)
                    }
                    is Event.Text -> {
                        thinking = false
                        shown = true
                        said.append(next.delta)
                        listener.text(next.delta)
                    }
                    is Event.Finished -> return next.ok
                    is Event.Error -> {
                        val f = ClaudeClient.failure(next.t)
                        RLog.w("claude_request", next.t, "failure" to f)
                        return Streamed.Failure(f, shown)
                    }
                }
            }
        } finally {
            // Stopped, timed out or done: the stream is closed (off the main thread) and the worker stops reading.
            gone.set(true)
            withContext(NonCancellable + Dispatchers.IO) {
                try { open.get()?.close() } catch (_: Throwable) {}
            }
            // However it ended, what was billed is counted: a request that stopped before its final usage is estimated
            // from what it said (its input is known from the start).
            val u = usage
            if (u != null) {
                val final = if (u.final) u else Usage(u.model, u.total.copy(output = maxOf(u.total.output, TokenEstimate.text(said.toString()).toLong())), u.attempts, false)
                count(final)
                val used = ClaudeCost.used(final.total, final.attempts)
                val all = report.usage
                report = report.copy(
                    usage = ClaudeUsage(all.input + used.input, all.output + used.output, all.cacheRead + used.cacheRead, all.cacheWrite + used.cacheWrite, all.searches + used.searches),
                    searches = report.searches + used.searches.toInt(),
                )
                // Numbers only: token counts, whether the cache was read, who served it, how it ended.
                RLog.i("claude_usage", "model" to model, "served" to RLog.Code(final.model ?: "none"), "in" to used.input, "out" to used.output,
                    "cache_read_input_tokens" to used.cacheRead, "cache_creation_input_tokens" to used.cacheWrite, "searches" to used.searches,
                    "attempts" to final.attempts.size, "final" to final.final)
            }
        }
    }

    /** Builds a request's [Usage] from message_start and message_delta (the worker thread's). */
    private class UsageWatch {
        private var model: String? = null
        private var total = ClaudeUsage()
        private var attempts: List<ClaudeAttempt> = emptyList()

        fun start(m: BetaMessage): Usage? {
            model = try { m.model().asString() } catch (_: Throwable) { null }
            val u = m.usage()
            total = ClaudeUsage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L),
                u.serverToolUse().map { it.webSearchRequests() }.orElse(0L))
            attempts = u.iterations().orElse(emptyList()).map { ClaudeAttempt(it.model().map { m2 -> m2.asString() }.orElse(null),
                ClaudeUsage(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens(), it.cacheCreationInputTokens())) }
            return Usage(model, total, attempts, final = false)
        }

        /** message_delta's counts are the request's totals so far. */
        fun delta(d: BetaRawMessageDeltaEvent): Usage {
            val u = d.usage()
            total = ClaudeUsage(
                input = u.inputTokens().orElse(total.input),
                output = u.outputTokens(),
                cacheRead = u.cacheReadInputTokens().orElse(total.cacheRead),
                cacheWrite = u.cacheCreationInputTokens().orElse(total.cacheWrite),
                searches = u.serverToolUse().map { it.webSearchRequests() }.orElse(total.searches),
            )
            u.iterations().ifPresent { list ->
                attempts = list.map { ClaudeAttempt(it.model().map { m2 -> m2.asString() }.orElse(null),
                    ClaudeUsage(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens(), it.cacheCreationInputTokens())) }
            }
            return Usage(model, total, attempts, final = d.delta().stopReason().isPresent)
        }
    }

    /** The finished message: its stop reason, the turn as it goes back, the calls, the searched domains. */
    private fun finish(acc: BetaMessageAccumulator): Streamed.Ok {
        val msg = acc.message()
        val stop = msg.stopReason().map { it.asString() }.orElse(null)
        val content = msg.content()
        // Only what came after the last fallback switch can call a tool (an attempt the fallback replaced never does).
        val boundary = content.indexOfLast { it.isFallback() }
        val calls = content.drop(boundary + 1).mapNotNull { b -> b.toolUse().map { u -> u.id() to RawToolCall(u.name(), input(u._input())) }.orElse(null) }
        val domains = mutableListOf<String>()
        var searchErrors = 0
        for (b in content) {
            b.webSearchToolResult().ifPresent { r ->
                // An error comes back as an object, not a list of results: count it and go on.
                val c = r.content()
                if (c.isError()) {
                    searchErrors++
                    RLog.i("claude_search_error", "code" to RLog.Code(c.asError().errorCode().asString()))
                } else {
                    c.resultBlocks().ifPresent { list -> list.forEach { res -> host(res.url())?.let(domains::add) } }
                }
            }
        }
        val blocks = ClaudeHistory.echo(content.map { it.toParam() }).ifEmpty { listOf(ClaudeHistory.text("…")) }
        if (boundary >= 0) RLog.i("claude_fallback", "served" to RLog.Code(msg.model().asString()))
        return Streamed.Ok(blocks, stop, calls, domains.distinct(), searchErrors)
    }

    companion object {
        const val IDLE_MS = 60_000L
        /** pause_turn continuations of one request at most. */
        const val MAX_CONTINUATIONS = 3
        const val MAX_DOMAINS = 5

        /** A tool call's input as plain values (Long, Double, String, Boolean, Map, List). */
        @Suppress("UNCHECKED_CAST")
        internal fun input(v: JsonValue): Map<String, Any?> = try {
            (v.convert(Map::class.java) as? Map<String, Any?>).orEmpty()
        } catch (_: Throwable) { emptyMap() }

        /** "en.wikipedia.org" from a result's URL, without "www.". */
        internal fun host(url: String): String? = try { URI(url).host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
    }
}

/**
 * Claude as a [TurnModel]: the turn runner shows Claude's calls as cards (remote = true) and sends their results back.
 * [first]: the question's blocks.
 */
class ClaudeTurn(
    private val session: ClaudeSession,
    private val first: List<ClaudeBlock>,
    private val plan: ClaudePlan,
    private val listener: ClaudeListener,
) : TurnModel {
    override suspend fun ask(): ModelOutcome = outcome(session.ask(first, plan, listener))

    override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = outcome(session.answer(results.map { it.second }, listener))

    override fun close() = session.abandon()

    override fun abandon(results: List<Pair<String, Map<String, Any?>>>) = session.abandon(results.map { it.second })

    override fun stopped(results: List<Pair<String, Map<String, Any?>>>) = session.abandon(results.map { it.second }, stopped = true)

    private fun outcome(o: ClaudeOutcome): ModelOutcome = when (o) {
        is ClaudeOutcome.Done -> ModelOutcome.Done(if (o.cut) DoneReason.LENGTH else DoneReason.END)
        is ClaudeOutcome.Calls -> ModelOutcome.Calls(o.calls)
        // The listener already put "Claude declined that one." in place of the reply.
        ClaudeOutcome.Declined -> ModelOutcome.Done(DoneReason.END)
        is ClaudeOutcome.Failed -> ModelOutcome.Failed(o.failure.text)
    }
}
