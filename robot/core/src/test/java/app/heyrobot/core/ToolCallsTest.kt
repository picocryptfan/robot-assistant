package app.heyrobot.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallsTest {
    private fun ok(name: String, vararg args: Pair<String, Any?>): Action {
        val p = ToolCalls.parse(RawToolCall(name, mapOf(*args)))
        assertTrue("expected Ok for $name ${args.toList()}: $p", p is ParsedCall.Ok)
        return (p as ParsedCall.Ok).action
    }

    private fun bad(name: String, vararg args: Pair<String, Any?>): String {
        val p = ToolCalls.parse(RawToolCall(name, mapOf(*args)))
        assertTrue("expected Invalid for $name ${args.toList()}: $p", p is ParsedCall.Invalid)
        return (p as ParsedCall.Invalid).reason
    }

    @Test fun everySpecHasAtMostThreeArgumentsAndValidJson() {
        assertEquals(ToolName.entries.toSet(), ToolSpecs.ALL.map { it.tool }.toSet())
        // Phase 2 raised the limit to three for draft_email and add_event only; everything else stays at two or fewer.
        assertEquals(setOf(ToolName.DRAFT_EMAIL, ToolName.ADD_EVENT), ToolSpecs.ALL.filter { it.params.size == 3 }.map { it.tool }.toSet())
        for (s in ToolSpecs.ALL) {
            val o = JSONObject(s.openApiJson())
            assertEquals(s.tool.id, o.getString("name"))
            // One sentence each.
            assertEquals(s.tool.id, 1, Regex("[.!?](\\s|$)").findAll(s.description).count())
            val props = o.getJSONObject("parameters").getJSONObject("properties")
            assertTrue(props.length() <= 3)
            val required = o.getJSONObject("parameters").getJSONArray("required")
            for (i in 0 until required.length()) assertTrue(props.has(required.getString(i)))
        }
    }

    @Test fun timerTakesMinutesSecondsOrBoth() {
        assertEquals(Action.SetTimer(300), ok("set_timer", "minutes" to 5))
        assertEquals(Action.SetTimer(90), ok("set_timer", "seconds" to 90))
        assertEquals(Action.SetTimer(150), ok("set_timer", "minutes" to 2, "seconds" to 30))
        assertEquals(Action.SetTimer(90), ok("set_timer", "minutes" to 1.5))
        assertEquals(Action.SetTimer(600), ok("set_timer", "minutes" to "10"))
        assertEquals(Action.SetTimer(600), ok("SET_TIMER", "Minutes" to 10L))
    }

    @Test fun timerRejectsNonsense() {
        bad("set_timer")
        bad("set_timer", "minutes" to 0)
        bad("set_timer", "minutes" to -5)
        bad("set_timer", "minutes" to "soon")
        bad("set_timer", "minutes" to 24 * 60 + 1)
        bad("set_timer", "seconds" to Double.NaN)
    }

    @Test fun alarmValidatesTheClock() {
        assertEquals(Action.SetAlarm(7, 30), ok("set_alarm", "hour" to 7, "minute" to 30))
        assertEquals(Action.SetAlarm(6, 0), ok("set_alarm", "hour" to 6))
        assertEquals(Action.SetAlarm(7, 45), ok("set_alarm", "hour" to "7:45"))
        assertEquals(Action.SetAlarm(23, 59), ok("set_alarm", "hour" to "23", "minute" to "59"))
        bad("set_alarm", "minute" to 5)
        bad("set_alarm", "hour" to 24)
        bad("set_alarm", "hour" to 7, "minute" to 60)
        bad("set_alarm", "hour" to 7.5)
    }

    @Test fun anUnreadableArgumentIsRefusedUnderAnyOfItsNames() {
        // 0.1.0 read {hour:7, minute:"30 am"} as 07:00 and {mins:"abc", seconds:30} as a 30 s timer.
        bad("set_alarm", "hour" to 7, "minute" to "30 am")
        bad("set_alarm", "hour" to 7, "minutes" to "half past")
        bad("set_alarm", "hours" to "seven")
        bad("set_timer", "mins" to "abc", "seconds" to 30)
        bad("set_timer", "minutes" to 5, "secs" to "ten")
        bad("set_timer", "minute" to "five")
        bad("flashlight", "on" to true, "state" to "maybe")
        bad("flashlight", "on" to true, "enabled" to false)
        // Readable aliases still work.
        assertEquals(Action.SetTimer(330), ok("set_timer", "mins" to 5, "secs" to 30))
        assertEquals(Action.SetAlarm(7, 30), ok("set_alarm", "hours" to "7", "min" to 30))
        assertEquals(Action.Flashlight(false), ok("flashlight", "state" to "off"))
    }

    @Test fun textArgumentsAreTrimmedAndRequired() {
        assertEquals(Action.OpenApp("Signal"), ok("open_app", "name" to "  Signal "))
        assertEquals(Action.MapsSearch("pharmacy"), ok("maps_search", "query" to "pharmacy"))
        assertEquals(Action.MapsNavigate("Central Station"), ok("maps_navigate", "destination" to "Central  Station"))
        assertEquals(Action.MapsSearch("pharmacy"), ok("maps_search", "query" to "a pharmacy near me"))
        assertEquals(Action.MapsNavigate("pharmacy"), ok("maps_navigate", "destination" to "the nearest pharmacy"))
        bad("open_app")
        bad("open_app", "name" to "   ")
        bad("maps_search", "query" to null)
        assertEquals(60, (ok("open_app", "name" to "x".repeat(500)) as Action.OpenApp).name.length)
    }

    @Test fun flashlightAcceptsBooleanSpellings() {
        assertEquals(Action.Flashlight(true), ok("flashlight", "on" to true))
        assertEquals(Action.Flashlight(false), ok("flashlight", "on" to "off"))
        assertEquals(Action.Flashlight(true), ok("flashlight", "on" to 1))
        bad("flashlight")
        bad("flashlight", "on" to "maybe")
    }

    @Test fun sendTextNeedsWhoAndWhatAndKeepsLineBreaks() {
        assertEquals(Action.SendText("Mom", "I'm running late"), ok("send_text", "to" to " Mom ", "message" to "I'm running late"))
        assertEquals(Action.SendText("+1 514 555 0101", "Line one\nLine two"), ok("send_text", "to" to "+1 514 555 0101", "message" to "Line one\r\nLine \t two"))
        assertEquals(Action.SendText("Alex", "hi"), ok("send_text", "recipient" to "Alex", "text" to "hi"))
        bad("send_text", "message" to "hi")
        bad("send_text", "to" to "Mom")
        bad("send_text", "to" to "Mom", "message" to "   ")
        bad("send_text", "to" to "", "message" to "hi")
        assertEquals(ToolCalls.MAX_MESSAGE, (ok("send_text", "to" to "Mom", "message" to "x".repeat(5_000)) as Action.SendText).message.length)
    }

    @Test fun draftEmailNeedsAnAddresseeAndSomethingToSay() {
        assertEquals(Action.DraftEmail("Alex", "Friday's plan", "Hi Alex,\n\nSee you then."),
            ok("draft_email", "to" to "Alex", "subject" to "Friday's plan", "body" to "Hi Alex,\n\n\n\nSee you then."))
        assertEquals(Action.DraftEmail("alex@example.org", "", "Hi"), ok("draft_email", "to" to "alex@example.org", "body" to "Hi"))
        assertEquals(Action.DraftEmail("Alex", "Lunch?", ""), ok("draft_email", "to" to "Alex", "subject" to "Lunch?"))
        bad("draft_email", "subject" to "x", "body" to "y")
        bad("draft_email", "to" to "Alex")
        bad("draft_email", "to" to "Alex", "subject" to " ", "body" to "")
    }

    @Test fun lookupAndNotesTakeText() {
        assertEquals(Action.LookupContact("Alex"), ok("lookup_contact", "name" to "Alex"))
        bad("lookup_contact")
        bad("lookup_contact", "name" to null)
        assertEquals(Action.AddNote("buy coffee filters"), ok("add_note", "text" to "buy coffee filters"))
        assertEquals(Action.AddNote("milk\neggs", "Groceries"), ok("add_note", "text" to "milk\neggs", "title" to "Groceries"))
        bad("add_note", "title" to "Groceries")
        bad("add_note", "text" to "  ")
        assertEquals(Action.FindNotes("coffee"), ok("find_notes", "query" to "coffee"))
        bad("find_notes")
        bad("find_notes", "query" to "")
    }

    @Test fun addEventTakesWordsForWhenAndAnOptionalWholeLength() {
        assertEquals(Action.AddEvent("Dentist", "Tuesday at 3"), ok("add_event", "title" to "Dentist", "when" to "Tuesday at 3"))
        assertEquals(Action.AddEvent("Dentist", "Tuesday at 3", 30), ok("add_event", "title" to "Dentist", "when" to "Tuesday at 3", "duration_minutes" to 30))
        assertEquals(Action.AddEvent("Dentist", "Tuesday at 3", 45), ok("add_event", "title" to "Dentist", "when" to "Tuesday at 3", "duration_minutes" to "45"))
        bad("add_event", "when" to "Tuesday")
        bad("add_event", "title" to "Dentist")
        bad("add_event", "title" to "Dentist", "when" to "Tuesday", "duration_minutes" to "an hour")
        bad("add_event", "title" to "Dentist", "when" to "Tuesday", "duration_minutes" to 0)
        bad("add_event", "title" to "Dentist", "when" to "Tuesday", "duration_minutes" to 2.5)
        bad("add_event", "title" to "Dentist", "when" to "Tuesday", "duration_minutes" to 24 * 60 + 1)
        // The model never builds a timestamp: the words are kept for the app to read.
        assertEquals("2026-10-14T15:00", (ok("add_event", "title" to "x", "when" to "2026-10-14T15:00") as Action.AddEvent).whenText)
    }

    @Test fun addEventJoinsASplitDateAndTime() {
        // Review A M4: only the first of when/time/date/start was kept, so the date was lost.
        fun w(vararg args: Pair<String, Any?>) = (ok("add_event", "title" to "Dentist", *args) as Action.AddEvent).whenText
        assertEquals("Tuesday 3pm", w("date" to "Tuesday", "time" to "3pm"))
        assertEquals("Tuesday 3pm", w("time" to "3pm", "date" to "Tuesday"))
        assertEquals("Tuesday 3pm", w("when" to "Tuesday", "time" to "3pm"))
        assertEquals("Tuesday 3pm", w("day" to "Tuesday", "start" to "3pm"))
        assertEquals("2026-10-14T15:00 to 2026-10-14T16:30", w("start" to "2026-10-14T15:00", "end" to "2026-10-14T16:30"))
        assertEquals("Friday 9 to 10:30", w("when" to "Friday 9", "end" to "10:30"))
        bad("add_event", "title" to "Dentist", "date" to "Tuesday", "time" to true)
        bad("add_event", "title" to "Dentist", "end" to "4pm")
        // An unknown key is ignored as before; the parser then reads the joined words.
        assertEquals("Tuesday", w("when" to "Tuesday", "colour" to "blue"))
        val now = java.time.ZonedDateTime.of(2026, 10, 10, 9, 5, 0, 0, java.time.ZoneId.of("America/Toronto"))
        val a = ok("add_event", "title" to "Dentist", "date" to "Tuesday", "time" to "3pm") as Action.AddEvent
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 13, 15, 0, 0, 0, now.zone), (Events.times(a, now, 60) as Read.Ok).value.start)
    }

    @Test fun quotesNumbersAndEmojiSurviveValidation() {
        // Review A minors 1, 2, 15.
        assertEquals("Tell him \"yes\"", (ok("send_text", "to" to "Mom", "message" to "Tell him \"yes\"") as Action.SendText).message)
        assertEquals("'sup", (ok("send_text", "to" to "Mom", "message" to "'sup") as Action.SendText).message)
        assertEquals("on my way", (ok("send_text", "to" to "Mom", "message" to "\"on my way\"") as Action.SendText).message)
        assertEquals("Signal", (ok("open_app", "name" to "'Signal'") as Action.OpenApp).name)
        assertEquals("5145550101", (ok("send_text", "to" to 5.145550101E9, "message" to "hi") as Action.SendText).to)
        assertEquals("5145550101", (ok("send_text", "to" to 5145550101L, "message" to "hi") as Action.SendText).to)
        // A cut never splits an emoji in two.
        val msg = "a".repeat(ToolCalls.MAX_MESSAGE - 1) + "😀😀"
        val cut = (ok("send_text", "to" to "Mom", "message" to msg) as Action.SendText).message
        assertEquals(ToolCalls.MAX_MESSAGE - 1, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
        assertEquals("ab", ToolCalls.safeTake("ab😀", 3))
        assertEquals("ab😀", ToolCalls.safeTake("ab😀", 4))
    }

    @Test fun textCallMessagesKeepCommasAndBraces() {
        // Review A minor 14: a bare value stopped at the first comma, the body at the first brace.
        val bare = ToolCalls.parseText("call:send_text{to:Mom,message:I'm late, sorry}").single().call
        assertEquals(mapOf("to" to "Mom", "message" to "I'm late, sorry"), bare.args)
        val braces = ToolCalls.parseText("call:add_note{text:<escape>use {braces} here<escape>}").single().call
        assertEquals(mapOf("text" to "use {braces} here"), braces.args)
        assertEquals(mapOf("hour" to 7L, "minute" to 30L), ToolCalls.parseText("call:set_alarm{hour:7,minute:30}").single().call.args)
    }

    @Test fun readCalendarDefaultsToToday() {
        assertEquals(Action.ReadCalendar("tomorrow"), ok("read_calendar", "day" to "tomorrow"))
        assertEquals(Action.ReadCalendar("next week"), ok("read_calendar", "date" to "next week"))
        assertEquals(Action.ReadCalendar("today"), ok("read_calendar"))
        bad("read_calendar", "day" to "")
        bad("read_calendar", "day" to true)
    }

    @Test fun phaseTwoActionsDescribeWhatWillHappen() {
        val target = TextTarget("Mom", "+15145550101@cheogram.com", "+1 514-555-0101", "Cheogram", TextVia.CHEOGRAM)
        val t = Action.SendText("mom", "I'm late", target)
        assertEquals("Text **Mom** (+1 514-555-0101) in Cheogram", t.describe())
        assertEquals("I'm late", t.body())
        // The button names the person: on a long message the card's top line can be out of sight.
        assertEquals("Open in Cheogram for Mom", t.confirmLabel)
        assertEquals("Open in Cheogram for +1 514-555-0101", t.copy(target = target.copy(name = null)).confirmLabel)
        assertEquals("Text **+1 514-555-0101** in Cheogram", t.copy(target = target.copy(name = null)).describe())
        assertEquals("Text **mom**", t.copy(target = null).describe())
        val e = Action.DraftEmail("Alex", "Friday", "Hi", MailTarget("Alex Martin", "alex@example.org", "Proton Mail"))
        assertEquals("Email **Alex Martin** (alex@example.org) in Proton Mail", e.describe())
        assertEquals("Subject: Friday\n\nHi", e.body())
        assertEquals("Open in Proton Mail for Alex Martin", e.confirmLabel)
        assertEquals("Choose the email app for alex@example.org", e.copy(target = MailTarget(null, "alex@example.org", null)).confirmLabel)
        val zone = java.time.ZoneId.of("America/Toronto")
        val start = java.time.ZonedDateTime.of(2026, 10, 14, 15, 0, 0, 0, zone)
        val ev = Action.AddEvent("Dentist", "Wednesday at 3", plan = EventPlan(start, start.plusHours(1), false, 3L, "Personal", 2026))
        assertEquals("Add **Dentist** on Wed Oct 14, 15:00–16:00 to *Personal*", ev.describe())
        assertEquals("Add to Personal", ev.confirmLabel)
        val fallback = ev.copy(plan = ev.plan!!.copy(calendarId = null, calendarName = null))
        assertEquals("Add **Dentist** on Wed Oct 14, 15:00–16:00 in your calendar app", fallback.describe())
        assertEquals("Open the calendar app", fallback.confirmLabel)
        assertEquals("Add **Dentist** (Wednesday at 3)", Action.AddEvent("Dentist", "Wednesday at 3").describe())
    }

    @Test fun phaseTwoTextCallsAreReadBack() {
        val c = ToolCalls.wholeReplyCalls("call:send_text{to:Mom,message:<escape>I'm late, sorry<escape>}")
        assertEquals(Action.SendText("Mom", "I'm late, sorry"), (ToolCalls.parse(c.single().call) as ParsedCall.Ok).action)
        val e = ToolCalls.parseText("call:add_event{title:Dentist,when:<escape>Tuesday 3pm<escape>,duration_minutes:30}")
        assertEquals(Action.AddEvent("Dentist", "Tuesday 3pm", 30), (ToolCalls.parse(e.single().call) as ParsedCall.Ok).action)
    }

    @Test fun unknownToolsAreRefused() {
        val p = ToolCalls.parse(RawToolCall("send_sms", mapOf("to" to "x")))
        assertEquals(ParsedCall.Invalid("send_sms", "unknown tool"), p)
    }

    @Test fun actionsDescribeThemselvesPlainly() {
        assertEquals("Set a timer for 5 minutes", Action.SetTimer(300).describe())
        assertEquals("Set a timer for 1 hour, 1 minute and 1 second", Action.SetTimer(3661).describe())
        assertEquals("Set an alarm for 07:05", Action.SetAlarm(7, 5).describe())
    }

    @Test fun textFallbackReadsGemmaStyleCalls() {
        val text = "<start_function_call>call:set_timer{minutes:5}<end_function_call>"
        val calls = ToolCalls.parseText(text)
        assertEquals(1, calls.size)
        assertEquals(RawToolCall("set_timer", mapOf("minutes" to 5L)), calls[0].call)
        assertEquals("", ToolCalls.stripText(text, calls))
        val q = ToolCalls.parseText("call:maps_search{query:<escape>coffee, near me<escape>}")
        assertEquals(mapOf("query" to "coffee, near me"), q.single().call.args)
        // Organic Maps searches around you already: "near me" is left out of what it searches (MapsQuery).
        assertEquals(Action.MapsSearch("coffee"), (ToolCalls.parse(q.single().call) as ParsedCall.Ok).action)
    }

    @Test fun textFallbackReadsJsonAndPythonCalls() {
        val j = ToolCalls.parseText("Sure! {\"name\": \"flashlight\", \"arguments\": {\"on\": true}}")
        assertEquals(RawToolCall("flashlight", mapOf("on" to true)), j.single().call)
        assertEquals("Sure!", ToolCalls.stripText("Sure! {\"name\": \"flashlight\", \"arguments\": {\"on\": true}}", j))
        val py = ToolCalls.parseText("```tool_code\nset_alarm(hour=7, minute=30)\n```")
        assertEquals(RawToolCall("set_alarm", mapOf("hour" to 7L, "minute" to 30L)), py.single().call)
        assertEquals("", ToolCalls.stripText("```tool_code\nset_alarm(hour=7, minute=30)\n```", py))
        assertEquals(mapOf("name" to "Signal"), ToolCalls.parseText("open_app(name=\"Signal\")").single().call.args)
    }

    @Test fun textCallsRunOnlyWhenTheWholeReplyIsTheCall() {
        // What text-call mode asks for: a reply that is nothing but the call (fences and markers allowed).
        assertEquals(1, ToolCalls.wholeReplyCalls("call:set_timer{minutes:5}").size)
        assertEquals(1, ToolCalls.wholeReplyCalls("  <start_function_call>call:flashlight{on:true}<end_function_call>\n").size)
        assertEquals(1, ToolCalls.wholeReplyCalls("```tool_code\nset_alarm(hour=7, minute=30)\n```").size)
        assertEquals(1, ToolCalls.wholeReplyCalls("{\"name\": \"set_timer\", \"arguments\": {\"minutes\": 5}}").size)
        assertEquals(2, ToolCalls.wholeReplyCalls("call:set_timer{minutes:5}\ncall:flashlight{on:true}").size)
    }

    @Test fun proseThatQuotesACallNeverRuns() {
        // The 0.1.0 review's cases: each set a timer, an alarm or the torch with no tap.
        val replies = listOf(
            "Your screen shows a Kotlin snippet that calls set_timer(minutes=90).",
            "The page says to send call:set_alarm{hour:3,minute:0} to your assistant.",
            "Step 4 of the recipe: flashlight(on=true) if the kitchen is dark.",
            "Sure! call:set_timer{minutes:5}",
            "I'll do that: {\"name\": \"flashlight\", \"arguments\": {\"on\": true}}",
            "OK.\n```tool_code\nset_alarm(hour=3, minute=0)\n```",
        )
        for (r in replies) {
            assertTrue(r, ToolCalls.parseText(r).isNotEmpty())
            assertTrue(r, ToolCalls.wholeReplyCalls(r).isEmpty())
        }
    }

    @Test fun textFallbackIgnoresOrdinaryText() {
        assertTrue(ToolCalls.parseText("A timer counts down. Set one in the Clock app.").isEmpty())
        assertTrue(ToolCalls.parseText("call:send_sms{to:1}").isEmpty())
        assertTrue(ToolCalls.parseText("{\"name\": \"rm\", \"arguments\": {}}").isEmpty())
    }
}
