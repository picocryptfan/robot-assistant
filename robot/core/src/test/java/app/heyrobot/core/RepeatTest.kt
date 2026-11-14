// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ToolStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

// fix-calendar-repeat (1.4.1 on a Pixel 10, 13 Nov 2026): "put on my calendar every second Friday …" was refused with
// "say the date like …", and nothing could write an RRULE. Neutral titles only.
class RepeatTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, zone) // a Friday
    private val FR = Langs(Lang.FR, Lang.FR, Lang.FR)

    private fun ok(text: String, lang: Lang = Lang.EN, at: ZonedDateTime = now): Repeating {
        val r = RepeatWords.plan(text, null, at, 60, lang)
        assertTrue("$text: $r", r is Read.Ok)
        return (r as Read.Ok).value
    }

    private fun plan(r: Repeating) = EventPlan(r.span.start, r.span.end!!, r.span.allDay, 7L, "Personal", 2026, repeat = r.repeat)

    @Test fun theUsersSentenceAddsAFortnightlyAllDaySeries() {
        val r = ok("Every second Friday")
        assertEquals("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR", r.repeat.rrule())
        assertTrue(r.span.allDay)
        assertEquals(LocalDate.of(2026, 11, 13), r.span.start.toLocalDate()) // today counts: an all-day one
        assertEquals("Every 2 weeks on Friday, from Fri Nov 13 (all day)", EventText.whenText(plan(r)).en())
        assertEquals("Add **timesheet** to *Personal*: Every 2 weeks on Friday, from Fri Nov 13 (all day)",
            Action.AddEvent("timesheet", "Every second Friday", plan = plan(r)).summary().en())
        assertEquals("P1D", Repeat.duration(r.span.start, r.span.end!!, true))
    }

    @Test fun theUsersSentenceInFrench() {
        val r = ok("un vendredi sur deux", Lang.FR)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR", r.repeat.rrule())
        assertEquals("Toutes les 2 semaines le vendredi, à partir du vendredi 13 novembre (toute la journée)", EventText.whenText(plan(r)).render(FR))
    }

    @Test fun eachPhraseReadsAsItsRule() {
        val en = mapOf(
            "every day" to "FREQ=DAILY", "daily" to "FREQ=DAILY",
            "weekdays" to "FREQ=WEEKLY;WKST=MO;BYDAY=MO,TU,WE,TH,FR", "every weekday" to "FREQ=WEEKLY;WKST=MO;BYDAY=MO,TU,WE,TH,FR",
            "every Friday" to "FREQ=WEEKLY;WKST=MO;BYDAY=FR", "on Fridays" to "FREQ=WEEKLY;WKST=MO;BYDAY=FR",
            "every Monday and Wednesday" to "FREQ=WEEKLY;WKST=MO;BYDAY=MO,WE",
            "every other Friday" to "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR", "every 2nd friday" to "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR",
            "every two weeks on Friday" to "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR", "every 3 weeks" to "FREQ=WEEKLY;INTERVAL=3;WKST=MO;BYDAY=FR",
            "every month on the 15th" to "FREQ=MONTHLY;BYMONTHDAY=15", "the 15th of every month" to "FREQ=MONTHLY;BYMONTHDAY=15",
            "the first Monday of every month" to "FREQ=MONTHLY;BYDAY=1MO", "every last Friday of the month" to "FREQ=MONTHLY;BYDAY=-1FR",
            "every year on March 3" to "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=3",
        )
        val fr = mapOf(
            "tous les jours" to "FREQ=DAILY", "en semaine" to "FREQ=WEEKLY;WKST=MO;BYDAY=MO,TU,WE,TH,FR",
            "chaque vendredi" to "FREQ=WEEKLY;WKST=MO;BYDAY=FR", "tous les vendredis" to "FREQ=WEEKLY;WKST=MO;BYDAY=FR",
            "un vendredi sur deux" to "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR", "toutes les deux semaines" to "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR",
            "le 15 de chaque mois" to "FREQ=MONTHLY;BYMONTHDAY=15", "le premier lundi de chaque mois" to "FREQ=MONTHLY;BYDAY=1MO",
            "chaque année le 3 mars" to "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=3",
        )
        en.forEach { (t, rule) -> assertEquals(t, rule, ok(t).repeat.rrule()) }
        fr.forEach { (t, rule) -> assertEquals(t, rule, ok(t, Lang.FR).repeat.rrule()) }
    }

    @Test fun theFirstDateIsTheNextMatchingDay() {
        assertEquals(LocalDate.of(2026, 11, 15), ok("every month on the 15th").span.start.toLocalDate())
        assertEquals(LocalDate.of(2026, 12, 7), ok("the first Monday of every month").span.start.toLocalDate())
        assertEquals(LocalDate.of(2027, 3, 3), ok("chaque année le 3 mars", Lang.FR).span.start.toLocalDate())
        // A time still to come today counts; one gone doesn't.
        val later = ok("every Friday at 3pm")
        assertEquals(ZonedDateTime.of(2026, 11, 13, 15, 0, 0, 0, zone), later.span.start)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 16, 0, 0, 0, zone), later.span.end)
        assertEquals(ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, zone), ok("every Friday at 9am").span.start)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 9, 0, 0, 0, zone), ok("tous les lundis à 9 h", Lang.FR).span.start)
        assertEquals(LocalDate.of(2026, 11, 27), ok("every other Friday starting November 27").span.start.toLocalDate())
    }

    @Test fun aTimedRepeatKeepsItsClockAcrossADstChange() {
        val before = ZonedDateTime.of(2026, 10, 23, 10, 0, 0, 0, zone) // DST ends 1 Nov 2026 in Halifax
        val r = ok("every Monday at 9am", at = before)
        // fix-calendar-repeat-c: the values Calendars writes (EventValues), expanded as the provider does: weekly from DTSTART
        // in EVENT_TIMEZONE. The region, not a fixed offset, keeps 9:00 on both sides of the change.
        val v = app.heyrobot.core.calendar.EventValues.of(plan(r))
        assertEquals("America/Halifax", v.zone)
        assertEquals("P3600S", v.duration)
        assertNull(v.dtEnd)
        assertEquals("FREQ=WEEKLY;WKST=MO;BYDAY=MO", v.rrule)
        val first = java.time.Instant.ofEpochMilli(v.dtStart).atZone(ZoneId.of(v.zone))
        val two = listOf(first, first.plusWeeks(1))
        assertEquals(listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2)), two.map { it.toLocalDate() })
        assertEquals(listOf(9, 9), two.map { it.hour })
        assertEquals(listOf(ZoneOffset.ofHours(-3), ZoneOffset.ofHours(-4)), two.map { it.offset })
    }

    @Test fun aSeriesRowReadsItsEndFromItsDuration() {
        // Calendars.row (the undo and correct-last record) reads a series' first end as DTSTART + DURATION.
        assertEquals(3_600_000L, Repeat.durationMillis("P3600S"))
        assertEquals(5_400_000L, Repeat.durationMillis("PT1H30M"))
        assertEquals(86_400_000L, Repeat.durationMillis("P1D"))
        assertNull(Repeat.durationMillis("soon"))
    }

    @Test fun aRepeatRobotCantWriteAddsNothingAndSaysWhich() {
        for (t in listOf("every last weekday of the month", "every 3 hours", "toutes les 2 heures")) {
            val r = RepeatWords.plan(t, null, now, 60, Lang.EN)
            assertTrue(t, r is Read.Unreadable)
            assertEquals(t, ToolStrings.repeatUnsupported().en(), (r as Read.Unreadable).detail)
        }
        assertTrue(ToolStrings.repeatUnsupported().en().contains("nothing was added"))
    }

    // fix-calendar-repeat-b: you pick which Friday starts a two-week repeat.
    @Test fun startingNamesTheFirstDate() {
        val nov20 = LocalDate.of(2026, 11, 20)
        for (s in listOf("every second Friday starting the 20th", "every second Friday starting next Friday", "every other Friday starting on the 20th"))
            assertEquals(s, nov20, ok(s).span.start.toLocalDate())
        assertEquals(nov20, ok("un vendredi sur deux à partir du 20", Lang.FR).span.start.toLocalDate())
        val timed = ok("every second Friday starting the 20th at 9")
        assertEquals(ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, zone), timed.span.start)
        assertEquals(LocalDate.of(2026, 12, 4), ok("every second Friday starting the 4th").span.start.toLocalDate()) // the 4th of next month
        assertEquals(LocalDate.of(2026, 11, 20), ok("every Friday starting the 18th").span.start.toLocalDate()) // the first Friday from then
        assertEquals(ZonedDateTime.of(2026, 11, 13, 21, 0, 0, 0, zone), ok("every Friday starting 9 pm").span.start) // a time, not a day
    }

    // fix-calendar-repeat-d: a start with its month keeps the month (as « à partir du » did on bb10dc619).
    @Test fun startingWithAMonthKeepsIt() {
        val jan22 = LocalDate.of(2027, 1, 22); val dec4 = LocalDate.of(2026, 12, 4)
        assertEquals(jan22, ok("chaque vendredi à partir du 20 janvier", Lang.FR).span.start.toLocalDate())
        assertEquals(dec4, ok("chaque vendredi à partir du 1er décembre", Lang.FR).span.start.toLocalDate())
        assertEquals(dec4, ok("every Friday starting 4 December").span.start.toLocalDate())
        assertEquals(jan22, ok("every Friday starting the 20th of January").span.start.toLocalDate())
    }

    // fix-calendar-repeat-b: after Robot's own line for a repeat it can't write, the model doesn't reply.
    @Test fun aRepeatRobotCantWriteEndsTheTurn() {
        val r = RepeatWords.plan("every last weekday of the month", null, now, 60, Lang.EN) as Read.Unreadable
        assertTrue(ActionOutcome.settles(listOf(Outcome(ToolName.ADD_EVENT, RepeatWords.status(r), r.detail, asked = true))))
        assertEquals(ActionStatus.INVALID, RepeatWords.status(Read.Unreadable(app.heyrobot.core.i18n.InfoStrings.timePassed())))
    }

    @Test fun aSingleEventIsNoRepeat() {
        for (t in listOf("Friday at 3", "on Friday", "tomorrow 9 to 10:30", "vendredi à 15 h")) assertNull(t, RepeatWords.plan(t, null, now, 60, Lang.EN))
    }

    @Test fun aRepeatArgThatSaysOnceAddsAOneOff() {
        // fix-calendar-repeat-c: 1.4.1 ignored these keys; null, blank or "none" from the model is a one-off, as the executor writes it.
        for (k in listOf("repeat", "recurrence")) for (v in listOf(null, "", " ", "none", "None", "once", "never", "no", "aucune", false, emptyList<Any>(), "does not repeat", "No repeat")) {
            val call = ToolCalls.parse(RawToolCall("add_event", mapOf("title" to "Dentist", "when" to "Friday 3pm", k to v)))
            val a = (call as? ParsedCall.Ok)?.action as? Action.AddEvent ?: throw AssertionError("$k=$v: $call")
            assertEquals("$k=$v", "Friday 3pm", a.whenText)
            val t = RepeatWords.eventTimes(a, now, 60, Lang.EN) as? Read.Ok ?: throw AssertionError("$k=$v unreadable")
            assertNull("$k=$v", t.value.repeat)
            assertEquals("$k=$v", 15, t.value.span.start.hour)
        }
        val a = (ToolCalls.parse(RawToolCall("add_event", mapOf("title" to "Timesheet", "when" to "Friday", "repeat" to "every second Friday"))) as ParsedCall.Ok).action as Action.AddEvent
        assertEquals("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR", (RepeatWords.eventTimes(a, now, 60, Lang.EN) as Read.Ok).value.repeat!!.rrule())
        val once = (ToolCalls.parse(RawToolCall("add_event", mapOf("title" to "Dentist", "when" to "Friday 3pm"))) as ParsedCall.Ok).action as Action.AddEvent
        assertEquals(Events.times(once, now, 60, Lang.EN), (RepeatWords.eventTimes(once, now, 60, Lang.EN) as Read.Ok).value.span.let { Read.Ok(it) })
    }

    @Test fun wordsWithoutEveryAreOneDay() {
        // fix-calendar-repeat-c: a span of weekdays or one day of the month, said without every or each, is never a series.
        for (t in listOf("du lundi au vendredi", "congres du lundi au vendredi", "Monday to Friday", "the last Friday of the month",
            "le dernier vendredi du mois", "the first Monday of the month", "on Weds", "on Wed"))
            assertTrue(t, RepeatWords.read(t) !is RepeatWords.Found.Ok)
        val weekdays = "FREQ=WEEKLY;WKST=MO;BYDAY=MO,TU,WE,TH,FR"
        for ((t, rule) in listOf("every Monday to Friday" to weekdays, "chaque lundi au vendredi" to weekdays,
            "tous les jours du lundi au vendredi" to weekdays, "du lundi au vendredi chaque semaine" to weekdays, "Monday to Friday every week" to weekdays,
            "every last Friday of the month" to "FREQ=MONTHLY;BYDAY=-1FR", "the last Friday of every month" to "FREQ=MONTHLY;BYDAY=-1FR",
            "le dernier vendredi de chaque mois" to "FREQ=MONTHLY;BYDAY=-1FR", "chaque dernier vendredi du mois" to "FREQ=MONTHLY;BYDAY=-1FR",
            "on Mondays and Wednesdays" to "FREQ=WEEKLY;WKST=MO;BYDAY=MO,WE", "every other Monday and Wednesday" to "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=MO,WE",
            "chaque 2 semaines" to "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR", "chaque deux semaines" to "FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR",
            "tous les 3 mars" to "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=3"))
            assertEquals(t, rule, ok(t, Lang.FR).repeat.rrule())
    }

    @Test fun anEndRobotCantWriteGetsItsLine() {
        // fix-calendar-repeat-c: no UNTIL or COUNT yet, so an end is refused with Robot's line: never endless, never the date hint.
        for (t in listOf("every Monday until June 30", "every Monday for 6 weeks", "chaque lundi jusqu'au 30 juin", "tous les lundis pendant 6 semaines",
            "every Friday until the 30th", "every day for 10 days", "every Monday 9am 6 times", "every Monday till December")) {
            val r = RepeatWords.plan(t, null, now, 60, Lang.EN) as? Read.Unreadable ?: throw AssertionError(t)
            assertEquals(t, ToolStrings.repeatUnsupported().en(), r.detail)
        }
        // An end time is not the series' end.
        for (t in listOf("every Monday 9am until 10am", "every Monday from 9 until 5 pm", "chaque lundi de 9 h jusqu'à 17 h", "every Monday for 2 hours"))
            assertTrue(t, RepeatWords.read(t) is RepeatWords.Found.Ok)
    }

    @Test fun aSeriesWhoseAlertFailedSaysSo() {
        val r = ok("every Friday at 9am")
        val p = plan(r).copy(alertAt = r.span.start)
        assertTrue(EventText.added("Timesheet", p, alert = false).en().contains("add one in your calendar app"))
        assertTrue(!EventText.added("Timesheet", p, alert = true).en().contains("couldn't"))
        assertTrue(!EventText.added("Timesheet", plan(r), alert = false).en().contains("couldn't"))
    }
}
