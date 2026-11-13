// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.Note
import app.heyrobot.core.NoteCodec
import app.heyrobot.core.NoteDelete
import app.heyrobot.core.NoteEnvelope
import app.heyrobot.core.NoteStore
import app.heyrobot.core.RLog
import app.heyrobot.core.SealedEnvelope
import app.heyrobot.core.SealedOpen
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.readForUpdate
import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomicBytes
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// MAIN AND VOICE PROCESSES. Robot Notes sealed with Robot's own keys (seal-notes; seal-local-data.md step 4), as History
// is (sys/HistoryVault; the framing core SealedEnvelope and the folder's handling sys/SealedDir are shared with it).
//
// The keys: robot_notes_v1 (setUnlockedDeviceRequired) seals each note; robot_notes_lock_v1, without it, seals a note
// taken while the phone is locked ("take a note" over the lock screen), merged at the next unlock. A note is small and
// whole: the journal holds the whole note (a note can't be opened, so not changed, while locked). Only these two aliases
// are asked for. A key Android invalidated can never open what it sealed: those files are kept aside and a new key made.
//
// The files, under files/notes/ (credential-encrypted storage, never in Android's backup):
//   <id>.enc          a note, sealed (NoteEnvelope); the name and the header are in the additional data
//   <id>.locked.enc   taken while locked, under the lock-screen key; merged into <id>.enc at the next unlock
//   <id>.json         Robot 1.2's plain note: sealed at the first unlock, read back and compared, then removed
//   *.bad, *.bad2..9  kept aside (key gone, or damaged); never deleted by Robot on its own
// Both app processes take files/robot/notes.lock for every read and change. Nothing here logs a note's words or id.

/** Notes' two Keystore keys, named here alone. */
object NoteKeyAliases {
    const val UNLOCKED = "robot_notes_v1"
    const val LOCK_SCREEN = "robot_notes_lock_v1"
}

/** Notes as they open now: [notes] newest change first; [aside] kept aside; [notNow] the key can't open until you unlock; [tooNew] a newer Robot's. */
data class NotesRead(val notes: List<Note>, val aside: Int = 0, val notNow: Int = 0, val tooNew: Int = 0)

/** From [NotesVault.whole]: a note the key can't open now ([notNow]) or a newer Robot's ([tooNew]). */
class NotesNotWhole(val notNow: Int, val tooNew: Int) : Exception()

