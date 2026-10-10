@file:OptIn(ExperimentalApi::class)

package app.heyrobot.brain

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Limits
import app.heyrobot.core.Prompt
import app.heyrobot.core.PromptRole
import app.heyrobot.core.PromptTurn
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolSpecs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.CancellationException

// :BRAIN PROCESS ONLY. The one file that uses LiteRT-LM (ProcessIsolationTest enforces that).
//
// Gemma 4 E2B on the Tensor G5 TPU exactly as the probe and the GPU lab (config T) load it:
// Backend.NPU(nativeLibraryDir), the vision encoder on the NPU with a text-only fallback, maxNumTokens
// 4096, cacheDir null, no sampler on the NPU. Every method runs on BrainService's work thread; LiteRT's
// callbacks are posted back to it, so the state below has a single thread.

class GemmaEngine(private val ctx: Context, private val handler: Handler) {
    fun interface Sink { fun emit(what: Int, msg: BrainMsg) }

    private var engine: Engine? = null
    private var vision = false
    /**
     * How tools reach the model, stepped down for good (until the process restarts) when this TPU build
     * refuses a level: [TOOLS_CONSTRAINED] LiteRT-LM tools with conversation constrained decoding, [TOOLS]
     * without it, [TEXT_CALLS] no tool API, the system prompt shows the call syntax and the app parses it.
     */
    private var toolMode = TOOLS_CONSTRAINED
    private val constrained get() = toolMode == TOOLS_CONSTRAINED
    private val convs = HashMap<String, Live>()
    private var running: Run? = null
    /** A request that arrived while another was being cancelled: it starts when that one ends. */
    private var queued: (() -> Unit)? = null

    private class Live(val conv: Conversation, var historyCount: Int, val toolMode: Int, val systemKey: String) {
        var dirty = false
        var awaitingTool = false
    }

    private inner class Run(
        val reqId: Int,
        val convId: String,
        val live: Live,
        val sink: Sink,
        val rebuilt: Boolean,
        /** Resend on a fresh conversation one tool level down if the first message fails before any output. */
        val retry: (() -> Unit)?,
    ) {
        val tSend = SystemClock.elapsedRealtime()
        var tFirst = -1L
        var chunks = 0
        val reply = StringBuilder()
        val calls = mutableListOf<RawToolCall>()
        var stopReason: DoneReason? = null
        var thoughtSent = false
        var ended = false
        /** Set from LiteRT's own thread when it called onDone/onError: the native side is finished with the conversation. */
        @Volatile var nativeEnded = false
    }

    // ------------------------------------------------------------------ load

