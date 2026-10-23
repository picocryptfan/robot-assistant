// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.AppCrash
import app.heyrobot.core.AppExit
import app.heyrobot.core.CheckLog
import app.heyrobot.core.CheckRecord
import app.heyrobot.core.ExitFacts
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.GpuTrying
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.NativeCrash
import app.heyrobot.core.ProblemList
import app.heyrobot.core.ProblemReport
import app.heyrobot.core.RLog
import app.heyrobot.core.ReportFacts
import app.heyrobot.core.Tombstone
import app.heyrobot.core.VoiceLogCodec
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// Main and voice processes (0.4.7). The GPU failure Robot remembers and the GPU load under way (written by the voice
// process, which loads the model; the main app deletes the failure when you try the GPU again), the last model problem
// (written by the voice process: codes and numbers only, core ModelProblem) with its notification, and the problem
// report the main app builds from it when you open it: plain text you read, then copy or share yourself. Nothing is
// ever sent by Robot. 0.7: the last 10 problems too (core ProblemList, the Model tab's list; one you delete is hidden by
// the main app, which never writes the voice process's file), and a native crash's tombstone read into its report as
// codes, library names and offsets (core Tombstone).

object Problems {
    private const val CHANNEL = "model_problems"
    private const val NOTICE_ID = 47
    private const val PREFS = "robot_problems"
    private const val DISMISSED = "dismissed"
    private const val DELETED = "deleted"
    /** 1.0: the time of the last crash of the app or the assistant you were told about (AppCrash). */
    private const val APP_SEEN = "app_exit_seen"

    private fun gpuFile(ctx: Context) = File(Paths.dir(ctx), GpuFailure.FILE)
    private fun tryingFile(ctx: Context) = File(Paths.dir(ctx), GpuTrying.FILE)
    private fun noteFile(ctx: Context) = File(Paths.dir(ctx), ModelProblem.FILE)
    private fun listFile(ctx: Context) = File(Paths.dir(ctx), ProblemList.FILE)

    /**
     * The GPU failure Robot remembers, or null. Any process. 1.2: only while it holds on this Android build (core
     * GpuFailure.inForce): after a system update every process reads none, and the GPU is checked again.
     */
    fun gpuFailure(ctx: Context): GpuFailure? = gpuFailureFiled(ctx)?.takeIf { it.inForce(Build.FINGERPRINT) }

    /** The record as it is in the file, whatever its build. */
    private fun gpuFailureFiled(ctx: Context): GpuFailure? = GpuFailure.decode(readOrNull(gpuFile(ctx)))

    /** [VOICE] The GPU failed: the CPU from now on, until you try the GPU again (1.2: or Android's build changes; written with this build). */
    fun gpuFailed(ctx: Context, f: GpuFailure) = try { writeAtomic(gpuFile(ctx), f.stamped(Build.FINGERPRINT).encode()) } catch (t: Throwable) { RLog.w("gpu_failed_save", t) }

    /** [MAIN] "Try the GPU again" in Settings › Model: 1.2, the check's record too, so the next load checks again. */
    fun forgetGpuFailure(ctx: Context) {
        gpuFile(ctx).delete()
        tryingFile(ctx).delete()
        checkFile(ctx).delete()
    }

    private fun tpuFile(ctx: Context) = File(Paths.dir(ctx), app.heyrobot.core.TpuFailure.FILE)

    /** 0.8: the beta TPU build's failure Robot remembers (Fast is then the general build), or null. Any process. 1.2: as [gpuFailure]. */
    fun tpuFailure(ctx: Context): app.heyrobot.core.TpuFailure? = tpuFailureFiled(ctx)?.takeIf { it.inForce(Build.FINGERPRINT) }

    private fun tpuFailureFiled(ctx: Context): app.heyrobot.core.TpuFailure? = app.heyrobot.core.TpuFailure.decode(readOrNull(tpuFile(ctx)))

    /** [VOICE] The beta TPU build didn't load: the general build from now on, until you try the TPU again (1.2: or Android's build changes). */
    fun tpuFailed(ctx: Context, f: app.heyrobot.core.TpuFailure) = try { writeAtomic(tpuFile(ctx), f.stamped(Build.FINGERPRINT).encode()) } catch (t: Throwable) { RLog.w("tpu_failed_save", t) }

