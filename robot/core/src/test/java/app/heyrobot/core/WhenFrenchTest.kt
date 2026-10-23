// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.1: add_event's and read_calendar's times in Québec French (core When.kt's [Lexicon.FR]), the language of the turn
 * first and the other one after, and English read exactly as before whatever the turn's language.
 */
class WhenFrenchTest {
    private val zone = ZoneId.of("America/Halifax")
    /** Tuesday 20 October 2026, 10:15 (ADT, UTC-3). */
    private val now = ZonedDateTime.of(2026, 10, 20, 10, 15, 0, 0, zone)

    private fun at(month: Int, day: Int, h: Int = 0, m: Int = 0, year: Int = 2026) = ZonedDateTime.of(year, month, day, h, m, 0, 0, zone)

    private fun fr(text: String, n: ZonedDateTime = now): WhenSpan {
        val r = WhenParser.parse(text, n, Lang.FR)
        assertTrue("« $text » → $r", r is Read.Ok)
        return (r as Read.Ok).value
    }

    private fun timed(text: String, start: ZonedDateTime, end: ZonedDateTime? = null, n: ZonedDateTime = now) {
        val s = fr(text, n)
        assertEquals("« $text » start", start, s.start)
        assertEquals("« $text » end", end, s.end)
        assertEquals("« $text » all day", false, s.allDay)
    }

    private fun day(text: String, month: Int, d: Int, year: Int = 2026, n: ZonedDateTime = now) {
        assertEquals("« $text »", WhenSpan(at(month, d, year = year), at(month, d, year = year).plusDays(1), true), fr(text, n))
    }

    private fun unreadable(text: String, detail: String = WhenParser.SAY_DATE) {
        assertEquals("« $text »", Read.Unreadable(detail), WhenParser.parse(text, now, Lang.FR))
    }

    private fun range(text: String, from: ZonedDateTime, to: ZonedDateTime) {
        assertEquals("« $text »", Read.Ok(DayRange(from, to)), WhenParser.range(text, now, Lang.FR))
    }

    // ------------------------------------------------------------------ the French corpus

    @Test fun theWordsRobotItselfSuggests() {
        // The tip (« Ajoute un dîner avec Alex vendredi à midi ») and InfoStrings.sayDate's and sayMonth's examples.
        timed("vendredi à midi", at(10, 23, 12))
        timed("demain 15 h", at(10, 21, 15))
        // 14 October has passed this year: next year's, as in English.
        timed("14 octobre à 9 h", at(10, 14, 9, year = 2027))
        day("11 octobre", 10, 11, 2027)
    }

    @Test fun withAndWithoutAccentsAndInCapitals() {
        timed("vendredi a midi", at(10, 23, 12))
        timed("VENDREDI À MIDI", at(10, 23, 12))
        timed("aujourd'hui à 17 h", at(10, 20, 17))
        timed("aujourd’hui à 17 h", at(10, 20, 17))
        timed("aujourdhui a 17h", at(10, 20, 17))
        timed("après-demain à 10 h", at(10, 22, 10))
        day("apres demain", 10, 22)
        day("demain", 10, 21)
        day("hier", 10, 19)
    }

    @Test fun partsOfTheDay() {
        timed("ce soir", at(10, 20, 20))
        timed("ce soir à 9 h", at(10, 20, 21))
        timed("cet après-midi", at(10, 20, 14))
        timed("cet après-midi à 3 h", at(10, 20, 15))
        timed("ce matin à 11 h", at(10, 20, 11))
        timed("demain matin", at(10, 21, 9))
        timed("demain soir", at(10, 21, 18))
        timed("demain midi", at(10, 21, 12))
        timed("dans la soirée", at(10, 20, 18))
        // Québec's « avant-midi » is the morning.
        timed("demain dans l'avant-midi", at(10, 21, 9))
        timed("à 8 h du soir", at(10, 20, 20))
        timed("à 2 h de l'après-midi", at(10, 20, 14))
        // 3:00 has passed today: tomorrow's.
        timed("à 3 h du matin", at(10, 21, 3))
        timed("demain à 7 h du matin", at(10, 21, 7))
    }

