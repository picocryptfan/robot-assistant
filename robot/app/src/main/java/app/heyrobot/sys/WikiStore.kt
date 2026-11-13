// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.DownloadPolicy
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.readOrNull
import app.heyrobot.core.wiki.WikiDownload
import app.heyrobot.core.wiki.WikiFiles
import app.heyrobot.core.wiki.WikiHops
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import app.heyrobot.core.wiki.WikiFilesCodec
import app.heyrobot.core.wiki.WikiSet
import app.heyrobot.core.writeAtomic
import java.io.File

// MAIN PROCESS. Wikipedia on this phone, P3 (wikipedia-offline-plan.md §2; core wiki/WikiSets.kt): Settings › Wikipedia on
// this phone starts a copy's download when you tap it, never on its own. Android's download manager fetches it from
// download.kiwix.org (and the mirror it sends Robot to) on Wi-Fi or another unmetered network only, never roaming, into
// Robot's outside folder; once it has finished, the page copies it into filesDir/wiki with every byte hashed and keeps it
// only at its pinned size and SHA-256 (a wrong file is deleted, never moved). wikipedia.json (files/robot) holds what is
// on the phone and the downloads under way; no backup takes it, and it isn't one of Settings' files (schema stays 13).
object WikiStore {
    enum class State { NONE, WAITING_WIFI, DOWNLOADING, DOWNLOADED, FAILED, ON_PHONE }

