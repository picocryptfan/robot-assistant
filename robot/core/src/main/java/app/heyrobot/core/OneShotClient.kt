// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

// Pure. A one-shot request as its client sees it (docs/conversation-mode.md 4.2, phase 5.4): the events :brain sends
// for it folded into one result (OneShotFold), with the client's two deadlines (OneShotBudget): one to start (a
// translation someone waits for goes to the fast engine instead) and one for the whole reply. The app's transport over
// the Messenger binding implements OneShotTransport; translations and summaries only see this interface.

/**
 * How long a one-shot may take: to start on the model ([startWithinMs], null: as long as it takes; from the request when
 * [waitForLoad] is false, else from when the model is ready: OneShotStart), and to run once started ([totalMs]).
 * [waitForLoad]: false when a model that isn't loaded yet should not be waited for (the fast engine answers meanwhile).
 */
data class OneShotBudget(val startWithinMs: Long?, val totalMs: Long, val waitForLoad: Boolean = true)

/** How a one-shot ended for its client. */
sealed class OneShotResult {
    /** The reply ([reason] END, or LENGTH when its cap cut it), after waiting [waitedMs] in line; [ms] from the request to the end. */
    data class Done(val text: String, val reason: DoneReason, val waitedMs: Long = 0, val ms: Long = 0) : OneShotResult()

    /** It hadn't started on the model by its start deadline, and was cancelled (the fast engine answers instead). */
    data object NotStarted : OneShotResult()

    /** Its total deadline passed: cancelled; [partial] what had come. */
    data class TimedOut(val partial: String) : OneShotResult()

    /** Cancelled: by its caller, or by :brain (a newer request, the model unloaded meanwhile). */
    data object Cancelled : OneShotResult()

    /** No model to ask ([unavailable]: none loaded and not waited for, none chosen, :brain down) or it refused ([code]). */
    data class Failed(val code: BrainError?, val unavailable: Boolean = false) : OneShotResult()
}

/**
 * A one-shot's wait for the model's client (the app's BrainKeeper: a status round trip through :brain's work thread, or
 * a load) and the time its start deadline counts from. A request the fast engine stands in for ([OneShotBudget.waitForLoad]
 * false) counts it from when it was asked, that wait included, and waits for the client no longer than the deadline:
 * past it, NotStarted, while the check goes on by itself (it is not cancelled). Any other request waits for the client
 * (a load, if need be) and counts its start deadline from then, as a load isn't the queue it measures.
 */
object OneShotStart {
    sealed class Wait<out C> {
        /** The client; the time the request counts from ([from]) and must have started by ([startBy], null: no deadline). */
        data class Ready<C>(val client: C, val from: Long, val startBy: Long?) : Wait<C>()

        /** No request to send: [result] is how it ended. */
        data class Ended(val result: OneShotResult) : Wait<Nothing>()
    }

    /** Waits for [client] for a request asked at [askedAt] within [budget] ([now]: the same clock). */
    suspend fun <C : Any> await(budget: OneShotBudget, askedAt: Long, now: () -> Long, client: Deferred<C?>): Wait<C> {
        val within = budget.startWithinMs
        if (!budget.waitForLoad && within != null && !client.isCompleted) {
            val left = askedAt + within - now()
            if ((left <= 0 || withTimeoutOrNull(left) { client.join() } == null) && !client.isCompleted) return Wait.Ended(OneShotResult.NotStarted)
        }
        return ready(budget, askedAt, now, client.await())
    }

    private fun <C : Any> ready(budget: OneShotBudget, askedAt: Long, now: () -> Long, got: C?): Wait<C> {
        got ?: return Wait.Ended(OneShotResult.Failed(null, unavailable = true))
        val from = if (budget.waitForLoad) now() else askedAt
        return Wait.Ready(got, from, budget.startWithinMs?.let { from + it })
    }
}

/** How a client reaches :brain's one-shot requests (the app: over BrainKeeper's binding). */
interface OneShotTransport {
    /** Runs [req] within [budget]; [onText] gets the reply as it streams (it starts over, from "", when the request gave way). */
    suspend fun run(req: BrainMsg.OneShot, budget: OneShotBudget, onText: (String) -> Unit = {}): OneShotResult
}

/**
 * Folds one one-shot's events. EV_QUEUED 0 says it started; EV_QUEUED 1+ after text says it gave way to a request with
 * a higher priority and will start over, so what came is dropped. EV_DONE CANCELLED or EV_ERROR end it.
 */
class OneShotFold(private val t0: Long) {
    /** Read by the client's start deadline on another thread. */
    @Volatile var started = false
        private set
    /** Times it gave way and started over. */
    var restarts = 0
        private set
    private val text = StringBuilder()
    /** When it last started (the clock of [event]'s calls), -1 before. */
    @Volatile var startedAt = -1L
        private set
    @Volatile var result: OneShotResult? = null
        private set

    val soFar: String get() = text.toString()

    /** One event at [now]; true when the text changed (to show it). */
    fun event(m: BrainMsg, now: Long): Boolean {
        if (result != null) return false
        when (m) {
            is BrainMsg.Queued -> if (m.position == 0) {
                if (!started) {
                    started = true
                    startedAt = now
                }
            } else {
                // Waiting (again): it gave way, and starts over from nothing.
                val had = text.isNotEmpty() || started
                if (had) restarts++
                started = false
                text.setLength(0)
                return had
            }
            is BrainMsg.Chunk -> if (m.text.isNotEmpty()) {
                if (!started) {
                    started = true
                    startedAt = now
                }
                text.append(m.text)
                return true
            }
            is BrainMsg.Done -> result = when (m.reason) {
                DoneReason.CANCELLED -> OneShotResult.Cancelled
                // :brain stopped a broken reply (pad tokens, one character over and over): no translation or note comes of it.
                DoneReason.GARBAGE -> OneShotResult.Failed(BrainError.ENGINE)
                else -> OneShotResult.Done(text.toString(), m.reason, waitedMs = if (startedAt >= 0) startedAt - t0 else 0, ms = now - t0)
            }
            is BrainMsg.Error -> result = OneShotResult.Failed(m.code, unavailable = m.code == BrainError.NOT_LOADED)
            else -> {}
        }
        return false
    }

    /** The stream ended without a terminal event (the model's process went). */
    fun closed(): OneShotResult = result ?: OneShotResult.Failed(BrainError.ENGINE, unavailable = true)
}