    @Test fun clockTimesInEveryForm() {
        // 9:30 has passed (it is 10:15): tomorrow's.
        timed("à 9 h 30", at(10, 21, 9, 30))
        timed("à 9h30", at(10, 21, 9, 30))
        timed("à 21 h", at(10, 20, 21))
        timed("21 h 45", at(10, 20, 21, 45))
        timed("à 15 h 30", at(10, 20, 15, 30))
        timed("à 0 h 30", at(10, 21, 0, 30))
        timed("07 h", at(10, 21, 7))
        // « h » and « heures » without « du matin » read as English's bare hours: 1 to 7 are afternoon ones, as "at 3".
        timed("à 3 h", at(10, 20, 15))
        timed("à 9 heures et quart", at(10, 21, 9, 15))
        timed("à 3 heures et demie", at(10, 20, 15, 30))
        timed("à 3 heures et demi", at(10, 20, 15, 30))
        timed("à 5 h moins le quart", at(10, 20, 16, 45))
        timed("à 5 h moins quart", at(10, 20, 16, 45))
        timed("midi moins le quart", at(10, 20, 11, 45))
        timed("midi et demi", at(10, 20, 12, 30))
        timed("minuit et quart", at(10, 21, 0, 15))
        timed("à 15 h pile", at(10, 20, 15))
        timed("demain vers 10 h", at(10, 21, 10))
        timed("pour 15 h", at(10, 20, 15))
    }

    @Test fun numbersInWords() {
        timed("à quinze heures trente", at(10, 20, 15, 30))
        timed("à dix-huit heures", at(10, 20, 18))
        timed("à vingt et une heures", at(10, 20, 21))
        timed("à neuf heures moins dix", at(10, 21, 8, 50))
        timed("dans dix minutes", at(10, 20, 10, 25))
        day("dans deux semaines", 11, 3)
    }

    @Test fun midnightAndRangesThatCrossIt() {
        timed("minuit", at(10, 21, 0))
        // As "tomorrow at midnight": the end of tomorrow.
        timed("demain à minuit", at(10, 22, 0))
        timed("cette nuit à 1 h", at(10, 21, 1))
        timed("ce soir de 22 h à 1 h", at(10, 20, 22), at(10, 21, 1))
        timed("ce soir de 23 h à minuit", at(10, 20, 23), at(10, 21, 0))
    }

    @Test fun ranges() {
        timed("vendredi de 9 h à 10 h 30", at(10, 23, 9), at(10, 23, 10, 30))
        timed("vendredi 9 h à 10 h 30", at(10, 23, 9), at(10, 23, 10, 30))
        timed("de 14 h à 16 h", at(10, 20, 14), at(10, 20, 16))
        timed("de midi à 13 h", at(10, 20, 12), at(10, 20, 13))
        timed("jeudi entre 2 h et 3 h", at(10, 22, 14), at(10, 22, 15))
        timed("demain de 9 h jusqu'à 11 h", at(10, 21, 9), at(10, 21, 11))
        timed("samedi à partir de 13 h jusqu'à 15 h", at(10, 24, 13), at(10, 24, 15))
    }

    @Test fun inSoManyMinutesHoursDaysOrWeeks() {
        timed("dans 10 minutes", at(10, 20, 10, 25))
        timed("dans une demi-heure", at(10, 20, 10, 45))
        timed("dans un quart d'heure", at(10, 20, 10, 30))
        timed("dans trois quarts d'heure", at(10, 20, 11))
        timed("dans une heure", at(10, 20, 11, 15))
        timed("dans 2 heures", at(10, 20, 12, 15))
        timed("dans une heure et demie", at(10, 20, 11, 45))
        timed("dans 1 h 30", at(10, 20, 11, 45))
        timed("dans 1 heure 20 minutes", at(10, 20, 11, 35))
        timed("d'ici 20 minutes", at(10, 20, 10, 35))
        day("dans 3 jours", 10, 23)
        day("dans un mois", 11, 20)
    }

