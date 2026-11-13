// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.SealedEnvelope
import app.heyrobot.core.SealedOpen
import app.heyrobot.core.logbook.LogEntry
import app.heyrobot.core.logbook.LogbookDelete
import app.heyrobot.core.logbook.LogbookCodec
import app.heyrobot.core.logbook.LogbookEnvelope
import app.heyrobot.core.logbook.LogbookLimits
import app.heyrobot.core.logbook.LogbookSettings
import app.heyrobot.core.logbook.LogbookSettingsCodec
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.readForUpdate
import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomicBytes
import java.io.File

// MAIN AND VOICE PROCESSES. The logbook (docs/plans/spoken-log-plan.md §7, §8, §12) sealed with its own keys, as Notes are
// (core SealedEnvelope, sys/SealedDir). robot_logbook_v1 (setUnlockedDeviceRequired) seals the whole log; robot_logbook_lock_v1,
// without it, seals one entry added while the phone is locked, merged at the next unlock. Reading needs the phone unlocked.
//
// The files, under files/logbook/ (credential-encrypted storage, never in Android's backup):
//   log.enc           every entry, sealed (LogbookEnvelope); the name and the header are in the additional data
//   <id>.locked.enc   one entry added while locked, under the lock-screen key
//   full              empty, only while the log holds MAX_ENTRIES: an add while locked is refused, not dropped at unlock
//   cleared           when Delete all last ran (plain millis): an Undo of an entry deleted before it is refused
//   *.bad, *.bad2..9  kept aside (key gone, or damaged); deleted only by Delete all
// Both processes take files/robot/logbook.lock for every read and change. Nothing here logs an entry's words.

object LogbookKeyAliases {
    const val UNLOCKED = "robot_logbook_v1"
    const val LOCK_SCREEN = "robot_logbook_lock_v1"
}

sealed class LogbookRead {
    /** [entries] as stored (oldest first); [waiting], adds in the journal not yet merged. */
    data class Ok(val entries: List<LogEntry>, val waiting: Int = 0) : LogbookRead()
    data object Locked : LogbookRead()
    /** A newer Robot's log, or a key that can't be used now: nothing is written over it. */
    data object NotNow : LogbookRead()
}

enum class LogbookAdd { ADDED, ADDED_LOCKED, NOT_WHILE_LOCKED, FULL, FAILED }

class LogbookVault(val dir: File, keys: MemoryKeys, private val lockFile: File, private val locked: () -> Boolean) : app.heyrobot.core.logbook.LogbookDelete.Store {
    private val sd = SealedDir(dir, keys, "logbook") // l10n: not text (a log code)
    private val log get() = File(dir, LOG + SealedEnvelope.SEALED)
    private fun journal(id: String) = File(dir, id + SealedEnvelope.LOCKED)
    /** Plain and empty: the log is full, so an add while locked is refused (its size can't be read then). */
    private val fullMark get() = File(dir, FULL)
    private val cleared get() = File(dir, CLEARED)
    private fun journals() = sd.files { n -> LogbookEnvelope.frame.idOf(n)?.second == true }

    /** The log, journal merged first; locked: [LogbookRead.Locked]. Off the main thread. */
    fun read(): LogbookRead = sd.guarded<LogbookRead>(LogbookRead.NotNow) { withFileLock(lockFile) { readNow() } }

    private fun readNow(): LogbookRead {
        if (locked()) return LogbookRead.Locked
        sd.dropCutWrites()
        val have = when (val o = openLog()) {
            is SealedOpen.Ok -> o.value
            null -> emptyList()
            SealedOpen.Locked -> return LogbookRead.Locked
            is SealedOpen.TooNew -> return LogbookRead.NotNow
            SealedOpen.KeyGone, SealedOpen.Unreadable -> { sd.aside(log); emptyList() }
        }
        val js = journals()
        if (js.isEmpty()) return LogbookRead.Ok(have)
        val opened = ArrayList<Pair<File, LogEntry>>()
        for (j in js) when (val o = openOne(j)) {
            is SealedOpen.Ok -> opened += j to o.value
            SealedOpen.KeyGone, SealedOpen.Unreadable -> sd.aside(j)
            else -> Unit
        }
        // spoken-log-4: only what fits joins the log, oldest added first; the rest keep waiting in the journal, never deleted unmerged.
        val ids = have.mapTo(HashSet()) { it.id }
        val fresh = opened.map { it.second }.filter { it.id !in ids }.distinctBy { it.id }.sortedBy { it.added }
        val all = have + fresh.take((LogbookLimits.MAX_ENTRIES - have.size).coerceAtLeast(0))
        val inLog = all.mapTo(HashSet()) { it.id }
        val merged = opened.filter { it.second.id in inLog }.map { it.first }
        return try {
            if (all.size != have.size) write(all)
            merged.forEach { it.delete() }
            LogbookRead.Ok(all, js.size - merged.size)
        } catch (e: MemoryKeyException) {
            if (e.problem == KeyProblem.LOCKED) LogbookRead.Locked else throw e
        }
    }

