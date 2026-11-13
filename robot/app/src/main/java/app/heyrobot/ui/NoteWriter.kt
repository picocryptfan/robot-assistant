package app.heyrobot.ui

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import app.heyrobot.core.Note
import app.heyrobot.sys.NotesVault
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * seal-notes-b: the note editor's writes, off the main thread. Each save waits for Notes' lock and the Keystore, so it
 * runs on one background thread, in the order asked (a later save never lands before an earlier one), and finishes even
 * once the page has closed. The note's id is fixed before its first write, so a second save can't add it twice; Delete
 * goes the same way, so a save still waiting can't bring a deleted note back. [done] runs through [post] (the main thread).
 */
internal class NoteWriter(
    private val id: String,
    private var exists: Boolean,
    private var created: Long,
    private var lastKey: String?,
    /** Writes the note; with must-exist only while a file of it is left, else null (gone), checked under the same lock. */
    private val put: (Note, Boolean) -> Boolean?,
    private val del: (String) -> Boolean,
    private val exec: Executor = WRITES,
    private val post: (() -> Unit) -> Unit = { MAIN.post(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        /** Written: [first] when this write made the note. */
        data class Saved(val key: String, val created: Long, val updated: Long, val first: Boolean) : Result
        /** Deleted underneath (Undo in the panel): never written back silently. */
        data object Gone : Result
        /** Not written (the phone locked, or the write failed): the words stay unsaved, so the next save writes them. */
        data object Failed : Result
    }

    // Only touched on [exec]'s thread, after the constructor.
    private var isGone = false

    /** The note as read from the store: its key and creation time, so an unchanged note isn't written again. */
    fun loaded(n: Note) = exec.execute { exists = true; created = n.created; lastKey = noteKey(n.title, n.text) }

    /** Writes [title] and [text] if they changed; [restore] writes them back after a delete underneath. */
    fun save(title: String, text: String, restore: Boolean = false, done: (Result) -> Unit) = exec.execute {
        val k = noteKey(title, text)
        if (!restore && (isGone || k == lastKey)) return@execute
        if (!exists && !restore && title.isBlank() && text.isBlank()) return@execute
        // Gone means no file of it is left; one that can't be opened now (locked, a newer Robot's) isn't (seal-notes-b).
        val now = clock()
        val c = if (created > 0) created else now
        val wrote = put(Note(id, title.trim(), text.trim(), c, now), exists && !restore)
        if (wrote == null) { isGone = true; post { done(Result.Gone) }; return@execute }
        if (!wrote) { post { done(Result.Failed) }; return@execute }
        val first = !exists
        exists = true; created = c; lastKey = k; isGone = false
        post { done(Result.Saved(k, c, now, first)) }
    }

    /** Every copy of the note removed, after any save asked before it. */
    fun delete() = exec.execute { isGone = true; lastKey = null; del(id) }

    companion object {
        /**
         * When the editor saves besides typing and closing. seal-notes-c: also on resume, so words a save refused as the
         * phone locked are written as soon as it's unlocked, not at the next keystroke.
         */
        val SAVES_ON = setOf(Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP)

        /** What a write that landed tells its page: [created], a new note's first write, takes the New note page over; [refresh], the Notes list reads again (it may have read before the write). */
        data class Landed(val created: Boolean, val refresh: Boolean = false)

        /** seal-notes-c: Delete only for a note whose words were read (or a new one): never one that can't be opened now, unseen. */
        fun offersDelete(deleted: Boolean, loaded: Boolean) = !deleted && loaded

        /** seal-notes-c: only while the page is open; once closed, the New note page shown may be another note's. */
        fun landed(first: Boolean, closed: Boolean) = Landed(created = first && !closed, refresh = closed)

        private val WRITES: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "note-writes") /* l10n: not text (a thread name) */.apply { isDaemon = true } }
        private val MAIN by lazy { Handler(Looper.getMainLooper()) }

        fun of(store: NotesVault, id: String, exists: Boolean, created: Long, saved: String?) =
            NoteWriter(id, exists, created, saved, { n, mustExist -> if (mustExist) store.saveIfThere(n) else store.saveUnlocked(n) }, store::delete)
    }
}