    @Test fun lengths() {
        timed("demain 15 h pendant 2 heures", at(10, 21, 15), at(10, 21, 17))
        timed("demain à 9 h pour une heure", at(10, 21, 9), at(10, 21, 10))
        timed("jeudi à 14 h pendant 45 minutes", at(10, 22, 14), at(10, 22, 14, 45))
        timed("jeudi à 14 h pendant une heure et demie", at(10, 22, 14), at(10, 22, 15, 30))
        timed("jeudi à 14 h pour une demi-heure", at(10, 22, 14), at(10, 22, 14, 30))
    }

    @Test fun weekdaysAndWeeks() {
        day("vendredi", 10, 23)
        day("ce vendredi", 10, 23)
        // Today is Tuesday: « mardi » is next week's, « ce mardi » today.
        day("mardi", 10, 27)
        day("ce mardi", 10, 20)
        // « prochain » is the one in the following week, as English's "next".
        day("lundi prochain", 10, 26)
        timed("lundi prochain à 9 h", at(10, 26, 9))
        day("vendredi prochain", 10, 30)
        day("vendredi de la semaine prochaine", 10, 30)
        day("mercredi la semaine prochaine", 10, 28)
        // « en huit »: a week after the next one.
        day("lundi en huit", 11, 2)
        day("demain en huit", 10, 28)
    }

    @Test fun datesWithAMonthOrOnlyADay() {
        day("le 14 novembre", 11, 14)
        day("le 1er novembre", 11, 1)
        // 1 November is after the clocks go back: AST, UTC-4.
        timed("le premier novembre à 15 h", at(11, 1, 15))
        assertEquals("-04:00", fr("le premier novembre à 15 h").start.offset.id)
        day("le 25", 10, 25)
        timed("le 25 à 9 h", at(10, 25, 9))
        day("le 3 mars", 3, 3, 2027)
        day("le 14 octobre 2027", 10, 14, 2027)
        day("le 15 sept", 9, 15, 2027)
        timed("vendredi 23 octobre à 12 h", at(10, 23, 12))
    }

    @Test fun moreAsPeopleSayThem() {
        timed("demain 14 h-15 h 30", at(10, 21, 14), at(10, 21, 15, 30))
        timed("vendredi 2 h-3 h", at(10, 23, 14), at(10, 23, 15))
        timed("dans deux heures et demie", at(10, 20, 12, 45))
        timed("dans 5 min", at(10, 20, 10, 20))
        timed("samedi soir à 7 h", at(10, 24, 19))
        timed("demain après-midi", at(10, 21, 14))
        timed("en après-midi", at(10, 20, 14))
        timed("le vendredi 23 octobre à midi", at(10, 23, 12))
        timed("de 11 h à midi", at(10, 20, 11), at(10, 20, 12))
        timed("minuit moins cinq", at(10, 20, 23, 55))
        timed("15h00", at(10, 20, 15))
        timed("à 9 h le matin", at(10, 21, 9))
        day("lundi le 26", 10, 26)
        day("d'ici vendredi", 10, 23)
        day("mardi qui vient", 10, 27)
        day("la semaine prochaine, mardi", 10, 27)
    }

    @Test fun allDay() {
        day("demain toute la journée", 10, 21)
        day("vendredi, toute la journée", 10, 23)
    }

    @Test fun mixedWords() {
        timed("demain, 3pm", at(10, 21, 15))
        timed("Friday à midi", at(10, 23, 12))
    }

    @Test fun whatStaysUnreadable() {
        // Meal words are not times, as "lunch" isn't in English.
        unreadable("vendredi au dîner")
        unreadable("au souper")
        unreadable("avant-hier")
        unreadable("il y a 3 jours")
        unreadable("vendredi ou samedi")
        unreadable("le premier lundi de novembre")
        unreadable("dans 1,5 jour")
        unreadable("à 25 h")
        // On its own, next week isn't a time for an event (read_calendar reads it).
        unreadable("la semaine prochaine")
        // « vendredi 9 » is Friday the 9th; this Friday is the 23rd.
        unreadable("vendredi 9")
        unreadable("10/11", WhenParser.SAY_MONTH)
    }

