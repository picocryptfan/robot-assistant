// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.DownloadPolicy
import app.heyrobot.core.ModelMove
import app.heyrobot.core.RLog
import app.heyrobot.core.memory.MeaningSettings
import app.heyrobot.core.SearchModel
import app.heyrobot.core.memory.SearchModelLogic
import app.heyrobot.core.memory.SearchModelState
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.FileInputStream

// MAIN PROCESS. Search by meaning's model (docs/plans/meaning-search-plan.md), only when you tap Download in Settings ›
// Memory: DownloadManager fetches the pinned file (core SearchModel.URL: one repository at one commit) into Robot's
// download folder, on Wi-Fi unless you allowed mobile data (meaning-search.json); the finished file is checked (size,
// then SHA-256) as it is copied into filesDir/models, kept only if both match, and a verified mark written. :brain loads
// it only through [verifiedPath]. Delete removes the model, its download and every source's vectors (MeaningVectors).
// The same path as the Gemma models (ModelStore) and the voice models.

object SearchModelStore {
    private const val PREFS = "robot_search_model"
    private const val STAGING = "search-download"
    private const val MARK = "search-model.verified"

    data class Snap(val state: SearchModelState, val percent: Int, val anyNetwork: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun file(ctx: Context): File = File(ModelStore.dir(ctx), SearchModel.FILE)
    private fun mark(ctx: Context) = File(ModelStore.dir(ctx), MARK)
    private fun staged(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, "$STAGING/${SearchModel.FILE}") }
    private fun settingsFile(ctx: Context) = File(ctx.filesDir, MeaningSettings.FILE)

    fun settings(ctx: Context): MeaningSettings = MeaningSettings.fromJson(readOrNull(settingsFile(ctx)))
    fun saveSettings(ctx: Context, s: MeaningSettings) = writeAtomic(settingsFile(ctx), s.toJson())

    /** The checked model's path, or null: nothing loads the file without this. */
    fun verifiedPath(ctx: Context): String? {
        val f = file(ctx)
        return if (f.isFile && SearchModelLogic.vouched(readOrNull(mark(ctx)), f.length(), f.lastModified())) f.absolutePath else null
    }

    @Volatile private var checking = false
    @Volatile private var checkPercent = 0

    private fun downloadId(ctx: Context): Long? = prefs(ctx).getLong("download_id", -1L).takeIf { it >= 0 }

