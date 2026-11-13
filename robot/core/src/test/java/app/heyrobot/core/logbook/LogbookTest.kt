// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.logbook

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

class LogbookTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 11, 20, 15, 0, 0, 0, zone) // a Friday
    private fun at(m: Int, d: Int, h: Int = 7) = ZonedDateTime.of(2026, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val entries = listOf(
        LogEntry("a", at(11, 2), "went for a run, 5 km", BigDecimal(5), "km"),
        LogEntry("b", at(11, 10), "ran 7.5 km", BigDecimal("7.5"), "km"),
        LogEntry("c", at(11, 18), "morning run", null, null),
        LogEntry("d", at(10, 28), "ran 4 km", BigDecimal(4), "km"),
        LogEntry("e", at(11, 19, 8), "took my pills"),
        LogEntry("f", at(10, 1), "oil changed at 84,000 km", BigDecimal(84000), "km"),
    )
    private val heard = LogbookHeard.of(true, entries)
    private val emptyHeard = LogbookHeard.of(true, emptyList())
    private fun route(t: String, h: LogbookHeard = heard) = LogbookRouter.route(t, h, now)
    private fun say(t: String, h: LogbookHeard = heard, e: List<LogEntry> = entries, l: Langs = Langs.EN) = Logbook.answer(route(t, h)!!, e, now).text.render(l)

    @Test fun logThatAddsYourWordsNow() {
        val c = route("Log that I took my pills") as LogbookCommand.Add
        assertEquals("took my pills", c.what)
        assertEquals(now.toInstant().toEpochMilli(), c.at)
        assertEquals(Lang.EN, c.lang)
    }

    @Test fun yesterdayAtEightIsYesterdayInThePast() {
        val c = route("log that I took my pills yesterday at 8") as LogbookCommand.Add
        assertEquals("took my pills", c.what)
        val t = java.time.Instant.ofEpochMilli(c.at).atZone(zone)
        assertEquals(now.toLocalDate().minusDays(1), t.toLocalDate())
        assertEquals(8, t.hour % 12)
        assertTrue(c.timed)
    }

    @Test fun aNumberAndItsUnitAreKept() {
        val c = route("log oil changed at 84,000 km") as LogbookCommand.Add
        assertEquals(BigDecimal(84000), c.number)
        assertEquals("km", c.unit)
        val fr = route("inscris que j'ai couru 5,5 km") as LogbookCommand.Add
        assertEquals(BigDecimal("5.5"), fr.number)
        assertEquals(Lang.FR, fr.lang)
    }

    @Test fun frenchInscrisQue() {
        val c = route("Inscris que j'ai pris mes pilules") as LogbookCommand.Add
        assertEquals("pris mes pilules", c.what)
        assertEquals(Lang.FR, c.lang)
    }

    @Test fun howManyRunsThisMonthIsAnExactCount() {
        assertEquals("3 times this month.", say("how many runs this month?"))
        assertEquals("4 times in all.", say("how many runs"))
        assertEquals("1 time last month.", say("how many runs last month"))
        assertEquals("1 time in October 2026.", say("how many times did I run in October"))
    }

    @Test fun aTotalAddsTheNumbersExactly() {
        assertEquals("12.5 km this month, from 2 entries.", say("how many km did I run this month"))
    }

    @Test fun aDayBefore1970IsNeverStored() {
        val c = route("log that I got married on June 5 1965") as LogbookCommand.Add
        assertTrue(c.at > 0)
    }

    @Test fun whenDidILastWithNoMatchGoesOnToMemoryAndTheModel() {
        assertNull(route("when did I last see the dentist?", emptyHeard))
        assertNull(route("when did I last see the dentist?"))
    }

    @Test fun anEmptyLogSaysHowToLog() {
        val r = Logbook.answer(route("what's in my log?", emptyHeard)!!, emptyList(), now)
        assertEquals(LogbookResult.EMPTY, r.result)
        assertTrue(r.text.en().contains("log that"))
        assertTrue(r.text.render(Langs(Lang.FR, Lang.FR, Lang.FR)).contains("inscris que"))
    }

    @Test fun whenDidILastFindsTheNewest() {
        val r = say("when did I last take my pills")
        assertTrue(r, r.startsWith("Last logged: took my pills, yesterday at"))
    }

    @Test fun questionsTheLogDoesntHoldGoOn() {
        assertNull(route("when did I last see the dentist"))
        assertNull(route("how many people live in Canada"))
        assertNull(route("how many cups in a litre", emptyHeard))
        assertNull(route("how many runs this month", emptyHeard))
    }

    @Test fun misfiresAreLeftAlone() {
        for (t in listOf("log in", "log me out", "please log me in", "the log file", "log into my account", "log out of Spotify", "open the voice log",
            "show me the last 20 lines of the sync logs", "what's a logarithm", "log on to the wifi", "log file size", "ouvre le journal vocal")) {
            assertNull(t, route(t))
        }
    }

    @Test fun offTakesNothing() {
        assertNull(route("log that I took my pills", LogbookHeard.OFF))
    }

    @Test fun recentAndDelete() {
        assertTrue(route("what's in my log") is LogbookCommand.Recent)
        assertTrue(route("delete the last log entry") is LogbookCommand.DeleteLast)
        assertTrue(say("what's in my logbook").startsWith("Newest in your logbook: took my pills, yesterday at"))
    }

    @Test fun codecRoundTripsAndRefusesDamage() {
        val back = LogbookCodec.fromJson(LogbookCodec.toJson(entries))
        assertEquals(entries, back)
        assertNull(LogbookCodec.fromJson("""{"v":1,"entries":[{"id":"a","at":0,"what":"x"}]}"""))
        assertNull(LogbookCodec.fromJson("""{"v":2,"entries":[]}"""))
        assertEquals(entries[1], LogbookCodec.oneFromJson(LogbookCodec.oneToJson(entries[1])))
    }

    @Test fun settingsFailClosed() {
        assertEquals(LogbookSettings(), LogbookSettingsCodec.decode(null))
        assertEquals(false, LogbookSettingsCodec.decode("not json").addLocked)
        assertEquals(LogbookSettings(on = false, addLocked = false), LogbookSettingsCodec.decode(LogbookSettingsCodec.encode(LogbookSettings(false, false))))
    }
}
