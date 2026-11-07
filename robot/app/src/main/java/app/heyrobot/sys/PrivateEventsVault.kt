// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.KeyguardManager
import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.calendar.PrivateEdit
import app.heyrobot.core.calendar.PrivateEnvelope
import app.heyrobot.core.calendar.PrivateEventsCodec
import app.heyrobot.core.calendar.PrivateEventsFile
import app.heyrobot.core.calendar.PrivateRead
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import app.heyrobot.core.writeAtomicBytes
import org.json.JSONArray
import java.io.File

// MAIN AND VOICE PROCESSES. Private events' vault (home-design H3, §6.3), kept as the lists' vault keeps lists: one
// Android Keystore AES-256-GCM key, robot_calendar_v1, made with setUnlockedDeviceRequired(true) through memory's key class
// (KeystoreMemoryKeys, this alias passed as unlock-only), seals files/calendar/events.enc (credential-encrypted storage,
// never in Android's backup; Robot's own backup carries the plain JSON inside its encryption, BackupCategory.CALENDAR).
// While the phone is locked (the lock screen, a phone lock, or the device locked) nothing is read or changed. A file Robot
// can't read (key gone, damaged, a newer Robot's) is never written over. Nothing here logs a title or a place.

/** What opening private events gave. */
sealed class PrivateLoad {
    data class Loaded(val file: PrivateEventsFile) : PrivateLoad()
    /** The phone is locked, or the Keystore can't answer now: "not now", never "gone". */
    data object Locked : PrivateLoad()
    /** The key is gone or the file is damaged: kept as it is, never written over. */
    data object Unreadable : PrivateLoad()
    data class TooNew(val v: Int) : PrivateLoad()
}

class PrivateEventsVault(val dir: File, private val keys: MemoryKeys, private val locked: () -> Boolean, private val now: () -> Long = System::currentTimeMillis) {
    val file = File(dir, FILE)
    private val lockFile = File(dir, LOCK_FILE)

    fun load(): PrivateLoad {
        if (locked()) return PrivateLoad.Locked
        return guarded { withFileLock(lockFile) { read() } }
    }

    /** One change on an unlocked phone; written only when [change] gave no refusal. */
    fun update(change: (PrivateEventsFile, Long) -> PrivateEdit): Pair<PrivateLoad, PrivateEdit?> {
        if (locked()) return PrivateLoad.Locked to null
        var edit: PrivateEdit? = null
        val load = guarded {
            withFileLock(lockFile) {
                val cur = read() as? PrivateLoad.Loaded ?: return@withFileLock read()
                val e = change(cur.file, now()).also { edit = it }
                if (e.refused != null || e.file == cur.file) cur else write(e.file)
            }
        }
        return load to edit
    }

    /** A restore's merge ([change] gets the events here), written under this phone's key. */
    fun restore(change: (PrivateEventsFile) -> PrivateEventsFile): PrivateLoad {
        if (locked()) return PrivateLoad.Locked
        return guarded {
            withFileLock(lockFile) {
                val cur = read() as? PrivateLoad.Loaded ?: return@withFileLock read()
                write(change(cur.file))
            }
        }
    }

    /**
     * Start over after private events Robot can't read (key gone, damaged, a newer Robot's), as Lists does: events.enc is
     * kept aside as events.enc.bad (an older one there is kept too, under the next free name), never deleted, and the next
     * save begins an empty file. Only when that is really the case, and only unlocked. True once the file is aside.
     */
    fun startOver(): Boolean {
        if (locked()) return false
        return try {
            withFileLock(lockFile) {
                val cur = guarded { read() }
                if (cur != PrivateLoad.Unreadable && cur !is PrivateLoad.TooNew) return@withFileLock false
                val to = (1..9).map { File(dir, if (it == 1) BAD else "$BAD$it") }.firstOrNull { !it.exists() } ?: return@withFileLock false
                file.renameTo(to).also { if (it) RLog.i("private_events_start_over") }
            }
        } catch (t: Exception) {
            RLog.w("private_events_start_over", t)
            false
        }
    }

    private fun read(): PrivateLoad {
        if (!file.isFile) return PrivateLoad.Loaded(PrivateEventsFile())
        val plain = try { PrivateEnvelope.open(file.readBytes()) { alias -> keys.cipher(alias, create = false) } } catch (e: MemoryKeyException) {
            return if (e.problem == KeyProblem.LOCKED) PrivateLoad.Locked else PrivateLoad.Unreadable
        }
        return when (val r = PrivateEventsCodec.decode(String(plain, Charsets.UTF_8))) {
            is PrivateRead.Ok -> PrivateLoad.Loaded(r.file)
            is PrivateRead.TooNew -> PrivateLoad.TooNew(r.v)
            PrivateRead.Damaged -> PrivateLoad.Unreadable
        }
    }

