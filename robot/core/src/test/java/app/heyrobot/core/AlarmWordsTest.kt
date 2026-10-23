// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.1 (docs/pm/handoff-dated-reminders.md points 2 and 3): set_alarm runs only for a clock time your own words gave this
 * turn, and never for a day it won't ring on; that is offered as a dated reminder instead (core AlarmWords, TurnRunner).
 */
class AlarmWordsTest {
    private val zone = ZoneId.of("America/Halifax")
    /** Wednesday 21 October 2026, 17:45: Matt's report came 15 minutes before an 18:00 alarm. */
    private val now = ZonedDateTime.of(2026, 10, 21, 17, 45, 0, 0, zone)

    private fun heard(words: String, at: ZonedDateTime = now, lang: Lang = Lang.EN) = TurnWords.of(words, at, lang)

    private fun check(words: String, h: Int, m: Int = 0, at: ZonedDateTime = now, lang: Lang = Lang.EN) =
        AlarmWords.check(Action.SetAlarm(h, m), heard(words, at, lang))

    private val matt = "Remind me to turn off Netflix on the twentieth of November."

    @Test fun aTimeYouSaidRuns() {
        assertEquals(AlarmFit.Ok, check("Set an alarm for 7", 7))
        // "7" is either half of the day: the card says which.
        assertEquals(AlarmFit.Ok, check("Set an alarm for 7", 19))
        assertEquals(AlarmFit.Ok, check("wake me up at 6:30 am", 6, 30))
        assertEquals(AlarmFit.Ok, check("set an alarm for seven thirty", 7, 30))
        assertEquals(AlarmFit.Ok, check("Réveille-moi à 7 h", 7, lang = Lang.FR))
        assertEquals(AlarmFit.Ok, check("mets une alarme à sept heures", 7, lang = Lang.FR))
        assertEquals(AlarmFit.Ok, check("wake me up in 20 minutes", 18, 5))
        // The answer to "What time?".
        assertEquals(AlarmFit.Ok, check("7", 7))
        assertEquals(AlarmFit.Ok, check("7 am", 7))
    }

    @Test fun noTimeInYourWords() {
        assertEquals(AlarmFit.NoTime, check("Remind me about it", 18))
        assertEquals(AlarmFit.NoTime, check("set an alarm", 7))
        assertEquals(AlarmFit.NoTime, check("set an alarm for tomorrow", 7, at = now.withHour(22)))
        assertEquals(AlarmFit.NoTime, check("set a timer for 5 minutes and an alarm", 7))
        // The answer "AM" to a model's "AM or PM?" holds no time by itself: Robot asks again (the strict reading).
        assertEquals(AlarmFit.NoTime, check("AM", 7))
    }

    @Test fun notTheTimeYouSaid() {
        assertTrue(check("Set an alarm for 7", 18) is AlarmFit.OtherTime)
        assertTrue(check("Set an alarm for 7 pm", 7) is AlarmFit.OtherTime)
        assertTrue(check("wake me up in 20 minutes", 18, 30) is AlarmFit.OtherTime)
    }

    @Test fun aDayItWontRingOn() {
        // Matt's words: the 20th of November, no time: the dated reminder all day (its alert at 9:00).
        assertEquals(AlarmFit.OtherDay(LocalDate.of(2026, 11, 20), null), check(matt, 18))
        assertEquals(AlarmFit.OtherDay(LocalDate.of(2026, 11, 20), LocalTime.of(7, 0)), check("set an alarm for 7 on the 20th", 7))
        // "Next Friday" is the one in the following week (WhenParser).
        assertEquals(AlarmFit.OtherDay(LocalDate.of(2026, 10, 30), LocalTime.of(7, 0)), check("set an alarm for 7 am next Friday", 7))
        // "Tomorrow at 7" said at 6 a.m.: the alarm would ring today.
        val six = now.withHour(6).withMinute(0)
        assertEquals(AlarmFit.OtherDay(LocalDate.of(2026, 10, 22), LocalTime.of(7, 0)), check("wake me up tomorrow at 7", 7, at = six))
        // Said at 22:00, tomorrow is the day it rings.
        assertEquals(AlarmFit.Ok, check("wake me up tomorrow at 7", 7, at = now.withHour(22)))
        assertEquals(AlarmFit.Ok, check("set an alarm for 7 pm today", 19))
    }

