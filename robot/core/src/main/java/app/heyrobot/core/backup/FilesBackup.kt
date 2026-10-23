// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.files.FieldBy
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileField
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileIndexCodec
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileLimits
import app.heyrobot.core.files.FileRemind
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.files.FilesSettingsCodec
import app.heyrobot.core.files.Filing
import app.heyrobot.core.files.FilingStep
import app.heyrobot.core.files.FilingWhy
import app.heyrobot.core.files.Provenance
import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileType

// Pure. 1.4, Robot Files in the one backup (docs/plans/files-1.4-plan.md §2.8; job F11). The FILES category holds:
//
//   blob/<id>            a file's plain bytes (at most 200 MiB), read through this phone's seal while exporting: neither the
//                        sealed form nor robot_files_v1 ever goes in, and the backup's own encryption covers them
//   index.json           the catalogue (FileIndexCodec v1, at most 32 MiB), written after the bytes: it names only the files
//                        whose bytes went in whole, each with the size and SHA-256 they streamed with. A file that failed part
//                        way (a damaged chunk) keeps its place in the backup as zeros and isn't named, so a restore skips it
//   files_settings.json  Settings › Files (FilesSettingsCodec), kept safer by RestoreSafety.filesSettings
//
// No thumbnails: a restored file that had one is listed in the index's top-level "rethumb" key, and FilingWork makes it
// again (each tried once). A 1.1 to 1.3 Robot skips the whole category by name (every entry is under 1 GiB).
//
// A restore (RestoreMerge.files): REPLACE takes the backup's files with their ids, deleting this phone's first (a file
// here with the backup's id and the same bytes keeps its sealed bytes, and the backup's entry takes its place, so what
// Replace deletes and what it restores never share an id, and finishing it twice after a crash deletes nothing it
// restored); ADD, and FRESH (empty when the preview opened, so a file added since stays), take the files whose id and
// SHA-256 this phone doesn't have, with their ids. At most 5,000 files. Someone else's files land in Inbox, waiting for
// you (RestoreTaint.file).

object FilesBackup {
    const val INDEX = "index.json"
    const val SETTINGS = FilesSettingsCodec.FILE
    const val BLOB = "blob/"
    /** 5,000 files, the index and the settings. */
    const val MAX_ENTRIES = FileLimits.MAX_FILES + 2
    /** The index's top-level key: the ids of restored files whose thumbnail is made again (FilingWork), each tried once. */
    const val RETHUMB = app.heyrobot.core.files.FilingRun.RETHUMB

    fun blobName(id: FileId): String = BLOB + id.hex

    /** The file id a "blob/<id>" entry names, or null for any other name. */
    fun blobId(name: String): FileId? = if (name.startsWith(BLOB)) FileId.of(name.substring(BLOB.length)) else null

    /** A video, by Robot's kind or its sniffed type: "Videos" under Files on Back up now's list leaves these out. */
    fun video(e: FileEntry): Boolean = e.kind == FileKind.VIDEO || e.from.type?.startsWith("video/") == true

    /** Whether [e]'s bytes go in a backup: not marked damaged, and not a video when [videos] are left out. */
    fun carried(e: FileEntry, videos: Boolean): Boolean = !e.damaged && (videos || !video(e))

    /**
     * index.json: [index]'s entries whose bytes went in whole ([streamed]: each one's size and SHA-256 as written), in its
     * order, with its top-level keys. Nothing else of the entries changes (a restore clears `thumb`).
     */
    fun exported(index: FileIndex, streamed: Map<FileId, Streamed>): FileIndex = FileIndex(
        index.entries.mapNotNull { e -> streamed[e.id]?.let { s -> e.copy(from = e.from.copy(size = s.size, sha256 = s.sha256)) } },
        index.extra,
    )

    /** One file's bytes as they went in: [size] and [sha256] (lower-case hex). */
    data class Streamed(val size: Long, val sha256: String)

    /** The ids a top-level list key holds ([RETHUMB]): anything that isn't an id is ignored. */
    fun ids(v: Any?): List<FileId> = (v as? List<*>).orEmpty().mapNotNull { FileId.of(it as? String) }.distinct()

    internal fun strings(v: Any?): List<String> = (v as? List<*>).orEmpty().filterIsInstance<String>()
}

/**
 * What a restore makes of Files: [add], the entries to write (each one's bytes staged, or already here for [keep]), in the
 * backup's order; [delete], this phone's files Replace deletes (never one of [add]'s ids); [keep], files here with the
 * backup's id and the same bytes (their sealed bytes stay); [rethumb], files whose thumbnail is made again; [skipped], the
 * backup's files left out (already here, or past the 5,000 cap). Counts only in [toString].
 */
class FilesRestored(val add: List<FileEntry>, val delete: Set<FileId>, val keep: Set<FileId>, val rethumb: List<FileId>, val skipped: Int) {
    /** The ids whose bytes the restore stages from the backup. */
    val staged: Set<FileId> get() = add.mapTo(LinkedHashSet()) { it.id } - keep

    override fun toString(): String = "FilesRestored(add=${add.size}, delete=${delete.size}, keep=${keep.size}, skipped=$skipped)" // l10n: not text (counts for logs and tests)
}

