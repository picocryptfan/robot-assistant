package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The weather tool (0.4.0) as the rest of Robot sees it: routing, the gate, the prompt, settings and the pre-router. */
class WeatherToolTest {
    private val now = ZonedDateTime.of(2026, 10, 10, 9, 5, 0, 0, ZoneId.of("America/Toronto"))

    @Test fun weatherWordsReachTheWeatherGroupInEnglishAndFrench() {
        val asks = listOf("what's the weather tomorrow", "will it rain on Saturday", "is it going to snow", "what's the temperature outside",
            "cloud cover tomorrow?", "Okay, cloud cover tomorrow?", "how cloudy is it", "how windy is it", "do I need an umbrella",
            "what's the forecast for Paris", "is it raining in Montreal", "quelle est la météo demain", "va-t-il pleuvoir", "il pleut ?",
            "est-ce qu'il va neiger", "quelle température fait-il", "est-ce nuageux demain", "y a-t-il du vent", "faut-il un parapluie",
            "quel temps fait-il à Québec")
        for (q in asks) assertTrue(q, ToolGroup.WEATHER in ToolRouter.route(q))
        for (q in listOf("set a timer for 5 minutes", "text Mom I'm late", "what's the capital of France", "ask Claude why the sky is blue")) {
            assertFalse(q, ToolGroup.WEATHER in ToolRouter.route(q))
        }
        // The tool is described with its group, unless turned off.
        val d = RobotSettings()
        assertTrue(ToolName.WEATHER in ToolRouter.tools(setOf(ToolGroup.CORE, ToolGroup.WEATHER), d))
        assertFalse(ToolName.WEATHER in ToolRouter.tools(setOf(ToolGroup.CORE), d))
        assertFalse(ToolName.WEATHER in ToolRouter.tools(ToolGroup.entries.toSet(), d.copy(approvals = d.approvals + (ToolName.WEATHER to Approval.OFF))))
        assertEquals("core.weather", ToolRouter.label(Prompt.CORE_TOOLS + ToolName.WEATHER))
    }

    @Test fun cloudIsAMishearingOfClaudeOnlyAfterACommand() {
        // "cloud cover" stays Robot's (and routes to the weather); "ask cloud about …" is still Claude.
        for (q in listOf("cloud cover tomorrow?", "Okay, cloud cover tomorrow?", "what's the cloud cover tomorrow", "clouds this afternoon?")) {
            assertNull(q, ClaudeRouter.route(q))
        }
        assertTrue(ClaudeRouter.route("ask cloud about the weather in Paris") is ClaudeCommand.Ask)
    }

    @Test fun withTheToolOnWeatherStaysOnThePhone() {
        // Off (or before 0.4.0): weather is current information for Claude.
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what's the weather tomorrow"))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what's the forecast for the weekend"))
        // On: Robot's own.
        assertNull(PreRouter.signal("what's the weather tomorrow", localWeather = true))
        assertNull(PreRouter.signal("what's the forecast for the weekend", localWeather = true))
        assertNull(PreRouter.signal("cloud cover tomorrow?", localWeather = true))
        assertNull(PreRouter.signal("any weather news for Montreal", localWeather = true))
        // Other signals still count, and other current information still goes.
        assertEquals(PreRouter.Signal.REASONING, PreRouter.signal("explain why it rains more in the spring", localWeather = true))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what's the latest on the election", localWeather = true))
        assertEquals(PreRouter.Signal.CURRENT_INFO, PreRouter.signal("what's today's price of bitcoin", localWeather = true))
    }

    @Test fun thePromptOffersTheWeatherOnlyWhenTheToolIsThere() {
        val without = Prompt.system(now, locked = false)
        assertTrue(without.contains("for news, weather or prices, say you can't check that"))
        val with = Prompt.system(now, locked = false, weather = true)
        assertTrue(with.contains("For the weather, call weather."))
        assertTrue(with.contains("for news or prices, say you can't check that"))
        assertFalse(with.contains("news, weather or prices"))
        val claude = Prompt.system(now, locked = false, askClaude = true, weather = true)
        assertTrue(claude.contains("for the weather, call weather; for up-to-date facts"))
        // A dozen tokens more, and only in conversations that describe the tool.
        assertTrue(TokenEstimate.text(with) - TokenEstimate.text(without) in 0..20)
        assertTrue(TokenEstimate.text(claude) - TokenEstimate.text(Prompt.system(now, locked = false, askClaude = true)) in 0..20)
        // Its schema is small: the weather group costs well under 150 tokens.
        val system = Prompt.system(now, locked = false)
        val cost = Prompt.fixedTokens(system, Prompt.CORE_TOOLS + ToolName.WEATHER) - Prompt.fixedTokens(system, Prompt.CORE_TOOLS)
        assertTrue("weather $cost", cost in 1..150)
        assertTrue(Prompt.textCallHint(listOf(ToolName.WEATHER)).contains("call:weather{place:Paris,day:tomorrow}"))
    }

