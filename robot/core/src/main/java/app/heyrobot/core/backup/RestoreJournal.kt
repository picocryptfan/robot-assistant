// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Pure (java.io under a file lock, as the stores do). All or nothing per category: files/robot/restore/
// journal.json records each category's state (ids and states, never content) and which process must finish it.
//
//   one file under one lock (People, Memory)    the store's own atomic write is the commit: STAGED, written, DONE
//   several files, or many (Routines, Headset   staged in restore/<category>/ (a transcript as a whole folder), COMMIT,
//   and car, Notes, History, Transcripts)       then moved in ([StagedFiles.moveIn], under the store's lock), DONE
//   the main app's settings files               staged by :voice in restore/settings/, COMMIT for the main app, which
//                                               applies them through its stores, DONE
//   keys                                        never staged: each key's name is noted before it is saved to its
//                                               Keystore file; a run cut short deletes the keys it saved
//   1.4, Robot Files                            each file sealed again under this phone's robot_files_v1 into
//                                               restore/files/, the entries it adds sealed too (names and text are
//                                               content), the plan's ids plain; COMMIT, then FileStore.restoreIn (Replace's
//                                               deletes, the index, the files moved in), DONE
//
// At each process's start, [RestoreJournal.recover] finishes that process's COMMIT categories (every step can be
// repeated) and undoes its STAGED ones (the staged copies deleted, the keys saved deleted again). Staged copies are plain
// JSON in Robot's private storage, as protected as the live files they become; Memory and keys are never staged, and
// Robot Files' are never in the clear (its sealed copies' keys are destroyed before they go).

enum class JournalState { STAGED, COMMIT, DONE, ROLLED_BACK }

/** One category's line: its [state], the process that must finish or undo it, and (keys) the names saved so far. */
data class JournalEntry(val category: BackupCategory, val state: JournalState, val process: RestoreProcess, val keys: List<String> = emptyList())

/** What [RestoreJournal.recover] did with one category. */
data class Recovered(val category: BackupCategory, val state: JournalState)

/**
 * A finisher's "not now" (1.4, Robot Files: its key needs the phone unlocked): the category stays at COMMIT for the next
 * recovery, and the other categories' lines go on. Anything else a finisher throws stops the recovery, as before. FX3:
 * [room], it waits for free space or the storage (a write that failed), not for the unlock.
 */
class NotNow(val room: Boolean = false) : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

class RestoreJournal(val dir: File) {
    private val file get() = File(dir, FILE)
    private val lock get() = File(dir, LOCK)

    /** Where [c]'s copies are staged. */
    fun staging(c: BackupCategory): File = File(dir, c.id)

    fun read(): Map<BackupCategory, JournalEntry> = decode(readOrNull(file))

    fun state(c: BackupCategory): JournalState? = read()[c]?.state

    /**
     * [c] begins: STAGED, an empty staging folder (a leftover one given to [undo], then cleared), [process] the one staging
     * it. FX1 (files-1.4-review.md finding 10): Robot Files' leftover staging is sealed, so its [undo] destroys the keys
     * first; and a Files line still at COMMIT (waiting for an unlock) is never overwritten: it is finished first, or this
     * throws.
     */
    fun begin(c: BackupCategory, process: RestoreProcess, undo: (File) -> Unit = {}) = withFileLock(lock) {
        val all = read()
        check(c != BackupCategory.FILES || all[c]?.state != JournalState.COMMIT) { "files commit waiting" } // l10n: not text (a log line)
        if (staging(c).exists()) undo(staging(c))
        staging(c).deleteRecursively()
        write(all + (c to JournalEntry(c, JournalState.STAGED, process)))
    }

    /** Everything of [c] is staged: from here it will be finished, by [finisher], even after a crash. */
    fun commit(c: BackupCategory, finisher: RestoreProcess) = set(c, JournalState.COMMIT, finisher)

    /** [c] is in place: its staging folder goes. */
    fun done(c: BackupCategory) {
        set(c, JournalState.DONE, null)
        staging(c).deleteRecursively()
    }

    /** Keys: [name] ("helper/claude") is about to be saved; noted first, so a cut run can delete it again. */
    fun keySaving(name: String) = withFileLock(lock) {
        val all = read()
        val e = all[BackupCategory.KEYS] ?: JournalEntry(BackupCategory.KEYS, JournalState.STAGED, RestoreProcess.VOICE)
        check(e.state == JournalState.STAGED)
        write(all + (BackupCategory.KEYS to e.copy(keys = (e.keys + name).distinct())))
    }

    /**
     * After a crash, at [process]'s start: each of its COMMIT categories is finished by [finish] (given its staging
     * folder; it must be repeatable) and marked DONE, unless it throws [NotNow] (it stays at COMMIT for the next recovery);
     * each of its STAGED ones is undone ([undo] given its staging folder first, then the folder deleted, and for keys,
     * [deleteKeys] given the names this run saved) and marked ROLLED_BACK. Other processes' lines are left to them. Once
     * every line is DONE or ROLLED_BACK the journal goes. Under the journal's lock.
     */
    fun recover(process: RestoreProcess, finish: (BackupCategory, File) -> Unit, deleteKeys: (List<String>) -> Unit): List<Recovered> =
        recover(process, finish, deleteKeys) { _, _ -> }

