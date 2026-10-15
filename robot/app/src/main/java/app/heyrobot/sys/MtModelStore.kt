// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.BuildConfig
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import app.heyrobot.core.FileDownload
import app.heyrobot.core.InstallResult
import app.heyrobot.core.InstalledFile
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtFile
import app.heyrobot.core.MtMark
import app.heyrobot.core.MtModelLogic
import app.heyrobot.core.DownloadPolicy
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceFacts
import app.heyrobot.core.VoiceMark
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.installGzVerified
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// MAIN PROCESS. The fast translation engine's files (core MtBundle.kt, docs/conversation-mode.md 4.6), only when you tap
// Download in Settings › Conversation: DownloadManager fetches each .gz from its pinned URL into Robot's download folder
// (it resumes after a dropped connection and carries on with the app closed); each finished .gz is then checked
// (size and SHA-256), unpacked as a stream into filesDir/models/<bundle>/ with the result checked again, kept only if
// both match, and its download deleted. The verified mark grows file by file, so an interrupted install costs only that
// file. The :mt process reads the mark before it loads anything. The same pattern as the voice models (VoiceModelStore).

object MtModelStore {
    private const val PREFS = "robot_mt_models"
    /** Robot's download folder for these files, under app-specific external storage. */
    private const val STAGING = "mt-download"
    /** The library the :mt process loads; a build made without tools/build_robotmt.sh has none. */
    const val LIBRARY = "librobotmt.so"

    data class Snap(
        val bundle: MtBundle,
        val status: ModelStatus,
        val progress: String?,
        val percent: Int,
        val failure: String?,
        /** librobotmt.so is in this build. */
        val engine: Boolean,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun dir(ctx: Context, b: MtBundle): File = MtBundle.dir(ctx.filesDir, b)
    private fun markFile(ctx: Context, b: MtBundle) = File(dir(ctx, b), VoiceMark.FILE)

    /** The engine is in this APK (the extracted native libraries, extractNativeLibs=true). */
    fun engineInBuild(ctx: Context): Boolean = try {
        File(ctx.applicationInfo.nativeLibraryDir, LIBRARY).isFile
    } catch (_: Exception) { false }

    private fun staging(ctx: Context, b: MtBundle): File? = ctx.getExternalFilesDir(null)?.let { File(it, "$STAGING/${b.id}") }
    private fun staged(ctx: Context, b: MtBundle, f: MtFile): File? = staging(ctx, b)?.let { File(it, "${f.name}.gz") }
    private fun stagedComplete(ctx: Context, b: MtBundle, f: MtFile) = staged(ctx, b, f)?.let { it.isFile && it.length() == f.gzBytes } == true

    private fun mark(ctx: Context, b: MtBundle): List<InstalledFile> = VoiceMark.decode(readOrNull(markFile(ctx, b)))

    private fun vouched(ctx: Context, b: MtBundle): Set<String> = MtMark.vouched(b, mark(ctx, b)) { name ->
        File(dir(ctx, b), name).takeIf { it.isFile }?.let { it.length() to it.lastModified() }
    }

    fun remaining(ctx: Context, b: MtBundle): List<MtFile> = vouched(ctx, b).let { v -> b.files.filter { it.name !in v } }

    fun verified(ctx: Context, b: MtBundle): Boolean = remaining(ctx, b).isEmpty()

    // ------------------------------------------------------------------ download rows

    private fun ids(ctx: Context, b: MtBundle): Map<String, Long> = prefs(ctx).getString("dl_${b.id}", null).orEmpty()
        .split(',').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { kv -> kv[1].toLongOrNull()?.let { kv[0] to it } } }.toMap()

    private fun setIds(ctx: Context, b: MtBundle, m: Map<String, Long>) {
        prefs(ctx).edit().apply { if (m.isEmpty()) remove("dl_${b.id}") else putString("dl_${b.id}", m.entries.joinToString(",") { "${it.key}=${it.value}" }) }.apply()
    }

