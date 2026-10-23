// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.withFileLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

// Pure (java.io around FileKeys, so the JVM tests run it; the app gives it files/files/, the Keystore's FileKeys and
// StorageManager's free space in F2). Robot Files' sealed storage (files-1.4-plan.md §2.2 to §2.7):
//
//   files.lock                   both app processes take it for every change (withFileLock: this process's threads, then
//                                the OS lock), and it is never taken twice in one thread
//   index.enc                    the catalogue (FileIndexCodec), sealed with a fresh key each write, written atomically
//                                (sealed into .index.enc.tmp, fsynced, renamed); never written over when it couldn't be
//                                read (locked, its key gone, damaged, a newer Robot's)
//   index.enc.bad                kept aside by Start over (never deleted by Robot on its own; Delete them all does)
//   journal.json                 the ids being deleted (FileJournal): plain, ids only
//   blobs/<id>.rf                a file's bytes, sealed (purpose 1)
//   thumbs/<id>.rf               its thumbnail, sealed with a key of its own (purpose 2)
//   incoming/<process>/<id>.rf   a file being added, sealed before it has an entry: ".part" while written, then renamed
//                                whole. Each process has its own folder and finishes only its own, so a process never
//                                touches another's add under way (fcntl locks are per process, so file locks can't say)
//
// Paths are built only from FileIds (32 hex digits) and the process's own name; a listing skips any other name and never
// deletes what it doesn't recognise. Nothing here decodes a file: bytes in, sealed bytes out (decoding is :files', F3).
//
// Adding (stage, then commit): the free space checked first (twice the file plus 1 GiB) and again every 8 MiB; the bytes
// sealed and hashed (SHA-256) as they stream in, never more than 200 MiB, never held whole in memory; fsynced; renamed
// whole; then, under the lock, the entry written to the index and the file moved to blobs/. Anything that fails removes
// its own partial file and leaves the index as it was. A crash is finished at the next start: a whole sealed file that has
// an entry is moved in, one without lands in Inbox as RECOVERED (its name and source are lost: the journal holds ids only),
// a ".part" is removed (cut while it was written; the sender still has it).
//
// Deleting (only your tap, §2.7): the ids journaled; each file's wrapped key overwritten in place and fsynced (its blob,
// thumbnail, and any copy still in incoming/), so what remains can't be opened; then the files unlinked; then the entries
// removed from the index; then the journal line goes. A crash at any point is finished at the next start, even while
// locked (the index part waits for the key). Robot never deletes a file by itself, and never to make room.

/** What reading the index gave. Only [Loaded] and [Empty] may be written over; Settings › Files says what the others mean. */
sealed class IndexLoad {
    /** [dropped]: entries left out as damaged (their files come back through Inbox at the next recovery). */
    data class Loaded(val index: FileIndex, val dropped: Int = 0) : IndexLoad()
    /** No index yet. */
    data object Empty : IndexLoad()
    /** robot_files_v1 can't be used now (the phone is locked, or the Keystore is busy), or the storage failed to read: not now. */
    data object Locked : IndexLoad()
    /** robot_files_v1 is gone: every file is kept; Settings › Files offers a restore, or Delete them all. */
    data object KeyGone : IndexLoad()
    /** Damaged: "Robot couldn't read your files' list", with Start over. */
    data object Unreadable : IndexLoad()
    /** A newer Robot's index ([version]: its JSON version, or its seal format): read nothing, write nothing. */
    data class TooNew(val version: Int) : IndexLoad()

    val writable: Boolean get() = this is Loaded || this == Empty

    /** The index to change, when [writable]. */
    val current: FileIndex? get() = when (this) {
        is Loaded -> index
        Empty -> FileIndex()
        else -> null
    }

    /** The problem a change would meet, or null when [writable]. */
    val problem: FileProblem? get() = when (this) {
        is Loaded, Empty -> null
        Locked -> FileProblem.LOCKED
        KeyGone -> FileProblem.KEY_GONE
        Unreadable, is TooNew -> FileProblem.INDEX
    }
}

/** What the sender or Robot says of a file being added; every value is checked again (the name cleaned, the rest validated). */
data class FileIntake(
    val name: String,
    val source: FileSource,
    /** The sending app's package, only when Android confirmed it. */
    val app: String? = null,
    /** The type sniffed from its first bytes (F5): never the name's or the sender's claim. */
    val type: String? = null,
)

/** A file sealed into incoming/ and not yet in the index: [FileStore.commit] it or [FileStore.discard] it. */
class Staged internal constructor(val id: FileId, val size: Long, val sha256: String, internal val file: File) {
    override fun toString(): String = "Staged($id, $size)"  // l10n: not text (toString for logs and tests)
}

/** What a recovery did. [waiting]: something needs the key or a readable index first; run it again after the next unlock. */
data class Recovery(
    val deletesFinished: Int = 0,
    val movedIn: Int = 0,
    val adopted: Int = 0,
    val partsRemoved: Int = 0,
    val thumbsRemoved: Int = 0,
    val missing: Int = 0,
    val waiting: Boolean = false,
    val load: IndexLoad = IndexLoad.Empty,
)

/** A restored file sealed into the restore's staging ([FileStore.sealRestored]): its plain [size], [sha256] and first bytes ([head], for a sniff). */
/**
 * A restored file sealed again: its [size], [sha256] and first bytes ([head]); FX3, [type]: what it is by the same two
 * looks intake takes (FileSniff.sniff on its first bytes, then FileSniff.Watch over the whole of it: a ZIP appended to a
 * picture or a PDF, markup deep in a text).
 */
