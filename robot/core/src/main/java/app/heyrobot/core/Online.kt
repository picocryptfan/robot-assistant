package app.heyrobot.core

// Pure. An online tool's blocking work (0.4.0, the weather) that Stop or its deadline can end: whoever waits on the
// network or on a location fix registers how to give up ([onCancel]: a connection's disconnect, a latch's count-down),
// and [cancel] runs those hooks once. Timeouts are cut to what is left before the deadline ([remainingMs]).

class Abort(deadlineMs: Long = NO_DEADLINE, private val clock: () -> Long = ::nowMs) {
    private val deadline = if (deadlineMs == NO_DEADLINE) Long.MAX_VALUE else clock() + deadlineMs
    private val hooks = mutableListOf<() -> Unit>()
    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Stop (or the deadline, [expired]) ended it: nothing more should be asked of the network. */
    @Volatile var cancelled = false
        private set

    /** The deadline passed (rather than Stop). */
    val expired: Boolean get() = clock() >= deadline

    /** Milliseconds left before the deadline, at least 0 (a long time without one). */
    fun remainingMs(): Long = if (deadline == Long.MAX_VALUE) Long.MAX_VALUE / 2 else (deadline - clock()).coerceAtLeast(0)

    /** Runs [hook] on [cancel] (at once if already cancelled); the returned function forgets it again. */
    fun onCancel(hook: () -> Unit): () -> Unit {
        val run = synchronized(hooks) { if (cancelled) true else { hooks += hook; false } }
        if (run) hook()
        return { synchronized(hooks) { hooks.remove(hook) } }
    }

    /**
     * The one app this work may open once it ends: true for the first to ask (the work itself, or what stands in for it
     * when the deadline passed), false for everyone after. So a late lookup and its fallback never both open Organic Maps.
     */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    fun cancel() {
        val todo = synchronized(hooks) {
            if (cancelled) return
            cancelled = true
            hooks.toList().also { hooks.clear() }
        }
        todo.forEach { try { it() } catch (_: Exception) {} }
    }

    companion object {
        const val NO_DEADLINE = -1L
        private fun nowMs() = System.nanoTime() / 1_000_000
    }
}
