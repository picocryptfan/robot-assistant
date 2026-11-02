// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import app.heyrobot.core.RLog
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetEntry
import app.heyrobot.core.online.NetFiles
import app.heyrobot.core.online.NetHost
import app.heyrobot.core.online.NetMore
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import app.heyrobot.core.online.NetSink
import app.heyrobot.core.online.OnlineSummary
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlin.concurrent.thread
import java.time.ZoneId

/**
 * MAIN, VOICE AND LINK PROCESSES. What went online's writer (docs/plans/everyday-set-plan.md §5.3, §5.8, D10, D11): core
 * NetRecord's sink in each process that goes online, each appending to its own file a day,
 * files/online/<process>-<yyyymmdd>.jsonl, so no two processes share a file. Only while "Keep a record of what went
 * online" is on (everyday_settings.json); trimmed to the days kept at each start, each new day and when the page opens, on or off; at most 2 MiB
 * a process a day, then counts only. On the phone only: not in files/robot, never in a backup, never sent (Send
 * feedback's tick adds hosts, kinds and counts only, and only when you tick it).
 *
 * Also the downloads Android's download service runs for Robot (main process only, where Settings starts them): each is
 * recorded when it is handed over, and again once it has ended ([settleDownloads], from the download pages' own checks
 * and the page), so a finished download's row may come a little after it finished.
 */
object NetLog {
    private val PROCESSES = setOf("main", "voice", "link")
    private const val DOWNLOADS = "robot_online_downloads"