    private fun write(f: PrivateEventsFile): PrivateLoad {
        dir.mkdirs()
        // A key Android invalidated is replaced once, only for a new file (none on disk sealed under it), as Lists' (F5).
        val c = try { keys.cipher(PrivateEnvelope.ALIAS, create = true) } catch (e: MemoryKeyException) {
            if (e.problem != KeyProblem.GONE || file.exists()) throw e
            keys.delete(PrivateEnvelope.ALIAS)
            keys.cipher(PrivateEnvelope.ALIAS, create = true)
        } ?: return PrivateLoad.Locked
        writeAtomicBytes(file, PrivateEnvelope.seal(PrivateEventsCodec.encode(f).toByteArray(Charsets.UTF_8), c))
        return PrivateLoad.Loaded(f)
    }

    private inline fun guarded(block: () -> PrivateLoad): PrivateLoad = try { block() } catch (e: MemoryKeyException) {
        if (e.problem == KeyProblem.LOCKED) PrivateLoad.Locked else PrivateLoad.Unreadable
    } catch (t: Throwable) {
        RLog.w("private_events", t)
        PrivateLoad.Locked
    }

    companion object {
        const val FILE = "events.enc"
        const val LOCK_FILE = "events.lock"
        const val BAD = "events.enc.bad"
    }
}

/** Private events as read now; [locked]: some are sealed on this phone and wait for the unlock. */
data class PrivateNow(val events: List<app.heyrobot.core.calendar.PrivateEvent>, val locked: Boolean)

/** This app's private events vault and the record of phone events Robot added. */
object PrivateEventsFiles {
    /** files/calendar: credential-encrypted storage, events.enc sealed again under robot_calendar_v1. */
    fun dir(ctx: Context): File = File(ctx.filesDir, "calendar")

    /** Tests only: keys in memory instead of the Keystore (Robolectric has none). */
    @Volatile var testKeys: MemoryKeys? = null

    fun vault(ctx: Context): PrivateEventsVault {
        val app = ctx.applicationContext
        return PrivateEventsVault(dir(app), testKeys ?: KeystoreMemoryKeys(unlockOnly = setOf(PrivateEnvelope.ALIAS)), locked = { locked(app) })
    }

    /** As Robot Lists: the lock screen is up, a phone lock is on, or the device is locked. */
    fun locked(ctx: Context): Boolean = Assistant.locked(ctx) ||
        try { ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked } catch (_: Exception) { true }

    /** The private events now, or an empty list when they can't be opened (locked, unreadable). */
    fun events(ctx: Context): List<app.heyrobot.core.calendar.PrivateEvent> = now(ctx).events

    /**
     * [events], and whether private events wait for the unlock: the vault is locked and events.enc is there and not empty.
     * That much is known without opening it; no title or time is kept in the clear for it.
     */
    fun now(ctx: Context): PrivateNow = when (val l = vault(ctx).load()) {
        is PrivateLoad.Loaded -> PrivateNow(l.file.events, false)
        PrivateLoad.Locked -> PrivateNow(emptyList(), try { File(dir(ctx), PrivateEventsVault.FILE).let { it.isFile && it.length() > 0 } } catch (_: Exception) { false })
        else -> PrivateNow(emptyList(), false)
    }

    /**
     * The provider ids of events Robot added (add_event), so Robot may change them and no others (LinkCalendar's rule).
     * They name rows of this phone's provider, so they stay out of every backup (noBackupFilesDir).
     */
    fun addedFile(ctx: Context) = File(ctx.noBackupFilesDir, "calendar_added.json")

    fun added(ctx: Context): Set<Long> = try {
        val f = addedFile(ctx)
        if (!f.isFile) emptySet() else JSONArray(f.readText()).let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toLong() }.toSet() }
    } catch (t: Throwable) { RLog.w("calendar_added", t); emptySet() }

    @Synchronized fun recordAdded(ctx: Context, id: Long) {
        try { writeAtomic(addedFile(ctx), JSONArray((added(ctx) + id).toList().takeLast(MAX_ADDED)).toString()) } catch (t: Throwable) { RLog.w("calendar_added", t) }
    }

    @Synchronized fun forgetAdded(ctx: Context, id: Long) {
        try { writeAtomic(addedFile(ctx), JSONArray((added(ctx) - id).toList()).toString()) } catch (t: Throwable) { RLog.w("calendar_added", t) }
    }

    private const val MAX_ADDED = 2_000
}
