// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.SealedOpen
import android.app.KeyguardManager
import android.content.Context
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.HistoryEnvelope
import app.heyrobot.core.HistoryOpen
import app.heyrobot.core.RLog
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.readForUpdate
import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomicBytes
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// MAIN AND VOICE PROCESSES. History sealed with Robot's own keys (seal-history; seal-local-data.md step 3).
//
// The keys: two Android Keystore AES-256-GCM keys made through memory's key class (KeystoreMemoryKeys): robot_history_v1
// with setUnlockedDeviceRequired(true) seals each conversation; robot_history_lock_v1, without it, seals what the panel
// saves while the phone is locked (a conversation over the lock screen), merged at the next unlock. Only these two
// aliases are ever asked for (another alias in a header is damaged data, never another feature's key). A key Android
// invalidated (the screen lock removed, say) can never open what it sealed: those files are kept aside and a new key made.
//
// The files, under files/conversations/ (credential-encrypted storage, never in Android's backup):
//   <id>.enc          a conversation, sealed (HistoryEnvelope); the name is in the additional data
//   <id>.locked.enc   saved while locked, under the lock-screen key; merged into <id>.enc at the next unlock. When this
//                     process knows <id>.enc's turns (it sealed or opened that file), only the turns after them
//                     (the header's base): what was said before the lock never opens under the lock-screen key.
//   <id>.json         Robot 1.2's plain file: sealed at the first unlock, read back and compared, then removed
//   *.bad, *.bad2..9  kept aside: sealed under a key that is gone, or damaged; deleted only by you (the card's Delete
//                     these, or Delete all)
// Both app processes take files/robot/history.lock for every read and change.
//
// Nothing here logs a conversation's words or id: codes and counts only.

/** History's two Keystore keys, named here alone. */
object HistoryKeyAliases {
    const val UNLOCKED = "robot_history_v1"
    const val LOCK_SCREEN = "robot_history_lock_v1"
}

/** History as it opens now: [conversations] newest first; [aside], conversations kept aside that can't be opened; [notNow], ones the key can't open until you unlock. */
data class HistoryRead(val conversations: List<StoredConversation>, val aside: Int = 0, val notNow: Int = 0, val tooNew: Int = 0)

/** From [HistoryVault.whole]: [notNow] conversations the key can't open now; [tooNew] a newer Robot's (update, or Delete these). */
class HistoryNotWhole(val notNow: Int, val tooNew: Int) : Exception()

