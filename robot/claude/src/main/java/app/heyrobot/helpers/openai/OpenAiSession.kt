package app.heyrobot.helpers.openai

import app.heyrobot.claude.ClaudeListener
import app.heyrobot.claude.ClaudeOutcome
import app.heyrobot.claude.ClaudeTurnReport
import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudeEffort
import app.heyrobot.core.ClaudeEffortSetting
import app.heyrobot.core.ClaudeEnd
import app.heyrobot.core.ClaudeFail
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ClaudePrompt
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolSpec
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperUsage
import app.heyrobot.core.helpers.Pricing
import app.heyrobot.core.helpers.Protocol
import app.heyrobot.core.helpers.wire.ChatHistory
import app.heyrobot.core.helpers.wire.ChatOptions
import app.heyrobot.core.helpers.wire.ChatReply
import app.heyrobot.core.helpers.wire.ChatWire
import app.heyrobot.core.helpers.wire.Extra
import app.heyrobot.core.helpers.wire.RespHistory
import app.heyrobot.core.helpers.wire.RespReply
import app.heyrobot.core.helpers.wire.RespWire
import app.heyrobot.core.helpers.wire.Step
import app.heyrobot.core.helpers.wire.Stop
import app.heyrobot.core.helpers.wire.WireErrors
import app.heyrobot.helpers.HelperSession
import app.heyrobot.helpers.http.HelperHttp
import app.heyrobot.helpers.http.NotLocalException
import app.heyrobot.helpers.http.SseReader
import app.heyrobot.helpers.http.textUpTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URI
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

// VOICE PROCESS ONLY, :claude module. One conversation with a helper that speaks Chat Completions or the Responses API
// (0.6.0): another company's model, or a model on your own server. It keeps ClaudeSession's promises (HelperSession) on
// the pure wire readers in core: every request streams; text goes to the listener as it arrives, reasoning never does;
// the helper's turns go back exactly as they came; a failed, declined or stopped question leaves no trace and tool rounds
// that ran stay; the history is checked before every request. Two things are its own:
// - Extras a server refuses (a 400 or 422 before any answer: stream_options, strict, tool_choice, effort, search, the
//   tools themselves, pictures) are dropped one at a time and the request sent again; what was dropped is remembered for
//   the helper ([dropped], [onDropped]) so later questions don't pay for it.
// - Waiting: silence is a timeout after 60 s at low effort, 120 s at medium and 240 s at high (hidden reasoning can be
//   silent a long time); a server on your network may take up to 5 minutes for its first byte (a model loading), and
//   after [loadingMs] Robot says "… is loading its model…".

