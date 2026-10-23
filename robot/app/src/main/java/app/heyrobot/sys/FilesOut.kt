// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileLimits
import app.heyrobot.core.files.FileOut
import app.heyrobot.core.files.FileOutCode
import app.heyrobot.core.files.FileProblem
import app.heyrobot.core.parse.OutgoingCopy
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseResult
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

// MAIN PROCESS. A file of Robot Files handed to another app (files-1.4-plan.md §5.2, §6.4; job F7): Share, or Open with
// another app. Never the sealed file and never Robot's storage: a copy written for this one hand-off into
// cache/files_out/<random>/<name>, served by [FilesOutProvider] (that folder alone, not exported), with a read grant on
// that one URI (no write, no persistable or prefix grant) through the system's chooser. Settings › Files' "Strip location
// from photos I send" decides which copy (core FileOut.plan): an image as :files re-encodes it without any metadata
// (FileParseLink.strip), else the file as you added it, decrypted as it streams.
//
// Taken back, each grant revoked and each copy deleted: when you come back to Robot after the other app took over
// ([stopped], then [resumed]), at screen-off (RobotFiles' hook), when a Files screen goes or the phone locks, when the
// file is deleted, after FileOut.MAX_MS at most, and at the main app's next start (after a crash). FX2 (K3): and by :voice
// at its start and at each unlock, once older than FileOut.MAX_MS, so one doesn't wait for the main app when Android ended
// it. Better later: serve the file through StorageManager.openProxyFileDescriptor, decrypting as it is read, with nothing
// plain on disk at all. The other app may keep
// what it read: the dialog before Open says so. Never while the phone is locked or a phone lock is on: the caller checks
// the gate at the tap and again here, and the vault refuses anyway. The voice log gets codes only (FILE_OUT).

/** The main app's provider for the copies handed to another app (cache/files_out only). Not exported; one-time read grants. */
class FilesOutProvider : FileProvider()

/** What making a copy came to. */
sealed class OutCopy {
    /** Ready to hand: [uri] in [FilesOut.AUTHORITY], typed [mime]. */
    class Ready(val id: FileId, val uri: Uri, val mime: String, val stripped: Boolean, val dangerous: Boolean) : OutCopy()
    /** Not made: [why]. Nothing is left behind. */
    class Failed(val why: OutFailure) : OutCopy()
}

/** Why a copy wasn't made. */
enum class OutFailure { LOCKED, LOW_SPACE, NO_COPY, FAILED }

object FilesOut {
    /** The manifest's `${applicationId}.files_out`. */
    const val AUTHORITY = app.heyrobot.BuildConfig.APPLICATION_ID + ".files_out"

    /** Robot's share target, never offered the copy (a file of Robot Files can't come back in as a new one). */
    private const val SHARE_TARGET = "app.heyrobot.ui.files.FilesShareActivity"

    fun dir(ctx: Context): File = File(ctx.cacheDir, "files_out")

    private class Live(val id: FileId, val uri: Uri, val folder: File, val until: Long)

    private val live = ArrayList<Live>()
    private val random = SecureRandom()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** A copy was handed out and Robot went to the background since: coming back takes it back. */
    @Volatile private var away = false

    /**
     * [MAIN, off the main thread] Writes [id]'s copy: [copy] ORIGINAL streams the file from the vault, STRIPPED has :files
     * re-encode it ([strip]: FileParseLink.strip). [name]: core FileOut.name's. [locked]: the phone is locked or a phone
     * lock is on (checked here again). Nothing is left behind when it fails.
     */
    fun make(ctx: Context, vault: FilesVault, id: FileId, size: Long, copy: OutgoingCopy, name: String, mime: String, dangerous: Boolean,
        locked: () -> Boolean, strip: (ByteArray) -> ParseResult, uriOf: ((File) -> Uri)? = null): OutCopy {
        if (locked()) return OutCopy.Failed(OutFailure.LOCKED)
        require(copy != OutgoingCopy.CANT_STRIP)
        val app = ctx.applicationContext
        val root = dir(app).apply { mkdirs() }
        if (root.usableSpace < size + FileLimits.FREE_MARGIN) return OutCopy.Failed(OutFailure.LOW_SPACE)
        val folder = File(root, FileId.new(random).hex)
        val file = File(folder, name)
        return try {
            folder.mkdirs()
            if (copy == OutgoingCopy.STRIPPED) {
                val bytes = vault.open(id).use { it.readAll(ParseLimits.MAX_INPUT.toLong()) }
                val out = (strip(bytes) as? ParseResult.Ok)?.out?.copy
                bytes.fill(0)
                if (out == null) {
                    folder.deleteRecursively()
                    return OutCopy.Failed(OutFailure.NO_COPY)
                }
                FileOutputStream(file).use { it.write(out) }
            } else {
                vault.open(id).use { input -> FileOutputStream(file).use { o -> input.copyTo(o, 64 * 1024) } }
            }
            // The phone may have locked while the copy was written: nothing goes then.
            if (locked()) {
                folder.deleteRecursively()
                return OutCopy.Failed(OutFailure.LOCKED)
            }
            // [uriOf]: the tests' stand-in (Robolectric doesn't read a FileProvider's paths); the phone's is the provider's own.
            val uri = uriOf?.invoke(file) ?: FileProvider.getUriForFile(app, AUTHORITY, file)
            synchronized(live) { live += Live(id, uri, folder, System.currentTimeMillis() + FileOut.MAX_MS) }
            main.postDelayed({ expire(app) }, FileOut.MAX_MS + 1_000)
            OutCopy.Ready(id, uri, mime, copy == OutgoingCopy.STRIPPED, dangerous)
        } catch (x: FileException) {
            folder.deleteRecursively()
            OutCopy.Failed(if (x.problem == FileProblem.LOCKED) OutFailure.LOCKED else OutFailure.FAILED)
        } catch (t: Exception) {
            RLog.w("files_out_copy", t)
            folder.deleteRecursively()
            OutCopy.Failed(OutFailure.FAILED)
        }
    }

