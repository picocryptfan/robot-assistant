package app.heyrobot.ui

import app.heyrobot.core.Note
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** seal-notes-b: the note editor's saves run off the caller's thread, in order, once each, and never bring a deleted note back. */
class NoteWriterTest {
    private val exec = Executors.newSingleThreadExecutor()
    private val store = java.util.concurrent.ConcurrentHashMap<String, Note>()
    private val writes = CopyOnWriteArrayList<Note>()
    private val threads = CopyOnWriteArrayList<Thread>()
    private val results = CopyOnWriteArrayList<NoteWriter.Result>()
    private var gate: CountDownLatch? = null
    private var t = 1_000L
    @Volatile private var phoneLocked = false

    private fun writer(exists: Boolean = false, created: Long = 0L, saved: String? = null) = NoteWriter("n1", exists, created, saved,
        put = { n, mustExist -> gate?.await(5, TimeUnit.SECONDS); if (mustExist && !store.containsKey(n.id)) null else if (phoneLocked) false else { threads += Thread.currentThread(); writes += n; store[n.id] = n; true } },
        del = { store.remove(it) != null },
        exec = exec, post = { it() }, clock = { t++ })

    private fun drain() = exec.submit {}.get(5, TimeUnit.SECONDS)

    @After fun done() { exec.shutdownNow() }

    // seal-notes-c 1: a save refused as the phone locked is tried again at the unlock, not at the next keystroke.
    @Test fun theEditorSavesAgainOnResume() {
        assertTrue(androidx.lifecycle.Lifecycle.Event.ON_RESUME in NoteWriter.SAVES_ON)
        assertTrue(androidx.lifecycle.Lifecycle.Event.ON_PAUSE in NoteWriter.SAVES_ON)
        assertTrue(androidx.lifecycle.Lifecycle.Event.ON_STOP in NoteWriter.SAVES_ON)
    }

    // seal-notes-c 2: a closed editor's late first write never turns a New note page opened since into the old note.
    @Test fun aFirstWriteLandingAfterThePageClosedTakesNoPageOver() {
        assertTrue(NoteWriter.landed(first = true, closed = false).created)
        assertFalse(NoteWriter.landed(first = true, closed = true).created)
        assertFalse(NoteWriter.landed(first = false, closed = false).created)
    }

    // seal-notes-c 3: a write landing after Back reads the Notes list again (it may have read before the write).
    @Test fun aWriteLandingAfterThePageClosedRefreshesTheList() {
        assertTrue(NoteWriter.landed(first = false, closed = true).refresh)
        assertFalse(NoteWriter.landed(first = true, closed = false).refresh)
    }

    // seal-notes-c 4: a note the editor couldn't open (locked, a newer Robot's) offers no Delete of words never seen.
    @Test fun deleteOnlyForANoteWhoseWordsWereRead() {
        assertTrue(NoteWriter.offersDelete(deleted = false, loaded = true))
        assertFalse(NoteWriter.offersDelete(deleted = false, loaded = false))
        assertFalse(NoteWriter.offersDelete(deleted = true, loaded = true))
    }

    @Test fun aSaveReturnsBeforeTheWriteAndRunsOnAnotherThread() {
        val w = writer()
        gate = CountDownLatch(1)
        w.save("Title", "words") { results += it }
        assertTrue(writes.isEmpty()) // the caller didn't wait for Notes' lock or the Keystore
        gate!!.countDown()
        drain()
        assertEquals(1, writes.size)
        assertNotEquals(Thread.currentThread(), threads.single())
        val r = results.single() as NoteWriter.Result.Saved
        assertTrue(r.first)
        assertEquals(noteKey("Title", "words"), r.key)
    }

    @Test fun savesLandInOrderOnceEachAndANewNoteIsAddedOnce() {
        val w = writer()
        w.save("", "  ") { results += it } // a blank new note: nothing written
        w.save("a", "one") { results += it }
        w.save("a", "one") { results += it } // unchanged: not written again
        w.save("a", "two") { results += it }
        drain()
        assertEquals(listOf("one", "two"), writes.map { it.text })
        assertEquals(listOf(true, false), results.map { (it as NoteWriter.Result.Saved).first })
        assertEquals(writes[0].created, store["n1"]!!.created)
        assertEquals(setOf("n1"), store.keys)
    }

    @Test fun aNoteDeletedUnderneathIsReportedGoneNotWrittenBackAndRestoresOnAsk() {
        store["n1"] = Note("n1", "t", "x", 5L, 5L)
        val w = writer(exists = true, created = 5L, saved = noteKey("t", "x"))
        store.remove("n1")
        w.save("t", "y") { results += it }
        w.save("t", "z") { results += it }
        drain()
        assertTrue(writes.isEmpty())
        assertEquals(listOf<NoteWriter.Result>(NoteWriter.Result.Gone), results.toList())
        w.save("t", "z", restore = true) { results += it }
        drain()
        assertEquals(5L, store["n1"]!!.created)
        assertEquals("z", store["n1"]!!.text)
    }

    @Test fun aSaveAsThePhoneLocksIsNeitherGoneNorLostAndIsWrittenAtTheNextSave() {
        store["n1"] = Note("n1", "t", "x", 5L, 5L)
        val w = writer(exists = true, created = 5L, saved = noteKey("t", "x"))
        phoneLocked = true
        w.save("t", "last words") { results += it }
        drain()
        assertEquals(listOf<NoteWriter.Result>(NoteWriter.Result.Failed), results.toList())
        phoneLocked = false
        w.save("t", "last words") { results += it }
        drain()
        assertEquals("last words", store["n1"]!!.text)
        assertTrue(results.last() is NoteWriter.Result.Saved)
    }

    @Test fun aDeleteAfterAWaitingFirstSaveLeavesNothing() {
        val w = writer()
        gate = CountDownLatch(1)
        w.save("a", "new") { results += it }
        w.delete()
        w.save("a", "newer") { results += it }
        gate!!.countDown()
        drain()
        assertFalse(store.containsKey("n1"))
        assertEquals(1, writes.size)
    }
}
