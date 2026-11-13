// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import app.heyrobot.core.RLog

// MAIN AND VOICE PROCESSES. Ask about your screenshots' daily pass (ShotsJobService, in :voice): scheduled and cancelled
// here by the service's class name, so Settings never loads the assistant's code (mainProcessCodeNeverReachesEngines).
// Charging and idle only, once a day, kept across restarts.
object ShotsJob {
    const val JOB_ID = 4_807
    private const val SERVICE = "app.heyrobot.assistant.shots.ShotsJobService" // l10n: not text (a class name)
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun schedule(ctx: Context): Boolean = try {
        val js = ctx.getSystemService(JobScheduler::class.java)
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx.packageName, SERVICE))
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setPeriodic(DAY_MS)
            .setPersisted(true)
            .build()) == JobScheduler.RESULT_SUCCESS
    } catch (t: Throwable) {
        RLog.w("shots_schedule", t)
        false
    }

    fun cancel(ctx: Context) = try { ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID) } catch (t: Throwable) { RLog.w("shots_cancel", t) }
}
