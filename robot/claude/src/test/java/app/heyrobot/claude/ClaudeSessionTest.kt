package app.heyrobot.claude

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudeContext
import app.heyrobot.core.ClaudeEffort
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudeModel
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ClaudeTools
import app.heyrobot.core.ClaudeWords
import app.heyrobot.core.Confirmations
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Shared
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnView
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * The API client against a local mock server through the SDK's base-URL override: no real key, no network. The server
 * plays the Messages API's server-sent events and error bodies; every request Robot sends is read back and checked.
 */
class ClaudeSessionTest {
    /** Looks like a real key so a leak would be found; it is not one. */
    private val key = "sk-ant-api03-ROBOTTESTKEY0123456789abcdefghijklmnopqrstuvwxyzQQ-not-a-real-key"
    private lateinit var server: MockWebServer
    private lateinit var client: ClaudeClient
    private val logged = mutableListOf<String>()
    private val now = ZonedDateTime.of(2026, 10, 10, 14, 5, 0, 0, ZoneId.of("America/Toronto"))

    @Before fun up() {
        server = MockWebServer()
        server.start()
        client = ClaudeClient.create(key, server.url("/").toString().removeSuffix("/"))
        RLog.sink = { synchronized(logged) { logged += it } }
    }

    @After fun down() {
        RLog.sink = null
        client.close()
        server.shutdown()
        // Nothing Robot logged in any of these runs carries the key or a piece of it.
        synchronized(logged) { for (l in logged) assertFalse(l, l.contains("sk-ant") || l.contains("ROBOTTESTKEY") || l.contains("not-a-real-key")) }
    }

    // ------------------------------------------------------------------ the mock API

