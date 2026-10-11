package app.heyrobot.assistant

import android.os.SystemClock
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotFold
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.OneShotTransport
import app.heyrobot.core.RLog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// VOICE PROCESS. Conversation mode's way to Robot's model (docs/conversation-mode.md 4.2, phase 5.4): REQ_ONE_SHOT over
// BrainKeeper's binding, the same client as the panel's (one numbering, one model). The request's two deadlines are
// kept here: not started on the model in time (others ahead of it in :brain's queue) cancels it, and so does its total
// time; the caller decides what stands in (the fast engine). A caller that stops (a session ending, Cancel on a summary)
// cancels it in :brain too. Nothing here logs content.

object BrainOneShots : OneShotTransport {
    override suspend fun run(req: BrainMsg.OneShot, budget: OneShotBudget, onText: (String) -> Unit): OneShotResult {
        val ctx = VoiceHub.app
        // A translation someone waits for doesn't wait for a model to load: the fast engine answers meanwhile, and the
        // model loads for the next one.
        val c = if (!budget.waitForLoad && !BrainKeeper.ready) {
            BrainKeeper.prewarm(ctx)
            return OneShotResult.Failed(BrainError.NOT_LOADED, unavailable = true)
        } else BrainKeeper.ensureLoaded(ctx) ?: return OneShotResult.Failed(null, unavailable = true)
        val t0 = SystemClock.elapsedRealtime()
        val (id, flow) = c.request(BrainProtocol.REQ_ONE_SHOT, req)
        val fold = OneShotFold(t0)
        var late = false
        var finished = false
        try {
            val r = coroutineScope {
                // The start deadline: still waiting in :brain's queue after it, the request is cancelled there.
                val watch = budget.startWithinMs?.let { ms ->
                    launch {
                        delay(ms)
                        if (!fold.started) {
                            late = true
                            c.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(id))
                            c.abandon(id)
                        }
                    }
                }
                val all = withTimeoutOrNull(budget.totalMs) {
                    flow.collect { m -> if (fold.event(m, SystemClock.elapsedRealtime())) onText(fold.soFar) }
                    true
                }
                watch?.cancel()
                when {
                    late -> OneShotResult.NotStarted
                    all == null -> {
                        c.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(id))
                        c.abandon(id)
                        OneShotResult.TimedOut(fold.soFar)
                    }
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
