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

object UpdateJobs {
    const val CHECK = 4_801
    const val DOWNLOAD = 4_802
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

    fun cancelDownload(ctx: Context) {
        try { scheduler(ctx)?.cancel(DOWNLOAD) } catch (_: Throwable) {}
    }
}

/**
 * The jobs' service (MAIN PROCESS). Not exported; only the system binds it (BIND_JOB_SERVICE). The work runs on its own
 * thread; when Android stops a job, Stop disconnects it and a download keeps what it has for the next run.
 */
class UpdateJobService : JobService() {
    @Volatile private var abort: Abort? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val a = Abort()
        abort = a
        val app = applicationContext
        Thread({
            var again = false
            try {
                again = when (params.jobId) {
                    UpdateJobs.CHECK -> { Updater.autoCheck(app, a, params.network); false }
                    UpdateJobs.DOWNLOAD -> Updater.autoDownload(app, a, params.network)
                    else -> false
                }
            } catch (t: Throwable) {
                RLog.w("update_job_run", t)
            } finally {
                try { jobFinished(params, again && !a.cancelled) } catch (_: Throwable) {}
            }
        }, "robot-update-job").start()
        return true
    }

    /** Android stops the job (the network went, or its time is up): a download continues at the next run. */
    override fun onStopJob(params: JobParameters): Boolean {
        abort?.cancel()
        return params.jobId == UpdateJobs.DOWNLOAD
    }
}
