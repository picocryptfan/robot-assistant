// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.1, dated reminders (docs/pm/handoff-dated-reminders.md point 5): dates as people say them and the transcriber writes
 * them, in English and Québec French, and [WhenParser.find], which finds the days and times named in free words.
 */
class WhenSpokenDateTest {
    private val zone = ZoneId.of("America/Halifax")
    /** Wednesday 21 October 2026, 17:45 (ADT), the evening of the report. */
    private val now = ZonedDateTime.of(2026, 10, 21, 17, 45, 0, 0, zone)

    private fun day(text: String, lang: Lang, month: Int, d: Int, year: Int = 2026) {
        val start = ZonedDateTime.of(year, month, d, 0, 0, 0, 0, zone)
        assertEquals("$lang “$text”", Read.Ok(WhenSpan(start, start.plusDays(1), true)), WhenParser.parse(text, now, lang))
    }

    private fun timed(text: String, lang: Lang, month: Int, d: Int, h: Int, m: Int = 0) {
        val r = WhenParser.parse(text, now, lang)
        assertTrue("$lang “$text” → $r", r is Read.Ok)
        assertEquals("$lang “$text”", ZonedDateTime.of(2026, month, d, h, m, 0, 0, zone), (r as Read.Ok).value.start)
        assertFalse((r as Read.Ok).value.allDay)
    }

    private fun unreadable(text: String, lang: Lang = Lang.EN) = assertTrue("“$text”", WhenParser.parse(text, now, lang) is Read.Unreadable)

    // ------------------------------------------------------------------ the brief's five forms

    @Test fun theBriefsFormsInEnglish() {
        for (lang in Lang.entries.filter { it == Lang.EN || it == Lang.FR }) {
            day("the twentieth of November", lang, 11, 20)
            day("on the twentieth of November", lang, 11, 20)
            day("November 20th", lang, 11, 20)
            day("the 20th", Lang.EN, 11, 20)
        }
    }

    @Test fun theBriefsFormsInFrench() {
        day("le 20 novembre", Lang.FR, 11, 20)
        day("le vingt novembre", Lang.FR, 11, 20)
        // English's turn reads them too (the other language after its own).
        day("le vingt novembre", Lang.EN, 11, 20)
    }

    @Test fun moreEnglishOrdinals() {
        day("November twentieth", Lang.EN, 11, 20)
        day("November the twentieth", Lang.EN, 11, 20)
        day("November the 20th", Lang.EN, 11, 20)
        day("twentieth November", Lang.EN, 11, 20)
        day("the twenty-first of November", Lang.EN, 11, 21)
        day("the twenty first of November", Lang.EN, 11, 21)
        day("Nov 22nd", Lang.EN, 11, 22)
        day("the first of December", Lang.EN, 12, 1)
        day("the thirty-first of December", Lang.EN, 12, 31)
        day("the second of January", Lang.EN, 1, 2, year = 2027)
        // This month's, or next month's once it has passed (as "the 14th").
        day("on the twenty-fifth", Lang.EN, 10, 25)
        day("the third", Lang.EN, 11, 3)
        timed("the twentieth of November at 9", Lang.EN, 11, 20, 9)
        timed("on the twentieth of November at 3:30 pm", Lang.EN, 11, 20, 15, 30)
    }

    @Test fun moreFrenchOrdinals() {
        day("le vingtième novembre", Lang.FR, 11, 20)
        day("le vingtième de novembre", Lang.FR, 11, 20)
        day("le vingt et unième novembre", Lang.FR, 11, 21)
        day("le vingt-et-unième novembre", Lang.FR, 11, 21)
        day("le premier décembre", Lang.FR, 12, 1)
        day("le trente et un décembre", Lang.FR, 12, 31)
        day("le vingt et un novembre", Lang.FR, 11, 21)
        timed("le vingt novembre à 9 h", Lang.FR, 11, 20, 9)
        timed("le 20 novembre à 15 h 30", Lang.FR, 11, 20, 15, 30)
    }

    @Test fun anOrdinalOutsideADateStaysAWord() {
        unreadable("first thing")
        unreadable("in a second")
        unreadable("the second floor")
        unreadable("second")
        unreadable("la deuxième semaine", Lang.FR)
    }

    // ------------------------------------------------------------------ find

    private fun found(text: String, lang: Lang = Lang.EN) = WhenParser.find(text, now, lang)

    @Test fun findsTheDateInMattsWords() {
        val m = found("Remind me to turn off Netflix on the twentieth of November.").single()
        assertTrue(m.dated)
        assertFalse(m.clock)
        assertEquals(LocalDate.of(2026, 11, 20), m.span.start.toLocalDate())
        val fr = found("Rappelle-moi d'appeler le dentiste le vingt novembre", Lang.FR).single()
        assertEquals(LocalDate.of(2026, 11, 20), fr.span.start.toLocalDate())
    }

    @Test fun findsAClockTime() {
        val m = found("Set an alarm for 7").single()
        assertTrue(m.clock)
        assertFalse(m.marked)
        assertTrue(m.sameTime(7, 0))
        assertTrue(m.sameTime(19, 0))
        assertFalse(m.sameTime(18, 0))
        val pm = found("I've set an alarm for 6:00 PM on November 20th.").single()
        assertTrue(pm.marked)
        assertEquals(LocalTime.of(18, 0), pm.time)
        assertEquals(LocalDate.of(2026, 11, 20), pm.span.start.toLocalDate())
        assertFalse(pm.sameTime(6, 0))
        assertTrue(found("Réveille-moi à 7 h", Lang.FR).single().sameTime(7, 0))
        assertTrue(found("wake me up at seven thirty").single().sameTime(7, 30))
        assertTrue(found("7").single().sameTime(7, 0))
    }

    @Test fun aNumberThatIsNotATime() {
        assertEquals(emptyList<Mention>(), found("Remind me about it"))
        assertEquals(emptyList<Mention>(), found("set a timer for 5 minutes"))
        assertEquals(emptyList<Mention>(), found("a table for 2 people"))
        assertEquals(emptyList<Mention>(), found("take 2 pills"))
        assertEquals(emptyList<Mention>(), found("set one alarm"))
        // "Tonight" alone is a part of the day, not a time you said.
        assertTrue(found("remind me tonight").single().let { it.dated && !it.clock })
    }

    @Test fun relativeTimes() {
        val m = found("wake me up in 20 minutes").single()
        assertTrue(m.relative)
        assertEquals(now.plusMinutes(20).withSecond(0), m.span.start)
    }

    @Test fun tomorrowSaidEarly() {
        val m = found("wake me up tomorrow at 7").single()
        assertTrue(m.dated && m.clock)
        assertEquals(LocalDate.of(2026, 10, 22), m.span.start.toLocalDate())
        assertNull(found("set an alarm").firstOrNull())
    }
}