    /** [MAIN] "Try the TPU again" in Settings › Model: 1.2, the check's record too. */
    fun forgetTpuFailure(ctx: Context) {
        tpuFile(ctx).delete()
        checkFile(ctx).delete()
    }

    /**
     * [VOICE] 1.2: the failure records against this Android build. One from 1.1 or 0.8 (no build) gets this build written
     * into it, so it holds until the next update; one from another build is deleted, once: the GPU (or the beta TPU build)
     * gets another chance, checked first (core GpuCheck). Returns what changed, for the log: 0 nothing, 1 stamped, 2 dropped.
     */
    fun fingerprintFailures(ctx: Context): Int {
        val fp = Build.FINGERPRINT
        var changed = 0
        gpuFailureFiled(ctx)?.let { f ->
            when {
                f.build == null -> { gpuFailed(ctx, f.stamped(fp)); changed = maxOf(changed, 1) }
                !f.inForce(fp) -> { gpuFile(ctx).delete(); changed = 2 }
            }
        }
        tpuFailureFiled(ctx)?.let { f ->
            when {
                f.build == null -> { tpuFailed(ctx, f.stamped(fp)); changed = maxOf(changed, 1) }
                !f.inForce(fp) -> { tpuFile(ctx).delete(); changed = 2 }
            }
        }
        return changed
    }

    private fun checkFile(ctx: Context) = File(Paths.dir(ctx), CheckLog.FILE)

    /** 1.2: the self-tests run on this phone (core CheckLog), oldest first. Any process. */
    fun checks(ctx: Context): List<CheckRecord> = CheckLog.decode(readOrNull(checkFile(ctx)))

    /** [VOICE] 1.2: a self-test ended with [r]. */
    fun checked(ctx: Context, r: CheckRecord) {
        try {
            synchronized(this) { writeAtomic(checkFile(ctx), CheckLog.add(readOrNull(checkFile(ctx)), r)) }
        } catch (t: Throwable) { RLog.w("check_save", t) }
    }

    /** [VOICE] A GPU load is about to start, or (null) has answered. */
    fun gpuTrying(ctx: Context, t: GpuTrying?) {
        try { if (t == null) tryingFile(ctx).delete() else writeAtomic(tryingFile(ctx), t.encode()) } catch (e: Throwable) { RLog.w("gpu_trying_save", e) }
    }

    /** A GPU load another voice process started and never saw answered (the phone or Robot restarted during it), or null. */
    fun staleGpuTry(ctx: Context): GpuTrying? = GpuTrying.decode(readOrNull(tryingFile(ctx)))?.takeIf { it.voicePid != android.os.Process.myPid() }

    /**
     * [VOICE] Keeps [p] as the last problem, adds it to the last 10 (the Model tab's list), and tells you once, with a
     * notification that opens Settings › Model.
     */
    fun note(ctx: Context, p: ModelProblem) {
        try { writeAtomic(noteFile(ctx), p.encode()) } catch (t: Throwable) { RLog.w("problem_save", t) }
        try {
            synchronized(this) { writeAtomic(listFile(ctx), ProblemList.add(readOrNull(listFile(ctx)), p)) }
        } catch (t: Throwable) { RLog.w("problem_list_save", t) }
        notify(ctx, p)
    }

    /**
     * [MAIN] The last 10 problems, newest first, without the ones you deleted. A 0.4.7 or 0.6.0 phone's last problem, from
     * before the list, is in it too.
     */
    fun list(ctx: Context): List<ModelProblem> {
        val deleted = prefs(ctx).getStringSet(DELETED, emptySet()).orEmpty()
        val all = ProblemList.decode(readOrNull(listFile(ctx))) + listOfNotNull(last(ctx))
        return all.distinctBy { it.time }.sortedByDescending { it.time }.take(ProblemList.KEEP).filter { it.time.toString() !in deleted }
    }

