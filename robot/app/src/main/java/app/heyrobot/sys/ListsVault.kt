// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.security.keystore.KeyPermanentlyInvalidatedException
import app.heyrobot.core.RLog
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.JournalAppend
import app.heyrobot.core.lists.JournalRefusal
import app.heyrobot.core.lists.ListsCodec
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsEnvelope
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsJournal
import app.heyrobot.core.lists.ListsJournalFile
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsMerged
import app.heyrobot.core.lists.ListsMergeResult
import app.heyrobot.core.lists.ListsRefusal
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.lists.ListsSettingsCodec
import app.heyrobot.core.lists.ListsSettingsFile
import app.heyrobot.core.lists.ListsWho
import app.heyrobot.core.lists.NewItem
import app.heyrobot.core.lock.SomeoneElseLock
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.readForUpdate
import app.heyrobot.core.readOrNull
import app.heyrobot.core.readTextForUpdate
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import app.heyrobot.core.writeAtomicBytes
import app.heyrobot.lock.PhoneLockNow
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

// MAIN AND VOICE PROCESSES. Robot Lists' vault (lists-plan.md §4, §5; decisions D1 to D3, LS2). Core decides what a file
// means (ListsEnvelope, ListsCodec, ListsJournal, ListsEdits); this file holds the keys, the files, the lock and the gate.
//
// The keys: two Android Keystore AES-256-GCM keys (TEE, not StrongBox, no user authentication, randomized, never exported),
// made through memory's key class (KeystoreMemoryKeys, its unlock-only aliases passed in): robot_lists_v1 with
// setUnlockedDeviceRequired(true) seals the lists; robot_lists_lock_v1, without it, seals the journal of adds said while
// locked, and the lists themselves while "Hear and tick on the lock screen" is on. Each file's header names its key and
// only Lists' own aliases are ever asked for (anything else is damaged data, never another feature's key). A key is made
// only for a new file on an unlocked phone; a key Android invalidated is replaced only when nothing is sealed under it
// (as Files' F5); a key is deleted only by a switch you turn (follow) or by that replacement. A tag that fails is
// damaged data; anything else from the Keystore is "not now", never "gone".
//
// The files, under files/lists/ (credential-encrypted storage, never in Android's backup):
//   lists.enc       the lists, sealed; never written over a file Robot couldn't read (locked, key gone, damaged, newer)
//   locked.enc      the journal: your lists' ids and folded names, refreshed at every write, and the adds said while
//                   locked, merged into lists.enc by item id at every open (twice changes nothing) and never read to answer
//   lists.lock      both app processes take it for every read and change
//   lists.enc.bad   kept aside by Start over; locked.enc.bad a journal that can't be read; never deleted by Robot itself
//
// The gate: while the phone is locked (the lock screen, a phone lock, or the device locked) nothing is read or changed,
// and the copy in RAM goes, unless "Hear and tick on the lock screen" is on, as SomeoneElseLock.lists narrows it; then
// only an add, a tick or an untick (LockedEdit), never a new file, a new key or a re-key. An add while locked otherwise
// goes to the journal (addLocked), while "Add to lists while locked" is on. The RAM copy also goes at every screen-off (D2;
// at every one, as Files' index: with a lock delay the phone locks later, with no broadcast).
//
// Nothing here logs a list's name or an item: codes and counts only.

/** Lists' two Keystore keys (lists-plan.md §4.2 D1), named here alone. */
object ListsKeyAliases {
    /** The lists: needs the phone unlocked. */
    const val UNLOCKED = "robot_lists_v1"
    /** The journal of adds said while locked, and the lists with "Hear and tick on the lock screen" on: works while locked. */
    const val LOCK_SCREEN = "robot_lists_lock_v1"
}

/** What a change gave: [load], the lists now (or why they couldn't be opened: nothing changed); [edit], the change's own answer. */
data class ListsWrite(val load: ListsLoad<ListsFile>, val edit: ListsEdit? = null) {
    val done: ListsEdit.Done? get() = edit as? ListsEdit.Done
}

/** What may change while the phone is locked, with "Hear and tick on the lock screen" on (§5): an add, a tick, an untick. */
sealed class LockedEdit {
    data class Add(val list: ListsId, val items: List<NewItem>) : LockedEdit()
    data class Tick(val list: ListsId, val item: ListsId) : LockedEdit()
    data class Untick(val list: ListsId, val item: ListsId) : LockedEdit()
}

/** What an add said while locked gave (the journal, §4.6). */
sealed class LockedAdd {
    /** Waiting, sealed: [ids], the items' ids once merged. */
    data class Ok(val ids: List<ListsId>) : LockedAdd()
    data class No(val why: JournalRefusal) : LockedAdd()
    /** "Add to lists while locked" (or Lists) is off, or a phone lock for someone else is on: nothing is added. */
    data object Off : LockedAdd()
    /** The journal can't be read or written now: "Robot couldn't add that while the phone is locked." */
    data object Failed : LockedAdd()
}

