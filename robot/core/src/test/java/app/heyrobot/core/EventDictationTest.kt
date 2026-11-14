// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EventDictationTest {
    // Saturday 14 Nov 2026, as on rc3.
    private val now = ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, ZoneId.of("America/Halifax"))

    private fun call(heard: String, lang: Lang = Lang.EN) = EventDictation.direct(heard, now, lang)

    private fun assertEvent(heard: String, title: String, whenText: String, lang: Lang = Lang.EN) {
        val c = call(heard, lang)
        assertEquals(heard, ToolName.ADD_EVENT.id, c?.name)
        assertEquals(heard, mapOf("title" to title, "when" to whenText), c?.args)
    }

    @Test fun thePhoneSentencesAreRepeatingEvents() {
        assertEvent("every second Friday starting the 20th timesheet", "timesheet", "every second Friday starting the 20th")
        assertEvent("every second Friday starting the 20th Robot test", "Robot test", "every second Friday starting the 20th")
        assertEvent("un vendredi sur deux à partir du 20 Robot test", "Robot test", "un vendredi sur deux à partir du 20", Lang.FR)
        assertEvent("chaque vendredi à partir du 20 janvier : Robot test", "Robot test", "chaque vendredi à partir du 20 janvier", Lang.FR)
        assertEvent("chaque vendredi à partir du 20 janvier : feuille de temps", "feuille de temps", "chaque vendredi à partir du 20 janvier", Lang.FR)
        assertEvent("every Friday starting 4 December timesheet", "timesheet", "every Friday starting 4 December")
        assertEvent("every Friday at 9 team meeting", "team meeting", "every Friday at 9")
        // review-fix-calendar-row7 -f: where it goes isn't the title; after a colon "my" is yours.
        assertEvent("Add every Friday at 9 team meeting to my calendar", "team meeting", "every Friday at 9")
        assertEvent("Ajoute chaque vendredi à partir du 20 janvier : Robot test dans mon agenda", "Robot test", "chaque vendredi à partir du 20 janvier", Lang.FR)
        assertEvent("every Friday starting 4 December: my timesheet", "my timesheet", "every Friday starting 4 December")
    }

    // fix-calendar-fr-daily-142 (rc7): a time said before « à partir du 18 novembre » gave no card (a one-off from the model).
    @Test fun aTimeBeforeAStartDateIsARepeatingCard() {
        val daily = "tous les jours à 9 h à partir du 18 novembre"
        assertEvent("Ajoute $daily : Robot test", "Robot test", daily, Lang.FR)
        assertEvent("$daily : Robot test", "Robot test", daily, Lang.FR)
        assertEvent("$daily Robot test", "Robot test", daily, Lang.FR)
        assertEvent("Ajoute chaque jour à 9 h à partir du 18 novembre : Robot test", "Robot test", "chaque jour à 9 h à partir du 18 novembre", Lang.FR)
        assertEvent("chaque jour à 9 h à partir du 18 novembre Robot test", "Robot test", "chaque jour à 9 h à partir du 18 novembre", Lang.FR)
        val weekly = "chaque vendredi à 9 h à partir du 20 novembre"
        assertEvent("Ajoute $weekly : Robot test", "Robot test", weekly, Lang.FR)
        assertEvent("every day at 9 from November 18: Robot test", "Robot test", "every day at 9 from November 18")
        assertEvent("every Friday at 9 from November 20: Robot test", "Robot test", "every Friday at 9 from November 20")
        for ((w, lang, start, freq) in listOf(
            listOf(daily, Lang.FR, "2026-11-18T09:00", Repeat.Freq.DAILY), listOf(weekly, Lang.FR, "2026-11-20T09:00", Repeat.Freq.WEEKLY),
            listOf("every day at 9 from November 18", Lang.EN, "2026-11-18T09:00", Repeat.Freq.DAILY),
            listOf("every Friday at 9 from November 20", Lang.EN, "2026-11-20T09:00", Repeat.Freq.WEEKLY),
            listOf("chaque vendredi à partir du 20 à 9 h", Lang.FR, "2026-11-20T09:00", Repeat.Freq.WEEKLY))) {
            val r = RepeatWords.plan(w as String, null, now, 60, lang as Lang) as Read.Ok
            assertEquals(w, start, r.value.span.start.toLocalDateTime().toString())
            assertEquals(w, freq, r.value.repeat.freq)
        }
    }

    @Test fun thePhoneSentencesPlanAsTheCardShowsThem() {
        val c = call("every second Friday starting the 20th timesheet")!!
        val r = RepeatWords.plan(c.args["when"] as String, null, now, 60, Lang.EN) as Read.Ok
        assertEquals("2026-11-20", r.value.span.start.toLocalDate().toString())
        assertEquals(true, r.value.span.allDay)
        assertEquals(2, r.value.repeat.interval)
    }

    @Test fun nearbyQuestionsAndTalkStayTheModels() {
        for (q in listOf("every second Friday starting the 20th, what do I have?", "every Friday I go to the gym", "what's on every Friday",
            "every Friday", "every second Friday starting the 20th", "tous les vendredis je vais au gym", "every second Friday is payday",
            "every day is a gift", "when is my timesheet due every second Friday", "chaque vendredi, qu'est-ce que j'ai", "is it every Friday",
            "every Friday starting the 20th what should I bring to the meeting with the whole team",
            // review-fix-calendar-row7 -c item 3: talk with no start or time, beside the phone sentences above.
            "every Monday sucks", "every morning my back hurts", "chaque lundi trop de devoirs", "every day counts",
            // review-fix-calendar-row7 -e Low: talk with a time, led by my / our / mon.
            "every morning at 6 my alarm goes off", "every Friday at 5 our team leaves early", "chaque matin à 6 h mon réveil sonne", "chaque vendredi à 9 h ajoute la paie dans les notes"))
            assertNull(q, call(q, if (q.contains("vendredi") || q.contains("chaque")) Lang.FR else Lang.EN))
    }

    @Test fun anAddVerbInFrontReadsTheSame() {
        // fix-calendar-row7-f: rc4 item 8, exactly as typed on the phone.
        assertEvent("Ajoute un vendredi sur deux à partir du 20 Robot test", "Robot test", "un vendredi sur deux à partir du 20", Lang.FR)
        assertEvent("Ajoute chaque vendredi à partir du 20 janvier : Robot test", "Robot test", "chaque vendredi à partir du 20 janvier", Lang.FR)
        assertEvent("add every second Friday starting the 20th Robot test", "Robot test", "every second Friday starting the 20th")
        assertEvent("Add every Friday at 9 team meeting", "team meeting", "every Friday at 9")
        assertEvent("schedule every Friday at 9 team meeting", "team meeting", "every Friday at 9")
        assertEvent("put every Friday at 9 team meeting", "team meeting", "every Friday at 9")
        assertEvent("create an event every Friday at 9 team meeting", "team meeting", "every Friday at 9")
        assertEvent("mets chaque vendredi à partir du 20 janvier : Robot test", "Robot test", "chaque vendredi à partir du 20 janvier", Lang.FR)
        assertEvent("planifie un vendredi sur deux à partir du 20 Robot test", "Robot test", "un vendredi sur deux à partir du 20", Lang.FR)
        assertEvent("crée un événement chaque vendredi à partir du 20 janvier : Robot test", "Robot test", "chaque vendredi à partir du 20 janvier", Lang.FR)
        assertEvent("ajouter chaque vendredi à partir du 20 janvier : Robot test", "Robot test", "chaque vendredi à partir du 20 janvier", Lang.FR)
    }

    @Test fun anAddVerbWithoutARepeatOrForAListOrNoteIsntAnEvent() {
        for ((q, lang) in listOf("add milk to my list" to Lang.EN, "ajoute du lait" to Lang.FR, "add milk" to Lang.EN,
            "add every Friday's timesheet to my notes" to Lang.EN, "add every Friday at 9 timesheet to my notes" to Lang.EN,
            "add every Friday at 9 milk to my shopping list" to Lang.EN, "ajoute chaque vendredi à partir du 20 du lait à ma liste" to Lang.FR,
            "add every Friday" to Lang.EN, "add every Friday at 9 my alarm goes off" to Lang.EN, "ajoute chaque vendredi, qu'est-ce que j'ai" to Lang.FR,
            "add every second Friday starting the 20th, what do I have?" to Lang.EN))
            assertNull(q, call(q, lang))
    }

    private fun start(heard: String, at: ZonedDateTime) =
        (RepeatWords.plan(EventDictation.direct(heard, at, Lang.EN)!!.args["when"] as String, null, at, 60, Lang.EN) as Read.Ok).value.span

    @Test fun theDatesPlanForA20thThatHasPassedOrIsntAFriday() {
        // review-fix-calendar-row7 -c item 5. Saturday 21 Nov: this month's 20th has passed.
        val later = now.plusDays(7)
        assertEquals("2026-12-25", start("every second Friday starting the 20th timesheet", later).start.toLocalDate().toString())
        // Friday 20 Nov isn't a Monday: the first Monday from it.
        assertEquals("2026-11-23", start("every second Monday starting the 20th timesheet", now).start.toLocalDate().toString())
        // A time, with and without am or pm.
        val nine = start("every Friday at 9 team meeting", now)
        assertEquals(false, nine.allDay)
        assertEquals("2026-11-20T09:00", nine.start.toLocalDateTime().toString())
        assertEquals("2026-11-20T21:00", start("every Friday at 9pm team meeting", now).start.toLocalDateTime().toString())
        assertEquals("2026-11-20T09:00", start("every Friday at 9am team meeting", now).start.toLocalDateTime().toString())
    }

    @Test fun onlyYourOwnWordsAlone() {
        val q = "every second Friday starting the 20th timesheet"
        assertNull(EventDictation.forTurn(q, FastPath.Way.MODEL, null, Attachment.NONE, untrusted = true, inHelperMode = false, now = now, lang = Lang.EN))
        assertNull(EventDictation.forTurn(q, FastPath.Way.MODEL, null, Attachment.NONE, untrusted = false, inHelperMode = true, now = now, lang = Lang.EN))
        assertEquals(ToolName.ADD_EVENT.id, EventDictation.forTurn(q, FastPath.Way.MODEL, null, Attachment.NONE, false, false, now, Lang.EN)?.name)
        // -c item 4: a helper's way runs its own branch. fix-calendar-row7-g: TEXT is the panel's way for every plain turn
        // (FastPath.of with no command); texting's own claim runs first in the panel's when, so TEXT reads the event too.
        assertNull(EventDictation.forTurn(q, FastPath.Way.HELPER, null, Attachment.NONE, false, false, now, Lang.EN))
        assertEquals(ToolName.ADD_EVENT.id, EventDictation.forTurn(q, FastPath.Way.TEXT, null, Attachment.NONE, false, false, now, Lang.EN)?.name)
    }

    // fix-calendar-row7-g (rc6 item 2): the panel's own way for a typed turn (PanelController.send: FastPath.of(HelperRouter.route(q), …)).
    private fun typed(q: String, lang: Lang): RawToolCall? {
        val cmd = HelperRouter.route(q)
        return EventDictation.forTurn(q, FastPath.of(cmd, false, Attachment.NONE), cmd, Attachment.NONE, false, false, now, lang)
    }

    private fun assertTyped(q: String, title: String, whenText: String, lang: Lang) {
        assertEquals(q, mapOf("title" to title, "when" to whenText), typed(q, lang)?.args)
        assertEquals(q, ToolName.ADD_EVENT.id, typed(q, lang)?.name)
    }

    @Test fun theRc6FrenchRowsTypedAreRepeatingEvents() {
        assertTyped("Ajoute un vendredi sur deux à partir du 20 Robot test", "Robot test", "un vendredi sur deux à partir du 20", Lang.FR)
        assertTyped("chaque vendredi à partir du 20 janvier : Robot test", "Robot test", "chaque vendredi à partir du 20 janvier", Lang.FR)
        assertTyped("Ajoute chaque vendredi à partir du 20 janvier : Robot test", "Robot test", "chaque vendredi à partir du 20 janvier", Lang.FR)
        assertTyped("Ajoute chaque vendredi à 9 h réunion d'équipe", "réunion d'équipe", "chaque vendredi à 9 h", Lang.FR)
        assertTyped("Ajoute chaque vendredi à 9 h réunion d'équipe dans mon agenda", "réunion d'équipe", "chaque vendredi à 9 h", Lang.FR)
    }

    @Test fun theRc6EnglishRowsTypedAreRepeatingEvents() {
        assertTyped("every second Friday starting the 20th timesheet", "timesheet", "every second Friday starting the 20th", Lang.EN)
        assertTyped("add every second Friday starting the 20th Robot test", "Robot test", "every second Friday starting the 20th", Lang.EN)
        assertTyped("Add every Friday at 9 team meeting", "team meeting", "every Friday at 9", Lang.EN)
    }

    @Test fun talkTypedStaysNoCard() {
        assertNull(typed("every Friday I go to the gym", Lang.EN))
        assertNull(typed("every Monday sucks", Lang.EN))
        assertNull(typed("chaque lundi trop de devoirs", Lang.FR))
    }

    // fix-calendar-row7-h (review-fix-calendar-row7-g, Low): another tool's verb as the title is that tool's ask, left to the model.
    @Test fun anotherToolsVerbTypedIsNoCard() {
        listOf("every weekday at 6 wake me up", "every Friday at 9 remind me to call Mom", "every morning at 7 text Sam",
            "every Friday at 5 text Sam I'm on my way", "every Friday at 9 note the meter reading", "every Monday at 8 call Mom",
            "every Friday at 6 message Sam", "every weekday at 7 set an alarm", "every Monday at 9 start a timer",
            "every night at 10 turn on do not disturb", "every Friday at 9 open the camera", "every morning at 7 play music",
            "every Friday at 9 stop the timer", "every Friday at 9 add milk").filter { typed(it, Lang.EN) != null }.let { assertEquals(emptyList<String>(), it) }
        listOf("chaque matin à 6 h réveille-moi", "chaque vendredi à 9 h rappelle-moi d'appeler maman", "chaque matin à 7 h texte Sam",
            "chaque lundi à 8 h appelle maman", "chaque vendredi à 9 h note le compteur", "chaque soir à 22 h allume ne pas déranger",
            "chaque matin à 7 h joue de la musique", "chaque lundi à 9 h lance un minuteur").filter { typed(it, Lang.FR) != null }.let { assertEquals(emptyList<String>(), it) }
    }

    // review-fix-calendar-row7-g, nit: a question said aloud with no '?' is talk.
    @Test fun aQuestionSaidWithoutAMarkIsNoCard() {
        listOf("every Friday at 9 anything on" to Lang.EN, "anything on Friday" to Lang.EN, "every Friday at 9 something going on" to Lang.EN,
            "chaque vendredi à 9 h quelque chose de prévu" to Lang.FR, "chaque vendredi à 9 h rien de prévu" to Lang.FR)
            .filter { (q, lang) -> typed(q, lang) != null }.map { it.first }.let { assertEquals(emptyList<String>(), it) }
    }

    /** guide-142: the setup guide's repeating-event examples (its People page, EN and FR) are events, as the guide says. */
    @Test fun theGuideExamplesAreEvents() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "app/src/main/res/values/strings_guide.xml").exists()) dir = dir.parentFile
        fun example(res: String, open: String, close: String): String {
            val xml = java.io.File(dir!!, "app/src/main/res/$res/strings_guide.xml").readText()
            val d = Regex("""name="guide_f_repeating_events_d">(.*?)</string>""").find(xml)!!.groupValues[1]
                .replace("&#160;", " ").replace("\\'", "'")
            return d.substringAfterLast(open).substringBefore(close).trim()
        }
        assertEvent(example("values", "\u201c", "\u201d"), "team meeting", "every Friday at 9")
        assertEvent(example("values-fr-rCA", "\u00ab", "\u00bb"), "feuille de temps", "chaque vendredi à partir du 20 janvier", Lang.FR)
    }
}