class HistoryVault(
    val dir: File,
    private val keys: MemoryKeys,
    private val lockFile: File,
    private val locked: () -> Boolean,
    /** Tests only: told at each step of a plain file's sealing ("seal", "written", "verified"), to stop there as a crash would. */
    private val step: (String) -> Unit = {},
) {
    /** The files kept aside, the keys, a pass's failures: shared with Notes (seal-notes). */
    private val sd = SealedDir(dir, keys, "history") // l10n: not text (a log code)

    /** Opened conversations by file name, with the file's time and size: a file that didn't change isn't opened again. */
    private val cache = ConcurrentHashMap<String, Triple<Long, Long, HistoryOpen.Ok>>()

    /** By id: <id>.enc's time and size when this process sealed or opened it, its turn count and their digest (no words). */
    private val sealedTurns = ConcurrentHashMap<String, Triple<Long, Long, Pair<Int, String>>>()

    private fun sealedFile(id: String) = File(dir, id + HistoryEnvelope.SEALED)
    private fun journalFile(id: String) = File(dir, id + HistoryEnvelope.LOCKED)
    private fun plainFile(id: String) = File(dir, id + HistoryEnvelope.PLAIN)

    /** Every file name a conversation may have here (a restore's Replace deletes them all). */
    fun namesOf(id: String): List<String> = listOf(HistoryEnvelope.SEALED, HistoryEnvelope.LOCKED, HistoryEnvelope.PLAIN).map { id + it }

    /** History now: unlocked, plain files sealed and the lock screen's saves merged first. */
    fun read(): HistoryRead = guarded(HistoryRead(emptyList())) { readNow() }

    /** [read] without its quiet fallback: what fails here fails [whole] (seal-history-fix-b 3). */
    private fun readNow(): HistoryRead {
        dir.mkdirs()
        return withFileLock(lockFile) {
            val lockedNow = locked()
            if (lockedNow) cache.clear() else settle()
            var notNow = 0
            var tooNew = 0
            val out = ArrayList<StoredConversation>()
            for (f in files { HistoryEnvelope.idOf(it)?.second == false }) when (val o = openCached(f)) {
                is HistoryOpen.Ok -> out += o.conversation
                HistoryOpen.KeyGone, HistoryOpen.Unreadable -> aside(f)
                is HistoryOpen.TooNew -> tooNew++
                else -> notNow++
            }
            // A plain file not sealed yet (the key couldn't be used): shown as before, until the next pass seals it.
            for (f in files { it.endsWith(HistoryEnvelope.PLAIN) }) {
                val id = f.name.removeSuffix(HistoryEnvelope.PLAIN)
                if (ConversationStore.validId(id) && !sealedFile(id).exists()) readOrNull(f)?.let(ConversationCodec::fromJson)?.let { out += it }
            }
            // A lock-screen save the unlock key couldn't take yet: listed from its journal, never hidden (seal-history-fix).
            if (!lockedNow) for (f in files { HistoryEnvelope.idOf(it)?.second == true }) when (val o = openCached(f)) {
                is HistoryOpen.Ok -> {
                    val i = out.indexOfFirst { it.id == o.conversation.id }
                    // Turns after a sealed copy that can't be opened now: that copy is already counted as not now.
                    if (i < 0 && o.base > 0 && sealedFile(o.conversation.id).exists()) continue
                    val c = joined(out.getOrNull(i), o)
                    if (i < 0) out += c else out[i] = c
                }
                HistoryOpen.KeyGone, HistoryOpen.Unreadable -> aside(f)
                is HistoryOpen.TooNew -> tooNew++
                else -> notNow++
            }
            HistoryRead(out.sortedByDescending { it.updated }, aside(), notNow, tooNew)
        }
    }

    /**
     * Every conversation, for a backup or a restore's plan. One the key can't open now, or a newer Robot's, throws
     * [HistoryNotWhole]: a backup says History is why (BackupCode.HISTORY_*), and a restore never deletes it or writes an
     * older copy over it unseen (seal-history-fix, seal-history-fix-b 1). Any other failure throws too, never an empty
     * History a Replace restore would apply (seal-history-fix-b 3).
     */
    fun whole(): List<StoredConversation> = readNow().also { if (it.notNow > 0 || it.tooNew > 0) throw HistoryNotWhole(it.notNow, it.tooNew) }.conversations

    /** One conversation, or null (none, or it can't be opened now). */
    fun load(id: String): StoredConversation? = if (!ConversationStore.validId(id)) null else guarded(null) {
        withFileLock(lockFile) {
            if (!locked()) {
                plainFile(id).takeIf { it.isFile }?.let(::migrate)
                journalFile(id).takeIf { it.isFile }?.let(::merge)
            }
            // A journal still here while unlocked: the unlock key couldn't take it yet, and it is the later copy.
            val sealed = sealedFile(id).takeIf { it.isFile }?.let(::openCached)
            if (!locked()) (journalFile(id).takeIf { it.isFile }?.let(::openCached) as? HistoryOpen.Ok)?.let { j ->
                val e = (sealed as? HistoryOpen.Ok)?.conversation
                if (j.base == 0 || e != null || sealed == null) return@withFileLock joined(e, j)
            }
            when (val o = sealed) {
                is HistoryOpen.Ok -> o.conversation
                null -> readOrNull(plainFile(id))?.let(ConversationCodec::fromJson)
                else -> null
            }
        }
    }

    /** [VOICE] Sealed under the unlock-only key; while locked, under the lock-screen key, merged at the next unlock. False: not saved. */
    fun save(c: StoredConversation): Boolean = ConversationStore.validId(c.id) && guarded(false) {
        withFileLock(lockFile) {
            if (c.id in gone || c.created <= goneBefore) true // fix-c 3: deleted in the main process while this save was on its way
            else if (locked()) saveLocked(c) else try {
                writeSealed(sealedFile(c.id), c)
                step("saved")
                journalFile(c.id).delete()
                // fix-seal-14-c M2: a plain file with a turn that didn't parse is kept aside whole, never deleted.
                plainFile(c.id).let { if (it.exists() && !plainWhole(it)) { aside(it); sealAside() } else it.delete() }
                true
            } catch (e: MemoryKeyException) {
                if (e.problem == KeyProblem.LOCKED) saveLocked(c) else throw e
            }
        }
    }

    fun delete(id: String) {
        if (!ConversationStore.validId(id)) return
        withFileLock(lockFile) { for (n in namesOf(id)) { File(dir, n).delete(); cache.remove(n) } }
    }

    /** The opened conversations forgotten: the screen went off, or the main process deleted some (seal-history-fix). */
    fun drop() = cache.clear()

    private val gone: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var goneBefore = Long.MIN_VALUE

    /**
     * seal-history-fix-c 3: [VOICE] the main process deleted [id] (null: Delete all, every conversation begun by [now]).
     * Marked at once, so no later [save] writes it back; the returned step (off the main thread) deletes it once more,
     * for a save that was already writing under History's lock when the delete came.
     */
    fun forgetDeleted(id: String?, now: Long = System.currentTimeMillis()): () -> Unit {
        if (id == null) goneBefore = maxOf(goneBefore, now) else if (ConversationStore.validId(id)) gone += id else return {}
        cache.clear()
        return {
            val back = if (id != null) listOf(id) else try { read().conversations.filter { it.created <= now }.map { it.id } } catch (_: Exception) { emptyList() }
            back.forEach(::delete)
            cache.clear()
        }
    }

    /** History's Delete all: every file here, kept-aside ones too, then both keys (nothing is sealed under them now). */
    fun deleteAll() {
        withFileLock(lockFile) {
            dir.listFiles().orEmpty().forEach { it.delete() }
            cache.clear()
            keys.delete(HistoryKeyAliases.UNLOCKED)
            keys.delete(HistoryKeyAliases.LOCK_SCREEN)
        }
    }

    /** A restore's staged file: [c] sealed for the name it will have here. Throws when the key can't be used now. */
    fun sealForRestore(c: StoredConversation): ByteArray = withFileLock(lockFile) {
        sealAs(c, sealedFile(c.id).name, HistoryKeyAliases.UNLOCKED)
    }

    /** A restore's staged files moved in (StagedFiles.moveIn: its staged deletions first), under History's lock. */
    fun moveIn(staging: File) = withFileLock(lockFile) {
        app.heyrobot.core.backup.StagedFiles.moveIn(staging, dir.apply { mkdirs() })
        cache.clear()
    }

    /** How many conversations are kept aside (their key is gone, or they're damaged): conversations, not files. */
    fun aside(): Int = sd.asideCount()

    /** History's card, Delete these: the kept-aside files only (they never open again); every other conversation stays. */
    fun deleteAside() = withFileLock(lockFile) { (sd.asideFiles() + newer()).forEach { cache.remove(it.name); it.delete() } }

    /** A newer Robot's files (left where they are, so that Robot still opens them): Delete these reaches them too, so none blocks backups for good. */
    private fun newer(): List<File> = files { HistoryEnvelope.idOf(it) != null }
        .filter { f -> HistoryEnvelope.open(try { readForUpdate(f) } catch (_: Exception) { null }, { null }, f.name) is HistoryOpen.TooNew }

    /** Unlocked: plain files sealed, the lock screen's saves merged (both repeatable; a cut pass leaves one whole copy). */
    fun open() {
        guarded(Unit) { withFileLock(lockFile) { if (!locked()) settle() } }
    }

    private fun settle() {
        sd.dropCutWrites()
        for (f in files { it.endsWith(HistoryEnvelope.PLAIN) }) migrate(f)
        for (f in files { HistoryEnvelope.idOf(it)?.second == true }) merge(f)
        sealAside()
    }

    /**
     * fix-seal-14-e 2: a plain copy kept aside (<id>.json.bad, .bad2..9) sealed as History is, under the unlock key, as
     * <id>.json.enc.bad…: read back and compared, only then the plain one removed. An older sealed copy holding other
     * bytes is never overwritten (the next name free in both forms); with none free, or the key not usable now, it waits.
     */
    private fun sealAside() {
        val plainAside = Regex("""^(.+)\Q${HistoryEnvelope.PLAIN}\E(\.bad\d?)$""")
        for (f in files { plainAside.matches(it) }) try {
            val (stem, bad) = plainAside.matchEntire(f.name)!!.destructured
            val bytes = f.readBytes()
            val unlockKey = { a: String -> if (a != HistoryKeyAliases.UNLOCKED) null else key(a, create = false) }
            fun back(to: File) = to.exists() && HistoryEnvelope.frame.open(readForUpdate(to), unlockKey, to.name) { it }
                .let { it is SealedOpen.Ok && it.value.contentEquals(bytes) }
            fun free(s: String) = !File(dir, stem + HistoryEnvelope.PLAIN + s).exists() && !File(dir, stem + HistoryEnvelope.PLAIN + ".enc" + s).exists()
            val named = File(dir, stem + HistoryEnvelope.PLAIN + ".enc" + bad)
            val to = if (!named.exists() || back(named)) named
                else (1..9).map { if (it == 1) ".bad" else ".bad$it" }.firstOrNull(::free)?.let { File(dir, stem + HistoryEnvelope.PLAIN + ".enc" + it) } // l10n: not text (a file name)
            if (to == null) { RLog.i("history_seal_later"); continue } // l10n: not text (a log code)
            if (!back(to)) writeAtomicBytes(to, HistoryEnvelope.frame.seal(bytes, key(HistoryKeyAliases.UNLOCKED, create = true) ?: throw MemoryKeyException(KeyProblem.GONE), to.name))
            if (back(to)) f.delete() else RLog.i("history_seal_later") // l10n: not text (a log code)
        } catch (e: MemoryKeyException) {
            RLog.i("history_seal_later", "why" to RLog.Code(e.problem.name)) // l10n: not text (a log code)
        } catch (e: Exception) {
            RLog.w("history_seal_aside", e) // l10n: not text (a log code)
        }
    }

    /** A plain file: sealed beside it, read back and compared, and only then removed. Damaged plain text stays as it was. */
    private fun migrate(plain: File) {
        val id = plain.name.removeSuffix(HistoryEnvelope.PLAIN)
        if (!ConversationStore.validId(id)) return
        val text = readOrNull(plain) ?: return
        val conv = ConversationCodec.fromJson(text) ?: run { aside(plain); return }
        // fix-seal-14-lows-b 2: a turn that didn't parse (a newer Robot's, or damage) is never dropped: the plain file is kept aside whole.
        val whole = whole(text, conv)
        val enc = sealedFile(id)
        if (enc.exists()) {
            val have = openCached(enc)
            if (have !is HistoryOpen.Ok) return
            // The sealed copy is the newer or the same (a pass cut after its rename): the plain one goes.
            // With the clock set back, the plain copy with more turns still wins (seal-history-fix).
            if (have.conversation.updated >= conv.updated && have.conversation.turns.size >= conv.turns.size) { if (whole) plain.delete() else aside(plain); return }
        }
        step("seal")
        try { writeSealed(enc, conv) } catch (e: MemoryKeyException) { RLog.i("history_seal_later", "why" to RLog.Code(e.problem.name)); return }
        step("written")
        val back = (openCached(enc) as? HistoryOpen.Ok)?.conversation ?: return
        if (ConversationCodec.toJson(back) != ConversationCodec.toJson(conv)) return
        step("verified")
        if (whole) plain.delete() else aside(plain)
    }

    /** A save made while locked: into <id>.enc unless that is newer; a journal that can't be opened is kept aside. */
    private fun merge(j: File) {
        val id = HistoryEnvelope.idOf(j.name)?.first ?: return
        val o = when (val o = open(j)) {
            is HistoryOpen.Ok -> o
            HistoryOpen.KeyGone, HistoryOpen.Unreadable -> { aside(j); return }
            else -> return
        }
        val conv = o.conversation
        val enc = sealedFile(id)
        val have = if (enc.exists()) openCached(enc) else null
        val e = when (have) {
            null -> null
            // A whole journal older by the clock goes only when the sealed copy has as many turns (the clock may have gone back).
            is HistoryOpen.Ok -> have.conversation.also { if (o.base == 0 && it.updated > conv.updated && it.turns.size >= conv.turns.size) { j.delete(); return } }
            HistoryOpen.KeyGone, HistoryOpen.Unreadable -> { aside(enc); null }
            else -> return
        }
        try { writeSealed(enc, joined(e, o)) } catch (x: MemoryKeyException) { return }
        step("merged")
        j.delete()
    }

    /**
     * A save while locked: only the turns after those of <id>.enc when this process knows them unchanged (it sealed or
     * opened that file and the earlier turns are the same), else the whole conversation (seal-history-fix 3).
     */
    private fun saveLocked(c: StoredConversation): Boolean {
        val j = journalFile(c.id)
        val base = sealedBase(c)
        writeAtomicBytes(j, sealAs(if (base > 0) c.copy(turns = c.turns.drop(base)) else c, j.name, HistoryKeyAliases.LOCK_SCREEN, base))
        cache.remove(j.name)
        return true
    }

    private fun sealedBase(c: StoredConversation): Int {
        val enc = sealedFile(c.id)
        val (t, n, k) = sealedTurns[c.id] ?: return 0
        if (!enc.isFile || enc.lastModified() != t || enc.length() != n) return 0
        val (count, digest) = k
        return if (count in 1..c.turns.size && digestOf(c.turns.take(count)) == digest) count else 0
    }

    private fun remember(f: File, c: StoredConversation) {
        sealedTurns[c.id] = Triple(f.lastModified(), f.length(), c.turns.size to digestOf(c.turns))
    }

    /**
     * A journal's turns after the sealed copy [e]'s (the journal alone when it holds the whole conversation, or [e] is gone).
     * [e] as it is when it already holds them after its first [HistoryOpen.Ok.base]: a merge or an unlocked save cut before
     * the journal went never adds them twice, and never drops a turn the save had after them (seal-history-fix-b 5).
     */
    private fun joined(e: StoredConversation?, j: HistoryOpen.Ok): StoredConversation {
        if (j.base == 0 || e == null) return j.conversation
        val updated = maxOf(e.updated, j.conversation.updated)
        // seal-history-fix-c 4: by each turn's time and role, since the panel changes a turn after saving it (actions settled).
        fun keys(t: List<app.heyrobot.core.ChatTurn>) = t.map { it.time to it.role }
        if (keys(e.turns.drop(j.base).take(j.conversation.turns.size)) == keys(j.conversation.turns)) return e.copy(updated = updated)
        return StoredConversation(e.id, e.created, updated, e.turns + j.conversation.turns)
    }

    private fun writeSealed(f: File, c: StoredConversation) {
        writeAtomicBytes(f, sealAs(c, f.name, HistoryKeyAliases.UNLOCKED))
        cache.remove(f.name)
        remember(f, c)
    }

    /**
     * [c] sealed with [alias]'s key, made when missing. A key Android invalidated can never open what it sealed: those
     * files are kept aside (never deleted) and the key made again, once. "Not now" throws, and nothing is written.
     */
    private fun sealAs(c: StoredConversation, name: String, alias: String, base: Int = 0): ByteArray =
        sd.rekeyingOnGone(alias, if (alias == HistoryKeyAliases.UNLOCKED) "history" else "lock", { HistoryEnvelope.idOf(it) != null }, // l10n: not text (log codes)
            HistoryEnvelope::alias, { cache.remove(it.name) }) {
            HistoryEnvelope.seal(c, key(alias, create = true) ?: throw MemoryKeyException(KeyProblem.GONE), name, base)
        }

    private fun openCached(f: File): HistoryOpen {
        val t = f.lastModified()
        val n = f.length()
        cache[f.name]?.let { (ct, cn, ok) -> if (ct == t && cn == n) return ok }
        return open(f).also {
            if (it !is HistoryOpen.Ok) return@also
            cache[f.name] = Triple(t, n, it)
            if (HistoryEnvelope.idOf(f.name)?.second == false) remember(f, it.conversation)
        }
    }

    /** [f] opened with the key its kind allows: a sealed conversation the unlock key's, a journal the lock screen's. */
    private fun open(f: File): HistoryOpen {
        val journal = HistoryEnvelope.idOf(f.name)?.second ?: return HistoryOpen.Unreadable
        val allowed = if (journal) HistoryKeyAliases.LOCK_SCREEN else HistoryKeyAliases.UNLOCKED
        val bytes = try { readForUpdate(f) } catch (_: Exception) { return HistoryOpen.Locked } ?: return HistoryOpen.Unreadable
        return HistoryEnvelope.open(bytes, { a -> if (a != allowed) throw MemoryKeyException(KeyProblem.BAD_DATA) else key(a, create = false) }, f.name)
    }

    /** Every turn in [text] parsed into [conv]. */
    private fun whole(text: String, conv: StoredConversation) = (try { org.json.JSONObject(text).optJSONArray("turns")?.length() ?: 0 } catch (_: Exception) { -1 }) == conv.turns.size

    /** fix-seal-14-c M2: [plain] parses whole; one that can't be read or doesn't parse isn't. */
    private fun plainWhole(plain: File) = readOrNull(plain)?.let { t -> ConversationCodec.fromJson(t)?.let { whole(t, it) } } == true

    private fun key(alias: String, create: Boolean): MemoryCipher? = sd.key(alias, create)

    private fun aside(f: File) {
        cache.remove(f.name)
        sd.aside(f)
    }

    private fun digestOf(turns: List<app.heyrobot.core.ChatTurn>): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(ConversationCodec.toJson(StoredConversation("t", 0L, 0L, turns)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun files(name: (String) -> Boolean): List<File> = sd.files(name)

    private inline fun <T> guarded(fallback: T, block: () -> T): T = sd.guarded(fallback, block)
}

/** History's vault in this process (main and :voice). */
object HistoryFiles {
    @Volatile private var vault: HistoryVault? = null

    /** Tests only (Robolectric has no Android Keystore): the keys a new vault takes. */
    @Volatile internal var keys: () -> MemoryKeys = { KeystoreMemoryKeys(unlockOnly = setOf(HistoryKeyAliases.UNLOCKED)) }

    fun vault(ctx: Context): HistoryVault = vault?.takeIf { it.dir == Paths.conversations(ctx) } ?: synchronized(this) {
        vault?.takeIf { it.dir == Paths.conversations(ctx) } ?: run {
            val app = ctx.applicationContext
            HistoryVault(Paths.conversations(app), keys(), File(Paths.dir(app), "history.lock"), locked = { deviceLocked(app) }).also { vault = it } // l10n: not text (a file name)
        }
    }

    /** The cache of this process's vault dropped, if it has one (screen off; :voice when main deleted conversations). */
    fun drop() { vault?.drop() }
    /** seal-history-fix-c 3: the main process deleted [id] (null: all); see [HistoryVault.forgetDeleted]. */
    fun forgetDeleted(id: String?) { val again = vault?.forgetDeleted(id) ?: return; Thread(again, "history-forget").start() }

    /** Tests only: the next [vault] is made again, with [k]. */
    internal fun testKeys(k: MemoryKeys) { keys = { k }; vault = null }

    /** The device is locked (the unlock-only key can't be used). Unknown counts as locked. */
    private fun deviceLocked(ctx: Context): Boolean =
        try { ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked } catch (_: Exception) { true }
}