    /** [e] added: to the log unlocked, to the journal locked when [whileLocked]. */
    fun add(e: LogEntry, whileLocked: Boolean): LogbookAdd = sd.guarded(LogbookAdd.FAILED) {
        withFileLock(lockFile) {
            if (locked()) addLocked(e, whileLocked) else when (val r = readNow()) {
                is LogbookRead.Ok -> if (r.entries.size >= LogbookLimits.MAX_ENTRIES) LogbookAdd.FULL else try {
                    write(r.entries + e); LogbookAdd.ADDED
                } catch (x: MemoryKeyException) {
                    if (x.problem == KeyProblem.LOCKED) addLocked(e, whileLocked) else throw x
                }
                LogbookRead.Locked -> addLocked(e, whileLocked)
                LogbookRead.NotNow -> LogbookAdd.FAILED
            }
        }
    }

    private fun addLocked(e: LogEntry, whileLocked: Boolean): LogbookAdd {
        if (!whileLocked) return LogbookAdd.NOT_WHILE_LOCKED
        if (fullMark.exists()) return LogbookAdd.FULL // spoken-log-4: said now, not dropped at the next unlock
        if (journals().size >= LogbookLimits.MAX_JOURNAL) return LogbookAdd.FULL
        val j = journal(e.id)
        writeAtomicBytes(j, seal(j.name, LogbookKeyAliases.LOCK_SCREEN) { LogbookEnvelope.sealOne(e, it, j.name) })
        return LogbookAdd.ADDED_LOCKED
    }

    /** The entries with these [ids] removed; the removed ones back, or null when nothing was changed. */
    override fun delete(ids: Set<String>): List<LogEntry>? = change { all -> all.filter { it.id !in ids } }?.let { (before, _) -> before.filter { it.id in ids } }

    /** Undo: [back] put again (an id already there is left as it is). */
    override fun restore(back: List<LogEntry>, deletedAt: Long): LogbookDelete.Back = sd.guarded(LogbookDelete.Back.NOT_NOW) {
        withFileLock(lockFile) {
            if (deletedAt <= clearedAt()) return@withFileLock LogbookDelete.Back.GONE // spoken-log-4: Delete all ends every Undo
            val r = readNow() as? LogbookRead.Ok ?: return@withFileLock LogbookDelete.Back.NOT_NOW
            val ids = r.entries.mapTo(HashSet()) { it.id }
            val after = (r.entries + back.filter { it.id !in ids }).sortedBy { it.at }
            // spoken-log-4: never past the cap, or the reader refuses the whole log
            if (after.size > LogbookLimits.MAX_ENTRIES) return@withFileLock LogbookDelete.Back.NOT_NOW
            if (after.size != r.entries.size) write(after)
            LogbookDelete.Back.DONE
        }
    }

    /** A backup's entries: Replace takes them whole, Add puts those not here yet. False: the log can't be opened now. */
    fun fromBackup(back: List<LogEntry>, replace: Boolean): Boolean = sd.guarded(false) {
        withFileLock(lockFile) {
            val r = readNow() as? LogbookRead.Ok ?: return@withFileLock false
            val ids = r.entries.mapTo(HashSet()) { it.id }
            val after = if (replace) back.distinctBy { it.id }.sortedBy { it.at }.takeLast(LogbookLimits.MAX_ENTRIES) else {
                // spoken-log-4: Add keeps every entry here; only the backup's newest that fit join them.
                val add = back.filter { it.id !in ids }.distinctBy { it.id }.sortedBy { it.at }
                (r.entries + add.takeLast((LogbookLimits.MAX_ENTRIES - r.entries.size).coerceAtLeast(0))).sortedBy { it.at }
            }
            if (after != r.entries) write(after)
            true
        }
    }