/** What turning Settings › Lists' lock switches gave (ListsVault.follow). */
sealed class ListsFollow {
    data class Done(val load: ListsLoad<ListsFile>) : ListsFollow()
    /** The lists or the journal couldn't be used now ([load] says why): nothing changed. */
    data class Problem(val load: ListsLoad<*>) : ListsFollow()
    /** [n] adds said while locked still wait for room on a full list: "Add to lists while locked" stays on until they fit. */
    data class Waiting(val n: Int) : ListsFollow()
}

/** What a restore's lists gave (LS7, ListsVault.restore). */
sealed class ListsRestore {
    /** Written, sealed under this phone's key: [merged], what the merge did, in counts. */
    data class Done(val merged: ListsMerged) : ListsRestore()
    /** The lists would be over 2 MiB: nothing changed. */
    data object TooBig : ListsRestore()
    /** The lists here couldn't be used now ([load] says why: locked, damaged, a newer Robot's): nothing changed. */
    data class Problem(val load: ListsLoad<*>) : ListsRestore()
}

/**
 * Robot Lists on [dir] (files/lists/ on the phone) for one process. [settings]: Settings › Lists as the file says;
 * [someoneElse]: a phone lock for someone else is on (both lock switches then read as off); [locked]: the phone is locked
 * now. Pure java.io around [keys], so the JVM tests run it with software keys.
 */
