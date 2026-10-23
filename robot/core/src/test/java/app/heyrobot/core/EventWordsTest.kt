// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.ToolStrings
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.1's phone fixes (22 Oct, Pixel 10 Pro, typed): « Ajoute un film ce soir de 22 h a 1 h » set a 1-minute timer, and
 * "Remind me about it" saved a note. The first named no day, so the calendar's tools weren't described and the model
 * picked from the always-described ones (ToolRouter); now « ce soir » and « 22 h » are calendar words, and a timer or an
 * alarm your words didn't name, when they ask for an event or a time range, shows its card (AlarmWords.event, TurnRunner).
 * The second is a reminder with nothing to remind and no time: in a new conversation Robot asks what and when
 * (RoutineCommand.RemindWhat).
 */
class EventWordsTest {
    private val zone = ZoneId.of("America/Halifax")
    /** Thursday 22 October 2026, 20:30: the evening the film was asked for. */
    private val now = ZonedDateTime.of(2026, 10, 22, 20, 30, 0, 0, zone)

    private val film = listOf("Ajoute un film ce soir de 22 h a 1 h", "Ajoute un film ce soir de 22 h à 1 h",
        "ajoute un film ce soir de vingt-deux heures à une heure", "ajoute un film ce soir de 22h à 1h")
    private val filmEn = listOf("Add a movie tonight from 10 pm to 1 am", "add a movie tonight from 10 to 1", "Add a movie this evening from 10 p.m. to 1 a.m.")

    /** Asked for an event, with the calendar's tools described, and never a timer or an alarm by its own words. */
    @Test fun eventWordsDescribeTheCalendar() {
        val events = film + filmEn + listOf("Ajoute un film de 22 h à 1 h", "mets un film ce soir de 22 h 30 à minuit", "Ajoute un souper ce soir à 18 h",
            "ajoute une réunion cet après-midi de 14 h à 15 h", "ajoute une reunion cet apres-midi de 14 h a 15 h", "planifie un appel ce soir à 20 h",
            "ajoute un brunch en fin de semaine", "schedule a call tonight at 8 pm", "add dinner at 6 pm")
        for (t in events) {
            assertTrue(t, ToolGroup.CALENDAR in ToolRouter.route(t))
            val asked = ToolRouter.asked(t)
            assertTrue(t, ToolName.ADD_EVENT in asked)
            assertFalse(t, ToolName.SET_TIMER in asked)
            assertFalse(t, ToolName.SET_ALARM in asked)
            assertTrue(t, ToolRouter.asksEvent(t))
        }
        // « 22 h » as French and the transcriber write it is a clock time, as « 22h » always was.
        assertTrue(ToolGroup.CALENDAR in ToolRouter.route("un film de 22 h à 1 h"))
        assertTrue(ToolGroup.CALENDAR in ToolRouter.route("un film de 22 h 30 à 1 h"))
    }

    /** Reminders, timers and talk about the evening aren't asked-for events: an alarm at a time you said still runs as before. */
    @Test fun notAnEvent() {
        for (t in listOf("remind me to call Bob at 5", "remind me in 20 minutes to check the oven", "rappelle-moi dans 1 h de sortir le linge",
            "set a timer for 10 minutes", "Réveille-moi demain à 6 h 30", "c'est quoi le programme de ce soir", "what's on tonight")) {
            assertFalse(t, ToolRouter.asksEvent(t))
        }
    }

    /** The film as said and typed: tonight 22:00 to 01:00 the next day, in both languages. */
    @Test fun theFilmIsTonightFrom22To1() {
        val start = ZonedDateTime.of(2026, 10, 22, 22, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2026, 10, 23, 1, 0, 0, 0, zone)
        for (w in listOf("ce soir de 22 h a 1 h", "ce soir de 22 h à 1 h", "ce soir de vingt-deux heures à une heure", "ce soir de 22h à 1h")) {
            assertEquals(w, Read.Ok(WhenSpan(start, end, false)), WhenParser.parse(w, now, Lang.FR))
        }
        assertEquals(Read.Ok(WhenSpan(start, end, false)), WhenParser.parse("tonight from 10 pm to 1 am", now, Lang.EN))
        // In the whole sentence the range is found too (AlarmWords.event's time range, without the verb).
        for (t in film) assertTrue(t, TurnWords.of(t, now, Lang.FR).mentions.any { it.clock && it.span.end != null })
        for (t in filmEn) assertTrue(t, TurnWords.of(t, now, Lang.EN).mentions.any { it.clock && it.span.end != null })
        assertTrue(AlarmWords.event(TurnWords.of("J'ai un film ce soir de 22 h à 1 h", now, Lang.FR)))
        assertFalse(AlarmWords.event(TurnWords.of("remind me to call Bob at 5", now, Lang.EN)))
    }

    // ------------------------------------------------------------------ in a turn

