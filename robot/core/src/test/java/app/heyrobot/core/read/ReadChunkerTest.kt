// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadChunkerTest {
    private val article = "Dr. Martin paid 1.5 dollars at 7:30 p.m. on the dot. Then he left the shop.\n\n" +
        "The **second** part starts here. It has three sentences! Does it end? Yes."

    @Test fun piecesAreWholeSentencesWithAbbreviationsDecimalsAndTimesKept() {
        val t = ReadChunker.plain(article)
        assertEquals(listOf("Dr. Martin paid 1.5 dollars at 7:30 p.m. on the dot.", "Then he left the shop.", "The second part starts here.",
            "It has three sentences!", "Does it end?", "Yes."), t.pieces.map { it.text })
        for (p in t.pieces) assertEquals(p.text, t.text.substring(p.start, p.end).replace("**", ""))
    }

    @Test fun aLongSentenceIsCutNearTheLimit() {
        val long = (1..120).joinToString(" ") { "word$it" } + "."
        val t = ReadChunker.plain(long)
        assertTrue(t.pieces.size > 1)
        assertTrue(t.pieces.all { it.text.length <= ReadChunker.MAX_PIECE + 20 })
        assertEquals(long.split(" ").size, t.pieces.sumOf { it.text.split(" ").size })
    }

    @Test fun aPdfLosesItsHeadersFootersAndPageNumbersAndJoinsBrokenWords() {
        val word = listOf("first", "second", "third")
        val pages = (1..3).map { n ->
            "Lease agreement 2026\nOn the ${word[n - 1]} page the tenant pays rent and keeps the agree-\nment in a safe place.\n" +
                "This is the ${word[n - 1]} section's end.\nPage $n of 3"
        }
        val t = ReadChunker.pdf(pages)
        val said = t.pieces.joinToString(" ") { it.text }
        assertFalse(said, said.contains("Lease agreement"))
        assertFalse(said, said.contains("Page"))
        assertTrue(said, said.contains("keeps the agreement in a safe place."))
        assertEquals(3, t.pieces.count { it.text.matches(Regex("On the \\w+ page the tenant pays rent and keeps the agreement in a safe place.")) })
        assertEquals(3, t.pieces.count { it.text.matches(Regex("This is the \\w+ section's end.")) })
    }

    @Test fun aSinglePageKeepsItsFirstLine() {
        val t = ReadChunker.pdf(listOf("A short letter\nDear Sam, thank you."))
        assertTrue(t.pieces.first().text.startsWith("A short letter"))
    }

    @Test fun emojiOnlyAndBlankTextHaveNoPieces() {
        assertTrue(ReadChunker.plain("").pieces.isEmpty())
        assertTrue(ReadChunker.plain("\n\n   \n").pieces.isEmpty())
    }

    @Test fun aResumedReadStartsAtTheSentenceWhereItStopped() {
        val places = MemoryReadPlaces()
        val note = ReadSource(ReadKind.NOTE, "42")
        val first = ReadCursor(note, ReadChunker.plain(article), places)
        assertEquals(0, first.current)
        first.spoken(0)
        first.spoken(1)
        // Cut off while the third sentence plays: it is not spoken to its end.
        first.stop()
        val again = ReadCursor(note, ReadChunker.plain(article), places)
        assertEquals(2, again.current)
        assertEquals("The second part starts here.", again.piece()!!.text)
        assertEquals(ReadPlace(note, 2, again.piece()!!.start), places.get(note))
    }

    @Test fun aNoteEditedPastItsPlaceResumesAtTheSameSentence() {
        val places = MemoryReadPlaces()
        val note = ReadSource(ReadKind.NOTE, "7")
        val c = ReadCursor(note, ReadChunker.plain(article), places)
        repeat(2) { c.spoken(it) }
        c.stop()
        // Sentences added and changed after the place: it resumes where it was.
        val edited = article.replace("It has three sentences!", "It has more sentences now! A new line.").replace("Yes.", "Yes, it does.")
        val r = ReadCursor(note, ReadChunker.plain(edited), places)
        assertEquals(2, r.current)
        assertEquals("The second part starts here.", r.piece()!!.text)
        // A place whose piece number no longer fits starts at the sentence holding its offset.
        places.put(ReadPlace(note, 9, places.get(note)!!.offset))
        assertEquals("The second part starts here.", ReadCursor(note, ReadChunker.plain(edited), places).piece()!!.text)
    }

    @Test fun readToTheEndKeepsNothing() {
        val places = MemoryReadPlaces()
        val f = ReadSource(ReadKind.FILE, "lease")
        val c = ReadCursor(f, ReadChunker.plain(article), places)
        while (!c.done) c.spoken(c.current)
        assertNull(places.get(f))
        assertNull(c.piece())
    }

    @Test fun aSharedPageKeepsNoPlace() {
        val places = MemoryReadPlaces()
        val s = ReadSource(ReadKind.SHARED, "a1")
        val c = ReadCursor(s, ReadChunker.plain(article), places)
        c.spoken(0)
        assertNull(places.get(s))
        c.stop()
        assertNull(places.get(s))
        assertEquals(0, ReadCursor(s, ReadChunker.plain(article), places).current)
    }

    @Test fun goBackABitGoesBackAtLeastTwoSentencesAndAboutTwentySeconds() {
        val text = ReadChunker.plain((1..30).joinToString(" ") { "Sentence number $it is here." })
        val c = ReadCursor(ReadSource(ReadKind.NOTE, "1"), text, MemoryReadPlaces())
        repeat(20) { c.spoken(it) }
        c.back()
        assertTrue(c.current <= 18)
        assertTrue(text.pieces[20].start - text.pieces[c.current].start >= ReadCursor.BACK_CHARS)
        val start = ReadCursor(ReadSource(ReadKind.NOTE, "2"), text, MemoryReadPlaces())
        start.spoken(0)
        start.back()
        assertEquals(0, start.current)
    }

    @Test fun theLockFailsClosed() {
        for (k in ReadKind.values()) {
            assertTrue(ReadGate.mayRead(k, locked = false))
            assertFalse(ReadGate.mayRead(k, locked = true))
            assertFalse(ReadGate.mayRead(k, locked = null))
            assertFalse(ReadGate.mayRead(k, locked = null, sharedWhileLocked = true))
        }
        assertTrue(ReadGate.mayRead(ReadKind.SHARED, locked = true, sharedWhileLocked = true))
        assertFalse(ReadGate.mayRead(ReadKind.NOTE, locked = true, sharedWhileLocked = true))
        assertTrue(ReadGate.utterance(ReadPiece("Hello.", 0, 6)).personal)
    }
}
