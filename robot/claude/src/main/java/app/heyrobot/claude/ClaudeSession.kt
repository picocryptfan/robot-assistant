package app.heyrobot.claude

import app.heyrobot.core.ClaudeAttempt
import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudeCost
import app.heyrobot.core.ClaudeEffortSetting
import app.heyrobot.core.ClaudeEnd
import app.heyrobot.core.ClaudeErrorKind
import app.heyrobot.core.ClaudeFail
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudeModel
import app.heyrobot.core.ClaudeModelCode
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ClaudePrompt
import app.heyrobot.core.ClaudeStage
import app.heyrobot.core.ClaudeUsage
import app.heyrobot.core.DoneReason
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolSpec
import app.heyrobot.core.TurnModel
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicServiceException
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
    /**
     * The system prompt: ClaudePrompt.SYSTEM (spoken answers), or ClaudePrompt.TRANSCRIPT_SYSTEM for a transcript's
     * summary (0.5.0, docs/conversation-mode.md 7.2: one question, no tools, no search).
     */
    private val system: String = ClaudePrompt.SYSTEM,
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
        // The history ends with Claude's paused turn, sent back as it is.
        var paused = false
        try {
            while (true) {
                // A request the API would refuse is never sent: Claude mode starts over instead.
                ClaudeHistory.check(messages, seals, paused)?.let { problem ->
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
                                paused = true
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

        /**
         * [shown]: some text had reached the user already (then it is never retried: it would be said twice). [stage],
         * [status] and [kind]: where it broke, for the voice log when it is [ClaudeFailure.UNKNOWN].
         */
        class Failure(val failure: ClaudeFailure, val shown: Boolean, val stage: ClaudeStage? = null, val status: Int = 0,
            val kind: ClaudeErrorKind? = null) : Streamed()
    }

    private sealed class Event {
        class Text(val delta: String) : Event()
        /** Anything else from the stream; [thinking]: a thinking block is open (Claude is working, silently). */
        class Alive(val thinking: Boolean, val started: Boolean) : Event()
        class Used(val usage: Usage) : Event()
        class Finished(val ok: Streamed) : Event()
        class Error(val t: Throwable, val stage: ClaudeStage) : Event()
        /** The request ended without an answer and without an exception: the stream stopped before Claude finished. */
        class Cut : Event()
    }

    /**
     * One request, streamed on its own thread (the SDK's stream is blocking): its events come back through a channel
     * read on the caller's coroutine, so a Stop cancels at once; the stream is closed off the main thread. Its usage is
     * counted however it ends.
     */
    private suspend fun stream(listener: ClaudeListener): Streamed {
        val params = try {
            ClaudeRequests.params(model, plan, tools, search, conversation, messages.toList(), system)
        } catch (t: Exception) {
            RLog.w("claude_request", t, "stage" to ClaudeStage.REQUEST)
            return Streamed.Failure(ClaudeFailure.UNKNOWN, false, ClaudeStage.REQUEST, 0, ClaudeClient.kind(t)).also(::logFailure)
        }
        val events = Channel<Event>(Channel.UNLIMITED)
        val gone = AtomicBoolean(false)
        val open = AtomicReference<StreamResponse<BetaRawMessageStreamEvent>?>(null)
        val worker = Thread({
            // Where a throwable would come from: the request, the SDK reading the stream, or Robot's own reading.
            var stage = ClaudeStage.OPENING
            try {
                val resp = client.sdk.beta().messages().createStreaming(params)
                open.set(resp)
                // Stopped (or timed out) while the request was opening.
                if (gone.get()) {
                    resp.close()
                    return@Thread
                }
                val reply = ClaudeReply()
                val watch = UsageWatch()
                var thinking = false
                var skipped = 0
                stage = ClaudeStage.SDK
                resp.use { s ->
                    s.stream().forEach { e ->
                        if (gone.get()) throw CancellationException()
                        var started = false
                        var text = ""
                        // A step Robot can't do for an event leaves that part out, never the end of the answer; each step
                        // is on its own, so the words and the usage still come (logged by class name only).
                        var problem: Throwable? = null
                        fun step(f: () -> Unit) {
                            try { f() } catch (t: Throwable) {
                                if (t is VirtualMachineError) throw t
                                if (problem == null) problem = t
                            }
                        }
                        step { reply.add(e) }
                        step { e.messageStart().ifPresent { m -> watch.start(m.message())?.let { events.trySend(Event.Used(it)) } } }
                        step { e.messageDelta().ifPresent { d -> events.trySend(Event.Used(watch.delta(d))) } }
                        step {
                            e.contentBlockStart().ifPresent { b ->
                                val c = b.contentBlock()
                                thinking = c.isThinking() || c.isRedactedThinking()
                                started = thinking
                            }
                        }
                        e.contentBlockStop().ifPresent { thinking = false }
                        step { text = e.contentBlockDelta().flatMap { it.delta().text() }.map { it.text() }.orElse("") }
                        problem?.let { t ->
                            reply.skip()
                            if (++skipped <= MAX_SKIP_LOGS) RLog.w("claude_event_skipped", t, "event" to RLog.Code(kind(e)))
                        }
                        events.trySend(if (text.isNotEmpty()) Event.Text(text) else Event.Alive(thinking, started))
                    }
                }
                stage = ClaudeStage.ROBOT
                val done = reply.done()
                events.trySend(if (done.complete) Event.Finished(finish(done)) else Event.Cut())
            } catch (t: Throwable) {
                if (!gone.get()) events.trySend(Event.Error(t, if (t is AnthropicServiceException) ClaudeStage.HTTP else stage))
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
        // The voice log: sent, the first event, and how it ended (numbers and codes only).
        val t0 = System.currentTimeMillis()
        var heard = false
        var ended: Streamed? = null
        fun end(r: Streamed): Streamed { ended = r; return r }
        VoiceLog.add(VoiceEvent.CLAUDE_SENT, report.requests, ClaudeModelCode.of(model).id)
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
                    val r = withTimeoutOrNull(idleMs) { events.receiveCatching() } ?: return end(Streamed.Failure(ClaudeFailure.TIMEOUT, shown))
                    r.getOrNull()
                }
                if (!heard && (next is Event.Alive || next is Event.Text || next is Event.Used)) {
                    heard = true
                    VoiceLog.add(VoiceEvent.CLAUDE_FIRST, (System.currentTimeMillis() - t0).toInt())
                }
                when (next) {
                    // The worker always ends with Finished, Cut or Error: a channel closed without any means it died.
                    null -> return end(Streamed.Failure(ClaudeFailure.UNKNOWN, shown, ClaudeStage.WORKER))
                    is Event.Cut -> {
                        RLog.i("claude_request", "failure" to ClaudeFailure.UNKNOWN, "stage" to ClaudeStage.CLOSED)
                        return end(Streamed.Failure(ClaudeFailure.UNKNOWN, shown, ClaudeStage.CLOSED))
                    }
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
                    is Event.Finished -> return end(next.ok)
                    is Event.Error -> {
                        val f = ClaudeClient.failure(next.t)
                        RLog.w("claude_request", next.t, "failure" to f, "stage" to next.stage)
                        return end(Streamed.Failure(f, shown, next.stage, (next.t as? AnthropicServiceException)?.statusCode() ?: 0, ClaudeClient.kind(next.t)))
                    }
                }
            }
        } finally {
            // Stopped, timed out or done: the stream is closed (off the main thread) and the worker stops reading.
            gone.set(true)
            val ms = (System.currentTimeMillis() - t0).toInt()
            when (val e = ended) {
                is Streamed.Ok -> VoiceLog.add(VoiceEvent.CLAUDE_DONE, ms, ClaudeEnd.of(e.stop).id, said.length)
                is Streamed.Failure -> logFailure(e)
                null -> VoiceLog.add(VoiceEvent.CLAUDE_DONE, ms, ClaudeEnd.STOPPED.id, said.length)
            }
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

    /** The finished reply: its stop reason, the turn as it goes back, the calls, the sources' domains. */
    private fun finish(reply: ClaudeReply.Done): Streamed.Ok {
        val stop = reply.stop
        val content = reply.content
        if (reply.whole + reply.fromCode + reply.leftOut > 0) {
            RLog.i("claude_read", "whole" to reply.whole, "from_code" to reply.fromCode, "left_out" to reply.leftOut)
            VoiceLog.add(VoiceEvent.CLAUDE_READ, reply.whole, reply.fromCode, reply.leftOut)
        }
        // Only what came after the last fallback switch can call a tool (an attempt the fallback replaced never does).
        val boundary = content.indexOfLast { it.isFallback() }
        val calls = content.drop(boundary + 1).mapNotNull { b -> b.toolUse().map { u -> u.id() to RawToolCall(u.name(), input(u._input())) }.orElse(null) }
        // The pages Claude cited come first, then the rest of what its searches found.
        val domains = content.flatMap { b -> b.text().flatMap { it.citations() }.orElse(emptyList()) }
            .mapNotNull { c -> try { c.webSearchResultLocation().map { it.url() }.orElse(null)?.let(::host) } catch (_: Exception) { null } }.toMutableList()
        var searchErrors = 0
        for (b in content) try {
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
        } catch (_: Exception) {
            // The sources line is a nicety: a result Robot can't read only leaves its domains out.
        }
        val blocks = ClaudeHistory.echo(content.map { it.toParam() }).ifEmpty { listOf(ClaudeHistory.text("…")) }
        if (boundary >= 0) RLog.i("claude_fallback", "served" to RLog.Code(reply.model ?: "none"))
        return Streamed.Ok(blocks, stop, calls, domains.distinct(), searchErrors)
    }

    /** A failed request in the voice log: why, and for "Something went wrong" where it broke (numbers and codes only). */
    private fun logFailure(f: Streamed.Failure) {
        VoiceLog.add(VoiceEvent.CLAUDE_FAILED, ClaudeFail.of(f.failure))
        if (f.failure == ClaudeFailure.UNKNOWN) VoiceLog.add(VoiceEvent.CLAUDE_UNKNOWN, f.stage?.id ?: 0, f.status, f.kind?.id ?: 0)
    }

    companion object {
        const val IDLE_MS = 60_000L
        /** pause_turn continuations of one request at most. */
        const val MAX_CONTINUATIONS = 3
        const val MAX_DOMAINS = 5
        /** Unreadable events logged per request at most (the rest are only counted). */
        private const val MAX_SKIP_LOGS = 3

        /** An event's type for the log ("content_block_delta"), never its content. */
        private fun kind(e: BetaRawMessageStreamEvent): String = when {
            e.isMessageStart() -> "message_start"
            e.isMessageDelta() -> "message_delta"
            e.isMessageStop() -> "message_stop"
            e.isContentBlockStart() -> "content_block_start"
            e.isContentBlockDelta() -> "content_block_delta"
            e.isContentBlockStop() -> "content_block_stop"
            else -> "other"
        }

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