    @Test fun itJustRunsUnlockedAndNeverOnTheLockScreen() {
        val d = RobotSettings()
        assertEquals(Approval.AUTO, d.approval(ToolName.WEATHER))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.WEATHER, locked = false, settings = d))
        // It reads, but online: in a conversation with screen text, a photo or a read tool's findings it shows a card first.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEATHER, locked = false, settings = d, mustAsk = true))
        // Locked: it would say where your Home for weather is, and go online, for whoever holds the phone.
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.WEATHER, locked = true, settings = d))
        assertFalse(ToolName.WEATHER in ApprovalGate.ALLOWED_WHEN_LOCKED)
        assertTrue(ToolName.WEATHER.reads && ToolName.WEATHER.online && !ToolName.WEATHER.internal)
        // Claude is never offered it (it has web search, and where you are is yours).
        assertFalse(ToolName.WEATHER in ClaudeTools.OFFERED)
    }

    @Test fun theCallTakesAnOptionalPlaceAndDay() {
        fun parse(args: Map<String, Any?>) = (ToolCalls.parse(RawToolCall("weather", args)) as ParsedCall.Ok).action as Action.Weather
        assertEquals(Action.Weather(null, null), parse(emptyMap()))
        assertEquals(Action.Weather("Paris", "tomorrow"), parse(mapOf("place" to " Paris ", "day" to "tomorrow")))
        assertEquals(Action.Weather("Lyon", "Friday"), parse(mapOf("city" to "Lyon", "date" to "Friday")))
        assertEquals(Action.Weather(null, null), parse(mapOf("place" to "", "day" to " ")))
        assertEquals("Check the weather here", Action.Weather(null, null).describe())
        assertEquals("Check the weather in **Paris** for tomorrow", Action.Weather("in Paris", "tomorrow").describe())
        assertEquals("Check the weather at home", Action.Weather("home", null).describe())
        val range = (WeatherAsk.days("tomorrow", now) as Read.Ok).value
        assertEquals("Check the weather here for Sun Oct 11", Action.Weather(null, "tomorrow", WeatherWhere.HERE, range).describe())
        // The text-call form reads back.
        val calls = ToolCalls.wholeReplyCalls("call:weather{place:Paris,day:tomorrow}")
        assertEquals(RawToolCall("weather", mapOf("place" to "Paris", "day" to "tomorrow")), calls.single().call)
    }

    @Test fun theHomeForWeatherIsSavedWithSchemaFive() {
        val home = WeatherPlace("Montreal, Quebec, Canada", 45.50884, -73.58781)
        val s = RobotSettings(weatherHome = home)
        val json = s.toJson()
        assertEquals(5, json.getInt("schema"))
        assertEquals(s, RobotSettings.fromJson(json.toString()))
        assertNull(RobotSettings().weatherHome)
        assertFalse(RobotSettings().toJson().has("weatherHome"))
        // A schema 4 file (0.3.1) has none, and the new action its default; its own migration still holds.
        val old = RobotSettings.fromJson("""{"schema":4,"approvals":{"open_app":"ASK","send_text":"OFF"}}""")
        assertNull(old.weatherHome)
        assertEquals(Approval.AUTO, old.approval(ToolName.WEATHER))
        assertEquals(Approval.ASK, old.approval(ToolName.OPEN_APP))
        assertEquals(Approval.AUTO, RobotSettings.fromJson("""{"schema":3,"approvals":{"open_app":"ASK"}}""").approval(ToolName.OPEN_APP))
        // A damaged place is dropped, never fatal.
        assertNull(RobotSettings.fromJson("""{"schema":5,"weatherHome":{"name":"X","lat":95,"lon":0}}""").weatherHome)
        assertNull(RobotSettings.fromJson("""{"schema":5,"weatherHome":{"name":" ","lat":1,"lon":1}}""").weatherHome)
        assertNull(RobotSettings.fromJson("""{"schema":5,"weatherHome":"Montreal"}""").weatherHome)
    }

    @Test fun aWeatherLookupMakesTheConversationPersonal() {
        val q = ChatTurn("1", TurnRole.USER, "what's the weather tomorrow", 1)
        val a = ChatTurn("2", TurnRole.ASSISTANT, "Cloudy, 12 degrees.", 2,
            actions = listOf(ActionRecord(ToolName.WEATHER, "Check the weather here for Sun Oct 11", ActionStatus.DONE, "Forecast for where you are, from Open-Meteo.", content = true)))
        val history = listOf(q, a)
        assertEquals(setOf(Personal.LOCATION), ClaudeContext.personal(history))
        assertEquals("Include the conversation so far? It contains your location.", ClaudeContext.includeQuestion(history))
        // Robot checking with Claude by itself then sends the question only, and later actions ask first.
        val blocks = ClaudeContext.escalation("and what about Paris?", history, now, "CA")
        assertFalse(blocks.filterIsInstance<ClaudeBlock.Text>().any { it.text.contains("Cloudy") || it.text.contains("weather tomorrow") })
        assertTrue(TurnRules.untrusted(history))
        // The card's saved line names no place.
        assertFalse(a.actions.single().detail!!.contains("Montreal"))
    }

    @Test fun theVoiceLogSaysHowNotWhere() {
        assertEquals("Weather from Open-Meteo for a new location fix · place found in 2300 ms · forecast in 410 ms",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.WEATHER, WeatherSource.FIX.id, 2300, 410)))
        assertEquals("Weather from Open-Meteo for your Home for weather · forecast in 380 ms",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.WEATHER, WeatherSource.HOME.id, 0, 380)))
        assertEquals("Weather lookup failed: rate limited (HTTP 429)", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.WEATHER_FAILED, WeatherFail.BUSY.id, 429)))
        assertTrue(VoiceEvent.WEATHER.id >= 80 && VoiceEvent.WEATHER_FAILED.id >= 80)
        for (c in listOf(WeatherSource.entries, WeatherFail.entries)) assertEquals(c.size, c.map { it.id }.toSet().size)
    }
}
