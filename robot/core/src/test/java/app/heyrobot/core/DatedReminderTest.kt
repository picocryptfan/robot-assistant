// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.1, dated reminders (docs/pm/handoff-dated-reminders.md point 4): "remind me to … on <date>" is recognised before the
 * model (RoutineRouter, after brief reminders) and becomes add_event with an alert, whose card always asks and names the
 * calendar, and whose line Robot says is the card's own words.
 */
class DatedReminderTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 10, 21, 17, 45, 0, 0, zone)

    private fun route(text: String) = RoutineRouter.route(text, now)

    private fun on(title: String, whenText: String) = RoutineCommand.RemindOn(title, whenText)

    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    @Test fun mattsWordsAreADatedReminder() {
        assertEquals(on("Turn off Netflix", "on the twentieth of November"), route("Remind me to turn off Netflix on the twentieth of November."))
        assertEquals(on("Call the bank", "on the twentieth of November"), route("Remind me to call the bank on the twentieth of November."))
        assertEquals(on("Appeler le dentiste", "le vingt novembre"), route("Rappelle-moi d'appeler le dentiste le vingt novembre"))
        assertEquals(on("Appeler le dentiste", "le 20 novembre"), route("rappelle-moi d'appeler le dentiste le 20 novembre"))
    }

    @Test fun withATime() {
        assertEquals(on("Stretch", "today at 5:50 pm"), route("Remind me to stretch today at 5:50 pm"))
        assertEquals(on("Call Bob", "at 8 tomorrow morning"), route("remind me to call Bob at 8 tomorrow morning"))
        assertEquals(on("Bring the passport", "tomorrow at 8"), route("remind me tomorrow at 8 to bring the passport"))
        assertEquals(on("Appeler Bob", "demain à 8 h"), route("rappelle-moi demain à 8 h d'appeler Bob"))
        assertEquals(on("Pay the rent", "on November 20th at 9"), route("Hey Robot, remind me to pay the rent on November 20th at 9"))
        assertEquals(on("Call mom", "on Friday"), route("set a reminder to call mom on Friday"))
        assertEquals(on("Call mom", "for Friday at 3"), route("set a reminder for Friday at 3 to call mom"))
    }

    @Test fun briefRemindersComeFirst() {
        assertEquals(RoutineCommand.Remind("bring the passport", true), route("remind me tomorrow morning to bring the passport"))
        assertEquals(RoutineCommand.Remind("bring the passport", false), route("Remind me in the morning to bring the passport."))
    }

    @Test fun noDayNoDatedReminder() {
        for (t in listOf("remind me to call Bob at 5", "remind me in the morning at 7 to call Bob", "remind me to call Bob",
            // A part of the day alone is a time you didn't say; "in 20 minutes" is a timer's.
            "remind me to call Bob tonight", "remind me to call Bob on Friday evening", "remind me to call Bob in 20 minutes"))
            assertNull(t, route(t))
        // 1.1 (phone, 22 Oct): nothing to remind and no time is never a dated reminder either: Robot asks what and when.
        for (t in listOf("Remind me about it", "rappelle-moi ça")) assertEquals(t, RoutineCommand.RemindWhat, route(t))
    }

    @Test fun repliedInTheLanguageItWasSaidIn() {
        assertEquals(Lang.FR, RoutineRouter.lang("Rappelle-moi d'appeler le dentiste le vingt novembre", route("Rappelle-moi d'appeler le dentiste le vingt novembre")!!))
        assertEquals(Lang.EN, RoutineRouter.lang("Remind me to call the bank on the 20th", route("Remind me to call the bank on the 20th")!!))
    }

    @Test fun lockedItSaysToUnlock() {
        val c = route("Remind me to call the bank on the twentieth of November.")!!
        assertEquals("Unlock first: reminders only go in your calendar with the phone unlocked.", RoutineLockedPhone.refuse(c, locked = true))
        assertNull(RoutineLockedPhone.refuse(c, locked = false))
    }

    // ------------------------------------------------------------------ the call and its card

    @Test fun theAlertTravelsWithTheCall() {
        val a = Action.AddEvent("Call the bank", "on the twentieth of November", alert = true)
        val args = RoutineCodec.args(a)
        assertEquals(true, args["alert"])
        assertEquals(a, (ToolCalls.parse(RawToolCall("add_event", args)) as ParsedCall.Ok).action)
        // A plain event stays as it was.
        assertFalse(RoutineCodec.args(Action.AddEvent("Dentist", "Friday 3pm")).containsKey("alert"))
        assertFalse(((ToolCalls.parse(RawToolCall("add_event", mapOf("title" to "Dentist", "when" to "Friday 3pm"))) as ParsedCall.Ok).action as Action.AddEvent).alert)
    }

    private val allDay = EventPlan(at(11, 20), at(11, 21), true, 7L, "Personal", 2026, at(11, 20, 9))
    private val timed = EventPlan(at(10, 21, 18, 5), at(10, 21, 19, 5), false, 7L, "Personal", 2026, at(10, 21, 18, 5))

    private fun at(month: Int, day: Int, h: Int = 0, m: Int = 0) = ZonedDateTime.of(2026, month, day, h, m, 0, 0, zone)

    @Test fun theCardNamesTheCalendarAndTheAlert() {
        val a = Action.AddEvent("Call the bank", "on the twentieth of November", alert = true, plan = allDay)
        assertEquals("Add **Call the bank** on Fri Nov 20 (all day) to *Personal*, with an alert at 09:00", a.summary().en())
        assertEquals("Ajouter **Call the bank** le vendredi 20 novembre (toute la journée) à *Personal*, avec une alerte à 9 h",
            plain(a.summary().render(Langs.of(Lang.FR))))
        val inApp = a.copy(plan = allDay.copy(calendarId = null, calendarName = null))
        assertEquals("Add **Call the bank** on Fri Nov 20 (all day) in your calendar app, with an alert at 09:00", inApp.summary().en())
        // An alert already past is never promised.
        assertEquals("Add **Call the bank** on Wed Oct 21 (all day) to *Personal*",
            a.copy(plan = allDay.copy(start = at(10, 21), end = at(10, 22), alertAt = null)).summary().en())
    }

    @Test fun itsCardAlwaysAsksFirst() {
        val auto = RobotSettings().let { it.copy(approvals = it.approvals + (ToolName.ADD_EVENT to Approval.AUTO)) }
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.ADD_EVENT, false, auto, asked = true))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.ADD_EVENT, false, auto, setOf(AskWhy.REMINDER), asked = true))
        // A yes may answer it.
        assertNull(CardAnswer.tapFor(setOf(AskWhy.REMINDER)))
    }

    @Test fun robotsLineIsTheCardsWords() {
        assertEquals("Added to Personal: “Call the bank”, Fri Nov 20 (all day), with an alert at 09:00.", EventText.added("Call the bank", allDay, alert = true).en())
        assertEquals("Ajouté à Personal : « Appeler la banque », vendredi 20 novembre (toute la journée), avec une alerte à 9 h.",
            plain(EventText.added("Appeler la banque", allDay, alert = true).render(Langs.of(Lang.FR))))
        assertEquals("Added to Personal: “Stretch”, Wed Oct 21, 18:05–19:05, with an alert at 18:05.", EventText.added("Stretch", timed, alert = true).en())
        // The alert's row didn't go in: the line says so, never claims it.
        assertEquals("Added to Personal: “Call the bank”, Fri Nov 20 (all day). Its alert couldn't be set: add one in your calendar app.",
            EventText.added("Call the bank", allDay, alert = false).en())
        // An event without an alert.
        assertEquals("Added to Personal: “Dentist”, Wed Oct 21, 18:05–19:05.", EventText.added("Dentist", timed.copy(alertAt = null), alert = false).en())
        assertTrue(ActionOutcome.line(Outcome(ToolName.ADD_EVENT, ActionStatus.DONE, "Added to the calendar.",
            spokenText = EventText.added("Call the bank", allDay, true), spoken = EventText.added("Call the bank", allDay, true).en())).contains("Fri Nov 20"))
    }
}
