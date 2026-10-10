package app.heyrobot.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    @Test fun everySpecHasAtMostTwoArgumentsAndValidJson() {
        assertEquals(ToolName.entries.toSet(), ToolSpecs.ALL.map { it.tool }.toSet())
        for (s in ToolSpecs.ALL) {
            val o = JSONObject(s.openApiJson())
            assertEquals(s.tool.id, o.getString("name"))
            val props = o.getJSONObject("parameters").getJSONObject("properties")
            assertTrue(props.length() <= 2)
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

    @Test fun textArgumentsAreTrimmedAndRequired() {
        assertEquals(Action.OpenApp("Signal"), ok("open_app", "name" to "  Signal "))
        assertEquals(Action.MapsSearch("pharmacy"), ok("maps_search", "query" to "pharmacy"))
        assertEquals(Action.MapsNavigate("Central Station"), ok("maps_navigate", "destination" to "Central  Station"))
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

    @Test fun unknownToolsAreRefused() {
        val p = ToolCalls.parse(RawToolCall("send_sms", mapOf("to" to "x")))
        assertEquals(ParsedCall.Invalid("send_sms", "unknown tool"), p)
    }

    @Test fun actionsDescribeThemselvesPlainly() {
        assertEquals("Set a timer for 5 minutes", Action.SetTimer(300).describe())
        assertEquals("Set a timer for 1 hour, 1 minute and 1 second", Action.SetTimer(3661).describe())
        assertEquals("Set an alarm for 07:05", Action.SetAlarm(7, 5).describe())
        assertTrue(Action.OpenApp("Signal").leavesPanel)
        assertTrue(!Action.SetTimer(5).leavesPanel)
    }

    @Test fun textFallbackReadsGemmaStyleCalls() {
        val text = "<start_function_call>call:set_timer{minutes:5}<end_function_call>"
        val calls = ToolCalls.parseText(text)
        assertEquals(1, calls.size)
        assertEquals(RawToolCall("set_timer", mapOf("minutes" to 5L)), calls[0].call)
        assertEquals("", ToolCalls.stripText(text, calls))
        val q = ToolCalls.parseText("call:maps_search{query:<escape>coffee, near me<escape>}")
        assertEquals(mapOf("query" to "coffee, near me"), q.single().call.args)
        assertEquals(Action.MapsSearch("coffee, near me"), (ToolCalls.parse(q.single().call) as ParsedCall.Ok).action)
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

    @Test fun textFallbackIgnoresOrdinaryText() {
        assertTrue(ToolCalls.parseText("A timer counts down. Set one in the Clock app.").isEmpty())
        assertTrue(ToolCalls.parseText("call:send_sms{to:1}").isEmpty())
        assertTrue(ToolCalls.parseText("{\"name\": \"rm\", \"arguments\": {}}").isEmpty())
    }
}