    private fun query(ctx: Context, ids: Collection<Long>): Map<Long, FileDownload> {
        if (ids.isEmpty()) return emptyMap()
        return try {
            ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(*ids.toLongArray())).use { c ->
                val out = HashMap<Long, FileDownload>()
                while (c.moveToNext()) {
                    out[c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))] = FileDownload(
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    )
                }
                out
            }
        } catch (_: Exception) { emptyMap() }
    }

    /** What this bundle's downloads under way will still take from the free space (for the other downloads' checks). */
    fun pendingBytes(ctx: Context): Long = MtBundle.entries.sumOf { b ->
        try {
            val ids = ids(ctx, b)
            if (ids.isEmpty()) 0L else {
                val rows = query(ctx, ids.values)
                MtModelLogic.pendingBytes(remaining(ctx, b), ids.mapNotNull { (name, id) -> rows[id]?.let { name to it } }.toMap())
            }
        } catch (_: Exception) { 0L }
    }

    // ------------------------------------------------------------------ status

    @Volatile private var installing: MtBundle? = null
    @Volatile private var installPercent = 0

    fun snapshot(ctx: Context, b: MtBundle): Snap {
        val v = vouched(ctx, b)
        val rem = b.files.filter { it.name !in v }
        val ids = ids(ctx, b)
        val rows = query(ctx, ids.values)
        val active = rem.mapNotNull { f -> ids[f.name]?.let { rows[it] } }
        val downloading = active.any(VoiceModelLogic::inFlight)
        if (!downloading && active.any { it.status == DownloadManager.STATUS_FAILED }) {
            val failed = active.first { it.status == DownloadManager.STATUS_FAILED }
            prefs(ctx).edit().putString("fail_${b.id}", MtModelLogic.downloadText(b, 0, listOf(failed)).first).apply()
            RLog.i("mt_download", "bundle" to RLog.Code(b.id), "op" to RLog.Code("failed"), "reason" to failed.reason)
            setIds(ctx, b, ids.filterValues { rows[it]?.status != DownloadManager.STATUS_FAILED })
        }
        val facts = VoiceFacts(
            vouched = v.size,
            downloading = downloading,
            downloaded = rem.isNotEmpty() && !downloading && rem.all { stagedComplete(ctx, b, it) },
            installing = installing == b,
            bad = prefs(ctx).contains("bad_${b.id}"),
            installFailed = prefs(ctx).contains("installfail_${b.id}"),
        )
        val status = MtModelLogic.status(b, facts)
        var percent = -1
        val progress = when (status) {
            ModelStatus.DOWNLOADING -> {
                val done = b.files.filter { it.name in v }.sumOf { it.gzBytes }
                MtModelLogic.downloadText(b, done, active).let { percent = it.second; it.first }
            }
            ModelStatus.VERIFYING -> { percent = installPercent; VoiceModelLogic.installText(installPercent) }
            ModelStatus.UNVERIFIED -> "Downloaded. Checking the files next."
            else -> null
        }
        val failure = when (status) {
            ModelStatus.BAD -> "A downloaded file didn't match its checksum, so Robot deleted it. Tap Download to fetch it again."
            ModelStatus.MISSING -> prefs(ctx).getString("fail_${b.id}", null)
            else -> null
        }
        return Snap(b, status, progress, percent, failure, engineInBuild(ctx))
    }

    // ------------------------------------------------------------------ download, install, delete

    /** Null when the download started (or goes on), else why not in plain words. */
    fun startDownload(ctx: Context, b: MtBundle): String? {
        val rem = remaining(ctx, b)
        val staged = rem.filter { stagedComplete(ctx, b, it) }.map { it.name }.toSet()
        val pending = VoiceModelStore.pendingBytes(ctx) + ModelStore.pendingBytes(ctx)
        MtModelLogic.refuseDownload(b, snapshot(ctx, b).status, rem, VoiceModelStore.freeBytes(ctx), staged, pending)?.let { return it }
        val dir = staging(ctx, b) ?: return "The phone's storage isn't available right now."
        dir.mkdirs()
        prefs(ctx).edit().remove("bad_${b.id}").remove("fail_${b.id}").remove("installfail_${b.id}").apply()
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val ids = ids(ctx, b).toMutableMap()
        val rows = query(ctx, ids.values)
        return try {
            for (f in rem) {
                val id = ids[f.name]
                val row = id?.let { rows[it] }
                if (VoiceModelLogic.inFlight(row)) continue
                if (row?.status == DownloadManager.STATUS_SUCCESSFUL && stagedComplete(ctx, b, f)) continue
                id?.let { dm.remove(it) }
                staged(ctx, b, f)?.delete()
                val req = DownloadManager.Request(Uri.parse(b.url(f)))
                    .setTitle("Robot: fast translation engine")
                    .setDescription(VoiceModelLogic.size(f.gzBytes))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(ctx, null, "$STAGING/${b.id}/${f.name}.gz")
                    .addRequestHeader("User-Agent", DownloadPolicy.userAgent(BuildConfig.VERSION_NAME))
                ids[f.name] = dm.enqueue(req)
            }
            setIds(ctx, b, ids)
            RLog.i("mt_download", "bundle" to RLog.Code(b.id), "op" to RLog.Code("start"), "files" to rem.size)
            null
        } catch (t: Throwable) {
            setIds(ctx, b, ids)
            RLog.w("mt_download", t, "bundle" to RLog.Code(b.id))
            "The download couldn't start."
        }
    }

    fun cancelDownload(ctx: Context, b: MtBundle) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        for (id in ids(ctx, b).values) try { dm.remove(id) } catch (_: Exception) {}
        setIds(ctx, b, emptyMap())
        staging(ctx, b)?.deleteRecursively()
        RLog.i("mt_download", "bundle" to RLog.Code(b.id), "op" to RLog.Code("cancel"))
    }

    /**
     * Checks each downloaded .gz and unpacks it into app-private storage, checking the result (blocking: about a
     * second for 52 MB). True when every file is installed.
     */
    fun install(ctx: Context, b: MtBundle): Boolean {
        synchronized(this) {
            if (installing != null) return false
            installing = b
            installPercent = 0
        }
        try {
            val v = vouched(ctx, b)
            val rem = b.files.filter { it.name !in v }
            val marks = mark(ctx, b).filter { it.name in v }.toMutableList()
            val total = rem.sumOf { it.bytes }.coerceAtLeast(1)
            var done = 0L
            val ids = ids(ctx, b).toMutableMap()
            val dm = ctx.getSystemService(DownloadManager::class.java)
            for (f in rem) {
                val src = staged(ctx, b, f)
                if (src == null || !src.isFile || src.length() != f.gzBytes) {
                    RLog.i("mt_install", "bundle" to RLog.Code(b.id), "ok" to false, "result" to RLog.Code("missing"))
                    return false
                }
                val dest = File(dir(ctx, b), f.name)
                val r = installGzVerified(src, dest, f) { n -> installPercent = ((done + n) * 100 / total).toInt() }
                if (r != InstallResult.OK) {
                    if (r == InstallResult.IO_ERROR) {
                        prefs(ctx).edit().putString("fail_${b.id}", "Robot couldn't unpack the files into its storage (is the phone full?). " +
                            "Free some space, then tap Download to try again.").putBoolean("installfail_${b.id}", true).apply()
                    } else {
                        // The download is damaged: delete it so the next attempt fetches it again.
                        prefs(ctx).edit().putString("bad_${b.id}", f.name).apply()
                        ids.remove(f.name)?.let { try { dm.remove(it) } catch (_: Exception) {} }
                        src.delete()
                        setIds(ctx, b, ids)
                    }
                    RLog.i("mt_install", "bundle" to RLog.Code(b.id), "ok" to false, "result" to r)
                    return false
                }
                marks.removeAll { it.name == f.name }
                marks += InstalledFile(f.name, f.bytes, dest.lastModified(), f.sha256)
                writeAtomic(markFile(ctx, b), VoiceMark.encode(marks))
                ids.remove(f.name)?.let { try { dm.remove(it) } catch (_: Exception) {} }
                src.delete()
                setIds(ctx, b, ids)
                done += f.bytes
            }
            staging(ctx, b)?.deleteRecursively()
            prefs(ctx).edit().remove("bad_${b.id}").remove("fail_${b.id}").remove("installfail_${b.id}").apply()
            val ok = verified(ctx, b)
            RLog.i("mt_install", "bundle" to RLog.Code(b.id), "ok" to ok)
            return ok
        } catch (t: Throwable) {
            RLog.w("mt_install", t, "bundle" to RLog.Code(b.id))
            prefs(ctx).edit().putString("fail_${b.id}", "Installing failed. Tap Download to try again.").putBoolean("installfail_${b.id}", true).apply()
            return false
        } finally {
            installing = null
        }
    }

    /**
     * Deletes the files and any download. A :mt process that has them loaded keeps its mapped copy until it unloads
     * (at most five idle minutes); it never loads them again without the mark.
     */
    fun delete(ctx: Context, b: MtBundle) {
        cancelDownload(ctx, b)
        prefs(ctx).edit().remove("bad_${b.id}").remove("fail_${b.id}").remove("installfail_${b.id}").apply()
        dir(ctx, b).deleteRecursively()
        RLog.i("mt_delete", "bundle" to RLog.Code(b.id))
    }
}
