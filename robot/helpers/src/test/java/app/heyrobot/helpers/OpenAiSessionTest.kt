package app.heyrobot.helpers

import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperEffort
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.HelperTools
import app.heyrobot.core.DoneReason
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.helpers.Rates
import app.heyrobot.core.helpers.wire.Extra
import app.heyrobot.helpers.http.HelperHttp
import app.heyrobot.helpers.http.NotLocalException
import app.heyrobot.helpers.openai.OpenAiSession
import app.heyrobot.helpers.openai.Timing
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The helpers' own transport and session (0.6.0) against a local mock server: no real key, no network. The server plays a
 * Chat Completions server (a home server on 127.0.0.1, which the LAN rule allows over http) or the Responses API; every
 * request Robot sends is read back and checked.
 */
class OpenAiSessionTest {
    private val key = "sk-proj-ROBOTTESTKEY0123456789abcdefghijklmnop-not-a-real-key"
    private lateinit var server: MockWebServer
    private val logged = mutableListOf<String>()

    @Before fun up() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        RLog.sink = { synchronized(logged) { logged += it } }
    }

    @After fun down() {
        RLog.sink = null
        server.shutdown()
        synchronized(logged) { for (l in logged) assertFalse(l, l.contains("ROBOTTESTKEY") || l.contains("not-a-real-key") || l.contains("127.0.0.1")) }
    }

    private val base get() = server.url("/v1").toString()
    private fun home(model: String = "qwen3.8:27b", protocol: String = "home") =
        HelperConfig.of(Presets.of(protocol)!!, "h1", "am_puck", "Jarvis", base).copy(model = model)

    private fun http(h: HelperConfig = home()) = HelperHttp.create(base, key, home = true)!!

    private fun session(h: HelperConfig = home(), tools: Boolean = false, conversation: Boolean = false, timing: Timing = Timing(2_000, 2_000, 2_000, 2_000, 500),
        dropped: Set<Extra> = emptySet(), onDropped: (Extra) -> Unit = {}) =
        OpenAiSession(http(h), h, if (tools) HelperTools.tools(RobotSettings()) else emptyList(), conversation, dropped = dropped, onDropped = onDropped,
            timing = timing, retryDelayMs = 10)

    private class Heard : HelperTurnListener {
        val text = StringBuilder()
        var declined = 0
        var loading = 0
        var thinking = 0
        var spent = 0.0
        override fun text(delta: String) { text.append(delta) }
        override fun declined() { declined++ }
        override fun loading() { loading++ }
        override fun thinking() { thinking++ }
        override fun spent(dollars: Double) { spent += dollars }
    }

    private fun sse(vararg payloads: String) = MockResponse().setHeader("content-type", "text/event-stream")
        .setBody(payloads.joinToString("") { "data: $it\n\n" })

    private fun chunk(delta: String, finish: String? = null) =
        """{"choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""

    private fun answer(vararg pieces: String, usage: String = """{"prompt_tokens":100,"completion_tokens":10}""") = sse(
        *pieces.map { chunk("""{"content":${JSONObject.quote(it)}}""") }.toTypedArray(), chunk("{}", "stop"), """{"choices":[],"usage":$usage}""", "[DONE]",
    )

    private fun error(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)
    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
    private fun body(r: RecordedRequest) = JSONObject(r.body.readUtf8())
    private val q = listOf(HelperBlock.Text("[Today is Monday.]"), HelperBlock.Text("why is the sky blue"))
    private val voice = HelperPlan(HelperEffort.LOW, 4_000)

    @Test fun aQuestionStreamsAndJoinsTheHistory() = runBlocking {
        server.enqueue(answer("The sky ", "is blue."))
        val s = session(conversation = true)
        val h = Heard()
        assertEquals(HelperOutcome.Done(), s.ask(q, voice, h))
        assertEquals("The sky is blue.", h.text.toString())
        val r = take()
        assertEquals("/v1/chat/completions", r.path)
        assertEquals("Bearer $key", r.getHeader("Authorization"))
        assertEquals("Robot", r.getHeader("User-Agent"))
        val b = body(r)
        assertEquals("qwen3.8:27b", b.getString("model"))
        assertTrue(b.getBoolean("stream"))
        assertEquals(true, b.getJSONObject("stream_options").getBoolean("include_usage"))
        val ms = b.getJSONArray("messages")
        assertEquals("system", ms.getJSONObject(0).getString("role"))
        assertTrue(ms.getJSONObject(0).getString("content").startsWith("You are Jarvis. "))
        assertEquals("[Today is Monday.]\n\nwhy is the sky blue", ms.getJSONObject(1).getString("content"))
        assertEquals(listOf("user", "assistant"), s.history.map { it.getString("role") })
        // A home server is free: nothing spent; the report knows the cost.
        assertEquals(0.0, h.spent, 0.0)
        assertTrue(s.lastReport.costKnown)
        assertEquals(1, s.lastReport.requests)
        // The next question in the conversation carries the first answer back as it came.
        server.enqueue(answer("Because of Rayleigh scattering."))
        s.ask(listOf(HelperBlock.Text("and why?")), voice, Heard())
        val m2 = body(take()).getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until m2.length()).map { m2.getJSONObject(it).getString("role") })
        assertEquals("The sky is blue.", m2.getJSONObject(2).getString("content"))
    }

    @Test fun aToolRoundGoesBackWithItsResults() = runBlocking {
        server.enqueue(sse(chunk("""{"role":"assistant","content":null,"reasoning_content":"timer","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"set_timer","arguments":"{\"minutes\":10}"}}]}"""),
            chunk("{}", "tool_calls"), "[DONE]"))
        server.enqueue(answer("Timer set."))
        val s = session(tools = true)
        val o = s.ask(q, voice, Heard())
        assertEquals(HelperOutcome.Calls(listOf(RawToolCall("set_timer", mapOf("minutes" to 10)))), o)
        val first = body(take())
        assertTrue(first.getJSONArray("tools").length() > 0)
        assertFalse(first.has("tool_choice"))
        assertEquals(HelperOutcome.Done(), s.answer(listOf(mapOf("status" to "done")), Heard()))
        val ms = body(take()).getJSONArray("messages")
        val roles = (0 until ms.length()).map { ms.getJSONObject(it).getString("role") }
        assertEquals(listOf("system", "user", "assistant", "tool"), roles)
        // DeepSeek's rule: the reasoning goes back with the call, unchanged.
        assertEquals("timer", ms.getJSONObject(2).getString("reasoning_content"))
        assertEquals("call_1", ms.getJSONObject(3).getString("tool_call_id"))
        assertEquals("""{"status":"done"}""", ms.getJSONObject(3).getString("content"))
    }

    @Test fun aFailedQuestionLeavesNoTraceAndIsRetriedOnce() = runBlocking {
        server.enqueue(error(500, """{"error":{"type":"server_error","message":"mock"}}"""))
        server.enqueue(error(503, """{"error":{"message":"mock"}}"""))
        val s = session()
        assertEquals(HelperOutcome.Failed(HelperFailure.SERVER), s.ask(q, voice, Heard()))
        assertEquals(2, server.requestCount)
        assertTrue(s.history.isEmpty())
        // A refused key: said at once, never retried.
        server.enqueue(error(401, """{"error":{"message":"bad key","type":"invalid_request_error","code":"invalid_api_key"}}"""))
        assertEquals(HelperOutcome.Failed(HelperFailure.KEY_REFUSED), s.ask(q, voice, Heard()))
        assertEquals(3, server.requestCount)
        // Out of credit with a 429 (OpenAI's code): not "busy".
        server.enqueue(error(429, """{"error":{"message":"…","type":"insufficient_quota","code":"insufficient_quota"}}"""))
        assertEquals(HelperOutcome.Failed(HelperFailure.NO_CREDITS), s.ask(q, voice, Heard()))
        // A model the server doesn't have (Ollama's 404): the home words.
        server.enqueue(error(404, """{"error":"model 'qwen3.8:27b' not found"}"""))
        assertEquals(HelperOutcome.Failed(HelperFailure.MODEL_UNAVAILABLE), s.ask(q, voice, Heard()))
        assertTrue(s.history.isEmpty())
    }

    @Test fun aRefusedExtraIsDroppedAndRemembered() = runBlocking {
        server.enqueue(error(400, """{"error":{"message":"unknown field stream_options","type":"invalid_request_error"}}"""))
        server.enqueue(answer("OK"))
        val dropped = mutableListOf<Extra>()
        val s = session(onDropped = { dropped += it })
        assertEquals(HelperOutcome.Done(), s.ask(q, voice, Heard()))
        assertTrue(body(take()).has("stream_options"))
        assertFalse(body(take()).has("stream_options"))
        assertEquals(listOf(Extra.STREAM_OPTIONS), dropped)
        // A session that starts with it remembered never sends it.
        server.enqueue(answer("OK"))
        session(dropped = setOf(Extra.STREAM_OPTIONS)).ask(q, voice, Heard())
        assertFalse(body(take()).has("stream_options"))
        // A 400 with nothing left to drop is said.
        server.enqueue(error(400, """{"error":{"message":"bad"}}"""))
        val bare = session(dropped = Extra.entries.toSet())
        assertEquals(HelperOutcome.Failed(HelperFailure.BAD_REQUEST), bare.ask(q, voice, Heard()))
    }

    @Test fun zeroDataRetentionIsNeverDroppedAndATooLongQuestionDropsNothing() = runBlocking {
        val or = HelperConfig.of(Presets.OPENROUTER, "h5", "am_puck", "OpenRouter").copy(model = "some/model")
        fun openRouter(onDropped: (Extra) -> Unit) = OpenAiSession(http(), or, emptyList(), false, onDropped = onDropped,
            timing = Timing(2_000, 2_000, 2_000, 2_000, 500), retryDelayMs = 10)
        // A 400 that names no part: effort goes (the only optional part Robot may drop), zero data retention never does.
        server.enqueue(error(400, """{"error":{"message":"bad","type":"invalid_request_error"}}"""))
        server.enqueue(error(400, """{"error":{"message":"bad","type":"invalid_request_error"}}"""))
        val dropped = mutableListOf<Extra>()
        assertEquals(HelperOutcome.Failed(HelperFailure.BAD_REQUEST), openRouter { dropped += it }.ask(q, voice, Heard()))
        assertEquals(listOf(Extra.EFFORT), dropped)
        repeat(2) { assertTrue(body(take()).getJSONObject("provider").getBoolean("zdr")) }
        assertEquals(2, server.requestCount)
        // A 400 that names zero data retention (no provider keeps nothing for this model): said, nothing dropped or sent again.
        server.enqueue(error(400, """{"error":{"message":"no zdr","type":"invalid_request_error","param":"provider.zdr"}}"""))
        assertEquals(HelperOutcome.Failed(HelperFailure.BAD_REQUEST), openRouter { dropped += it }.ask(q, voice, Heard()))
        assertEquals(3, server.requestCount)
        // Too long: no part is to blame, so none is dropped for later questions.
        server.enqueue(error(400, """{"error":{"message":"too long","type":"invalid_request_error","code":"context_length_exceeded"}}"""))
        assertEquals(HelperOutcome.Failed(HelperFailure.TOO_LONG), openRouter { dropped += it }.ask(q, voice, Heard()))
        assertEquals(4, server.requestCount)
        // A 400 that names the part it refused: that part only.
        server.enqueue(error(400, """{"error":{"message":"no","type":"invalid_request_error","param":"stream_options"}}"""))
        server.enqueue(answer("OK"))
        val named = mutableListOf<Extra>()
        assertEquals(HelperOutcome.Done(), session(onDropped = { named += it }).ask(q, voice, Heard()))
        assertEquals(listOf(Extra.STREAM_OPTIONS), named)
        assertEquals(listOf(Extra.EFFORT), dropped)
    }

    @Test fun aServerThatLoadsItsModelThenAnswers() = runBlocking {
        server.enqueue(answer("Hello").setHeadersDelay(900, TimeUnit.MILLISECONDS))
        val h = Heard()
        assertEquals(HelperOutcome.Done(), session().ask(q, voice, h))
        assertEquals(1, h.loading)
        assertEquals("Hello", h.text.toString())
    }

    @Test fun silenceIsATimeoutAndAStopLeavesNoTrace() = runBlocking {
        // Nothing at all comes back: "loading its model" after a while, then a timeout.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val s = session(timing = Timing(300, 300, 300, 1_000, 500))
        val h = Heard()
        val o = s.ask(q, voice, h)
        assertEquals(HelperOutcome.Failed(HelperFailure.TIMEOUT), o)
        assertEquals(1, h.loading)
        assertTrue(s.history.isEmpty())
        // A stop (the coroutine cancelled) mid-answer: back to before the question.
        server.enqueue(answer("never").setHeadersDelay(3, TimeUnit.SECONDS))
        val job = async { s.ask(q, voice, Heard()) }
        delay(200)
        job.cancel()
        try { job.await() } catch (_: kotlinx.coroutines.CancellationException) {}
        assertTrue(s.history.isEmpty())
    }

    @Test fun aRefusalIsDeclined() = runBlocking {
        server.enqueue(sse(chunk("""{"refusal":"I can't help with that."}"""), chunk("{}", "stop"), "[DONE]"))
        val h = Heard()
        val s = session()
        assertEquals(HelperOutcome.Declined, s.ask(q, voice, h))
        assertEquals(1, h.declined)
        assertTrue(s.history.isEmpty())
    }

    @Test fun anErrorInsideTheStream() = runBlocking {
        server.enqueue(sse(chunk("""{"content":"Hel"}"""), """{"error":{"code":429,"message":"slow","metadata":{"error_type":"rate_limit_error"}},"choices":[]}"""))
        assertEquals(HelperOutcome.Failed(HelperFailure.RATE_LIMITED), session().ask(q, voice, Heard()))
        // A stream that stops before saying how it ended is a failure, never an answer.
        server.enqueue(sse(chunk("""{"content":"half an ans"}""")))
        assertEquals(HelperOutcome.Failed(HelperFailure.UNKNOWN), session().ask(q, voice, Heard()))
    }

    @Test fun costFromYourPricesOrReported() = runBlocking {
        server.enqueue(answer("x", usage = """{"prompt_tokens":1000000,"completion_tokens":1000000}"""))
        val h = Heard()
        val priced = home().copy(prices = Rates(0.5, 1.5))
        session(priced).ask(q, voice, h)
        assertEquals(2.0, h.spent, 1e-9)
        // OpenRouter-style reported cost wins.
        server.enqueue(answer("x", usage = """{"prompt_tokens":10,"completion_tokens":10,"cost":0.0123}"""))
        val h2 = Heard()
        session(priced).ask(q, voice, h2)
        assertEquals(0.0123, h2.spent, 1e-12)
        // Another company without a known price: cost unknown, nothing counted.
        server.enqueue(answer("x"))
        val other = HelperConfig.of(Presets.OTHER, "h2", "am_puck", "Box", base).copy(model = "m")
        val s = session(other)
        val h3 = Heard()
        s.ask(q, voice, h3)
        assertEquals(0.0, h3.spent, 0.0)
        assertFalse(s.lastReport.costKnown)
    }

    @Test fun noRedirectsAndCleartextOnlyToYourNetwork() = runBlocking {
        // A redirect is never followed.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://8.8.8.8/v1/chat/completions"))
        assertTrue(session().ask(q, voice, Heard()) is HelperOutcome.Failed)
        assertEquals(1, server.requestCount)
        // A company never gets http://.
        assertNull(HelperHttp.create(base, key, home = false))
        // A typed public address over http: refused before any connection.
        assertNull(HelperHttp.create("http://8.8.8.8:11434/v1", key, home = true))
        // A name that resolves to a public address: the DNS filter refuses it, nothing is sent.
        val publicDns = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("93.184.216.34")) }
        val viaName = HelperHttp.create("http://my.server.example:${server.port}/v1", key, home = true, dns = publicDns)!!
        val h = HelperConfig.of(Presets.HOME, "h3", "am_puck", "Jarvis", "http://my.server.example:${server.port}/v1").copy(model = "m")
        val s = OpenAiSession(viaName, h, emptyList(), false, retryDelayMs = 10)
        assertEquals(HelperOutcome.Failed(HelperFailure.NOT_LOCAL), s.ask(q, voice, Heard()))
        assertEquals(1, server.requestCount)
        // Mixed: a public and the private one; only the private one is used.
        val mixed = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")) }
        assertEquals(1, app.heyrobot.helpers.http.LanDns(mixed).lookup("x").size)
        try { app.heyrobot.helpers.http.LanDns(publicDns).lookup("x"); throw AssertionError("not refused") } catch (_: NotLocalException) {}
    }

    @Test fun theInterceptorChecksTheAddressReallyConnected() {
        // What OkHttp connected to (an address typed as a number never reaches the DNS filter): cleartext only to your network.
        fun chain(ip: ByteArray, url: String = "http://box.example/v1/chat/completions") = object : okhttp3.Interceptor.Chain {
            private val req = okhttp3.Request.Builder().url(url).build()
            private val address = okhttp3.Address("box.example", 80, Dns.SYSTEM, javax.net.SocketFactory.getDefault(), null, null, null,
                okhttp3.Authenticator.NONE, java.net.Proxy.NO_PROXY, listOf(okhttp3.Protocol.HTTP_1_1), listOf(okhttp3.ConnectionSpec.CLEARTEXT),
                java.net.ProxySelector.getDefault())
            private val conn = object : okhttp3.Connection {
                override fun route() = okhttp3.Route(address, java.net.Proxy.NO_PROXY, java.net.InetSocketAddress(InetAddress.getByAddress(ip), 80))
                override fun socket() = java.net.Socket()
                override fun handshake(): okhttp3.Handshake? = null
                override fun protocol() = okhttp3.Protocol.HTTP_1_1
            }
            override fun request() = req
            override fun proceed(request: okhttp3.Request) =
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").build()
            override fun connection() = conn
            override fun call(): okhttp3.Call = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
        }
        val lan = byteArrayOf(192.toByte(), 168.toByte(), 1, 20)
        val public = byteArrayOf(8, 8, 8, 8)
        assertEquals(200, app.heyrobot.helpers.http.LanGuard.intercept(chain(lan)).code)
        try {
            app.heyrobot.helpers.http.LanGuard.intercept(chain(public))
            throw AssertionError("a public address over http went through")
        } catch (_: NotLocalException) {}
        // Over https the certificate protects it.
        assertEquals(200, app.heyrobot.helpers.http.LanGuard.intercept(chain(public, "https://box.example/v1/chat/completions")).code)
        // A number the rule can't read ("134744072" is 8.8.8.8) would skip the DNS filter: no client at all.
        assertNull(HelperHttp.create("http://134744072:11434/v1", key, home = true))
        assertNull(HelperHttp.create("http://10.1:11434/v1", key, home = true))
    }

    @Test fun eventsAreReadWhateverTheirPiecesAndNeverTooLong() = runBlocking {
        val r = app.heyrobot.helpers.http.SseReader(okio.Buffer().writeUtf8(": keep-alive\n\ndata: {\"a\":1}\r\n\ndata: x\ndata: y\n\nevent: done\ndata: [DONE]"))
        assertEquals("""{"a":1}""", r.next())
        assertEquals("x\ny", r.next())
        assertEquals("[DONE]", r.next())
        assertNull(r.next())
        val long = app.heyrobot.helpers.http.SseReader(okio.Buffer().writeUtf8("data: " + "x".repeat(100) + "\n\n"), maxLine = 50)
        try { long.next(); throw AssertionError("a line too long was read") } catch (_: java.io.IOException) {}
        // The stream arriving one byte at a time (an accented letter and an emoji split inside their bytes, a tag split too).
        server.enqueue(answer("Café ", "<thi", "nk>hmm</think>", "déjà 👍").throttleBody(1, 1, TimeUnit.MILLISECONDS))
        val h = Heard()
        assertEquals(HelperOutcome.Done(), session().ask(q, voice, h))
        assertEquals("Café déjà 👍", h.text.toString())
    }

    @Test fun anUnreachableServerSaysSo() = runBlocking {
        val port = server.port
        server.shutdown()
        val h = HelperConfig.of(Presets.HOME, "h4", "am_puck", "Jarvis", "http://127.0.0.1:$port/v1").copy(model = "m")
        val s = OpenAiSession(HelperHttp.create("http://127.0.0.1:$port/v1", null, home = true)!!, h, emptyList(), false, retryDelayMs = 10)
        assertEquals(HelperOutcome.Failed(HelperFailure.UNREACHABLE), s.ask(q, voice, Heard()))
        server = MockWebServer().also { it.start() }
    }

    @Test fun responsesApiEchoesReasoningAndCallsWithStoreOff() = runBlocking {
        val reasoning = """{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"gAAAAB-secret"}"""
        val call = """{"type":"function_call","id":"fc_1","call_id":"call_7","name":"set_timer","arguments":"{\"minutes\":5}","status":"completed"}"""
        server.enqueue(sse(
            """{"type":"response.output_item.done","output_index":0,"item":$reasoning}""",
            """{"type":"response.output_item.done","output_index":1,"item":$call}""",
            """{"type":"response.completed","response":{"status":"completed","output":[$reasoning,$call],"usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":0},"output_tokens":50}}}""",
        ))
        server.enqueue(sse(
            """{"type":"response.output_text.delta","delta":"Done."}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Done.","annotations":[]}]}],"usage":{"input_tokens":1100,"output_tokens":5}}}""",
        ))
        val gpt = HelperConfig.of(Presets.OPENAI, "h5", "am_puck").copy(model = "gpt-6.1-sol")
        // The Responses session against the mock (a home-style client so the test can use http on 127.0.0.1).
        val s = OpenAiSession(HelperHttp.create(base, key, home = true)!!, gpt, HelperTools.tools(RobotSettings()), conversation = true, retryDelayMs = 10)
        val h = Heard()
        assertEquals(HelperOutcome.Calls(listOf(RawToolCall("set_timer", mapOf("minutes" to 5)))), s.ask(q, HelperPlan(HelperEffort.MEDIUM, 16_000), h))
        val first = take()
        assertEquals("/v1/responses", first.path)
        val b1 = body(first)
        assertFalse(b1.getBoolean("store"))
        assertEquals("reasoning.encrypted_content", b1.getJSONArray("include").getString(0))
        assertEquals("medium", b1.getJSONObject("reasoning").getString("effort"))
        assertEquals(HelperOutcome.Done(), s.answer(listOf(mapOf("status" to "done")), h))
        val input = body(take()).getJSONArray("input")
        val types = (0 until input.length()).map { input.getJSONObject(it).optString("type").ifEmpty { input.getJSONObject(it).getString("role") } }
        assertEquals(listOf("user", "reasoning", "function_call", "function_call_output"), types)
        assertEquals("gAAAAB-secret", input.getJSONObject(1).getString("encrypted_content"))
        assertEquals("call_7", input.getJSONObject(3).getString("call_id"))
        // Priced from OpenAI's page: gpt-6.1-sol at $2 in and $10 out.
        assertEquals((1000 * 2.0 + 50 * 10.0 + 1100 * 2.0 + 5 * 10.0) / 1e6, h.spent, 1e-12)
    }

    @Test fun theTurnRunnerSeesAHelperAsAModel() = runBlocking {
        server.enqueue(answer("Fine."))
        val t = HelperTurn(session(), q, voice, Heard())
        assertEquals(ModelOutcome.Done(DoneReason.END), t.ask())
        // The panel runs only the tools a helper was given: none with its actions off, and the call it sends anyway is
        // passed on as it came, for the turn runner to refuse.
        val none = session()
        assertTrue(none.offered.isEmpty())
        assertEquals(HelperTools.tools(RobotSettings()).map { it.tool }.toSet(), session(tools = true).offered)
        server.enqueue(sse(chunk("""{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"add_note","arguments":"{\"text\":\"x\"}"}}]}""", "tool_calls"), "[DONE]"))
        assertEquals(HelperOutcome.Calls(listOf(RawToolCall("add_note", mapOf("text" to "x")))), none.ask(q, voice, Heard()))
        // Failures come back in the helper's words.
        server.enqueue(error(401, "{}"))
        server.enqueue(error(401, "{}"))
        val who = app.heyrobot.core.helpers.HelperWho.of(home())
        assertEquals(ModelOutcome.Failed("Your Jarvis key was refused. Check it in Robot's settings."), HelperTurn(session(), q, voice, Heard()) { it.say(who) }.ask())
    }

    @Test fun probeFindsTheServerListsModelsAndAsks() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"qwen3.8:27b"},{"id":"llama4:8b"}]}"""))
        server.enqueue(answer("OK"))
        val r = HelperProbe.run(home(model = ""), null, listOf(base))
        assertTrue(r.ok)
        assertEquals(listOf("qwen3.8:27b", "llama4:8b"), r.models)
        assertTrue(r.answered && r.usage)
        assertEquals("/v1/models", take().path)
        assertEquals("qwen3.8:27b", body(take()).getString("model"))
        // Ollama's own list after a 404.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"gemma4:e4b","model":"gemma4:e4b"}]}"""))
        val o = HelperProbe.run(home(), null, listOf(base), question = false)
        assertEquals("ollama", o.server)
        assertEquals(listOf("gemma4:e4b"), o.models)
        // A server that asks for a key.
        server.enqueue(MockResponse().setResponseCode(401))
        val k = HelperProbe.run(home(), null, listOf(base))
        assertTrue(k.needsKey)
        assertEquals(HelperFailure.KEY_REFUSED, k.failure)
        assertEquals(mapOf<String, Any>(), HelperProbe.ids("not json").associateWith { it })
    }
}
