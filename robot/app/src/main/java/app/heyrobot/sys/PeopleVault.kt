// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.PeopleEnvelope
import app.heyrobot.core.PeopleFile
import app.heyrobot.core.PeopleIo
import app.heyrobot.core.PeopleLoad
import app.heyrobot.core.PeopleOp
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.Person
import app.heyrobot.core.RLog
import app.heyrobot.core.SealedOpen
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.readForUpdate
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomicBytes
import java.io.File

// MAIN AND VOICE PROCESSES. Robot's People sealed with its own keys (seal-people; seal-local-data.md item 3), as History
// and Notes are (the framing core SealedEnvelope and the folder's handling sys/SealedDir are shared with them).
//
// The keys: robot_people_v1 (setUnlockedDeviceRequired) seals People whole; robot_people_lock_v1, without it, seals only
// the journal of changes made while the phone is locked (a sender learned from a notification, a Link desk's card), made
// at the next unlock. People is never sealed whole under the lock key, and while locked nobody is listed. Only these two
// aliases are asked for.
//
// The files, under files/people/ (credential-encrypted storage, never in Android's backup):
//   people.enc         People and the ignored addresses, sealed (PeopleEnvelope); the name and header in the additional data
//   people.locked.enc  the changes kept while locked, under the lock-screen key; made at the next unlock, then removed
//   people.json        Robot 1.2's plain file: sealed at the first unlock, read back and compared, then removed
//   *.bad, *.bad2..9   kept aside (Start over on a file that can't be read); deleted only by People's Delete all
//   people.json.enc.bad*  a plain people.json.bad* sealed whole under the unlock key, read back, then the plain one removed
// Both app processes take files/people/.lock for every read and change. Nothing here logs a person's name or address.

/** People's two Keystore keys, named here alone. */
object PeopleKeyAliases {
    const val UNLOCKED = "robot_people_v1"
    const val LOCK_SCREEN = "robot_people_lock_v1"
}

