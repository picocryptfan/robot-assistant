// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NotesTest {
    private fun store(): Pair<NoteStore, File> {
        val dir = Files.createTempDirectory("robot-notes").toFile()
        dir.deleteOnExit()
        return NoteStore(dir) to dir
    }

    @Test fun addListGetEditDelete() {
        val (s, dir) = store()
        assertEquals(emptyList<Note>(), s.list())
        val a = s.add("buy coffee filters", null, now = 1_000)!!
        val b = s.add("milk\neggs", "Groceries", now = 2_000)!!
        assertEquals(listOf(b, a), s.list())
        assertEquals(a, s.get(a.id))
        assertEquals("buy coffee filters", a.displayTitle)
        assertEquals("Groceries", b.displayTitle)
        // Edited: it moves to the top, keeps its id and creation time.
        val edited = a.copy(text = "buy coffee filters and beans", updated = 3_000)
        assertTrue(s.save(edited))
        assertEquals(listOf(edited, b), s.list())
        assertTrue(s.delete(b.id))
        assertFalse(s.delete(b.id))
        assertEquals(listOf(edited), s.list())
        // One file per note, nothing else left behind.
        assertEquals(listOf("${a.id}.json"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test fun badIdsAndFilesAreRefused() {
        val (s, dir) = store()
        assertNull(s.get("../settings"))
        assertFalse(s.delete("../../x"))
        assertFalse(s.save(Note("a/b", "", "x", 1, 1)))
        assertNull(s.add("x", null, 1, id = "no/slashes"))
        File(dir, "junk.json").writeText("not json")
        File(dir, "other.txt").writeText("{}")
        assertEquals(emptyList<Note>(), s.list())
        dir.deleteRecursively()
    }

    @Test fun codecRoundTripsAndTitlesFallBack() {
        val n = Note("abc-1", "", "\n  first line here  \nsecond", 5, 6)
        assertEquals(n, NoteCodec.fromJson(NoteCodec.toJson(n)))
        assertEquals("first line here", n.displayTitle)
        assertEquals("Note", Note("x", "", "  ", 1, 1).displayTitle)
        assertNull(NoteCodec.fromJson("{\"id\":\"../x\",\"text\":\"t\"}"))
        assertNull(NoteCodec.fromJson("[]"))
    }

    private val notes = listOf(
        Note("1", "", "buy coffee filters", 1, 10),
        Note("2", "Coffee shops", "Café Olimpico, Pikolo", 2, 20),
        Note("3", "Groceries", "milk, eggs, filter paper", 3, 30),
        Note("4", "", "Call the dentist about Tuesday", 4, 40),
    )

    @Test fun findRanksTitleOverTextAndAllWordsFirst() {
        // "coffee" in a title counts twice; both coffee notes come before nothing else.
        assertEquals(listOf("2", "1"), NoteSearch.find("coffee", notes).map { it.id })
        // Every word found beats one word found: the filters note first, the paper one after.
        assertEquals(listOf("1", "2", "3"), NoteSearch.find("coffee filters", notes).map { it.id })
        assertEquals(listOf("3", "1"), NoteSearch.find("filter", notes).map { it.id })
        // Accents and case don't matter.
        assertEquals(listOf("2"), NoteSearch.find("CAFE", notes).map { it.id })
        assertEquals(listOf("4"), NoteSearch.find("what did I note about the dentist", notes).map { it.id })
        assertEquals(emptyList<Note>(), NoteSearch.find("bicycle", notes))
        // "My notes": the newest.
        assertEquals(listOf("4", "3", "2"), NoteSearch.find("my notes", notes).map { it.id })
        // One or two letters find nothing by themselves.
        assertEquals(emptyList<Note>(), NoteSearch.find("e", notes))
    }

    @Test fun resultsGiveTheTitleAndTheStartOfTheText() {
        val long = Note("9", "Long", "word ".repeat(200), 1, 1)
        val r = NoteSearch.results(listOf(long, notes[0]))
        assertEquals(NoteSearch.SNIPPET, (r[0]["text"] as String).length)
        assertTrue((r[0]["text"] as String).endsWith("…"))
        assertEquals(mapOf("title" to "buy coffee filters", "text" to "buy coffee filters"), r[1])
        assertEquals(NoteSearch.MAX_RESULTS, NoteSearch.find("my notes", notes + notes.map { it.copy(id = it.id + "b") }).size)
        assertEquals("abc", NoteSearch.cut("abc", 3))
        assertEquals("ab…", NoteSearch.cut("abcd", 3))
    }
}
