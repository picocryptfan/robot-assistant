// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.SystemClock
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotFold
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.OneShotStart
import app.heyrobot.core.OneShotTransport
import app.heyrobot.core.RLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

// VOICE PROCESS. Conversation mode's way to Robot's model (docs/conversation-mode.md 4.2, phase 5.4): REQ_ONE_SHOT over
// BrainKeeper's binding, the same client as the panel's (one numbering, one model). The request's two deadlines are
// kept here: not started on the model in time (others ahead of it in :brain's queue) cancels it, and so does its total
// time; the caller decides what stands in (the fast engine). A caller that stops (a session ending, Cancel on a summary)
// cancels it in :brain too. Nothing here logs content.

object BrainOneShots : OneShotTransport {
    /** How often the deadlines are looked at. */
    private const val WATCH_MS = 100L

    override suspend fun run(req: BrainMsg.OneShot, budget: OneShotBudget, onText: (String) -> Unit): OneShotResult = send(req, null, budget, onText)

    /**
     * 1.4 (Robot Files, plan §4.2): a FILE_READ of one page's picture, [jpeg] as `:files` made it. It crosses as a panel
     * photo does (a read-only SharedMemory, never a file); a model without vision answers NO_VISION (OneShotResult.Failed),
     * and the caller keeps the file for one that sees. A request that doesn't say it carries a picture isn't sent.
     */
    suspend fun runWithImage(req: BrainMsg.OneShot, jpeg: ByteArray, budget: OneShotBudget, onText: (String) -> Unit = {}): OneShotResult =
        if (!req.hasImage || req.kind != OneShotKind.FILE_READ) OneShotResult.Failed(BrainError.BAD_REQUEST) else send(req, jpeg, budget, onText)

    private suspend fun send(req: BrainMsg.OneShot, image: ByteArray?, budget: OneShotBudget, onText: (String) -> Unit): OneShotResult {
        if (image == null && req.hasImage) return OneShotResult.Failed(BrainError.BAD_REQUEST)
        val ctx = VoiceHub.app
        val askedAt = SystemClock.elapsedRealtime()
        // A translation someone waits for doesn't wait for a model to load: the fast engine answers meanwhile, and the
        // model loads for the next one.
        if (!budget.waitForLoad && !BrainKeeper.ready) {
            BrainKeeper.prewarm(ctx)
            return OneShotResult.Failed(BrainError.NOT_LOADED, unavailable = true)
        }
        // Even a ready model is checked first, by a status call through :brain's work thread (behind a rebuild, or an
        // unload's wait): for a translation the fast engine stands in for, that time counts towards its start deadline
        // (core OneShotStart), so the fast engine stands in at most 1.5 s after the line was asked, not 1.5 s after the check.
        val ready = if (budget.waitForLoad) CompletableDeferred(BrainKeeper.ensureLoaded(ctx)) else BrainKeeper.loading(ctx)
        val (c, t0, startBy) = when (val w = OneShotStart.await(budget, askedAt, SystemClock::elapsedRealtime, ready)) {
            is OneShotStart.Wait.Ended -> return w.result
            is OneShotStart.Wait.Ready -> Triple(w.client, w.from, w.startBy)
        }
        val (id, flow) = c.request(BrainProtocol.REQ_ONE_SHOT, req, image)
        val fold = OneShotFold(t0)
        val late = AtomicBoolean(false)
        val slow = AtomicBoolean(false)
        var finished = false
        try {
            val r = coroutineScope {
                // The deadlines: still waiting in :brain's queue after the start one, or running longer than the total
                // one since it started (it starts over after giving way), and it is cancelled there.
                val watch = launch {
                    while (true) {
                        delay(WATCH_MS)
                        val now = SystemClock.elapsedRealtime()
                        val over = if (!fold.started) startBy != null && now >= startBy else now - fold.startedAt >= budget.totalMs
                        if (over) {
                            if (fold.started) slow.set(true) else late.set(true)
                            c.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(id))
                            c.abandon(id)
                            break
                        }
                    }
                }
                flow.collect { m -> if (fold.event(m, SystemClock.elapsedRealtime())) onText(fold.soFar) }
                watch.cancel()
                when {
                    late.get() -> OneShotResult.NotStarted
                    slow.get() -> OneShotResult.TimedOut(fold.soFar)
                    else -> fold.closed()
                }
            }
            finished = true
            RLog.i("one_shot", "kind" to req.kind, "priority" to req.priority, "result" to RLog.Code(r.javaClass.simpleName),
                "restarts" to fold.restarts, "ms" to (SystemClock.elapsedRealtime() - t0))
            return r
        } finally {
            // Its caller stopped (a session ending, Cancel): the request goes in :brain too, waiting or running.
            if (!finished) {
                c.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(id))
                c.abandon(id)
            }
        }
    }
}