class NotesVault(
    val dir: File,
    keys: MemoryKeys,
    private val lockFile: File,
    private val locked: () -> Boolean,
    /** Tests only: told at each step ("seal", "written", "verified", "saved", "merged"), to stop there as a crash would. */
    private val step: (String) -> Unit = {},
) : NoteDelete.Store {
    private val sd = SealedDir(dir, keys, "notes") // l10n: not text (a log code)

    /** Opened notes by file name, with the file's time and size: a file that didn't change isn't opened again. */
    private val cache = ConcurrentHashMap<String, Triple<Long, Long, Note>>()

    private fun sealedFile(id: String) = File(dir, id + SealedEnvelope.SEALED)
    private fun journalFile(id: String) = File(dir, id + SealedEnvelope.LOCKED)
    private fun plainFile(id: String) = File(dir, id + SealedEnvelope.PLAIN)
    private fun idOf(name: String) = NoteEnvelope.frame.idOf(name)

    /** Every file name a note may have here (a restore's Replace deletes them all). */
    fun namesOf(id: String): List<String> = listOf(SealedEnvelope.SEALED, SealedEnvelope.LOCKED, SealedEnvelope.PLAIN).map { id + it }

    /** Newest change first; while locked, none (they open only after you unlock). */
    fun list(): List<Note> = read().notes

    // fix-seal-lows-b: a read that failed counts as one note not opened now (fail closed), never as an empty Notes: Today
    // then holds its quick add and says so, and Notes and the assistant say the notes can't be opened now.
    // fix-seal-lows-c 5: held as many as the folder holds note files (none, or the folder can't be listed: one).
    fun read(): NotesRead = sd.guarded(null) { readNow() } ?: NotesRead(emptyList(), notNow = heldGuess())

    private fun heldGuess(): Int = try {
        sd.files { idOf(it) != null || it.endsWith(SealedEnvelope.PLAIN) }.mapNotNull { f ->
            idOf(f.name)?.first ?: f.name.removeSuffix(SealedEnvelope.PLAIN).takeIf(NoteStore::validId)
        }.distinct().size.coerceAtLeast(1)
    } catch (_: Exception) { 1 }

    /** Every note, for a backup or a restore's plan: one that can't be opened now, or a newer Robot's, throws [NotesNotWhole], as any failure does. */
    fun whole(): List<Note> = readNow().also { if (it.notNow > 0 || it.tooNew > 0) throw NotesNotWhole(it.notNow, it.tooNew) }.notes

    private fun readNow(): NotesRead {
        dir.mkdirs()
        return withFileLock(lockFile) {
            val lockedNow = locked()
            if (lockedNow) cache.clear() else settle()
            var notNow = 0
            var tooNew = 0
            val out = LinkedHashMap<String, Note>()
            fun count(f: File, o: SealedOpen<Note>) {
                when (o) {
                    is SealedOpen.Ok -> {}
                    SealedOpen.KeyGone, SealedOpen.Unreadable -> aside(f)
                    is SealedOpen.TooNew -> tooNew++
                    SealedOpen.Locked -> notNow++
                }
            }
            // Fail closed: while locked no note is opened (the key would refuse anyway), not even a plain one not sealed yet.
            if (lockedNow) notNow = sd.files { idOf(it)?.second == false || it.endsWith(SealedEnvelope.PLAIN) }.size
            else for (f in sd.files { idOf(it)?.second == false }) openCached(f).also { count(f, it) }.let { (it as? SealedOpen.Ok)?.value?.let { n -> out[n.id] = n } }
            // A plain note not sealed yet (the key couldn't be used): shown as before, until the next pass seals it.
            if (!lockedNow) for (f in sd.files { it.endsWith(SealedEnvelope.PLAIN) }) {
                val id = f.name.removeSuffix(SealedEnvelope.PLAIN)
                if (NoteStore.validId(id) && !sealedFile(id).exists()) readOrNull(f)?.let(NoteCodec::fromJson)?.let { out.putIfAbsent(it.id, it) }
            }
            // A lock-screen note the unlock key couldn't take yet: listed from its journal, never hidden (seal-history-fix).
            if (!lockedNow) for (f in sd.files { idOf(it)?.second == true }) openCached(f).also { count(f, it) }.let { o ->
                val n = (o as? SealedOpen.Ok)?.value ?: return@let
                val have = out[n.id]
                if (have == null || n.updated >= have.updated) out[n.id] = n
            }
            NotesRead(out.values.sortedWith(compareByDescending<Note> { it.updated }.thenByDescending { it.created }), sd.asideCount(), notNow, tooNew)
        }
    }

    /** One note, or null (none, or it can't be opened now: [gone] tells which). seal-notes-b: none while locked, not even a cached one. */
    fun get(id: String): Note? = if (!NoteStore.validId(id)) null else sd.guarded(null) {
        withFileLock(lockFile) {
            if (locked()) { cache.clear(); return@withFileLock null }
            run {
                plainFile(id).takeIf { it.isFile }?.let(::migrate)
                journalFile(id).takeIf { it.isFile }?.let(::merge)
            }
            val sealed = sealedFile(id).takeIf { it.isFile }?.let(::openCached)
            // A journal still here while unlocked: the unlock key couldn't take it yet.
            val j = if (locked()) null else (journalFile(id).takeIf { it.isFile }?.let(::openCached) as? SealedOpen.Ok)?.value
            val e = (sealed as? SealedOpen.Ok)?.value
            when {
                j != null && (e == null || j.updated >= e.updated) -> j
                e != null -> e
                sealed == null -> readOrNull(plainFile(id))?.let(NoteCodec::fromJson)
                else -> null
            }
        }
    }

    /** A new note; null if it couldn't be written. */
    fun add(text: String, title: String?, now: Long, id: String = UUID.randomUUID().toString()): Note? {
        val n = Note(id, title?.trim().orEmpty(), text.trim(), now, now)
        return if (save(n)) n else null
    }

    /** seal-notes-b: true only when no file of [id] is left (sealed, journal or plain): deleted, not just unopenable now. */
    fun gone(id: String): Boolean = NoteStore.validId(id) && sd.guarded(false) { withFileLock(lockFile) { namesOf(id).none { File(dir, it).exists() } } }

    /** seal-small 3: [id]'s sealed file is a newer Robot's (false while locked, or when it can't tell); only such a file is opened. */
    fun tooNew(id: String): Boolean = NoteStore.validId(id) && sd.guarded(false) {
        withFileLock(lockFile) { !locked() && sealedFile(id).let { it.isFile && versionAbove(it) && open(it) is SealedOpen.TooNew } }
    }

    /** [VOICE] Sealed under the unlock-only key; while locked, under the lock-screen key, merged at the next unlock. False: not saved. */
    override fun save(n: Note): Boolean = save(n, lockScreen = true)

    /** seal-notes-b: the editor's save, never under the lock-screen key (only notes taken over the lock screen get it): false while locked. */
    fun saveUnlocked(n: Note): Boolean = save(n, lockScreen = false)

    /**
     * seal-notes-c: the editor's save of a note it has written or read: null when no file of it is left (Undo in the
     * panel), checked and written under one lock, so an Undo can't land between the two and the Notes list never reads
     * between them. Otherwise as [saveUnlocked].
     */
    fun saveIfThere(n: Note): Boolean? = if (!NoteStore.validId(n.id)) false else sd.guarded<Boolean?>(false) {
        withFileLock(lockFile) { if (namesOf(n.id).none { File(dir, it).exists() }) null else saveNow(n, lockScreen = false) }
    }

    private fun save(n: Note, lockScreen: Boolean): Boolean = NoteStore.validId(n.id) && sd.guarded(false) {
        withFileLock(lockFile) { saveNow(n, lockScreen) }
    }

    /** Under Notes' lock. */
    private fun saveNow(n: Note, lockScreen: Boolean): Boolean =
            if (locked()) lockScreen && saveLocked(n) else try {
                writeSealed(sealedFile(n.id), n)
                step("saved")
                journalFile(n.id).delete()
                plainFile(n.id).delete()
                true
            } catch (e: MemoryKeyException) {
                if (e.problem == KeyProblem.LOCKED) lockScreen && saveLocked(n) else throw e
            }

    /** Every copy of [id] (sealed, journal, plain); true when there was one. Off the main thread: it waits for the store's lock. */
    override fun delete(id: String): Boolean {
        if (!NoteStore.validId(id)) return false
        return sd.guarded(false) { withFileLock(lockFile) { namesOf(id).map { n -> cache.remove(n); File(dir, n).delete() }.any { it } } }
    }

    /** The opened notes forgotten (the screen went off). */
    fun drop() = cache.clear()

    /** A restore's staged file: [n] sealed for the name it will have here. Throws when the key can't be used now. */
    fun sealForRestore(n: Note): ByteArray = withFileLock(lockFile) { sealAs(n, sealedFile(n.id).name, NoteKeyAliases.UNLOCKED) }

    /** A restore's staged files moved in (StagedFiles.moveIn: its staged deletions first), under Notes' lock. */
    fun moveIn(staging: File) = withFileLock(lockFile) {
        app.heyrobot.core.backup.StagedFiles.moveIn(staging, dir.apply { mkdirs() })
        cache.clear()
    }

    /** How many notes are kept aside (their key is gone, or they're damaged). */
    fun aside(): Int = sd.asideCount()

    /** The Notes card's Delete these: the kept-aside files (they never open again) and a newer Robot's; every other note stays. */
    fun deleteAside() = withFileLock(lockFile) { (sd.asideFiles() + newer()).forEach { cache.remove(it.name); it.delete() } }

    /** seal-notes-b: a newer Robot's notes (left where they are, so that Robot still opens them): Delete these reaches them too, so none blocks backups for good. */
    private fun newer(): List<File> = sd.files { idOf(it) != null }.filter { versionAbove(it) && open(it) is SealedOpen.TooNew }

    /** seal-notes-c: only a file whose version byte is above this Robot's is opened (the Keystore) to tell: not every note. */
    private fun versionAbove(f: File): Boolean = try {
        java.io.RandomAccessFile(f, "r").use { r -> r.length() > 4 && r.run { seek(4); read() } > NoteEnvelope.frame.version } // l10n: not text (a file mode)
    } catch (_: Exception) { false }

    /** True when nothing of Notes is here, kept-aside files included. */
    fun none(): Boolean = sd.files { true }.isEmpty()

    /** Unlocked: plain notes sealed, the lock screen's notes merged (both repeatable; a cut pass leaves one whole copy). */
    fun open() {
        sd.guarded(Unit) { withFileLock(lockFile) { if (!locked()) settle() } }
    }

    private fun settle() {
        sd.dropCutWrites()
        for (f in sd.files { it.endsWith(SealedEnvelope.PLAIN) }) migrate(f)
        for (f in sd.files { idOf(it)?.second == true }) merge(f)
    }

    /** A plain note: sealed beside it, read back and compared, and only then removed. An unparseable one is kept aside. */
    private fun migrate(plain: File) {
        val id = plain.name.removeSuffix(SealedEnvelope.PLAIN)
        if (!NoteStore.validId(id)) return
        val text = readOrNull(plain) ?: return
        val note = NoteCodec.fromJson(text)?.takeIf { it.id == id } ?: run { aside(plain); return }
        val enc = sealedFile(id)
        if (enc.exists()) {
            val have = openCached(enc) as? SealedOpen.Ok ?: return
            // The sealed copy is the newer or the same (a pass cut after its rename): the plain one goes.
            if (have.value.updated >= note.updated) { plain.delete(); return }
        }
        step("seal")
        try { writeSealed(enc, note) } catch (e: MemoryKeyException) { RLog.i("notes_seal_later", "why" to RLog.Code(e.problem.name)); return }
        step("written")
        val back = (openCached(enc) as? SealedOpen.Ok)?.value ?: return
        if (NoteCodec.toJson(back) != NoteCodec.toJson(note)) return
        step("verified")
        plain.delete()
    }

    /** A note taken while locked: into <id>.enc unless that is newer; a journal that can't be opened is kept aside. */
    private fun merge(j: File) {
        val id = idOf(j.name)?.first ?: return
        val note = when (val o = open(j)) {
            is SealedOpen.Ok -> o.value
            SealedOpen.KeyGone, SealedOpen.Unreadable -> { aside(j); return }
            else -> return
        }
        val enc = sealedFile(id)
        when (val have = if (enc.exists()) openCached(enc) else null) {
            null -> Unit
            is SealedOpen.Ok -> if (have.value.updated > note.updated) { j.delete(); return }
            SealedOpen.KeyGone, SealedOpen.Unreadable -> aside(enc)
            else -> return
        }
        try { writeSealed(enc, note) } catch (_: MemoryKeyException) { return }
        step("merged")
        j.delete()
    }

    private fun saveLocked(n: Note): Boolean {
        val j = journalFile(n.id)
        writeAtomicBytes(j, sealAs(n, j.name, NoteKeyAliases.LOCK_SCREEN))
        cache.remove(j.name)
        return true
    }

    private fun writeSealed(f: File, n: Note) {
        writeAtomicBytes(f, sealAs(n, f.name, NoteKeyAliases.UNLOCKED))
        cache.remove(f.name)
    }

    private fun sealAs(n: Note, name: String, alias: String): ByteArray =
        sd.rekeyingOnGone(alias, if (alias == NoteKeyAliases.UNLOCKED) "notes" else "lock", { idOf(it) != null }, NoteEnvelope.frame::alias, // l10n: not text (log codes)
            { cache.remove(it.name) }) {
            NoteEnvelope.seal(n, sd.key(alias, create = true) ?: throw MemoryKeyException(KeyProblem.GONE), name)
        }

    private fun openCached(f: File): SealedOpen<Note> {
        val t = f.lastModified()
        val size = f.length()
        cache[f.name]?.let { (ct, cn, n) -> if (ct == t && cn == size) return SealedOpen.Ok(n) }
        return open(f).also { if (it is SealedOpen.Ok) cache[f.name] = Triple(t, size, it.value) }
    }

    /** [f] opened with the key its kind allows: a sealed note the unlock key's, a journal the lock screen's; a note under another id's name is damage. */
    private fun open(f: File): SealedOpen<Note> {
        val (id, journal) = idOf(f.name) ?: return SealedOpen.Unreadable
        val allowed = if (journal) NoteKeyAliases.LOCK_SCREEN else NoteKeyAliases.UNLOCKED
        val bytes = try { readForUpdate(f) } catch (_: Exception) { return SealedOpen.Locked } ?: return SealedOpen.Unreadable
        val o = NoteEnvelope.open(bytes, { a -> if (a != allowed) throw MemoryKeyException(KeyProblem.BAD_DATA) else sd.key(a, create = false) }, f.name)
        return if (o is SealedOpen.Ok && o.value.id != id) SealedOpen.Unreadable else o
    }

    private fun aside(f: File) {
        cache.remove(f.name)
        sd.aside(f)
    }
}

