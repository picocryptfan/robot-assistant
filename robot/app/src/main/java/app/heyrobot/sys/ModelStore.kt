package app.heyrobot.sys

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import app.heyrobot.core.ModelFacts
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelSpec
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

// Main process (download, import, verify, delete); the voice process only reads [verifiedPath]. Adapted
// from the probe's ModelStore: DownloadManager into getExternalFilesDir (resumes by itself after network
// loss), the free-space check, an import of a file the person picked (ACTION_OPEN_DOCUMENT) through a
// .part file, and the SHA-256 check that must pass before the model is ever loaded.

object ModelStore {
    private const val PREFS = "robot_model"

    fun dir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "models")
    fun file(ctx: Context) = File(dir(ctx), ModelSpec.FILE)
    private fun part(ctx: Context) = File(dir(ctx), "${ModelSpec.FILE}.part")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun fingerprint(f: File) = "${f.length()}|${f.lastModified()}"
    /** A file, not a preference, so the voice process can trust it too. */
    private fun verifiedMark(ctx: Context) = File(Paths.dir(ctx), "model_verified.txt")

    /** The model's path if it is complete and its SHA-256 checked, else null. Any process. */
    fun verifiedPath(ctx: Context): String? {
        val f = file(ctx)
        val mark = readOrNull(verifiedMark(ctx))?.trim() ?: return null
        return if (f.isFile && f.length() == ModelSpec.BYTES && mark == "${ModelSpec.SHA256}|${fingerprint(f)}") f.absolutePath else null
    }

    /** Process-local work in progress (the main process runs both). */
    @Volatile var importText: String? = null; private set
    @Volatile var verifyPercent: Int? = null; private set

    fun facts(ctx: Context): ModelFacts {
        val f = file(ctx)
        return ModelFacts(
            fileBytes = if (f.isFile) f.length() else 0,
            verifiedMark = verifiedPath(ctx) != null,
            badMark = f.isFile && prefs(ctx).getString("bad", null) == fingerprint(f),
            downloading = downloadId(ctx) != null,
            importing = importText != null,
            verifying = verifyPercent != null,
        )
    }

    fun status(ctx: Context): ModelStatus = ModelLogic.status(facts(ctx))

    fun freeBytes(ctx: Context): Long = try { StatFs(dir(ctx).apply { mkdirs() }.path).availableBytes } catch (_: Exception) { -1 }

    // ------------------------------------------------------------------ download

    /** Null when the download started, else why not (plain words). */
    fun startDownload(ctx: Context): String? {
        val existing = listOf(file(ctx), part(ctx)).filter { it.isFile }.sumOf { it.length() }
        // Voice-model downloads under way still need their space (DownloadManager reserves none).
        ModelLogic.refuseDownload(status(ctx), freeBytes(ctx), existing, VoiceModelStore.pendingBytes(ctx) + MtModelStore.pendingBytes(ctx))?.let { return it }
        file(ctx).delete()
        part(ctx).delete()
        verifiedMark(ctx).delete()
        prefs(ctx).edit().remove("bad").apply()
        val req = DownloadManager.Request(Uri.parse(ModelSpec.URL))
            .setTitle(ModelSpec.NAME)
            .setDescription("Robot's AI model (3.1 GB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, ModelSpec.FILE)
        return try {
            val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            prefs(ctx).edit().putLong("download_id", id).apply()
            RLog.i("model_download", "op" to RLog.Code("start"))
            null
        } catch (t: Throwable) {
            RLog.w("model_download", t)
            "The download couldn't start."
        }
    }

    /** What Gemma's download under way will still write (0 when none runs), for the voice models' space check. */
    fun pendingBytes(ctx: Context): Long {
        val id = prefs(ctx).getLong("download_id", -1).takeIf { it >= 0 } ?: return 0
        val q = query(ctx, id) ?: return 0
        val flying = q.status == DownloadManager.STATUS_PENDING || q.status == DownloadManager.STATUS_RUNNING || q.status == DownloadManager.STATUS_PAUSED
        return if (flying) (ModelSpec.BYTES - q.soFar.coerceAtLeast(0)).coerceAtLeast(0) else 0
    }

    /** The running (pending, running or paused) download's id, or null. */
    fun downloadId(ctx: Context): Long? {
        val id = prefs(ctx).getLong("download_id", -1).takeIf { it >= 0 } ?: return null
        val st = query(ctx, id)?.status
        return if (st == null || st == DownloadManager.STATUS_SUCCESSFUL || st == DownloadManager.STATUS_FAILED) null else id
    }

    data class Dl(val status: Int, val soFar: Long, val reason: Int)

    private fun query(ctx: Context, id: Long): Dl? = try {
        ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) null else Dl(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
        }
    } catch (_: Exception) { null }

    /** Plain-language download line plus percent (-1 unknown), or null when there is no download to report. Settles a finished one. */
    fun downloadProgress(ctx: Context): Pair<String, Int>? {
        val id = prefs(ctx).getLong("download_id", -1).takeIf { it >= 0 } ?: return null
        val q = query(ctx, id)
        return when (q?.status) {
            null -> { prefs(ctx).edit().remove("download_id").apply(); null }
            DownloadManager.STATUS_SUCCESSFUL -> { prefs(ctx).edit().remove("download_id").apply(); null }
            DownloadManager.STATUS_FAILED -> {
                prefs(ctx).edit().remove("download_id").putString("last_failure", ModelLogic.downloadText(q.status, q.reason, q.soFar)).apply()
                RLog.i("model_download", "op" to RLog.Code("failed"), "reason" to q.reason)
                null
            }
            else -> ModelLogic.downloadText(q.status, q.reason, q.soFar) to if (q.status == DownloadManager.STATUS_RUNNING) ModelLogic.percent(q.soFar) else -1
        }
    }

    /** The last failed download's explanation (shown until the next attempt). */
    fun lastFailure(ctx: Context): String? = prefs(ctx).getString("last_failure", null)

    fun cancelDownload(ctx: Context) {
        val id = prefs(ctx).getLong("download_id", -1).takeIf { it >= 0 } ?: return
        try { ctx.getSystemService(DownloadManager::class.java).remove(id) } catch (_: Exception) {}
        prefs(ctx).edit().remove("download_id").apply()
    }

    // ------------------------------------------------------------------ import

    fun docSize(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    /** Null when an import may start, else why not. */
    fun refuseImport(ctx: Context, uri: Uri): String? {
        val reclaim = listOf(file(ctx), part(ctx)).filter { it.isFile }.sumOf { it.length() }
        return ModelLogic.refuseImport(status(ctx), docSize(ctx, uri), freeBytes(ctx), reclaim, VoiceModelStore.pendingBytes(ctx) + MtModelStore.pendingBytes(ctx))
    }

    /**
     * Copies the picked file to where the download puts the model, through a .part file renamed when complete;
     * a download in progress is cancelled first. Blocking (minutes). Null on success (the file is then
     * UNVERIFIED and gets the usual SHA-256 check), else why not.
     */
    fun import(ctx: Context, uri: Uri): String? {
        refuseImport(ctx, uri)?.let { return it }
        importText = "Starting"
        val part = part(ctx)
        try {
            cancelDownload(ctx)
            file(ctx).delete()
            verifiedMark(ctx).delete()
            prefs(ctx).edit().remove("bad").remove("last_failure").apply()
            var done = 0L
            (ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()).use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(4 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > ModelSpec.BYTES) break
                        importText = "${ModelLogic.gb(done)} of ${ModelLogic.gb(ModelSpec.BYTES)} (${ModelLogic.percent(done)}%)"
                    }
                    out.fd.sync()
                }
            }
            if (done != ModelSpec.BYTES) {
                part.delete()
                return "That file isn't the model: its size is wrong."
            }
            if (!part.renameTo(file(ctx))) {
                part.delete()
                return "Couldn't move the copied file into place."
            }
            RLog.i("model_import", "ok" to true)
            return null
        } catch (t: Throwable) {
            part.delete()
            RLog.w("model_import", t)
            return "The copy failed (${t.javaClass.simpleName})."
        } finally {
            importText = null
        }
    }

    // ------------------------------------------------------------------ verify, delete

    /** SHA-256 of the whole file (blocking, 10 to 20 s); writes the verified or the bad mark. */
    fun verify(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.isFile || f.length() != ModelSpec.BYTES || verifyPercent != null) return false
        verifyPercent = 0
        try {
            val fp = fingerprint(f)
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8 shl 20)
            var done = 0L
            FileInputStream(f).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    done += n
                    verifyPercent = ModelLogic.percent(done)
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            val ok = sha == ModelSpec.SHA256
            if (ok) {
                writeAtomic(verifiedMark(ctx), "$sha|$fp")
                prefs(ctx).edit().remove("bad").remove("last_failure").apply()
            } else {
                prefs(ctx).edit().putString("bad", fp).apply()
            }
            RLog.i("model_verify", "ok" to ok)
            return ok
        } catch (t: Throwable) {
            RLog.w("model_verify", t)
            return false
        } finally {
            verifyPercent = null
        }
    }

    fun delete(ctx: Context) {
        cancelDownload(ctx)
        prefs(ctx).edit().clear().apply()
        file(ctx).delete()
        part(ctx).delete()
        verifiedMark(ctx).delete()
        RLog.i("model_delete")
    }
}
