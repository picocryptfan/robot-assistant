// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. fix-note-delete (Pixel 8, phone-1.2.3-b: "delete the note about the gate code" only searched the notes): which
// note a spoken delete means (RoutineCommand.DeleteNote, NoteSearch's ranking), and the Undo that puts a deleted note back
// whole (same id, title, text and times). The panel's side is PanelRoutines' deleteNote.

object NoteDelete {
    sealed class Plan {
        /** No note fits. */
        data object None : Plan()
        /** One note fits best: its card names it. */
        data class One(val note: Note) : Plan()
        /** Several fit as well as each other: the card asks which (at most [NoteSearch.MAX_RESULTS]). */
        data class Several(val notes: List<Note>) : Plan()
    }

    /**
     * The note [query] means among [notes]: the notes holding every word said, or, when none does, those with the best
     * score; one of them is [Plan.One], more [Plan.Several]. "the last note" (an empty [query] with "last") is the newest.
     */
    fun plan(query: String, notes: List<Note>): Plan {
        val ranked = NoteSearch.ranked(query, notes)
        if (ranked.isEmpty()) return Plan.None
        val best = ranked[0].second
        // NoteSearch adds ALL to a note holding every word said.
        val tier = ranked.filter { if (best >= ALL) it.second >= ALL else it.second == best }.map { it.first }
        return if (tier.size == 1) Plan.One(tier[0]) else Plan.Several(tier.take(NoteSearch.MAX_RESULTS))
    }

    private const val ALL = 10

    /** Undo keys start so (a saved note's Undo key is its id, which never holds ':'). */
    const val UNDO = "note-delete:"

    fun isUndo(key: String?): Boolean = key?.startsWith(UNDO) == true

    /** The notes deleted in this process, by their card's Undo key, to put back whole; the newest [MAX] only. */
    class Undos {
        private val kept = LinkedHashMap<String, Note>()

        /** Deletes [note] from [store]; its Undo key, or null when it couldn't be deleted. */
        @Synchronized fun delete(store: NoteStore, note: Note): String? {
            if (!store.delete(note.id)) return null
            val key = UNDO + note.id
            kept.remove(key)
            kept[key] = note
            while (kept.size > MAX) kept.remove(kept.keys.first())
            return key
        }

        /** An Undo under [key] can still be tried (lists-undo-fix: one that couldn't be written keeps its button). */
        @Synchronized fun has(key: String): Boolean = key in kept

        /** Puts the note under [key] back as it was; false when there is none or it couldn't be written. */
        @Synchronized fun restore(store: NoteStore, key: String): Boolean {
            val n = kept[key] ?: return false
            if (!store.save(n)) return false
            kept.remove(key)
            return true
        }

        companion object { const val MAX = 20 }
    }
}