    /**
     * Every entry gone: the log, the journal, the kept-aside copies and cut writes (spoken-log-4, so "This can't be undone"
     * holds), and every Undo made before now ends ([clearedAt]).
     */
    fun deleteAll(): Boolean = sd.guarded(false) {
        withFileLock(lockFile) {
            writeAtomicBytes(cleared, System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8))
            val gone = journals() + sd.asideFiles() + sd.files { it.startsWith(".") && it.endsWith(".tmp") } + listOf(log, fullMark) // l10n: not text (file names)
            gone.forEach { it.delete() }
            gone.none { it.exists() }
        }
    }

    /** When Delete all last ran (0: never); a stamp that doesn't read ends every Undo (fail closed). */
    private fun clearedAt(): Long = if (!cleared.exists()) 0L else readOrNull(cleared)?.trim()?.toLongOrNull() ?: Long.MAX_VALUE

    private fun change(f: (List<LogEntry>) -> List<LogEntry>): Pair<List<LogEntry>, List<LogEntry>>? = sd.guarded(null) {
        withFileLock(lockFile) {
            val r = readNow() as? LogbookRead.Ok ?: return@withFileLock null
            val after = f(r.entries)
            if (after == r.entries) return@withFileLock null
            write(after)
            r.entries to after
        }
    }

    /** spoken-log-4: refuses (an exception, so FAILED or false) a log the reader would refuse whole on its next read. */
    private fun write(all: List<LogEntry>) {
        check(all.size <= LogbookLimits.MAX_ENTRIES && LogbookCodec.fromJson(LogbookCodec.toJson(all)) != null) { "unreadable" } // l10n: not text (an exception's message)
        writeAtomicBytes(log, seal(log.name, LogbookKeyAliases.UNLOCKED) { LogbookEnvelope.seal(all, it, log.name) })
        if (all.size >= LogbookLimits.MAX_ENTRIES) fullMark.createNewFile() else fullMark.delete()
    }

    private fun seal(name: String, alias: String, block: (app.heyrobot.core.memory.MemoryCipher) -> ByteArray): ByteArray =
        sd.rekeyingOnGone(alias, if (alias == LogbookKeyAliases.UNLOCKED) "logbook" else "lock", { LogbookEnvelope.frame.idOf(it) != null }, // l10n: not text (log codes)
            LogbookEnvelope.frame::alias, {}) { block(sd.key(alias, create = true) ?: throw MemoryKeyException(KeyProblem.GONE)) }

    private fun openLog(): SealedOpen<List<LogEntry>>? {
        if (!log.isFile) return null
        val bytes = try { readForUpdate(log) } catch (_: Exception) { return SealedOpen.Locked } ?: return SealedOpen.Unreadable
        return LogbookEnvelope.open(bytes, { a -> if (a != LogbookKeyAliases.UNLOCKED) throw MemoryKeyException(KeyProblem.BAD_DATA) else sd.key(a, create = false) }, log.name)
    }

    private fun openOne(j: File): SealedOpen<LogEntry> {
        val id = LogbookEnvelope.frame.idOf(j.name)?.first ?: return SealedOpen.Unreadable
        val bytes = try { readForUpdate(j) } catch (_: Exception) { return SealedOpen.Locked } ?: return SealedOpen.Unreadable
        val o = LogbookEnvelope.openOne(bytes, { a -> if (a != LogbookKeyAliases.LOCK_SCREEN) throw MemoryKeyException(KeyProblem.BAD_DATA) else sd.key(a, create = false) }, j.name)
        return if (o is SealedOpen.Ok && o.value.id != id) SealedOpen.Unreadable else o
    }

    private companion object {
        const val LOG = "log" // l10n: not text (a file name)
        const val FULL = "full" // l10n: not text (a file name)
        const val CLEARED = "cleared" // l10n: not text (a file name)
    }
}

/** The logbook's vault and settings in this process (main and :voice). */
object LogbookFiles {
    @Volatile private var vault: LogbookVault? = null

    /** Tests only (Robolectric has no Android Keystore): the keys a new vault takes. */
    @Volatile internal var keys: () -> MemoryKeys = { KeystoreMemoryKeys(unlockOnly = setOf(LogbookKeyAliases.UNLOCKED)) }

    fun dir(ctx: Context): File = File(ctx.filesDir, "logbook").apply { mkdirs() } // l10n: not text (a folder name)

    fun store(ctx: Context): LogbookVault = vault?.takeIf { it.dir == dir(ctx) } ?: synchronized(this) {
        vault?.takeIf { it.dir == dir(ctx) } ?: run {
            val app = ctx.applicationContext
            LogbookVault(dir(app), keys(), File(Paths.dir(app), "logbook.lock"), locked = { deviceLocked(app) }).also { vault = it } // l10n: not text (a file name)
        }
    }

    fun settingsFile(ctx: Context): File = File(Paths.dir(ctx), LogbookSettingsCodec.FILE)

    fun settings(ctx: Context): LogbookSettings = LogbookSettingsCodec.decode(readOrNull(settingsFile(ctx)))

    fun saveSettings(ctx: Context, s: LogbookSettings): Boolean = try {
        writeAtomicBytes(settingsFile(ctx), LogbookSettingsCodec.encode(s).toByteArray(Charsets.UTF_8)); true
    } catch (t: Exception) {
        RLog.w("logbook_settings", t) // l10n: not text (a log code)
        false
    }

    /** Tests only: the next [store] is made again, with [k]. */
    internal fun testKeys(k: MemoryKeys) { keys = { k }; vault = null }

    private fun deviceLocked(ctx: Context): Boolean =
        try { ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked } catch (_: Exception) { true }
}
