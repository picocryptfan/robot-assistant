// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.i18n.TurnStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.0.2, from a 1.0.1 phone run: Robot claimed a note it never saved after you cancelled it, an email to Bob when no contact
 * matched, and a draft to "[Recipient Name]" when you named no one; and the weather lost the town of every question that
 * named a day.
 */
class ActionOutcomeTest {
    // ------------------------------------------------------------------ claims

    @Test fun theRepliesThePhoneGaveAreClaims() {
        for (s in listOf(
            "I have saved that as a note titled \"ROBOT TEST Kitchen list.\"",
            "I have saved a note for you saying \"note that down\" under the title \"To-Do.\"",
            "I have saved that list as a note for you.",
            "I drafted an email to Bob about lunch.",
            "I've added it to your calendar for Friday at 3.",
            "Your note is saved.",
            "The timer is set for 5 minutes.",
            "Done! I've opened Settings for you.",
            "J'ai enregistré la note.",
            "C'est noté.",
        )) assertTrue(s, ActionOutcome.claims(s))
    }

    /** 1.1: Robot's French replies (tu form) claim actions in these words too; its own French lines never read as a claim. */
    @Test fun frenchClaimsAndRobotsFrenchLines() {
        for (s in listOf("Je t'ai créé une note.", "Ta minuterie est réglée.", "Je viens de t'envoyer le texto.", "J'ai appelé Bob.",
            "C'est ajouté à ton agenda.", "J'ai supprimé l'alarme.", "Je l'ai bien noté.", "J'ai allumé la lampe de poche."))
            assertTrue(s, ActionOutcome.claims(s))
        val fr = Langs.of(Lang.FR)
        for (p in listOf(TurnStrings.outcomeCancelled(), TurnStrings.outcomeNotDone(), TurnStrings.outcomeNoOne(), TurnStrings.outcomeDone()))
            assertFalse(p.render(fr), ActionOutcome.claims(p.render(fr)))
        assertFalse(ActionOutcome.claims("Je n'ai pas appelé Bob."))
        // The line in the reply language, from the card's own words when their English matches.
        assertEquals(TurnStrings.outcomeCancelled().render(fr), ActionOutcome.line(Outcome(ToolName.ADD_NOTE, ActionStatus.DECLINED, null)) { it.render(fr) })
        assertEquals(TurnStrings.outcomeNoOne().render(fr),
            ActionOutcome.line(Outcome(ToolName.SEND_TEXT, ActionStatus.INVALID, ToolCalls.NO_RECIPIENT, text = ToolStrings.noRecipient())) { it.render(fr) })
        assertEquals(TurnStrings.stopped().render(fr),
            ActionOutcome.line(Outcome(ToolName.WEATHER, ActionStatus.DECLINED, "Stopped.", text = TurnStrings.stopped())) { it.render(fr) })
    }

    @Test fun refusalsQuestionsAndRobotsOwnLinesAreNot() {
        for (s in listOf(
            ActionOutcome.CANCELLED, ActionOutcome.NOT_DONE, ActionOutcome.NO_ONE,
            "No contact matches “Bob”.", "Unlock your phone, then ask again.", "Saved to Robot Notes.",
            "I didn't save the note.", "I couldn't find Bob in your contacts.", "Nothing was saved.",
            "Would you like me to save it as a note?", "Who should I email?", "You cancelled it, so I haven't saved anything.",
            "Tomorrow in Paris: light rain, high 8°C, low 4°C.", "Je n'ai rien enregistré.", "La note n'a pas été enregistrée.",
            "Here is the list: milk, eggs, bread.",
            // What Robot read on a screen, described after a card you didn't ask for was cancelled: never a claim.
            "Wi-Fi is turned on.", "Bluetooth is set to off.", "The page says your order was sent.", "Notifications are turned off for this app.",
            "La mise à jour est installée.", "I see Bluetooth is turned on.", "Location is set to approximate.", "It's set to approximate.",
        )) assertFalse(s, ActionOutcome.claims(s))
    }

    @Test fun onlyTheClaimingSentencesGo() {
        val t = "Here is your list: milk and eggs. I have saved that list as a note for you.\n\nAnything else?"
        assertEquals("Here is your list: milk and eggs.\n\nAnything else?", ActionOutcome.withoutClaims(t))
        assertEquals("Nothing to change.", ActionOutcome.withoutClaims("Nothing to change."))
    }

    // ------------------------------------------------------------------ Robot's own lines

