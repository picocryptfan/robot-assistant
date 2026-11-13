// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.logbook

// Pure. spoken-log-3: "delete the last log entry" (« efface la dernière inscription du carnet ») deletes on your yes, and
// its card's Undo puts the entry back whole (same id, words, amount and times), as a note deleted by voice comes back
// (core NoteDelete). The panel's side is the app's PanelLogbook.

object LogbookDelete {
    /** Undo keys start so (a note's start "note-delete:", a saved note's is its id, which never holds ':'). */
    const val UNDO = "logbook-delete:" // l10n: not text (a key)

    fun isUndo(key: String?): Boolean = key?.startsWith(UNDO) == true

    /** The entry [entries] "the last log entry" means: the newest one added. */
    fun last(entries: List<LogEntry>): LogEntry? = entries.maxByOrNull { it.added }

    /** Where a deleted entry goes from and comes back to (the app's sealed LogbookVault). */
    interface Store {
        /** The entries deleted of [ids], or null when nothing could be written. */
        fun delete(ids: Set<String>): List<LogEntry>?
        /** [back] put again; [deletedAt], when it was deleted: a Delete all since then means [Back.GONE] (never back). */
        fun restore(back: List<LogEntry>, deletedAt: Long): Back
    }

    /** An Undo's end: back; not now (locked, full, can't be written: it may be tried again); gone for good. */
    enum class Back { DONE, NOT_NOW, GONE }

    /** The entries deleted in this process, by their card's Undo key, to put back whole; the newest [MAX] only. */
    class Undos {
        private val kept = LinkedHashMap<String, Pair<LogEntry, Long>>()

        /** Deletes [e] from [store]; its Undo key, or null when it couldn't be deleted (or was already gone). */
        @Synchronized fun delete(store: Store, e: LogEntry): String? {
            val at = System.currentTimeMillis() // before the delete: a Delete all after it always ends this Undo
            val gone = store.delete(setOf(e.id))?.firstOrNull { it.id == e.id } ?: return null
            val key = UNDO + e.id
            kept.remove(key)
            kept[key] = gone to at
            while (kept.size > MAX) kept.remove(kept.keys.first())
            return key
        }

        /** Puts the entry under [key] back as it was; false when there is none or it couldn't be written. */
        @Synchronized fun restore(store: Store, key: String): Boolean {
            val (e, at) = kept[key] ?: return false
            val back = store.restore(listOf(e), at)
            if (back != Back.NOT_NOW) kept.remove(key)
            return back == Back.DONE
        }

        /** An Undo under [key] can still be tried (spoken-log-4: one that failed for now keeps its button). */
        @Synchronized fun has(key: String): Boolean = key in kept

        companion object { const val MAX = 20 }
    }
}