    private fun npu() = Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir)

    private fun config(path: String, withVision: Boolean) = EngineConfig(
        modelPath = path,
        backend = npu(),
        visionBackend = if (withVision) npu() else null,
        audioBackend = null,
        maxNumTokens = ContextWindow.MAX_TOKENS,
        cacheDir = null,
    )

    fun status() = BrainMsg.Status(loaded = engine != null, vision = vision, constrained = constrained, busy = running != null)

    fun load(path: String): BrainMsg.Loaded {
        if (engine != null) return BrainMsg.Loaded(true, vision, 0, null)
        val f = File(path)
        // Only the model file in Robot's own storage.
        val allowed = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).any { f.canonicalPath.startsWith(it.canonicalPath + File.separator) }
        if (!allowed || !f.isFile) return BrainMsg.Loaded(false, false, 0, "The model file is missing.")
        ExperimentalFlags.enableBenchmark = true
        val t0 = SystemClock.elapsedRealtime()
        var e: Engine? = null
        var visionError: String? = null
        try {
            e = Engine(config(path, withVision = true))
            e.initialize()
            // The vision encoder starts with the first conversation: open one so a TPU failure shows here.
            e.createConversation().close()
        } catch (ex: Exception) {
            // The lab and Off Grid catch Exception only here; anything else is a real crash.
            visionError = ex.javaClass.simpleName
            try { e?.close() } catch (_: Exception) {}
            e = null
        }
        vision = e != null
        if (e == null) {
            try {
                e = Engine(config(path, withVision = false))
                e.initialize()
            } catch (ex: Exception) {
                try { e?.close() } catch (_: Exception) {}
                RLog.w("brain_load", ex, "ok" to false)
                return BrainMsg.Loaded(false, false, SystemClock.elapsedRealtime() - t0, "The model didn't load (${ex.javaClass.simpleName}).")
            }
        }
        engine = e
        val ms = SystemClock.elapsedRealtime() - t0
        RLog.i("brain_load", "ok" to true, "ms" to ms, "vision" to vision, "visionError" to visionError?.let { RLog.Code(it) })
        return BrainMsg.Loaded(true, vision, ms, null)
    }

    fun unload(why: String) {
        val e = engine ?: return
        queued = null
        running?.let { r ->
            try { r.live.conv.cancelProcess() } catch (_: Exception) {}
            // Never close a conversation LiteRT is still decoding into: wait (bounded) for its callback.
            var waited = 0
            while (!r.nativeEnded && waited < 3_000) { Thread.sleep(50); waited += 50 }
            finish(r, DoneReason.CANCELLED)
        }
        convs.values.forEach { closeQuietly(it.conv) }
        convs.clear()
        engine = null
        try { e.close() } catch (ex: Exception) { RLog.w("brain_unload", ex) }
        RLog.i("brain_unload", "why" to RLog.Code(why))
    }

    // ------------------------------------------------------------------ conversations

    private val toolProviders: List<ToolProvider> by lazy {
        ToolSpecs.ALL.map { spec ->
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String = spec.openApiJson()
                // Never called: automaticToolCalling is off, the app process runs tools after its approval gate.
                override fun execute(paramsJsonString: String): String = "{\"status\":\"failed\",\"detail\":\"tools run in the app\"}"
            })
        }
    }

    /** System prompt plus tool descriptions, as estimated tokens (the template adds a little more). */
    private fun fixedTokens(system: String) = TokenEstimate.text(system) + ToolSpecs.ALL.sumOf { TokenEstimate.text(it.openApiJson()) } + 40

    private fun newConversation(system: String, history: List<PromptTurn>): Pair<Conversation, Int> {
        val e = engine ?: throw IllegalStateException("not loaded")
        while (true) {
            val mode = toolMode
            val cfg = ConversationConfig(
                systemInstruction = Contents.of(if (mode == TEXT_CALLS) system + Prompt.TEXT_CALL_HINT else system),
                initialMessages = history.map { if (it.role == PromptRole.USER) Message.user(it.text) else Message.model(it.text) },
                tools = if (mode == TEXT_CALLS) emptyList() else toolProviders,
                samplerConfig = null,
                automaticToolCalling = false,
            )
            try {
                ExperimentalFlags.enableConversationConstrainedDecoding = mode == TOOLS_CONSTRAINED
                return e.createConversation(cfg) to mode
            } catch (ex: Exception) {
                if (mode == TEXT_CALLS) throw ex
                RLog.w("brain_tool_mode_down", ex, "from" to mode)
                toolMode = mode - 1
            } finally {
                ExperimentalFlags.enableConversationConstrainedDecoding = false
            }
        }
    }

    private fun closeQuietly(c: Conversation) {
        try { if (c.isAlive) c.close() } catch (_: Exception) {}
    }

    fun closeConv(convId: String) {
        val r = running
        if (r != null && r.convId == convId) {
            try { r.live.conv.cancelProcess() } catch (_: Exception) {}
            r.live.dirty = true
            r.stopReason = DoneReason.CANCELLED
            return
        }
        convs.remove(convId)?.let { closeQuietly(it.conv) }
    }

    // ------------------------------------------------------------------ generate

    fun generate(reqId: Int, req: BrainMsg.Generate, image: ByteArray?, sink: Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        if (req.hasImage && image == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "image missing"))
        if (image != null && !vision) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NO_VISION, null))
        if (afterRunning { generate(reqId, req, image, sink) }) return
        startGenerate(reqId, req, image, sink, allowRetry = true)
    }

    private fun startGenerate(reqId: Int, req: BrainMsg.Generate, image: ByteArray?, sink: Sink, allowRetry: Boolean) {
        val newTurn = TokenEstimate.turn(PromptTurn(PromptRole.USER, req.text, image != null))
        var live = convs[req.convId]
        val reuse = live != null && !live.dirty && !live.awaitingTool && live.historyCount == req.historyCount &&
            live.systemKey == systemKey(req.system) && !ContextWindow.needsRebuild(tokenCount(live.conv), newTurn)
        var rebuilt = false
        if (!reuse) {
            live?.let { closeQuietly(it.conv) }
            convs.remove(req.convId)
            val budget = ContextWindow.historyBudget(fixedTokens(req.system), newTurn)
            val history = ContextWindow.fit(req.history, budget)
            val (conv, usedMode) = try {
                newConversation(req.system, history)
            } catch (ex: Exception) {
                RLog.w("brain_conversation", ex)
                return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.ENGINE, ex.javaClass.simpleName))
            }
            live = Live(conv, req.historyCount, usedMode, systemKey(req.system)).also { convs[req.convId] = it }
            rebuilt = true
            RLog.i("brain_rebuild", "kept" to history.size, "of" to req.history.size, "budget" to budget)
        }
        val l = live
        // After this exchange the client's history holds two more turns (the question and the reply).
        l.historyCount = req.historyCount + 2
        val retry: (() -> Unit)? = if (allowRetry && l.toolMode > TEXT_CALLS) {
            {
                toolMode = minOf(toolMode, l.toolMode - 1)
                l.dirty = true
                RLog.i("brain_tool_mode_down", "to" to toolMode)
                startGenerate(reqId, req, image, sink, allowRetry = toolMode > TEXT_CALLS)
            }
        } else null
        val run = Run(reqId, req.convId, l, sink, rebuilt, retry)
        val parts = buildList {
            image?.let { add(Content.ImageBytes(it)) }
            add(Content.Text(req.text))
        }
        send(run) { cb -> l.conv.sendMessageAsync(Message.user(Contents.of(parts)), cb) }
    }

    fun toolResult(reqId: Int, req: BrainMsg.ToolResult, sink: Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        if (afterRunning { toolResult(reqId, req, sink) }) return
        val l = convs[req.convId]
        if (l == null || !l.awaitingTool || l.dirty) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "no tool call pending"))
        l.awaitingTool = false
        val run = Run(reqId, req.convId, l, sink, rebuilt = false, retry = null)
        val parts = req.results.map { (name, response) -> Content.ToolResponse(name, response) }
        send(run) { cb -> l.conv.sendMessageAsync(Message.tool(Contents.of(parts)), cb) }
    }

    /** If a reply is running, cancels it and queues [next] for when it ends (the newest request wins). */
    private fun afterRunning(next: () -> Unit): Boolean {
        val r = running ?: return false
        if (r.stopReason == null) {
            r.stopReason = DoneReason.CANCELLED
            try { r.live.conv.cancelProcess() } catch (_: Exception) {}
        }
        queued = next
        return true
    }

    fun cancel(target: Int) {
        val r = running ?: return
        if (r.reqId != target || r.stopReason != null) return
        r.stopReason = DoneReason.CANCELLED
        try { r.live.conv.cancelProcess() } catch (_: Exception) {}
    }

    /**
     * The system prompt without its clock time: a live conversation is reused while only the minutes moved,
     * and rebuilt when the date or the lock state in the prompt changed.
     */
    private fun systemKey(system: String) = system.replace(Regex("""\b\d{1,2}:\d{2}\b"""), "")

    private fun tokenCount(c: Conversation): Int = try { c.getTokenCount() } catch (_: Exception) { Int.MAX_VALUE / 2 }

    private fun send(run: Run, start: (MessageCallback) -> Unit) {
        running = run
        val cb = object : MessageCallback {
            override fun onMessage(message: Message) { handler.post { onChunk(run, message) } }
            override fun onDone() { run.nativeEnded = true; handler.post { onEnd(run, null) } }
            override fun onError(throwable: Throwable) { run.nativeEnded = true; handler.post { onEnd(run, throwable) } }
        }
        try {
            start(cb)
        } catch (ex: Exception) {
            run.nativeEnded = true
            onEnd(run, ex)
        }
    }

    private fun onChunk(run: Run, m: Message) {
        if (run.ended) return
        if (m.toolCalls.isNotEmpty()) run.calls += m.toolCalls.map { RawToolCall(it.name, it.arguments) }
        if (!run.thoughtSent && !m.channels["thought"].isNullOrEmpty()) {
            run.thoughtSent = true
            run.sink.emit(BrainProtocol.EV_CHUNK, BrainMsg.Chunk("", thought = true))
        }
        val text = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        if (text.isEmpty() || run.stopReason != null) return
        if (run.tFirst < 0) run.tFirst = SystemClock.elapsedRealtime()
        run.chunks++
        run.reply.append(text)
        run.sink.emit(BrainProtocol.EV_CHUNK, BrainMsg.Chunk(text))
        val stop = when {
            garbage(text, run.reply) -> DoneReason.GARBAGE
            run.chunks >= Limits.MAX_REPLY_CHUNKS -> DoneReason.LENGTH
            else -> null
        }
        if (stop != null) {
            run.stopReason = stop
            try { run.live.conv.cancelProcess() } catch (_: Exception) {}
        }
    }

    private fun onEnd(run: Run, error: Throwable?) {
        if (run.ended) return
        run.ended = true
        when {
            error == null || error is CancellationException || run.stopReason != null -> {
                val reason = run.stopReason ?: if (error != null) DoneReason.CANCELLED else if (run.calls.isNotEmpty()) DoneReason.TOOL_CALLS else DoneReason.END
                if (reason == DoneReason.TOOL_CALLS) {
                    run.live.awaitingTool = true
                    run.sink.emit(BrainProtocol.EV_TOOL_CALLS, BrainMsg.ToolCallsMsg(run.calls))
                }
                // A stopped reply leaves the conversation in an unknown state: rebuild it next time.
                if (reason != DoneReason.END && reason != DoneReason.TOOL_CALLS) run.live.dirty = true
                finish(run, reason)
            }
            run.retry != null && run.chunks == 0 && run.calls.isEmpty() -> {
                RLog.w("brain_send_retry", error)
                running = null
                run.retry.invoke()
            }
            else -> {
                RLog.w("brain_send", error, "chunks" to run.chunks)
                run.live.dirty = true
                running = null
                run.sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.ENGINE, error.javaClass.simpleName))
                runQueued()
            }
        }
    }

    private fun finish(run: Run, reason: DoneReason) {
        run.ended = true
        if (running === run) running = null
        val bench = try { run.live.conv.getBenchmarkInfo() } catch (_: Throwable) { null }
        val tokens = if (run.live.conv.isAlive) tokenCount(run.live.conv) else -1
        val ttft = if (run.tFirst >= 0) run.tFirst - run.tSend else -1
        run.sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(reason, tokens, ttft, bench?.lastDecodeTokensPerSecond ?: -1.0, run.rebuilt))
        RLog.i("brain_reply", "reason" to reason, "chunks" to run.chunks, "ttftMs" to ttft, "tokens" to tokens,
            "tps" to (bench?.lastDecodeTokensPerSecond ?: -1.0), "rebuilt" to run.rebuilt, "calls" to run.calls.size)
        runQueued()
    }

    private fun runQueued() {
        val q = queued ?: return
        queued = null
        q()
    }

    companion object {
        const val TEXT_CALLS = 0
        const val TOOLS = 1
        const val TOOLS_CONSTRAINED = 2
    }

    /** Broken output (as the lab detects it): pad/unused tokens, or one character 32+ times in a row. */
    private fun garbage(chunk: String, reply: CharSequence): Boolean {
        if (chunk.contains("<pad>") || chunk.contains("<unused")) return true
        if (reply.length < 32) return false
        val c = reply[reply.length - 1]
        if (c.isWhitespace()) return false
        for (i in reply.length - 32 until reply.length) if (reply[i] != c) return false
        return true
    }
}
