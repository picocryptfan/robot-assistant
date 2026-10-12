package app.heyrobot.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.heyrobot.core.Abort
import app.heyrobot.core.RLog
import app.heyrobot.core.update.UpdateSettings

// MAIN PROCESS. The updater's two jobs (JobScheduler, no WorkManager): the daily check, on any network (one small request),
// while "Check for updates automatically" is on; and, with "Download updates on Wi-Fi", the download, which Android runs only
// on an unmetered network. Robot can't tell a metered network itself (it has no network-state permission); the job's
// constraint does it. Both survive a restart (RECEIVE_BOOT_COMPLETED) and are scheduled again when the app opens or updates.
// 1.0: and Settings › Termux's weekly "Check for Termux updates" (TermuxUpdateCheck), in the same service.

object UpdateJobs {
    const val CHECK = 4_801
    const val DOWNLOAD = 4_802
    /** 1.0: Termux's weekly update check. */
    const val TERMUX = 4_803
    private const val DAY_MS = 24L * 3600 * 1000
    private const val FLEX_MS = 6L * 3600 * 1000

    private fun scheduler(ctx: Context): JobScheduler? = ctx.getSystemService(JobScheduler::class.java)

    /** The daily check scheduled while [s] has it on, cancelled (with any download waiting for Wi-Fi) while it is off. */
    fun ensure(ctx: Context, s: UpdateSettings) {
        val js = scheduler(ctx) ?: return
        try {
            if (!s.autoCheck) {
                js.cancel(CHECK)
                js.cancel(DOWNLOAD)
                return
            }
            if (!s.wifiDownload) js.cancel(DOWNLOAD)
            if (js.getPendingJob(CHECK) != null) return
            js.schedule(JobInfo.Builder(CHECK, ComponentName(ctx, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DAY_MS, FLEX_MS)
                .setPersisted(true)
                .setEstimatedNetworkBytes(256L * 1024, 1024)
                .build())
            RLog.i("update_job", "op" to RLog.Code("scheduled"))
        } catch (t: Throwable) {
            RLog.w("update_job_schedule", t)
        }
    }

    /** The download, when Android sees an unmetered network (at once if the phone is on Wi-Fi now). */
    fun downloadOnWifi(ctx: Context, bytes: Long) {
        val js = scheduler(ctx) ?: return
        try {
            if (js.getPendingJob(DOWNLOAD) != null) return
            js.schedule(JobInfo.Builder(DOWNLOAD, ComponentName(ctx, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresStorageNotLow(true)
                .setPersisted(true)
                .setEstimatedNetworkBytes(bytes, 1024)
                .build())
        } catch (t: Throwable) {
            RLog.w("update_job_download", t)
        }
    }

    /** 1.0: Termux's weekly update check, scheduled while [on] (the switch, and a trusted Termux), cancelled otherwise. */
    fun ensureTermux(ctx: Context, on: Boolean) {
        val js = scheduler(ctx) ?: return
        try {
            if (!on) {
                js.cancel(TERMUX)
                return
            }
            if (js.getPendingJob(TERMUX) != null) return
            js.schedule(JobInfo.Builder(TERMUX, ComponentName(ctx, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(app.heyrobot.core.termux.TermuxUpdates.WEEK_MS, DAY_MS)
                .setPersisted(true)
                .setEstimatedNetworkBytes(64L * 1024, 1024)
                .build())
            RLog.i("termux_updates_job", "op" to RLog.Code("scheduled"))
        } catch (t: Throwable) {
            RLog.w("termux_updates_job", t)
        }
    }

    fun cancelDownload(ctx: Context) {
        try { scheduler(ctx)?.cancel(DOWNLOAD) } catch (_: Throwable) {}
    }
}

/**
 * Each running job's stop signal, by its id. The daily check and the download on Wi-Fi can run at once in the one service:
 * Android stopping one stops that one's work only, and a run that ends forgets its own signal, never a newer run's.
 */
internal class JobAborts {
    private val running = java.util.concurrent.ConcurrentHashMap<Int, Abort>()

    /** A new run of job [id]; a run of it still going is told to stop. */
    fun start(id: Int): Abort = Abort().also { running.put(id, it)?.cancel() }

    fun stop(id: Int) {
        running[id]?.cancel()
    }

    fun done(id: Int, a: Abort) {
        running.remove(id, a)
    }
}

/**
 * The jobs' service (MAIN PROCESS). Not exported; only the system binds it (BIND_JOB_SERVICE). The work runs on its own
 * thread; when Android stops a job, Stop disconnects that job's work and a download keeps what it has for the next run.
 */
class UpdateJobService : JobService() {
    private val aborts = JobAborts()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.jobId
        val a = aborts.start(id)
        val app = applicationContext
        Thread({
            var again = false
            try {
                again = when (id) {
                    UpdateJobs.CHECK -> { Updater.autoCheck(app, a, params.network); false }
                    UpdateJobs.DOWNLOAD -> Updater.autoDownload(app, a, params.network)
                    UpdateJobs.TERMUX -> { TermuxUpdateCheck.autoCheck(app, a, params.network); false }
                    else -> false
                }
            } catch (t: Throwable) {
                RLog.w("update_job_run", t)
            } finally {
                aborts.done(id, a)
                try { jobFinished(params, again && !a.cancelled) } catch (_: Throwable) {}
            }
        }, "robot-update-job").start()
        return true
    }

    /** Android stops the job (the network went, or its time is up): a download continues at the next run. */
    override fun onStopJob(params: JobParameters): Boolean {
        aborts.stop(params.jobId)
        return params.jobId == UpdateJobs.DOWNLOAD
    }
}
