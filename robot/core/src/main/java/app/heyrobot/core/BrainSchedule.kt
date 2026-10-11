package app.heyrobot.core

// Pure. How :brain serves several clients with one model (docs/conversation-mode.md 4.2, phase 5.4): BrainQueue's order
// with each request's work attached (BrainScheduler), and what a one-shot request gets (OneShotRules). GemmaEngine only
// acts on them; every call happens on :brain's one work thread.
//
// A one-shot is a translation, or a summary's step: one message to a conversation of its own, with its own system
// prompt, no history, no tools and constrained decoding off, streamed, then closed. It never reuses the assistant's
// conversation and the assistant never reuses its: LiteRT keeps one conversation at a time on this engine, so a one-shot
// closes the assistant's (which is rebuilt from the app's own history at the next question) and is closed itself when
// it ends. A call it writes anyway is dropped (ToolMode.CHAT_ONLY, as for a short-context model): it never reaches the
// app. It runs at the level it asked for, never changes the engine's tool level, and is never sent again after a
// failure (a summary step that gave way starts over from nothing).

/**
 * :brain's requests in [BrainQueue]'s order, each with its [Work] (GemmaEngine's). Clients number their own requests, so
 * a request is known by its client and number ([Key]); inside, each gets an id of its own. Work may end synchronously
 * inside [Work.start] (a request refused before it is sent): the scheduler runs one step at a time, so nothing re-enters.
 * While the panel's conversation waits for its tool results ([holdForTools]) only panel requests start, for at most
 * [toolHoldMs]; after that the hold lapses (a card left open for minutes must not stop the interpreter for ever), and a
 * one-shot may close that conversation: its tool results then end the reply (GemmaEngine).
 */
class BrainScheduler(private val clock: () -> Long, val toolHoldMs: Long = TOOL_HOLD_MS) {
    /** One request's work. Every call on :brain's work thread. */
    interface Work {
        val priority: Priority

        /** Start request [id], or start it over after it gave way. It ends through [BrainScheduler.ended]. */
        fun start(id: Int)

        /**
         * Stop it while it runs: a newer panel request or the client's REQ_CANCEL ([giveWay] false: end it as cancelled),
         * or background work giving way ([giveWay] true: no terminal event, it starts over later).
         */
        fun stop(giveWay: Boolean)

        /** Its place in line (EV_QUEUED): 1 and up while it waits, 0 as it starts (at once, or after waiting). */
        fun queued(position: Int)

        /** It ends without running: dropped by a newer panel request, cancelled while waiting, or [unloaded] (the model went). */
        fun dropped(unloaded: Boolean)
    }

    /** A request as its client numbered it: [client] tells clients apart (their reply binder), [reqId] is theirs. */
    data class Key(val client: Any?, val reqId: Int)

    private val queue = BrainQueue()
    private val works = HashMap<Int, Work>()
    private val ids = HashMap<Key, Int>()
    private val keys = HashMap<Int, Key>()
    /** Not yet told they started (EV_QUEUED 0, sent as they start; EV_QUEUED 1+ while they wait). */
    private val told = HashSet<Int>()
    private var seq = 0
    private val todo = ArrayDeque<() -> Unit>()
    private var draining = false
    private var holdWanted = false
    private var holdExpired = false
    private var holdSince = 0L

    /** The id of the request running now. */
    val running: Int? get() = queue.running?.id

    /** The waiting requests' ids, next first. */
    val waiting: List<Int> get() = queue.queue.map { it.id }

    /** Only panel requests start now (the panel's conversation waits for its tool results). */
    val holding: Boolean get() = queue.held

    fun position(key: Key): Int = ids[key]?.let(queue::position) ?: -1

    /** The id [key] got, while it waits or runs. */
    fun idOf(key: Key): Int? = ids[key]

    /** A new request: it starts now or waits ([Work.queued]). Its id. */
    fun submit(key: Key, work: Work): Int {
        val id = ++seq
        step {
            // A client counting from 1 again (a new binding) owns its number from now on, for REQ_CANCEL.
            ids[key] = id
            keys[id] = key
            works[id] = work
            val s = queue.submit(BrainQueue.Job(id, work.priority))
            // Told as it starts (EV_QUEUED 0), at once or later: its client knows it began, not just that it waits.
            told += id
            apply(s)
            if (id in told) queue.position(id).takeIf { it > 0 }?.let(work::queued)
        }
        return id
    }

    /**
     * The running request [id] ended. [terminalSent]: its client got EV_DONE or EV_ERROR; false for one stopped to give
     * way, which is queued again (EV_QUEUED), or, if its client cancelled it meanwhile, ended here as cancelled.
     */
    fun ended(id: Int, terminalSent: Boolean = true) = step {
        if (queue.running?.id != id) return@step
        val w = works[id]
        // A request whose client already got its end (it finished just as it was asked to give way) is never run again.
        val s = queue.done(id, finished = terminalSent)
        if (s.requeued?.id == id && w != null) {
            told += id
            w.queued(queue.position(id).coerceAtLeast(1))
        } else {
            forget(id)
            if (!terminalSent) w?.dropped(false)
        }
        apply(s.copy(requeued = null))
    }

