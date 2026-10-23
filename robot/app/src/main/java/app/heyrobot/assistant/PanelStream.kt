// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.SystemClock
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainFail
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.ClockEcho
import app.heyrobot.core.ControlRefusal
import app.heyrobot.core.DoneCode
import app.heyrobot.core.DoneReason
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.StallTimer
import app.heyrobot.core.TurnModel
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// VOICE PROCESS. The model stream: Robot's own model's side of a turn, its events from :brain into the reply bubble
// (through PanelReplies) and the voice, and the watchdog for a model that stops answering. It keeps nothing of its own:
// the request under way, a refused control token's text, the turn's languages and the voice are PanelController's,
// through [PanelStream.Port]. It runs where the turn runs, on the main thread, and its watchdog in the panel's scope.

/** Robot's model's stream for the panel's turns, over [replies] and PanelController's [port]; [scope]: the panel's own. */
internal class PanelStream(private val scope: CoroutineScope, private val replies: PanelReplies, private val port: Port) {
    /** What the stream needs of PanelController. */
    interface Port {
        /** The request under way, which Stop cancels: set as it starts, 0 once it has ended. */
        var reqId: Int
        /** The turn's languages, for a failure's text. */
        val langs: Langs
        /** 1.0.2: a control token's text llama.cpp refused in this request, for the turn to leave out (gemma()). */
        fun refused(f: ControlRefusal.Found)
        /** Streamed text for the voice: the sentences it completes are spoken. */
        fun speak(replyId: String, text: String)
    }

    /** The model side of a turn on reply [replyId]: [gen] (and [img]) to :brain through [c]. */
    fun turn(c: BrainClient, convId: String, replyId: String, gen: BrainMsg.Generate, img: ByteArray?): TurnModel =
        BrainTurn(c, convId, replyId, gen, img)

    /** The model side of a turn: :brain through [BrainClient]. Nothing is sent once the turn was stopped. */
    private inner class BrainTurn(
        private val c: BrainClient, private val convId: String, private val replyId: String,
        private val gen: BrainMsg.Generate, private val img: ByteArray?,
    ) : TurnModel {
        private var round = 0

        override suspend fun ask(): ModelOutcome {
            currentCoroutineContext().ensureActive()
            VoiceLog.add(VoiceEvent.BRAIN_SENT, 0, if (img != null || gen.hasImage) 1 else 0)
            return collect(c, c.request(BrainProtocol.REQ_GENERATE, gen, img), replyId)
        }

        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            currentCoroutineContext().ensureActive()
            VoiceLog.add(VoiceEvent.BRAIN_SENT, ++round, 0)
            return collect(c, c.request(BrainProtocol.REQ_TOOL_RESULT, BrainMsg.ToolResult(convId, results)), replyId)
        }

        override fun close() = c.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(convId))

        /**
         * Stopped while its cards waited (Stop, the panel closed): no results will come, but :brain's conversation would
         * go on waiting for them, and that wait keeps conversation mode's translations and summaries off the model for
         * up to 90 s (BrainScheduler.TOOL_HOLD_MS). It goes now; the next question rebuilds it from the history, as it
         * would have anyway.
         */
        override fun stopped(results: List<Pair<String, Map<String, Any?>>>) = close()
    }

    /** One request's events ([req]: its id and its stream) into reply [replyId], until its end or the watchdog's. */
    suspend fun collect(c: BrainClient, req: Pair<Int, Flow<BrainMsg>>, replyId: String): ModelOutcome {
        port.reqId = req.first
        // 1.2: the model this request runs on, for its reply's numbers (BrainKeeper.measured).
        val plan = BrainKeeper.planNow()
        var calls: List<RawToolCall>? = null
        var out: ModelOutcome = ModelOutcome.Failed(PanelStrings.modelDidntAnswer().render(port.langs))
        // Each model round (the reply, then its text after a tool round) is checked for a copy of the clock at its start.
        val echo = ClockEcho()
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L
        var chars = 0
        var timedOut = false
        // A model that stops answering (a hung engine) must not leave "Thinking…" for ever: no event from :brain for
        // EVENT_GAP_MS, before the first one or between any two, ends the request here (it is cancelled there too).
        val stall = StallTimer(EVENT_GAP_MS, SystemClock.elapsedRealtime())
        val watchdog: Job = scope.launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                if (stall.stalled(SystemClock.elapsedRealtime())) {
                    timedOut = true
                    c.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(req.first))
                    c.abandon(req.first)
                    break
                }
            }
        }
        try {
            req.second.collect { m ->
                stall.event(SystemClock.elapsedRealtime())
                when (m) {
                    is BrainMsg.Chunk -> if (m.text.isNotEmpty()) {
                        if (first < 0) {
                            first = SystemClock.elapsedRealtime() - t0
                            VoiceLog.add(VoiceEvent.BRAIN_FIRST, first.toInt())
                        }
                        chars += m.text.length
                        streamText(replyId, echo.feed(m.text))
                    }
                    is BrainMsg.ToolCallsMsg -> calls = m.calls
                    is BrainMsg.Done -> {
                        out = calls?.takeIf { m.reason == DoneReason.TOOL_CALLS }?.let { ModelOutcome.Calls(it) }
                            ?: ModelOutcome.Done(m.reason, m.textCalls)
                        VoiceLog.add(VoiceEvent.BRAIN_DONE, (SystemClock.elapsedRealtime() - t0).toInt(), DoneCode.of(m.reason).id, chars)
                        BrainKeeper.measured(plan, m)
                    }
                    is BrainMsg.Error -> {
                        // 1.0.2: a control token's text llama.cpp refused: what held it, for the turn to leave out (gemma()).
                        val refused = if (m.code == BrainError.ENGINE) ControlRefusal.parse(m.detail) else null
                        if (refused != null) port.refused(refused)
                        out = ModelOutcome.Failed((refused?.let(ControlRefusal::text) ?: m.code.textPhrase()).render(port.langs))
                        VoiceLog.add(VoiceEvent.BRAIN_FAILED, if (timedOut) BrainFail.TIMEOUT else BrainFail.of(m.code))
                    }
                    else -> {}
                }
            }
            // A reply shorter than the hold-back window.
            streamText(replyId, echo.flush())
        } finally {
            watchdog.cancel()
            port.reqId = 0
        }
        if (timedOut) out = ModelOutcome.Failed(PanelStrings.modelTimedOut().render(port.langs))
        return out
    }

    /** Streamed reply text: into the bubble, and the sentences it completes to the voice. */
    fun streamText(replyId: String, text: String) {
        if (text.isEmpty()) return
        replies.appendText(replyId, text)
        port.speak(replyId, text)
    }

    companion object {
        /** Nothing from the model this long, before its first word or between two: it is given up (a first word takes about 0.3 s). */
        const val EVENT_GAP_MS = 60_000L
        const val WATCHDOG_TICK_MS = 5_000L
    }
}