    @Test fun eachCardGetsALineFromItsRealStatus() {
        assertEquals(ActionOutcome.CANCELLED, ActionOutcome.line(Outcome(ToolName.ADD_NOTE, ActionStatus.DECLINED, null)))
        assertEquals("Stopped.", ActionOutcome.line(Outcome(ToolName.WEATHER, ActionStatus.DECLINED, "Stopped.")))
        assertEquals("No contact matches “Bob”.", ActionOutcome.line(Outcome(ToolName.DRAFT_EMAIL, ActionStatus.NOT_FOUND, "No contact matches “Bob”.")))
        assertEquals("No email app is installed.", ActionOutcome.line(Outcome(ToolName.DRAFT_EMAIL, ActionStatus.NO_APP, "No email app is installed")))
        assertEquals(ActionOutcome.NOT_DONE, ActionOutcome.line(Outcome(ToolName.ADD_NOTE, ActionStatus.FAILED, " ")))
        assertEquals(ActionOutcome.NOT_DONE, ActionOutcome.line(Outcome(ToolName.SET_ALARM, ActionStatus.INVALID, "hour must be a number")))
        assertEquals(ActionOutcome.NO_ONE, ActionOutcome.line(Outcome(ToolName.DRAFT_EMAIL, ActionStatus.INVALID, ToolCalls.NO_RECIPIENT)))
        assertEquals("Timer set for 5 minutes.", ActionOutcome.line(Outcome(ToolName.SET_TIMER, ActionStatus.DONE, "Timer set for 5 minutes")))
        // Two cards that say the same thing: one line.
        assertEquals(listOf(ActionOutcome.CANCELLED), ActionOutcome.lines(listOf(Outcome(ToolName.ADD_NOTE, ActionStatus.DECLINED, null),
            Outcome(ToolName.SET_TIMER, ActionStatus.DECLINED, null))))
    }

    /** The phone run's requests count as asked for in your words: Robot answers for their cards itself. */
    @Test fun theRequestsFromThePhoneRunAreAskedFor() {
        for (q in listOf("save that as a note", "note that down", "add a note: call the garage tomorrow")) assertTrue(q, ToolName.ADD_NOTE in ToolRouter.asked(q))
        assertTrue(ToolName.DRAFT_EMAIL in ToolRouter.asked("email Bob about lunch"))
        // What Robot read or saw is not: "what's on my screen?" asks for no action.
        assertFalse(ToolName.OPEN_APP in ToolRouter.asked("what's on my screen?"))
        assertFalse(ToolName.ADD_NOTE in ToolRouter.asked("what does this note say?"))
    }

    @Test fun robotSpeaksForTheRoundOnlyWhenAnActionYouAskedForDidntHappen() {
        val cancelled = Outcome(ToolName.ADD_NOTE, ActionStatus.DECLINED, null, asked = true)
        assertTrue(ActionOutcome.settles(listOf(cancelled)))
        assertTrue(ActionOutcome.settles(listOf(Outcome(ToolName.DRAFT_EMAIL, ActionStatus.NOT_FOUND, "No contact matches “Bob”.", asked = true))))
        // Not asked for in your words (the model's idea, cancelled): the model answers, as before ("what's on my screen?").
        assertFalse(ActionOutcome.settles(listOf(cancelled.copy(asked = false))))
        // A call it got wrong: the model may call again with what was missing.
        assertFalse(ActionOutcome.settles(listOf(Outcome(ToolName.SET_ALARM, ActionStatus.INVALID, "hour must be a number", asked = true))))
        // A read that found nothing is the model's to answer from.
        assertFalse(ActionOutcome.settles(listOf(Outcome(ToolName.WEATHER, ActionStatus.NOT_FOUND, "Not found.", asked = true))))
        // Something read in the same round is the model's to answer from too.
        assertFalse(ActionOutcome.settles(listOf(cancelled, Outcome(ToolName.READ_CALENDAR, ActionStatus.DONE, "1 event.", content = true))))
        assertFalse(ActionOutcome.settles(listOf(cancelled.copy(status = ActionStatus.DONE))))
    }