class ListsVault(
    val dir: File,
    private val keys: MemoryKeys,
    private val settings: () -> ListsSettings,
    private val someoneElse: () -> Boolean,
    private val locked: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    random: SecureRandom = SecureRandom(),
    /** lists.enc was written in this process (LS6: the widgets drawn again); never under a reader's lock for long. */
    private val changed: () -> Unit = {},
) {
    private val ids: () -> ListsId = { ListsId.new(random) }
    private val listsFile = File(dir, ListsEnvelope.FILE)
    private val journalFile = File(dir, ListsEnvelope.JOURNAL)
    private val lockFile = File(dir, LOCK_FILE)

    /** The lists as last opened in this process while unlocked, with the bytes they came from. */
    private class Cached(val lists: ByteArray?, val journal: ByteArray?, val cur: Current)
    @Volatile private var cache: Cached? = null

    /**
     * The lists opened under the lock: [alias], lists.enc's key; [journal], the journal as it now holds (its adds after the
     * merge, its names refreshed), null when it can't be used now (its key busy, or a newer Robot's); [disk], the journal as
     * it is on disk (null: no journal file).
     */
    private class Current(val load: ListsLoad<ListsFile>, val alias: String?, val journal: ListsJournalFile?, val disk: ListsJournalFile?)

    /** Settings › Lists as they apply to the lock now: both lock switches off during a phone lock for someone else. */
    private fun narrowed(): ListsSettings = SomeoneElseLock.lists(settings(), someoneElse())

    /** Whether lists may be heard or changed now: unlocked, or locked with "Hear and tick on the lock screen" on. */
    private fun open(lockedNow: Boolean): Boolean = !lockedNow || narrowed().let { it.on && it.lockScreen }

    /** The RAM copy goes (screen-off, Cmd.LISTS, the phone locked). */
    fun drop() {
        cache = null
    }

    /**
     * The lists as this phone can open them now, the journal's adds merged in first (§4.6 step 4). On an unlocked phone with
     * Lists on and no file at all yet, the first-use lists (Shopping and To-do, Q2) are written, so their ids stay, unless
     * [firstUse] is false (Settings › Lists only counts them: Empty then).
     */
    fun load(firstUse: Boolean = true): ListsLoad<ListsFile> {
        val lockedNow = locked()
        if (!open(lockedNow)) {
            drop()
            return ListsLoad.Locked
        }
        return guarded { withFileLock(lockFile) { current(lockedNow, create = firstUse && !lockedNow && settings().on).load } }
    }

    /**
     * One change on an unlocked phone ([change] gets the lists and who makes it: you, or [by] content, §8.2's items from a
     * screen or a message added through a card; by [how]). Its answer is kept as it is; the file is written only when it
     * changed. Locked: nothing (LockedEdit is the only way then).
     */
    fun update(how: ItemHow, by: ItemBy = ItemBy.YOU, change: (ListsFile, ListsWho) -> ListsEdit): ListsWrite {
        require(by == ItemBy.YOU || by == ItemBy.CONTENT)
        if (locked()) {
            drop()
            return ListsWrite(ListsLoad.Locked)
        }
        return guardedWrite {
            withFileLock(lockFile) {
                val cur = current(lockedNow = false, create = true)
                val f = (cur.load as? ListsLoad.Loaded)?.value ?: return@withFileLock ListsWrite(cur.load)
                commit(cur, change(f, ListsWho(by, how, now())), lockedNow = false)
            }
        }
    }

    /**
     * An add, a tick or an untick (§5): on an unlocked phone as you, or while locked with "Hear and tick on the lock
     * screen" on, by "locked", into lists that already exist and only with the keys they have.
     */
    fun lockedEdit(e: LockedEdit, how: ItemHow): ListsWrite {
        val lockedNow = locked()
        if (!open(lockedNow)) {
            drop()
            return ListsWrite(ListsLoad.Locked)
        }
        return guardedWrite {
            withFileLock(lockFile) {
                val cur = current(lockedNow, create = !lockedNow)
                val f = (cur.load as? ListsLoad.Loaded)?.value ?: return@withFileLock ListsWrite(cur.load)
                val who = ListsWho(if (lockedNow) ItemBy.LOCKED else ItemBy.YOU, how, now())
                commit(cur, when (e) {
                    is LockedEdit.Add -> ListsEdits.add(f, e.list, e.items, who, ids)
                    is LockedEdit.Tick -> ListsEdits.tick(f, e.list, e.item, who)
                    is LockedEdit.Untick -> ListsEdits.untick(f, e.list, e.item, who)
                }, lockedNow)
            }
        }
    }

    /**
     * While locked (§4.6 step 2): the lists the journal knows, by id, role and folded name, to match a list you name. Never
     * an item (the adds stay inside core). Locked unless "Add to lists while locked" and Lists are on.
     */
    fun lockedLists(): ListsLoad<ListsJournalFile> {
        val s = narrowed()
        if (!s.on || !s.addLocked) return ListsLoad.Locked
        return try {
            withFileLock(lockFile) { ListsEnvelope.openJournal(read(journalFile), ::journalCipher) }
        } catch (t: Exception) {
            RLog.w("lists_journal_read", t)
            ListsLoad.Locked
        }
    }

    /** [items] (read by ItemText) for [list], waiting in the journal until the next open on an unlocked phone (§4.6 step 3). */
    fun addLocked(list: ListsId, items: List<NewItem>): LockedAdd {
        val s = narrowed()
        if (!s.on || !s.addLocked) return LockedAdd.Off
        return try {
            withFileLock(lockFile) {
                val bytes = read(journalFile)
                val journal = when (val l = ListsEnvelope.openJournal(bytes, ::journalCipher)) {
                    is ListsLoad.Loaded -> l.value
                    // No journal: it knows no list yet ("Unlock your phone to make one").
                    ListsLoad.Empty -> ListsJournalFile()
                    else -> return@withFileLock LockedAdd.Failed
                }
                when (val r = ListsJournal.append(journal, list, items, now(), ids)) {
                    is JournalAppend.No -> LockedAdd.No(r.why)
                    is JournalAppend.Ok -> {
                        val out = sealed(ListsKeyAliases.LOCK_SCREEN, create = false) { ListsEnvelope.sealJournal(r.journal, it) }
                            ?: return@withFileLock LockedAdd.Failed
                        writeAtomicBytes(journalFile, out)
                        LockedAdd.Ok(r.ids)
                    }
                }
            }
        } catch (t: Exception) {
            if (t !is MemoryKeyException) RLog.w("lists_journal_add", t)
            LockedAdd.Failed
        }
    }

    /**
     * Settings › Lists' lock switches turned to [s], before they're saved (memory's order, so a crash between fails
     * closed), on an unlocked phone only (§4.5, §4.6):
     * - lists.enc sealed again with robot_lists_lock_v1 ("Hear and tick on the lock screen" on) or robot_lists_v1 (off),
     *   the unlock-only key deleted once the new file is written, as memory's rekey does;
     * - "Add to lists while locked" on: the journal's names written; off: its adds merged first, then it goes (while an add
     *   still waits for room on a full list, nothing changes: [ListsFollow.Waiting]);
     * - robot_lists_lock_v1 deleted once neither switch needs it, after the lists moved off it and the journal went.
     */
    fun follow(s: ListsSettings): ListsFollow {
        if (locked()) {
            drop()
            return ListsFollow.Problem(ListsLoad.Locked)
        }
        return try {
            withFileLock(lockFile) {
                val cur = current(lockedNow = false, create = true)
                val f = (cur.load as? ListsLoad.Loaded)?.value ?: return@withFileLock ListsFollow.Problem(cur.load)
                val journal = cur.journal ?: return@withFileLock ListsFollow.Problem(journalProblem())
                if (!s.addLocked && journal.waiting > 0) return@withFileLock ListsFollow.Waiting(journal.waiting)
                val target = if (s.lockScreen) ListsKeyAliases.LOCK_SCREEN else ListsKeyAliases.UNLOCKED
                val from = checkNotNull(cur.alias)
                // LS10 (L7): all or nothing. Both files sealed first (a Keystore that can't answer now changes nothing), then
                // written, then the keys go: a failure never leaves the lists under one key and the switch saying the other.
                val want = if (s.addLocked) ListsJournal.snapshot(f, journal).takeIf { it != cur.disk } else null
                val journalOut = want?.let { w -> sealed(ListsKeyAliases.LOCK_SCREEN, create = true) { ListsEnvelope.sealJournal(w, it) }
                    ?: return@withFileLock ListsFollow.Problem(ListsLoad.Locked) }
                val listsOut = if (from == target) null else sealed(target, create = true) { ListsEnvelope.seal(f, it) }
                    ?: return@withFileLock ListsFollow.Problem(ListsLoad.Locked)
                // Off, the journal (no add waiting, so only its names) goes before anything is written.
                if (!s.addLocked && journalFile.exists() && !journalFile.delete()) {
                    return@withFileLock ListsFollow.Problem(ListsLoad.Locked)
                }
                listsOut?.let {
                    writeAtomicBytes(listsFile, it)
                    drop()
                    RLog.i("lists_rekey", "lockScreen" to s.lockScreen)
                }
                journalOut?.let { writeAtomicBytes(journalFile, it) }
                // The unlock-only key goes once nothing is sealed under it; the lock screen's once neither switch needs it.
                if (from == ListsKeyAliases.UNLOCKED && target != ListsKeyAliases.UNLOCKED) deleteKey(ListsKeyAliases.UNLOCKED)
                if (!s.lockKey) deleteKey(ListsKeyAliases.LOCK_SCREEN)
                drop()
                ListsFollow.Done(ListsLoad.Loaded(f))
            }
        } catch (e: MemoryKeyException) {
            ListsFollow.Problem(problem(e.problem))
        } catch (t: Exception) {
            RLog.w("lists_follow", t)
            ListsFollow.Problem(ListsLoad.Locked)
        }
    }

    /**
     * Settings › Lists' Delete all lists (after its dialog, refused while locked): every list and item, the journal's adds,
     * and the copies kept aside. An empty lists file is written in their place, so the first-use lists don't come back.
     * The keys stay. False when it wasn't done.
     */
    fun deleteAll(): Boolean {
        if (locked()) {
            drop()
            return false
        }
        return try {
            withFileLock(lockFile) {
                drop()
                val all = dir.listFiles { f -> f.isFile && f.name != LOCK_FILE && (f.name.startsWith(ListsEnvelope.FILE) || f.name.startsWith(ListsEnvelope.JOURNAL) ||
                    f.name.startsWith(".${ListsEnvelope.FILE}") || f.name.startsWith(".${ListsEnvelope.JOURNAL}")) }.orEmpty()
                val gone = all.all { !it.exists() || it.delete() }
                if (gone) {
                    try {
                        writeFresh(ListsFile())
                        val s = settings()
                        if (s.addLocked) sealed(ListsKeyAliases.LOCK_SCREEN, create = true) { ListsEnvelope.sealJournal(ListsJournalFile(), it) }
                            ?.let { writeAtomicBytes(journalFile, it) }
                    } catch (t: Exception) {
                        // The lists are gone; only the empty file is missing, and the next open makes the first-use lists.
                        RLog.w("lists_delete_all_empty", t)
                    }
                    wrote()
                }
                gone
            }
        } catch (t: Exception) {
            RLog.w("lists_delete_all", t)
            false
        }
    }

    /**
     * Start over after lists Robot can't read (damaged, a newer Robot's, or their key gone; §4.5): lists.enc is kept aside
     * as lists.enc.bad (an older one there is kept too, under the next free name), the journal as locked.enc.bad, and the
     * first-use lists are made again (under a new key when the old one is gone). Only when that is really the case, and
     * only unlocked. True once the file is aside, even if the new lists have to wait for the next open.
     */
    fun startOver(): Boolean {
        if (locked()) {
            drop()
            return false
        }
        return try {
            withFileLock(lockFile) {
                val load = ListsEnvelope.open(read(listsFile), ::listsCipher)
                if (load != ListsLoad.Unreadable && load != ListsLoad.KeyGone && load !is ListsLoad.TooNew) return@withFileLock false
                drop()
                if (aside(listsFile, BAD) == null) return@withFileLock false
                if (journalFile.exists()) aside(journalFile, JOURNAL_BAD)
                RLog.i("lists_start_over", "state" to RLog.Code(stateCode(load)))
                wrote()
                try { current(lockedNow = false, create = true) } catch (t: Exception) { RLog.w("lists_start_over_new", t) }
                true
            }
        } catch (t: Exception) {
            RLog.w("lists_start_over", t)
            false
        }
    }

    /**
     * LS7, a restore (lists-plan.md §6), in :voice on an unlocked phone: [change] gets the lists here (none: an empty file)
     * and this vault's ids, and gives the lists to write (RestoreMerge.lists). They are sealed under this phone's key: the
     * one lists.enc has, or for a new file the one Settings › Lists' lock-screen switch names (the restored switches are
     * followed afterwards, ListsVault.follow). Lists whose key is gone are kept aside first (lists.enc.bad, as Start over
     * keeps them), never deleted; damaged or a newer Robot's are never written over. The journal's adds are merged into the
     * lists here first (so Add keeps them and Replace replaces them with the rest of this phone's lists); its names are
     * refreshed after.
     */
    fun restore(change: (ListsFile, () -> ListsId) -> ListsMergeResult): ListsRestore {
        if (locked()) {
            drop()
            return ListsRestore.Problem(ListsLoad.Locked)
        }
        return try {
            withFileLock(lockFile) {
                var cur = current(lockedNow = false, create = false)
                if (cur.load == ListsLoad.KeyGone) {
                    drop()
                    if (aside(listsFile, BAD) == null) return@withFileLock ListsRestore.Problem(cur.load)
                    RLog.i("lists_restore_aside", "state" to RLog.Code("key_gone"))
                    cur = current(lockedNow = false, create = false)
                }
                val have = when (val l = cur.load) {
                    is ListsLoad.Loaded -> l.value
                    ListsLoad.Empty -> ListsFile()
                    else -> return@withFileLock ListsRestore.Problem(l)
                }
                val merged = when (val r = change(have, ids)) {
                    is ListsMergeResult.Ok -> r.merged
                    ListsMergeResult.TooBig -> return@withFileLock ListsRestore.TooBig
                }
                val next = ListsEdits.purge(merged.file, now())
                if (cur.load is ListsLoad.Loaded) {
                    val out = sealed(checkNotNull(cur.alias), create = false) { ListsEnvelope.seal(next, it) } ?: throw MemoryKeyException(KeyProblem.GONE)
                    writeAtomicBytes(listsFile, out)
                    wrote()
                } else {
                    writeFresh(next)
                }
                drop()
                // The journal's names follow the restored lists.
                current(lockedNow = false, create = false)
                ListsRestore.Done(merged.copy(file = next))
            }
        } catch (e: MemoryKeyException) {
            drop()
            ListsRestore.Problem(problem(e.problem))
        } catch (t: Exception) {
            drop()
            RLog.w("lists_restore", t)
            ListsRestore.Problem(ListsLoad.Locked)
        }
    }

    // Under the lock.

    /**
     * The lists as they are: the RAM copy when nothing changed on disk (unlocked only); else lists.enc opened, the
     * first-use lists written when there is no file and [create], the journal's adds merged, its names refreshed.
     */
    private fun current(lockedNow: Boolean, create: Boolean): Current {
        val bytes = read(listsFile)
        val jBytes = read(journalFile)
        if (!lockedNow) cache?.let { c -> if (c.lists.contentEquals(bytes) && c.journal.contentEquals(jBytes)) return c.cur }
        // The journal first: one that can't be read is kept aside before anything is sealed again under its key.
        val j = journalNow(jBytes)
        var alias = ListsEnvelope.alias(bytes)
        var file = when (val l = ListsEnvelope.open(bytes, ::listsCipher)) {
            is ListsLoad.Loaded -> l.value
            ListsLoad.Empty -> {
                if (!create) return Current(ListsLoad.Empty, null, null, null)
                ListsEdits.firstUse(now(), ids).also { alias = writeFresh(it) }
            }
            else -> {
                drop()
                return Current(l, alias, null, null)
            }
        }
        val from = checkNotNull(alias)
        var journal = (j as? Journal.Ok)?.journal
        var disk = (j as? Journal.Ok)?.takeIf { it.exists }?.journal
        if (journal != null && journal.waiting > 0) {
            val m = ListsJournal.merge(file, journal)
            // LS10 (L6): lists.enc is written only when the merge changed it (an add merged before, already there or dropped
            // changes nothing); the journal is emptied by names() whatever the switch reads.
            if (m.changed) {
                // lists.enc first, then the journal: a crash between leaves adds that the next merge finds already there.
                val next = ListsEdits.purge(m.file, now())
                val out = sealed(from, create = false) { ListsEnvelope.seal(next, it) } ?: throw MemoryKeyException(KeyProblem.GONE)
                writeAtomicBytes(listsFile, out)
                file = next
                wrote()
            }
            RLog.i("lists_merge", "added" to m.added, "putBack" to m.putBack, "qtyKept" to m.qtyKept, "already" to m.already, "waiting" to m.waiting, "dropped" to m.dropped)
            journal = m.journal
        }
        val merged = journal
        if (merged != null) {
            val (held, written) = names(file, merged, disk, lockedNow)
            journal = held
            disk = written
        }
        val cur = Current(ListsLoad.Loaded(file), from, journal, disk)
        // After a write: a failed read here costs the RAM copy, never the answer.
        if (!lockedNow) cache = try { Cached(read(listsFile), read(journalFile), cur) } catch (_: Exception) { null }
        return cur
    }

    /** A change's answer: the file written when it changed (the tombstones over 30 days purged), the journal's names refreshed. */
    private fun commit(cur: Current, edit: ListsEdit, lockedNow: Boolean): ListsWrite {
        if (edit !is ListsEdit.Done || !edit.changed) return ListsWrite(cur.load, edit)
        val next = ListsEdits.purge(edit.file, now())
        if (!ListsCodec.fits(next)) return ListsWrite(cur.load, ListsEdit.No(ListsRefusal.FILE_FULL))
        val out = sealed(checkNotNull(cur.alias), create = false) { ListsEnvelope.seal(next, it) } ?: throw MemoryKeyException(KeyProblem.GONE)
        writeAtomicBytes(listsFile, out)
        drop()
        wrote()
        cur.journal?.let { names(next, it, cur.disk, lockedNow) }
        return ListsWrite(ListsLoad.Loaded(next), edit.copy(file = next))
    }

    /**
     * The journal holding [file]'s lists as its names (§4.6 step 1), written when they differ from [disk] (null: no journal
     * file) and it is wanted ("Add to lists while locked" on, adds still waiting, or LS10, L6: adds the merge took from the
     * journal on disk, whatever the switch reads, so a merged journal is always emptied). Its key is made only on an unlocked
     * phone. The journal as it now holds, and as it is on disk: one that can't be written now is left as it is, and the next
     * write refreshes it.
     */
    private fun names(file: ListsFile, journal: ListsJournalFile, disk: ListsJournalFile?, lockedNow: Boolean): Pair<ListsJournalFile, ListsJournalFile?> {
        val want = ListsJournal.snapshot(file, journal)
        val merged = (disk?.waiting ?: 0) > want.waiting
        if (!(settings().addLocked || want.waiting > 0 || merged) || want == disk || !ListsJournal.fits(want)) return want to disk
        return try {
            val out = sealed(ListsKeyAliases.LOCK_SCREEN, create = !lockedNow) { ListsEnvelope.sealJournal(want, it) } ?: return want to disk
            writeAtomicBytes(journalFile, out)
            want to want
        } catch (t: Exception) {
            if (t !is MemoryKeyException) RLog.w("lists_journal_names", t)
            want to disk
        }
    }

    /** The journal now. One that is damaged, or whose key is gone, is kept aside as locked.enc.bad and a new one starts. */
    private fun journalNow(bytes: ByteArray?): Journal = when (val l = ListsEnvelope.openJournal(bytes, ::journalCipher)) {
        is ListsLoad.Loaded -> Journal.Ok(l.value, true)
        ListsLoad.Empty -> Journal.Ok(ListsJournalFile(), false)
        ListsLoad.Locked -> Journal.NotNow
        is ListsLoad.TooNew -> Journal.Newer
        ListsLoad.Unreadable, ListsLoad.KeyGone -> {
            RLog.i("lists_journal_aside", "state" to RLog.Code(stateCode(l)))
            if (aside(journalFile, JOURNAL_BAD) != null) Journal.Ok(ListsJournalFile(), false) else Journal.NotNow
        }
    }

    private sealed class Journal {
        data class Ok(val journal: ListsJournalFile, val exists: Boolean) : Journal()
        /** Its key can't be used now: nothing merged or written this time. */
        data object NotNow : Journal()
        /** A newer Robot's: never written over, no adds. */
        data object Newer : Journal()
    }

    private fun journalProblem(): ListsLoad<*> = when (val l = ListsEnvelope.openJournal(read(journalFile), ::journalCipher)) {
        is ListsLoad.TooNew -> l
        else -> ListsLoad.Locked
    }

    /** [file] as a new lists.enc, under the key Settings › Lists' lock-screen switch says; its alias. */
    private fun writeFresh(file: ListsFile): String {
        val alias = if (settings().lockScreen) ListsKeyAliases.LOCK_SCREEN else ListsKeyAliases.UNLOCKED
        val out = sealed(alias, create = true) { ListsEnvelope.seal(file, it) } ?: throw MemoryKeyException(KeyProblem.LOCKED)
        writeAtomicBytes(listsFile, out)
        wrote()
        return alias
    }

    /**
     * [seal] with [alias]'s key: made when [create] and missing. A key Android invalidated is replaced once, only for a new
     * file ([create]) and only when nothing on disk is sealed under it (as Files' F5); never for "not now". Null: no key.
     */
    private fun sealed(alias: String, create: Boolean, seal: (MemoryCipher) -> ByteArray): ByteArray? {
        fun once(): ByteArray? = key(alias, create)?.let(seal)
        return try {
            once()
        } catch (e: MemoryKeyException) {
            if (e.problem != KeyProblem.GONE || !create || sealedUnder(alias)) throw e
            keys.delete(alias)
            RLog.i("lists_key_replaced", "key" to RLog.Code(if (alias == ListsKeyAliases.UNLOCKED) "lists" else "lock"))
            once()
        }
    }

    /** Whether lists.enc or the journal on disk is sealed under [alias]. */
    private fun sealedUnder(alias: String): Boolean =
        ListsEnvelope.alias(read(listsFile)) == alias || ListsEnvelope.journalAlias(read(journalFile)) == alias

    /**
     * LS10 (L5, §4.5): whether a copy kept aside (lists.enc.bad*, locked.enc.bad*) names [alias] in its header. Kept aside
     * is never deleted by Robot, so neither is the key that opens it; Delete all lists removes those copies.
     */
    private fun keptAside(alias: String): Boolean = dir.listFiles { f -> f.isFile && (f.name.startsWith(BAD) || f.name.startsWith(JOURNAL_BAD)) }.orEmpty()
        .any { f -> read(f).let { ListsEnvelope.alias(it) == alias || ListsEnvelope.journalAlias(it) == alias } }

    /** A switch's key deleted (follow), unless a copy kept aside still needs it ([keptAside]). */
    private fun deleteKey(alias: String) {
        if (keptAside(alias)) RLog.i("lists_key_kept", "key" to RLog.Code(if (alias == ListsKeyAliases.UNLOCKED) "lists" else "lock"))
        else keys.delete(alias)
    }

    /** lists.enc's key: one of Lists' two aliases, never made here; any other alias is damaged data. */
    private fun listsCipher(alias: String): MemoryCipher? {
        if (alias != ListsKeyAliases.UNLOCKED && alias != ListsKeyAliases.LOCK_SCREEN) throw MemoryKeyException(KeyProblem.BAD_DATA)
        return key(alias, create = false)
    }

    /** The journal's key: the lock screen's alone. */
    private fun journalCipher(alias: String): MemoryCipher? {
        if (alias != ListsKeyAliases.LOCK_SCREEN) throw MemoryKeyException(KeyProblem.BAD_DATA)
        return key(alias, create = false)
    }

    /** The Keystore's answer for [alias]; every failure a [MemoryKeyException] (core's frame reads only those). */
    private fun key(alias: String, create: Boolean): MemoryCipher? = try {
        keys.cipher(alias, create)
    } catch (e: MemoryKeyException) {
        throw e
    } catch (t: Throwable) {
        throw MemoryKeyException(problemOf(t), t)
    }

    /** [f] renamed to [base], or the first free of base2 to base9 (an older copy is never replaced); its new file, or null. */
    private fun aside(f: File, base: String): File? {
        for (n in 1..9) {
            val to = File(dir, if (n == 1) base else "$base$n")
            if (!to.exists()) return if (f.renameTo(to)) to else null
        }
        return null
    }

    /** Tells [changed]; a failure there never fails a write. */
    private fun wrote() {
        try { changed() } catch (t: Exception) { RLog.w("lists_changed_hook", t) }
    }

    /** Null only when [f] isn't there. One there that can't be read now throws: every caller answers "not now", so first-use lists or a new journal never replace it, and a key it needs is never taken for unused. */
    private fun read(f: File): ByteArray? = readForUpdate(f)

    /** A read that can't throw: a Keystore or disk failure is "not now". */
    private inline fun guarded(block: () -> ListsLoad<ListsFile>): ListsLoad<ListsFile> = try {
        block()
    } catch (e: MemoryKeyException) {
        problem(e.problem)
    } catch (t: Exception) {
        RLog.w("lists_open", t)
        ListsLoad.Locked
    }

    private inline fun guardedWrite(block: () -> ListsWrite): ListsWrite = try {
        block()
    } catch (e: MemoryKeyException) {
        drop()
        ListsWrite(problem(e.problem))
    } catch (t: Exception) {
        drop()
        RLog.w("lists_write", t)
        ListsWrite(ListsLoad.Locked)
    }

    companion object {
        const val LOCK_FILE = "lists.lock"
        const val BAD = "lists.enc.bad"
        const val JOURNAL_BAD = "locked.enc.bad"

        private fun problem(p: KeyProblem): ListsLoad<Nothing> = when (p) {
            KeyProblem.LOCKED -> ListsLoad.Locked
            KeyProblem.GONE -> ListsLoad.KeyGone
            KeyProblem.BAD_DATA -> ListsLoad.Unreadable
        }

        private fun stateCode(l: ListsLoad<*>): String = when (l) {
            ListsLoad.Unreadable -> "damaged"
            ListsLoad.KeyGone -> "key_gone"
            is ListsLoad.TooNew -> "newer"
            else -> "other"
        }

        /**
         * What a Keystore failure means, from the exception and its causes (Android may wrap an invalidated key's, as in a
         * getKey's UnrecoverableKeyException): invalidated is GONE, a tag that fails BAD_DATA, anything else LOCKED.
         */
        fun problemOf(t: Throwable): KeyProblem {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth++ < 8) {
                when (c) {
                    is MemoryKeyException -> return c.problem
                    is KeyPermanentlyInvalidatedException -> return KeyProblem.GONE
                    is AEADBadTagException -> return KeyProblem.BAD_DATA
                }
                c = c.cause
            }
            return KeyProblem.LOCKED
        }
    }
}

