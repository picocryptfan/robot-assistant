// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.1 (docs/pm/handoff-dated-reminders.md point 1): Robot never says what its card contradicts. On the phone the card read
 * "Alarm set for 18:00" and the reply "I've set an alarm for 6:00 PM on November 20th". A done timer, alarm, event or note
 * is confirmed in Robot's own words from its card; any other sentence about an action that names a day, time or length
 * its card doesn't show goes (ReplyFacts), in English and Québec French.
 */
class ReplyMatchesCardTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 10, 21, 17, 45, 0, 0, zone)

    private fun at(month: Int, day: Int, h: Int = 0, m: Int = 0) = ZonedDateTime.of(2026, month, day, h, m, 0, 0, zone)

    // ------------------------------------------------------------------ what a card shows, and what contradicts it

    private val alarm = ReplyFacts.shown(Action.SetAlarm(18, 0), now, "", Lang.EN)
    private val timer = ReplyFacts.shown(Action.SetTimer(300), now, "", Lang.EN)
    private val plan = EventPlan(at(11, 20), at(11, 21), true, 7L, "Personal", 2026, at(11, 20, 9))
    private val event = ReplyFacts.shown(Action.AddEvent("Call the bank", "on the 20th", alert = true, plan = plan), now, "", Lang.EN)

    private fun contradicts(s: String, shown: Shown, lang: Lang = Lang.EN) = ReplyFacts.contradicts(s, listOf(shown), now, lang)

    @Test fun whatEachCardShows() {
        assertEquals(Shown(setOf(LocalDate.of(2026, 10, 21)), setOf(LocalTime.of(18, 0))), alarm)
        // An alarm for 7:00 set at 17:45 rings tomorrow.
        assertEquals(setOf(LocalDate.of(2026, 10, 22)), ReplyFacts.shown(Action.SetAlarm(7, 0), now, "", Lang.EN).dates)
        assertEquals(Shown(seconds = setOf(300L)), timer)
        assertEquals(Shown(setOf(LocalDate.of(2026, 11, 20)), setOf(LocalTime.of(9, 0))), event)
        // Anything else: the days and times in its words (a text's message).
        val text = ReplyFacts.shown(Action.SendText("Bob", "See you at 5 tomorrow"), now, "Text **Bob**\nSee you at 5 tomorrow", Lang.EN)
        assertEquals(setOf(LocalDate.of(2026, 10, 22)), text.dates)
        assertEquals(setOf(LocalTime.of(17, 0)), text.times)
    }

    @Test fun mattsReplyContradictsItsCard() {
        assertTrue(contradicts("I've set an alarm for 6:00 PM on November 20th.", alarm))
        assertTrue(contradicts("J'ai réglé une alarme pour 18 h le 20 novembre.", alarm, Lang.FR))
        assertTrue(contradicts("Your alarm is set for 7 PM.", alarm))
        assertTrue(contradicts("I've set an alarm for 6 PM tomorrow.", alarm))
        // What the card says may be said.
        assertFalse(contradicts("I've set an alarm for 6:00 PM.", alarm))
        assertFalse(contradicts("I've set an alarm for 6 tonight.", alarm))
        assertFalse(contradicts("Alarme réglée pour 18 h aujourd'hui.", alarm, Lang.FR))
    }

    @Test fun timersAndEvents() {
        assertTrue(contradicts("I've started a 10 minute timer.", timer))
        assertTrue(contradicts("Timer set; it will ring at 5:50 PM.", timer))
        assertFalse(contradicts("I've started a 5 minute timer.", timer))
        assertFalse(contradicts("J'ai réglé une minuterie de cinq minutes.", timer, Lang.FR))
        assertTrue(contradicts("I've added it to your calendar for Friday at 3.", event))
        assertFalse(contradicts("I've added a reminder for November 20th at 9 AM.", event))
        assertFalse(contradicts("Your reminder is set for Friday the 20th.", event))
        assertTrue(contradicts("J'ai ajouté un rappel pour le 21 novembre.", event, Lang.FR))
    }

    @Test fun onlySentencesAboutAnAction() {
        // A read answer or ordinary talk is not about the action: its days and times stay.
        assertFalse(contradicts("You have a meeting at 3 tomorrow.", alarm))
        assertFalse(contradicts("The store opens at 9 on Saturday.", alarm))
    }

    @Test fun lengthsSaidTogetherAddUp() {
        assertEquals(listOf(5400L), ReplyFacts.lengths("an hour and a half"))
        assertEquals(listOf(5400L), ReplyFacts.lengths("1 hour and 30 minutes"))
        assertEquals(listOf(1800L), ReplyFacts.lengths("une demi-heure"))
        assertEquals(listOf(300L, 60L), ReplyFacts.lengths("5 minutes, then wait a minute later"))
    }

    @Test fun scrubKeepsTheRest() {
        assertEquals("Sure!", ReplyFacts.scrub("Sure! I've set an alarm for 6:00 PM on November 20th.", listOf(alarm), now, Lang.EN))
        assertNull(ReplyFacts.scrub("Sure! I've set an alarm for 6 PM.", listOf(alarm), now, Lang.EN))
    }

    // ------------------------------------------------------------------ in a turn

    /** A model that writes [before] before its calls and [reply] after them, as Gemma does. */
    private class Model(private val calls: List<RawToolCall>, private val view: View, private val before: String = "", private val reply: String = "") : TurnModel {
        val answers = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        var abandoned: List<Pair<String, Map<String, Any?>>>? = null
        override suspend fun ask(): ModelOutcome { view.text += before; return ModelOutcome.Calls(calls) }
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            answers += results
            view.text += reply
            return ModelOutcome.Done(DoneReason.END)
        }
        override fun close() {}
        override fun abandon(results: List<Pair<String, Map<String, Any?>>>) { abandoned = results }
    }

    private inner class Actions : TurnActions {
        val ran = mutableListOf<Action>()
        override val locked: Boolean get() = false
        override val settings: RobotSettings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared = when (action) {
            is Action.AddEvent -> Prepared.Ready(action.copy(plan = plan), why = setOf(AskWhy.REMINDER))
            else -> Prepared.Ready(action)
        }
        override suspend fun run(action: Action): Executed {
            ran += action
            return when (action) {
                is Action.SetAlarm -> Executed(ActionStatus.DONE, ToolStrings.alarmSet("time" to EventText.clock(action.hour, action.minute)))
                is Action.SetTimer -> Executed(ActionStatus.DONE, ToolStrings.timerSet("duration" to app.heyrobot.core.i18n.Arg.Seconds(action.seconds)))
                is Action.AddEvent -> Executed(ActionStatus.DONE, ToolStrings.addedToCalendar(), spoken = EventText.added(action.title, action.plan!!, alert = true))
                is Action.Flashlight -> Executed(ActionStatus.DONE, ToolStrings.flashlightOn())
                else -> Executed(ActionStatus.DONE, ToolStrings.savedToNotes())
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
    private fun turn(model: TurnModel, actions: TurnActions, view: View, words: String, lang: Lang = Lang.EN, conf: Confirmations = Confirmations()) =
        TurnRunner(model, actions, view, conf, false, asked = ToolRouter.asked(words), langs = Langs.of(lang), heard = TurnWords.of(words, now, lang),
            newId = { "r${++ids}" })

    @Test fun aDoneAlarmIsConfirmedFromItsCard() = runBlocking {
        val view = View()
        val model = Model(listOf(RawToolCall("set_alarm", mapOf("hour" to 18))), view, reply = "I've set an alarm for 6:00 PM on November 20th.")
        turn(model, Actions(), view, "set an alarm for 6 pm").run()
        // The model hears what happened and writes nothing after it; Robot's line is the card's.
        assertTrue(model.answers.isEmpty())
        assertEquals("Alarm set for 18:00.", view.text)
        assertEquals(listOf("Alarm set for 18:00."), view.said)
    }

    @Test fun aClaimBeforeTheCallThatTheCardContradictsGoes() = runBlocking {
        val view = View()
        val model = Model(listOf(RawToolCall("set_alarm", mapOf("hour" to 18))), view, before = "Sure! I've set an alarm for 6:00 PM on November 20th.")
        turn(model, Actions(), view, "set an alarm for 6 pm").run()
        assertEquals("Sure!\n\nAlarm set for 18:00.", view.text)
    }

    @Test fun aDoneTimerWithAnotherLength() = runBlocking {
        val view = View()
        val model = Model(listOf(RawToolCall("set_timer", mapOf("minutes" to 5))), view, before = "Starting a 10 minute timer.",
            reply = "Your 10 minute timer is running.")
        turn(model, Actions(), view, "set a timer for 5 minutes").run()
        assertEquals("Timer set for 5 minutes.", view.text)
    }

    @Test fun aDoneEventWithAnotherDay() = runBlocking {
        val view = View()
        val model = Model(listOf(RawToolCall("add_event", mapOf("title" to "Call the bank", "when" to "November 20", "alert" to true))), view,
            before = "I'll add it for Friday at 3.", reply = "Done! I've added it to your calendar for Friday at 3 PM.")
        val conf = Confirmations()
        val t = async { turn(model, Actions(), view, "add call the bank to my calendar on November 20", conf = conf).run() }
        conf.answer(withTimeout(5_000) { view.pending.receive() }, true)
        t.await()
        assertTrue(model.answers.isEmpty())
        assertEquals("Added to Personal: “Call the bank”, Fri Nov 20 (all day), with an alert at 09:00.", view.text)
    }

    @Test fun inFrench() = runBlocking {
        val view = View()
        val model = Model(listOf(RawToolCall("set_alarm", mapOf("hour" to 18))), view, before = "C'est fait : j'ai réglé une alarme pour 18 h le 20 novembre.")
        turn(model, Actions(), view, "mets une alarme à 18 h", Lang.FR).run()
        assertEquals("Alarme réglée pour 18 h.", view.text)
    }

    /** Another action than those four: the model's follow-up stays unless a sentence of it names what the card doesn't show. */
    @Test fun theBackstopForOtherActions() = runBlocking {
        val view = View()
        val model = Model(listOf(RawToolCall("flashlight", mapOf("on" to true))), view, reply = "I turned the flashlight on and set an alarm for 7 tomorrow. Anything else?")
        turn(model, Actions(), view, "turn on the flashlight").run()
        assertEquals(1, model.answers.size)
        assertEquals("Anything else?\n\nTurn the flashlight on.", view.text)
        val v2 = View()
        val m2 = Model(listOf(RawToolCall("flashlight", mapOf("on" to true))), v2, reply = "I turned the flashlight on. Anything else?")
        turn(m2, Actions(), v2, "turn on the flashlight").run()
        assertEquals("I turned the flashlight on. Anything else?", v2.text)
    }
}
