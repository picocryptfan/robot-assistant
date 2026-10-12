package app.heyrobot.assistant

import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.termux.TermuxExitBucket
import app.heyrobot.core.termux.TermuxFailure
import app.heyrobot.core.termux.TermuxLog
import app.heyrobot.core.termux.TermuxResult
import app.heyrobot.core.termux.TermuxRun
import app.heyrobot.core.termux.TermuxRunKind
import app.heyrobot.core.termux.TermuxRunPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

// VOICE PROCESS. One Termux run from sending to its result (docs/plans/websearch-termux-design.md B8.2, B8.3): a random
// 31-bit id unique while it is pending, the plan sent through [TermuxTransport] (the RUN_COMMAND intent, TermuxSend), the
// result awaited until the plan's deadline (its limit, timeout's 5 s and 10 s more). Stop (the turn cancelled: the Stop
// button, "stop", the panel closed) and the deadline send the fixed Stop run, which ends the run's whole process group;
// the run's own result then arrives for an id nobody waits for and is dropped as TERMUX_LATE, as is any result after
// :voice restarted. The logs get codes, buckets, sizes and milliseconds only: never the command, a path, an argument, a
// script's name or its output (TermuxLogTest plants a marker in all of them).

/** Sends a run to Termux; null when it went, else why it couldn't. */
fun interface TermuxTransport {
    fun send(plan: TermuxRunPlan): TermuxFailure?
}

/** How a run came back: Termux's result, or why there is none. */
sealed class TermuxReply {
    abstract val ms: Long

    data class Answered(val result: TermuxResult, override val ms: Long) : TermuxReply()
    data class Failed(val failure: TermuxFailure, override val ms: Long) : TermuxReply()
}

/** [deadline]: how long a run is waited for (the plan's own; tests shorten it). */
class TermuxRunner(private val transport: TermuxTransport, private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: Random = SecureRandom(), private val deadline: (TermuxRunPlan) -> Long = { it.deadlineMs }) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()
    /** Runs whose result nobody waits for and that aren't late: Stop runs and Open in Termux. */
    private val quiet: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** A positive 31-bit id no pending run has (the PendingIntent's request code: one per run, or Termux answers only the first). */
    fun newId(): Int {
        while (true) {
            val id = random.nextInt(Int.MAX_VALUE - 1) + 1
            if (!pending.containsKey(id) && id !in quiet) return id
        }
    }

    /** Runs waiting for their result. */
    val waiting: Int get() = pending.size

    /**
     * Sends [plan] and waits for its result. Cancelled, or past its deadline: the Stop run ends its process tree (a Stop
     * or a set-up test needs none), and its result, when it comes, is late.
     */
    suspend fun run(plan: TermuxRunPlan): TermuxReply {
        val d = CompletableDeferred<TermuxResult>()
        pending[plan.execId] = d
        val start = now()
        VoiceLog.add(VoiceEvent.TERMUX_SENT, plan.kind, plan.limitSeconds)
        try {
            transport.send(plan)?.let { return failed(plan, it, 0) }
            val r = withTimeoutOrNull(deadline(plan)) { d.await() }
            val ms = now() - start
            if (r == null) {
                if (needsStop(plan)) stop(plan.execId)
                return failed(plan, TermuxFailure.NO_ANSWER, ms)
            }
            TermuxFailure.of(r.err, r.errmsg)?.let { return failed(plan, it, ms) }
            val bytes = (r.stdout?.length ?: 0).toLong() + (r.stderr?.length ?: 0)
            val bucket = TermuxExitBucket.of(r.exitCode ?: -1)
            VoiceLog.add(VoiceEvent.TERMUX_DONE, bucket, ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), TermuxLog.doneC(bytes, cut(r)))
            RLog.i("termux_run", "kind" to plan.kind, "exit" to bucket, "ms" to ms, "kb" to TermuxLog.kbBucket(bytes))
            return TermuxReply.Answered(r, ms)
        } catch (e: CancellationException) {
            // Stop: the run's whole process group goes (the Stop run is sent at once, its own result dropped quietly).
            if (needsStop(plan)) stop(plan.execId)
            RLog.i("termux_stopped", "kind" to plan.kind)
            throw e
        } finally {
            pending.remove(plan.execId)
        }
    }

    private fun needsStop(plan: TermuxRunPlan) = plan.kind != TermuxRunKind.STOP && plan.kind != TermuxRunKind.TEST

    private fun failed(plan: TermuxRunPlan, f: TermuxFailure, ms: Long): TermuxReply.Failed {
        VoiceLog.add(VoiceEvent.TERMUX_FAILED, f)
        RLog.i("termux_failed", "kind" to plan.kind, "why" to f, "ms" to ms)
        return TermuxReply.Failed(f, ms)
    }

    private fun cut(r: TermuxResult): Boolean {
        fun cut(original: String?, kept: String?) = (original?.trim()?.toIntOrNull() ?: 0) > (kept?.length ?: 0)
        return cut(r.stdoutOriginalLength, r.stdout) || cut(r.stderrOriginalLength, r.stderr)
    }

    /** Sends the fixed Stop run for [target] (card-free: it can only end Robot's own run). */
    fun stop(target: Int) {
        val id = newId()
        quiet += id
        VoiceLog.add(VoiceEvent.TERMUX_SENT, TermuxRunKind.STOP, TermuxRun.STOP_LIMIT_S)
        transport.send(TermuxRun.stop(id, target))?.let {
            quiet -= id
            VoiceLog.add(VoiceEvent.TERMUX_FAILED, it)
        }
    }

    /** Sends [plan] without waiting for it (Open in Termux: only its exit code would come back). */
    fun fire(plan: TermuxRunPlan): TermuxFailure? {
        quiet += plan.execId
        VoiceLog.add(VoiceEvent.TERMUX_SENT, plan.kind, plan.limitSeconds)
        return transport.send(plan)?.also {
            quiet -= plan.execId
            VoiceLog.add(VoiceEvent.TERMUX_FAILED, it)
        }
    }

    /**
     * A result from the receiver. For a run waiting: its result. For a Stop run or an opened terminal: dropped. For
     * anything else (Stop already ended the wait, the deadline passed, :voice restarted, an id Robot never sent): logged
     * TERMUX_LATE, its output dropped. False when nobody was waiting for it.
     */
    fun deliver(execId: Int, r: TermuxResult): Boolean {
        pending[execId]?.let { return it.complete(r) }
        val bucket = TermuxExitBucket.of(r.exitCode ?: -1)
        if (quiet.remove(execId)) {
            RLog.i("termux_quiet", "exit" to bucket)
            return false
        }
        VoiceLog.add(VoiceEvent.TERMUX_LATE, bucket)
        RLog.i("termux_late", "exit" to bucket)
        return false
    }
}