    /** Share's chooser for [c]: SEND with the one URI and a read grant (EXTRA_STREAM and its ClipData), never Robot's own share target. */
    fun shareIntent(ctx: Context, c: OutCopy.Ready, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType(c.mime).putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, c.uri)
        return chooser(ctx, send, title)
    }

    /** Open with another app's chooser for [c]: VIEW of the one URI with a read grant. */
    fun openIntent(ctx: Context, c: OutCopy.Ready, title: String): Intent {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, c.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri(null, c.uri)
        return chooser(ctx, view, title)
    }

    /** Always the chooser (no app gets a file of yours as a default), the grant carried to the app you pick. */
    private fun chooser(ctx: Context, target: Intent, title: String): Intent =
        Intent.createChooser(target, title).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx.packageName, SHARE_TARGET)))

    /** A copy was handed (its chooser started): log it, codes only. */
    fun handed(ctx: Context, c: OutCopy.Ready, open: Boolean) =
        log(ctx, (if (open) FileOutCode.OPENED else FileOutCode.SHARED).id, if (c.stripped) 1 else 0, if (c.dangerous) 1 else 0)

    /** Robot went to the background (the Files screen's ON_STOP): with a copy out, coming back takes it back. */
    fun stopped() {
        if (synchronized(live) { live.isNotEmpty() }) away = true
    }

    /** Robot is in front again (the Files screen's ON_RESUME): every copy handed out before it went is taken back. */
    fun resumed(ctx: Context) {
        if (!away) return
        away = false
        revokeAll(ctx)
    }

    /** Whether any copy is out now. */
    val out: Boolean get() = synchronized(live) { live.isNotEmpty() }

    /** Every copy taken back: its grant revoked, its folder deleted. */
    fun revokeAll(ctx: Context) = revoke(ctx) { true }

    /** [id]'s copies taken back (the file is being deleted). */
    fun revoke(ctx: Context, id: FileId) = revoke(ctx) { it.id == id }

    private fun expire(ctx: Context) {
        val now = System.currentTimeMillis()
        revoke(ctx) { it.until <= now }
    }

    private fun revoke(ctx: Context, which: (Live) -> Boolean) {
        val gone = synchronized(live) { live.filter(which).also { live.removeAll(it.toSet()) } }
        if (gone.isEmpty()) return
        for (l in gone) {
            try { ctx.applicationContext.revokeUriPermission(l.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (t: Exception) { RLog.w("files_out_revoke", t) }
            l.folder.deleteRecursively()
        }
        log(ctx, FileOutCode.REVOKED.id, gone.size, 0)
    }

    /** At the main app's start (and Delete them all): no copy outlives the process that handed it out. */
    fun clear(ctx: Context) {
        synchronized(live) { live.clear() }
        dir(ctx).listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    /**
     * FX2 (files-1.4-review.md K3), from :voice at its start and at each unlock: copies older than FileOut.MAX_MS deleted,
     * so a copy whose main process died (Android killed it while another app had the file open) doesn't stay plain in the
     * cache until Robot's main app next starts. A copy still in its 15 minutes is left for the main app to take back. The
     * number deleted.
     */
    fun clearStale(ctx: Context, now: Long = System.currentTimeMillis()): Int = stale(dir(ctx), now, FileOut.MAX_MS)

    /** [root]'s folders (and stray files) last written more than [maxMs] before [now], deleted: how many. */
    internal fun stale(root: File, now: Long, maxMs: Long): Int {
        var n = 0
        for (f in root.listFiles().orEmpty()) {
            val newest = maxOf(f.lastModified(), f.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L)
            if (now - newest > maxMs && f.deleteRecursively()) n++
        }
        return n
    }

    private fun log(ctx: Context, a: Int, b: Int, c: Int) {
        try {
            VoiceLog.init(Paths.voiceLogMain(ctx))
            VoiceLog.add(VoiceEvent.FILE_OUT, a, b, c)
        } catch (_: Throwable) {}
    }
}