    @Test fun theModelIsToldPlainlyThatNothingWasDone() {
        val r = ActionStatus.DECLINED.toolResponse(null)
        val told = ActionOutcome.forModel(r, Outcome(ToolName.ADD_NOTE, ActionStatus.DECLINED, null))
        assertEquals("cancelled_by_user", told["status"])
        assertEquals(ActionOutcome.FOR_MODEL, told["outcome"])
        assertEquals(ActionOutcome.FOR_MODEL, ActionOutcome.forModel(ActionStatus.NOT_FOUND.toolResponse("x"), Outcome(ToolName.DRAFT_EMAIL, ActionStatus.NOT_FOUND, "x"))["outcome"])
        // Done, a draft opened, or a read: as before.
        val done = ActionStatus.DONE.toolResponse("Saved.")
        assertEquals(done, ActionOutcome.forModel(done, Outcome(ToolName.ADD_NOTE, ActionStatus.DONE, "Saved.")))
        val draft = ActionStatus.DRAFT_OPENED.toolResponse("Opened.")
        assertEquals(draft, ActionOutcome.forModel(draft, Outcome(ToolName.DRAFT_EMAIL, ActionStatus.DRAFT_OPENED, "Opened.")))
        // A call with bad arguments: the model may call again with them put right, its reason says what was missing.
        val bad = ActionStatus.INVALID.toolResponse(ToolCalls.NO_RECIPIENT)
        assertEquals(bad, ActionOutcome.forModel(bad, Outcome(ToolName.DRAFT_EMAIL, ActionStatus.INVALID, ToolCalls.NO_RECIPIENT)))
        val none = ActionStatus.NOT_FOUND.toolResponse("No notes match.")
        assertEquals(none, ActionOutcome.forModel(none, Outcome(ToolName.FIND_NOTES, ActionStatus.NOT_FOUND, "No notes match.")))
    }

    // ------------------------------------------------------------------ placeholders

    @Test fun aTemplatesPlaceholderIsNoRecipient() {
        for (p in listOf("[Recipient Name]", "<name>", "{contact}", "(recipient)", "Recipient", "recipient's name", "Recipient Name", "NAME",
            "Destinataire", "[Bob]")) assertTrue(p, ToolCalls.placeholder(p))
        for (n in listOf("Bob", "Mom", "Dr. Name", "Alex Martin", "alex@example.com", "+1 514 555 0101")) assertFalse(n, ToolCalls.placeholder(n))
        for (call in listOf(RawToolCall("draft_email", mapOf("to" to "[Recipient Name]", "subject" to "Lunch", "body" to "Lunch on Friday?")),
            RawToolCall("send_text", mapOf("to" to "<name>", "message" to "On my way")), RawToolCall("call", mapOf("to" to "Recipient")))) {
            assertEquals(call.name, ParsedCall.Invalid(call.name, ToolCalls.NO_RECIPIENT), ToolCalls.parse(call))
        }
        assertTrue(ToolCalls.parse(RawToolCall("draft_email", mapOf("to" to "Bob", "body" to "Lunch?"))) is ParsedCall.Ok)
    }

    // ------------------------------------------------------------------ the order of a tool's arguments

    /**
     * Gemma 4's template shows a tool's parameters, and writes a call's arguments, in alphabetical order; LiteRT-LM's
     * constrained decoding follows the order a spec declares them. An optional one declared before one that sorts before
     * it is skipped whenever the model writes that one: on the phone, the weather's place whenever a day came with it
     * ("Check the weather here for Fri Oct 16" for Paris tomorrow; "Springfield, Atlantis", with no day, kept its place).
     */
    @Test fun noOptionalParameterIsDeclaredBeforeOneThatSortsBeforeIt() {
        val specs = ToolSpecs.ALL + TermuxCalls.SPECS + BrowserCalls.SPEC
        for (spec in specs) {
            for ((i, p) in spec.params.withIndex()) {
                if (p.required) continue
                for (later in spec.params.drop(i + 1)) {
                    assertTrue("${spec.tool.id}: ${p.name} is optional and declared before ${later.name}", p.name < later.name)
                }
            }
        }
        assertEquals(listOf("day", "place"), ToolSpecs.of(ToolName.WEATHER).params.map { it.name })
    }

    /** The other way a place could be lost: the model writing it into the day. That day can't be read, so the card would say so. */
    @Test fun aPlaceInsideTheDayIsNeverReadAsADay() {
        val now = ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, ZoneId.of("America/Toronto"))
        assertTrue(WeatherAsk.days("tomorrow", now) is Read.Ok)
        for (d in listOf("tomorrow in Paris", "tomorrow in London Ontario", "demain à Québec")) assertTrue(d, WeatherAsk.days(d, now) is Read.Unreadable)
        // A weather call with the day alone is here; with both, a named place.
        assertEquals(WeatherWhere.HERE, (ToolCalls.parse(RawToolCall("weather", mapOf("day" to "tomorrow"))) as ParsedCall.Ok).action.let { WeatherAsk.where((it as Action.Weather).place).first })
        val both = (ToolCalls.parse(RawToolCall("weather", mapOf("day" to "tomorrow", "place" to "Paris, France"))) as ParsedCall.Ok).action as Action.Weather
        assertEquals(WeatherWhere.NAMED to "Paris, France", WeatherAsk.where(both.place))
        assertTrue(both.describe().contains("in **Paris, France**"))
    }
}
