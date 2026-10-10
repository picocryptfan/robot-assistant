package app.heyrobot.claude

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
import app.heyrobot.core.ToolSpec
import app.heyrobot.core.TurnModel
import com.anthropic.core.JsonValue
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.core.http.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// VOICE PROCESS ONLY. One conversation with Claude: a one-shot question, or Claude mode (docs/ask-claude.md). Every
// request streams; text deltas go to the listener as they arrive (the panel feeds them to the sentence splitter and the
// voice, as for local replies). The history is append-only, Claude's turns kept exactly as they came (thinking blocks
// with their signatures, server tool blocks), so the prompt cache and the preserved thinking stay valid. A turn that
// fails, is declined or is stopped leaves no trace in it.

/** What a Claude turn tells the panel while it streams. Called on the caller's coroutine (the panel's main thread). */
interface ClaudeListener {
    /** More of Claude's reply. */
    fun text(delta: String)

    /** Claude declined (`stop_reason == "refusal"`): what was shown of this reply is withdrawn. */
    fun declined()
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
    /** Claude mode: the request's automatic cache breakpoint follows the last message. */
    private val conversation: Boolean,
    /** 60 s to the first token (docs/ask-claude.md "Errors"); then no limit while the answer keeps arriving. */
    private val firstOutputMs: Long = FIRST_OUTPUT_MS,
    private val retryDelayMs: Long = 1_000,
) {
    private val messages = mutableListOf<BetaMessageParam>()
    /** Where the turn under way started in [messages]: a failed, declined or stopped turn is cut back to it. */
    private var turnStart = 0
    /** Claude's tool_use ids waiting for results, in order. */
    private var pending: List<String> = emptyList()
    /** Results owed from a turn that ended without sending them: they open the next user message. */
    private var owed: List<BetaContentBlockParam> = emptyList()
    private var plan: ClaudePlan = ClaudePlan.of(ClaudeEffortSetting.AUTO, voice = true, deep = false)
    private var report = ClaudeTurnReport()

    /** Messages so far (tests read the shape; the panel never does). */
    internal val history: List<BetaMessageParam> get() = messages.toList()

    /** The numbers of the last turn ([ask] starts a new one). */
    val lastReport: ClaudeTurnReport get() = report

    /** A new question: [blocks] from ClaudeContext. */
    suspend fun ask(blocks: List<ClaudeBlock>, plan: ClaudePlan, listener: ClaudeListener): ClaudeOutcome {
        // The last turn was stopped while a card waited: it never happened, as far as Claude knows.
        if (pending.isNotEmpty()) rollBack()
        this.plan = plan
        report = ClaudeTurnReport()
        turnStart = messages.size
        messages += ClaudeRequests.user(blocks, before = owed)
        owed = emptyList()
        pending = emptyList()
        return run(listener)
    }

    /** The results of Claude's calls, in the order it asked ([TurnModel.answer]). */
    suspend fun answer(results: List<Map<String, Any?>>, listener: ClaudeListener): ClaudeOutcome {
        val ids = pending
        pending = emptyList()
        messages += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(ClaudeRequests.results(ids, results)).build()
        return run(listener)
    }

    /** The turn ends without sending the results (another app opened, or too many rounds): they are owed. */
    fun abandon() {
        if (pending.isNotEmpty()) owed = ClaudeRequests.results(pending, pending.map { mapOf("status" to "not_done", "detail" to "The turn ended before this ran.") })
        pending = emptyList()
    }

    /** Back to where the turn started: Claude never sees a failed, declined or stopped exchange. */
    private fun rollBack() {
        while (messages.size > turnStart) messages.removeAt(messages.lastIndex)
        pending = emptyList()
    }

    private suspend fun run(listener: ClaudeListener): ClaudeOutcome {
        var continuations = 0
        var retried = false
        while (true) {
            val r = try {
                stream(listener)
            } catch (e: CancellationException) {
                rollBack()
                throw e
            }
            report = report.copy(requests = report.requests + 1)
            when (r) {
                is Streamed.Failure -> {
                    // Overloaded or 5xx before anything was shown: once more, after a moment.
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
                    addUsage(r)
                    // stop_reason first, before any content is read.
                    when (r.stop) {
                        "refusal" -> {
                            rollBack()
                            listener.declined()
                            return ClaudeOutcome.Declined
                        }
                        "pause_turn" -> {
                            // A server-side search loop paused: send the turn back as it is, no extra user message.
                            messages += r.message
                            if (++continuations > MAX_CONTINUATIONS) return ClaudeOutcome.Done(cut = true)
                            continue
                        }
                        "tool_use" -> if (r.calls.isNotEmpty()) {
                            messages += r.message
                            pending = r.calls.map { it.first }
                            return ClaudeOutcome.Calls(r.calls.map { it.second })
                        }
                        // A reply cut off mid-call never runs its calls; history keeps only its text.
                        "max_tokens", "model_context_window_exceeded" -> {
                            messages += if (r.calls.isEmpty()) r.message else textOnly(r.text)
                            return ClaudeOutcome.Done(cut = true)
                        }
                    }
                    messages += if (r.calls.isEmpty()) r.message else textOnly(r.text)
                    return ClaudeOutcome.Done()
                }
            }
        }
    }

    private fun textOnly(text: String): BetaMessageParam = BetaMessageParam.builder().role(BetaMessageParam.Role.ASSISTANT)
        .contentOfBetaContentBlockParams(listOf(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(text.ifBlank { "…" }).build()))).build()

    private fun addUsage(r: Streamed.Ok) {
        val u = r.usage
        val all = report.usage
        report = report.copy(
            dollars = report.dollars + ClaudeCost.dollars(model, u),
            usage = ClaudeUsage(all.input + u.input, all.output + u.output, all.cacheRead + u.cacheRead, all.cacheWrite + u.cacheWrite, all.searches + u.searches),
            domains = (report.domains + r.domains).distinct().take(MAX_DOMAINS),
            searches = report.searches + u.searches.toInt(),
            searchErrors = report.searchErrors + r.searchErrors,
        )
        // Numbers only: token counts and whether the cache was read.
        RLog.i("claude_usage", "model" to model, "in" to u.input, "out" to u.output, "cache_read_input_tokens" to u.cacheRead,
            "cache_creation_input_tokens" to u.cacheWrite, "searches" to u.searches, "stop" to RLog.Code(r.stop ?: "none"))
    }

    private sealed class Streamed {
        /** [calls]: Claude's tool_use blocks (id, call). [text]: the reply's text, for a cut-off reply's history. */
        class Ok(val message: BetaMessageParam, val stop: String?, val text: String, val calls: List<Pair<String, RawToolCall>>,
            val usage: ClaudeUsage, val domains: List<String>, val searchErrors: Int) : Streamed()

        /** [shown]: some text had reached the owner already (then it is never retried: it would be said twice). */
        class Failure(val failure: ClaudeFailure, val shown: Boolean) : Streamed()
    }

    private sealed class Event {
        class Text(val delta: String) : Event()
        /** The first thing of the answer arrived (text, a tool call, a search): the 60 s watch ends. */
        data object Started : Event()
        class Finished(val ok: Streamed) : Event()
        class Error(val t: Throwable) : Event()
    }

    /**
     * One request, streamed on its own thread (the SDK's stream is blocking): its events come back through a channel
     * read on the caller's coroutine, so a Stop cancels at once and closes the stream, whatever the thread is doing.
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
                var started = false
                resp.use { s ->
                    s.stream().forEach { e ->
                        if (gone.get()) throw CancellationException()
                        acc.accumulate(e)
                        e.contentBlockStart().ifPresent { b ->
                            val c = b.contentBlock()
                            if (!started && (c.isText() || c.isToolUse() || c.isServerToolUse())) {
                                started = true
                                events.trySend(Event.Started)
                            }
                        }
                        e.contentBlockDelta().ifPresent { d ->
                            d.delta().text().ifPresent { t ->
                                if (!started) { started = true; events.trySend(Event.Started) }
                                if (t.text().isNotEmpty()) events.trySend(Event.Text(t.text()))
                            }
                        }
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
        var started = false
        val t0 = System.currentTimeMillis()
        try {
            while (true) {
                val next: Event? = if (started) {
                    events.receiveCatching().getOrNull()
                } else {
                    // Nothing of the answer yet: wait at most what is left of the 60 s.
                    val left = (firstOutputMs - (System.currentTimeMillis() - t0)).coerceAtLeast(1)
                    val r = withTimeoutOrNull(left) { events.receiveCatching() } ?: return Streamed.Failure(ClaudeFailure.TIMEOUT, shown = false)
                    r.getOrNull()
                }
                when (next) {
                    null -> return Streamed.Failure(ClaudeFailure.UNKNOWN, shown)
                    is Event.Started -> started = true
                    is Event.Text -> {
                        started = true
                        shown = true
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
            // Stopped, timed out or done: the stream is closed and the thread stops reading.
            gone.set(true)
            try { open.get()?.close() } catch (_: Throwable) {}
        }
    }

    /** The finished message: its stop reason, the calls, the usage and the searched domains. */
    private fun finish(acc: BetaMessageAccumulator): Streamed.Ok {
        val msg = acc.message()
        val stop = msg.stopReason().map { it.asString() }.orElse(null)
        val calls = mutableListOf<Pair<String, RawToolCall>>()
        val domains = mutableListOf<String>()
        var searchErrors = 0
        val text = StringBuilder()
        for (b in msg.content()) {
            b.text().ifPresent { text.append(it.text()) }
            b.toolUse().ifPresent { u -> calls += u.id() to RawToolCall(u.name(), input(u._input())) }
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
        val u = msg.usage()
        val usage = ClaudeUsage(
            input = u.inputTokens(),
            output = u.outputTokens(),
            cacheRead = u.cacheReadInputTokens().orElse(0L),
            cacheWrite = u.cacheCreationInputTokens().orElse(0L),
            searches = u.serverToolUse().map { it.webSearchRequests() }.orElse(0L),
        )
        return Streamed.Ok(msg.toParam(), stop, text.toString(), calls, usage, domains.distinct(), searchErrors)
    }

    companion object {
        const val FIRST_OUTPUT_MS = 60_000L
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

    private fun outcome(o: ClaudeOutcome): ModelOutcome = when (o) {
        is ClaudeOutcome.Done -> ModelOutcome.Done(if (o.cut) DoneReason.LENGTH else DoneReason.END)
        is ClaudeOutcome.Calls -> ModelOutcome.Calls(o.calls)
        // The listener already put "Claude declined that one." in place of the reply.
        ClaudeOutcome.Declined -> ModelOutcome.Done(DoneReason.END)
        is ClaudeOutcome.Failed -> ModelOutcome.Failed(o.failure.text)
    }
}
