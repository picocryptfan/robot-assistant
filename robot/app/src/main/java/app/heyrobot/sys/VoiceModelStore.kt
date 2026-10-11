package app.heyrobot.sys

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import app.heyrobot.core.FileDownload
import app.heyrobot.core.InstallResult
import app.heyrobot.core.InstalledFile
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceFacts
import app.heyrobot.core.VoiceFile
import app.heyrobot.core.VoiceMark
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.installVerified
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.FileInputStream

// MAIN PROCESS. The voice models in the model manager (core VoiceModels.kt): each bundle is a few single files at
// pinned Hugging Face commits. DownloadManager fetches them (it resumes by itself after a dropped connection, and
// carries on with the app closed) into Robot's own download folder, since it cannot write into app-private storage;
// each finished file is then copied into filesDir/models/<bundle>/ while its SHA-256 is computed, kept only if size
// and hash match, and its download deleted. The verified mark grows file by file, so an interrupted install or a
// failed file costs only that file. The :voice process is told (Cmd.VOICE_MODELS) when a bundle is complete.

object VoiceModelStore {
    private const val PREFS = "robot_voice_models"

    data class Snap(
        val bundle: VoiceBundle,
        val status: ModelStatus,
        /** A progress line while downloading or installing, else null. */
        val progress: String?,
        /** 0 to 100, or -1 when unknown. */
        val percent: Int,
        /** Why the last attempt failed, in plain words (shown while nothing runs). */
        val failure: String?,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun dir(ctx: Context, b: VoiceBundle): File = VoiceBundle.dir(ctx.filesDir, b)
    private fun markFile(ctx: Context, b: VoiceBundle) = File(dir(ctx, b), VoiceMark.FILE)

    /** DownloadManager's folder (app-specific external storage), or null if there is none. */
    private fun staging(ctx: Context, b: VoiceBundle): File? = ctx.getExternalFilesDir(null)?.let { File(it, "$STAGING/${b.id}") }
    private fun staged(ctx: Context, b: VoiceBundle, f: VoiceFile): File? = staging(ctx, b)?.let { File(it, f.name) }
    private fun stagedComplete(ctx: Context, b: VoiceBundle, f: VoiceFile) = staged(ctx, b, f)?.let { it.isFile && it.length() == f.bytes } == true

    private fun mark(ctx: Context, b: VoiceBundle): List<InstalledFile> = VoiceMark.decode(readOrNull(markFile(ctx, b)))

    private fun vouched(ctx: Context, b: VoiceBundle): Set<String> = VoiceMark.vouched(b, mark(ctx, b)) { name ->
        File(dir(ctx, b), name).takeIf { it.isFile }?.let { it.length() to it.lastModified() }
    }

    /** Files of [b] not installed and checked yet. */
    fun remaining(ctx: Context, b: VoiceBundle): List<VoiceFile> = vouched(ctx, b).let { v -> b.files.filter { it.name !in v } }

    fun verified(ctx: Context, b: VoiceBundle): Boolean = remaining(ctx, b).isEmpty()

    fun freeBytes(ctx: Context): Long = try { StatFs(ctx.filesDir.path).availableBytes } catch (_: Exception) { -1 }

    // ------------------------------------------------------------------ download rows

    /** The DownloadManager ids of a bundle's files ("name=id,name=id"). */
    private fun ids(ctx: Context, b: VoiceBundle): Map<String, Long> = prefs(ctx).getString("dl_${b.id}", null).orEmpty()
        .split(',').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { kv -> kv[1].toLongOrNull()?.let { kv[0] to it } } }.toMap()