/** Robot Lists in this process: its vault, the screen-off and unlock hooks. The main app and :voice only. */
object ListsFiles {
    /** The processes that may hold lists: never :brain, :llama, :mt or :lock. */
    private val PROCESSES = setOf("main", "voice")

    @Volatile private var vault: ListsVault? = null
    @Volatile private var started = false

    /** Told after this process wrote the lists or Settings › Lists (LS6: ui/lists ListsWidgets, which redraws the widgets). */
    @Volatile var onChange: ((Context) -> Unit)? = null
    private val worker: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-lists").apply { isDaemon = true } } }

    /** files/lists: credential-encrypted storage, and lists.enc sealed again under Lists' Keystore keys. */
    fun dir(ctx: Context): File = File(ctx.filesDir, "lists")

    /** This process's name ("main", "voice"), or null for one that may not hold lists. */
    fun process(): String? = Application.getProcessName().let { if (it.contains(':')) it.substringAfter(':') else "main" }.takeIf { it in PROCESSES }

    /** This process's vault (its RAM copy is this process's). Throws in a process that may not hold lists. */
    fun vault(ctx: Context): ListsVault = vault ?: synchronized(this) {
        vault ?: run {
            val app = ctx.applicationContext
            checkNotNull(process())
            ListsVault(dir(app), KeystoreMemoryKeys(unlockOnly = setOf(ListsKeyAliases.UNLOCKED)), settings = { ListsSettingsStore.current(app) },
                someoneElse = { PhoneLockNow.someoneElse(app) }, locked = { locked(app) }, changed = { onChange?.invoke(app) }).also { vault = it }
        }
    }