    @Test fun theReminderOffered() {
        assertEquals(Action.AddEvent("Reminder", "2026-11-20", alert = true), AlarmWords.reminder(AlarmFit.OtherDay(LocalDate.of(2026, 11, 20), null), "Reminder"))
        val at7 = AlarmWords.reminder(AlarmFit.OtherDay(LocalDate.of(2026, 11, 20), LocalTime.of(7, 0)), "Rappel")
        assertEquals("2026-11-20T07:00", at7.whenText)
        // WhenParser reads its words exactly.
        assertEquals(ZonedDateTime.of(2026, 11, 20, 7, 0, 0, 0, zone), (WhenParser.parse(at7.whenText, now) as Read.Ok).value.start)
    }

    // ------------------------------------------------------------------ in a turn

    private class Model(private val calls: List<RawToolCall>, private val view: View, private val reply: String = "") : TurnModel {
        val answers = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        var abandoned: List<Pair<String, Map<String, Any?>>>? = null
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(calls)
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            answers += results
            view.text += reply
            return ModelOutcome.Done(DoneReason.END)
        }
        override fun close() {}
        override fun abandon(results: List<Pair<String, Map<String, Any?>>>) { abandoned = results }
    }

    private class Actions : TurnActions {
        val ran = mutableListOf<Action>()
        override val locked: Boolean get() = false
        override val settings: RobotSettings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action, why = if (action is Action.AddEvent && action.alert) setOf(AskWhy.REMINDER) else emptySet())
        override suspend fun run(action: Action): Executed {
            ran += action
            return when (action) {
                is Action.SetAlarm -> Executed(ActionStatus.DONE, app.heyrobot.core.i18n.ToolStrings.alarmSet("time" to EventText.clock(action.hour, action.minute)))
                else -> Executed(ActionStatus.DONE, app.heyrobot.core.i18n.ToolStrings.addedToCalendar())
            }
        }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val said = mutableListOf<String>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards[c.id] = c; if (c.status == ActionStatus.PENDING) pending.trySend(c.id) }
        override var text: String = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) { said += sentence }
    }

    private var ids = 0
    private fun alarm(h: Int) = RawToolCall("set_alarm", mapOf("hour" to h))

    @Test fun noTimeRobotAsksWhatTime() = runBlocking {
        val view = View()
        val model = Model(listOf(alarm(18)), view, "I've set an alarm for 6 PM.")
        val actions = Actions()
        TurnRunner(model, actions, view, Confirmations(), false, asked = setOf(ToolName.SET_ALARM), newId = { "t${++ids}" },
            heard = heard("Remind me about it")).run()
        assertTrue(actions.ran.isEmpty())
        assertEquals(listOf(ActionStatus.INVALID), view.cards.values.map { it.status })
        // The model writes nothing after it: Robot asks.
        assertTrue(model.answers.isEmpty())
        assertEquals(AlarmWords.NO_TIME, model.abandoned!!.single().second["detail"])
        assertEquals("What time should the alarm ring?", view.text)
        assertEquals(listOf("What time should the alarm ring?"), view.said)
    }

    @Test fun mattsWordsNeverSetAnAlarm() = runBlocking {
        val view = View()
        val model = Model(listOf(alarm(18)), view)
        val actions = Actions()
        val conf = Confirmations()
        val turn = async {
            TurnRunner(model, actions, view, conf, false, asked = setOf(ToolName.SET_ALARM), newId = { "m${++ids}" }, heard = heard(matt)).run()
        }
        // The dated reminder's card, which asks first.
        val id = withTimeout(5_000) { view.pending.receive() }
        assertEquals(ToolName.ADD_EVENT, view.cards.getValue(id).tool)
        conf.answer(id, true)
        turn.await()
        assertEquals(listOf<Action>(Action.AddEvent("Reminder", "2026-11-20", alert = true)), actions.ran)
        val told = (model.abandoned ?: model.answers.single()).single()
        assertEquals("set_alarm", told.first)
        assertEquals(AlarmWords.OFFERED, told.second["alarm"])
    }

    @Test fun anAlarmWithATimeStillRunsWithoutACard() = runBlocking {
        val view = View()
        val actions = Actions()
        TurnRunner(Model(listOf(alarm(7)), view), actions, view, Confirmations(), false, asked = setOf(ToolName.SET_ALARM), newId = { "s${++ids}" },
            heard = heard("Set an alarm for 7")).run()
        assertEquals(listOf<Action>(Action.SetAlarm(7, 0)), actions.ran)
        assertEquals(listOf(ActionStatus.DONE), view.cards.values.map { it.status })
    }
}