    /** [recover], with [undo] given each STAGED category's staging folder before it is deleted (1.4: Files' sealed copies). */
    fun recover(process: RestoreProcess, finish: (BackupCategory, File) -> Unit, deleteKeys: (List<String>) -> Unit,
                undo: (BackupCategory, File) -> Unit): List<Recovered> =
        withFileLock(lock) {
            val out = ArrayList<Recovered>()
            var all = read()
            for (e in all.values.filter { it.process == process }) {
                val next = when (e.state) {
                    JournalState.COMMIT -> {
                        try {
                            finish(e.category, staging(e.category))
                        } catch (_: NotNow) {
                            continue
                        }
                        JournalState.DONE
                    }
                    JournalState.STAGED -> {
                        if (e.category == BackupCategory.KEYS && e.keys.isNotEmpty()) deleteKeys(e.keys)
                        // 1.4: Robot Files' staged files are sealed: their keys are destroyed before the folder goes.
                        undo(e.category, staging(e.category))
                        JournalState.ROLLED_BACK
                    }
                    else -> continue
                }
                staging(e.category).deleteRecursively()
                all = all + (e.category to e.copy(state = next, keys = emptyList()))
                write(all)
                out += Recovered(e.category, next)
            }
            if (all.values.all { it.state == JournalState.DONE || it.state == JournalState.ROLLED_BACK }) clear()
            out
        }

    /** The run is over (every category DONE or ROLLED_BACK): the journal and any staging folder go. */
    fun clear() {
        file.delete()
        BackupCategory.entries.forEach { staging(it).deleteRecursively() }
    }

    private fun set(c: BackupCategory, s: JournalState, process: RestoreProcess?) = withFileLock(lock) {
        val all = read()
        val e = all[c] ?: JournalEntry(c, s, process ?: RestoreProcess.VOICE)
        write(all + (c to e.copy(state = s, process = process ?: e.process, keys = if (s == JournalState.DONE) emptyList() else e.keys)))
    }

    private fun write(all: Map<BackupCategory, JournalEntry>) {
        dir.mkdirs()
        writeAtomic(file, encode(all))
    }

    companion object {
        const val FILE = "journal.json"
        const val LOCK = "journal.lock"
        const val VERSION = 1

        fun encode(all: Map<BackupCategory, JournalEntry>): String = JSONObject().put("v", VERSION).put("categories", JSONObject().apply {
            for (e in all.values) put(e.category.id, JSONObject().put("state", e.state.name).put("process", e.process.name).apply {
                if (e.keys.isNotEmpty()) put("keys", JSONArray(e.keys))
            })
        }).toString()

        /** A missing or unreadable journal is an empty one; an unknown category or state is left out. */
        fun decode(text: String?): Map<BackupCategory, JournalEntry> {
            val o = try { JSONObject(text ?: return emptyMap()).optJSONObject("categories") } catch (_: Exception) { null } ?: return emptyMap()
            val out = LinkedHashMap<BackupCategory, JournalEntry>()
            for (k in o.keys()) {
                val c = BackupCategory.of(k) ?: continue
                val x = o.optJSONObject(k) ?: continue
                val s = JournalState.entries.firstOrNull { it.name == x.optString("state") } ?: continue
                val p = RestoreProcess.entries.firstOrNull { it.name == x.optString("process") } ?: continue
                val keys = x.optJSONArray("keys")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { n -> BackupCategory.KEYS.validName(n) } } }.orEmpty()
                out[c] = JournalEntry(c, s, p, keys)
            }
            return out
        }
    }
}

/**
 * A staging folder's contents moved into a live folder. Replace's deletions are staged too ([stageDelete]), so they
 * happen in the same repeatable step.
 */
object StagedFiles {
    /** The names of this phone's files a Replace deletes, in the staging folder. */
    const val DELETE = ".delete.json"

    fun stageDelete(staging: File, names: Collection<String>) {
        staging.mkdirs()
        writeAtomic(File(staging, DELETE), JSONArray(names.sorted()).toString())
    }

    /**
     * Moves every file and folder of [staging] into [live] (one rename each, on the same storage), after deleting the
     * names staged for deletion. A name already moved, or already deleted, is skipped, so a repeat after a crash finishes
     * the job and a second repeat changes nothing. Names are checked: nothing leaves [live].
     */
    fun moveIn(staging: File, live: File) {
        live.mkdirs()
        readOrNull(File(staging, DELETE))?.let { t ->
            val a = try { JSONArray(t) } catch (_: Exception) { JSONArray() }
            for (i in 0 until a.length()) {
                val n = a.optString(i)
                if (safe(n)) File(live, n).deleteRecursively()
            }
        }
        for (f in staging.listFiles().orEmpty().sortedBy { it.name }) {
            if (f.name == DELETE || !safe(f.name) || f.name.startsWith(".")) continue
            val target = File(live, f.name)
            // A folder (a transcript) never replaces one that is there: Add leaves yours alone, Replace deleted it first.
            if (f.isDirectory && target.exists()) continue
            if (!f.renameTo(target)) throw java.io.IOException("rename")
        }
        File(staging, DELETE).delete()
    }

    private fun safe(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name
}