    @Volatile private var app: Context? = null
    private val lock = Any()
    private var trimmed: LocalDate? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, NetFiles.DIR)

    /** This process's name in the record, or null where Robot never goes online (:brain, :files, :lock, :llama, :mt, :linkparse). */
    fun process(): String? = Application.getProcessName().let { if (it.contains(':')) it.substringAfter(':') else "main" }.takeIf { it in PROCESSES }

    /** RobotApp: this process's writer, in main, :voice and :link (E4). */
    fun start(a: Application) {
        val p = process() ?: return
        app = a.applicationContext
        NetRecord.process = p
        NetRecord.sink = NetSink { e -> write(e) }
        // E10: the days kept hold whether or not the record is on, or anything goes online today.
        thread(isDaemon = true, name = "net_trim") { // l10n: not text
            try {
                synchronized(lock) {
                    val today = LocalDate.now()
                    trimmed = today
                    trimNow(a, EverydaySettingsStore.current(a).onlineDays, today)
                }
            } catch (t: Throwable) {
                RLog.w("net_log", t)
            }
        }
    }

    private fun write(e: NetEntry) {
        val ctx = app ?: return
        val s = EverydaySettingsStore.current(ctx)
        try {
            synchronized(lock) {
                val day = NetFiles.dayOf(e.time)
                if (trimmed != day) {
                    trimmed = day
                    trimNow(ctx, s.onlineDays, day)
                }
                if (!s.online) return
                val d = dir(ctx).apply { mkdirs() }
                val f = File(d, NetFiles.rows(e.process, day))
                val bytes = (NetRecord.encode(e) + "\n").toByteArray(Charsets.UTF_8)
                if (NetFiles.fits(if (f.isFile) f.length() else 0, bytes.size)) FileOutputStream(f, true).use { it.write(bytes) }
                else {
                    val m = File(d, NetFiles.more(e.process, day))
                    writeAtomic(m, (NetMore.decode(readOrNull(m)) + e).encode())
                }
            }
        } catch (t: Throwable) {
            RLog.w("net_log", t)
        }
    }

    private fun trimNow(ctx: Context, days: Int, today: LocalDate) {
        val names = dir(ctx).list()?.toList() ?: return
        for (n in NetFiles.expired(names, today, days)) File(dir(ctx), n).delete()
    }

    /** [MAIN] The days kept, now (the page opens, the days kept changed). */
    fun trim(ctx: Context, days: Int = EverydaySettingsStore.current(ctx).onlineDays) = synchronized(lock) { trimNow(ctx, days, LocalDate.now()) }

    /** [MAIN] Every row kept, from each process, oldest first (a row another process is writing just now is skipped until it's whole). */
    fun read(ctx: Context): List<NetEntry> = readKept(ctx).first

    /** [MAIN] [read], and whether the record was too long to read whole (the newest days are read first: [NetFiles.toRead]). */
    fun readKept(ctx: Context): Pair<List<NetEntry>, Boolean> {
        val pick = NetFiles.toRead((dir(ctx).listFiles()?.toList() ?: emptyList()).associate { it.name to it.length() })
        return pick.names.flatMap { NetRecord.decodeAll(readOrNull(File(dir(ctx), it))) }.sortedBy { it.time } to pick.cut
    }

    /** [MAIN] The counts kept past a day's cap, by day. */
    fun more(ctx: Context): Map<LocalDate, NetMore> = (dir(ctx).listFiles()?.toList() ?: emptyList())
        .mapNotNull { f -> NetFiles.parse(f.name)?.takeIf { !it.third }?.let { it.second to NetMore.decode(readOrNull(f)) } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.fold(NetMore()) { a, b -> a + b } }

    /** [MAIN] Send feedback's tick (§5.8): the last 7 days' rows, and whether the record is on. */
    fun lastWeek(ctx: Context): Pair<List<NetEntry>, Boolean> {
        val since = LocalDate.now().minusDays((OnlineSummary.FEEDBACK_DAYS - 1).toLong())
        return read(ctx).filter { NetFiles.dayOf(it.time) >= since } to EverydaySettingsStore.current(ctx).online
    }

    /** [MAIN] Delete the record (its own dialog on the page). */
    fun delete(ctx: Context) = synchronized(lock) {
        dir(ctx).listFiles()?.forEach { if (NetFiles.parse(it.name) != null) it.delete() }
        Unit
    }

    // ---- Android's own count (§5.4).

    /** Bytes Android counted for Robot's uid since the phone started, sent and received; null when it gives none. */
    fun androidBytes(): Long? {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        return if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong() || rx < 0 || tx < 0) null else rx + tx
    }

    /** When the phone started (wall clock, ms). */
    fun bootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    // ---- Downloads (main process).

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(DOWNLOADS, Context.MODE_PRIVATE)

    /**
     * [MAIN] A download handed to Android's download service ([id], DownloadManager's): its host and planned size now,
     * and its end once [settleDownloads] sees it.
     */
    fun downloadStarted(ctx: Context, id: Long, purpose: NetPurpose, url: String, planned: Long) {
        NetRecord.downloadStarted(purpose, url, planned)
        prefs(ctx).edit().putString(id.toString(), purpose.code + " " + NetHost.ofUrl(url)).apply()
    }

    /** [MAIN] Each download recorded as started that has ended since: its bytes and how it ended. */
    fun settleDownloads(ctx: Context) = settle(ctx, null)

    /** [MAIN] Downloads you stopped ([ids]), before they are removed: what they had fetched. */
    fun downloadsStopped(ctx: Context, ids: Collection<Long>) = settle(ctx, ids.toSet())

    private fun settle(ctx: Context, stopped: Set<Long>?) {
        // The download pages are the main app's; its own preferences are never read from another process.
        if (process() != "main") return
        try {
            val p = prefs(ctx)
            val pending = p.all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> (v as? String)?.let { id to it } } }.toMap()
            val ids = if (stopped == null) pending.keys else pending.keys.filter { it in stopped }
            if (ids.isEmpty()) return
            val rows = HashMap<Long, Pair<Int, Long>>()
            ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(*ids.toLongArray())).use { c ->
                while (c.moveToNext()) rows[c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))] =
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            }
            val e = p.edit()
            for (id in ids) {
                val (code, host) = pending.getValue(id).split(' ', limit = 2).let { it[0] to it.getOrElse(1) { NetHost.UNKNOWN } }
                val purpose = NetPurpose.of(code)
                if (purpose == null) {
                    e.remove(id.toString())
                    continue
                }
                val row = rows[id]
                val result = when {
                    stopped != null -> NetResult.STOPPED
                    row == null -> NetResult.STOPPED
                    row.first == DownloadManager.STATUS_SUCCESSFUL -> NetResult.DONE
                    row.first == DownloadManager.STATUS_FAILED -> NetResult.FAILED
                    else -> null
                } ?: continue
                NetRecord.record(purpose, host.takeIf { NetHost.valid(it) && !NetHost.marker(it) }, NetCause.TAP, 0, row?.second?.coerceAtLeast(0) ?: 0, result)
                e.remove(id.toString())
            }
            e.apply()
        } catch (t: Throwable) {
            RLog.w("net_log_downloads", t)
        }
    }

    /** The zone days are counted in (the phone's). */
    val zone: ZoneId get() = ZoneId.systemDefault()
}
