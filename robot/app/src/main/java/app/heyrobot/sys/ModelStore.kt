// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.BuildConfig
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import app.heyrobot.core.BrainLoaded
import app.heyrobot.core.BrainModel
import app.heyrobot.core.GgufCheck
import app.heyrobot.core.LiteRtLmCheck
import app.heyrobot.core.LoadPlan
import app.heyrobot.core.ModelAvailability
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFacts
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelMove
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.OwnModel
import app.heyrobot.core.Phone
import app.heyrobot.core.DownloadPolicy
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

// Main process (download, import, verify, delete); the voice process only reads [plan] and [verifiedPath]. Adapted
// from the probe's ModelStore: DownloadManager into getExternalFilesDir (resumes by itself after network loss), the
// free-space check, an import of a file you picked (ACTION_OPEN_DOCUMENT, no storage permission) through a .part file,
// and the SHA-256 check that must pass before a model is ever loaded. 0.4.1: one set of files per model of the catalogue
// (core BrainModel): its file, download, verified mark and CPU cache folder, Fast keeping 0.4.0's names; one download or
// copy at a time; and your own model file, checked by its contents (core LiteRtLmCheck) and recorded with its SHA-256.
// 0.4.5: your own file may be a GGUF file (core GgufCheck), kept as own_model.gguf: its first bytes decide, never its name.
// 1.0.1 (core ModelMove): models live in internal storage (filesDir/models), which only Robot can write. DownloadManager
// can only write to the external files folder, so a finished download is copied in by its check, hashed as it is written;
// your own file is imported straight in; a 1.0 model still outside moves in once ([moveIn]) when there's room, and until
// then :brain hashes it in full before each load. The outside copy goes only once the inside one is complete and checked.

object ModelStore {
    private const val PREFS = "robot_model"
    /** While copying a file whose size the picker didn't give, the free space is checked this often. */
    private const val SPACE_CHECK_EVERY = 256L shl 20

    /** Where models live (1.0.1): internal storage, which only Robot can write. */
    fun dir(ctx: Context): File = File(ctx.filesDir, "models")
    /** Where 1.0 kept them, and where DownloadManager still writes (it can't write to internal storage); null without one. */
    private fun outsideDir(ctx: Context): File? = ctx.getExternalFilesDir(null)
    private fun dirs(ctx: Context): List<File> = listOfNotNull(dir(ctx), outsideDir(ctx)).distinctBy { it.absolutePath }
    /** [f] isn't in internal storage: not trusted without its hash (core ModelMove). */
    fun outside(ctx: Context, f: File): Boolean = ModelMove.hashBeforeLoad(f, ctx.filesDir)
    /** [m]'s file: the one inside when there is one, else 1.0's (or a finished download) outside, else where it will go. */
    fun file(ctx: Context, m: BrainModel): File = if (m == BrainModel.OWN) ownFile(ctx) else candidates(ctx, m.file).firstOrNull { it.isFile } ?: File(dir(ctx), m.file)
    /** Inside first. */
    private fun candidates(ctx: Context, name: String): List<File> = dirs(ctx).map { File(it, name) }
    /** Your own file: own_model.gguf when the file you imported is a GGUF file, else 0.4.1's own_model.litertlm; inside first. */
    private fun ownFile(ctx: Context): File = dirs(ctx).flatMap { d -> listOf(File(d, ModelFiles.OWN_GGUF), File(d, BrainModel.OWN.file)) }.firstOrNull { it.isFile }
        ?: File(dir(ctx), BrainModel.OWN.file)
    /** Every place your own file can be (one at a time). */
    private fun ownFiles(ctx: Context) = dirs(ctx).flatMap { d -> ModelFormat.entries.map { File(d, ModelFiles.ownFile(it)) } }
    /** Every copy of [m] there may be, inside and out. */
    private fun allFiles(ctx: Context, m: BrainModel): List<File> = if (m == BrainModel.OWN) ownFiles(ctx) else candidates(ctx, m.file)
    /** An import's copy in progress (inside). */
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

