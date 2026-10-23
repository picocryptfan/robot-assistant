// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Pure. 1.4 FX2 (files-1.4-review.md K1): every delete of a file you tapped waits [UNDO_MS] for its Undo, out of every list
// meanwhile, wherever you tapped it: the Files tab, Settings › Files' clean-up suggestions, the panel's delete card. One
// holder per process (app sys/FileDeletes): the main app's serves the Files tab and Settings, :voice's the panel, so a file
// waiting in one isn't listed by the other screen of that process, and both go through at the same moments. Nothing new
// crosses a process: each deletes through its own vault, as before.
//
// A delete goes through when its Undo runs out, or at once when [commitAll] is called: you leave the screen, Robot goes to
// the background, the screen goes off or the phone locks, the panel closes. Until then only the list hides it; its key is
// destroyed when it goes through (core FileStore.delete). A spoken yes never starts one: the callers start it from a tap.

/**
 * The deletes waiting for their Undo in this process. [runner] runs a delete (its own thread by default; tests run it at once),
 * [later] schedules the end of an Undo window (tests drive their own clock).
 */
class PendingDeletes(
    private val runner: (() -> Unit) -> Unit = { f -> work.execute(Runnable { f() }) },
    private val later: (Long, () -> Unit) -> (() -> Unit) = { ms, f ->
        val t: ScheduledFuture<*> = work.schedule(Runnable { f() }, ms, TimeUnit.MILLISECONDS)
        val cancel: () -> Unit = { t.cancel(false) }
        cancel
    },
) {
    private class Waiting(val ids: List<FileId>, val delete: (List<FileId>) -> Boolean, val done: (Boolean) -> Unit, var cancel: () -> Unit = {})

    private val waiting = LinkedHashMap<String, Waiting>()
    /** Deleted in this process: never listed again (their ids are random, never reused). At most [MAX_GONE] are kept. */
    private val gone = LinkedHashSet<FileId>()
    private val listeners = ArrayList<(Set<FileId>) -> Unit>()

    /** The files out of every list now: waiting for their Undo, or deleted here. */
    fun hidden(): Set<FileId> = synchronized(this) { waiting.values.flatMapTo(LinkedHashSet()) { it.ids } + gone }

    /** [f] hears [hidden] each time it changes (the screens' lists); the returned function stops it. */
    fun watch(f: (Set<FileId>) -> Unit): () -> Unit {
        synchronized(this) { listeners += f }
        return { synchronized(this) { listeners -= f } }
    }

    /**
     * [ids] out of every list at once and deleted by [delete] (on [runner]'s thread) after [ms], unless [undo] comes first;
     * [done] hears whether it went through. The token [undo] takes.
     */
    fun start(ids: List<FileId>, ms: Long = UNDO_MS, delete: (List<FileId>) -> Boolean, done: (Boolean) -> Unit = {}): String {
        val token = FileId.new(random).hex
        val w = Waiting(ids, delete, done)
        synchronized(this) { waiting[token] = w }
        w.cancel = later(ms) { commit(token) }
        changed()
        return token
    }

    /** Undo: true when [token]'s delete was still waiting (nothing was deleted, the files are listed again). */
    fun undo(token: String): Boolean {
        val w = synchronized(this) { waiting.remove(token) } ?: return false
        w.cancel()
        changed()
        return true
    }

    /** Whether [token]'s delete still waits for its Undo. */
    fun waiting(token: String): Boolean = synchronized(this) { token in waiting }

    /** Every delete waiting goes through now (you left, Robot went to the background, the phone locked, the panel closed). */
    fun commitAll() {
        val tokens = synchronized(this) { waiting.keys.toList() }
        for (t in tokens) commit(t)
    }

    private fun commit(token: String) {
        val w = synchronized(this) { waiting.remove(token) } ?: return
        w.cancel()
        // Out of the lists until it is known whether it went: as gone, so it doesn't come back for a moment before the list is read again.
        synchronized(this) { gone += w.ids }
        runner {
            val ok = try { w.delete(w.ids) } catch (_: Exception) { false }
            synchronized(this) {
                // It didn't go (the phone locked meanwhile, say): listed again.
                if (!ok) gone -= w.ids.toSet()
                while (gone.size > MAX_GONE) gone.remove(gone.first())
            }
            changed()
            try { w.done(ok) } catch (_: Exception) {}
        }
    }

    private fun changed() {
        val h = hidden()
        val ls = synchronized(this) { listeners.toList() }
        for (l in ls) try { l(h) } catch (_: Exception) {}
    }

    companion object {
        /** How long a delete waits for its Undo (Q2: 10 seconds). */
        const val UNDO_MS = 10_000L
        /** Deleted ids kept out of the lists, at most. */
        const val MAX_GONE = 1_000

        private val random = java.security.SecureRandom()
        private val work: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "robot-files-deletes").apply { isDaemon = true } } // l10n: not text (a thread's name)
        }
    }
}
