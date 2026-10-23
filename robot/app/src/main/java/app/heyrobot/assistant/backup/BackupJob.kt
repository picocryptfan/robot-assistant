// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.heyrobot.BuildConfig
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupCode
import app.heyrobot.core.backup.BackupContainerWriter
import app.heyrobot.core.backup.BackupExport
import app.heyrobot.core.backup.BackupFormat
import app.heyrobot.core.backup.BackupHeader
import app.heyrobot.core.backup.BackupKdf
import app.heyrobot.core.backup.BackupManifest
import app.heyrobot.core.backup.BackupWriter
import app.heyrobot.core.backup.CategoryCount
import app.heyrobot.core.backup.KdfParams
import app.heyrobot.core.backup.RestoreReader
import app.heyrobot.core.backup.BackupCaps
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileProblem
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.BackupFiles
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.RobotFiles
import app.heyrobot.sys.SettingsStore
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.SecureRandom

// :VOICE PROCESS (docs/plans/backup-1.1-design.md §5, §6.1, §9.2). Back up now's work, off the main thread: the
// entries (BackupSources, BackupSecrets), Argon2id (BackupKdf), the file through the picker's stream (AES-256-GCM in
// 64 KiB chunks, BackupWriter), then the whole file read back and every chunk checked (RestoreReader.verify) before it
// counts as saved. A file that fails, is cancelled or doesn't read back is deleted. The passphrase and the key never
// leave this process; the log gets codes only (voice log event 240).

