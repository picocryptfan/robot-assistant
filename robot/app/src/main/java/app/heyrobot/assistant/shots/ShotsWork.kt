// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.shots

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Size
import app.heyrobot.assistant.BrainOneShots
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.RLog
import app.heyrobot.core.shots.ShotBatch
import app.heyrobot.core.shots.ShotEntry
import app.heyrobot.core.shots.ShotPass
import app.heyrobot.core.shots.ShotRead
import app.heyrobot.core.shots.ShotRow
import app.heyrobot.sys.PhoneInfo
import app.heyrobot.sys.ShotsJob
import app.heyrobot.sys.ShotsVault
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

// VOICE PROCESS (beside the model's client). Ask about your screenshots' charging pass (screenshots-plan.md, "What's new"
// 1 and 3): a daily job that needs charging and idle; it reads new screenshots in the screenshots folders only (core
// ShotPass), each by Gemma's vision encoder, hides their secrets (core ShotRead), seals them as one batch (works locked)
// and drops entries whose screenshot is gone. It stops when charging stops, the phone warms, or Android stops the job.
// No network. Logs counts only.
object ShotsWork {
    const val JOB_ID = ShotsJob.JOB_ID
    private const val READ_MS = 120_000L

    fun schedule(ctx: Context): Boolean = ShotsJob.schedule(ctx)

    fun cancel(ctx: Context) = ShotsJob.cancel(ctx)

    /** The screenshots Android lets Robot see (trashed and pending ones aren't listed: MediaStore's default). */
    fun rows(ctx: Context, ids: Collection<Long>? = null): List<ShotRow> {
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED)
        val where = StringBuilder("(" + ShotPass.FOLDERS.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" } + ")") // l10n: not text (SQL)
        val args = ShotPass.FOLDERS.map { "$it%" }.toMutableList()
        if (ids != null) {
            if (ids.isEmpty()) return emptyList()
            where.append(" AND ${MediaStore.Images.Media._ID} IN (${ids.joinToString(",") { "?" }})") // l10n: not text (SQL)
            args += ids.map { it.toString() }
        }
        val out = ArrayList<ShotRow>()
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, where.toString(), args.toTypedArray(), null)?.use { c ->
            while (c.moveToNext()) out += ShotRow(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3) * 1000)
        }
        return out
    }

    private fun charging(ctx: Context): Boolean = ctx.getSystemService(BatteryManager::class.java)?.isCharging == true
    private fun warm(ctx: Context): Boolean = (ctx.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE

    private fun jpeg(ctx: Context, id: Long): ByteArray? = try {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bmp = ctx.contentResolver.loadThumbnail(uri, Size(1024, 1024), null)
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it); bmp.recycle() }.toByteArray()
    } catch (t: Throwable) {
        null
    }

    /** One pass. True: run again later (stopped before the end). */
    fun run(ctx: Context, stopped: () -> Boolean): Boolean {
        val store = ShotsVault.store(ctx)
        val state = store.state()
        if (!state.on) return false
        // Photo access taken away: nothing is read and every entry is dropped.
        val rows = if (PhoneInfo.mediaGranted(ctx)) rows(ctx) else emptyList()
        val plan = ShotPass.plan(state, rows, if (PhoneInfo.mediaPartial(ctx)) rows.map { it.id }.toSet() else null)
        val entries = ArrayList<ShotEntry>()
        val readIds = ArrayList<Long>()
        var done = 0
        for (r in plan.read) {
            if (stopped() || !charging(ctx) || warm(ctx)) break
            val pic = jpeg(ctx, r.id)
            if (pic != null) {
                val result = kotlinx.coroutines.runBlocking { BrainOneShots.runWithImage(ShotRead.request(), pic, OneShotBudget(startWithinMs = null, totalMs = READ_MS)) }
                if (result !is OneShotResult.Done) break
                readIds += r.id
                ShotRead.entry(r.id, r.added, result.text)?.let { entries += it }
            }
            done++
        }
        store.append(ShotBatch(entries, plan.drop), ShotPass.after(state, plan, done, entries, rows, System.currentTimeMillis(), readIds))
        RLog.i("shots_pass", "read" to entries.size, "dropped" to plan.drop.size, "left" to (plan.read.size - done))
        return done < plan.read.size
    }
}

/** The charging pass's job (VOICE PROCESS). Not exported; only the system binds it (BIND_JOB_SERVICE). */
class ShotsJobService : JobService() {
    @Volatile private var stop: AtomicBoolean? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val stopped = AtomicBoolean(false).also { stop = it }
        val app = applicationContext
        Thread({
            var again = false
            try {
                again = ShotsWork.run(app) { stopped.get() }
            } catch (t: Throwable) {
                RLog.w("shots_run", t)
            } finally {
                try { jobFinished(params, again && !stopped.get()) } catch (_: Throwable) {}
            }
        }, "robot-shots-job").start() // l10n: not text (a thread name)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stop?.set(true)
        return true
    }
}