    private class Model(private val calls: List<RawToolCall>) : TurnModel {
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(calls)
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
        override fun close() {}
        override fun abandon(results: List<Pair<String, Map<String, Any?>>>) {}
    }

    private class Actions : TurnActions {
        val ran = mutableListOf<Action>()
        override val locked: Boolean get() = false
        override val settings: RobotSettings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
        override suspend fun run(action: Action): Executed {
            ran += action
            return Executed(ActionStatus.DONE, ToolStrings.addedToCalendar())
        }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards[c.id] = c; if (c.status == ActionStatus.PENDING) pending.trySend(c.id) }
        override var text: String = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private var ids = 0
    private val timer1 = RawToolCall("set_timer", mapOf("minutes" to 1))

    /** [call] for [words], as the panel runs a turn (asked: ToolRouter.asked of your words). Null: it ran without a card. */
    private fun cardFor(words: String, call: RawToolCall, lang: Lang, answer: Boolean = false): Pair<Card?, List<Action>> = runBlocking {
        val view = View()
        val actions = Actions()
        val conf = Confirmations()
        val turn = async {
            TurnRunner(Model(listOf(call)), actions, view, conf, false, asked = ToolRouter.asked(words), newId = { "e${++ids}" },
                heard = TurnWords.of(words, now, lang), clock = { now }).run()
        }
        val id = withTimeout(5_000) { kotlinx.coroutines.selects.select<String?> { view.pending.onReceive { it }; turn.onAwait { null } } }
        val card = id?.let { view.cards.getValue(it) }
        if (id != null) conf.answer(id, answer)
        turn.await()
        card to actions.ran
    }

    @Test fun theFilmNeverStartsATimerWithoutACard() {
        for (t in film) {
            val (card, ran) = cardFor(t, timer1, Lang.FR)
            assertEquals(t, ToolName.SET_TIMER, card?.tool)
            assertEquals(t, emptyList<Action>(), ran)
        }
        for (t in filmEn) {
            val (card, ran) = cardFor(t, timer1, Lang.EN)
            assertEquals(t, ToolName.SET_TIMER, card?.tool)
            assertEquals(t, emptyList<Action>(), ran)
        }
        // A tap on Do it still sets it: the card asks, it doesn't refuse.
        assertEquals(listOf<Action>(Action.SetTimer(60)), cardFor(film[0], timer1, Lang.FR, answer = true).second)
    }

    @Test fun theFilmNeverSetsAnAlarmWithoutACard() {
        val (card, ran) = cardFor(film[1], RawToolCall("set_alarm", mapOf("hour" to 22)), Lang.FR)
        assertEquals(ToolName.SET_ALARM, card?.tool)
        assertEquals(emptyList<Action>(), ran)
        val (cardEn, ranEn) = cardFor(filmEn[0], RawToolCall("set_alarm", mapOf("hour" to 22)), Lang.EN)
        assertEquals(ToolName.SET_ALARM, cardEn?.tool)
        assertEquals(emptyList<Action>(), ranEn)
    }

    /** Timers and alarms you asked for, by name or as before, still run without a card. */
    @Test fun timersAndAlarmsAskedForStillRun() {
        fun runs(words: String, call: RawToolCall, lang: Lang, action: Action) {
            val (card, ran) = cardFor(words, call, lang)
            assertNull(words, card)
            assertEquals(words, listOf(action), ran)
        }
        runs("mets une minuterie de 1 h", RawToolCall("set_timer", mapOf("minutes" to 60)), Lang.FR, Action.SetTimer(3600))
        runs("minuterie de 1 h", RawToolCall("set_timer", mapOf("minutes" to 60)), Lang.FR, Action.SetTimer(3600))
        runs("set a timer for 10 minutes", RawToolCall("set_timer", mapOf("minutes" to 10)), Lang.EN, Action.SetTimer(600))
        runs("remind me in 20 minutes to check the oven", RawToolCall("set_timer", mapOf("minutes" to 20)), Lang.EN, Action.SetTimer(1200))
        runs("Réveille-moi demain à 6 h 30", RawToolCall("set_alarm", mapOf("hour" to 6, "minute" to 30)), Lang.FR, Action.SetAlarm(6, 30))
        runs("set an alarm for 7", RawToolCall("set_alarm", mapOf("hour" to 7)), Lang.EN, Action.SetAlarm(7, 0))
        runs("remind me to call Bob at 5", RawToolCall("set_alarm", mapOf("hour" to 5)), Lang.EN, Action.SetAlarm(5, 0))
    }

    // ------------------------------------------------------------------ "Remind me about it"

    private fun route(t: String) = RoutineRouter.route(t, now)

