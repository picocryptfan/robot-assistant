// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.heyrobot.core.RLog
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileOut
import app.heyrobot.core.files.FilePack
import app.heyrobot.core.files.OutPlan
import app.heyrobot.core.files.Pack
import app.heyrobot.core.files.PackWords
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.OutgoingCopy
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseResult
import java.io.InputStream
import java.io.OutputStream

// MAIN PROCESS. 1.4 F12: a tax pack or an expense report copied out (files-1.4-plan.md §7.2): only after your tap on
// Export in Files and the folder you then pick in Android's folder picker (ACTION_OPEN_DOCUMENT_TREE). Robot keeps no
// access to that folder (no persisted grant): one export, then it is gone.
//
//   - A new folder inside the one you picked ("Robot tax pack 2026"), so nothing of yours there is written over.
//   - Each file as Share sends it (core FileOut.plan): a photo without its location when "Strip location from photos I
//     send" is on. One whose location Robot can't remove (a video, a type :files doesn't decode, over 64 MiB) is left out
//     and counted; share it from its page if you want it.
//   - Then the table (core FilePack.csv), naming each file as it was copied.
//   - The lock checked before anything, before each file and after each write: the phone locked, Robot stops, and what was
//     already copied stays where you put it (the result says how many).
//   - The copies are not encrypted: the page says so before you tap. Codes and counts only in the logs.

/** Where an export writes: a folder it made. */
interface ExportFolder {
    /** A new file [name] of [mime] to write; null when it couldn't be made. */
    fun create(name: String, mime: String): OutputStream?

    /**
     * FX2 (files-1.4-review.md finding 13): the file [create] made as [name] deleted, after its copy failed part way, so no
     * half-written plain copy stays in your folder. Nothing when none was made.
     */
    fun remove(name: String) {}
}

/** The folder you picked: [folder] makes Robot's own folder [name] inside it, or null. */
fun interface ExportPlace {
    fun folder(name: String): ExportFolder?
}

/** How an export ended: [copied] files and [leftOut] (not copied), in the folder [folder]; or why it stopped. */
sealed class ExportResult {
    data class Done(val copied: Int, val leftOut: Int, val folder: String) : ExportResult()
    data class Stopped(val why: ExportStop, val copied: Int) : ExportResult()
}

/** Why an export stopped: the phone locked, the folder couldn't be made there, or writing failed. */
enum class ExportStop { LOCKED, NO_FOLDER, FAILED }

/** What an export reads: each file's bytes through the vault's gate, its type, :files' stripped copy, the lock. */
class ExportSource(
    val open: (FileId) -> InputStream,
    val type: (FileEntry) -> FileType,
    val strip: (ByteArray) -> ParseResult,
    val locked: () -> Boolean,
    val stripLocation: Boolean,
)

object FilesExport {
    /** [pack] into [place]: Robot's folder, each file, then the table. */
    fun run(pack: Pack, place: ExportPlace, src: ExportSource, words: PackWords, langs: Langs): ExportResult {
        if (src.locked()) return ExportResult.Stopped(ExportStop.LOCKED, 0)
        val folderName = FilePack.folder(pack.kind, pack.year, langs)
        val folder = try { place.folder(folderName) } catch (t: Exception) { RLog.w("files_export_folder", t); null }
            ?: return ExportResult.Stopped(ExportStop.NO_FOLDER, 0)
        val plans = pack.files.map { e -> FileOut.plan(e, src.type(e), src.stripLocation) }
        val names = FilePack.unique(plans.map { p -> when (p) { is OutPlan.Go -> p.name; is OutPlan.AskFirst -> p.name } })
        val copied = LinkedHashMap<FileId, String?>()
        var n = 0
        for ((i, e) in pack.files.withIndex()) {
            if (src.locked()) return ExportResult.Stopped(ExportStop.LOCKED, n)
            val plan = plans[i] as? OutPlan.Go
            val ok = plan != null && copy(e, plan, names[i], folder, src)
            if (src.locked()) return ExportResult.Stopped(ExportStop.LOCKED, n + if (ok) 1 else 0)
            copied[e.id] = if (ok) names[i] else null
            if (ok) n++
        }
        val table = FilePack.csv(pack, copied, words)
        val csv = "$folderName.csv" // l10n: not text (a file extension)
        val wrote = try {
            folder.create(csv, "text/csv")?.use { it.write(table.toByteArray(Charsets.UTF_8)); true } ?: false // l10n: not text (a MIME type)
        } catch (t: Exception) {
            RLog.w("files_export_table", t)
            removeQuietly(folder, csv)
            false
        }
        if (!wrote) return ExportResult.Stopped(ExportStop.FAILED, n)
        return ExportResult.Done(n, pack.files.size - n, folderName)
    }

    /** One file as [plan] says, into [folder] as [name]; false when it couldn't be (left out, noted in the table). */
    private fun copy(e: FileEntry, plan: OutPlan.Go, name: String, folder: ExportFolder, src: ExportSource): Boolean = try {
        if (plan.copy == OutgoingCopy.STRIPPED) {
            // Stripped first, in :files: a photo whose copy can't be made leaves nothing behind in your folder.
            val bytes = src.open(e.id).use { it.readNBytes(ParseLimits.MAX_INPUT) }
            val stripped = (src.strip(bytes) as? ParseResult.Ok)?.out?.copy
            bytes.fill(0)
            if (stripped == null) false else folder.create(name, plan.mime)?.use { it.write(stripped); true } ?: false
        } else {
            folder.create(name, plan.mime)?.use { o -> src.open(e.id).use { it.copyTo(o, 64 * 1024) }; true } ?: false
        }
    } catch (t: Exception) {
        RLog.w("files_export_copy", t)
        // FX2 (finding 13): a copy cut part way (the phone locked, the folder's app failed) leaves nothing behind.
        removeQuietly(folder, name)
        false
    }

    private fun removeQuietly(folder: ExportFolder, name: String) {
        try { folder.remove(name) } catch (t: Exception) { RLog.w("files_export_remove", t) }
    }
}

/** The folder you picked in Android's folder picker ([tree]: its URI, a one-time grant Robot never keeps). */
class TreeExportPlace(ctx: Context, private val tree: Uri) : ExportPlace {
    private val resolver = ctx.applicationContext.contentResolver

    override fun folder(name: String): ExportFolder? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val dir = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: return null
        return object : ExportFolder {
            /** What each name asked for became (the folder's app may have renamed it): what [remove] deletes. */
            private val made = HashMap<String, Uri>()

            override fun create(name: String, mime: String): OutputStream? =
                DocumentsContract.createDocument(resolver, dir, mime, name)?.let { made[name] = it; resolver.openOutputStream(it, "w") } // l10n: not text (a file mode)

            override fun remove(name: String) {
                made.remove(name)?.let { DocumentsContract.deleteDocument(resolver, it) }
            }
        }
    }
}