internal class BackupJob(private val ctx: Context, private val chosen: Set<BackupCategory>, private val recordings: Boolean, private val pictures: Boolean,
                         /** 1.4: Robot Files' videos go in (Back up now's Videos row under Files). */
                         private val videos: Boolean = true,
                         /** 1.4: what Back up now offers next time (your ticks, a category left out because it couldn't be opened included). */
                         private val remember: Set<BackupCategory> = chosen,
                         private val files: () -> FilesVault = { RobotFiles.vault(ctx) }) {
    /** Where the work is: [category] being written (null: starting), [done] of [entries], or [checking] the saved file. */
    data class Progress(val category: BackupCategory?, val done: Int, val entries: Int, val checking: Boolean = false)

    sealed class Outcome {
        /** Saved and checked: [bytes] long, [categories] in it; [unreadableKeys]: helpers whose key couldn't be read now. */
        class Done(val bytes: Long, val categories: Set<BackupCategory>, val unreadableKeys: List<String>) : Outcome()
        class Failed(val code: BackupCode) : Outcome()
        data object Cancelled : Outcome()
    }

    @Volatile var cancelled = false

    /** 1.4: when the job ended with [BackupCode.TOO_BIG], about how big the backup would have been. */
    @Volatile var tooBig = 0L

    private class Stop : Exception()

    fun run(uri: Uri, passphrase: CharSequence, progress: (Progress) -> Unit): Outcome {
        val cr = ctx.contentResolver
        if (Assistant.locked(ctx)) return failed(BackupCode.LOCKED, null)
        val settings = SettingsStore.load(ctx)
        progress(Progress(null, 0, 0))
        val gathered = try {
            BackupSources.gather(ctx, settings, chosen, recordings, pictures, videos, files)
        } catch (_: BackupSources.MemoryUnreadable) {
            return failed(BackupCode.MEMORY_UNREADABLE, uri)
        } catch (_: BackupSources.FilesUnreadable) {
            return failed(BackupCode.FILES_UNREADABLE, uri)
        } catch (t: Throwable) {
            RLog.w("backup_gather", t)
            return failed(BackupCode.FAILED, uri)
        }
        // 1.4: over the 32 GiB a backup holds (Robot Files' videos, transcripts' recordings): refused before anything is written.
        val planned = gathered.sources.sumOf { it.estimate } + gathered.secrets.sumOf { it.bytes.size.toLong() }
        if (planned > BackupCaps.MAX_TOTAL) {
            gathered.secrets.forEach { it.close() }
            tooBig = planned
            return failed(BackupCode.TOO_BIG, uri)
        }
        try {
            val salt = ByteArray(BackupFormat.SALT).also { SecureRandom().nextBytes(it) }
            val header = BackupHeader(BackupFormat.FORMAT, KdfParams.CURRENT, salt, BuildConfig.VERSION_CODE.toLong())
            return BackupKdf.derive(passphrase, header).use job@{ key ->
                if (cancelled) return@job cancel(uri)
                // Checked again before the first write.
                if (Assistant.locked(ctx)) return@job failed(BackupCode.LOCKED, uri)
                val categories = (gathered.sources.map { it.category } + gathered.secrets.map { it.category }).toSet()
                val counts = categories.associate { c ->
                    c.id to CategoryCount(gathered.sources.count { it.category == c }.toLong() + gathered.secrets.count { it.category == c },
                        gathered.sources.filter { it.category == c }.sumOf { it.estimate })
                }
                val manifest = BackupManifest(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis(), counts,
                    keys = BackupCategory.KEYS in categories)
                val total = gathered.sources.size + gathered.secrets.size
                var written = 0
                val wrote = HashSet<BackupCategory>()
                try {
                    val out = cr.openOutputStream(uri, "wt") ?: return@job failed(BackupCode.WRITE_FAILED, uri)
                    BackupWriter(BufferedOutputStream(out, BackupFormat.CHUNK), header, key).use { w ->
                        val c = BackupContainerWriter(w)
                        c.manifest(manifest)
                        for (src in gathered.sources) {
                            if (cancelled) throw Stop()
                            progress(Progress(src.category, written, total))
                            if (src.streamed) {
                                val (length, data) = src.open() ?: continue
                                Stoppable(data).use { c.entry(src.category, src.name, length, it) }
                            } else {
                                val bytes = src.read() ?: continue
                                c.entry(src.category, src.name, bytes)
                            }
                            wrote += src.category
                            written++
                        }
                        for (sec in gathered.secrets) {
                            if (cancelled) throw Stop()
                            progress(Progress(sec.category, written, total))
                            c.entry(sec.category, sec.name, sec.bytes)
                            wrote += sec.category
                            written++
                        }
                        c.end()
                    }
                } catch (_: Stop) {
                    return@job cancel(uri)
                } catch (x: FileException) {
                    // 1.4: Robot Files' key needs the phone unlocked; a file's bytes are otherwise never a reason to stop.
                    RLog.w("backup_write", x)
                    return@job failed(if (x.problem == FileProblem.LOCKED) BackupCode.LOCKED else BackupCode.WRITE_FAILED, uri)
                } catch (t: Throwable) {
                    RLog.w("backup_write", t)
                    return@job failed(BackupCode.WRITE_FAILED, uri)
                }
                // Read the whole saved file back: every chunk, every record, the end's counts.
                progress(Progress(null, written, total, checking = true))
                val ok = try {
                    RestoreReader.verify({ cr.openInputStream(uri) ?: throw java.io.FileNotFoundException() }, header, key).entries == written.toLong()
                } catch (t: Throwable) {
                    RLog.w("backup_check", t)
                    false
                }
                if (!ok) return@job failed(BackupCode.CHECK_FAILED, uri)
                val bytes = size(uri)
                val now = System.currentTimeMillis()
                BackupFiles.saveState(ctx, BackupFiles.state(ctx).after(now, wrote, bytes, checked = true)
                    .copy(chosen = remember, recordings = recordings, pictures = pictures, videos = videos))
                log(BackupCode.DONE_CHECKED, wrote, bytes)
                Outcome.Done(bytes, wrote, gathered.unreadableKeys)
            }
        } finally {
            gathered.secrets.forEach { it.close() }
        }
    }

    private fun cancel(uri: Uri): Outcome {
        delete(uri)
        log(BackupCode.CANCELLED, emptySet(), 0)
        return Outcome.Cancelled
    }

    private fun failed(code: BackupCode, uri: Uri?): Outcome {
        uri?.let(::delete)
        log(code, emptySet(), 0)
        return Outcome.Failed(code)
    }

    private fun log(code: BackupCode, categories: Set<BackupCategory>, bytes: Long) =
        VoiceLog.add(VoiceEvent.BACKUP, code.id, BackupExport.bits(categories), (bytes / (1024 * 1024)).toInt())

    /** The partial or unchecked file goes: the provider deletes it, or at least it is emptied. */
    private fun delete(uri: Uri) {
        try {
            if (DocumentsContract.deleteDocument(ctx.contentResolver, uri)) return
        } catch (t: Throwable) {
            RLog.w("backup_delete", t)
        }
        try { ctx.contentResolver.openOutputStream(uri, "wt")?.close() } catch (_: Throwable) {}
    }

    private fun size(uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    } catch (_: Throwable) { 0L }

    /** A stream that stops the job between two reads once Cancel is tapped (a recording may be a gigabyte). */
    private inner class Stoppable(input: InputStream) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (cancelled) throw Stop()
            return super.read(b, off, len)
        }
    }
}