    @Test fun nothingToRemindAsksWhatAndWhen() {
        for (t in listOf("Remind me about it", "remind me about that", "Remind me", "remind me of it", "Remind me about this.", "remind me about it please",
            "set a reminder", "Rappelle-moi ça", "rappelle moi ca", "Rappelle-moi-le", "Rappelle-le-moi", "fais-moi penser à ça", "Hey Robot, remind me about it")) {
            assertEquals(t, RoutineCommand.RemindWhat, route(t))
        }
        // French by its words, English otherwise (the reply's language).
        assertEquals(Lang.FR, RoutineRouter.lang("Rappelle-moi ça", RoutineCommand.RemindWhat))
        assertEquals(Lang.FR, RoutineRouter.lang("fais-moi penser à ça", RoutineCommand.RemindWhat))
        assertEquals(Lang.EN, RoutineRouter.lang("Remind me about it", RoutineCommand.RemindWhat))
        assertEquals("What should I remind you about, and when?", app.heyrobot.core.i18n.RoutineStrings.remindWhat().en())
        // Asking reveals nothing: it goes on with the phone locked.
        assertNull(RoutineLockedPhone.refuseText(RoutineCommand.RemindWhat, locked = true))
    }

    @Test fun somethingToRemindStaysAsBefore() {
        // Something to remind, or a time: the model's, or a brief or dated reminder, as before.
        for (t in listOf("remind me to buy milk", "remind me about the dentist", "rappelle-moi d'acheter du lait", "remind me about it today",
            "remind me about it at 5", "remind me about it tomorrow", "remind me in 20 minutes", "rappelle-moi ça demain", "remind me to call the bank")) {
            assertFalse(t, route(t) == RoutineCommand.RemindWhat)
        }
        assertTrue(route("remind me tomorrow morning to bring the passport") is RoutineCommand.Remind)
        assertTrue(route("remind me to call the dentist tomorrow") is RoutineCommand.RemindOn)
    }

    // ------------------------------------------------------------------ "Cancel my 6:30 alarm"

    /** Robot can't cancel alarms or timers (Android has no way for it): it says so, and the model never sees the request. */
    @Test fun cancellingAnAlarmOrTimerIsAnsweredByCode() {
        val alarms = listOf("Cancel my 6:30 alarm", "turn off my alarm", "delete the 7 am alarm", "can you cancel my alarm for tomorrow",
            "remove all my alarms", "stop the alarm", "please disable my alarms")
        val alarmsFr = listOf("annule mon alarme de 6 h 30", "Annule mon alarme de 6 h 30", "supprime l'alarme de 7 h", "désactive mon réveil",
            "desactive mon reveil", "arrête l'alarme", "enlève toutes mes alarmes", "éteins mon cadran")
        val timers = listOf("cancel the timer", "stop my timer", "turn off the countdown")
        val timersFr = listOf("annule la minuterie", "arrête le minuteur", "efface ma minuterie de 10 minutes", "arrête le compte à rebours")
        for (t in alarms) assertEquals(t, RoutineCommand.CantCancel(timer = false, french = false), route(t))
        for (t in alarmsFr) assertEquals(t, RoutineCommand.CantCancel(timer = false, french = true), route(t))
        for (t in timers) assertEquals(t, RoutineCommand.CantCancel(timer = true, french = false), route(t))
        for (t in timersFr) assertEquals(t, RoutineCommand.CantCancel(timer = true, french = true), route(t))
        assertEquals(Lang.FR, RoutineRouter.lang("annule mon alarme de 6 h 30", route("annule mon alarme de 6 h 30")!!))
        assertEquals(Lang.EN, RoutineRouter.lang("Cancel my 6:30 alarm", route("Cancel my 6:30 alarm")!!))
        assertEquals("Robot can't cancel or turn off alarms: do it in your clock app.", app.heyrobot.core.i18n.ToolStrings.cantCancelAlarms().en())
        assertNull(RoutineLockedPhone.refuseText(RoutineCommand.CantCancel(false, false), locked = true))
    }

    /** Setting one, cancelling something else, or asking for more than a cancel stays as before. */
    @Test fun notACancel() {
        for (t in listOf("set an alarm for 7", "cancel my meeting at 7", "delete the note about the alarm code and set an alarm for 7",
            "Réveille-moi demain à 6 h 30", "mets une minuterie de 5 minutes", "stop talking", "cancel", "annule mon rendez-vous de 15 h",
            "remind me to cancel my alarm", "delete the note about my alarm code", "efface ce que tu sais sur mon alarme")) {
            assertFalse(t, route(t) is RoutineCommand.CantCancel)
        }
        // A routine named for the alarm stays a routine's to delete.
        val routines = listOf("After my alarm", "Après mon cadran", "Alarm")
        assertTrue(RoutineRouter.route("delete my alarm routine", now, routines) is RoutineCommand.Delete)
        assertTrue(RoutineRouter.route("delete the routine After my alarm", now, routines) is RoutineCommand.Delete)
        assertFalse(RoutineRouter.route("supprime ma routine du réveil", now, routines) is RoutineCommand.CantCancel)
        assertFalse(RoutineRouter.route("supprime ma routine après mon cadran", now, routines) is RoutineCommand.CantCancel)
    }
}