    /** DownloadManager's status and bytes so far for our download, or null when it has none. */
    private fun row(ctx: Context): Triple<Int, Long, Long>? {
        val id = downloadId(ctx) ?: return null
        return try {
            ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) null else Triple(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
            }
        } catch (_: Exception) { null }
    }

    fun snapshot(ctx: Context): Snap {
        // What went online: a download that ended since the last look gets its end recorded, as the other stores do.
        NetLog.settleDownloads(ctx)
        val r = row(ctx)
        val state = SearchModelLogic.state(
            verified = verifiedPath(ctx) != null,
            checking = checking,
            downloadStatus = r?.first,
            waitingForWifi = r?.first == DownloadManager.STATUS_PAUSED && r.third == DownloadManager.PAUSED_QUEUED_FOR_WIFI.toLong(),
            stagedBytes = staged(ctx)?.takeIf { it.isFile }?.length() ?: 0,
            failed = prefs(ctx).getBoolean("failed", false),
            roaming = r?.first == DownloadManager.STATUS_PAUSED && r.third == DownloadManager.PAUSED_WAITING_FOR_NETWORK.toLong() && DownloadNetwork.roaming(ctx),
        )
        val percent = if (checking) checkPercent else SearchModelLogic.percent(r?.second ?: 0)
        return Snap(state, percent, settings(ctx).anyNetwork)
    }

    /** Null when the download started, else why not, in plain words. */
    fun startDownload(ctx: Context): String? {
        if (verifiedPath(ctx) != null || downloadId(ctx) != null) return null
        val pending = VoiceModelStore.pendingBytes(ctx) + ModelStore.pendingBytes(ctx) + MtModelStore.pendingBytes(ctx)
        if (!SearchModelLogic.room(VoiceModelStore.freeBytes(ctx), pending)) return ctx.getString(R.string.memory_search_no_room)
        val dest = staged(ctx) ?: return ctx.getString(R.string.memory_search_no_room)
        dest.parentFile?.mkdirs()
        dest.delete()
        prefs(ctx).edit().remove("failed").apply()
        val req = DownloadManager.Request(Uri.parse(SearchModel.URL))
            .setTitle(ctx.getString(R.string.memory_search_title))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, "$STAGING/${SearchModel.FILE}")
            .addRequestHeader("User-Agent", DownloadPolicy.userAgent(BuildConfig.VERSION_NAME))
            .setAllowedOverMetered(settings(ctx).anyNetwork)
            .setAllowedOverRoaming(false)
        return try {
            val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            // What went online: handed to Android's download service; its end is recorded once it has ended.
            NetLog.downloadStarted(ctx, id, NetPurpose.MODEL_DOWNLOAD, SearchModel.URL, SearchModel.BYTES)
            prefs(ctx).edit().putLong("download_id", id).apply()
            RLog.i("search_model", "op" to RLog.Code("start"), "metered" to settings(ctx).anyNetwork)
            null
        } catch (t: Throwable) {
            RLog.w("search_model", t)
            ctx.getString(R.string.memory_search_failed)
        }
    }

    fun cancelDownload(ctx: Context) {
        downloadId(ctx)?.let { id ->
            NetLog.downloadsStopped(ctx, listOf(id))
            try { ctx.getSystemService(DownloadManager::class.java).remove(id) } catch (_: Exception) {}
        }
        prefs(ctx).edit().remove("download_id").apply()
        staged(ctx)?.delete()
    }

    /**
     * Checks the finished download as it copies it in (blocking: a few seconds for 165 MB), then writes the mark.
     * A file of the wrong size or hash is deleted and marked failed, so the next tap fetches it again.
     */
    fun install(ctx: Context): Boolean {
        synchronized(this) { if (checking) return false; checking = true; checkPercent = 0 }
        val src = staged(ctx)
        try {
            if (src == null || !src.isFile || src.length() != SearchModel.BYTES) return false
            val dir = ModelStore.dir(ctx).apply { mkdirs() }
            val tmp = File(dir, "${SearchModel.FILE}.part")
            val sha = FileInputStream(src).use { input ->
                java.io.FileOutputStream(tmp).use { out ->
                    ModelMove.sha256(TeeStream(input, out)) { done -> checkPercent = SearchModelLogic.percent(done) }.also { out.fd.sync() }
                }
            }
            val ok = sha == SearchModel.SHA256 && tmp.length() == SearchModel.BYTES && tmp.renameTo(file(ctx))
            if (ok) writeAtomic(mark(ctx), SearchModelLogic.mark(file(ctx).length(), file(ctx).lastModified()))
            else { tmp.delete(); prefs(ctx).edit().putBoolean("failed", true).apply() }
            cancelDownload(ctx)
            RLog.i("search_model", "op" to RLog.Code("check"), "ok" to ok)
            return ok
        } catch (t: Throwable) {
            RLog.w("search_model", t)
            prefs(ctx).edit().putBoolean("failed", true).apply()
            return false
        } finally {
            checking = false
        }
    }

    /** Deletes the model, any download, and every source's vectors (they came from your private text). */
    fun delete(ctx: Context) {
        cancelDownload(ctx)
        mark(ctx).delete()
        file(ctx).delete()
        File(ModelStore.dir(ctx), "${SearchModel.FILE}.part").delete()
        prefs(ctx).edit().remove("failed").apply()
        val n = MeaningVectors.deleteAll(ctx)
        RLog.i("search_model", "op" to RLog.Code("delete"), "vectors" to n)
    }

    /** Copies what is read to [out], so the hash and the copy are one pass. */
    private class TeeStream(private val src: java.io.InputStream, private val out: java.io.OutputStream) : java.io.InputStream() {
        override fun read(): Int = src.read().also { if (it >= 0) out.write(it) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len).also { if (it > 0) out.write(b, off, it) }
    }
}

/** Where each source keeps its vectors, beside its data and with its protection (docs/plans/meaning-search-plan.md). */
object MeaningVectors {
    fun memory(ctx: Context): File = File(MemoryFiles.dir(ctx), FileMemoryVault.INDEX)
    fun notes(ctx: Context): File = File(ctx.filesDir, "notes-vectors.bin")
    fun files(ctx: Context): File = File(ctx.filesDir, "files/vectors.enc")
    fun logbook(ctx: Context): File = File(ctx.filesDir, "logbook-vectors.bin")
    fun all(ctx: Context): List<File> = listOf(memory(ctx), notes(ctx), files(ctx), logbook(ctx))

    /** Deletes every vector file; how many there were. */
    fun deleteAll(ctx: Context): Int = all(ctx).count { it.isFile && it.delete() }
}