    /**
     * This build of Robot has its llama.cpp library (tools/build_llama.sh), so a GGUF file can run. Any process; only
     * :brain ever loads the library.
     */
    fun ggufEngine(ctx: Context): Boolean = try { File(ctx.applicationInfo.nativeLibraryDir, "librobot_llama.so").isFile } catch (_: Exception) { false }

    @Volatile private var phoneHere: Phone? = null

    /**
     * This phone (0.4.7, core Phone): Android's Build fields and its memory, read once per process. Which Fast it gets, and
     * whether Robot uses its TPU or its GPU, follow from it (core ModelCatalogue). Any process.
     */
    fun phone(ctx: Context): Phone = phoneHere ?: Phone(
        android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.MODEL.orEmpty(), android.os.Build.SOC_MANUFACTURER.orEmpty(),
        android.os.Build.SOC_MODEL.orEmpty(), totalRam(ctx),
    ).also { phoneHere = it }

    /** The phone's memory (for a GGUF file's memory check), 0 when unknown. */
    fun totalRam(ctx: Context): Long = try {
        ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    } catch (_: Exception) { 0L }

    /** The kind of model file [f] is, by its first bytes. */
    private fun formatOf(f: File): ModelFormat? = try {
        FileInputStream(f).use { input -> ByteArray(8).let { b -> val n = input.read(b); ModelFormat.sniff(b.copyOf(maxOf(n, 0))) } }
    } catch (_: Exception) { null }

    /** [m]'s path if it is complete and checked (your own file: and runnable here), else null. Any process. */
    fun verifiedPath(ctx: Context, m: BrainModel): String? {
        val f = file(ctx, m)
        if (!f.isFile) return null
        if (m == BrainModel.OWN) {
            val r = own(ctx) ?: return null
            return if (r.fingerprint == fingerprint(f) && r.runsOn != null && f.name == ModelFiles.ownFile(r.format)) f.absolutePath else null
        }
        val mark = readOrNull(verifiedMark(ctx, m))?.trim() ?: return null
        // The copy the mark is for: inside, or 1.0's outside until it moves (while a move finishes, the mark may still be the
        // outside one's for a moment, with the inside one already there).
        return candidates(ctx, m.file).firstOrNull { it.isFile && it.length() == m.bytes && mark == "${m.sha256}|${fingerprint(it)}" }?.absolutePath
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
            // A GGUF file can run only with this build's llama.cpp library; a record of the other kind of file is checked again.
            val a = when {
                r != null && fp != null && f.name != ModelFiles.ownFile(r.format) -> ModelAvailability.NOT_CHECKED
                // 0.4.7: a TPU build on a phone without that TPU (a Pixel 9a) can run nowhere here; 0.8: nor a Tensor G5
                // build on a Tensor G6, or the reverse (its stamp must name this phone's chip).
                r != null && r.tensorBuild != null && !r.report.runsOnTpuOf(phone(ctx)) -> ModelAvailability.UNUSABLE
                else -> r?.availability(fp, ggufEngine(ctx)) ?: if (fp == null) ModelAvailability.NOT_DOWNLOADED else ModelAvailability.NOT_CHECKED
            }
            val b = r?.backend
            // Its context is the file's own when shorter than 4,096 (0.4.3, chat only), a GGUF file's too; :brain checks the
            // header again. A GGUF file never sees photos.
            return if (a == ModelAvailability.READY && r != null && b != null && fp != null) {
                a to LoadPlan(m, f.absolutePath, b, r.contextTokens, fp, r.vision, r.format)
            } else a to null
        }
        val path = verifiedPath(ctx, m) ?: return (if (f.isFile && f.length() == m.bytes) ModelAvailability.NOT_CHECKED else ModelAvailability.NOT_DOWNLOADED) to null
        return ModelAvailability.READY to LoadPlan(m, path, m.backend, m.contextTokens, fingerprint(File(path)))
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
    /** 1.0.1: the model moving into internal storage ([moveIn]), and how far. */
    @Volatile var moving: BrainModel? = null; private set
    @Volatile var movePercent: Int? = null; private set
    /** A delete, a download or an import of the model moving stops the move first ([stopMove]). */
    @Volatile private var moveStop = false

