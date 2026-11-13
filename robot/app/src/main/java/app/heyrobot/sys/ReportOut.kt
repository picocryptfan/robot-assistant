// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.online.NetRecord
import android.net.Uri
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.HandedReport
import app.heyrobot.core.feedback.HandedReports
import app.heyrobot.core.feedback.ReportPart
import app.heyrobot.core.feedback.SentReport
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import app.heyrobot.BuildConfig
import app.heyrobot.core.feedback.ReportFile
import app.heyrobot.core.feedback.ReportShot
import app.heyrobot.core.link.LinkIpcProtocol
import java.io.File
import java.io.IOException

// MAIN PROCESS. 1.4, Report a problem (report-a-problem-plan.md "What's new" 5): Send. The report's bytes (ReportFile) go
// to :link through a pipe (LinkIpcProtocol.SEAL_REPORT; libsignal lives there only), which writes the sealed
// RFB-XXXXXXXX.robotreport to cache/reports/; the main app hands it on with ACTION_SEND through ExportProvider (a one-time
// read grant on that one file), the address and subject filled in for an email app. No server, no new permission. Each
// copy goes before its own 24 hours: one inexact alarm, allowed in Doze, for the oldest copy left (Android delivers it at
// most an hour late, API 31 on), set again by [ReportExpiry], at the main app's or :voice's start and after a reboot.

object ReportOut {
    const val KEEP_MS = 24L * 60 * 60 * 1000
    /** How much before its 24 hours a copy may go: Android may deliver an inexact alarm up to an hour late. */
    const val EARLY_MS = 75L * 60 * 1000
    private const val TMP_MS = 60_000L
    /** The sweep alarm's request code: no other PendingIntent in Robot uses it (NoticeIdsTest). */
    const val REQ_EXPIRY = 8_975
    const val TO = "hey@robot-app.ai" // l10n: not text (an address)
    /** The subject, English as the report's header is: the PC tool and your mail sort by it. */
    private const val SUBJECT = "Robot report " // l10n: not text (a subject line the developer's tools read)
    private const val MIME = "application/octet-stream" // l10n: not text (a MIME type)
    private const val AUTHORITY = BuildConfig.APPLICATION_ID + ".exports" // l10n: not text (an authority)
    private const val TMP = ".tmp" // l10n: not text (a file name's end)

    fun dir(ctx: Context): File = File(ctx.cacheDir, LinkIpcProtocol.REPORT_DIR)

    fun subject(ref: String): String = SUBJECT + ref

    /** The bytes to seal, or null: a bad reference, too big, or a screenshot whose sides the PC tool won't take. */
    fun plain(ref: String, text: String, png: ByteArray?): ByteArray? {
        if (png != null && ReportShot.read(png)?.let { ReportShot.fits(it.width, it.height) } != true) return null
        return try { ReportFile.write(ReportFile.Report(ref, text, png)) } catch (_: IllegalArgumentException) { null }
    }

    /** [plain] sealed to [key] by :link, as cache/reports/[ref].robotreport; null when :link refused or isn't there. Off the main thread. */
    fun seal(ctx: Context, key: ByteArray, ref: String, plain: ByteArray): File? {
        val file = File(dir(ctx), ReportFile.name(ref))
        val (read, write) = ParcelFileDescriptor.createPipe()
        val writer = Thread {
            try { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(plain) } } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }
        val c = LinkClient(ctx)
        val size = try { c.sealReport(key, ref, read) } finally { read.close(); c.close() }
        writer.join(TMP_MS)
        return file.takeIf { size != null && it.isFile && it.length() == size.toLong() }
    }

    /** The share sheet for [file]: ACTION_SEND, the address and subject for an email app, a read grant on that file alone. */
    fun sendIntent(ctx: Context, file: File, ref: String, title: String, kind: FeedbackKind, parts: Set<ReportPart>): Intent {
        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(TO)).putExtra(Intent.EXTRA_SUBJECT, subject(ref))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        // RP6: the chooser tells [ReportPicked] when an app is picked (Back tells nothing). Explicit and one-shot; mutable
        // only so the system can add EXTRA_CHOSEN_COMPONENT; the report is fixed in its data, which fill-in can't change.
        val picked = Intent(ctx, ReportPicked::class.java).setData(Uri.parse(HandedReports.data(HandedReport(ref, kind, parts, file.length()))))
        val pi = PendingIntent.getBroadcast(ctx, 0, picked, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
        return Intent.createChooser(send, title, pi.intentSender)
    }

    /** When [f] may go: a sealed copy [EARLY_MS] before its 24 hours, a left-over temporary file a minute on. */
    private fun due(f: File): Long = f.lastModified() + if (f.name.endsWith(TMP)) TMP_MS else KEEP_MS - EARLY_MS

    /** Deletes the sealed copies and temporary files that are due; how many went. */
    fun sweep(dir: File, now: Long): Int = (dir.listFiles() ?: emptyArray()).count { f -> f.isFile && now >= due(f) && f.delete() }

    /** The time the next file in [dir] is due, or null with none: each copy keeps its own deadline. */
    fun next(dir: File): Long? = (dir.listFiles() ?: emptyArray()).filter { it.isFile }.minOfOrNull { due(it) }

    /** At the main app's or :voice's start, whichever comes first, and after a reboot ([BootReceiver]): what an alarm missed, then the next alarm. */
    fun start(ctx: Context) {
        val name = Application.getProcessName()
        if (name != ctx.packageName && name != ctx.packageName + ":voice") return // l10n: not text (a process name)
        val app = ctx.applicationContext
        Thread { sweep(dir(app), System.currentTimeMillis()); schedule(app) }.apply { isDaemon = true; start() }
    }

    /** The one alarm, for the oldest file left (none left: cancelled). After a send, a sweep and a start. */
    fun schedule(ctx: Context) {
        val pi = PendingIntent.getBroadcast(ctx, REQ_EXPIRY, Intent(ctx, ReportExpiry::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try { next(dir(ctx))?.let { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it, pi) } ?: am.cancel(pi) } catch (_: Exception) {}
    }
}

/** RP5: the oldest sealed copy is due, from AlarmManager: the sweep, then the alarm for the next. Not exported, no filter. */
class ReportExpiry : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val done = goAsync()
        Thread { try { ReportOut.sweep(ReportOut.dir(ctx), System.currentTimeMillis()); ReportOut.schedule(ctx) } finally { done.finish() } }.start()
    }
}

/**
 * RP6: Send's chooser, an app picked (sys/ReportOut.kt's [ReportOut.sendIntent]). Not exported, no filter: only Robot's
 * own PendingIntent reaches it. It trusts nothing but that a pick happened for the report in its data, and never reads
 * which app (none is kept): the report goes into Reports you sent, and What went online gets a "handed to another app" row.
 */
class ReportPicked : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val h = HandedReports.of(intent?.dataString) ?: return
        val app = ctx.applicationContext
        val done = goAsync()
        Thread {
            try { FeedbackFiles.addSent(app, SentReport(h.ref, System.currentTimeMillis(), h.kind, h.parts)); NetRecord.handed(h.kind, h.size) } finally { done.finish() }
        }.start()
    }
}