    /** REQ_CANCEL for [key]: a waiting request ends now, a running one is stopped. */
    fun cancel(key: Key) = step {
        val id = ids[key] ?: return@step
        val s = queue.cancel(id)
        if (s.cancelRunning) works[id]?.stop(giveWay = false)
        apply(s.copy(cancelRunning = false))
    }

    /**
     * The panel's conversation waits for its tool results ([on]), or no longer does. A wait that already lapsed
     * ([expire]) stays lapsed until the conversation stops waiting.
     */
    fun holdForTools(on: Boolean) = step {
        if (!on) {
            holdWanted = false
            holdExpired = false
            apply(queue.hold(false))
        } else if (!holdWanted) {
            holdWanted = true
            holdExpired = false
            holdSince = clock()
            apply(queue.hold(true))
        }
    }

    /** When the hold for tool results lapses (null: none in force): GemmaEngine calls [expire] then. */
    fun holdEndsAt(): Long? = if (holdWanted && !holdExpired) holdSince + toolHoldMs else null

    /** The hold lapses once [toolHoldMs] have passed: the waiting requests may start. */
    fun expire() = step {
        if (holdWanted && !holdExpired && clock() - holdSince >= toolHoldMs) {
            holdExpired = true
            apply(queue.hold(false))
        }
    }

    /** The model was unloaded: every waiting request ends ([Work.dropped] unloaded). The running one ends through [ended]. */
    fun dropWaiting() = step {
        for (j in queue.dropWaiting()) {
            val w = works[j.id]
            forget(j.id)
            w?.dropped(true)
        }
    }

    private fun apply(s: BrainQueue.Step) {
        if (s.cancelRunning) queue.running?.let { r -> works[r.id]?.stop(giveWay = queue.givingWay(r.id)) }
        for (j in s.dropped) {
            val w = works[j.id]
            forget(j.id)
            w?.dropped(false)
        }
        s.start?.let { j ->
            val w = works[j.id] ?: return
            if (told.remove(j.id)) w.queued(0)
            w.start(j.id)
        }
    }

    private fun forget(id: Int) {
        works.remove(id)
        told.remove(id)
        keys.remove(id)?.let { k -> if (ids[k] == id) ids.remove(k) }
    }

    /** One change at a time: a change asked for while one is applied (work ending inside its start) runs right after it. */
    private fun step(change: () -> Unit) {
        todo.addLast(change)
        if (draining) return
        draining = true
        try {
            while (todo.isNotEmpty()) {
                val c = todo.removeFirst()
                try { c() } catch (t: Throwable) { RLog.w("brain_schedule", t) }
            }
        } finally {
            draining = false
        }
    }

    companion object {
        /**
         * How long the panel's tool round keeps background work waiting: actions and cards usually take seconds; a card
         * left open longer lets the interpreter and transcript translations go on (the session is paused while the
         * panel is open anyway).
         */
        const val TOOL_HOLD_MS = 90_000L
    }
}

/** What a one-shot request gets (docs/conversation-mode.md 4.2), and what its failures mean. */
object OneShotRules {
    /** The chat template's markers around the system prompt and the turn. */
    const val TEMPLATE_TOKENS = 40

    /** The conversation a one-shot gets: [system] its own, no [history], no [tools], [constrained] decoding off. */
    data class Conv(val system: String, val history: List<PromptTurn>, val mode: ToolMode, val tools: List<ToolName>, val constrained: Boolean)

    /**
     * Its own system prompt and nothing else: no turn of anyone's conversation, no tool (whatever the engine's tool level
     * or the model), constrained decoding off, and [ToolMode.CHAT_ONLY], so a call the model writes anyway is dropped.
     */
    fun conversation(req: BrainMsg.OneShot): Conv = Conv(req.system, emptyList(), ToolMode.CHAT_ONLY, emptyList(), constrained = false)

    /** The reply's cap in a context of [maxTokens]: what [req] asked, never past the reply's room (a quarter of a short context). */
    fun maxChunks(req: BrainMsg.OneShot, maxTokens: Int): Int = minOf(req.maxChunks, Limits.MAX_REPLY_CHUNKS, ContextWindow.replyReserve(maxTokens)).coerceAtLeast(1)

    /** The estimate of what [req] sends: its system prompt, the template and its one turn. */
    fun tokens(req: BrainMsg.OneShot): Int =
        TokenEstimate.text(req.system) + TEMPLATE_TOKENS + TokenEstimate.turn(PromptTurn(PromptRole.USER, req.text))

    /** [req] and its reply's room fit a context of [maxTokens] (else TOO_LONG: the client cuts it, or uses the fast engine). */
    fun fits(req: BrainMsg.OneShot, maxTokens: Int): Boolean = tokens(req) + ContextWindow.replyReserve(maxTokens) <= maxTokens

    /**
     * A one-shot's send failed with [msg]: with output already streamed, a full context keeps it (LENGTH) and anything
     * else is an engine error; without, a full context is TOO_LONG. Never a step down, never sent again.
     */
    fun afterError(msg: String?, hadOutput: Boolean): BrainPolicy.Next {
        val full = EngineErrors.isContextFull(msg)
        return when {
            hadOutput && full -> BrainPolicy.Next.Stop(DoneReason.LENGTH)
            full -> BrainPolicy.Next.Fail(BrainError.TOO_LONG)
            else -> BrainPolicy.Next.Fail(BrainError.ENGINE)
        }
    }
}