    private fun setIds(ctx: Context, b: VoiceBundle, m: Map<String, Long>) {
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

    private fun running(r: FileDownload?) = VoiceModelLogic.inFlight(r)

    /** What [b]'s downloads under way will still take from the free space (0 when none runs). */
    private fun bundlePendingBytes(ctx: Context, b: VoiceBundle): Long {
        val ids = ids(ctx, b)
        if (ids.isEmpty()) return 0
        val rows = query(ctx, ids.values)
        val byName = ids.mapNotNull { (name, id) -> rows[id]?.let { name to it } }.toMap()
        return VoiceModelLogic.pendingBytes(remaining(ctx, b), byName)
    }

    /** What the voice-model downloads under way, other than [except]'s, will still take (for every space check). */
    fun pendingBytes(ctx: Context, except: VoiceBundle? = null): Long =
        VoiceBundle.entries.filter { it != except }.sumOf { try { bundlePendingBytes(ctx, it) } catch (_: Exception) { 0L } }

    // ------------------------------------------------------------------ status

    @Volatile private var installing: VoiceBundle? = null
    @Volatile private var installPercent = 0

    fun snapshot(ctx: Context, b: VoiceBundle): Snap {
        val v = vouched(ctx, b)
        val rem = b.files.filter { it.name !in v }
        val ids = ids(ctx, b)
        val rows = query(ctx, ids.values)
        val active = rem.mapNotNull { f -> ids[f.name]?.let { rows[it] } }
        val downloading = active.any(::running)
        if (!downloading && active.any { it.status == DownloadManager.STATUS_FAILED }) {
            val failed = active.first { it.status == DownloadManager.STATUS_FAILED }
            prefs(ctx).edit().putString("fail_${b.id}", VoiceModelLogic.downloadText(b, 0, listOf(failed)).first).apply()
            RLog.i("voice_download", "bundle" to RLog.Code(b.id), "op" to RLog.Code("failed"), "reason" to failed.reason)
            // Forget the failed rows: "Download" starts those files again.
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
        val status = VoiceModelLogic.status(b, facts)
        var percent = -1
        val progress = when (status) {
            ModelStatus.DOWNLOADING -> {
                val done = b.files.filter { it.name in v }.sumOf { it.bytes }
                VoiceModelLogic.downloadText(b, done, active).let { percent = it.second; it.first }
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
        return Snap(b, status, progress, percent, failure)
    }

    // ------------------------------------------------------------------ download, install, delete

    /** Null when the download started (or goes on), else why not in plain words. */
    fun startDownload(ctx: Context, b: VoiceBundle): String? {
        val rem = remaining(ctx, b)
        // Files already downloaded (an install that failed to write) are not fetched again; other downloads under way
        // (Gemma, the other voice models) will still need their space.
        val staged = rem.filter { stagedComplete(ctx, b, it) }.map { it.name }.toSet()
        val pending = pendingBytes(ctx, except = b) + ModelStore.pendingBytes(ctx) + MtModelStore.pendingBytes(ctx)
        VoiceModelLogic.refuseDownload(b, snapshot(ctx, b).status, rem, freeBytes(ctx), staged, pending)?.let { return it }
        val dir = staging(ctx, b) ?: return "The phone's storage isn't available right now."
        dir.mkdirs()
        // Download again: a failed install may run again (once).
        prefs(ctx).edit().remove("bad_${b.id}").remove("fail_${b.id}").remove("installfail_${b.id}").apply()
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val ids = ids(ctx, b).toMutableMap()
        val rows = query(ctx, ids.values)
        return try {
            for (f in rem) {
                val id = ids[f.name]
                val row = id?.let { rows[it] }
                if (running(row)) continue
                if (row?.status == DownloadManager.STATUS_SUCCESSFUL && stagedComplete(ctx, b, f)) continue
                id?.let { dm.remove(it) }
                staged(ctx, b, f)?.delete()
                val req = DownloadManager.Request(Uri.parse(b.url(f)))
                    .setTitle("Robot: ${b.title.lowercase()}")
                    .setDescription(VoiceModelLogic.size(f.bytes))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(ctx, null, "$STAGING/${b.id}/${f.name}")
                ids[f.name] = dm.enqueue(req)
            }
            setIds(ctx, b, ids)
            RLog.i("voice_download", "bundle" to RLog.Code(b.id), "op" to RLog.Code("start"), "files" to rem.size)
            null
        } catch (t: Throwable) {
            setIds(ctx, b, ids)
            RLog.w("voice_download", t, "bundle" to RLog.Code(b.id))
            "The download couldn't start."
        }
    }

    fun cancelDownload(ctx: Context, b: VoiceBundle) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        for (id in ids(ctx, b).values) try { dm.remove(id) } catch (_: Exception) {}
        setIds(ctx, b, emptyMap())
        staging(ctx, b)?.deleteRecursively()
        RLog.i("voice_download", "bundle" to RLog.Code(b.id), "op" to RLog.Code("cancel"))
    }

    /**
     * Copies each downloaded file into app-private storage, checking its size and SHA-256 on the way (blocking:
     * seconds for 650 MB). True when the bundle is complete; the voice process is then told.
     */
    fun install(ctx: Context, b: VoiceBundle): Boolean {
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
                if (src == null || !src.isFile || src.length() != f.bytes) {
                    RLog.i("voice_install", "bundle" to RLog.Code(b.id), "ok" to false, "result" to RLog.Code("missing"))
                    return false
                }
                val dest = File(dir(ctx, b), f.name)
                val r = try {
                    FileInputStream(src).use { input -> installVerified(input, dest, f.bytes, f.sha256) { n -> installPercent = ((done + n) * 100 / total).toInt() } }
                } catch (_: Exception) { InstallResult.IO_ERROR }
                if (r != InstallResult.OK) {
                    if (r == InstallResult.IO_ERROR) {
                        // No automatic retry (the app would copy hundreds of MB every second): the person taps Download.
                        prefs(ctx).edit().putString("fail_${b.id}", "Robot couldn't copy the files into its storage (is the phone full?). " +
                            "Free some space, then tap Download to try again.").putBoolean("installfail_${b.id}", true).apply()
                    } else {
                        // The download is damaged: delete it so the next attempt fetches it again.
                        prefs(ctx).edit().putString("bad_${b.id}", f.name).apply()
                        ids.remove(f.name)?.let { try { dm.remove(it) } catch (_: Exception) {} }
                        src.delete()
                        setIds(ctx, b, ids)
                    }
                    RLog.i("voice_install", "bundle" to RLog.Code(b.id), "ok" to false, "result" to r)
                    return false
                }
                marks.removeAll { it.name == f.name }
                marks += InstalledFile(f.name, f.bytes, dest.lastModified(), f.sha256)
                writeAtomic(markFile(ctx, b), VoiceMark.encode(marks))
                // DownloadManager deletes its copy with the row.
                ids.remove(f.name)?.let { try { dm.remove(it) } catch (_: Exception) {} }
                src.delete()
                setIds(ctx, b, ids)
                done += f.bytes
            }
            staging(ctx, b)?.deleteRecursively()
            prefs(ctx).edit().remove("bad_${b.id}").remove("fail_${b.id}").remove("installfail_${b.id}").apply()
            val ok = verified(ctx, b)
            RLog.i("voice_install", "bundle" to RLog.Code(b.id), "ok" to ok)
            if (ok) Cmd.send(ctx, Cmd.VOICE_MODELS)
            return ok
        } catch (t: Throwable) {
            RLog.w("voice_install", t, "bundle" to RLog.Code(b.id))
            prefs(ctx).edit().putString("fail_${b.id}", "Installing failed. Tap Download to try again.").putBoolean("installfail_${b.id}", true).apply()
            return false
        } finally {
            installing = null
        }
    }

    fun delete(ctx: Context, b: VoiceBundle) {
        cancelDownload(ctx, b)
        prefs(ctx).edit().remove("bad_${b.id}").remove("fail_${b.id}").remove("installfail_${b.id}").apply()
        dir(ctx, b).deleteRecursively()
        RLog.i("voice_delete", "bundle" to RLog.Code(b.id))
        Cmd.send(ctx, Cmd.VOICE_MODELS)
    }

    /** Robot's download folder for the voice models, under app-specific external storage. */
    private const val STAGING = "voice-download"
}
