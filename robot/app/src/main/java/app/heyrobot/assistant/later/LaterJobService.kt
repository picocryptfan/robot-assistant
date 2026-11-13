// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.net.Network
import app.heyrobot.core.RLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Signal later's job (VOICE PROCESS, android:process=":voice"), where web search, its keys and its gate already are, so
 * the kept search, the queue's read and the answer never reach the main process. Not exported; only the system binds it
 * (BIND_JOB_SERVICE). The work runs on its own thread; when Android stops it, what is left on the queue waits for the next run.
 */
class LaterJobService : JobService() {
    /** This start's stop: each start has its own, so a new start never un-stops an old thread still running. */
    @Volatile private var stop: AtomicBoolean? = null

    override fun onStartJob(params: JobParameters): Boolean = start(params.network) { again -> jobFinished(params, again) }

    /** One start: its own stop flag, its run on its own thread, then [finish] (true: run again later, never once stopped). */
    internal fun start(network: Network?, finish: (Boolean) -> Unit): Boolean {
        val stopped = AtomicBoolean(false).also { stop = it }
        val app = applicationContext
        Thread({
            var again = false
            try {
                again = runner(app, network) { stopped.get() }
            } catch (t: Throwable) {
                RLog.w("later_job_run", t)
            } finally {
                try { finish(again && !stopped.get()) } catch (_: Throwable) {}
            }
        }, "robot-later-job").start() // l10n: not text (a thread name)
        return true
    }

    /** Android stops the job (the network went): the loop stops before the next question; what still waits runs on the next one. */
    override fun onStopJob(params: JobParameters): Boolean {
        stopNow()
        return true
    }

    /** Stops the latest start only. */
    internal fun stopNow() {
        stop?.set(true)
    }

    companion object {
        /** The run ([SignalLater.run]); a seam for the tests. */
        @Volatile internal var runner: (Context, Network?, () -> Boolean) -> Boolean = { ctx, network, stopped -> SignalLater.run(ctx, network, stopped) }
    }
}
