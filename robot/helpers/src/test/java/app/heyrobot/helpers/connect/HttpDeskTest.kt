// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.connect

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.StepArg
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.connect.Built
import app.heyrobot.core.connect.CallEnd
import app.heyrobot.core.connect.CheckOutcome
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnOp
import app.heyrobot.core.connect.ConnOps
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnStep
import app.heyrobot.core.connect.ConnTested
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.ConsentAnswer
import app.heyrobot.core.connect.HttpMethod
import app.heyrobot.core.connect.HttpRequest
import app.heyrobot.core.connect.LimitState
import app.heyrobot.core.connect.NtfyPreset
import app.heyrobot.core.connect.OpBody
import app.heyrobot.core.connect.OpOutput
import app.heyrobot.core.connect.OpParam
import app.heyrobot.core.connect.OpSpec
import app.heyrobot.core.connect.ReplyRead
import app.heyrobot.core.connect.RoutingKind
import app.heyrobot.core.connect.RoutingPreset
import app.heyrobot.core.connect.StepContext
import app.heyrobot.core.connect.TravelStep
import app.heyrobot.core.connect.WebhookPreset
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.output.Places
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.StepCheck
import app.heyrobot.core.skills.StepKind
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * C6's desk runs (connections plan §7.4): Robot's real client (ConnHttp) and core's rules, end to end, against the fake
 * ntfy, webhook receiver and routing servers in robot/tools/conn/ on 127.0.0.1 (https with TlsFixture's test CA, trusted
 * only here through the client's test seam). ntfy's Test checks the token and a publish carries the topic only in its body;
 * a webhook's Test reaches its origin with no key and its step reads its outputs and check, a secret address included;
 * travel time against OSRM, Valhalla and GraphHopper, where you are sent rounded to two decimals; and a routing server on
 * a LAN address over http is never reached. Nothing goes to a real server. Skipped where python3 isn't installed.
 */
class HttpDeskTest {
    private val procs = mutableListOf<Process>()
    private val logs = mutableMapOf<Process, MutableList<String>>()
    private lateinit var dir: File
    private val tools = File("../tools/conn")
    private val token = "tk_robotdesk0123456789abcdefghij"
    private val topic = "robot_desk_topic_zq9"
    private val now = 1_761_100_000_000L

    /** home.test is every fake's address: a home one (127.0.0.1, loopback). */
    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == "home.test") listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1))) else throw java.net.UnknownHostException("desk")
    }
    private val http = ConnHttp(dns, { sslSocketFactory(TlsFixture.trusting.socketFactory, TlsFixture.trust) }, ConnHttp.CALL_MS)

    @Before fun up() {
        assumeTrue("python3 isn't installed: the desk runs need it", python() != null)
        for (f in listOf("fake_ntfy.py", "fake_webhook.py", "fake_routing.py")) assertTrue(f, File(tools, f).isFile)
        dir = Files.createTempDirectory("robot-fake-http").toFile()
    }

    @After fun down() {
        procs.forEach { it.destroy(); it.waitFor(5, TimeUnit.SECONDS); if (it.isAlive) it.destroyForcibly() }
        if (::dir.isInitialized) dir.deleteRecursively()
        // The fakes print methods and short paths only: never a key, a topic, a secret path or a coordinate.
        for (l in logs.values.flatten()) assertFalse(l, l.contains(token) || l.contains(topic) || l.contains("SECRET") || l.contains("45.") || l.contains("73."))
    }

    private fun python(): String? = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }

    /** Starts [script] with [args] (https when [tls]); its port. Its request lines go to [logs]. */
    private fun start(script: String, tls: Boolean, vararg args: String): Pair<Process, Int> {
        val extra = if (tls) TlsFixture.pem(dir).let { (c, k) -> listOf("--cert", c.path, "--key", k.path) } else emptyList()
        val p = ProcessBuilder(listOf(python()!!, "-I", File(tools, script).absolutePath, "--port", "0") + args + extra).start()
        procs += p
        val lines = Collections.synchronizedList(mutableListOf<String>())
        logs[p] = lines
        Thread { p.errorStream.bufferedReader().forEachLine { lines += it } }.apply { isDaemon = true }.start()
        val first = p.inputStream.bufferedReader().readLine() ?: throw AssertionError("$script didn't start: " + lines.joinToString("\n"))
        return p to first.removePrefix("port ").trim().toInt()
    }

    private fun end(r: ConnCall): CallEnd = when (r) {
        is ConnCall.Answered -> CallEnd.Answered(r.read, r.bytes)
        is ConnCall.Failed -> CallEnd.Failed(r.why, r.sent)
        is ConnCall.Stopped -> CallEnd.Stopped(r.sent)
    }

    private fun x(c: Connection, places: Places = Places()) = StepContext(c, true, null, true, emptyMap(), LimitState(), now, places, locationAllowed = true)

    /** One step of [op] on [c] as the panel runs it: prepared, built, sent with [secrets], judged. */
    private fun step(c: Connection, op: ConnOp, args: Map<String, StepArg>, secrets: ConnSecrets): Executed {
        val a = ConnStepAction(ConnStepCall(StepKind.CONN, c.id, op.id, args, "Tell the family", 1))
        val ready = ConnStep.prepare(a, x(c)) as? Prepared.Ready ?: throw AssertionError("refused")
        val values = (ConnStep.values(op, args) as ConnStep.Values.Ok).values
        val request = (ConnOps.build(c, op.id, values) as Built.Ok).request
        return ConnStep.result(ready.action as ConnStepAction, c, op, end(http.call(c, request, secrets)), null)
    }

    private fun lines(p: Process): List<String> {
        Thread.sleep(200)
        return logs.getValue(p).toList()
    }

    @Test fun ntfyTestChecksTheTokenAndAPublishKeepsTheTopicOutOfTheAddress() {
        val (p, port) = start("fake_ntfy.py", true, "--topic", topic, "--token", token, "--limit", "1")
        val origin = "https://home.test:$port"
        val publish = ConnOp("tell", "Tell the family", OpSpec.NtfyPublish("{{message}}", title = "Robot", priority = 4L, tags = listOf("house")),
            mapOf("message" to OpParam(ParamType.TEXT, maxLen = 200)))
        val c = Connection("c-ntfy01", ConnKind.NTFY, "Family ntfy", ConnUrl.parse(origin)!!, auth = ConnAuth.Bearer, where = ConnWhere.HOME,
            ops = listOf(publish), answers = mapOf(origin to ConsentAnswer.ALLOW), tested = ConnTested(1L))
        val secrets = ConnSecrets(token = token, topic = topic)
        // Test: healthy with the token; a wrong one is refused even on the health check.
        val t = http.call(c, NtfyPreset.test(c), secrets) as ConnCall.Answered
        assertTrue(NtfyPreset.healthy((t.read as ReplyRead.Ok).json))
        assertEquals(ConnFailure.KEY_REFUSED, ((http.call(c, NtfyPreset.test(c), ConnSecrets(token = "tk_wrong0123456789", topic = topic)) as ConnCall.Answered)
            .read as ReplyRead.Failed).why)
        // CN8 at the desk: the publish arrives; the topic went in the body only.
        val done = step(c, publish, mapOf("message" to StepArg.Fixed("I'm home.")), secrets)
        assertEquals(done.text!!.render(Langs.EN), ActionStatus.DONE, done.status)
        assertEquals(listOf("GET /v1/health", "GET /v1/health", "POST /", "published"), lines(p))
        // Past the server's limit: its 429 in Robot's words.
        val many = step(c, publish, mapOf("message" to StepArg.Fixed("Again.")), secrets)
        assertEquals("Your Family ntfy says Robot sent too many. Try later.", many.text!!.render(Langs.EN))
        // An older server without the health check: 404, which Test takes as "it answers".
        val (_, oldPort) = start("fake_ntfy.py", true, "--topic", topic, "--old")
        val old = c.copy(address = ConnUrl.parse("https://home.test:$oldPort")!!, auth = ConnAuth.None)
        assertTrue(NtfyPreset.older((http.call(old, NtfyPreset.test(old), ConnSecrets(topic = topic)) as ConnCall.Answered).read))
    }

    @Test fun aWebhooksTestReachesItsOriginAndItsStepReadsItsAnswer() {
        val (p, port) = start("fake_webhook.py", false, "--path", "/webhook/trips")
        val origin = "http://127.0.0.1:$port"
        val op = ConnOp("hook", "Log a trip", OpSpec.Webhook(HttpMethod.POST, "/webhook/trips", body = OpBody.Json(mapOf("minutes" to "{{minutes}}"))),
            mapOf("minutes" to OpParam(ParamType.INTEGER, min = 0.0, max = 600.0)),
            outputs = mapOf("count" to OpOutput("/count", ParamType.INTEGER), "saved" to OpOutput("/saved", ParamType.YES_NO)), check = StepCheck.Equals("/ok", true))
        val c = Connection("c-hook01", ConnKind.WEBHOOK, "Trips", ConnUrl.parse(origin)!!, ops = listOf(op), answers = mapOf(origin to ConsentAnswer.ALLOW),
            tested = ConnTested(1L))
        val (probe, test) = WebhookPreset.test(c)!!
        val t = http.call(probe, test, ConnSecrets()) as ConnCall.Answered
        assertTrue(WebhookPreset.reached(t.read))
        assertEquals(ConnWhere.HOME, t.where)
        val done = step(c, op, mapOf("minutes" to StepArg.Fixed(25L)), ConnSecrets())
        assertEquals(ActionStatus.DONE, done.status)
        assertEquals(3L, done.conn!!.outputs.getValue("count").value)
        assertEquals(CheckOutcome.PASSED, done.conn!!.check)
        assertEquals(listOf("GET /", "POST webhook"), lines(p))
        // A server that answers 404 to its front page still passes Test (it's there).
        assertTrue(WebhookPreset.reached(ReplyRead.Failed(ConnFailure.SAID_NO, 404)))
    }

    @Test fun anAddressThatIsItsOwnKeyGoesOnlyOverHttpsToItsOrigin() {
        val secretPath = "/api/webhooks/1/SECRETAbCdEfGhIjKlMnOpQrStUv"
        val (p, port) = start("fake_webhook.py", true, "--path", secretPath)
        val origin = "https://home.test:$port"
        val post = ConnOp("post", "Post to the channel", OpSpec.Webhook(HttpMethod.POST, body = OpBody.Json(mapOf("content" to "{{text}}"))),
            mapOf("text" to OpParam(ParamType.TEXT, maxLen = 200)))
        val c = Connection("c-disc01", ConnKind.WEBHOOK, "Channel", ConnUrl.parse(origin)!!, secretAddress = true, ops = listOf(post),
            answers = mapOf(origin to ConsentAnswer.ALLOW), tested = ConnTested(1L))
        val (probe, test) = WebhookPreset.test(c)!!
        assertTrue(WebhookPreset.reached((http.call(probe, test, ConnSecrets()) as ConnCall.Answered).read))
        val done = step(c, post, mapOf("text" to StepArg.Fixed("On my way.")), ConnSecrets(address = origin + secretPath))
        assertEquals(ActionStatus.DONE, done.status)
        // Test never sent the secret path; the step did, once.
        assertEquals(listOf("GET /", "POST webhook"), lines(p))
        // A key's address for another host is never sent to it.
        val elsewhere = step(c, post, mapOf("text" to StepArg.Fixed("x")), ConnSecrets(address = "https://split.test:$port$secretPath"))
        assertEquals(ActionStatus.FAILED, elsewhere.status)
        assertEquals(listOf("GET /", "POST webhook"), lines(p))
    }

    @Test fun travelTimeFromEachRoutingServer() {
        val home = SavedPlace.of("Home", 45.50884, -73.58781)!!
        val here = 45.473129 to -73.602191
        for (kind in RoutingKind.entries) {
            val (p, port) = start("fake_routing.py", false, "--kind", kind.id, "--strict")
            val origin = "http://127.0.0.1:$port"
            val c = Connection("c-rout01", ConnKind.ROUTING, "Routes", ConnUrl.parse(origin)!!, routing = kind, where = ConnWhere.HOME,
                answers = mapOf(origin to ConsentAnswer.ALLOW), tested = ConnTested(1L))
            // Test: two points near Home.
            val t = http.call(c, (RoutingPreset.test(c, home) as Built.Ok).request, ConnSecrets()) as ConnCall.Answered
            assertNotNull(kind.id, RoutingPreset.read(kind, (t.read as ReplyRead.Ok).json))
            // The step: prepared, then where you are (rounded by core: the fake refuses more than two decimals), then judged.
            val a = ConnStepAction(ConnStepCall(StepKind.TRAVEL_TIME, null, null, mapOf("to" to StepArg.Fixed("home")), "Heading home", 1))
            val ready = (ConnStep.prepare(a, x(c, Places(home, null))) as Prepared.Ready).action as ConnStepAction
            val r = (RoutingPreset.request(c, here.first, here.second, home) as Built.Ok).request
            val done = TravelStep.result(ready, c, end(http.call(c, r, ConnSecrets())), null)
            assertEquals(kind.id + ": " + done.text!!.render(Langs.EN), ActionStatus.DONE, done.status)
            val minutes = done.conn!!.outputs.getValue("minutes").value as Long
            assertTrue("$kind $minutes", minutes in 5L..9L)
            assertTrue(done.conn!!.located)
            assertEquals(kind.id, 2, lines(p).size)
        }
        // No route: each server's own 400, said in Robot's words.
        for (kind in RoutingKind.entries) {
            val (_, port) = start("fake_routing.py", false, "--kind", kind.id, "--no-route")
            val c = Connection("c-rout02", ConnKind.ROUTING, "Routes", ConnUrl.parse("http://127.0.0.1:$port")!!, routing = kind, tested = ConnTested(1L))
            val r = (RoutingPreset.request(c, here.first, here.second, home) as Built.Ok).request
            val a = ConnStepAction(ConnStepCall(StepKind.TRAVEL_TIME, null, null, mapOf("to" to StepArg.Fixed("home")), "Heading home", 1))
            assertEquals(kind.id, "Your Routes said no (error 400).", TravelStep.result(a, c, end(http.call(c, r, ConnSecrets())), null).text!!.render(Langs.EN))
        }
        // GraphHopper names its own profiles: one without "car" says no.
        val (_, gp) = start("fake_routing.py", false, "--kind", "graphhopper", "--profiles", "bike,foot")
        val g = Connection("c-rout03", ConnKind.ROUTING, "Routes", ConnUrl.parse("http://127.0.0.1:$gp")!!, routing = RoutingKind.GRAPHHOPPER, tested = ConnTested(1L))
        val gr = http.call(g, (RoutingPreset.test(g, home) as Built.Ok).request, ConnSecrets()) as ConnCall.Answered
        assertEquals(ReplyRead.Failed(ConnFailure.SAID_NO, 400), gr.read)
    }

    @Test fun aRoutingServerOnALanAddressOverHttpIsNeverReached() {
        val (p, port) = start("fake_routing.py", false, "--kind", "osrm")
        val home = SavedPlace.of("Home", 45.50884, -73.58781)!!
        val lan = Connection("c-rout04", ConnKind.ROUTING, "Routes", ConnUrl.parse("http://192.168.1.20:$port")!!, routing = RoutingKind.OSRM, tested = ConnTested(1L))
        assertEquals(Built.Refused(ConnFailure.LOCATION_OVER_HTTP), RoutingPreset.request(lan, 45.47, -73.6, home))
        // Even a request built for loopback and handed to the LAN connection: refused before any client.
        val ok = Connection("c-rout05", ConnKind.ROUTING, "Routes", ConnUrl.parse("http://127.0.0.1:$port")!!, routing = RoutingKind.OSRM)
        val r = (RoutingPreset.request(ok, 45.47, -73.6, home) as Built.Ok).request
        val refused = http.call(lan, HttpRequest(r.method, lan.address!!, false, r.path, r.query, r.headers, r.body, r.auth, r.read, r.json), ConnSecrets())
        assertEquals(ConnFailure.LOCATION_OVER_HTTP, (refused as ConnCall.Failed).why)
        assertFalse(refused.sent)
        assertEquals(emptyList<String>(), lines(p))
    }
}