class OpenAiSession(
    private val http: HelperHttp,
    val config: HelperConfig,
    private val tools: List<ToolSpec>,
    /** A conversation ("go get …"): the effort of its first question holds (a change would restart the cache). */
    private val conversation: Boolean,
    /** The model can see pictures (from the catalog, or learned); without, a picture's text only goes. */
    private val vision: Boolean = config.catalogModel?.vision ?: true,
    dropped: Set<Extra> = emptySet(),
    /** An extra the server refused was dropped (to remember it for the helper). */
    private val onDropped: (Extra) -> Unit = {},
    private val timing: Timing = Timing.of(config),
    private val retryDelayMs: Long = 1_000,
    private val clock: () -> Instant = Instant::now,
) : HelperSession {
    private val protocol = config.presetOf.protocol
    private val responses = protocol == Protocol.RESPONSES
    private val messages = mutableListOf<JSONObject>()
    private var before: List<JSONObject> = emptyList()
    private var keep = -1
    private var pending: List<String> = emptyList()
    private var plan: ClaudePlan = ClaudePlan.of(ClaudeEffortSetting.AUTO, voice = true, deep = false)
    private var firstPlan: ClaudePlan? = null
    private var report = ClaudeTurnReport()
    private val refused = dropped.toMutableSet()

    init {
        require(protocol == Protocol.CHAT || protocol == Protocol.RESPONSES)
    }

    /** Messages so far (tests read the shape). */
    internal val history: List<JSONObject> get() = messages.toList()

    /** Extras refused so far. */
    val droppedExtras: Set<Extra> get() = refused.toSet()

    override val fresh: Boolean get() = messages.isEmpty()
    override val lastReport: ClaudeTurnReport get() = report
    override val offered: Set<ToolName> = tools.map { it.tool }.toSet()

    override suspend fun ask(blocks: List<ClaudeBlock>, plan: ClaudePlan, listener: ClaudeListener): ClaudeOutcome {
        if (pending.isNotEmpty()) abandon(emptyList(), stopped = true)
        this.plan = if (!conversation) plan else firstPlan?.let { plan.keeping(it) } ?: plan.also { firstPlan = it }
        report = ClaudeTurnReport()
        // Items are never changed once in the history: a shallow copy is enough.
        before = messages.toList()
        keep = -1
        val images = vision && Extra.IMAGES !in refused
        messages += if (responses) RespWire.user(blocks, images) else ChatWire.user(blocks, images)
        return run(listener)
    }

    override suspend fun answer(results: List<Map<String, Any?>>, listener: ClaudeListener): ClaudeOutcome {
        val ids = pending
        pending = emptyList()
        addResults(ids, results)
        keep = messages.size
        return run(listener)
    }

    override fun abandon(results: List<Map<String, Any?>>, stopped: Boolean) {
        if (pending.isEmpty()) return
        if (stopped && results.isEmpty() && keep < 0) {
            rollBack()
            return
        }
        val why = if (stopped) "The user stopped before this ran." else "The turn ended before this ran."
        addResults(pending, pending.indices.map { i -> results.getOrNull(i) ?: mapOf("status" to "not_done", "detail" to why) })
        pending = emptyList()
        keep = messages.size
    }

    private fun addResults(ids: List<String>, results: List<Map<String, Any?>>) {
        for ((i, id) in ids.withIndex()) {
            val r = results.getOrNull(i) ?: mapOf("status" to "not_done", "detail" to "Robot didn't run this call.")
            messages += if (responses) RespWire.toolResult(id, r) else ChatWire.toolResult(id, r)
        }
    }

    private fun rollBack() {
        if (keep >= 0) {
            while (messages.size > keep) messages.removeAt(messages.lastIndex)
        } else {
            messages.clear()
            messages += before
        }
        pending = emptyList()
        if (messages.isEmpty()) firstPlan = null
    }

    private fun options(): ChatOptions {
        val search = config.searching
        val name = config.name
        val system = ClaudePrompt.system(name, tools.isNotEmpty(), search)
        return ChatOptions(config.model, config.presetOf.quirks, config.catalogModel?.effort ?: config.presetOf.quirks.effort, plan, tools, search, system,
            config.zdr, refused.toSet())
    }

    private suspend fun run(listener: ClaudeListener): ClaudeOutcome {
        var retried = false
        var adapted = 0
        try {
            while (true) {
                val problem = if (responses) RespHistory.check(messages) else ChatHistory.check(messages)
                if (problem != null) {
                    RLog.i("helper_history", "problem" to RLog.Code(problem))
                    rollBack()
                    return ClaudeOutcome.Failed(ClaudeFailure.RESET)
                }
                val o = options()
                val r = stream(o, listener)
                report = report.copy(requests = report.requests + 1)
                when (r) {
                    is Streamed.Failure -> {
                        if (r.failure.retry && !r.shown && !retried) {
                            retried = true
                            RLog.i("helper_retry", "failure" to r.failure)
                            delay(retryDelayMs)
                            continue
                        }
                        // A 400 or 422 before any answer, with something optional in the request: once more without it (the
                        // part the error names, when it names one). Not for a request that was too long or a key or account
                        // problem: dropping a part wouldn't help, and it would be lost for every later question.
                        if (r.status in setOf(400, 422) && !r.shown && adapted < MAX_ADAPT && r.failure in ADAPTABLE) {
                            val carried = (if (responses) RespWire.extras(o) else ChatWire.extras(o)) + (if (hasImages()) setOf(Extra.IMAGES) else emptySet())
                            ChatWire.toDrop(carried, refused, r.param, o.tools.size.takeIf { Extra.TOOLS !in refused } ?: 0)?.let { extra ->
                                adapted++
                                refused += extra
                                onDropped(extra)
                                RLog.i("helper_adapt", "dropped" to extra, "status" to r.status)
                                continue
                            }
                        }
                        rollBack()
                        return ClaudeOutcome.Failed(r.failure)
                    }
                    is Streamed.Ok -> {
                        report = report.copy(domains = (report.domains + r.domains).distinct().take(MAX_DOMAINS), searches = report.searches + r.searches)
                        when (r.stop) {
                            Stop.DECLINED -> {
                                rollBack()
                                listener.declined()
                                return ClaudeOutcome.Declined
                            }
                            Stop.CALLS -> {
                                append(r.echo)
                                pending = r.calls.map { it.first }
                                return ClaudeOutcome.Calls(r.calls.map { it.second })
                            }
                            Stop.CUT -> {
                                append(r.echo, closed = true)
                                return ClaudeOutcome.Done(cut = true)
                            }
                            Stop.END -> {
                                append(r.echo)
                                return ClaudeOutcome.Done()
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            rollBack()
            throw e
        }
    }

    private fun hasImages(): Boolean = messages.any { m ->
        val c = m.optJSONArray("content") ?: return@any false
        (0 until c.length()).any { c.optJSONObject(it)?.optString("type").let { t -> t == "image_url" || t == "input_image" } }
    }

    /** The helper's turn into the history ([closed]: cut at its length, so no unanswered call goes back). */
    private fun append(echo: List<JSONObject>, closed: Boolean = false) {
        for (item in echo) {
            if (!responses) {
                if (closed) item.remove("tool_calls")
                // A message with nothing in it would be refused next time: it says "…".
                if ((item.isNull("content") || item.optString("content").isEmpty() && item.optJSONArray("content") == null) && !item.has("tool_calls")) item.put("content", "…")
            }
            messages += item
        }
        if (responses && echo.isEmpty()) messages += JSONObject().put("role", "assistant").put("content", "…")
    }

    private sealed class Streamed {
        class Ok(val echo: List<JSONObject>, val stop: Stop, val calls: List<Pair<String, RawToolCall>>, val domains: List<String>, val searches: Int) : Streamed()
        class Failure(val failure: ClaudeFailure, val shown: Boolean, val status: Int = 0, val param: String? = null) : Streamed()
    }

    private sealed class Event {
        class Text(val delta: String) : Event()
        class Alive(val thinking: Boolean) : Event()
        class Used(val usage: HelperUsage) : Event()
        class Finished(val ok: Streamed.Ok) : Event()
        class Failed(val failure: ClaudeFailure, val status: Int, val param: String? = null) : Event()
        class Cut : Event()
    }

    /** One request, streamed on its own thread; its events come back through a channel, so a Stop cancels at once. */
    private suspend fun stream(o: ChatOptions, listener: ClaudeListener): Streamed {
        val body = if (responses) RespWire.request(o, messages) else ChatWire.request(o, messages)
        val call = http.post(if (responses) "responses" else "chat/completions", body)
        val events = Channel<Event>(Channel.UNLIMITED)
        val gone = AtomicBoolean(false)
        val home = config.home
        val worker = Thread({
            // The server answered (a failure after this is a dropped connection, not an unreachable server).
            var opened = false
            try {
                call.execute().use { resp ->
                    opened = true
                    if (!resp.isSuccessful) {
                        val text = try { resp.body?.source()?.let { s -> s.request(MAX_ERROR_BYTES); s.buffer.readUtf8(minOf(s.buffer.size, MAX_ERROR_BYTES)) } } catch (_: Exception) { null }
                        val status = resp.code
                        val e = WireErrors.parse(text)
                        val f = if (home && status == 404) ClaudeFailure.MODEL_UNAVAILABLE else e.failure(status)
                        events.trySend(Event.Failed(f, status, e.param))
                        return@Thread
                    }
                    val source = resp.body?.source() ?: throw java.io.IOException("no body")
                    val sse = resp.header("Content-Type")?.contains("event-stream") != false
                    val chat = if (responses) null else ChatReply()
                    val resp2 = if (responses) RespReply() else null
                    var skipped = 0
                    fun feed(payload: String) {
                        val step: Step = try {
                            chat?.add(payload) ?: resp2!!.add(payload)
                        } catch (t: Throwable) {
                            if (t is VirtualMachineError) throw t
                            if (++skipped <= 3) RLog.w("helper_event_skipped", t)
                            Step()
                        }
                        (chat?.usage ?: resp2?.usage)?.let { events.trySend(Event.Used(it)) }
                        events.trySend(if (step.text.isNotEmpty()) Event.Text(step.text) else Event.Alive(step.thinking))
                    }
                    if (sse) {
                        val reader = SseReader(source)
                        while (true) {
                            if (gone.get()) throw CancellationException()
                            val payload = reader.next { events.trySend(Event.Alive(false)) } ?: break
                            if (payload.isBlank()) continue
                            feed(payload)
                            val err = chat?.error ?: resp2?.error
                            if (err != null) {
                                events.trySend(Event.Failed(err.failure(200), 200))
                                return@Thread
                            }
                        }
                    } else {
                        // A server that answered without streaming: the whole reply at once.
                        feed(source.textUpTo(MAX_WHOLE_BYTES))
                    }
                    if (chat != null) {
                        val d = chat.done()
                        if (d.tail.isNotEmpty()) events.trySend(Event.Text(d.tail))
                        d.usage?.let { events.trySend(Event.Used(it)) }
                        events.trySend(if (d.complete || !sse) Event.Finished(Streamed.Ok(listOf(d.message), d.stop, d.calls, d.sources.mapNotNull(::host), 0)) else Event.Cut())
                    } else {
                        val d = resp2!!.done()
                        d.usage?.let { events.trySend(Event.Used(it)) }
                        events.trySend(if (d.complete) Event.Finished(Streamed.Ok(d.items, d.stop, d.calls, d.sources.mapNotNull(::host), d.searches)) else Event.Cut())
                    }
                }
            } catch (t: Throwable) {
                if (!gone.get()) {
                    RLog.w("helper_request", t, "home" to home)
                    events.trySend(Event.Failed(failure(t, home, opened), 0))
                }
            } finally {
                events.close()
            }
        }, "robot-helper")
        worker.isDaemon = true
        worker.start()
        var shown = false
        var heard = false
        var thinking = false
        var usage: HelperUsage? = null
        var counted = 0.0
        val said = StringBuilder()
        val t0 = System.currentTimeMillis()
        var ended: Streamed? = null
        fun end(r: Streamed): Streamed { ended = r; return r }
        VoiceLog.add(VoiceEvent.CLAUDE_SENT, report.requests, 0, config.kind.id)
        fun count(u: HelperUsage) {
            val d = Pricing.dollars(config.price, u, clock()) ?: run {
                report = report.copy(costKnown = false)
                return
            }
            if (d > counted) {
                listener.spent(d - counted)
                report = report.copy(dollars = report.dollars + (d - counted))
                counted = d
            }
        }
        try {
            var loadingSaid = false
            while (true) {
                val wait = when {
                    !heard && home -> if (loadingSaid) timing.firstByteMs - timing.loadingMs else timing.loadingMs
                    else -> timing.idleMs(plan.effort)
                }
                val r = withTimeoutOrNull(wait) { events.receiveCatching() }
                if (r == null) {
                    if (!heard && home && !loadingSaid) {
                        loadingSaid = true
                        listener.loading()
                        continue
                    }
                    return end(Streamed.Failure(ClaudeFailure.TIMEOUT, shown))
                }
                val next = r.getOrNull()
                if (!heard && next != null && next !is Event.Failed) {
                    heard = true
                    VoiceLog.add(VoiceEvent.CLAUDE_FIRST, (System.currentTimeMillis() - t0).toInt())
                }
                when (next) {
                    null -> return end(Streamed.Failure(ClaudeFailure.UNKNOWN, shown))
                    is Event.Cut -> return end(Streamed.Failure(ClaudeFailure.UNKNOWN, shown))
                    is Event.Alive -> {
                        if (next.thinking && !thinking && !shown) listener.thinking()
                        thinking = next.thinking
                    }
                    is Event.Used -> usage = next.usage
                    is Event.Text -> {
                        thinking = false
                        shown = true
                        said.append(next.delta)
                        listener.text(next.delta)
                    }
                    is Event.Finished -> return end(next.ok)
                    is Event.Failed -> return end(Streamed.Failure(next.failure, shown, next.status, next.param))
                }
            }
        } finally {
            gone.set(true)
            val ms = (System.currentTimeMillis() - t0).toInt()
            when (val e = ended) {
                is Streamed.Ok -> VoiceLog.add(VoiceEvent.CLAUDE_DONE, ms, end(e.stop).id, said.length)
                is Streamed.Failure -> VoiceLog.add(VoiceEvent.CLAUDE_FAILED, ClaudeFail.of(e.failure).id, config.kind.id)
                null -> VoiceLog.add(VoiceEvent.CLAUDE_DONE, ms, ClaudeEnd.STOPPED.id, said.length)
            }
            withContext(NonCancellable + Dispatchers.IO) { try { call.cancel() } catch (_: Throwable) {} }
            // However it ended, what was billed is counted: without the server's usage (a stop, a cut, a server that sends
            // none), estimated from the request and what was said, once the server had started answering.
            val u = usage ?: if (!heard) null else HelperUsage(input = TokenEstimate.text(textOf(body)).toLong(), output = TokenEstimate.text(said.toString()).toLong())
            if (u != null) {
                val final = if (usage == null) u else u.copy(output = maxOf(u.output, TokenEstimate.text(said.toString()).toLong()))
                count(final)
                val all = report.usage
                report = report.copy(usage = app.heyrobot.core.ClaudeUsage(all.input + final.input, all.output + final.output, all.cacheRead + final.cacheRead,
                    all.cacheWrite + final.cacheWrite, all.searches + final.searches))
                RLog.i("helper_usage", "kind" to config.kind, "in" to final.input, "out" to final.output, "cached" to final.cacheRead,
                    "searches" to final.searches, "reported" to (final.reported != null), "final" to (usage != null))
            }
        }
    }

    private fun end(s: Stop): ClaudeEnd = when (s) {
        Stop.END -> ClaudeEnd.END
        Stop.CALLS -> ClaudeEnd.TOOL_USE
        Stop.CUT -> ClaudeEnd.CUT
        Stop.DECLINED -> ClaudeEnd.DECLINED
    }

    /** The words of a request (no pictures), for an estimate when the server sent no usage. */
    private fun textOf(body: JSONObject): String {
        val sb = StringBuilder(body.optString("instructions"))
        val list = body.optJSONArray("messages") ?: body.optJSONArray("input")
        if (list != null) for (i in 0 until list.length()) {
            val m = list.optJSONObject(i) ?: continue
            when (val c = m.opt("content")) {
                is String -> sb.append(c).append('\n')
                is org.json.JSONArray -> for (k in 0 until c.length()) c.optJSONObject(k)?.optString("text")?.let { sb.append(it).append('\n') }
            }
        }
        return sb.toString()
    }

    companion object {
        const val MAX_DOMAINS = 5
        /** Extras dropped for one question at most. */
        const val MAX_ADAPT = 4

        /** The failures a refused optional part can cause (Ollama says "does not support tools" as an api_error with a 400). */
        private val ADAPTABLE = setOf(ClaudeFailure.BAD_REQUEST, ClaudeFailure.SERVER, ClaudeFailure.UNKNOWN)
        private const val MAX_ERROR_BYTES = 16_384L
        /** A reply that came whole (not streamed) is read up to this much. */
        private const val MAX_WHOLE_BYTES = 8L * 1024 * 1024

        /**
         * An exception to Robot's words (its class only; the message can quote the request). [opened]: the server had
         * answered, so a failure now is a dropped connection or silence, not an unreachable server.
         */
        fun failure(t: Throwable, home: Boolean, opened: Boolean = false): ClaudeFailure = when (t) {
            is NotLocalException -> ClaudeFailure.NOT_LOCAL
            // OkHttp: http:// to a client that only speaks TLS.
            is UnknownServiceException -> ClaudeFailure.NOT_LOCAL
            is SSLPeerUnverifiedException, is SSLHandshakeException -> ClaudeFailure.CERTIFICATE
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> if (home) ClaudeFailure.UNREACHABLE else ClaudeFailure.NO_INTERNET
            is InterruptedIOException -> if (home && !opened) ClaudeFailure.UNREACHABLE else ClaudeFailure.TIMEOUT
            // A header OkHttp can't send ("Unexpected char … in Authorization value"): the key has a character a key never has.
            is IllegalArgumentException -> if (t.message?.startsWith("Unexpected char") == true) ClaudeFailure.KEY_REFUSED else ClaudeFailure.UNKNOWN
            is java.io.IOException -> if (home) ClaudeFailure.UNREACHABLE else ClaudeFailure.NO_INTERNET
            else -> ClaudeFailure.UNKNOWN
        }

        /** "en.wikipedia.org" from a URL, without "www.". */
        internal fun host(url: String): String? = try { URI(url).host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
    }
}

/**
 * How long a helper may stay silent: [idleMs] between events by effort, [firstByteMs] for a home server's first byte (a
 * cold model), [loadingMs] before Robot says it is loading.
 */
data class Timing(val low: Long, val medium: Long, val high: Long, val firstByteMs: Long, val loadingMs: Long) {
    fun idleMs(e: ClaudeEffort): Long = when (e) {
        ClaudeEffort.LOW -> low
        ClaudeEffort.MEDIUM -> medium
        ClaudeEffort.HIGH -> high
    }

    companion object {
        val CLOUD = Timing(60_000, 120_000, 240_000, 60_000, Long.MAX_VALUE)
        val HOME = Timing(120_000, 120_000, 240_000, 300_000, 8_000)

        fun of(h: HelperConfig): Timing = if (h.home) HOME else CLOUD
    }
}