    data class Row(val set: WikiSet, val state: State, val percent: Int)

    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "robot/${WikiFilesCodec.FILE}")
    fun load(ctx: Context): WikiFiles = WikiFilesCodec.decode(readOrNull(file(ctx)))
    private fun save(ctx: Context, f: WikiFiles) = writeAtomic(file(ctx), WikiFilesCodec.encode(f))
    fun freeBytes(ctx: Context): Long = try { StatFs(ctx.filesDir.path).availableBytes } catch (_: Exception) { -1 }
    private fun staged(ctx: Context, s: WikiSet): File? = ctx.getExternalFilesDir(null)?.let { File(it, WikiDownload.request(s).path) }
    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    private data class Dl(val status: Int, val done: Long, val reason: Int)

    private fun query(ctx: Context, id: Long): Dl? = try {
        dm(ctx).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) null else Dl(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
        }
    } catch (_: Exception) { null }

    /** What the copies' downloads under way still write (the download manager reserves no space). */
    fun pendingBytes(ctx: Context, f: WikiFiles = load(ctx)): Long = f.downloads.entries.sumOf { (id, n) ->
        val s = WikiSet.byId(id) ?: return@sumOf 0L
        val d = query(ctx, n)
        if (d == null || d.status == DownloadManager.STATUS_FAILED || d.status == DownloadManager.STATUS_SUCCESSFUL) 0L
        else (s.bytes - d.done).coerceAtLeast(0)
    }

    /** Each copy and where it stands (blocking: off the main thread). */
    fun rows(ctx: Context): List<Row> {
        NetLog.settleDownloads(ctx)
        val f = synchronized(lock) { load(ctx).let { old -> WikiDownload.tidy(ctx.filesDir, old).also { if (it != old) save(ctx, it) } } }
        return WikiSet.entries.map { s ->
            if (WikiDownload.installed(ctx.filesDir, f, s)) return@map Row(s, State.ON_PHONE, 100)
            val id = f.downloads[s.id] ?: return@map Row(s, State.NONE, 0)
            val d = query(ctx, id) ?: return@map Row(s, State.FAILED, 0)
            val pct = (d.done * 100 / s.bytes).toInt().coerceIn(0, 100)
            Row(s, when (d.status) {
                DownloadManager.STATUS_SUCCESSFUL -> State.DOWNLOADED
                DownloadManager.STATUS_FAILED -> State.FAILED
                DownloadManager.STATUS_PAUSED -> if (d.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ||
                    d.reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) State.WAITING_WIFI else State.DOWNLOADING
                else -> State.DOWNLOADING
            }, pct)
        }
    }

    /** Null when the download started (or goes on), else why not in words. Only from your tap. */
    /** The active network when it is unmetered (Wi-Fi), else null: a copy's hops never go over mobile data. */
    private fun wifi(ctx: Context): android.net.Network? = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.activeNetwork?.takeIf { cm.getNetworkCapabilities(it)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true }
    } catch (_: Exception) { null }

    /** One HEAD on [n], redirects not followed: its status and Location (status -1 when it failed). */
    private fun head(n: android.net.Network, u: String): Pair<Int, String?> {
        var result = NetResult.FAILED
        val c = n.openConnection(java.net.URL(u)) as? javax.net.ssl.HttpsURLConnection ?: return -1 to null
        return try {
            c.requestMethod = "HEAD" // l10n: not text (an HTTP method)
            c.instanceFollowRedirects = false
            c.connectTimeout = HOP_MS
            c.readTimeout = HOP_MS
            c.setRequestProperty("User-Agent", DownloadPolicy.userAgent(BuildConfig.VERSION_NAME)) // l10n: not text (a header)
            (c.responseCode to c.getHeaderField("Location")).also { result = NetResult.DONE } // l10n: not text (a header)
        } catch (_: Exception) { -1 to null } finally {
            c.disconnect()
            // What went online: each hop, under the download's purpose (you tapped Download).
            NetRecord.record(NetPurpose.WIKI_DOWNLOAD, c.url.host, NetCause.TAP, 0, 0, result)
        }
    }
    private const val HOP_MS = 15_000

    fun start(ctx: Context, s: WikiSet): String? = synchronized(lock) {
        val f = load(ctx)
        val pending = pendingBytes(ctx, f) + VoiceModelStore.pendingBytes(ctx) + ModelStore.pendingBytes(ctx) + MtModelStore.pendingBytes(ctx)
        when (WikiDownload.refuse(s, freeBytes(ctx), pending, f, ctx.filesDir)) {
            WikiDownload.Refusal.NO_ROOM -> return ctx.getString(R.string.wiki_no_room,
                VoiceModelLogic.sizeText(WikiDownload.spaceNeeded(s)).render(AppLanguage.langs(ctx)))
            WikiDownload.Refusal.ALREADY -> return null
            null -> {}
        }
        val dest = staged(ctx, s) ?: return ctx.getString(R.string.wiki_no_storage)
        dest.delete()
        val r = WikiDownload.request(s)
        // L1: on Wi-Fi, the redirects to a mirror are followed here first and a plain-http hop at any host stops the download
        // (core WikiHops); off Wi-Fi nothing is asked now, and the download manager waits for Wi-Fi with Kiwix's own address.
        val url = wifi(ctx)?.let { n -> WikiHops.resolve(r.url) { u -> head(n, u) } ?: return ctx.getString(R.string.wiki_download_failed) } ?: r.url
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(ctx.getString(R.string.wiki_download_title))
                .setDescription(VoiceModelLogic.sizeText(s.bytes).render(AppLanguage.langs(ctx)))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, null, r.path)
                .addRequestHeader("User-Agent", DownloadPolicy.userAgent(BuildConfig.VERSION_NAME)) // l10n: not text (a header)
                .setAllowedOverMetered(r.overMetered)
                .setAllowedOverRoaming(r.overRoaming)
            val id = dm(ctx).enqueue(req)
            save(ctx, f.copy(downloads = f.downloads + (s.id to id)))
            // What went online: handed to Android's download service; its end is recorded once it has ended.
            NetLog.downloadStarted(ctx, id, NetPurpose.WIKI_DOWNLOAD, r.url, s.bytes)
            RLog.i("wiki_download", "set" to RLog.Code(s.id), "op" to RLog.Code("start"))
            null
        } catch (t: Throwable) {
            RLog.w("wiki_download", t, "set" to RLog.Code(s.id))
            ctx.getString(R.string.wiki_download_failed)
        }
    }

    /** Checks a finished download and copies it into filesDir/wiki (blocking: about a minute for 3 GB). */
    fun install(ctx: Context, s: WikiSet): WikiDownload.Moved = synchronized(lock) {
        val f = load(ctx)
        if (WikiDownload.installed(ctx.filesDir, f, s)) return WikiDownload.Moved.OK
        val src = staged(ctx, s) ?: return WikiDownload.Moved.FAILED
        val r = WikiDownload.moveIn(src, ctx.filesDir, s, { freeBytes(ctx) })
        if (r == WikiDownload.Moved.OK || r == WikiDownload.Moved.WRONG_FILE) {
            NetLog.settleDownloads(ctx) // its end recorded before its row goes
            f.downloads[s.id]?.let { try { dm(ctx).remove(it) } catch (_: Exception) {} }
            save(ctx, if (r == WikiDownload.Moved.OK) WikiDownload.withInstalled(f, s) else f.copy(downloads = f.downloads - s.id))
        }
        RLog.i("wiki_install", "set" to RLog.Code(s.id), "result" to r)
        r
    }

    /** Stops its download and deletes the copy, what is left of the download and its entry. */
    fun delete(ctx: Context, s: WikiSet) = synchronized(lock) {
        val f = load(ctx)
        f.downloads[s.id]?.let { id -> NetLog.downloadsStopped(ctx, listOf(id)); try { dm(ctx).remove(id) } catch (_: Exception) {} }
        staged(ctx, s)?.delete()
        save(ctx, WikiDownload.delete(ctx.filesDir, f, s))
        RLog.i("wiki_delete", "set" to RLog.Code(s.id))
    }
}