class Resealed(val size: Long, val sha256: String, val head: ByteArray, val type: app.heyrobot.core.parse.FileType = app.heyrobot.core.parse.FileSniff.sniff(head, size)) {
    override fun toString(): String = "Resealed($size)"  // l10n: not text (toString for logs and tests)
}

/** What a delete did: [removed] files' bytes destroyed; [waiting] when their entries leave the index only at the next unlock. */
data class Deleted(val removed: Int, val waiting: Boolean)

/** The steps of an add and a delete, where a test makes a crash happen (FileStoreTest). */
internal enum class FileStep { PART_WRITTEN, STAGED, INDEX_SEALED, INDEX_WRITTEN, MOVED, JOURNALED, KEYS_DESTROYED, UNLINKED, ENTRIES_REMOVED, THUMB_SEALED }

class FileStore(
    val dir: File,
    private val keys: FileKeys,
    /** This process's name ("main", "voice"): its own incoming/ folder. */
    private val process: String,
    /** The bytes that can be allocated on Robot's storage now (StorageManager.getAllocatableBytes on the phone). */
    private val space: () -> Long = { dir.usableSpace },
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * FX3: a restore's own staging folder (outside [dir], RestoreJournal's), whose sealed files a commit waiting there (for
     * the unlock, or room) holds under this phone's robot_files_v1: [nothingSealed] counts them, so the key is never
     * replaced from under them. None for a store no restore stages for.
     */
    private val restoreStaging: File? = null,
) {
    init {
        require(PROCESS.matches(process))
    }

    /** Where a test makes the process stop, at each step (an Error, so no clean-up runs, as with a real crash). */
    internal var step: (FileStep) -> Unit = {}

    private val lockFile = File(dir, LOCK)
    private val indexFile = File(dir, INDEX)
    private val indexTmp = File(dir, ".$INDEX.tmp")
    private val badFile = File(dir, BAD)
    private val blobs = File(dir, BLOBS)
    private val thumbs = File(dir, THUMBS)
    private val incoming = File(dir, INCOMING)
    private val mine = File(incoming, process)
    private val journal = FileJournal(dir)
    /** What writeAtomic leaves when a journal write is cut (it holds ids only). */
    private val journalTmp = File(dir, ".${FileJournal.FILE}.tmp")

    /** The index last read or written by this store, by its header's id (each write makes a new one, so a change is always seen). */
    @Volatile private var cache: Pair<FileId, IndexLoad.Loaded>? = null

    // The index.

    /** The index, as this phone can read it now (entries being deleted left out). */
    fun load(): IndexLoad = locked { loadIndex() }

    /** Drops the index from RAM (D2: at screen-off with the phone locked); the next load reads it again. */
    fun drop() {
        cache = null
    }

    /**
     * One change of entries under the lock: read, [change], write (a fresh key) when anything changed. Nothing is written
     * when the index couldn't be read: the load says why. A change may move, rename, tag and file, never add or remove an
     * entry (that is [commit] and [delete]) or change what is known of a file's bytes (IllegalArgumentException).
     */
    fun update(change: (FileIndex) -> FileIndex): IndexLoad = locked {
        val load = loadIndex()
        val current = load.current ?: return@locked load
        val next = change(current)
        // F12: a travel-mode view (TravelMode.shown) is never written back: it would lose the hidden drawers' entries.
        require(next.outOfSight.isEmpty() && next.ids == current.ids && next.entries.size == current.entries.size)
        val was = current.entries.associateBy { it.id }
        for (e in next.entries) {
            val w = was.getValue(e.id)
            require(e.from.size == w.from.size && e.from.sha256 == w.from.sha256)
        }
        if (next == current) return@locked load
        writeIndex(next)
        IndexLoad.Loaded(next)
    }

    /** The entry whose bytes have [sha256], for "You already have this file" (§4.1). */
    fun duplicateOf(sha256: String): FileEntry? = (load() as? IndexLoad.Loaded)?.index?.entries?.firstOrNull { it.from.sha256 == sha256 }

    /**
     * What the files take, read from the sizes of blobs/ alone: no key, no index, nothing decrypted (Settings' row, also
     * while the phone is locked or the key is gone), each file's own size worked out from its sealed size. Files being
     * deleted aren't counted; adds still in incoming/ aren't either until they move in.
     */
    fun usage(): FileUsage {
        val deleting = journal.deleting()
        val sealed = listing(blobs).filter { idOf(it) !in deleting }
        return FileUsage(sealed.size, sealed.sumOf { FileSeal.plainSize(it.length()) })
    }

    // Adding.

    /**
     * Seals [input] into this process's incoming/ folder, hashing it as it goes: at most 200 MiB, never held whole. [declared]:
     * the size the sender claims (untrusted; checked against the free space first, the real size is what counts). Throws
     * [FileException]: LOW_SPACE (with what is needed), TOO_BIG, FULL, LOCKED, KEY_GONE, INDEX, IO. Nothing is left behind
     * when it throws. [input] is read, not closed.
     */
    fun stage(input: InputStream, declared: Long? = null): Staged {
        locked {
            val load = loadIndex()
            load.problem?.let { throw FileException(it) }
            if (load.current!!.entries.size >= FileLimits.MAX_FILES) throw FileException(FileProblem.FULL)
            if (declared != null && declared > FileLimits.MAX_FILE) throw FileException(FileProblem.TOO_BIG)
            val need = FileLimits.spaceToAdd(declared ?: 0)
            val free = free()
            if (free < need) throw FileException(FileProblem.LOW_SPACE, need - free)
            // A new cabinet starts with an empty index, written here under the lock: robot_files_v1 is made with it, so two
            // processes never make it at once, and every stream after it is sealed with create = false.
            if (load == IndexLoad.Empty && nothingSealed()) {
                try {
                    writeIndex(FileIndex())
                } catch (x: FileException) {
                    // F5: an old robot_files_v1 that can't be used any more (invalidated when the screen lock was removed)
                    // over a cabinet with nothing sealed in it: nothing can be lost with it, and without a new one no file
                    // could ever be added (and Settings, seeing no index, would offer nothing to delete). Replaced once,
                    // here alone: never while anything is sealed, and never for "not now".
                    if (x.problem != FileProblem.KEY_GONE) throw x
                    keys.delete()
                    // FX2 (K2): codes only, so a report can tell the key was replaced (a: KEY_GONE, b: 1 replaced).
                    app.heyrobot.core.VoiceLog.add(app.heyrobot.core.VoiceEvent.FILE_KEY, FileProblem.KEY_GONE.id, 1)
                    writeIndex(FileIndex())
                }
            }
        }
        val id = FileId.new(random)
        val part = File(mine, "${id.hex}$SEALED_EXT$PART_EXT")
        val whole = File(mine, "${id.hex}$SEALED_EXT")
        INFLIGHT.add(whole.absolutePath)
        var written = 0L
        try {
            mine.mkdirs()
            val md = MessageDigest.getInstance("SHA-256")
            sealTo(part, SealPurpose.FILE, id, create = false).use { w ->
                val buf = ByteArray(BUFFER)
                var nextCheck = FileLimits.SPACE_CHECK
                while (true) {
                    val k = input.read(buf)
                    if (k < 0) break
                    w.write(buf, 0, k)
                    md.update(buf, 0, k)
                    written += k
                    if (written >= nextCheck) {
                        val free = free()
                        if (free < FileLimits.FREE_MARGIN) throw FileException(FileProblem.LOW_SPACE, FileLimits.spaceToAdd(written) - free)
                        nextCheck += FileLimits.SPACE_CHECK
                    }
                }
                buf.fill(0)
            }
            step(FileStep.PART_WRITTEN)
            if (!part.renameTo(whole)) throw IOException()
            syncDir(mine)
            step(FileStep.STAGED)
            return Staged(id, written, md.digest().joinToString("") { "%02x".format(it) }, whole)
        } catch (x: Exception) {
            destroy(part)
            destroy(whole)
            INFLIGHT.remove(whole.absolutePath)
            throw failure(x, written)
        }
    }

    /**
     * [staged] gets its entry (Inbox, kind unknown, provenance from [intake], now) and moves into blobs/. Throws
     * [FileException] (FULL, LOCKED, KEY_GONE, INDEX, LOW_SPACE, IO), and then the staged file is gone too: the add failed
     * whole, and the sender still has it.
     */
    fun commit(staged: Staged, intake: FileIntake): FileEntry = locked {
        val entry = try {
            val load = loadIndex()
            load.problem?.let { throw FileException(it) }
            val index = load.current!!
            if (index.entries.size >= FileLimits.MAX_FILES) throw FileException(FileProblem.FULL)
            // F6 (§4.1 step 3): a file whose sniffed type could install or run something is DANGEROUS from the start, held
            // in Inbox: never filed, parsed or opened by Robot.
            val dangerous = intake.type?.let { t -> FileType.entries.firstOrNull { it.mime == t } }?.group == FileGroup.DANGEROUS
            val entry = FileEntry(
                id = staged.id,
                name = FileIndexCodec.cleanName(intake.name),
                kind = if (dangerous) FileKind.DANGEROUS else FileKind.UNKNOWN,
                state = FileState.INBOX,
                filing = if (dangerous) Filing(FilingStep.HELD, FilingWhy.DANGEROUS) else null,
                from = Provenance(intake.source, at = now(), size = staged.size, sha256 = staged.sha256,
                    app = intake.app?.takeIf { it.length <= FileIndexCodec.MAX_APP && FileIndexCodec.PACKAGE.matches(it) },
                    type = intake.type?.takeIf { it.length <= FileIndexCodec.MAX_TYPE && FileIndexCodec.MIME.matches(it) }),
            )
            check(staged.file.parentFile == mine && staged.file.isFile && index.entry(staged.id) == null)
            writeIndex(FileIndex(index.entries + entry, index.extra))
            entry
        } catch (x: Exception) {
            discardLocked(staged)
            throw failure(x, staged.size)
        }
        // From here the file is in the index: a move that fails leaves it in incoming/, where it is read from and the next
        // recovery moves it in. It is never removed.
        step(FileStep.INDEX_WRITTEN)
        try { moveIn(staged.file, staged.id) } catch (_: Exception) {}
        step(FileStep.MOVED)
        INFLIGHT.remove(staged.file.absolutePath)
        entry
    }

    /** Seals [input] and gives it an entry: [stage] then [commit]. */
    fun add(input: InputStream, intake: FileIntake, declared: Long? = null): FileEntry = commit(stage(input, declared), intake)

    /** [staged] isn't wanted (a duplicate you didn't add): its key destroyed, its bytes removed. */
    fun discard(staged: Staged) = locked { discardLocked(staged) }

    private fun discardLocked(staged: Staged) {
        if (staged.file.parentFile == mine) destroy(staged.file)
        INFLIGHT.remove(staged.file.absolutePath)
    }

    // Reading.

    /**
     * File [id]'s bytes as they stream: only chunks whose tags check are released, so a [FileException] part way means
     * what was read is an authentic beginning and the rest isn't there (drop it). MISSING when there is no such file or
     * it is being deleted. Close it when done.
     */
    fun open(id: FileId): FileSealReader = locked {
        if (id in journal.deleting()) throw FileException(FileProblem.MISSING)
        val f = blobOf(id) ?: throw FileException(FileProblem.MISSING)
        val input = try { FileInputStream(f) } catch (_: IOException) { throw FileException(FileProblem.MISSING) }
        FileSeal.reader(BufferedInputStream(input, BUFFER), SealPurpose.FILE, id, keys)
    }

    /** File [id]'s bytes whole, or a [FileException] and nothing (over [max]: TOO_BIG). For :files' 64 MiB read. */
    fun readAll(id: FileId, max: Long): ByteArray = open(id).use { it.readAll(max) }

    // Thumbnails.

    /** Seals [bytes] (a thumbnail :files made, at most 64 KiB) as [id]'s, replacing any. The caller marks the entry. */
    fun putThumb(id: FileId, bytes: ByteArray) = locked {
        if (bytes.size > FileLimits.MAX_THUMB) throw FileException(FileProblem.TOO_BIG)
        if (id in journal.deleting() || blobOf(id) == null) throw FileException(FileProblem.MISSING)
        val free = free()
        if (free < FileLimits.FREE_MARGIN) throw FileException(FileProblem.LOW_SPACE, FileLimits.FREE_MARGIN - free)
        thumbs.mkdirs()
        val tmp = File(thumbs, ".${id.hex}$SEALED_EXT$TMP_EXT")
        try {
            sealTo(tmp, SealPurpose.THUMBNAIL, id, create = false).use { it.write(bytes) }
            step(FileStep.THUMB_SEALED)
            val target = File(thumbs, "${id.hex}$SEALED_EXT")
            destroy(target)
            if (!tmp.renameTo(target)) throw IOException()
            syncDir(thumbs)
        } catch (x: Exception) {
            destroy(tmp)
            throw failure(x, bytes.size.toLong())
        }
    }

    /** [id]'s thumbnail, or null when it has none; a damaged one throws [FileException]. */
    fun thumb(id: FileId): ByteArray? = locked {
        if (id in journal.deleting()) return@locked null
        val f = File(thumbs, "${id.hex}$SEALED_EXT")
        val input = try { FileInputStream(f) } catch (_: IOException) { return@locked null }
        FileSeal.readAll(BufferedInputStream(input, BUFFER), SealPurpose.THUMBNAIL, id, keys)
    }

    // Deleting.

    /**
     * Deletes [ids] (your tap: a file's page, a delete card, a clean-up suggestion). Journaled first; each file's wrapped
     * keys are destroyed, then its bytes, then its entry. When the index can't be written now the entries go at the next
     * unlock ([Deleted.waiting]); the bytes are gone either way.
     */
    fun delete(ids: Collection<FileId>): Deleted = locked {
        val set = ids.toSet()
        if (set.isEmpty()) return@locked Deleted(0, false)
        journal.add(set)
        step(FileStep.JOURNALED)
        finishDeletes(set)
    }

    /** The journaled deletes from [ids] on: keys, then bytes, then entries, then the journal. */
    private fun finishDeletes(ids: Set<FileId>): Deleted {
        val copies = ids.associateWith { copiesOf(it) }
        val files = copies.values.flatten()
        val load = readIndex()
        val inIndex = load.current?.ids.orEmpty()
        val removed = ids.count { copies.getValue(it).isNotEmpty() || it in inIndex }
        files.forEach(::destroyKey)
        step(FileStep.KEYS_DESTROYED)
        files.forEach { it.delete() }
        listOf(blobs, thumbs).plus(incomingDirs()).forEach(::syncDir)
        step(FileStep.UNLINKED)
        val index = load.current ?: return Deleted(removed, waiting = true)
        val left = index.entries.filter { it.id !in ids }
        if (left.size != index.entries.size) {
            try {
                writeIndex(FileIndex(left, index.extra))
            } catch (_: Exception) {
                // The bytes are gone; the entries go at the next recovery (the journal keeps them hidden until then).
                return Deleted(removed, waiting = true)
            }
        }
        step(FileStep.ENTRIES_REMOVED)
        journal.remove(ids)
        return Deleted(removed, waiting = false)
    }

    /**
     * Delete them all (Settings › Files, its own dialog; after robot_files_v1 was lost, or by your choice): every key
     * destroyed, the index and the list kept aside, then every file, the journal, and robot_files_v1 itself, so the next
     * file starts a new cabinet.
     */
    fun deleteAll() = locked {
        val all = listing(blobs) + listing(thumbs) + listing(thumbs, tmp = true) + incomingDirs().flatMap { listing(it) + listing(it, part = true) } +
            listOf(indexFile, badFile, indexTmp)
        all.forEach(::destroyKey)
        indexFile.delete()
        badFile.delete()
        indexTmp.delete()
        all.forEach { it.delete() }
        File(dir, FileJournal.FILE).delete()
        journalTmp.delete()
        cache = null
        try { keys.delete() } catch (_: Exception) {}
    }

    // Recovery.

    /**
     * At each process's start and after each unlock: finishes what a crash cut short. Journaled deletes are finished;
     * this process's adds that have an entry are moved in, whole ones without an entry land in Inbox (RECOVERED), cut ones
     * (".part") are removed; another process's adds that have an entry are moved in; sealed files with no entry (an
     * entry dropped as damaged, an older index, Start over) land in Inbox; thumbnails with no entry go (they are made
     * again). Never anything else: a file is never thrown away.
     */
    fun recover(): Recovery = locked { recoverLocked() }

    private fun recoverLocked(): Recovery {
        indexTmp.delete()
        journalTmp.delete()
        listing(thumbs, tmp = true).forEach { it.delete() }
        var r = Recovery()
        // Deletes first: what is being deleted must never come back through Inbox.
        val deleting = journal.deleting()
        if (deleting.isNotEmpty()) {
            val d = finishDeletes(deleting)
            r = r.copy(deletesFinished = if (d.waiting) 0 else d.removed, waiting = d.waiting)
        }
        val load = readIndex()
        val index = load.current
        if (index == null) {
            // Locked, gone, damaged or newer: nothing more can be done (or should be) until the index can be read.
            return r.copy(waiting = r.waiting || load == IndexLoad.Locked, load = load)
        }
        var entries = index.entries
        val known = entries.mapTo(HashSet()) { it.id }
        // This process's adds.
        for (f in listing(mine, part = true)) {
            if (f.absolutePath.removeSuffix(PART_EXT) in INFLIGHT) continue
            destroy(f)
            r = r.copy(partsRemoved = r.partsRemoved + 1)
        }
        val orphans = ArrayList<Pair<FileId, File>>()
        for (dirIn in incomingDirs()) for (f in listing(dirIn)) {
            if (f.absolutePath in INFLIGHT) continue
            val id = idOf(f) ?: continue
            when {
                id in known -> { moveIn(f, id); r = r.copy(movedIn = r.movedIn + 1) }
                dirIn == mine -> orphans += id to f
            }
        }
        // Sealed files with no entry.
        for (f in listing(blobs)) {
            val id = idOf(f) ?: continue
            if (id !in known) orphans += id to f
        }
        val adopted = ArrayList<FileEntry>()
        var waiting = false
        for ((id, f) in orphans) {
            val e = try {
                adopt(id, f)
            } catch (x: FileException) {
                // The key can't be used now (or is gone): the rest waits for it.
                waiting = true
                break
            }
            adopted += e
            known += id
        }
        if (adopted.isNotEmpty()) {
            try {
                writeIndex(FileIndex(entries + adopted, index.extra))
                entries = entries + adopted
                for ((id, f) in orphans) if (f.parentFile != blobs && id in known) moveIn(f, id)
            } catch (_: Exception) {
                // Not now (the key, the storage): the files stay where they are, and the next recovery adopts them.
                adopted.forEach { known -= it.id }
                adopted.clear()
                waiting = true
            }
        }
        // Thumbnails of nothing.
        var thumbsRemoved = 0
        for (f in listing(thumbs)) {
            val id = idOf(f) ?: continue
            if (id !in known) {
                destroy(f)
                thumbsRemoved++
            }
        }
        val missing = entries.count { blobOf(it.id) == null }
        return r.copy(movedIn = r.movedIn, adopted = adopted.size, thumbsRemoved = thumbsRemoved, missing = missing,
            waiting = r.waiting || waiting, load = loadIndex())
    }

    /** An entry for sealed bytes found without one: read through, checked and hashed; damaged ones are kept and marked. */
    private fun adopt(id: FileId, f: File): FileEntry {
        val at = f.lastModified().coerceAtLeast(0)
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileSeal.reader(BufferedInputStream(FileInputStream(f), BUFFER), SealPurpose.FILE, id, keys).use { r ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val k = r.read(buf)
                    if (k < 0) break
                    md.update(buf, 0, k)
                    size += k
                }
                buf.fill(0)
            }
            FileEntry(id, "", FileKind.UNKNOWN, FileState.INBOX, Provenance(FileSource.RECOVERED, at, size, md.digest().joinToString("") { "%02x".format(it) }))
        } catch (x: FileException) {
            if (x.problem == FileProblem.LOCKED || x.problem == FileProblem.KEY_GONE) throw x
            FileEntry(id, "", FileKind.UNKNOWN, FileState.INBOX, Provenance(FileSource.RECOVERED, at, 0), damaged = true)
        } catch (_: IOException) {
            FileEntry(id, "", FileKind.UNKNOWN, FileState.INBOX, Provenance(FileSource.RECOVERED, at, 0), damaged = true)
        }
    }

    /**
     * Start over (§2.4: Settings › Files, after "Robot couldn't read your files' list", or a newer Robot's): the index is
     * kept aside as index.enc.bad (an older one there replaced) and a new one made from the sealed files, which land in
     * Inbox with their bytes and ids and without their names. Only for an index that is damaged or newer: never for a
     * locked phone or a lost key (null then, and nothing changes).
     */
    fun startOver(): Recovery? = locked {
        val load = loadIndex()
        if (load != IndexLoad.Unreadable && load !is IndexLoad.TooNew) return@locked null
        destroy(badFile)
        if (!indexFile.renameTo(badFile)) throw FileException(FileProblem.IO)
        syncDir(dir)
        cache = null
        recoverLocked()
    }

    // Restoring (F11, core/backup FilesBackup and RestoreJournal): a backup's files sealed again under this phone's
    // robot_files_v1 with fresh stream keys, staged in the restore's own folder (outside this one: no listing here ever sees
    // them, so a cut restore never lands in Inbox), then committed in one repeatable step.

    /**
     * Before a restore seals anything: the index readable (else its [FileException]: LOCKED, KEY_GONE, INDEX) and, for a
     * cabinet with nothing sealed yet, started as an add starts one (robot_files_v1 made with an empty index, under the lock;
     * an old one that can't be used any more replaced, nothing sealed being lost with it). The index as it is now.
     */
    fun prepareRestore(): FileIndex = locked {
        val load = loadIndex()
        load.problem?.let { throw FileException(it) }
        if (load == IndexLoad.Empty && nothingSealed()) {
            try {
                writeIndex(FileIndex())
            } catch (x: FileException) {
                if (x.problem != FileProblem.KEY_GONE) throw x
                keys.delete()
                // FX2 (K2): as in [stage], logged as codes.
                app.heyrobot.core.VoiceLog.add(app.heyrobot.core.VoiceEvent.FILE_KEY, FileProblem.KEY_GONE.id, 1)
                writeIndex(FileIndex())
            }
        }
        load.current!!
    }

    /**
     * Seals [input] (a restored file's plain bytes, read to its end) into [staging] as "<id>.rf" (".part" while written)
     * with a fresh stream key, hashing it as it streams: at most 200 MiB, never held whole, the free space checked every
     * 8 MiB. Throws [FileException], and leaves nothing behind then.
     */
    fun sealRestored(staging: File, id: FileId, input: InputStream): Resealed {
        require(!inside(staging))
        staging.mkdirs()
        val part = File(staging, "${id.hex}$SEALED_EXT$PART_EXT")
        val whole = File(staging, "${id.hex}$SEALED_EXT")
        val md = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(app.heyrobot.core.parse.FileSniff.PREFIX)
        // FX3: the second look intake takes, as the bytes stream by.
        val watch = app.heyrobot.core.parse.FileSniff.Watch()
        var written = 0L
        try {
            sealTo(part, SealPurpose.FILE, id, create = false).use { w ->
                val buf = ByteArray(BUFFER)
                var nextCheck = FileLimits.SPACE_CHECK
                while (true) {
                    val k = input.read(buf)
                    if (k < 0) break
                    w.write(buf, 0, k)
                    md.update(buf, 0, k)
                    watch.feed(buf, 0, k)
                    if (written < head.size) System.arraycopy(buf, 0, head, written.toInt(), minOf(k.toLong(), head.size - written).toInt())
                    written += k
                    if (written >= nextCheck) {
                        val free = free()
                        if (free < FileLimits.FREE_MARGIN) throw FileException(FileProblem.LOW_SPACE, FileLimits.spaceToAdd(written) - free)
                        nextCheck += FileLimits.SPACE_CHECK
                    }
                }
                buf.fill(0)
            }
            destroy(whole)
            if (!part.renameTo(whole)) throw IOException()
            syncDir(staging)
            val first = head.copyOf(minOf(written, head.size.toLong()).toInt())
            val type = app.heyrobot.core.parse.FileSniff.sniff(first, written).let { t -> watch.hidden(t) ?: t }
            return Resealed(written, md.digest().joinToString("") { "%02x".format(it) }, first, type)
        } catch (x: Exception) {
            destroy(part)
            throw failure(x, written)
        } finally {
            head.fill(0)
            watch.clear()
        }
    }

    /** Seals [index] (the entries a restore adds: names, fields, text) into [file] with a fresh key, never in the clear. */
    fun sealRestoreIndex(file: File, index: FileIndex) {
        require(!inside(file))
        val json = FileIndexCodec.encode(index)
        val tmp = File(file.parentFile, ".${file.name}$TMP_EXT")
        try {
            file.parentFile?.mkdirs()
            sealTo(tmp, SealPurpose.INDEX, FileId.new(random), create = false).use { it.write(json) }
            destroy(file)
            if (!tmp.renameTo(file)) throw IOException()
        } catch (x: Exception) {
            destroy(tmp)
            throw failure(x, json.size.toLong())
        } finally {
            json.fill(0)
        }
    }

    /** The entries [sealRestoreIndex] sealed in [file]. Throws [FileException] (MISSING, LOCKED: not now, DAMAGED). */
    fun readRestoreIndex(file: File): FileIndex {
        val input = try { FileInputStream(file) } catch (_: IOException) { throw FileException(FileProblem.MISSING) }
        val json = FileSeal.readAll(BufferedInputStream(input, BUFFER), SealPurpose.INDEX, null, keys, FileLimits.MAX_INDEX)
        try {
            return (FileIndexCodec.decode(json) as? IndexParse.Ok)?.index ?: throw FileException(FileProblem.DAMAGED)
        } finally {
            json.fill(0)
        }
    }

    /**
     * A restore's commit (its RestoreJournal line at COMMIT: repeatable until it is done). Under the lock: [delete] (this
     * phone's files a Replace removes, never one of [add]'s ids) deleted as Delete does, journaled; then the index written
     * with [add]'s entries (one already in it takes the backup's entry and keeps its own thumbnail; one whose bytes are
     * neither in [staging] nor here is left out; past 5,000 files or the index's cap the last ones are left out) and its
     * top-level keys from [extra]; then each staged file moved into blobs/, and what is left in [staging] destroyed. Throws
     * [FileException] when it can't now (LOCKED, KEY_GONE, INDEX, IO): nothing is lost, and the next run finishes it.
     * The number of files added.
     */
    fun restoreIn(staging: File, add: List<FileEntry>, delete: Set<FileId>, extra: (Map<String, Any?>) -> Map<String, Any?>): Int = locked {
        require(!inside(staging))
        val adding = add.mapTo(HashSet()) { it.id }
        val gone = delete - adding
        if (gone.isNotEmpty()) {
            journal.add(gone)
            if (finishDeletes(gone).waiting) throw FileException(FileProblem.LOCKED)
        }
        val load = loadIndex()
        val index = load.current ?: throw FileException(load.problem ?: FileProblem.IO)
        fun staged(id: FileId) = File(staging, "${id.hex}$SEALED_EXT").isFile
        val byId = add.associateBy { it.id }
        val entries = index.entries.map { e -> byId[e.id]?.copy(thumb = e.thumb) ?: e }
        var fresh = add.filter { it.id !in index.ids && (staged(it.id) || blobOf(it.id) != null) }.distinctBy { it.id }
            .take((FileLimits.MAX_FILES - entries.size).coerceAtLeast(0))
        while (true) {
            val next = FileIndex(entries + fresh, extra(index.extra))
            try {
                if (next != index) writeIndex(next)
                break
            } catch (x: FileException) {
                // Over the index's 32 MiB (the plan checked it; another add came since): the last ones wait outside.
                if (x.problem != FileProblem.TOO_BIG || fresh.isEmpty()) throw x
                fresh = fresh.dropLast(maxOf(1, fresh.size / 10))
            }
        }
        step(FileStep.INDEX_WRITTEN)
        val known = entries.mapTo(HashSet()) { it.id } + fresh.map { it.id }
        for (f in listing(staging)) idOf(f)?.let { id -> if (id in known) { moveIn(f, id); step(FileStep.MOVED) } }
        for (f in listing(staging) + listing(staging, part = true)) destroy(f)
        fresh.size
    }

    /** Every sealed file a restore left in [staging] (staged files, their parts, the sealed entries): keys destroyed, then gone. */
    fun discardRestore(staging: File) {
        if (inside(staging)) return
        for (f in staging.listFiles().orEmpty()) if (f.isFile && (f.name.endsWith(SEALED_EXT) || f.name.endsWith(PART_EXT) || f.name.endsWith(TMP_EXT))) destroy(f)
    }

    /** Whether [f] is in this store's own folder (a restore never stages there). */
    private fun inside(f: File): Boolean = f.absoluteFile.normalize().toPath().startsWith(dir.absoluteFile.normalize().toPath())

    // Inside the lock.

    private fun <T> locked(block: () -> T): T {
        dir.mkdirs()
        return withFileLock(lockFile, block)
    }

    /** The index without the entries being deleted. */
    private fun loadIndex(): IndexLoad {
        val load = readIndex()
        if (load !is IndexLoad.Loaded) return load
        val deleting = journal.deleting()
        if (deleting.isEmpty()) return load
        return load.copy(index = load.index.copy(entries = load.index.entries.filter { it.id !in deleting }))
    }

    private fun readIndex(): IndexLoad {
        if (!indexFile.isFile) {
            cache = null
            return IndexLoad.Empty
        }
        return try {
            FileInputStream(indexFile).use { input ->
                val buffered = BufferedInputStream(input, BUFFER)
                val (header, bytes) = FileSeal.readHeader(buffered)
                cache?.let { (id, loaded) -> if (id == header.id) return loaded }
                val json = FileSeal.reader(ConcatInput(bytes, buffered), SealPurpose.INDEX, null, keys).use { it.readAll(FileLimits.MAX_INDEX) }
                try {
                    when (val p = FileIndexCodec.decode(json)) {
                        is IndexParse.Ok -> IndexLoad.Loaded(p.index, p.dropped).also { cache = header.id to it }
                        is IndexParse.TooNew -> IndexLoad.TooNew(p.version)
                        IndexParse.Damaged -> IndexLoad.Unreadable
                    }
                } finally {
                    json.fill(0)
                }
            }
        } catch (x: FileException) {
            when (x.problem) {
                FileProblem.LOCKED -> IndexLoad.Locked
                FileProblem.KEY_GONE -> IndexLoad.KeyGone
                FileProblem.NEWER -> IndexLoad.TooNew(FileSeal.FORMAT + 1)
                else -> IndexLoad.Unreadable
            }
        } catch (_: IOException) {
            // The storage failed to read it: not now, and never a reason to write over it.
            IndexLoad.Locked
        }
    }

    /** Seals [index] with a fresh key into .index.enc.tmp, fsyncs, and renames it over index.enc. */
    private fun writeIndex(index: FileIndex) {
        require(index.outOfSight.isEmpty()) // F12: never a travel-mode view.
        val json = FileIndexCodec.encode(index)
        val id = FileId.new(random)
        try {
            sealTo(indexTmp, SealPurpose.INDEX, id, create = nothingSealed()).use { it.write(json) }
            step(FileStep.INDEX_SEALED)
            if (!indexTmp.renameTo(indexFile)) throw IOException()
            syncDir(dir)
            cache = id to IndexLoad.Loaded(index)
        } catch (x: Exception) {
            indexTmp.delete()
            throw x
        } finally {
            json.fill(0)
        }
    }

    /** A new sealed stream into [f] (created or emptied), fsynced when closed; nothing is left open if it can't start. */
    private fun sealTo(f: File, purpose: SealPurpose, id: FileId, create: Boolean): FileSealWriter {
        val out = SyncedOutput(FileOutputStream(f))
        return try {
            FileSeal.writer(out, purpose, id, keys, create, random)
        } catch (x: Exception) {
            try { out.close() } catch (_: IOException) {}
            throw x
        }
    }

    /** Moves [f] (a whole sealed file in incoming/) to blobs/<id>.rf, unless that is there already (then [f] goes). */
    private fun moveIn(f: File, id: FileId) {
        blobs.mkdirs()
        val target = File(blobs, "${id.hex}$SEALED_EXT")
        if (f == target) return
        if (target.exists()) {
            destroy(f)
            return
        }
        if (!f.renameTo(target)) throw FileException(FileProblem.IO)
        syncDir(blobs)
    }

    /** Where [id]'s sealed bytes are: blobs/, or still in an incoming/ folder (an add cut after its entry). */
    private fun blobOf(id: FileId): File? {
        File(blobs, "${id.hex}$SEALED_EXT").takeIf { it.isFile }?.let { return it }
        return incomingDirs().map { File(it, "${id.hex}$SEALED_EXT") }.firstOrNull { it.isFile }
    }

    /** Every sealed copy of [id]: blob, thumbnail, incoming (whole or part). */
    private fun copiesOf(id: FileId): List<File> =
        (listOf(File(blobs, "${id.hex}$SEALED_EXT"), File(thumbs, "${id.hex}$SEALED_EXT"), File(thumbs, ".${id.hex}$SEALED_EXT$TMP_EXT")) +
            incomingDirs().flatMap { listOf(File(it, "${id.hex}$SEALED_EXT"), File(it, "${id.hex}$SEALED_EXT$PART_EXT")) }).filter { it.exists() }

    private fun incomingDirs(): List<File> = incoming.listFiles().orEmpty().filter { it.isDirectory && PROCESS.matches(it.name) }.sortedBy { it.name }

    /** The sealed files in [d] by name: "<id>.rf" ([part]: "<id>.rf.part"; [tmp]: ".<id>.rf.tmp"). Nothing else is ever listed. */
    private fun listing(d: File, part: Boolean = false, tmp: Boolean = false): List<File> = d.listFiles().orEmpty().filter { f ->
        f.isFile && when {
            part -> f.name.endsWith(SEALED_EXT + PART_EXT) && FileId.of(f.name.removeSuffix(SEALED_EXT + PART_EXT)) != null
            tmp -> f.name.startsWith(".") && f.name.endsWith(SEALED_EXT + TMP_EXT) && FileId.of(f.name.removePrefix(".").removeSuffix(SEALED_EXT + TMP_EXT)) != null
            else -> idOf(f) != null
        }
    }.sortedBy { it.name }

    private fun idOf(f: File): FileId? = if (f.name.endsWith(SEALED_EXT)) FileId.of(f.name.removeSuffix(SEALED_EXT)) else null

    /** Whether nothing sealed exists yet: then, and only then, robot_files_v1 may be made. */
    private fun nothingSealed(): Boolean = !indexFile.exists() && !badFile.exists() && !indexTmp.exists() &&
        // FX2 (files-1.4-review.md K2): any file in these folders counts, not only the names a listing takes (a ".part", a
        // ".tmp", a name this version doesn't know): each may be sealed with the key a replacement would destroy.
        listOf(blobs, thumbs).all { anyFile(it).not() } && incomingDirs().all { anyFile(it).not() } &&
        // FX3: and a restore's staging, its sealed files (a staged one, its part, the sealed entries) the commit still holds.
        restoreStaging.let { st -> st == null || st.listFiles().orEmpty().none { it.isFile && (it.name.endsWith(SEALED_EXT) || it.name.endsWith(PART_EXT) || it.name.endsWith(TMP_EXT)) } }

    private fun anyFile(d: File): Boolean = d.listFiles().orEmpty().any { it.isFile }

    private fun free(): Long = try { space() } catch (_: Exception) { dir.usableSpace }

    /** A failure as the caller hears it: its own FileException, else low space (when that is why) or IO. */
    private fun failure(x: Exception, size: Long): FileException = when {
        x is FileException -> x
        free() < FileLimits.spaceToAdd(size) -> FileException(FileProblem.LOW_SPACE, (FileLimits.spaceToAdd(size) - free()).coerceAtLeast(0))
        else -> FileException(FileProblem.IO)
    }

    companion object {
        const val LOCK = "files.lock"
        const val INDEX = "index.enc"
        const val BAD = "index.enc.bad"
        const val BLOBS = "blobs"
        const val THUMBS = "thumbs"
        const val INCOMING = "incoming"
        const val SEALED_EXT = ".rf"
        const val PART_EXT = ".part"
        const val TMP_EXT = ".tmp"
        private const val BUFFER = 65_552
        private val PROCESS = Regex("[a-z]{1,16}")

        /** This process's adds under way, by their whole file's path: its own recovery leaves them alone. */
        private val INFLIGHT: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Tests: this process ended (its adds under way are no longer under way). */
        internal fun forgetInflight() = INFLIGHT.clear()

        /**
         * Overwrites [f]'s wrapped key (and its size) in place and fsyncs, so it can't be opened again, before it is
         * deleted. A file shorter than a header holds no whole key and is just deleted.
         */
        internal fun destroyKey(f: File) {
            if (!f.isFile) return
            try {
                RandomAccessFile(f, "rw").use { raf ->
                    if (raf.length() >= FileSeal.HEADER_SIZE) {
                        raf.seek(FileSeal.WRAPPED_AT.toLong())
                        raf.write(ByteArray(2 + FileSeal.WRAPPED))
                        raf.fd.sync()
                    }
                }
            } catch (_: IOException) {}
        }

        /** [destroyKey], then delete. */
        internal fun destroy(f: File) {
            if (!f.exists()) return
            destroyKey(f)
            f.delete()
        }

        /** Best effort: the folder's entries (a rename, a delete) on storage. */
        internal fun syncDir(d: File) {
            try {
                FileChannel.open(d.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Exception) {}
        }
    }
}

/** A file written whole: buffered, and fsynced before it is closed (so a rename after it never shows a half-written file). */
private class SyncedOutput(private val file: FileOutputStream) : BufferedOutputStream(file, 65_552) {
    override fun close() {
        try {
            flush()
            file.fd.sync()
        } finally {
            file.close()
        }
    }
}

/** [head] and then [tail]: the header already read, then the rest of the file. */
private class ConcatInput(private val head: ByteArray, private val tail: InputStream) : InputStream() {
    private var pos = 0

    override fun read(): Int = if (pos < head.size) head[pos++].toInt() and 0xFF else tail.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos < head.size) {
            val k = minOf(len, head.size - pos)
            System.arraycopy(head, pos, b, off, k)
            pos += k
            return k
        }
        return tail.read(b, off, len)
    }

    override fun close() = tail.close()
}
