package app.heyrobot.sys

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import app.heyrobot.core.BrainLoaded
import app.heyrobot.core.BrainModel
import app.heyrobot.core.LiteRtLmCheck
import app.heyrobot.core.LoadPlan
import app.heyrobot.core.ModelAvailability
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFacts
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.OwnModel
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

// Main process (download, import, verify, delete); the voice process only reads [plan] and [verifiedPath]. Adapted
// from the probe's ModelStore: DownloadManager into getExternalFilesDir (resumes by itself after network loss), the
// free-space check, an import of a file you picked (ACTION_OPEN_DOCUMENT, no storage permission) through a .part file,
// and the SHA-256 check that must pass before a model is ever loaded. 0.4.1: one set of files per model of the catalogue
// (core BrainModel): its file, download, verified mark and CPU cache folder, Fast keeping 0.4.0's names; one download or
// copy at a time; and your own model file, checked by its contents (core LiteRtLmCheck) and recorded with its SHA-256.

object ModelStore {
    private const val PREFS = "robot_model"
    /** While copying a file whose size the picker didn't give, the free space is checked this often. */
    private const val SPACE_CHECK_EVERY = 256L shl 20

    fun dir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "models")
    fun file(ctx: Context, m: BrainModel) = File(dir(ctx), m.file)
    private fun part(ctx: Context, m: BrainModel) = File(dir(ctx), "${m.file}.part")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(m: BrainModel, k: String) = ModelFiles.prefKey(m, k)
    private fun fingerprint(f: File) = ModelFiles.fingerprint(f.length(), f.lastModified())
    /** A file, not a preference, so the voice process can trust it too. */
    private fun verifiedMark(ctx: Context, m: BrainModel) = File(Paths.dir(ctx), ModelFiles.verifiedMark(m))
    /** The CPU's weight cache for [m] (filesDir/litert_cache/<id>), deleted with the model. */
    fun cacheDir(ctx: Context, m: BrainModel): File = File(ctx.filesDir, ModelFiles.cacheDir(m, ModelBackend.CPU)!!)
    private fun ownRecord(ctx: Context) = File(Paths.dir(ctx), ModelFiles.OWN_RECORD)

    /** Your own model file's record (core OwnModel), whether or not it still matches the file. */
    fun own(ctx: Context): OwnModel? = OwnModel.fromJson(readOrNull(ownRecord(ctx)))

    /** [m]'s path if it is complete and checked (your own file: and runnable here), else null. Any process. */
    fun verifiedPath(ctx: Context, m: BrainModel): String? {
        val f = file(ctx, m)
        if (!f.isFile) return null
        if (m == BrainModel.OWN) {
            val r = own(ctx) ?: return null
            return if (r.fingerprint == fingerprint(f) && r.report.backend != null) f.absolutePath else null
        }
        val mark = readOrNull(verifiedMark(ctx, m))?.trim() ?: return null
        return if (f.length() == m.bytes && mark == "${m.sha256}|${fingerprint(f)}") f.absolutePath else null
    }

    /**
     * VOICE PROCESS, before each load: whether [m] can load now and, when it can, what :brain is asked for (the file,
     * where it runs, its context). Files only (the preferences belong to the main process).
     */
    fun plan(ctx: Context, m: BrainModel): Pair<ModelAvailability, LoadPlan?> {
        val f = file(ctx, m)
        if (m == BrainModel.OWN) {
            val fp = if (f.isFile && f.length() > 0) fingerprint(f) else null
            val r = own(ctx)
            val a = r?.availability(fp) ?: if (fp == null) ModelAvailability.NOT_DOWNLOADED else ModelAvailability.NOT_CHECKED
            val b = r?.backend
            return if (a == ModelAvailability.READY && b != null && fp != null) a to LoadPlan(m, f.absolutePath, b, m.contextTokens, fp) else a to null
        }
        val path = verifiedPath(ctx, m) ?: return (if (f.isFile && f.length() == m.bytes) ModelAvailability.NOT_CHECKED else ModelAvailability.NOT_DOWNLOADED) to null
        return ModelAvailability.READY to LoadPlan(m, path, m.backend, m.contextTokens, fingerprint(f))
    }

    /**
     * The model :brain has in memory now (its "loaded" file, while that process lives), or null. Unknown whether it
     * lives: assumed so, so a model in use is never deleted.
     */
    fun loadedNow(ctx: Context): BrainModel? {
        val l = BrainLoaded.decode(readOrNull(File(Paths.dir(ctx), ModelFiles.LOADED))) ?: return null
        val alive = try {
            ctx.getSystemService(ActivityManager::class.java).runningAppProcesses?.any { it.pid == l.pid && it.processName.endsWith(":brain") }
        } catch (_: Exception) { null }
        return if (alive == false) null else l.model
    }

    /** Process-local work in progress (the main process runs both); one model at a time. */
    @Volatile var importing: BrainModel? = null; private set
    @Volatile var importText: String? = null; private set
    @Volatile var verifying: BrainModel? = null; private set
    @Volatile var verifyPercent: Int? = null; private set

    fun facts(ctx: Context, m: BrainModel): ModelFacts {
        val f = file(ctx, m)
        val bad = f.isFile && if (m == BrainModel.OWN) own(ctx)?.let { it.fingerprint == fingerprint(f) && it.report.backend == null } == true
            else prefs(ctx).getString(key(m, "bad"), null) == fingerprint(f)
        return ModelFacts(
            fileBytes = if (f.isFile) f.length() else 0,
            verifiedMark = verifiedPath(ctx, m) != null,
            badMark = bad,
            downloading = m.builtIn && downloadId(ctx, m) != null,
            importing = importing == m,
            verifying = verifying == m,
        )
    }

    fun status(ctx: Context, m: BrainModel): ModelStatus = ModelLogic.status(m, facts(ctx, m))

    /** One model's card in Settings › Model. */
    data class Snap(
        val model: BrainModel,
        val status: ModelStatus,
        /** A progress line while downloading, copying or checking, else null. */
        val progress: String? = null,
        /** 0 to 100, or -1 when unknown. */
        val percent: Int = -1,
        /** Why the last download failed, in plain words (shown while nothing runs). */
        val failure: String? = null,
        val fileBytes: Long = 0,
        /** What its CPU cache holds on the phone now. */
        val cacheBytes: Long = 0,
    )

    /** [m]'s card; settles a finished download. */
    fun snapshot(ctx: Context, m: BrainModel): Snap {
        val dl = if (m.builtIn) downloadProgress(ctx, m) else null
        val f = file(ctx, m)
        return Snap(
            model = m,
            status = status(ctx, m),
            progress = when (m) {
                importing -> importText?.let { "Copying: $it" }
                verifying -> verifyPercent?.let { "Checking the file: $it%" }
                else -> dl?.first
            },
            percent = when (m) {
                importing -> -1
                verifying -> verifyPercent ?: -1
                else -> dl?.second ?: -1
            },
            failure = lastFailure(ctx, m),
            fileBytes = if (f.isFile) f.length() else 0,
            cacheBytes = if (m.backend == ModelBackend.CPU) cacheBytes(ctx, m) else 0,
        )
    }

    fun freeBytes(ctx: Context): Long = try { StatFs(dir(ctx).apply { mkdirs() }.path).availableBytes } catch (_: Exception) { -1 }

    /** The bytes [m]'s cache holds on the phone now. */
    fun cacheBytes(ctx: Context, m: BrainModel): Long = try { cacheDir(ctx, m).walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0 }

    /** The model whose download or copy is under way, if any (one at a time, so each space check holds). */
    fun busyWith(ctx: Context): BrainModel? = importing ?: BrainModel.entries.firstOrNull { it.builtIn && downloadId(ctx, it) != null }

    // ------------------------------------------------------------------ download

    /** Null when the download started, else why not (plain words). */
    fun startDownload(ctx: Context, m: BrainModel): String? {
        val url = m.url ?: return "This one has no download: import your file."
        val existing = listOf(file(ctx, m), part(ctx, m)).filter { it.isFile }.sumOf { it.length() } + cacheBytes(ctx, m)
        // Voice-model downloads under way still need their space (DownloadManager reserves none).
        ModelLogic.refuseDownload(m, status(ctx, m), freeBytes(ctx), existing, VoiceModelStore.pendingBytes(ctx) + pendingBytes(ctx, except = m), busyWith(ctx))
            ?.let { return it }
        file(ctx, m).delete()
        part(ctx, m).delete()
        verifiedMark(ctx, m).delete()
        cacheDir(ctx, m).deleteRecursively()
        prefs(ctx).edit().remove(key(m, "bad")).apply()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(m.modelName)
            .setDescription("Robot's AI model (${ModelLogic.gb(m.bytes)})")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, m.file)
        return try {
            val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            prefs(ctx).edit().putLong(key(m, "download_id"), id).remove(key(m, "last_failure")).apply()
            RLog.i("model_download", "op" to RLog.Code("start"), "model" to RLog.Code(m.id))
            null
        } catch (t: Throwable) {
            RLog.w("model_download", t)
            "The download couldn't start."
        }
    }

    /** What the model downloads under way will still write (0 when none runs), for the other downloads' space checks. */
    fun pendingBytes(ctx: Context, except: BrainModel? = null): Long = BrainModel.entries.filter { it.builtIn && it != except }.sumOf { m ->
        val id = prefs(ctx).getLong(key(m, "download_id"), -1).takeIf { it >= 0 } ?: return@sumOf 0L
        val q = query(ctx, id) ?: return@sumOf 0L
        val flying = q.status == DownloadManager.STATUS_PENDING || q.status == DownloadManager.STATUS_RUNNING || q.status == DownloadManager.STATUS_PAUSED
        if (flying) (m.bytes - q.soFar.coerceAtLeast(0)).coerceAtLeast(0) else 0L
    }

    /** The running (pending, running or paused) download's id, or null. */
    fun downloadId(ctx: Context, m: BrainModel): Long? {
        val id = prefs(ctx).getLong(key(m, "download_id"), -1).takeIf { it >= 0 } ?: return null
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
    fun downloadProgress(ctx: Context, m: BrainModel): Pair<String, Int>? {
        val k = key(m, "download_id")
        val id = prefs(ctx).getLong(k, -1).takeIf { it >= 0 } ?: return null
        val q = query(ctx, id)
        return when (q?.status) {
            null -> { prefs(ctx).edit().remove(k).apply(); null }
            DownloadManager.STATUS_SUCCESSFUL -> { prefs(ctx).edit().remove(k).apply(); null }
            DownloadManager.STATUS_FAILED -> {
                prefs(ctx).edit().remove(k).putString(key(m, "last_failure"), ModelLogic.downloadText(m, q.status, q.reason, q.soFar)).apply()
                RLog.i("model_download", "op" to RLog.Code("failed"), "model" to RLog.Code(m.id), "reason" to q.reason)
                null
            }
            else -> ModelLogic.downloadText(m, q.status, q.reason, q.soFar) to if (q.status == DownloadManager.STATUS_RUNNING) ModelLogic.percent(q.soFar, m.bytes) else -1
        }
    }

    /** The last failed download's or copy's explanation (shown until the next attempt). */
    fun lastFailure(ctx: Context, m: BrainModel): String? = prefs(ctx).getString(key(m, "last_failure"), null)

    fun cancelDownload(ctx: Context, m: BrainModel) {
        val k = key(m, "download_id")
        val id = prefs(ctx).getLong(k, -1).takeIf { it >= 0 } ?: return
        try { ctx.getSystemService(DownloadManager::class.java).remove(id) } catch (_: Exception) {}
        prefs(ctx).edit().remove(k).apply()
    }

    // ------------------------------------------------------------------ import

    fun docSize(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    /** The picked file's name, for the screen only (never a path, never logged, never a reason to trust it). */
    private fun docName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) { null }
        // Shown on the TPU's warning card: no control or invisible formatting characters (a right-to-left override could
        // make "…ldom.litertlm" read as another name).
        ?.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }?.take(120)?.ifBlank { null } ?: "your file"

    /** Null when an import into [m] may start, else why not. */
    fun refuseImport(ctx: Context, m: BrainModel, uri: Uri): String? {
        if (loadedNow(ctx) == m) return inUse(m)
        val reclaim = listOf(file(ctx, m), part(ctx, m)).filter { it.isFile }.sumOf { it.length() } + cacheBytes(ctx, m)
        return ModelLogic.refuseImport(m, status(ctx, m), docSize(ctx, uri), freeBytes(ctx), reclaim,
            VoiceModelStore.pendingBytes(ctx) + pendingBytes(ctx, except = m), busyWith(ctx))
    }

    /**
     * Copies the picked file to [m]'s place through a .part file renamed when complete; a download of [m] in progress
     * is cancelled first. Blocking (minutes). Null on success (the file is then UNVERIFIED and gets the usual check:
     * [verify]), else why not.
     */
    fun import(ctx: Context, m: BrainModel, uri: Uri): String? {
        refuseImport(ctx, m, uri)?.let { return it }
        importing = m
        importText = "Starting"
        val part = part(ctx, m)
        val total = if (m.builtIn) m.bytes else docSize(ctx, uri)
        try {
            cancelDownload(ctx, m)
            file(ctx, m).delete()
            verifiedMark(ctx, m).delete()
            cacheDir(ctx, m).deleteRecursively()
            prefs(ctx).edit().remove(key(m, "bad")).remove(key(m, "last_failure")).apply()
            if (m == BrainModel.OWN) {
                ownRecord(ctx).delete()
                prefs(ctx).edit().putString(OWN_NAME, docName(ctx, uri)).apply()
            }
            var done = 0L
            var nextCheck = SPACE_CHECK_EVERY
            (ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()).use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(4 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (m.builtIn && done > m.bytes) break
                        if (done >= nextCheck) {
                            nextCheck += SPACE_CHECK_EVERY
                            val free = freeBytes(ctx)
                            if (free in 0 until BrainModel.HEADROOM / 2) {
                                part.delete()
                                return "The phone ran out of free space while copying (${ModelLogic.gb(done)} copied)."
                            }
                        }
                        importText = if (total > 0) "${ModelLogic.gb(done)} of ${ModelLogic.gb(total)} (${ModelLogic.percent(done, total)}%)" else ModelLogic.gb(done)
                    }
                    out.fd.sync()
                }
            }
            if (m.builtIn && done != m.bytes) {
                part.delete()
                return "That file isn't the model: its size is wrong."
            }
            if (done < ModelLogic.OWN_MIN_BYTES) {
                part.delete()
                return "That file is too small to be a model."
            }
            if (!part.renameTo(file(ctx, m))) {
                part.delete()
                return "Couldn't move the copied file into place."
            }
            RLog.i("model_import", "ok" to true, "model" to RLog.Code(m.id))
            return null
        } catch (t: Throwable) {
            part.delete()
            RLog.w("model_import", t)
            return "The copy failed (${t.javaClass.simpleName})."
        } finally {
            importText = null
            importing = null
        }
    }

    // ------------------------------------------------------------------ verify, delete

    /**
     * SHA-256 of the whole file (blocking, 10 to 30 s); writes the verified or the bad mark. Your own file: its SHA-256
     * and size, then the content check, kept in its record (true when Robot can run it here).
     */
    fun verify(ctx: Context, m: BrainModel): Boolean {
        val f = file(ctx, m)
        if (!f.isFile || !ModelLogic.complete(m, f.length()) || verifying != null) return false
        verifying = m
        verifyPercent = 0
        try {
            val fp = fingerprint(f)
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8 shl 20)
            var done = 0L
            val total = f.length()
            FileInputStream(f).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    done += n
                    verifyPercent = ModelLogic.percent(done, total)
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (m == BrainModel.OWN) {
                val report = LiteRtLmCheck.check(f)
                val before = own(ctx)
                // A TPU allowed for this very file (same SHA-256) stays allowed; anything else asks again.
                val allowed = before != null && before.sha256 == sha && before.tpuAllowed && report.tensorG5Build
                val name = prefs(ctx).getString(OWN_NAME, null) ?: before?.name ?: "your file"
                writeAtomic(ownRecord(ctx), OwnModel(name, total, sha, fp, report, allowed, System.currentTimeMillis()).toJson().toString())
                val ok = report.backend != null
                RLog.i("model_verify", "ok" to ok, "model" to RLog.Code(m.id), "tpu" to report.tensorG5Build, "gemma4" to report.gemma4,
                    "tools" to report.toolTokens, "readable" to report.readable)
                return ok
            }
            val ok = sha == m.sha256
            if (ok) {
                writeAtomic(verifiedMark(ctx, m), "$sha|$fp")
                prefs(ctx).edit().remove(key(m, "bad")).remove(key(m, "last_failure")).apply()
            } else {
                prefs(ctx).edit().putString(key(m, "bad"), fp).apply()
            }
            RLog.i("model_verify", "ok" to ok, "model" to RLog.Code(m.id))
            return ok
        } catch (t: Throwable) {
            RLog.w("model_verify", t)
            return false
        } finally {
            verifyPercent = null
            verifying = null
        }
    }

    /**
     * Your own file is a proven Tensor G5 build: you allowed the TPU for it (its warning card), or took that back. Allowed
     * only for the very file whose card you read ([sha256], as the card showed it), unchanged since its check.
     */
    fun allowOwnTpu(ctx: Context, allow: Boolean, sha256: String? = null): Boolean {
        val r = own(ctx) ?: return false
        if (allow && (!r.report.tensorG5Build || r.sha256 != sha256 || r.fingerprint != fingerprint(file(ctx, BrainModel.OWN)))) return false
        writeAtomic(ownRecord(ctx), r.copy(tpuAllowed = allow).toJson().toString())
        RLog.i("model_own_tpu", "allowed" to allow)
        return true
    }

    private fun inUse(m: BrainModel) = "Robot has “${m.title}” loaded right now. Choose another model first: Robot lets go of it right away, then try again."

    /** Null when [m]'s file, cache, marks and download are gone, else why not: never the model in memory. */
    fun delete(ctx: Context, m: BrainModel): String? {
        if (loadedNow(ctx) == m) return inUse(m)
        cancelDownload(ctx, m)
        prefs(ctx).edit().remove(key(m, "download_id")).remove(key(m, "bad")).remove(key(m, "last_failure")).apply()
        file(ctx, m).delete()
        part(ctx, m).delete()
        verifiedMark(ctx, m).delete()
        cacheDir(ctx, m).deleteRecursively()
        // Anything LiteRT-LM may have left beside the file (its name first: no other model's file starts like it).
        dir(ctx).listFiles { f -> f.name.startsWith(m.file) }?.forEach { it.deleteRecursively() }
        if (m == BrainModel.OWN) {
            ownRecord(ctx).delete()
            prefs(ctx).edit().remove(OWN_NAME).apply()
        }
        RLog.i("model_delete", "model" to RLog.Code(m.id))
        return null
    }

    private const val OWN_NAME = "own_name"
}
