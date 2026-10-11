package app.heyrobot.core

// Pure. A one-shot request as its client sees it (docs/conversation-mode.md 4.2, phase 5.4): the events :brain sends
// for it folded into one result (OneShotFold), with the client's two deadlines (OneShotBudget): one to start (a
// translation someone waits for goes to the fast engine instead) and one for the whole reply. The app's transport over
// the Messenger binding implements OneShotTransport; translations and summaries only see this interface.

/**
 * How long a one-shot may take: to start on the model, from the request ([startWithinMs], null: as long as it takes),
 * and to run once started ([totalMs]). [waitForLoad]: false when a model that isn't loaded yet should not be waited for
 * (the fast engine answers meanwhile).
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
            is BrainMsg.Done -> result = if (m.reason == DoneReason.CANCELLED) OneShotResult.Cancelled
                else OneShotResult.Done(text.toString(), m.reason, waitedMs = if (startedAt >= 0) startedAt - t0 else 0, ms = now - t0)
            is BrainMsg.Error -> result = OneShotResult.Failed(m.code, unavailable = m.code == BrainError.NOT_LOADED)
            else -> {}
        }
        return false
    }

    /** The stream ended without a terminal event (the model's process went). */
    fun closed(): OneShotResult = result ?: OneShotResult.Failed(BrainError.ENGINE, unavailable = true)
}