    @Test fun daylightSavingTimeInHalifax() {
        // Clocks go back on Sunday 1 November 2026 at 02:00 ADT.
        val saturday = ZonedDateTime.of(2026, 10, 31, 10, 0, 0, 0, zone)
        assertEquals("-04:00", fr("demain 15 h", saturday).start.offset.id)
        assertEquals(15, fr("demain 15 h", saturday).start.hour)
        val night = fr("ce soir de 23 h à 3 h", saturday)
        assertEquals("-03:00", night.start.offset.id)
        assertEquals(23, night.start.hour)
        assertEquals(ZonedDateTime.of(2026, 11, 1, 3, 0, 0, 0, zone), night.end)
        assertEquals("-04:00", night.end!!.offset.id)
        // 1:30 comes twice that night: the first one, still ADT.
        val twice = fr("cette nuit à 1 h 30", ZonedDateTime.of(2026, 10, 31, 22, 0, 0, 0, zone))
        assertEquals("2026-11-01T01:30-03:00", twice.start.toOffsetDateTime().toString())
        // « dans 2 heures » is two real hours, across the change.
        val late = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, zone)
        assertEquals(ZonedDateTime.ofInstant(Instant.parse("2026-11-01T05:30:00Z"), zone), fr("dans 2 heures", late).start)
        assertEquals("-04:00", fr("dans 2 heures", late).start.offset.id)
        // Clocks go forward on Sunday 14 March 2027 at 02:00 AST: 2:30 doesn't exist that night, 3:30 ADT does.
        val march = ZonedDateTime.of(2027, 3, 13, 12, 0, 0, 0, zone)
        assertEquals("2027-03-14T03:30-03:00", fr("demain à 2 h 30 du matin", march).start.toOffsetDateTime().toString())
        assertEquals("-03:00", fr("demain 15 h", march).start.offset.id)
    }

    @Test fun readCalendarsDaysAndWeeksInFrench() {
        range("aujourd'hui", at(10, 20), at(10, 21))
        range("demain", at(10, 21), at(10, 22))
        range("vendredi", at(10, 23), at(10, 24))
        range("cette semaine", at(10, 20), at(10, 26))
        range("la semaine prochaine", at(10, 26), at(11, 2))
        range("la fin de semaine", at(10, 24), at(10, 26))
        range("ce week-end", at(10, 24), at(10, 26))
        range("la fin de semaine prochaine", at(10, 31), at(11, 2))
    }

    // ------------------------------------------------------------------ the fallback, both ways

    @Test fun eachLanguageFallsBackOnTheOther() {
        // An English turn with French words, and a French turn with English ones.
        assertEquals(Read.Ok(WhenSpan(at(10, 23, 12), null, false)), WhenParser.parse("vendredi à midi", now, Lang.EN))
        assertEquals(Read.Ok(WhenSpan(at(10, 23, 12), null, false)), WhenParser.parse("Friday at noon", now, Lang.FR))
        assertEquals(Read.Ok(DayRange(at(10, 26), at(11, 2))), WhenParser.range("la semaine prochaine", now, Lang.EN))
        assertEquals(Read.Ok(DayRange(at(10, 26), at(11, 2))), WhenParser.range("next week", now, Lang.FR))
        // Neither reads it: English's answer, whichever came first.
        assertEquals(Read.Unreadable(WhenParser.SAY_DATE), WhenParser.parse("un de ces jours", now, Lang.FR))
        assertEquals(Read.Unreadable(WhenParser.SAY_DATE), WhenParser.parse("someday", now, Lang.EN))
        assertEquals(Read.Unreadable(WhenParser.SAY_MONTH), WhenParser.parse("10/11 à 15 h", now, Lang.FR))
        // The plain parser stays English only.
        assertEquals(Read.Unreadable(WhenParser.SAY_DATE), WhenParser.parse("vendredi à midi", now))
    }

    /** Every phrase of WhenParserTest (but its one French word), and English's short forms. */
    private val english = listOf(
        "09:30", "10.11.", "10/11", "10/11/2026", "10/11 at 3pm", "10pm to 2", "10 to 11:30", "10 to 2", "11pm to 12", "11pm to midnight",
        "11 to 1pm", "12 midnight", "12 noon", "14 Oct", "15:00", "15:00-16:30", "15h", "15h30", "1 hour from now", "20:00 to 2", "2026-01-02",
        "2026-10-14", "2026-10-14 15:00-16:00", "2026-10-14 15:30", "2026-10-14 9:00-10:00", "2026-10-14T09:00-10:30", "2026-10-14T15:00",
        "2026-10-14T15:00:00-04:00", "2026-10-14T15:00:00-13:00", "2026-10-14T15:00:00+19:00", "2026-10-14T15:00-04:00", "2026-10-14T15:00+15:00",
        "2026-10-14T15:00 to 16:30", "2026-10-14T15:00 to 2026-10-14T16:30", "2026-10-14T19:00:00Z", "2026-10-14T19:00Z", "2026-10-14T21:00+02:00",
        "2026-10-20", "2026-13-01", "20 minutes from now", "22:00 to 03:00", "22h to 1", "2-4pm", "3:30 pm", "3 days ago", "3 days from now",
        "3 o'clock", "3 p.m.", "3pm", "3pm for 2 hours", "8am", "8pm-2", "9", "9pm to 1", "9 to 10:30", "all day", "all day tomorrow",
        "an hour from now", "at 12 midnight", "at 25:00", "at 3 and 4", "at three thirty", "a week from Friday", "a week from tomorrow",
        "between 2 and 3", "day after tomorrow at 10am", "dentist in 2 months", "February 30", "first Monday of November", "Friday",
        "Friday 9-10:30", "Friday 9 to 10:30", "Friday after next", "Friday after next at 3", "Friday all-day", "Friday next week",
        "Friday night at 2", "Friday the week after next", "Friday this week", "from 2 to 4pm", "half 3", "half an hour from now", "half past 3",
        "in 1.5 days", "in 1 month", "in 20 minutes", "in 2 and a half hours", "in 2 hours", "in 2 hours on Friday", "in 2 weeks", "in 3 days",
        "in 6 months", "in 90 mins", "in an hour", "in an hour and a half", "in half an hour", "in one and a half hours", "March 3",
        "March 3 2026", "midnight", "Monday morning", "my birthday", "my weekend plans", "next Friday", "next week", "next weekend", "noon",
        "Oct 14 2pm", "Oct. 14th", "October 10", "October 14", "October 14, 2027", "October 14 at 9", "on Tuesday at 3", "on Wednesday",
        "quarter to 9", "Saturday", "Sept 14", "soon", "the 14th", "the 14th of next month", "the 14th of October", "the 5th",
        "the week after next", "the weekend", "the weekend after next", "this afternoon at 3", "this coming Friday", "this evening",
        "this evening at 12", "this evening at 8", "this Monday", "this morning at 11", "this week", "this weekend", "tmrw 3pm", "today",
        "today at 5", "today at 8", "tomorrow", "tomorrow 11pm to 1am", "tomorrow 3pm", "tomorrow 3pm for 30 minutes", "tomorrow afternoon",
        "tomorrow all day", "tomorrow at 3:30 pm", "tomorrow at 3 before lunch", "tomorrow at midnight", "tomorrow at noon", "tomorrow Friday",
        "tomorrow morning", "tomorrow night at 1", "tonight", "tonight at 1", "tonight at 11", "tonight at 11 to 1", "tonight at 12",
        "tonight at 2am", "tonight at 4", "tonight at 5", "tonight at 8", "tonight at midnight", "Tues at 3", "Tuesday 3 4",
        "Tuesday at 2 for an hour and a half", "Tuesday at 3", "Tuesday at 3pm sharp", "Tuesday at 3 with Sam", "Tuesday or Wednesday",
        "two weeks from now", "two weeks from today", "week", "week of the 19th", "whenever", "yesterday", "",
        // English's short forms, some of them French words too.
        "Sept", "sept 14 at 6", "mar 3", "jun 5", "sat", "sun at 9", "tmr", "tonite", "wed 2pm", "in 2 hrs", "in 3 wks", "Dec 1 for 45 mins",
        "at six", "at six thirty", "in six days", "a couple of days from now",
    )

    @Test fun englishReadsExactlyAsBeforeWhateverTheTurnsLanguage() {
        val toronto = ZoneId.of("America/Toronto")
        val clocks = listOf(
            ZonedDateTime.of(2026, 10, 10, 9, 5, 0, 0, toronto), ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, toronto),
            now, ZonedDateTime.of(2026, 10, 31, 23, 30, 0, 0, zone), ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, zone),
        )
        var read = 0
        for (n in clocks) for (text in english) {
            val before = WhenParser.parse(text, n)
            assertEquals("“$text” in an English turn", before, WhenParser.parse(text, n, Lang.EN))
            assertEquals("“$text” in a French turn", before, WhenParser.parse(text, n, Lang.FR))
            val days = WhenParser.range(text, n)
            assertEquals("“$text” as a day, English turn", days, WhenParser.range(text, n, Lang.EN))
            assertEquals("“$text” as a day, French turn", days, WhenParser.range(text, n, Lang.FR))
            if (before is Read.Ok) read++
        }
        // Most of them read; the rest stay unreadable in both.
        assertTrue("$read", read > english.size * 3)
    }

    /**
     * The one place a French turn reads digits differently: a number with « h » from 1 to 7 reads as French is
     * spoken, as English's "at 3" (afternoon), where the English parser reads "3h" on a 24-hour clock. An English turn
     * reads them as before; from 8 to 11 and from 13 on, both agree.
     */
    @Test fun frenchHoursReadAsSpokenNotAs24Hour() {
        for (text in listOf("3h", "7h", "7h30", "9h", "12h", "Friday 7h", "8h", "11h", "13h", "22h30")) {
            assertEquals("“$text” in an English turn", WhenParser.parse(text, now), WhenParser.parse(text, now, Lang.EN))
        }
        timed("3h", at(10, 20, 15))
        assertEquals(at(10, 21, 3), (WhenParser.parse("3h", now, Lang.EN) as Read.Ok).value.start)
        timed("7h30", at(10, 20, 19, 30))
        assertEquals(at(10, 21, 7, 30), (WhenParser.parse("7h30", now, Lang.EN) as Read.Ok).value.start)
        for (text in listOf("9h", "11h", "13h", "22h30", "12h")) {
            assertEquals("“$text”", WhenParser.parse(text, now, Lang.EN), WhenParser.parse(text, now, Lang.FR))
        }
        // A leading zero is a 24-hour time in both.
        assertEquals(WhenParser.parse("07h", now, Lang.EN), WhenParser.parse("07h", now, Lang.FR))
    }

    // ------------------------------------------------------------------ the card

    @Test fun theAddEventCardInFrench() {
        val frLangs = Langs.of(Lang.FR)
        fun card(title: String, words: String, calendar: String? = "Personnel"): Action.AddEvent {
            val s = (Events.times(Action.AddEvent(title, words), now, 60, Lang.FR) as Read.Ok).value
            return Action.AddEvent(title, words, plan = EventPlan(s.start, s.end!!, s.allDay, calendar?.let { 7L }, calendar, now.year))
        }
        val lunch = card("Dîner avec Alex", "vendredi à midi")
        assertEquals("Ajouter **Dîner avec Alex** le vendredi 23 octobre, 12 h à 13 h à *Personnel*", lunch.summary().render(frLangs))
        // The model reads English.
        assertEquals("Add **Dîner avec Alex** on Fri Oct 23, 12:00–13:00 to *Personnel*", lunch.describe())
        val late = card("Party", "samedi de 23 h à 1 h", calendar = null)
        assertEquals("Ajouter **Party** le samedi 24 octobre, 23 h – dimanche 25 octobre, 1 h dans ton appli d’agenda",
            late.summary().render(frLangs))
        val allDay = card("Congé", "demain toute la journée")
        assertEquals("Ajouter **Congé** le mercredi 21 octobre (toute la journée) à *Personnel*", allDay.summary().render(frLangs))
        // A French time already gone is refused like an English one.
        assertEquals(Read.Unreadable(Events.PASSED), Events.times(Action.AddEvent("x", "ce matin à 9 h"), now, 60, Lang.FR))
    }
}