    fun facts(ctx: Context, m: BrainModel): ModelFacts {
        val f = file(ctx, m)
        val bad = f.isFile && if (m == BrainModel.OWN) own(ctx)?.let { it.fingerprint == fingerprint(f) && it.runsOn == null } == true
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
        /** 1.0.1: a copy of it is still outside internal storage (1.0's place, or a finished download): [moveIn] tidies it. */
        val outside: Boolean = false,
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
                moving -> movePercent?.let { "Moving into Robot's own storage: $it%" }
                else -> dl?.first
            },
            percent = when (m) {
                importing -> -1
                verifying -> verifyPercent ?: -1
                moving -> movePercent ?: -1
                else -> dl?.second ?: -1
            },
            failure = lastFailure(ctx, m),
            fileBytes = if (f.isFile) f.length() else 0,
            cacheBytes = if (m.backend == ModelBackend.CPU) cacheBytes(ctx, m) else 0,
            outside = outsideDir(ctx) != null && allFiles(ctx, m).any { it.isFile && outside(ctx, it) },
        )
    }

    fun freeBytes(ctx: Context): Long = try { StatFs(dir(ctx).apply { mkdirs() }.path).availableBytes } catch (_: Exception) { -1 }

    /** A move of any model stops (its part deleted) before [m]'s files change; waits for it, briefly. */
    private fun stopMove() {
        if (moving == null) return
        moveStop = true
        val until = System.currentTimeMillis() + 10_000
        while (moving != null && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    /** The bytes [m]'s cache holds on the phone now. */
    fun cacheBytes(ctx: Context, m: BrainModel): Long = try { cacheDir(ctx, m).walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0 }

    /** The model whose download or copy is under way, if any (one at a time, so each space check holds). */
    fun busyWith(ctx: Context): BrainModel? = importing ?: BrainModel.entries.firstOrNull { it.builtIn && downloadId(ctx, it) != null }

    // ------------------------------------------------------------------ download

    /**
     * Null when the download started, else why not (plain words). [anyNetwork] false: the download manager holds it until
     * the phone is on Wi-Fi or another unmetered network (1.0, core DownloadPolicy: Robot can't see the network itself).
     */
    fun startDownload(ctx: Context, m: BrainModel, anyNetwork: Boolean = true): String? {
        val url = m.url ?: return "This one has no download: import your file."
        val existing = (allFiles(ctx, m) + part(ctx, m)).filter { it.isFile }.sumOf { it.length() } + cacheBytes(ctx, m)
        // Voice-model and fast-engine downloads under way still need their space (DownloadManager reserves none).
        val pending = VoiceModelStore.pendingBytes(ctx) + pendingBytes(ctx, except = m) + MtModelStore.pendingBytes(ctx)
        ModelLogic.refuseDownload(m, status(ctx, m), freeBytes(ctx), existing, pending, busyWith(ctx))?.let { return it }
        stopMove()
        allFiles(ctx, m).forEach { it.delete() }
        part(ctx, m).delete()
        verifiedMark(ctx, m).delete()
        cacheDir(ctx, m).deleteRecursively()
        prefs(ctx).edit().remove(key(m, "bad")).apply()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(m.modelName)
            .setDescription("Robot's AI model (${ModelLogic.gb(m.bytes)})")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, m.file)
            .addRequestHeader("User-Agent", DownloadPolicy.userAgent(BuildConfig.VERSION_NAME))
            .setAllowedOverMetered(anyNetwork)
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

    /** The picked file starts like a GGUF file (it needs no room for a cache then); its first bytes only. */
    private fun docIsGguf(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { input -> ByteArray(8).let { b -> val n = input.read(b); ModelFormat.sniff(b.copyOf(maxOf(n, 0))) == ModelFormat.GGUF } } == true
    } catch (_: Exception) { false }

    /** Null when an import into [m] may start, else why not. */
    fun refuseImport(ctx: Context, m: BrainModel, uri: Uri): String? {
        if (loadedNow(ctx) == m) return inUse(m)
        val reclaim = (listOf(part(ctx, m)) + allFiles(ctx, m)).filter { it.isFile }.sumOf { it.length() } +
            cacheBytes(ctx, m)
        return ModelLogic.refuseImport(m, status(ctx, m), docSize(ctx, uri), freeBytes(ctx), reclaim,
            VoiceModelStore.pendingBytes(ctx) + pendingBytes(ctx, except = m) + MtModelStore.pendingBytes(ctx), busyWith(ctx),
            gguf = m == BrainModel.OWN && docIsGguf(ctx, uri))
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
            stopMove()
            // 1.0.1: straight into internal storage; every older copy, inside or out, goes.
            allFiles(ctx, m).forEach { it.delete() }
            verifiedMark(ctx, m).delete()
            cacheDir(ctx, m).deleteRecursively()
            prefs(ctx).edit().remove(key(m, "bad")).remove(key(m, "last_failure")).apply()
            if (m == BrainModel.OWN) {
                ownRecord(ctx).delete()
                prefs(ctx).edit().putString(OWN_NAME, docName(ctx, uri)).apply()
            }
            dir(ctx).mkdirs()
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
            // Your own file goes where its kind of file goes (its first bytes say which, never its name).
            val target = if (m == BrainModel.OWN) File(dir(ctx), ModelFiles.ownFile(formatOf(part) ?: ModelFormat.LITERT_LM)) else File(dir(ctx), m.file)
            if (!part.renameTo(target)) {
                part.delete()
                return "Couldn't move the copied file into place."
            }
            RLog.i("model_import", "ok" to true, "model" to RLog.Code(m.id), "format" to (if (m == BrainModel.OWN) formatOf(target) else ModelFormat.LITERT_LM))
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
     * and size, then the content check, kept in its record (true when Robot can run it here). 1.0.1: a file outside
     * internal storage (a finished download) is copied in as it is hashed when there's room, and the outside copy goes
     * once the inside one is checked; without room it is checked where it is (then :brain hashes it before each load).
     */
    fun verify(ctx: Context, m: BrainModel): Boolean {
        val src = file(ctx, m)
        if (!src.isFile || !ModelLogic.complete(m, src.length()) || verifying != null || moving != null) return false
        verifying = m
        verifyPercent = 0
        try {
            val total = src.length()
            var f = src
            var sha: String? = null
            if (outside(ctx, src) && loadedNow(ctx) != m) {
                val r = ModelMove.copyIn(src, File(dir(ctx), src.name), if (m.builtIn) m.sha256 else null, { freeBytes(ctx) }, pendingAll(ctx)) { done, all ->
                    verifyPercent = ModelLogic.percent(done, all)
                }
                RLog.i("model_move", "op" to RLog.Code("verify"), "model" to RLog.Code(m.id), "result" to RLog.Code(r.javaClass.simpleName.lowercase()))
                when (r) {
                    is ModelMove.Result.Moved -> { f = r.file; sha = r.sha256 }
                    // The download isn't the model: marked bad below, from its own hash.
                    is ModelMove.Result.Mismatch -> sha = r.sha256
                    else -> {}
                }
            }
            val fp = fingerprint(f)
            if (sha == null) {
                verifyPercent = 0
                sha = FileInputStream(f).use { input -> ModelMove.sha256(input) { done -> verifyPercent = ModelLogic.percent(done, total) } }
            }
            if (m == BrainModel.OWN) {
                // By its contents: a GGUF file gets the GGUF check (llama.cpp runs it), anything else the LiteRT-LM one.
                val format = formatOf(f)
                val gguf = if (format == ModelFormat.GGUF) GgufCheck.check(f, totalRam(ctx)) else null
                val report = when {
                    gguf != null -> OwnModel.NOT_LITERT_LM
                    format == null -> NOT_A_MODEL
                    // 1.0 (native-01): your own .litertlm's container is checked strictly before LiteRT-LM ever reads it.
                    else -> LiteRtLmCheck.check(f, strict = true)
                }
                val before = own(ctx)
                // A TPU allowed for this very file (same SHA-256) stays allowed; anything else asks again.
                val allowed = gguf == null && before != null && before.sha256 == sha && before.tpuAllowed && report.tensorBuild != null
                val name = prefs(ctx).getString(OWN_NAME, null) ?: before?.name ?: "your file"
                val record = OwnModel(name, total, sha, fp, report, allowed, System.currentTimeMillis(), gguf)
                writeAtomic(ownRecord(ctx), record.toJson().toString())
                // The record names the inside copy now (the same bytes): the outside one goes.
                if (f != src) src.delete()
                val ok = record.runsOn != null
                RLog.i("model_verify", "ok" to ok, "model" to RLog.Code(m.id), "format" to record.format, "tpu" to (record.tensorBuild != null),
                    "gemma4" to report.gemma4, "tools" to report.toolTokens, "readable" to (gguf?.readable ?: report.readable),
                    "fileType" to (gguf?.fileType ?: -1), "thinks" to (gguf?.thinks ?: false))
                return ok
            }
            val ok = sha == m.sha256
            if (ok) {
                writeAtomic(verifiedMark(ctx, m), "$sha|$fp")
                prefs(ctx).edit().remove(key(m, "bad")).remove(key(m, "last_failure")).apply()
                if (f != src) tidy(ctx, m)
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
     * Your own file is a proven TPU build for this phone's chip: you allowed the TPU for it (its warning card), or took that back. Allowed
     * only for the very file whose card you read ([sha256], as the card showed it), unchanged since its check.
     */
    fun allowOwnTpu(ctx: Context, allow: Boolean, sha256: String? = null): Boolean {
        val r = own(ctx) ?: return false
        if (allow && (!r.report.runsOnTpuOf(phone(ctx)) || r.sha256 != sha256 || r.fingerprint != fingerprint(file(ctx, BrainModel.OWN)))) return false
        writeAtomic(ownRecord(ctx), r.copy(tpuAllowed = allow).toJson().toString())
        RLog.i("model_own_tpu", "allowed" to allow)
        return true
    }

    /** What the model downloads under way will still write, all of them (a move's room check). */
    private fun pendingAll(ctx: Context): Long = VoiceModelStore.pendingBytes(ctx) + pendingBytes(ctx) + MtModelStore.pendingBytes(ctx)

    /**
     * 1.0.1: [m]'s checked file still outside internal storage (1.0's place) is copied in once, hashed as it is written
     * against its checked SHA-256, when there's room; its mark (or your own file's record) then names the inside copy and the
     * outside one goes ([tidy]). With the inside copy already checked, only the outside one goes. Blocking (minutes). True
     * when nothing is left outside. A copy whose bytes aren't the checked ones (changed behind Robot's back) leaves the
     * outside file marked unchecked, so it is checked again before anything loads it.
     */
    fun moveIn(ctx: Context, m: BrainModel): Boolean {
        if (busyWith(ctx) != null || verifying != null || moving != null || importing != null) return false
        val checked = verifiedPath(ctx, m)?.let(::File) ?: return false
        if (!outside(ctx, checked)) return tidy(ctx, m)
        val expected = (if (m == BrainModel.OWN) own(ctx)?.sha256 else m.sha256) ?: return false
        moving = m
        movePercent = 0
        moveStop = false
        try {
            val r = ModelMove.copyIn(checked, File(dir(ctx), checked.name), expected, { freeBytes(ctx) }, pendingAll(ctx), go = { !moveStop }) { done, all ->
                movePercent = ModelLogic.percent(done, all)
            }
            RLog.i("model_move", "op" to RLog.Code("move"), "model" to RLog.Code(m.id), "result" to RLog.Code(r.javaClass.simpleName.lowercase()))
            when (r) {
                is ModelMove.Result.Moved -> {
                    val fp = fingerprint(r.file)
                    if (m == BrainModel.OWN) own(ctx)?.let { writeAtomic(ownRecord(ctx), it.copy(fingerprint = fp).toJson().toString()) }
                    else writeAtomic(verifiedMark(ctx, m), "$expected|$fp")
                    return tidy(ctx, m)
                }
                is ModelMove.Result.Mismatch -> {
                    // Same size and time, other bytes: never trusted again without a full check.
                    if (m == BrainModel.OWN) own(ctx)?.let { writeAtomic(ownRecord(ctx), it.copy(fingerprint = "").toJson().toString()) }
                    else verifiedMark(ctx, m).delete()
                    return false
                }
                else -> return false
            }
        } finally {
            movePercent = null
            moving = null
            moveStop = false
        }
    }

    /**
     * [m]'s copies outside internal storage, once the one inside is the checked one: deleted (and what LiteRT-LM left
     * beside them). Never while :brain has [m] loaded or is loading it. True when none is left.
     */
    fun tidy(ctx: Context, m: BrainModel): Boolean {
        val outsideDir = outsideDir(ctx) ?: return true
        val checked = verifiedPath(ctx, m)?.let(::File) ?: return false
        if (outside(ctx, checked) || loadedNow(ctx) == m) return false
        val names = if (m == BrainModel.OWN) ModelFormat.entries.map { ModelFiles.ownFile(it) } else listOf(m.file)
        outsideDir.listFiles { f -> names.any { n -> ModelFiles.besideFile(f.name, n) } }?.forEach { it.deleteRecursively() }
        return names.none { File(outsideDir, it).exists() }
    }

    private fun inUse(m: BrainModel) = "Robot has “${m.title}” loaded right now. Choose another model first: Robot lets go of it right away, then try again."

    /** Null when [m]'s file, cache, marks and download are gone, else why not: never the model in memory. */
    fun delete(ctx: Context, m: BrainModel): String? {
        if (loadedNow(ctx) == m) return inUse(m)
        cancelDownload(ctx, m)
        stopMove()
        prefs(ctx).edit().remove(key(m, "download_id")).remove(key(m, "bad")).remove(key(m, "last_failure")).apply()
        allFiles(ctx, m).forEach { it.delete() }
        part(ctx, m).delete()
        verifiedMark(ctx, m).delete()
        cacheDir(ctx, m).deleteRecursively()
        // Anything LiteRT-LM may have left beside the file (ModelFiles.besideFile), inside and out.
        for (d in dirs(ctx)) d.listFiles { f -> ModelFiles.besideFile(f.name, m.file) }?.forEach { it.deleteRecursively() }
        if (m == BrainModel.OWN) {
            ownRecord(ctx).delete()
            prefs(ctx).edit().remove(OWN_NAME).apply()
        }
        RLog.i("model_delete", "model" to RLog.Code(m.id))
        return null
    }

    private const val OWN_NAME = "own_name"

    /** Neither kind of model file Robot reads, by its first bytes. */
    private val NOT_A_MODEL = app.heyrobot.core.LiteRtLmReport(readable = false, problem = "It isn't a model file Robot can read (a .litertlm or .gguf file).")
}