object FilesMerge {
    /**
     * The plan for [file] (the backup's index, every entry's bytes checked) on a phone whose index is [have]. [people]:
     * where each People id of the backup landed (null: nobody here). [now]: when someone else's files come in.
     */
    fun plan(have: FileIndex, file: FileIndex, mode: RestoreMode, mine: Boolean, now: Long, people: (String) -> String?): FilesRestored {
        // Each entry within the index's cap once changed (someone else's gains a question and a suggestion): its search text
        // cut if need be; one that still can't fit is left out.
        val incoming = file.entries.mapNotNull { FileIndexCodec.fitText(RestoreTaint.file(RestoreRefs.file(it, people), mine, now)) }
        val had = FilesBackup.ids(file.extra[FilesBackup.RETHUMB]).toSet()
        val thumbs = file.entries.filter { it.thumb || it.id in had }.mapTo(HashSet()) { it.id }
        val here = have.entries.associateBy { it.id }
        val add: List<FileEntry>
        val delete: Set<FileId>
        val keep: Set<FileId>
        if (mode == RestoreMode.ADD || mode == RestoreMode.FRESH) {
            // By id, and never a second copy of bytes already here (on an empty cabinet: every file, as Replace takes them).
            val shas = have.entries.mapNotNullTo(HashSet()) { it.from.sha256 }
            val seen = HashSet<FileId>()
            add = incoming.filter { it.id !in here && it.from.sha256 !in shas && seen.add(it.id) }.take((FileLimits.MAX_FILES - have.entries.size).coerceAtLeast(0))
            delete = emptySet()
            keep = emptySet()
        } else {
            // A file here with the backup's id: the same bytes stay (only the entry changes); other bytes under that id are
            // no file of this backup's (ids are random), so the backup's is left out and this phone's goes with the rest.
            val clash = incoming.filter { it.id in here }
            keep = clash.filter { e -> here.getValue(e.id).let { !it.damaged && it.from.sha256 != null && it.from.sha256 == e.from.sha256 } }.mapTo(HashSet()) { it.id }
            val refused = clash.mapTo(HashSet()) { it.id } - keep
            add = incoming.filter { it.id !in refused }.distinctBy { it.id }.take(FileLimits.MAX_FILES)
            delete = have.ids - keep
        }
        val rethumb = add.filter { it.id in thumbs && it.id !in keep }.map { it.id }
        return FilesRestored(add, delete, keep, rethumb, file.entries.size - add.size)
    }

    /**
     * The index's top-level keys after a restore: this phone's ([have]) with the backup's ([file]) as the plan says. Your own
     * backup: on Add (and FRESH, the same on a phone with none) your drawers gain the backup's (at most 100) and this phone's
     * other keys win; on Replace the backup's drawers and keys take their place. Someone else's: none of theirs. Either way
     * the files whose thumbnail is made again are added to [FilesBackup.RETHUMB].
     */
    fun extra(have: Map<String, Any?>, file: Map<String, Any?>, mode: RestoreMode, mine: Boolean, rethumb: List<FileId>): Map<String, Any?> {
        val out = LinkedHashMap(have)
        val drawers = FileViews.DRAWERS_KEY
        if (mine) {
            for ((k, v) in file) if (k != drawers && k != FilesBackup.RETHUMB && (mode == RestoreMode.REPLACE || k !in out)) out[k] = v
            val theirs = FilesBackup.strings(file[drawers]).filter(FileIndexCodec::label)
            when {
                mode == RestoreMode.REPLACE -> if (drawers in file) out[drawers] = theirs.distinct().take(FileViews.MAX_DRAWERS) else out.remove(drawers)
                theirs.isNotEmpty() -> out[drawers] = (FilesBackup.strings(have[drawers]) + theirs).distinct().take(FileViews.MAX_DRAWERS)
            }
        }
        val again = (FilesBackup.ids(have[FilesBackup.RETHUMB]) + rethumb).distinct()
        if (again.isNotEmpty()) out[FilesBackup.RETHUMB] = again.map { it.hex }
        return out
    }

    /**
     * Someone else's file, its type sniffed again ([type], FX3: by the same two looks intake takes, Resealed.type): never
     * the backup's claim. One that could install or run something is DANGEROUS and held, as an add is (FileStore.commit);
     * one the backup calls dangerous stays so. Your own files are left as they were, but for one whose bytes are dangerous
     * while its entry doesn't say so (added before FX2's second look, or "This backup is my own" ticked on someone else's
     * file): DANGEROUS, where it is (never decoded, never handed to a helper), its drawer kept.
     */
    fun sniffed(e: FileEntry, type: FileType, mine: Boolean): FileEntry {
        if (mine) return if (type.group != FileGroup.DANGEROUS || e.kind == FileKind.DANGEROUS) e
            else e.copy(kind = FileKind.DANGEROUS, from = e.from.copy(type = type.mime))
        val mime = type.mime.takeIf { type != FileType.UNKNOWN && type != FileType.EMPTY }
        val from = e.from.copy(type = mime)
        return if (type.group == FileGroup.DANGEROUS) e.copy(kind = FileKind.DANGEROUS, from = from, filing = Filing(FilingStep.HELD, FilingWhy.DANGEROUS))
        else e.copy(from = from)
    }

    /** Someone else's file as it lands in Inbox (RestoreTaint.file's rules; kept here beside the plan that applies them). */
    internal fun theirs(e: FileEntry, now: Long): FileEntry {
        val suggest = e.drawer?.takeIf { e.state == FileState.FILED && FileIndexCodec.label(it) }
        val fields = e.fields.filterKeys { it != FileRemind.FIELD }.mapValues { (_, f) -> FileField(f.value, if (f.by == FieldBy.YOU || f.by == FieldBy.UNKNOWN) FieldBy.READ else f.by) }
        return e.copy(
            state = FileState.INBOX,
            drawer = null,
            named = false,
            tags = emptyList(),
            people = emptyList(),
            fields = fields,
            thumb = false,
            damaged = false,
            filing = Filing(FilingStep.ASK, FilingWhy.NOT_YOURS, suggest = suggest),
            from = Provenance(FileSource.BACKUP, at = now, size = e.from.size, sha256 = e.from.sha256, type = e.from.type),
            extra = emptyMap(),
        )
    }
}