    /** The phone is locked for Robot Lists: the lock screen is up, a phone lock is on, or the device is locked. */
    fun locked(ctx: Context): Boolean = Assistant.locked(ctx) ||
        try { ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked } catch (_: Exception) { true }

    /**
     * At the process's start (RobotApp, main and :voice only): the RAM copy goes at every screen-off; in :voice, after each
     * unlock and at its start once the user has unlocked since boot, the journal's adds are merged (§4.6 step 4) and, with
     * Lists on and no file yet, the first-use lists made, so an add said while locked finds Shopping and To-do.
     */
    fun start(app: Application) {
        if (process() == null || started) return
        started = true
        val voice = process() == "voice"
        try {
            ProtectedBroadcasts.register(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        Intent.ACTION_SCREEN_OFF -> vault(app).drop()
                        Intent.ACTION_USER_PRESENT -> if (voice) worker.execute { open(app) }
                    }
                }
            }, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_OFF))
        } catch (t: Throwable) { RLog.w("lists_hooks", t) }
        if (voice) worker.execute { if (userUnlocked(app)) open(app) }
    }

    /** Main changed the lists or Settings › Lists (Cmd.LISTS): this process reads them again. */
    fun changed(ctx: Context) {
        vault(ctx).drop()
        ListsSettingsStore.forget()
    }

    private fun open(app: Context) {
        try { vault(app).load() } catch (t: Throwable) { RLog.w("lists_unlock", t) }
    }

    private fun userUnlocked(ctx: Context): Boolean = try { ctx.getSystemService(UserManager::class.java).isUserUnlocked } catch (_: Throwable) { false }
}