    /** [MAIN] "Delete" in the Model tab's list: hidden for good (and dismissed, when it is the open one). */
    fun delete(ctx: Context, p: ModelProblem) {
        val p0 = prefs(ctx)
        val kept = list(ctx).map { it.time.toString() }.toSet()
        // Only the times still listed are remembered, so the set never grows past the list.
        val deleted = (p0.getStringSet(DELETED, emptySet()).orEmpty().filter { it in kept || it == p.time.toString() } + p.time.toString()).toSet()
        p0.edit().putStringSet(DELETED, deleted).apply()
        if (open(ctx)?.time == p.time) dismiss(ctx, p)
    }

    /** The last problem, or null. Any process. */
    fun last(ctx: Context): ModelProblem? = ModelProblem.decode(readOrNull(noteFile(ctx)))

    /** [MAIN] The last problem unless you dismissed it in Settings › Model. */
    fun open(ctx: Context): ModelProblem? = last(ctx)?.takeIf { it.time != prefs(ctx).getLong(DISMISSED, 0) }

    fun dismiss(ctx: Context, p: ModelProblem) {
        prefs(ctx).edit().putLong(DISMISSED, p.time).apply()
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID) } catch (_: Exception) {}
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The memory now, for a problem note: free, total, low. */
    fun memory(ctx: Context): Triple<Long, Long, Boolean> = try {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        Triple(mi.availMem, mi.totalMem, mi.lowMemory)
    } catch (_: Exception) { Triple(0L, 0L, false) }

    private fun notify(ctx: Context, p: ModelProblem) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // 1.1: in the menus' language (Android gives every process of Robot its per-app language), named again at each notice.
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.model_problem_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ctx.getString(R.string.model_problem_channel_text)
            })
            val target = Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity")
                .putExtra(AppPages.EXTRA, AppPages.MODEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open = PendingIntent.getActivity(ctx, NOTICE_ID, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = listOf(p.headlineText().render(AppLanguage.langs(ctx)), ctx.getString(R.string.model_problem_report)).joinToString(" ")
            nm.notify(NOTICE_ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(ctx.getString(R.string.model_problem_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ERROR)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (t: Throwable) {
            RLog.w("problem_notify", t)
        }
    }

    // ------------------------------------------------------------------ the report (main process)

    /** [MAIN] The report for [p], as you'll see it before you copy or share it (blocking: files and ActivityManager). */
    fun report(ctx: Context, p: ModelProblem): String {
        val facts = ReportFacts(
            robotVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            phone = ModelStore.phone(ctx),
            android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", // l10n: not text (the problem report, a diagnostic: English)
            fingerprint = Build.FINGERPRINT.orEmpty(),
            fileBytes = ModelStore.file(ctx, p.model).let { if (it.isFile) it.length() else -1 },
            exits = exits(ctx, p),
            voiceLog = VoiceLogCodec.decode(readOrNull(Paths.voiceLog(ctx))).asReversed(),
        )
        return ProblemReport.text(p, facts, System.currentTimeMillis())
    }

    /**
     * Android's records of how the problem's process ended (ApplicationExitInfo: :brain's, or 0.6.0 :llama's for a GGUF
     * file, which Android keeps under Robot's package too), the one with the problem's process id first, then the others
     * within 10 minutes of it: its reason, status, description, importance and memory. 0.7: a native crash's trace (its
     * tombstone) is read by core Tombstone alone, which keeps the signal, the abort message when safe and the crashing
     * thread's frames as library names and offsets; nothing else of it is kept or shown.
     */
    private fun exits(ctx: Context, p: ModelProblem): List<ExitFacts> = try {
        val suffix = p.process.suffix
        val all = ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(ctx.packageName, 0, 16)
            .filter { e -> e.processName?.let { it.endsWith(suffix) || it.contains("$suffix:") } == true }
        val near = all.filter { (p.brainPid > 0 && it.pid == p.brainPid) || kotlin.math.abs(it.timestamp - p.time) < 10 * 60_000L }
        near.sortedWith(compareBy<ApplicationExitInfo>({ it.pid != p.brainPid }, { kotlin.math.abs(it.timestamp - p.time) })).take(3).map {
            ExitFacts(it.timestamp, it.pid, it.reason, reasonName(it.reason), it.status, it.description, it.importance, it.pss, it.rss, nativeCrash(it))
        }
    } catch (t: Throwable) {
        RLog.w("problem_exits", t)
        emptyList()
    }

    /** A native crash's tombstone (Android 12 and later keep it for REASON_CRASH_NATIVE), as core Tombstone reads it; else null. */
    private fun nativeCrash(e: ApplicationExitInfo): NativeCrash? {
        if (e.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return null
        return try { e.traceInputStream?.use(Tombstone::read) } catch (t: Throwable) { RLog.w("problem_tombstone", t); null }
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN"
    }

    // ------------------------------------------------------------------ 1.0: "Robot closed unexpectedly" (main process)

    /**
     * [MAIN] The app's or the assistant's crash or "not responding" since the last one you were told about, or null
     * (blocking: ActivityManager). The very first look only sets the mark (core AppCrash.firstSeen).
     */
    fun newCrash(ctx: Context): AppExit? {
        val exits = appExits(ctx, traces = false)
        val p = prefs(ctx)
        if (!p.contains(APP_SEEN)) {
            p.edit().putLong(APP_SEEN, AppCrash.firstSeen(exits, System.currentTimeMillis())).apply()
            return null
        }
        return AppCrash.pick(exits, p.getLong(APP_SEEN, 0)).also { if (it != null) RLog.i("app_crash_seen", "proc" to RLog.Code(it.process), "reason" to it.facts.reason) }
    }

    /** [MAIN] You were told about [e] (Send feedback or Not now): it isn't told again. */
    fun crashTold(ctx: Context, e: AppExit) {
        val p = prefs(ctx)
        p.edit().putLong(APP_SEEN, maxOf(p.getLong(APP_SEEN, 0), e.facts.time)).apply()
    }

    /**
     * [MAIN] The crash report of the app's or the assistant's crash at [time] (Send feedback's), or null when Android no
     * longer keeps it (blocking: ActivityManager and the tombstone). Other crashes within 10 minutes of it are listed too.
     */
    fun crashReport(ctx: Context, time: Long): String? {
        val all = appExits(ctx, traces = true).filter { it.process in AppCrash.PROCESSES && AppCrash.unexpected(it.facts.reason) }
        val e = all.firstOrNull { it.facts.time == time } ?: return null
        val facts = ReportFacts(
            robotVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            phone = ModelStore.phone(ctx),
            android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", // l10n: not text (the problem report, a diagnostic: English)
            fingerprint = Build.FINGERPRINT.orEmpty(),
            fileBytes = -1,
            exits = all.filter { it !== e && kotlin.math.abs(it.facts.time - time) < 10 * 60_000L }.take(2).map { it.facts },
            voiceLog = VoiceLogCodec.decode(readOrNull(Paths.voiceLog(ctx))).asReversed(),
        )
        return AppCrash.report(e, facts, System.currentTimeMillis())
    }

    /** Android's records of Robot's processes that ended, newest first; a native crash's tombstone read only with [traces]. */
    private fun appExits(ctx: Context, traces: Boolean): List<AppExit> = try {
        ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(ctx.packageName, 0, 16).mapNotNull { x ->
            AppCrash.processOf(x.processName, ctx.packageName)?.let { proc ->
                AppExit(proc, ExitFacts(x.timestamp, x.pid, x.reason, reasonName(x.reason), x.status, x.description, x.importance, x.pss, x.rss,
                    if (traces && proc in AppCrash.PROCESSES) nativeCrash(x) else null))
            }
        }
    } catch (t: Throwable) {
        RLog.w("app_exits", t)
        emptyList()
    }

    /** [MAIN] Puts the report on the clipboard, marked sensitive (Android then hides its preview). */
    fun copy(ctx: Context, text: String) {
        val clip = ClipData.newPlainText("Robot problem report", text) // l10n: not text (a clip label)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /** [MAIN] Android's share sheet with the report as plain text: you pick where it goes, or nowhere. */
    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.model_report_subject))
            .putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.model_share_report_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
