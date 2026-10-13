package app.heyrobot.helpers.anthropic

import app.heyrobot.helpers.HelperTurnListener
import app.heyrobot.helpers.HelperOutcome
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperEffortSetting
import app.heyrobot.core.AnthropicErrorKind
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.AnthropicStage
import app.heyrobot.core.HelperTools
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceLogEntry
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Web search answers as the API streams them (docs/ask-claude.md "Web search"), through Robot's real path against a local
 * mock server: dynamic filtering (web_search_20260209 runs Claude's code, and the searches that code makes arrive with
 * their input whole), citations, pauses inside the code, fallbacks, and what Robot doesn't know. "Tell me about Donald
 * Trump" on 0.3.1 said "Something went wrong with Claude.": the first answer that searched through Claude's code.
 */
class AnthropicWebSearchTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AnthropicClient
    private val logged = mutableListOf<String>()
    private val now = ZonedDateTime.of(2026, 10, 10, 14, 5, 0, 0, ZoneId.of("America/Toronto"))
    private val plan = HelperPlan.of(HelperEffortSetting.AUTO, voice = true, deep = false)

    @Before fun up() {
        server = MockWebServer()
        server.start()
        client = AnthropicClient.create("sk-ant-api03-not-a-real-key", server.url("/").toString().removeSuffix("/"))
        RLog.sink = { synchronized(logged) { logged += it } }
        // A voice log of this test's own: no other test's events in it.
        VoiceLog.resetForTests()
    }

    @After fun down() {
        RLog.sink = null
        client.close()
        server.shutdown()
    }

    // ------------------------------------------------------------------ the stream

    private fun sse(vararg events: Pair<String, String>): MockResponse = MockResponse()
        .setHeader("content-type", "text/event-stream")
        .setBody(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })

    private fun start(model: String = "claude-opus-5-5") = "message_start" to
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":900,"output_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}"""
    private fun blockStart(i: Int, block: String) = "content_block_start" to """{"type":"content_block_start","index":$i,"content_block":$block}"""
    private fun delta(i: Int, d: String) = "content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":$d}"""
    private fun textDelta(i: Int, t: String) = delta(i, """{"type":"text_delta","text":${JSONObject.quote(t)}}""")
    private fun jsonDelta(i: Int, j: String) = delta(i, """{"type":"input_json_delta","partial_json":${JSONObject.quote(j)}}""")
    private fun blockStop(i: Int) = "content_block_stop" to """{"type":"content_block_stop","index":$i}"""
    private fun end(reason: String, searches: Int = 1, details: String = "") = "message_delta" to
        """{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null$details},"usage":{"output_tokens":300,"server_tool_use":{"web_search_requests":$searches,"web_fetch_requests":0}}}"""
    private val stop = "message_stop" to """{"type":"message_stop"}"""
    private fun thinking(i: Int) = arrayOf(blockStart(i, """{"type":"thinking","thinking":"","signature":""}"""),
        delta(i, """{"type":"signature_delta","signature":"SIG$i"}"""), blockStop(i))
    private fun text(i: Int, vararg pieces: String) = arrayOf(blockStart(i, """{"type":"text","text":""}"""), *pieces.map { textDelta(i, it) }.toTypedArray(), blockStop(i))

    private val direct = """{"type":"direct"}"""
    private fun fromCode(id: String) = """{"type":"code_execution_20260120","tool_id":"$id"}"""
    private fun result(url: String) = """{"type":"web_search_result","url":"$url","title":"T","encrypted_content":"ENC","page_age":"October 9, 2026"}"""
    private fun citation(i: Int, url: String) =
        delta(i, """{"type":"citations_delta","citation":{"type":"web_search_result_location","cited_text":"Cited.","url":"$url","title":"T","encrypted_index":"IDX"}}""")

    /** Claude's code (dynamic filtering) runs a search: the code's call streams its input, the search it makes comes whole. */
    private fun codeSearch(i: Int, code: String, search: String, query: String, vararg urls: String) = arrayOf(
        blockStart(i, """{"type":"server_tool_use","id":"$code","name":"code_execution","input":{},"caller":$direct}"""),
        jsonDelta(i, """{"code":"results = await web_search({\"query\": \"$query\"})\nprint(results)"}"""), blockStop(i),
        blockStart(i + 1, """{"type":"server_tool_use","id":"$search","name":"web_search","input":{"query":"$query"},"caller":${fromCode(code)}}"""), blockStop(i + 1),
        blockStart(i + 2, """{"type":"web_search_tool_result","tool_use_id":"$search","caller":${fromCode(code)},"content":[${urls.joinToString(",") { result(it) }}]}"""), blockStop(i + 2),
    )

    private fun codeResult(i: Int, code: String) = arrayOf(
        blockStart(i, """{"type":"code_execution_tool_result","tool_use_id":"$code","content":{"type":"code_execution_result","stdout":"filtered","stderr":"","return_code":0,"content":[]}}"""),
        blockStop(i),
    )

    // ------------------------------------------------------------------ helpers

    private class Heard : HelperTurnListener {
        val text = StringBuilder()
        var declined = 0
        override fun text(delta: String) { text.append(delta) }
        override fun declined() { declined++ }
    }

    private fun session(model: AnthropicModel = AnthropicModel.OPUS, conversation: Boolean = true) =
        AnthropicSession(client, model, HelperTools.tools(RobotSettings()), search = true, conversation = conversation, idleMs = 10_000, retryDelayMs = 10)

    private fun ask(q: String) = HelperContext.oneShot(q, now, "CA")

    /** The next request Robot sent; it must be one the API takes (a request ending with Claude's turn continues a pause). */
    private fun take(): JSONObject {
        val b = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        val mapper = com.anthropic.core.jsonMapper()
        val ms = b.getJSONArray("messages")
        val messages = (0 until ms.length()).map { mapper.readValue(ms.getJSONObject(it).toString(), BetaMessageParam::class.java) }
        val paused = messages.last().role() == BetaMessageParam.Role.ASSISTANT
        assertNull(b.toString(), AnthropicHistory.check(messages, messages.map { AnthropicHistory.seal(AnthropicHistory.blocks(it)) }, paused))
        return b
    }

    private fun types(m: JSONObject): List<String> {
        val c = m.getJSONArray("content")
        return (0 until c.length()).map { c.getJSONObject(it).getString("type") }
    }

    private fun content(b: JSONObject, message: Int): JSONArray = b.getJSONArray("messages").getJSONObject(message).getJSONArray("content")

    /** The voice log's entries since [before] entries, oldest first. */
    /** What was logged since [before] (VoiceLog.total then): the ring may already be full from other tests. */
    private fun voice(before: Long): List<VoiceLogEntry> = VoiceLog.entries().take((VoiceLog.total - before).toInt()).asReversed()

    // ------------------------------------------------------------------ dynamic filtering

    /** The phone's failure: a search Claude's code made streams with its input whole, and the SDK's accumulator throws. */
    @Test fun theSdksOwnAccumulatorRefusesASearchWhoseInputCameWhole() {
        val mapper = com.anthropic.core.jsonMapper()
        val acc = com.anthropic.helpers.BetaMessageAccumulator.create()
        val events = listOf(start(), *codeSearch(0, "srvtoolu_c", "srvtoolu_s", "news", "https://example.com/a")).map { it.second }
        val thrown = try {
            for (e in events) acc.accumulate(mapper.readValue(e, BetaRawMessageStreamEvent::class.java))
            null
        } catch (t: Throwable) { t }
        // If a later SDK reads this, AnthropicReply can go (and this test with it).
        assertTrue(thrown.toString(), thrown is com.anthropic.errors.AnthropicInvalidDataException)
    }

    @Test fun aSearchThroughClaudesCodeIsAnswered() = runBlocking {
        server.enqueue(sse(start(), *thinking(0),
            *codeSearch(1, "srvtoolu_c1", "srvtoolu_s1", "Donald Trump news", "https://en.wikipedia.org/wiki/Donald_Trump", "https://apnews.com/x", "https://www.bbc.com/y"),
            *codeResult(4, "srvtoolu_c1"),
            blockStart(5, """{"type":"text","text":"","citations":[]}"""), citation(5, "https://apnews.com/x"), textDelta(5, "He is the president of the United States."), blockStop(5),
            end("end_turn"), stop))
        server.enqueue(sse(start(), *text(0, "Since January 2025."), end("end_turn", searches = 0), stop))
        val before = VoiceLog.total
        val s = session()
        val heard = Heard()
        assertEquals(HelperOutcome.Done(), s.ask(ask("tell me about Donald Trump"), plan, heard))
        assertEquals("He is the president of the United States.", heard.text.toString())
        // The page Claude cited first, then the rest of what the search found.
        assertEquals(listOf("apnews.com", "en.wikipedia.org", "bbc.com"), s.lastReport.domains)
        assertEquals(1, s.lastReport.searches)
        // The voice log says what was read with care: one search's input came whole, made from Claude's code.
        val read = voice(before).single { it.event == VoiceEvent.CLAUDE_READ }
        assertEquals(Triple(1, 1, 0), Triple(read.a, read.b, read.c))
        assertTrue(voice(before).none { it.event == VoiceEvent.CLAUDE_FAILED })
        // Claude mode: the next question carries the turn exactly as it came, which the API takes.
        assertEquals(HelperOutcome.Done(), s.ask(HelperContext.next("since when?"), plan, Heard()))
        take()
        val second = take()
        assertEquals(listOf("thinking", "server_tool_use", "server_tool_use", "web_search_tool_result", "code_execution_tool_result", "text"),
            types(second.getJSONArray("messages").getJSONObject(1)))
        val turn = content(second, 1)
        // The search keeps the input it came with, and who made it.
        assertEquals("Donald Trump news", turn.getJSONObject(2).getJSONObject("input").getString("query"))
        assertEquals("srvtoolu_c1", turn.getJSONObject(2).getJSONObject("caller").getString("tool_id"))
        assertEquals("srvtoolu_c1", turn.getJSONObject(3).getJSONObject("caller").getString("tool_id"))
        assertEquals("ENC", turn.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("encrypted_content"))
        assertTrue(turn.getJSONObject(1).getJSONObject("input").getString("code").contains("web_search"))
        // The cited text keeps its citation (its encrypted index must go back).
        val cited = turn.getJSONObject(5)
        assertEquals("He is the president of the United States.", cited.getString("text"))
        assertEquals("IDX", cited.getJSONArray("citations").getJSONObject(0).getString("encrypted_index"))
        assertNull(s.problem())
    }

    @Test fun citationsStayOnTheirTextAndNameTheSources() = runBlocking {
        // A direct search (web_search_20250305, Claude Haiku 5.5): the query streams in pieces; the answer cites twice.
        server.enqueue(sse(start("claude-haiku-5-5"), *text(0, "Let me check."),
            blockStart(1, """{"type":"server_tool_use","id":"srvtoolu_d","name":"web_search","input":{}}"""), jsonDelta(1, ""), jsonDelta(1, """{"query":"""), jsonDelta(1, """ "weather"}"""), blockStop(1),
            blockStart(2, """{"type":"web_search_tool_result","tool_use_id":"srvtoolu_d","content":[${result("https://weather.gc.ca/x")},${result("https://www.theweathernetwork.com/y")}]}"""), blockStop(2),
            blockStart(3, """{"type":"text","text":""}"""), citation(3, "https://www.theweathernetwork.com/y"), textDelta(3, "Sunny, "), citation(3, "https://weather.gc.ca/x"), textDelta(3, "then rain."), blockStop(3),
            end("end_turn"), stop))
        server.enqueue(sse(start("claude-haiku-5-5"), *text(0, "Yes."), end("end_turn", searches = 0), stop))
        val s = session(AnthropicModel.HAIKU)
        val heard = Heard()
        assertEquals(HelperOutcome.Done(), s.ask(ask("weather tomorrow?"), plan, heard))
        assertEquals("Let me check.Sunny, then rain.", heard.text.toString())
        assertEquals(listOf("theweathernetwork.com", "weather.gc.ca"), s.lastReport.domains)
        s.ask(HelperContext.next("sure?"), plan, Heard())
        take()
        val turn = content(take(), 1)
        assertEquals("""{"query": "weather"}""".replace(" ", ""), turn.getJSONObject(1).getJSONObject("input").toString())
        assertEquals("Sunny, then rain.", turn.getJSONObject(3).getString("text"))
        assertEquals(2, turn.getJSONObject(3).getJSONArray("citations").length())
    }

    @Test fun aPauseInsideClaudesCodeIsContinued() = runBlocking {
        // The server loop paused while Claude's code still ran: the code's call has no result yet.
        server.enqueue(sse(start(), *thinking(0), *codeSearch(1, "srvtoolu_c2", "srvtoolu_s2", "aurora", "https://www.swpc.noaa.gov/a"),
            end("pause_turn"), stop))
        server.enqueue(sse(start(), *codeResult(0, "srvtoolu_c2"), *text(1, "After midnight."), end("end_turn", searches = 0), stop))
        server.enqueue(sse(start(), *text(0, "Yes."), end("end_turn", searches = 0), stop))
        val s = session()
        val heard = Heard()
        assertEquals(HelperOutcome.Done(), s.ask(ask("aurora tonight?"), plan, heard))
        assertEquals("After midnight.", heard.text.toString())
        take()
        // The paused turn goes back as it is, its code still running; no extra user message.
        val continued = take()
        assertEquals(2, continued.getJSONArray("messages").length())
        assertEquals(listOf("thinking", "server_tool_use", "server_tool_use", "web_search_tool_result"), types(continued.getJSONArray("messages").getJSONObject(1)))
        s.ask(HelperContext.next("really?"), plan, Heard())
        assertEquals(listOf("thinking", "server_tool_use", "server_tool_use", "web_search_tool_result", "code_execution_tool_result", "text"),
            types(take().getJSONArray("messages").getJSONObject(1)))
        assertNull(s.problem())
    }

    @Test fun tooManyPausesInsideCodeEndWithoutTheUnfinishedCode() = runBlocking {
        repeat(AnthropicSession.MAX_CONTINUATIONS + 1) { n ->
            val said = if (n == 0) text(0, "Searching.").toList() else emptyList()
            val events = listOf(start()) + said + codeSearch(said.size.coerceAtMost(1), "srvtoolu_c$n", "srvtoolu_s$n", "q$n", "https://example.com/$n") + listOf(end("pause_turn"), stop)
            server.enqueue(sse(*events.toTypedArray()))
        }
        server.enqueue(sse(start(), *text(0, "Fine."), end("end_turn", searches = 0), stop))
        val s = session()
        assertEquals(HelperOutcome.Done(cut = true), s.ask(ask("search a lot"), plan, Heard()))
        // Each pause left its code unfinished: only the text stays, and the next request is one the API takes.
        assertNull(s.problem())
        s.ask(HelperContext.next("and?"), plan, Heard())
        repeat(AnthropicSession.MAX_CONTINUATIONS + 1) { take() }
        assertEquals(listOf("text"), types(take().getJSONArray("messages").getJSONObject(1)))
    }

    @Test fun serverCallsMadeWithRobotsCallsRunWhenTheResultsGoBack() = runBlocking {
        // Claude searches and sets a timer at once: the API returns before searching (stop_reason tool_use).
        server.enqueue(sse(start(), *text(0, "On it."),
            blockStart(1, """{"type":"server_tool_use","id":"srvtoolu_m","name":"web_search","input":{}}"""), jsonDelta(1, """{"query":"tea"}"""), blockStop(1),
            blockStart(2, """{"type":"tool_use","id":"toolu_m","name":"set_timer","input":{}}"""), jsonDelta(2, """{"minutes":4}"""), blockStop(2),
            end("tool_use", searches = 0), stop))
        // The next response opens with the search's result, then the answer.
        server.enqueue(sse(start(), blockStart(0, """{"type":"web_search_tool_result","tool_use_id":"srvtoolu_m","content":[${result("https://tea.example/steep")}]}"""), blockStop(0),
            *text(1, "Four minutes is right."), end("end_turn"), stop))
        server.enqueue(sse(start(), *text(0, "Yes."), end("end_turn", searches = 0), stop))
        val s = session()
        val calls = s.ask(ask("set a tea timer and check how long to steep"), plan, Heard()) as HelperOutcome.Calls
        assertEquals("set_timer", calls.calls.single().name)
        assertEquals(HelperOutcome.Done(), s.answer(listOf(mapOf("status" to "done")), Heard()))
        assertEquals(listOf("tea.example"), s.lastReport.domains)
        s.ask(HelperContext.next("thanks"), plan, Heard())
        take()
        assertEquals(listOf("tool_result"), types(take().getJSONArray("messages").getJSONObject(2)))
        val third = take()
        assertEquals(listOf("web_search_tool_result", "text"), types(third.getJSONArray("messages").getJSONObject(3)))
        assertNull(s.problem())
    }

    @Test fun aCallWhoseInputCameWholeStillRuns() = runBlocking {
        server.enqueue(sse(start(), blockStart(0, """{"type":"tool_use","id":"toolu_w","name":"set_timer","input":{"minutes":5}}"""), blockStop(0), end("tool_use", searches = 0), stop))
        val o = session().ask(ask("timer for 5 minutes"), plan, Heard()) as HelperOutcome.Calls
        assertEquals("5", o.calls.single().args["minutes"].toString())
    }

    // ------------------------------------------------------------------ refusals

    @Test fun aRefusalAfterAFallbackIsDeclinedAndLeavesNoTrace() = runBlocking {
        server.enqueue(sse(start(), *thinking(0), *text(1, "Here is"),
            blockStart(2, """{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-4-8"},"trigger":{"type":"refusal","category":"general_harms"}}"""), blockStop(2),
            end("refusal", searches = 0, details = ""","stop_details":{"type":"refusal","category":"general_harms","explanation":null}"""), stop))
        val s = session()
        val heard = Heard()
        assertEquals(HelperOutcome.Declined, s.ask(ask("something"), plan, heard))
        assertEquals(1, heard.declined)
        assertTrue(s.history.isEmpty())
    }

    // ------------------------------------------------------------------ what Robot doesn't know

    @Test fun blocksDeltasAndEventsRobotDoesNotKnowAreLeftOut() = runBlocking {
        server.enqueue(sse(start(), "ping" to """{"type":"ping"}""",
            blockStart(0, """{"type":"some_future_block","data":"x"}"""), delta(0, """{"type":"some_future_delta","x":1}"""), blockStop(0),
            "some_future_event" to """{"type":"some_future_event","x":1}""",
            blockStart(1, """{"type":"text","text":""}"""), textDelta(1, "Still "), delta(1, """{"type":"some_future_delta","x":2}"""), textDelta(1, "here."), blockStop(1),
            end("end_turn", searches = 0), stop))
        server.enqueue(sse(start(), *text(0, "Yes."), end("end_turn", searches = 0), stop))
        val before = VoiceLog.total
        val s = session()
        val heard = Heard()
        assertEquals(HelperOutcome.Done(), s.ask(ask("hi"), plan, heard))
        assertEquals("Still here.", heard.text.toString())
        assertEquals(1, voice(before).single { it.event == VoiceEvent.CLAUDE_READ }.c)
        s.ask(HelperContext.next("ok"), plan, Heard())
        take()
        // The unknown block never goes back.
        assertEquals(listOf("text"), types(take().getJSONArray("messages").getJSONObject(1)))
    }

    @Test fun anEventRobotCannotReadIsSkippedAndLoggedByClassOnly() = runBlocking {
        // A delta for a block that never started, between two good ones.
        server.enqueue(sse(start(), blockStart(0, """{"type":"text","text":""}"""), textDelta(0, "One, "), textDelta(7, "lost"), textDelta(0, "two."), blockStop(0),
            end("end_turn", searches = 0), stop))
        val heard = Heard()
        assertEquals(HelperOutcome.Done(), session().ask(ask("count"), plan, heard))
        assertTrue(heard.text.startsWith("One, "))
        assertTrue(heard.text.endsWith("two."))
        assertTrue(logged.toString(), logged.any { it == "claude_event_skipped event=content_block_delta error=IllegalStateException" })
    }

    // ------------------------------------------------------------------ "Something went wrong": where

    private fun unknownAt(): VoiceLogEntry = VoiceLog.entries().first { it.event == VoiceEvent.CLAUDE_UNKNOWN }

    @Test fun aStreamCutBeforeClaudeFinishedSaysWhere() = runBlocking {
        server.enqueue(sse(start(), blockStart(0, """{"type":"text","text":""}"""), textDelta(0, "Half")))
        assertEquals(HelperOutcome.Failed(HelperFailure.UNKNOWN), session().ask(ask("q"), plan, Heard()))
        assertEquals(AnthropicStage.CLOSED.id, unknownAt().a)
        assertTrue(VoiceLog.entries().first { it.event == VoiceEvent.CLAUDE_FAILED }.time <= unknownAt().time)
    }

    @Test fun anEventTheSdkCannotParseSaysWhere() = runBlocking {
        server.enqueue(sse(start(), "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"""))
        assertEquals(HelperOutcome.Failed(HelperFailure.UNKNOWN), session().ask(ask("q"), plan, Heard()))
        val e = unknownAt()
        assertEquals(AnthropicStage.SDK.id, e.a)
        assertEquals(0, e.b)
        assertTrue(e.c in listOf(AnthropicErrorKind.JSON.id, AnthropicErrorKind.INVALID_DATA.id, AnthropicErrorKind.SDK_OTHER.id))
        assertTrue(logged.any { it.startsWith("claude_request failure=UNKNOWN stage=SDK error=") })
    }

    @Test fun anHttpStatusRobotDoesNotKnowSaysWhichOne() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(418).setHeader("content-type", "application/json")
            .setBody("""{"type":"error","error":{"type":"teapot_error","message":"mock"}}"""))
        assertEquals(HelperOutcome.Failed(HelperFailure.UNKNOWN), session().ask(ask("q"), plan, Heard()))
        val e = unknownAt()
        assertEquals(Triple(AnthropicStage.HTTP.id, 418, AnthropicErrorKind.API_ERROR.id), Triple(e.a, e.b, e.c))
    }

    @Test fun aKnownFailureLogsNoSubCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("content-type", "application/json")
            .setBody("""{"type":"error","error":{"type":"rate_limit_error","message":"mock"}}"""))
        val before = VoiceLog.total
        assertEquals(HelperOutcome.Failed(HelperFailure.RATE_LIMITED), session().ask(ask("q"), plan, Heard()))
        assertTrue(voice(before).none { it.event == VoiceEvent.CLAUDE_UNKNOWN })
    }

    // ------------------------------------------------------------------ the history rules for server tools

    @Test fun theValidatorPairsEveryServerToolsResult() {
        val mapper = com.anthropic.core.jsonMapper()
        fun block(json: String) = mapper.readValue(json, BetaContentBlockParam::class.java)
        fun msg(role: BetaMessageParam.Role, vararg b: BetaContentBlockParam) = AnthropicHistory.message(role, b.toList())
        fun user(vararg b: BetaContentBlockParam) = msg(BetaMessageParam.Role.USER, *b)
        fun assistant(vararg b: BetaContentBlockParam) = msg(BetaMessageParam.Role.ASSISTANT, *b)
        fun check(vararg m: BetaMessageParam, paused: Boolean = false) = AnthropicHistory.check(m.toList(), m.map { AnthropicHistory.seal(AnthropicHistory.blocks(it)) }, paused)
        val q = AnthropicHistory.text("q")
        val code = block("""{"type":"server_tool_use","id":"srvtoolu_c","name":"code_execution","input":{"code":"x"},"caller":{"type":"direct"}}""")
        val search = block("""{"type":"server_tool_use","id":"srvtoolu_s","name":"web_search","input":{"query":"x"},"caller":{"type":"code_execution_20260120","tool_id":"srvtoolu_c"}}""")
        val found = block("""{"type":"web_search_tool_result","tool_use_id":"srvtoolu_s","content":[]}""")
        val ran = block("""{"type":"code_execution_tool_result","tool_use_id":"srvtoolu_c","content":{"type":"code_execution_result","stdout":"","stderr":"","return_code":0,"content":[]}}""")
        val bash = block("""{"type":"server_tool_use","id":"srvtoolu_b","name":"bash_code_execution","input":{"command":"ls"}}""")
        val bashRan = block("""{"type":"bash_code_execution_tool_result","tool_use_id":"srvtoolu_b","content":{"type":"bash_code_execution_result","stdout":"","stderr":"","return_code":0,"content":[]}}""")
        val a = AnthropicHistory.text("a")
        assertNull(check(user(q), assistant(code, search, found, ran, a), user(q)))
        assertNull(check(user(q), assistant(bash, bashRan, a), user(q)))
        assertEquals("server_tool_unpaired", check(user(q), assistant(code, search, found, a), user(q)))
        // A paused turn may end with its code still running; only as the paused turn sent back.
        assertNull(check(user(q), assistant(code, search, found), paused = true))
        assertEquals("server_tool_unpaired", check(user(q), assistant(code, search, found)))
        // A search made alongside Robot's call runs when the results go back, and its result opens Claude's next message.
        val timer = block("""{"type":"tool_use","id":"toolu_t","name":"set_timer","input":{"minutes":4}}""")
        val direct = block("""{"type":"server_tool_use","id":"srvtoolu_d","name":"web_search","input":{"query":"tea"}}""")
        val directFound = block("""{"type":"web_search_tool_result","tool_use_id":"srvtoolu_d","content":[]}""")
        val done = AnthropicRequests.results(listOf("toolu_t"), listOf(mapOf("status" to "done"))).single()
        assertNull(check(user(q), assistant(direct, timer), user(done)))
        assertNull(check(user(q), assistant(direct, timer), user(done), assistant(directFound, a), user(q)))
        assertEquals("server_tool_unpaired", check(user(q), assistant(direct, timer), user(done), assistant(a), user(q)))
        // Words after the results would end the turn with the search never run.
        assertEquals("server_tool_unpaired", check(user(q), assistant(direct, timer), user(done, q)))
        // A cut turn loses its unfinished code with the searches it made, and keeps the thinking as it came.
        val think = block("""{"type":"thinking","thinking":"","signature":"SIG"}""")
        assertEquals(listOf(think, a), AnthropicHistory.closed(listOf(think, a, code, search, found)))
        assertEquals(listOf(think, a, bash, bashRan), AnthropicHistory.closed(listOf(think, a, bash, bashRan, code, search)))
    }
}