    private fun sse(vararg events: Pair<String, String>): MockResponse = MockResponse()
        .setHeader("content-type", "text/event-stream")
        .setBody(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })

    private fun start(model: String = "claude-opus-5-5", input: Int = 120, cacheRead: Int = 0) =
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":$input,"output_tokens":1,"cache_read_input_tokens":$cacheRead,"cache_creation_input_tokens":0}}}"""

    private fun blockStart(i: Int, block: String) = "content_block_start" to """{"type":"content_block_start","index":$i,"content_block":$block}"""
    private fun textDelta(i: Int, t: String) = "content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":{"type":"text_delta","text":${JSONObject.quote(t)}}}"""
    private fun jsonDelta(i: Int, j: String) = "content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":${JSONObject.quote(j)}}}"""
    private fun signature(i: Int, s: String) = "content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":{"type":"signature_delta","signature":"$s"}}"""
    private fun blockStop(i: Int) = "content_block_stop" to """{"type":"content_block_stop","index":$i}"""
    private fun end(reason: String, out: Int = 20, searches: Int = 0, details: String = "") =
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null$details},"usage":{"output_tokens":$out,"server_tool_use":{"web_search_requests":$searches,"web_fetch_requests":0}}}"""
    private val stop = "message_stop" to """{"type":"message_stop"}"""

    private fun thinking(i: Int) = arrayOf(blockStart(i, """{"type":"thinking","thinking":"","signature":""}"""), signature(i, "SIG$i"), blockStop(i))

    private fun textReply(vararg pieces: String, reason: String = "end_turn") = sse(
        start(), *thinking(0), blockStart(1, """{"type":"text","text":""}"""), *pieces.map { textDelta(1, it) }.toTypedArray(), blockStop(1), end(reason), stop,
    )

    private fun error(code: Int, type: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json")
        .setBody("""{"type":"error","error":{"type":"$type","message":"mock"},"request_id":"req_mock"}""")

    private fun body(r: RecordedRequest) = JSONObject(r.body.readUtf8())

    // ------------------------------------------------------------------ helpers

    private class Heard : ClaudeListener {
        val text = StringBuilder()
        var declined = 0
        override fun text(delta: String) { text.append(delta) }
        override fun declined() { declined++ }
    }

    private fun session(model: ClaudeModel = ClaudeModel.OPUS, search: Boolean = true, conversation: Boolean = false, idleMs: Long = 10_000, retryDelayMs: Long = 10) =
        ClaudeSession(client, model, ClaudeTools.tools(RobotSettings()), search, conversation, idleMs = idleMs, retryDelayMs = retryDelayMs)

    /** Every request Robot sent must be one the API takes: the same rules the session checks before sending. */
    private fun valid(r: RecordedRequest): JSONObject {
        val b = JSONObject(r.body.readUtf8())
        val mapper = com.anthropic.core.jsonMapper()
        val ms = b.getJSONArray("messages")
        val messages = (0 until ms.length()).map { mapper.readValue(ms.getJSONObject(it).toString(), com.anthropic.models.beta.messages.BetaMessageParam::class.java) }
        // A request that ends with Claude's turn is a paused turn sent back to be continued.
        val paused = messages.last().role() == com.anthropic.models.beta.messages.BetaMessageParam.Role.ASSISTANT
        assertNull(b.toString(), ClaudeHistory.check(messages, messages.map { ClaudeHistory.seal(ClaudeHistory.blocks(it)) }, paused))
        return b
    }

    private fun take(): JSONObject = valid(server.takeRequest(5, TimeUnit.SECONDS)!!)

    private fun roles(b: JSONObject): String {
        val ms = b.getJSONArray("messages")
        return (0 until ms.length()).joinToString(" ") { i ->
            val m = ms.getJSONObject(i)
            val c = m.getJSONArray("content")
            m.getString("role") + "[" + (0 until c.length()).joinToString(",") { c.getJSONObject(it).getString("type") } + "]"
        }
    }

    private val voicePlan = ClaudePlan.of(app.heyrobot.core.ClaudeEffortSetting.AUTO, voice = true, deep = false)
    private fun ask(q: String) = ClaudeContext.oneShot(q, now, "CA")

    // ------------------------------------------------------------------ tests

    @Test fun theRequestIsWhatTheSpecSays() = runBlocking {
        server.enqueue(textReply("Rayleigh scattering."))
        val s = session()
        assertEquals(ClaudeOutcome.Done(), s.ask(ask("why is the sky blue"), voicePlan, Heard()))
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        // The beta endpoint (the fallback is a beta parameter).
        assertEquals("/v1/messages?beta=true", r.path)
        assertEquals(key, r.getHeader("x-api-key"))
        assertTrue(r.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val b = body(r)
        assertEquals("claude-opus-5-5", b.getString("model"))
        assertEquals(4_000, b.getInt("max_tokens"))
        assertTrue(b.getBoolean("stream"))
        assertEquals("low", b.getJSONObject("output_config").getString("effort"))
        // Thinking is never sent (always on with Opus 5.5; "disabled" is a 400).
        assertFalse(b.has("thinking"))
        assertEquals("default", b.getString("fallbacks"))
        assertEquals("auto", b.getJSONObject("tool_choice").getString("type"))
        // The system prompt: fixed, with its cache breakpoint; no date in it.
        val sys = b.getJSONArray("system").getJSONObject(0)
        assertEquals(app.heyrobot.core.ClaudePrompt.SYSTEM, sys.getString("text"))
        assertEquals("ephemeral", sys.getJSONObject("cache_control").getString("type"))
        // A one-shot question: no breakpoint on the last message.
        assertFalse(b.has("cache_control"))
        // Robot's tools in a fixed order with Gemma's schemas, then web search (max 3 uses).
        val tools = b.getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(ClaudeTools.OFFERED.map { it.id } + "web_search", names)
        val search = tools.getJSONObject(tools.length() - 1)
        assertEquals("web_search_20260209", search.getString("type"))
        assertEquals(3, search.getInt("max_uses"))
        val timer = tools.getJSONObject(0)
        assertEquals("integer", timer.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("minutes").getString("type"))
        assertFalse(timer.has("eager_input_streaming"))
        // Strict: the API only produces input that fits, and no property beyond the schema's.
        assertTrue(timer.getBoolean("strict"))
        assertFalse(timer.getJSONObject("input_schema").getBoolean("additionalProperties"))
        // The SDK's device description is replaced with neutral values (the kernel's build tag would identify the phone).
        assertEquals("unknown", r.getHeader("X-Stainless-OS-Version"))
        assertEquals("unknown", r.getHeader("X-Stainless-Arch"))
        assertEquals("Other", r.getHeader("X-Stainless-OS"))
        assertEquals("unknown", r.getHeader("X-Stainless-Runtime-Version"))
        assertTrue(r.headers.values("X-Stainless-OS-Version").none { it == System.getProperty("os.version") })
        // The question only, after the date and region.
        val content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("[Today is Saturday, 10 October 2026. The user is in Canada.]", content.getJSONObject(0).getString("text"))
        assertEquals("why is the sky blue", content.getJSONObject(1).getString("text"))
        assertEquals(2, content.length())
    }

    @Test fun haikuGoesWithoutTheFallbackAndSearchCanBeOff() = runBlocking {
        server.enqueue(textReply("Hi."))
        session(ClaudeModel.HAIKU, search = false).ask(ask("hi"), ClaudePlan(ClaudeEffort.HIGH, 16_000), Heard())
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(r.getHeader("anthropic-beta")?.takeIf { it.contains("server-side-fallback") })
        val b = body(r)
        assertFalse(b.has("fallbacks"))
        assertEquals("claude-haiku-5-5", b.getString("model"))
        assertEquals("high", b.getJSONObject("output_config").getString("effort"))
        assertEquals(16_000, b.getInt("max_tokens"))
        val tools = b.getJSONArray("tools")
        assertTrue((0 until tools.length()).none { tools.getJSONObject(it).optString("name") == "web_search" })
    }

    @Test fun theVoiceLogGetsCodesForClaudeRequests() = runBlocking {
        server.enqueue(textReply("Fine."))
        server.enqueue(error(429, "rate_limit_error"))
        val before = app.heyrobot.core.VoiceLog.entries().size
        session().ask(ask("why is the sky blue"), voicePlan, Heard())
        session().ask(ask("q"), voicePlan, Heard())
        val events = app.heyrobot.core.VoiceLog.entries().take(app.heyrobot.core.VoiceLog.entries().size - before).asReversed()
        assertEquals(listOf(app.heyrobot.core.VoiceEvent.CLAUDE_SENT, app.heyrobot.core.VoiceEvent.CLAUDE_FIRST, app.heyrobot.core.VoiceEvent.CLAUDE_DONE,
            app.heyrobot.core.VoiceEvent.CLAUDE_SENT, app.heyrobot.core.VoiceEvent.CLAUDE_FAILED), events.map { it.event })
        assertEquals(app.heyrobot.core.ClaudeEnd.END.id, events[2].b)
        assertEquals("Fine.".length, events[2].c)
        assertEquals(app.heyrobot.core.ClaudeFail.RATE_LIMITED.id, events[4].a)
        assertEquals("Claude failed: rate limited", app.heyrobot.core.VoiceLogText.describe(events[4]))
    }

    @Test fun haikuSearchesWithTheBasicWebSearchTool() = runBlocking {
        server.enqueue(textReply("Hi."))
        server.enqueue(textReply("Hi."))
        session(ClaudeModel.HAIKU).ask(ask("hi"), voicePlan, Heard())
        session(ClaudeModel.SONNET).ask(ask("hi"), voicePlan, Heard())
        fun search(b: JSONObject): JSONObject { val t = b.getJSONArray("tools"); return t.getJSONObject(t.length() - 1) }
        val haiku = search(take())
        assertEquals("web_search_20250305", haiku.getString("type"))
        assertEquals(3, haiku.getInt("max_uses"))
        assertEquals("web_search_20260209", search(take()).getString("type"))
    }

    @Test fun screenTextAndPicturesGoOnlyWhenShared() = runBlocking {
        server.enqueue(textReply("Fine."))
        server.enqueue(textReply("Fine."))
        val s = session()
        s.ask(ClaudeContext.oneShot("what does it say", now, "CA"), voicePlan, Heard())
        val plain = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertFalse(plain.contains("Meeting at 3") || plain.contains("\"image\""))
        session().ask(ClaudeContext.oneShot("what does it say", now, "CA", Shared(screenText = "Meeting at 3", image = byteArrayOf(-1, -40, -1))), voicePlan, Heard())
        val b = body(server.takeRequest(5, TimeUnit.SECONDS)!!)
        val content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val types = (0 until content.length()).map { content.getJSONObject(it).getString("type") }
        assertEquals(listOf("text", "text", "image", "text"), types)
        assertTrue(content.getJSONObject(1).getString("text").contains("Meeting at 3"))
        assertEquals("image/jpeg", content.getJSONObject(2).getJSONObject("source").getString("media_type"))
    }

    /** The stream to the sentence splitter, with the event stream the API sends (split mid-word, as it does). */
    @Test fun streamedTextFeedsTheSentenceSplitter() = runBlocking {
        server.enqueue(textReply("The sky is bl", "ue because air scatters, mostly, the sh", "ort blue waves. At sun", "set the light travels further. ", "So reds remain."))
        val voice = ReplyVoice()
        voice.start("r1")
        val spoken = mutableListOf<String>()
        val heard = object : ClaudeListener {
            override fun text(delta: String) { spoken += voice.feed("r1", delta, speak = true) }
            override fun declined() {}
        }
        session().ask(ask("why is the sky blue"), voicePlan, heard)
        voice.finish("r1", cancelled = false, speak = true)?.let { spoken += it }
        assertEquals(listOf("The sky is blue because air scatters,", "mostly, the short blue waves.", "At sunset the light travels further.", "So reds remain."), spoken)
    }

    private class AutoView(private val conf: Confirmations) : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) {
            cards[c.id] = c
            if (c.status == ActionStatus.PENDING) pending.trySend(c.id)
        }
        override var text: String = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private class Actions : TurnActions {
        val ran = mutableListOf<Action>()
        override val locked = false
        override val settings = RobotSettings()
        override suspend fun prepare(action: Action) = Prepared.Ready(action)
        override suspend fun run(action: Action): Executed { ran += action; return Executed(ActionStatus.DONE, "Timer set for 10 minutes.") }
    }

    @Test fun aToolCallMakesACardAndItsResultGoesBack() = runBlocking {
        server.enqueue(sse(start(), *thinking(0), blockStart(1, """{"type":"text","text":""}"""), textDelta(1, "Setting it."), blockStop(1),
            blockStart(2, """{"type":"tool_use","id":"toolu_01","name":"set_timer","input":{}}"""), jsonDelta(2, """{"minutes": """), jsonDelta(2, "10}"), blockStop(2),
            end("tool_use"), stop))
        server.enqueue(textReply("Your timer is running."))
        val s = session()
        val heard = Heard()
        val conf = Confirmations()
        val view = AutoView(conf)
        val actions = Actions()
        val turn = async { TurnRunner(ClaudeTurn(s, ask("set a timer for 10 minutes"), voicePlan, heard), actions, view, conf, untrusted = true, remote = true) { "c${view.cards.size}" }.run() }
        // A card, always: nothing runs before the tap, even a timer.
        val id = withTimeout(10_000) { view.pending.receive() }
        assertTrue(actions.ran.isEmpty())
        assertTrue(view.cards[id]!!.remote)
        conf.answer(id, true)
        assertEquals(TurnResult(DoneReason.END), turn.await())
        assertEquals(listOf<Action>(Action.SetTimer(600)), actions.ran)
        assertEquals("Setting it.Your timer is running.", heard.text.toString())
        server.takeRequest(5, TimeUnit.SECONDS)
        val second = body(server.takeRequest(5, TimeUnit.SECONDS)!!).getJSONArray("messages")
        assertEquals(3, second.length())
        // Claude's turn goes back exactly as it came: the thinking block with its signature, the text, the call.
        val assistant = second.getJSONObject(1).getJSONArray("content")
        assertEquals("thinking", assistant.getJSONObject(0).getString("type"))
        assertEquals("SIG0", assistant.getJSONObject(0).getString("signature"))
        assertEquals("tool_use", assistant.getJSONObject(2).getString("type"))
        assertEquals(10, assistant.getJSONObject(2).getJSONObject("input").getInt("minutes"))
        // The results: one user message, a tool_result per call, Robot's fixed-vocabulary response.
        val results = second.getJSONObject(2)
        assertEquals("user", results.getString("role"))
        val r = results.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", r.getString("type"))
        assertEquals("toolu_01", r.getString("tool_use_id"))
        assertFalse(r.optBoolean("is_error", false))
        assertEquals("done", JSONObject(r.getString("content")).getString("status"))
    }

    @Test fun aFailedOrRefusedCallIsAnErrorResult() = runBlocking {
        server.enqueue(sse(start(), blockStart(0, """{"type":"tool_use","id":"toolu_02","name":"set_timer","input":{}}"""), jsonDelta(0, """{"minutes":"ten"}"""), blockStop(0), end("tool_use"), stop))
        server.enqueue(textReply("Sorry."))
        val s = session()
        val conf = Confirmations()
        val view = AutoView(conf)
        TurnRunner(ClaudeTurn(s, ask("timer"), voicePlan, Heard()), Actions(), view, conf, untrusted = true, remote = true) { "x" }.run()
        server.takeRequest(5, TimeUnit.SECONDS)
        val r = body(server.takeRequest(5, TimeUnit.SECONDS)!!).getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        // The input didn't fit the schema: nothing ran, Claude hears why.
        assertTrue(r.getBoolean("is_error"))
        assertEquals("invalid_arguments", JSONObject(r.getString("content")).getString("status"))
        assertEquals(ActionStatus.INVALID, view.cards.values.single().status)
    }

    @Test fun pauseTurnIsContinuedWithoutAnExtraUserMessage() = runBlocking {
        server.enqueue(sse(start(), blockStart(0, """{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}"""),
            jsonDelta(0, """{"query":"aurora tonight"}"""), blockStop(0), end("pause_turn", searches = 1), stop))
        server.enqueue(sse(start(cacheRead = 900), blockStart(0, """{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[""" +
            """{"type":"web_search_result","url":"https://www.spaceweather.gov/aurora","title":"Aurora","encrypted_content":"x","page_age":null},""" +
            """{"type":"web_search_result","url":"https://en.wikipedia.org/wiki/Aurora","title":"Aurora","encrypted_content":"y","page_age":null}]}"""),
            blockStop(0), blockStart(1, """{"type":"text","text":""}"""), textDelta(1, "Maybe after midnight."), blockStop(1), end("end_turn"), stop))
        val s = session()
        val heard = Heard()
        assertEquals(ClaudeOutcome.Done(), s.ask(ask("will I see the aurora tonight"), voicePlan, heard))
        assertEquals("Maybe after midnight.", heard.text.toString())
        server.takeRequest(5, TimeUnit.SECONDS)
        val second = body(server.takeRequest(5, TimeUnit.SECONDS)!!).getJSONArray("messages")
        // The paused turn goes back as it is and the server resumes: no "Continue." from Robot.
        assertEquals(2, second.length())
        assertEquals("assistant", second.getJSONObject(1).getString("role"))
        assertEquals("server_tool_use", second.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        val report = s.lastReport
        assertEquals(listOf("spaceweather.gov", "en.wikipedia.org"), report.domains)
        assertEquals(2, report.requests)
        assertEquals(900, report.usage.cacheRead)
        assertTrue(logged.any { it.startsWith("claude_usage") && it.contains("cache_read_input_tokens=900") })
    }

    @Test fun aWebSearchErrorObjectDoesNotBreakTheAnswer() = runBlocking {
        server.enqueue(sse(start(), blockStart(0, """{"type":"server_tool_use","id":"srvtoolu_2","name":"web_search","input":{}}"""), jsonDelta(0, """{"query":"x"}"""), blockStop(0),
            blockStart(1, """{"type":"web_search_tool_result","tool_use_id":"srvtoolu_2","content":{"type":"web_search_tool_result_error","error_code":"unavailable"}}"""), blockStop(1),
            blockStart(2, """{"type":"text","text":""}"""), textDelta(2, "I couldn't search just now, but here is what I know."), blockStop(2), end("end_turn", searches = 1), stop))
        val s = session()
        val heard = Heard()
        assertEquals(ClaudeOutcome.Done(), s.ask(ask("latest news"), voicePlan, heard))
        assertEquals("I couldn't search just now, but here is what I know.", heard.text.toString())
        assertEquals(1, s.lastReport.searchErrors)
        assertTrue(s.lastReport.domains.isEmpty())
        assertTrue(logged.any { it == "claude_search_error code=unavailable" })
    }

    @Test fun aRefusalIsDeclinedAndLeavesNoTrace() = runBlocking {
        server.enqueue(sse(start(), blockStart(0, """{"type":"text","text":""}"""), textDelta(0, "Partial"), blockStop(0),
            end("refusal", details = ""","stop_details":{"type":"refusal","category":"cyber","explanation":null}"""), stop))
        val s = session(conversation = true)
        val heard = Heard()
        assertEquals(ClaudeOutcome.Declined, s.ask(ask("something"), voicePlan, heard))
        assertEquals(1, heard.declined)
        assertTrue(s.history.isEmpty())
        assertEquals("Claude declined that one.", ClaudeWords.DECLINED)
        // As a turn: done, with the panel's words in place of the partial.
        server.enqueue(sse(start(), end("refusal"), stop))
        val conf = Confirmations()
        val view = AutoView(conf)
        val declining = object : ClaudeListener {
            override fun text(delta: String) { view.text += delta }
            override fun declined() { view.replaceText(ClaudeWords.DECLINED) }
        }
        assertEquals(TurnResult(DoneReason.END), TurnRunner(ClaudeTurn(s, ask("x"), voicePlan, declining), Actions(), view, conf, untrusted = true, remote = true) { "y" }.run())
        assertEquals("Claude declined that one.", view.text)
    }

    private fun failure(vararg responses: MockResponse): Pair<ClaudeOutcome, Int> = runBlocking {
        responses.forEach(server::enqueue)
        val before = server.requestCount
        val o = session().ask(ask("q"), voicePlan, Heard())
        o to server.requestCount - before
    }

    @Test fun errorsAreTypedAndMapped() {
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.KEY_REFUSED) to 1, failure(error(401, "authentication_error")))
        assertEquals("Your Claude key was refused. Check it in Robot's settings.", ClaudeFailure.KEY_REFUSED.text)
        // A rate limit is never retried: "Claude is busy, try again in a minute".
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.RATE_LIMITED) to 1, failure(error(429, "rate_limit_error")))
        // Overloaded (529) or a 5xx: retried once, then said.
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.OVERLOADED) to 2, failure(error(529, "overloaded_error"), error(529, "overloaded_error")))
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.SERVER) to 2, failure(error(500, "api_error"), error(503, "api_error")))
        assertEquals(ClaudeOutcome.Done() to 2, failure(error(500, "api_error"), textReply("Fine now.")))
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.NO_CREDITS) to 1, failure(error(402, "billing_error")))
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.MODEL_UNAVAILABLE) to 1, failure(error(404, "not_found_error")))
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.BAD_REQUEST) to 1, failure(error(400, "invalid_request_error")))
    }

    @Test fun anErrorEventMidStream() {
        val midStream = sse(start(), "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        val (o, n) = failure(midStream, midStream)
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.OVERLOADED), o)
        assertEquals(2, n)
        // After text was shown it is not retried (it would be said twice).
        server.enqueue(sse(start(), blockStart(0, """{"type":"text","text":""}"""), textDelta(0, "Half an ans"),
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        val before = server.requestCount
        val heard = Heard()
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.OVERLOADED), runBlocking { session().ask(ask("q"), voicePlan, heard) })
        assertEquals(1, server.requestCount - before)
        assertEquals("Half an ans", heard.text.toString())
    }

    @Test fun noInternet() = runBlocking {
        server.shutdown()
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.NO_INTERNET), session().ask(ask("q"), voicePlan, Heard()))
        // GrapheneOS may show a revoked Network permission as no network at all: the words say where to look.
        assertTrue(ClaudeFailure.NO_INTERNET.text.contains("Network permission"))
    }

    @Test fun sixtySecondsOfSilenceIsATimeout() = runBlocking {
        // Headers, then nothing for longer than the limit (shortened for the test).
        server.enqueue(textReply("Late.").setBodyDelay(2, TimeUnit.SECONDS))
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.TIMEOUT), session(idleMs = 300).ask(ask("q"), voicePlan, Heard()))
        assertEquals("Claude took too long to answer.", ClaudeFailure.TIMEOUT.text)
    }

    /**
     * A one-connection server that writes [events] with [gapMs] between them (MockWebServer's throttle also slows the
     * reading of Robot's 5 KB request, so it can't show this).
     */
    private fun slowServer(events: List<String>, gapMs: Long): java.net.ServerSocket = slowServer(events.map { it to gapMs })

    /** The same, each event followed by its own pause. */
    private fun slowServer(events: List<Pair<String, Long>>): java.net.ServerSocket {
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                ss.accept().use { sock ->
                    val input = java.io.BufferedInputStream(sock.getInputStream())
                    fun line(): String {
                        val b = StringBuilder()
                        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }
                        return b.toString()
                    }
                    var length = -1
                    var chunked = false
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toInt()
                        if (h.lowercase().startsWith("transfer-encoding:") && h.lowercase().contains("chunked")) chunked = true
                    }
                    if (chunked) {
                        while (true) { val n = line().trim().toInt(16); if (n == 0) { line(); break }; repeat(n) { input.read() }; line() }
                    } else repeat(length.coerceAtLeast(0)) { input.read() }
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    for ((e, gap) in events) { out.write(e.toByteArray()); out.flush(); Thread.sleep(gap) }
                }
            } catch (_: Exception) {}
        }.start()
        return ss
    }

    @Test fun noLimitOnceTheAnswerFlows() = runBlocking {
        // Warm the SDK's classes first (a cold JVM spends most of a short window loading them).
        server.enqueue(textReply("Warm."))
        session().ask(ask("q"), voicePlan, Heard())
        // Once text flows, the answer may take far longer than the first-token limit: no limit while it keeps arriving.
        val pieces = (1..12).map { "Piece $it. " }
        val events = listOf(start(), blockStart(0, """{"type":"text","text":""}""")) + pieces.map { textDelta(0, it) } + listOf(blockStop(0), end("end_turn"), stop)
        val slow = slowServer(events.map { (n, d) -> "event: $n\ndata: $d\n\n" }, gapMs = 120)
        ClaudeClient.create(key, "http://127.0.0.1:${slow.localPort}").use { c ->
            val heard = Heard()
            val t0 = System.currentTimeMillis()
            val s = ClaudeSession(c, ClaudeModel.OPUS, ClaudeTools.tools(RobotSettings()), search = true, conversation = false, idleMs = 600)
            assertEquals(ClaudeOutcome.Done(), s.ask(ask("q"), voicePlan, heard))
            assertTrue(System.currentTimeMillis() - t0 > 1_200)
            assertEquals(pieces.joinToString(""), heard.text.toString())
        }
        slow.close()
    }

    @Test fun stopCancelsAtOnceAndLeavesNoTrace() = runBlocking {
        server.enqueue(textReply("Never heard.").setBodyDelay(5, TimeUnit.SECONDS))
        val s = session(conversation = true)
        val job = async { s.ask(ask("q"), voicePlan, Heard()) }
        delay(300)
        val t0 = System.currentTimeMillis()
        job.cancel()
        job.join()
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
        assertTrue(s.history.isEmpty())
    }

    @Test fun claudeModeIsAppendOnlyAndCachesTheLastMessage() = runBlocking {
        server.enqueue(textReply("First answer."))
        server.enqueue(textReply("Second answer."))
        val s = session(conversation = true)
        val history = listOf(ChatTurn("1", TurnRole.USER, "tell me a joke", 0), ChatTurn("2", TurnRole.ASSISTANT, "Why did the robot cross the road?", 0))
        s.ask(ClaudeContext.join(history, "make it funnier", now, "CA"), voicePlan, Heard())
        s.ask(ClaudeContext.next("and shorter"), voicePlan, Heard())
        val first = body(server.takeRequest(5, TimeUnit.SECONDS)!!)
        val second = body(server.takeRequest(5, TimeUnit.SECONDS)!!)
        // The request's automatic breakpoint follows the last message.
        assertEquals("ephemeral", second.getJSONObject("cache_control").getString("type"))
        val m1 = first.getJSONArray("messages")
        val m2 = second.getJSONArray("messages")
        assertTrue(m1.getJSONObject(0).toString().contains("Why did the robot cross the road?"))
        assertEquals(3, m2.length())
        // The earlier messages are byte for byte what was sent before (the prefix the cache and the thinking need).
        assertEquals(m1.getJSONObject(0).toString(), m2.getJSONObject(0).toString())
        assertEquals("SIG0", m2.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"))
        assertEquals("and shorter", m2.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
        // Tools and system unchanged between the two requests.
        assertEquals(first.getJSONArray("tools").toString(), second.getJSONArray("tools").toString())
        assertEquals(first.getJSONArray("system").toString(), second.getJSONArray("system").toString())
    }

    @Test fun aStoppedCardTurnIsRolledBackBeforeTheNextQuestion() = runBlocking {
        server.enqueue(sse(start(), blockStart(0, """{"type":"tool_use","id":"toolu_09","name":"set_timer","input":{}}"""), jsonDelta(0, """{"minutes":5}"""), blockStop(0), end("tool_use"), stop))
        server.enqueue(textReply("Hello again."))
        val s = session(conversation = true)
        assertTrue(s.ask(ask("timer"), voicePlan, Heard()) is ClaudeOutcome.Calls)
        // Stop while the card waited: no results are ever sent. The next question starts clean (no dangling call).
        s.ask(ClaudeContext.next("never mind, hello"), voicePlan, Heard())
        server.takeRequest(5, TimeUnit.SECONDS)
        val m = body(server.takeRequest(5, TimeUnit.SECONDS)!!).getJSONArray("messages")
        assertEquals(1, m.length())
        assertEquals("never mind, hello", m.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun warmingUpNeedsNoNetwork() {
        server.shutdown()
        ClaudeClient.warm()
        assertEquals(0, server.requestCount)
    }

    @Test fun costOfATurn() = runBlocking {
        server.enqueue(sse(start(input = 1_000, cacheRead = 2_000), blockStart(0, """{"type":"text","text":""}"""), textDelta(0, "x"), blockStop(0), end("end_turn", out = 300), stop))
        val s = session()
        s.ask(ask("q"), voicePlan, Heard())
        // Opus 5.5: 1,000 in at $4, 300 out at $20, 2,000 cache reads at $0.20 (per million).
        assertEquals(0.004 + 0.006 + 0.0004, s.lastReport.dollars, 1e-9)
        assertEquals("≈ 1¢", app.heyrobot.core.ClaudeCost.line(s.lastReport.dollars))
    }

    @Test fun theKeyIsNeverLoggedOrStored() = runBlocking {
        // Every path that logs: success, retries, errors, a search error.
        server.enqueue(error(529, "overloaded_error"))
        server.enqueue(textReply("ok"))
        server.enqueue(error(401, "authentication_error"))
        server.enqueue(sse(start(), "error" to """{"type":"error","error":{"type":"api_error","message":"x"}}"""))
        server.enqueue(sse(start(), "error" to """{"type":"error","error":{"type":"api_error","message":"x"}}"""))
        val s = session()
        val heard = Heard()
        s.ask(ask("q"), voicePlan, heard)
        s.ask(ask("q"), voicePlan, heard)
        s.ask(ask("q"), voicePlan, heard)
        assertTrue(logged.isNotEmpty())
        // What history would store for these turns carries no key either.
        val c = StoredConversation("c", 0, 0, listOf(ChatTurn("u", TurnRole.USER, "q", 0), ChatTurn("a", TurnRole.ASSISTANT, heard.text.toString(), 0, claude = true)))
        assertFalse(ConversationCodec.toJson(c).contains(key))
        // (The log check itself runs after every test, in down().)
    }

    // ------------------------------------------------------------------ the history stays one the API takes

    private fun toolUse(id: String, name: String = "send_text", json: String = """{"to":"Mom","message":"Running late"}""") = sse(start(),
        blockStart(0, """{"type":"tool_use","id":"$id","name":"$name","input":{}}"""), jsonDelta(0, json), blockStop(0), end("tool_use"), stop)

    @Test fun resultsOwedFromAnAppLaunchSurviveAFailedQuestion() = runBlocking {
        // A card opened Cheogram (the turn ended without a reply); the next question fails; the one after must still
        // carry the result, or every later request would be a 400.
        server.enqueue(toolUse("toolu_t"))
        server.enqueue(error(429, "rate_limit_error"))
        server.enqueue(textReply("Sure."))
        val s = session(conversation = true)
        assertTrue(s.ask(ask("text Mom I'm running late"), voicePlan, Heard()) is ClaudeOutcome.Calls)
        s.abandon(listOf(mapOf("status" to "draft_opened", "detail" to "Opened in Cheogram.")))
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.RATE_LIMITED), s.ask(ClaudeContext.next("and remind me at five"), voicePlan, Heard()))
        assertNull(s.problem())
        assertEquals(ClaudeOutcome.Done(), s.ask(ClaudeContext.next("and remind me at five"), voicePlan, Heard()))
        take()
        assertEquals("user[text,text] assistant[tool_use] user[tool_result,text]", roles(take()))
        val third = take()
        assertEquals("user[text,text] assistant[tool_use] user[tool_result,text]", roles(third))
        // The launched call's real result, not "not done".
        val r = third.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("draft_opened", JSONObject(r.getString("content")).getString("status"))
        assertNull(s.problem())
    }

    @Test fun anAppLaunchReportsWhatRanAndWhatDidNot() = runBlocking {
        server.enqueue(sse(start(),
            blockStart(0, """{"type":"tool_use","id":"toolu_a","name":"send_text","input":{}}"""), jsonDelta(0, """{"to":"Mom","message":"Late"}"""), blockStop(0),
            blockStart(1, """{"type":"tool_use","id":"toolu_b","name":"set_timer","input":{}}"""), jsonDelta(1, """{"minutes":5}"""), blockStop(1),
            end("tool_use"), stop))
        server.enqueue(textReply("Done."))
        val s = session(conversation = true)
        s.ask(ask("text Mom and set a timer"), voicePlan, Heard())
        s.abandon(listOf(mapOf("status" to "draft_opened")))
        s.ask(ClaudeContext.next("thanks"), voicePlan, Heard())
        take()
        val c = take().getJSONArray("messages").getJSONObject(2).getJSONArray("content")
        assertEquals("draft_opened", JSONObject(c.getJSONObject(0).getString("content")).getString("status"))
        assertEquals("not_done", JSONObject(c.getJSONObject(1).getString("content")).getString("status"))
        assertTrue(c.getJSONObject(1).getBoolean("is_error"))
        assertEquals("text", c.getJSONObject(2).getString("type"))
    }

    @Test fun aToolRoundThatRanSurvivesAFailedFollowUp() = runBlocking {
        server.enqueue(toolUse("toolu_x", "set_timer", """{"minutes":10}"""))
        server.enqueue(error(500, "api_error"))
        server.enqueue(error(500, "api_error"))
        server.enqueue(textReply("Your timer is set."))
        val s = session(conversation = true)
        assertTrue(s.ask(ask("set a timer"), voicePlan, Heard()) is ClaudeOutcome.Calls)
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.SERVER), s.answer(listOf(mapOf("status" to "done")), Heard()))
        // Claude learns that the timer was set: the round stays, and the next question joins its results.
        s.ask(ClaudeContext.next("is it running?"), voicePlan, Heard())
        repeat(3) { take() }
        assertEquals("user[text,text] assistant[tool_use] user[tool_result,text]", roles(take()))
    }

    @Test fun aStopDuringTheRetryPauseLeavesNoTrace() = runBlocking {
        server.enqueue(error(529, "overloaded_error"))
        val s = session(conversation = true, retryDelayMs = 5_000)
        val job = async { s.ask(ask("q"), voicePlan, Heard()) }
        withTimeout(5_000) { while (server.requestCount < 1) delay(20) }
        delay(100)
        job.cancel()
        job.join()
        assertTrue(s.history.isEmpty())
    }

    @Test fun aPausedSearchContinuesInTheSameAssistantMessage() = runBlocking {
        server.enqueue(sse(start(), *thinking(0), blockStart(1, """{"type":"server_tool_use","id":"srvtoolu_p","name":"web_search","input":{}}"""),
            jsonDelta(1, """{"query":"aurora"}"""), blockStop(1), end("pause_turn", searches = 1), stop))
        server.enqueue(sse(start(), blockStart(0, """{"type":"web_search_tool_result","tool_use_id":"srvtoolu_p","content":[]}"""), blockStop(0),
            blockStart(1, """{"type":"text","text":""}"""), textDelta(1, "After midnight."), blockStop(1), end("end_turn"), stop))
        server.enqueue(textReply("Yes."))
        val s = session(conversation = true)
        assertEquals(ClaudeOutcome.Done(), s.ask(ask("aurora tonight?"), voicePlan, Heard()))
        s.ask(ClaudeContext.next("really?"), voicePlan, Heard())
        assertEquals("user[text,text]", roles(take()))
        // The continuation: the paused turn as it came, no "Continue." from Robot.
        assertEquals("user[text,text] assistant[thinking,server_tool_use]", roles(take()))
        // One assistant message: the paused turn and its continuation, the thinking block as it came.
        val third = take()
        assertEquals("user[text,text] assistant[thinking,server_tool_use,web_search_tool_result,text] user[text]", roles(third))
        assertEquals("SIG0", third.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"))
        assertNull(s.problem())
    }

    @Test fun tooManyPausesEndWithoutAnUnansweredSearch() = runBlocking {
        // Each continuation brings the last search's results, then pauses on a new search.
        fun paused(n: Int) = if (n == 0) sse(start(), blockStart(0, """{"type":"server_tool_use","id":"srvtoolu_0","name":"web_search","input":{}}"""),
            jsonDelta(0, """{"query":"q0"}"""), blockStop(0), end("pause_turn", searches = 1), stop)
        else sse(start(), blockStart(0, """{"type":"web_search_tool_result","tool_use_id":"srvtoolu_${n - 1}","content":[]}"""), blockStop(0),
            blockStart(1, """{"type":"server_tool_use","id":"srvtoolu_$n","name":"web_search","input":{}}"""),
            jsonDelta(1, """{"query":"q$n"}"""), blockStop(1), end("pause_turn", searches = 1), stop)
        repeat(ClaudeSession.MAX_CONTINUATIONS + 1) { server.enqueue(paused(it)) }
        server.enqueue(textReply("Fine."))
        val s = session(conversation = true)
        assertEquals(ClaudeOutcome.Done(cut = true), s.ask(ask("search a lot"), voicePlan, Heard()))
        assertNull(s.problem())
        s.ask(ClaudeContext.next("and?"), voicePlan, Heard())
        repeat(ClaudeSession.MAX_CONTINUATIONS + 1) { take() }
        // The dangling call at the end is gone; the next request is one the API takes.
        val last = take()
        val assistant = last.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue((0 until assistant.length()).none { assistant.getJSONObject(it).getString("type") == "server_tool_use" &&
            assistant.getJSONObject(it).getString("id") == "srvtoolu_${ClaudeSession.MAX_CONTINUATIONS}" })
    }

    @Test fun aMidAnswerFallbackDropsTheDeclinedAttemptsBlocksAndCalls() = runBlocking {
        // Opus 5.5 declined mid-answer (after thinking, some text and the start of a call); the fallback model went on.
        server.enqueue(sse(start(), *thinking(0),
            blockStart(1, """{"type":"text","text":""}"""), textDelta(1, "Let me "), blockStop(1),
            blockStart(2, """{"type":"tool_use","id":"toolu_declined","name":"set_timer","input":{}}"""), jsonDelta(2, """{"minutes":1}"""), blockStop(2),
            blockStart(3, """{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-4-8"},"trigger":{"type":"refusal","category":"cyber"}}"""), blockStop(3),
            blockStart(4, """{"type":"text","text":""}"""), textDelta(4, "set that."), blockStop(4),
            blockStart(5, """{"type":"tool_use","id":"toolu_kept","name":"set_timer","input":{}}"""), jsonDelta(5, """{"minutes":5}"""), blockStop(5),
            end("tool_use"), stop))
        server.enqueue(textReply("Set."))
        val s = session(conversation = true)
        val o = s.ask(ask("set a timer"), voicePlan, Heard()) as ClaudeOutcome.Calls
        // Only the call made after the switch: no card for the declined attempt's.
        assertEquals(1, o.calls.size)
        assertEquals("5", o.calls.single().args["minutes"].toString())
        s.answer(listOf(mapOf("status" to "done")), Heard())
        take()
        val second = take()
        // Echoed back: the text before and after the switch, the kept call; no thinking, declined call or fallback marker.
        assertEquals("user[text,text] assistant[text,text,tool_use] user[tool_result]", roles(second))
        val tu = second.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(2)
        assertEquals("toolu_kept", tu.getString("id"))
    }

    @Test fun theValidatorCatchesWhatTheApiRefuses() {
        fun user(vararg b: com.anthropic.models.beta.messages.BetaContentBlockParam) = ClaudeHistory.message(com.anthropic.models.beta.messages.BetaMessageParam.Role.USER, b.toList())
        fun assistant(vararg b: com.anthropic.models.beta.messages.BetaContentBlockParam) = ClaudeHistory.message(com.anthropic.models.beta.messages.BetaMessageParam.Role.ASSISTANT, b.toList())
        val t = ClaudeHistory.text("hi")
        val use = com.anthropic.models.beta.messages.BetaContentBlockParam.ofToolUse(com.anthropic.models.beta.messages.BetaToolUseBlockParam.builder()
            .id("toolu_1").name("set_timer").input(com.anthropic.models.beta.messages.BetaToolUseBlockParam.Input.builder().build()).build())
        val result = ClaudeRequests.results(listOf("toolu_1"), listOf(mapOf("status" to "done"))).single()
        val think = com.anthropic.models.beta.messages.BetaContentBlockParam.ofThinking(com.anthropic.models.beta.messages.BetaThinkingBlockParam.builder().thinking("").signature("SIG").build())
        val edited = com.anthropic.models.beta.messages.BetaContentBlockParam.ofThinking(com.anthropic.models.beta.messages.BetaThinkingBlockParam.builder().thinking("").signature("SIG2").build())
        fun check(vararg m: com.anthropic.models.beta.messages.BetaMessageParam, seals: List<List<String>>? = null) =
            ClaudeHistory.check(m.toList(), seals ?: m.map { ClaudeHistory.seal(ClaudeHistory.blocks(it)) })
        assertNull(check(user(t)))
        assertNull(check(user(t), assistant(think, use), user(result, t)))
        assertEquals("roles_repeat", check(user(t), user(t)))
        assertEquals("first_not_user", check(assistant(t), user(t)))
        assertEquals("tool_result_missing", check(user(t), assistant(use), user(t)))
        assertEquals("tool_result_unasked", check(user(t), assistant(t), user(result, t)))
        assertEquals("tool_result_not_first", check(user(t), assistant(use), user(t, result)))
        assertEquals("ends_with_assistant", check(user(t), assistant(t)))
        // A thinking block changed after it arrived.
        val m = listOf(user(t), assistant(edited, t), user(t))
        assertEquals("thinking_changed", ClaudeHistory.check(m, listOf(emptyList(), ClaudeHistory.seal(listOf(think)), emptyList())))
    }

    @Test fun claudeModeKeepsItsFirstEffort() = runBlocking {
        server.enqueue(textReply("One."))
        server.enqueue(textReply("Two."))
        val s = session(conversation = true)
        s.ask(ask("spoken"), voicePlan, Heard())
        s.ask(ClaudeContext.next("typed"), ClaudePlan.of(app.heyrobot.core.ClaudeEffortSetting.AUTO, voice = false, deep = false), Heard())
        assertEquals("low", take().getJSONObject("output_config").getString("effort"))
        val second = take()
        // The effort is part of what the cache matches: it stays; the typed question still gets the longer limit.
        assertEquals("low", second.getJSONObject("output_config").getString("effort"))
        assertEquals(16_000, second.getInt("max_tokens"))
    }

    // ------------------------------------------------------------------ time and money

    @Test fun thinkingHoldsTheSilenceClock() = runBlocking {
        server.enqueue(textReply("Warm."))
        session().ask(ask("q"), voicePlan, Heard())
        // A thinking block opens, then nothing for longer than the limit, then the answer: not a timeout.
        val events = listOf(start() to 50L, blockStart(0, """{"type":"thinking","thinking":"","signature":""}""") to 1_200L, signature(0, "SIG0") to 0L,
            blockStop(0) to 0L, blockStart(1, """{"type":"text","text":""}""") to 0L, textDelta(1, "Thought it through.") to 0L, blockStop(1) to 0L,
            end("end_turn") to 0L, stop to 0L)
        val slow = slowServer(events.map { (e, gap) -> "event: ${e.first}\ndata: ${e.second}\n\n" to gap })
        ClaudeClient.create(key, "http://127.0.0.1:${slow.localPort}").use { c ->
            var thought = 0
            val heard = object : ClaudeListener {
                val text = StringBuilder()
                override fun text(delta: String) { text.append(delta) }
                override fun declined() {}
                override fun thinking() { thought++ }
            }
            val s = ClaudeSession(c, ClaudeModel.OPUS, ClaudeTools.tools(RobotSettings()), search = true, conversation = false, idleMs = 500)
            assertEquals(ClaudeOutcome.Done(), s.ask(ask("q"), voicePlan, heard))
            assertEquals("Thought it through.", heard.text.toString())
            assertEquals(1, thought)
        }
        slow.close()
    }

    @Test fun silenceInTheMiddleOfAnAnswerIsATimeoutToo() = runBlocking {
        server.enqueue(textReply("Warm."))
        session().ask(ask("q"), voicePlan, Heard())
        val events = listOf(start() to 0L, blockStart(0, """{"type":"text","text":""}""") to 0L, textDelta(0, "Half ") to 1_500L, textDelta(0, "late.") to 0L)
        val slow = slowServer(events.map { (e, gap) -> "event: ${e.first}\ndata: ${e.second}\n\n" to gap })
        ClaudeClient.create(key, "http://127.0.0.1:${slow.localPort}").use { c ->
            val s = ClaudeSession(c, ClaudeModel.OPUS, ClaudeTools.tools(RobotSettings()), search = true, conversation = false, idleMs = 400)
            val heard = Heard()
            assertEquals(ClaudeOutcome.Failed(ClaudeFailure.TIMEOUT), s.ask(ask("q"), voicePlan, heard))
            // Billed all the same: the input known from message_start, the output estimated from what was said.
            assertTrue(s.lastReport.dollars > 0)
        }
        slow.close()
    }

    private class Spend : ClaudeListener {
        var dollars = 0.0
        override fun text(delta: String) {}
        override fun declined() {}
        override fun spent(dollars: Double) { this.dollars += dollars }
    }

    @Test fun aStoppedRequestStillCountsItsInput() = runBlocking {
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream")
            .setBody("event: ${start(input = 10_000).first}\ndata: ${start(input = 10_000).second}\n\n").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN))
        val s = session(conversation = true)
        val spend = Spend()
        val job = async { s.ask(ask("q"), voicePlan, spend) }
        withTimeout(5_000) { while (spend.dollars == 0.0) delay(20) }
        job.cancel()
        job.join()
        // 10,000 input tokens at Opus 5.5's $4 per million, counted the moment message_start arrived.
        assertEquals(0.04, spend.dollars, 0.0005)
        assertTrue(s.history.isEmpty())
    }

    @Test fun aFailedStreamCountsWhatItUsed() = runBlocking {
        server.enqueue(sse(start(input = 5_000), blockStart(0, """{"type":"text","text":""}"""), textDelta(0, "Partial answer here."),
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"x"}}"""))
        val s = session()
        val spend = Spend()
        assertEquals(ClaudeOutcome.Failed(ClaudeFailure.OVERLOADED), s.ask(ask("q"), voicePlan, spend))
        assertTrue(s.lastReport.dollars >= 0.02)
        assertEquals(s.lastReport.dollars, spend.dollars, 1e-9)
    }

    @Test fun aFallbackModelIsPricedAtItsOwnRates() = runBlocking {
        // Sticky routing: message_start already names the fallback model.
        server.enqueue(sse(start(model = "claude-opus-4-8", input = 1_000), blockStart(0, """{"type":"text","text":""}"""), textDelta(0, "x"), blockStop(0), end("end_turn", out = 100), stop))
        val s = session()
        s.ask(ask("q"), voicePlan, Heard())
        assertEquals((1_000 * 5.0 + 100 * 25.0) / 1e6, s.lastReport.dollars, 1e-9)
        // Per-attempt usage when a fallback ran within the request.
        val iterations = ""","iterations":[{"type":"message","input_tokens":1000,"output_tokens":50,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"model":"claude-opus-5-5"},""" +
            """{"type":"fallback_message","input_tokens":1000,"output_tokens":100,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"model":"claude-opus-4-8"}]"""
        server.enqueue(sse(start(input = 1_000), blockStart(0, """{"type":"text","text":""}"""), textDelta(0, "x"), blockStop(0),
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":100$iterations}}""", stop))
        val t = session()
        t.ask(ask("q"), voicePlan, Heard())
        assertEquals((1_000 * 4.0 + 50 * 20.0) / 1e6 + (1_000 * 5.0 + 100 * 25.0) / 1e6, t.lastReport.dollars, 1e-9)
        assertEquals(2_000, t.lastReport.usage.input)
    }

    @Test fun aKeyWithACharacterHeadersCantCarryIsAKeyError() = runBlocking {
        ClaudeClient.create("sk-ant-api03-ROBOTTESTKEY-é-not-a-real-key-0123456789abcdef", server.url("/").toString().removeSuffix("/")).use { c ->
            val s = ClaudeSession(c, ClaudeModel.OPUS, ClaudeTools.tools(RobotSettings()), search = true, conversation = false)
            assertEquals(ClaudeOutcome.Failed(ClaudeFailure.KEY_REFUSED), s.ask(ask("q"), voicePlan, Heard()))
        }
        assertEquals(0, server.requestCount)
    }
}
