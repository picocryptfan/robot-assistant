// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

/** today-fix-b (review finding 4): Today and the Calendar tab read on open and on a change; the minute moves only the clock. */
class PageRefreshTest {
    private val zone = ZoneId.of("America/Halifax")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 3, h, m, 0, 0, zone)

    @Test fun theMinuteReadsNothingAChangeReadsAgainAndANewOpenIsFresh() {
        val r = PageRefresh<List<Any?>, String>()
        val calls = mutableListOf<Pair<String?, Boolean>>()
        fun get(open: Int, stamp: List<Any?>) = r.get(open, stamp) { last, fresh -> calls += last to fresh; "read${calls.size}" }
        assertEquals("read1", get(1, listOf(1L)))
        // Ten minutes on the page, nothing changed: no read at all (no Keystore, no :link).
        repeat(10) { assertEquals("read1", get(1, listOf(1L))) }
        assertEquals(1, calls.size)
        // A file changed in the same open: read again, the last read given (Today keeps Robot to Robot's count from it).
        assertEquals("read2", get(1, listOf(2L)))
        assertEquals("read1" to false, calls[1])
        // The page came back: a fresh read.
        assertEquals("read3", get(2, listOf(2L)))
        assertEquals("read2" to true, calls[2])
        assertEquals(null to true, calls[0])
    }

    @Test fun theStampFollowsAFileOrAFolderWithoutOpeningIt() {
        val dir = Files.createTempDirectory("stamp").toFile()
        try {
            val f = File(dir, "events.enc").apply { writeBytes(ByteArray(10)) }
            val missing = File(dir, "none.json")
            val a = PageStamp.files(dir, f, missing)
            assertEquals(a, PageStamp.files(dir, f, missing))
            File(dir, "note.json").writeText("{}")
            val b = PageStamp.files(dir, f, missing)
            assertNotEquals(a, b)
            f.appendBytes(ByteArray(3))
            assertNotEquals(b, PageStamp.files(dir, f, missing))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun todayReadShowsTheNextMinuteFromWhatWasRead() {
        val begin = at(10).toInstant().toEpochMilli()
        val ev = CalendarEvent(begin, begin + 3_600_000L, false, "Dentist", "12 Main St")
        val read = TodayRead(DayInput(at(9), listOf(ev)), listOf(ev), emptyList(), 0, 0, null)
        val before = read.view(at(9, 1))
        assertEquals(ev, before.day.next)
        assertNull(before.day.current)
        assertEquals(at(9, 1), before.day.now)
        val during = read.view(at(10, 30))
        assertEquals(ev, during.day.current)
        assertNull(during.day.next)
        assertTrue(read.view(at(11, 30)).rows.isEmpty())
        assertSame(ev, read.view(at(9, 59)).next)
    }
}