/** Notes' vault in this process (main and :voice). */
object NotesFiles {
    @Volatile private var vault: NotesVault? = null

    /** Tests only (Robolectric has no Android Keystore): the keys a new vault takes. */
    @Volatile internal var keys: () -> MemoryKeys = { KeystoreMemoryKeys(unlockOnly = setOf(NoteKeyAliases.UNLOCKED)) }

    fun dir(ctx: Context): File = File(ctx.filesDir, "notes").apply { mkdirs() } // l10n: not text (a folder name)

    fun store(ctx: Context): NotesVault = vault?.takeIf { it.dir == dir(ctx) } ?: synchronized(this) {
        vault?.takeIf { it.dir == dir(ctx) } ?: run {
            val app = ctx.applicationContext
            NotesVault(dir(app), keys(), File(Paths.dir(app), "notes.lock"), locked = { deviceLocked(app) }).also { vault = it } // l10n: not text (a file name)
        }
    }

    /** The cache of this process's vault dropped, if it has one (screen off). */
    fun drop() { vault?.drop() }

    /** merge-seal-130: the notes folder's names and times for Today's page stamp (core PageStamp), no note opened. */
    fun stamp(ctx: Context): List<Long> = app.heyrobot.core.PageStamp.files(dir(ctx))

    /** Tests only: the next [store] is made again, with [k]. */
    internal fun testKeys(k: MemoryKeys) { keys = { k }; vault = null }

    /** The device is locked (the unlock-only key can't be used). Unknown counts as locked. */
    private fun deviceLocked(ctx: Context): Boolean =
        try { ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked } catch (_: Exception) { true }
}