/**
 * Settings › Lists' values (core ListsSettings) in files/robot/lists_settings.json, never in settings.json. The main app
 * writes it; both app processes read it, again whenever the file changed. A newer Robot's file is never written over.
 */
object ListsSettingsStore {
    @Volatile private var cached: Pair<Long, ListsSettingsFile>? = null

    fun file(ctx: Context): File = File(Paths.dir(ctx), ListsSettingsCodec.FILE)

    /** The lock every write of the file takes (Settings › Lists now, a restore's finish in LS7), so each writes over the file as it is. */
    fun lock(ctx: Context): File = File(Paths.dir(ctx), "lists_settings.lock") // l10n: not text (a file name)

    private fun stamp(f: File): Long = if (f.isFile) f.lastModified() * 31 + f.length() else 0L

    /** The file as read (the defaults when there is none; a damaged or newer one with both lock switches off). */
    fun read(ctx: Context): ListsSettingsFile {
        val f = file(ctx)
        val s = stamp(f)
        cached?.let { (at, read) -> if (at == s) return read }
        return ListsSettingsCodec.decode(readOrNull(f)).also { cached = s to it }
    }

    fun current(ctx: Context): ListsSettings = read(ctx).settings

    /**
     * [MAIN] [change] applied to the file as it is now, under [lock], unknown keys kept; then :voice is told (Cmd.LISTS).
     * False when nothing was written: a newer Robot's file, or a failed write.
     */
    fun update(ctx: Context, change: (ListsSettings) -> ListsSettings): Boolean {
        val app = ctx.applicationContext
        val f = file(app)
        val ok = withFileLock(lock(app)) {
            try {
                val text = ListsSettingsCodec.update(readTextForUpdate(f), change) ?: return@withFileLock false
                writeAtomic(f, text)
                true
            } catch (t: Exception) {
                RLog.w("lists_settings_save", t)
                false
            } finally {
                cached = null
            }
        }
        if (ok) {
            Cmd.send(app, Cmd.LISTS)
            ListsFiles.onChange?.invoke(app)
        }
        return ok
    }

    /**
     * [VOICE] LS7, a restore: [next] makes the text to write of the file as it is now (core RestoreSafety.listsSettingsFile),
     * under [lock]. False when nothing was written. The restore's restart has the main app read it again.
     */
    fun restore(ctx: Context, next: (String?) -> String?): Boolean {
        val app = ctx.applicationContext
        val f = file(app)
        val ok = withFileLock(lock(app)) {
            try {
                val text = next(readTextForUpdate(f)) ?: return@withFileLock false
                writeAtomic(f, text)
                true
            } catch (t: Exception) {
                RLog.w("lists_settings_restore", t)
                false
            } finally {
                cached = null
            }
        }
        if (ok) ListsFiles.onChange?.invoke(app)
        return ok
    }

    /** Read the file again next time (Cmd.LISTS). */
    fun forget() {
        cached = null
    }
}