class PeopleVault(
    val dir: File,
    private val keys: MemoryKeys,
    private val locked: () -> Boolean,
    /** seal-people-fix 5: told under People's lock of each write (the file written) and of Delete all and Start over (null). */
    private val written: (PeopleFile?) -> Unit = {},
    /** Tests only: told at each step ("seal", "written", "verified", "merged"), to stop there as a crash would. */
    private val step: (String) -> Unit = {},
) : PeopleIo {
    private val sd = SealedDir(dir, keys, "people") // l10n: not text (a log code)
    private val enc get() = File(dir, PeopleEnvelope.FILE)
    private val journalFile get() = File(dir, PeopleEnvelope.JOURNAL)
    private val plain get() = File(dir, PeopleEnvelope.PLAIN)
    private val held = ThreadLocal<Boolean>()

    override val id: String get() = enc.path

    /** Re-entrant in this thread (a read inside a change); the OS lock between the app's processes. */
    override fun <T> locked(block: () -> T): T = if (held.get() == true) block() else withFileLock(File(dir, ".lock")) { // l10n: not text (a file name)
        held.set(true)
        try { block() } finally { held.set(false) }
    }

    /** Unlocked: the plain file sealed and the lock screen's changes made first (both repeatable; a cut pass leaves one whole copy). */
    override fun load(): PeopleLoad = locked {
        if (locked()) PeopleLoad.NotNow
        else {
            if (plain.exists() || journalFile.exists() || plainAside().isNotEmpty()) settle()
            openMain()
        }
    }

    /** Not while locked, nor while a plain file or a journal waits (the next [load] settles them). */
    override fun stamp(): String? = if (locked() || plain.exists() || journalFile.exists()) null else PeopleStore.stampOf(enc)

    override fun write(f: PeopleFile) {
        if (locked()) throw MemoryKeyException(KeyProblem.LOCKED)
        val had = enc.exists()
        writeAtomicBytes(enc, sealAs(PeopleEnvelope.bytes(f), PeopleKeyAliases.UNLOCKED))
        written(f)
        // A plain file still beside it (its migration failed) is what this change was made from: it goes once this opens as written.
        // fix-seal-14-c M2: only if it parses whole; one with an entry that didn't parse is kept aside and sealed whole instead.
        // fix-seal-14-d 3: with people.enc there before, the change was made from it, not the plain file: that one is kept aside.
        if (plain.exists() && same(openSealed(), f)) { if (!had && plainWhole()) plain.delete() else { asidePlain(); sealAside() } }
    }

    /** The plain file read whole: every entry parsed. One that can't be read, or doesn't parse, isn't. */
    private fun plainWhole() = try { PeopleCodec.read(plain.readText(Charsets.UTF_8))?.skipped == 0 } catch (_: Exception) { false }

    override fun journal(op: PeopleOp): Boolean {
        val have = if (!journalFile.exists()) emptyList() else when (val o = openJournal()) {
            is SealedOpen.Ok -> o.value
            SealedOpen.KeyGone, SealedOpen.Unreadable -> { sd.aside(journalFile); emptyList() }
            else -> return false
        }
        val next = PeopleEnvelope.appended(have, op) ?: return false.also { RLog.i("people_journal_full") } // l10n: not text (a log code)
        writeAtomicBytes(journalFile, sd.rekeyingOnGone(PeopleKeyAliases.LOCK_SCREEN, "lock", ::ours, PeopleEnvelope.frame::alias, {}) { // l10n: not text (a log code)
            PeopleEnvelope.sealJournal(next, key(PeopleKeyAliases.LOCK_SCREEN))
        })
        return true
    }

    override fun reset(): Boolean {
        if (locked()) return false
        return when (openMain()) {
            is PeopleLoad.Ok -> true
            PeopleLoad.NotNow -> false
            // The sealed file first, then a plain one left beside it; one that can't be moved stops it.
            else -> { val f = if (enc.exists()) enc else plain; makeRoom(f); sd.aside(f); sealAside(); written(null); !f.exists() && reset() }
        }
    }

    /** seal-people-fix-b 5: nine copies of [f] kept aside already: the oldest goes, so Start over (and then Delete all) never sticks. */
    private fun makeRoom(f: File) {
        val kept = (1..9).map { File(f.parentFile, f.name + if (it == 1) ".bad" else ".bad$it") }
        if (kept.all { it.exists() }) kept.minBy { it.lastModified() }.delete()
    }

    override fun deleteAll(): Boolean {
        if (locked() || load() !is PeopleLoad.Ok) return false
        sd.dropCutWrites()
        // seal-people-fix 2: the copies Start over kept aside go too (people.json.bad holds names and numbers in plain text).
        val ok = (listOf(enc, journalFile, plain) + sd.asideFiles()).all { !it.exists() || it.delete() }
        if (ok) dropUnusedKeys()
        written(null)
        return ok
    }

    /** At unlock (ListsVault's hook): the plain file sealed and the journal made, without waiting for a reader. */
    fun open() {
        sd.guarded(Unit) { locked { if (!locked()) settle() } }
    }

    private fun ours(name: String) = name == PeopleEnvelope.FILE || name == PeopleEnvelope.JOURNAL

    /** Each step on its own: one that fails is logged and leaves its files whole, and the readers still see the plain copy. */
    private fun settle() {
        sd.dropCutWrites()
        if (plain.exists()) try { migrate() } catch (e: Exception) { log("people_migrate", e) } // l10n: not text (a log code)
        if (journalFile.exists()) try { merge() } catch (e: Exception) { log("people_merge", e) } // l10n: not text (a log code)
        sealAside()
    }

    /** Robot 1.2's file kept aside (Start over, or a copy unlike the sealed one): names and numbers in plain text. */
    private fun plainAside() = sd.files { it.startsWith(PeopleEnvelope.PLAIN + ".bad") }

    /**
     * seal-small 1: each plain copy kept aside sealed byte for byte under the unlock key (people.json.enc.bad*, still kept
     * aside, gone with Delete all), read back and compared, only then the plain one deleted. A key not usable now, or
     * gone, leaves it for the next pass; no other file is ever moved for it.
     */
    private fun sealAside() {
        for (f in plainAside()) try {
            val bytes = f.readBytes()
            fun back(to: File) = to.exists() && PeopleEnvelope.frame.open(readForUpdate(to), { a -> if (a != PeopleKeyAliases.UNLOCKED) null else sd.key(a, create = false) }, to.name) { it }
                .let { it is SealedOpen.Ok && it.value.contentEquals(bytes) }
            // fix-seal-14-c L1: an older sealed copy under its name holding other bytes is never overwritten: the next free name.
            val named = File(dir, f.name.replaceFirst(PeopleEnvelope.PLAIN, PeopleEnvelope.PLAIN + ".enc"))
            val to = if (!named.exists() || back(named)) named else freeSealed()
            if (to == null) { RLog.i("people_seal_later"); continue } // l10n: not text (a log code)
            if (!back(to)) writeAtomicBytes(to, PeopleEnvelope.frame.seal(bytes, key(PeopleKeyAliases.UNLOCKED), to.name))
            if (back(to)) f.delete() else RLog.i("people_seal_later") // l10n: not text (a log code)
        } catch (e: Exception) { log("people_seal_aside", e) } // l10n: not text (a log code)
    }

    /** The first sealed copy's name free in both its plain and its sealed forms; null with nine kept. */
    private fun freeSealed(): File? = (1..9).map { if (it == 1) ".bad" else ".bad$it" } // l10n: not text (a file name)
        .firstOrNull { s -> !File(dir, PeopleEnvelope.PLAIN + s).exists() && !File(dir, PeopleEnvelope.PLAIN + ".enc" + s).exists() }
        ?.let { File(dir, PeopleEnvelope.PLAIN + ".enc" + it) }

    private fun log(code: String, e: Exception) =
        if (e is MemoryKeyException) RLog.i(code, "why" to RLog.Code(e.problem.name)) else RLog.w(code, e) // l10n: not text (a log code)

    /** The plain file: sealed beside it, read back and compared, only then removed. Unparseable, it stays for Start over. */
    private fun migrate() {
        val text = try { plain.readText(Charsets.UTF_8) } catch (_: Exception) { return }
        val parsed = PeopleCodec.read(text) ?: return
        if (enc.exists()) {
            // A pass cut after its rename: the plain file goes only when it holds what the sealed one does; else it is kept aside.
            when (val have = openSealed()) {
                // fix-seal-14-lows-b 1: an entry that didn't parse is never dropped: the plain file is kept aside, sealed whole.
                is SealedOpen.Ok -> if (same(have, parsed) && parsed.skipped == 0) plain.delete() else { asidePlain(); sealAside() }
                else -> Unit
            }
            return
        }
        step("seal")
        val sealed = sealAs(PeopleEnvelope.bytes(parsed), PeopleKeyAliases.UNLOCKED)
        writeAtomicBytes(enc, sealed)
        step("written")
        // Not as written: the sealed copy goes, so the plain one stays the only one and the next pass tries again.
        if (!same(openSealed(), parsed)) { enc.delete(); RLog.i("people_seal_later"); return } // l10n: not text (a log code)
        step("verified")
        if (parsed.skipped == 0) plain.delete() else { asidePlain(); sealAside() }
    }

    /**
     * fix-seal-14-lows-b 1: the plain file kept aside under the first number free in both its plain and its sealed names,
     * so sealing it never replaces an older sealed copy. Nine copies already: it stays where it is (never deleted).
     */
    private fun asidePlain() {
        for (n in 1..9) {
            val s = if (n == 1) ".bad" else ".bad$n" // l10n: not text (a file name)
            val to = File(dir, PeopleEnvelope.PLAIN + s)
            if (!to.exists() && !File(dir, PeopleEnvelope.PLAIN + ".enc" + s).exists()) { plain.renameTo(to); return }
        }
    }

    /**
     * fix-seal-14-lows-b 1: People's copies not read into it: each file kept aside (an entry that didn't parse, Start over),
     * and a plain file still waiting beside the sealed one. While any is left People isn't read whole; Delete all removes them.
     */
    fun keptAside(): Int = locked { sd.asideFiles().size + if (plain.exists() && enc.exists()) 1 else 0 }

    /** fix-seal-14-d 1: everyone in People's kept-aside copies that open; [whole] false when one held an entry that didn't parse or can't be opened yet. */
    class Aside(val people: List<Person>, val whole: Boolean)

    /**
     * fix-seal-14-d 1: what People's kept-aside copies hold, read but never read into People. A copy that can't be opened at
     * all (damaged, a key gone, a newer Robot's: what Start over keeps) holds nothing Robot can read, so it never counts as
     * unread; one that can't be opened now (its storage locked) does, and so does an entry in one that didn't parse.
     */
    fun asidePeople(): Aside = locked {
        val out = mutableListOf<Person>()
        var whole = true
        fun take(f: PeopleFile?) { if (f != null) { out += f.people; if (f.skipped > 0) whole = false } }
        fun text(b: ByteArray) = try { PeopleCodec.read(String(b, Charsets.UTF_8)) } catch (_: Exception) { null }
        val unlockKey = { a: String -> if (a != PeopleKeyAliases.UNLOCKED) null else sd.key(a, create = false) }
        val files = sd.asideFiles() + if (plain.exists() && enc.exists()) listOf(plain) else emptyList()
        for (f in files) {
            val bytes = try { readForUpdate(f) } catch (_: Exception) { whole = false; continue } ?: continue
            try {
                val n = f.name
                when {
                    n == PeopleEnvelope.PLAIN || n.startsWith(PeopleEnvelope.PLAIN + ".bad") -> take(text(bytes))
                    n.startsWith(PeopleEnvelope.PLAIN + ".enc") -> when (val o = PeopleEnvelope.frame.open(bytes, unlockKey, n) { it }) {
                        is SealedOpen.Ok -> take(text(o.value))
                        SealedOpen.Locked -> whole = false
                        else -> Unit
                    }
                    n.startsWith(PeopleEnvelope.JOURNAL) -> when (val o = PeopleEnvelope.openJournal(bytes) { a -> if (a != PeopleKeyAliases.LOCK_SCREEN) null else sd.key(a, create = false) }) {
                        is SealedOpen.Ok -> take(o.value.fold(PeopleFile()) { p, op -> PeopleStore.apply(p, op) })
                        SealedOpen.Locked -> whole = false
                        else -> Unit
                    }
                    n.startsWith(PeopleEnvelope.FILE) -> when (val o = PeopleEnvelope.open(bytes, unlockKey, PeopleEnvelope.FILE)) {
                        is SealedOpen.Ok -> take(o.value)
                        SealedOpen.Locked -> whole = false
                        else -> Unit
                    }
                }
            } catch (_: Exception) { Unit } finally { bytes.fill(0) }
        }
        Aside(out, whole)
    }

    /** The lock screen's changes made on People; each is safe made twice, so a pass cut before the journal goes adds nothing twice. */
    private fun merge() {
        val ops = when (val o = openJournal()) {
            is SealedOpen.Ok -> o.value
            SealedOpen.KeyGone, SealedOpen.Unreadable -> { sd.aside(journalFile); return }
            else -> return
        }
        val have = (openMain() as? PeopleLoad.Ok)?.file ?: return
        val next = ops.fold(have) { f, op -> PeopleStore.apply(f, op) }
        if (next !== have) write(PeopleStore.tidy(next))
        step("merged")
        journalFile.delete()
    }

    /** The sealed file, or the plain one while it waits (or its migration failed), or an empty People. */
    private fun openMain(): PeopleLoad {
        if (!enc.exists()) {
            if (!plain.exists()) return PeopleLoad.Ok(PeopleFile())
            val text = try { plain.readText(Charsets.UTF_8) } catch (_: Exception) { return PeopleLoad.NotNow }
            return PeopleCodec.read(text)?.let { PeopleLoad.Ok(it) } ?: PeopleLoad.Unreadable
        }
        return when (val o = openSealed()) {
            is SealedOpen.Ok -> PeopleLoad.Ok(o.value)
            SealedOpen.Locked -> PeopleLoad.NotNow
            SealedOpen.KeyGone, SealedOpen.Unreadable -> PeopleLoad.Unreadable
            is SealedOpen.TooNew -> PeopleLoad.TooNew(o.version)
        }
    }

    /** people.enc with the unlock key only; any other alias in its header is damage, never asked for. */
    private fun openSealed(): SealedOpen<PeopleFile> {
        val bytes = try { readForUpdate(enc) } catch (_: Exception) { return SealedOpen.Locked } ?: return SealedOpen.Unreadable
        return PeopleEnvelope.open(bytes, { a -> if (a != PeopleKeyAliases.UNLOCKED) throw MemoryKeyException(KeyProblem.BAD_DATA) else sd.key(a, create = false) }, enc.name)
    }

    /** The journal with the lock-screen key only. */
    private fun openJournal(): SealedOpen<List<PeopleOp>> {
        val bytes = try { readForUpdate(journalFile) } catch (_: Exception) { return SealedOpen.Locked } ?: return SealedOpen.Unreadable
        return PeopleEnvelope.openJournal(bytes) { a -> if (a != PeopleKeyAliases.LOCK_SCREEN) throw MemoryKeyException(KeyProblem.BAD_DATA) else sd.key(a, create = false) }
    }

    /** Byte for byte as it is sealed (a word that doesn't survive UTF-8 changes both sides alike). */
    private fun same(o: SealedOpen<PeopleFile>, f: PeopleFile): Boolean = o is SealedOpen.Ok && PeopleEnvelope.bytes(o.value).contentEquals(PeopleEnvelope.bytes(f))

    private fun key(alias: String): MemoryCipher = sd.key(alias, create = true) ?: throw MemoryKeyException(KeyProblem.GONE)

    /** People's file sealed with the unlock key; a key Android invalidated is replaced (what it sealed kept aside first). */
    private fun sealAs(bytes: ByteArray, alias: String): ByteArray =
        sd.rekeyingOnGone(alias, "people", ::ours, PeopleEnvelope.frame::alias, {}) { // l10n: not text (a log code)
            PeopleEnvelope.frame.seal(bytes, key(alias), PeopleEnvelope.FILE)
        }

    /** A key goes once no file here (a kept-aside one too) is sealed under it. */
    private fun dropUnusedKeys() {
        val used = sd.files { it != ".lock" }.mapNotNull { f -> try { PeopleEnvelope.frame.alias(readForUpdate(f)) } catch (_: Exception) { return } }.toSet() // l10n: not text (a file name)
        for (a in listOf(PeopleKeyAliases.UNLOCKED, PeopleKeyAliases.LOCK_SCREEN)) if (a !in used) keys.delete(a)
    }
}
