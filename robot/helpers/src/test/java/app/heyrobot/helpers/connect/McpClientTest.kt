// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.connect

import app.heyrobot.core.Abort
import app.heyrobot.core.StrictJson
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnCheck
import app.heyrobot.core.connect.ConnProblem
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnRules
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.McpEra
import app.heyrobot.core.connect.McpOps
import app.heyrobot.core.connect.McpOut
import app.heyrobot.core.connect.McpPin
import app.heyrobot.core.connect.McpPolicy
import app.heyrobot.core.connect.McpRefusal
import app.heyrobot.core.connect.McpToolSetting
import app.heyrobot.core.connect.McpToolsFile
import app.heyrobot.core.skills.SkillLevel
import okhttp3.Dns
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Collections

/**
 * McpClient (S3.6) against the replies tools/fake_mcp.py recorded (test resources mcp/<scenario>.json: `fake_mcp.py
 * --record`), replayed by a local server that also checks what Robot sent: the era's headers, the session, the token.
 * Design M1 (both eras, list and call), M2 (a changed schema turns the tool off) and M3 (loopback without a token is
 * refused, with one it works; a wrong argument is refused before sending), on the PC.
 */
class McpClientTest {
    private lateinit var server: MockWebServer
    private val seen = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val token = "TEST-TOKEN"

    private class Replay(name: String) {
        val exchanges: List<Map<String, Any?>> = (StrictJson.parse(McpClientTest::class.java.getResourceAsStream("/mcp/$name.json")!!.readBytes())!!.value as List<*>)
            .map { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }

        fun of(method: String): Map<String, Any?> = exchanges.first { it["method"] == method }
    }

    private fun method(r: RecordedRequest): String = if (r.method == "DELETE") "DELETE" else
        ((StrictJson.parse(r.body.clone().readByteArray())!!.value as Map<*, *>)["method"] as String)

    private fun id(r: RecordedRequest): Long? = (StrictJson.parse(r.body.clone().readByteArray())?.value as? Map<*, *>)?.get("id") as? Long

    /** The recorded reply to [r], its JSON-RPC id made [r]'s. */
    private fun reply(x: Map<String, Any?>, r: RecordedRequest): MockResponse {
        val m = MockResponse().setResponseCode((x["status"] as Long).toInt())
        (x["type"] as String?)?.let { m.setHeader("Content-Type", it) }
        (x["session"] as String?)?.let { m.setHeader("Mcp-Session-Id", it) }
        val body = (x["body"] as String).let { b -> id(r)?.let { b.replace(Regex("\"id\": \\d+"), "\"id\": $it") } ?: b }
        return m.setBody(body)
    }

    private fun serve(replay: Replay, change: (String, RecordedRequest) -> MockResponse? = { _, _ -> null }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val m = method(request)
                return change(m, request) ?: reply(replay.of(m), request)
            }
        }
    }

    private fun mcp(address: String = "https://127.0.0.1:${server.port}/api/mcp", auth: ConnAuth = ConnAuth.Bearer) =
        Connection("c-mcp001", ConnKind.MCP, "Home", ConnUrl.parse(address)!!, auth = auth)

    private fun client(dns: Dns = Dns.SYSTEM, callMs: Long = 5_000) = McpClient(ConnHttp(dns, { sslSocketFactory(TlsFixture.trusting.socketFactory, TlsFixture.trust) }, ConnHttp.CALL_MS), 5_000, callMs)
    private fun secrets(auth: ConnAuth = ConnAuth.Bearer) = if (auth == ConnAuth.Bearer) ConnSecrets(token = token) else ConnSecrets()
    private fun methods() = seen.map(::method)

    private fun listing(end: McpListEnd): McpListing = ((end.out as McpOut.Ok).value)

    private fun pin(l: McpListing, name: String): McpPin = McpPolicy.pin(l.listed.tool(name)!!)

    @Before fun up() {
        server = MockWebServer().apply { useHttps(TlsFixture.signed.socketFactory, false) }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun down() = server.shutdown()

    @Test fun modernListsAndCallsWithItsHeadersAndNoSession() {
        serve(Replay("modern"))
        val c = mcp()
        val l = client().list(c, secrets(), Abort(), "test")
        assertEquals(McpEra.MODERN, l.era)
        assertEquals(listOf("HassTurnOn", "HassTurnOff", "GetLiveContext"), listing(l).listed.tools.map { it.name })
        val call = client().call(c, pin(listing(l), "HassTurnOff"), mapOf("name" to "kitchen"), secrets(), Abort(), "test")
        assertEquals("Turned off kitchen.", ((call.out as McpOut.Ok).value).text)
        assertTrue(call.sent)
        assertEquals(listOf("server/discover", "tools/list", "server/discover", "tools/call"), methods())
        for (r in seen) {
            assertEquals("2026-07-28", r.getHeader("MCP-Protocol-Version"))
            assertEquals(method(r), r.getHeader("Mcp-Method"))
            assertEquals("Bearer $token", r.getHeader("Authorization"))
            assertNull(r.getHeader("Mcp-Session-Id"))
            assertEquals("/api/mcp", r.path)
        }
        assertEquals("HassTurnOff", seen.last().getHeader("Mcp-Name"))
    }

    @Test fun legacyOverSseInitializesSendsItsSessionAndEndsIt() {
        val replay = Replay("legacy-sse")
        val session = replay.of("initialize")["session"] as String
        serve(replay)
        val c = mcp()
        val l = client().list(c, secrets(), Abort(), "test")
        assertEquals(McpEra.LEGACY, l.era)
        assertEquals(3, listing(l).tools.size)
        assertEquals(listOf("server/discover", "initialize", "notifications/initialized", "tools/list", "DELETE"), methods())
        assertEquals(session, seen[3].getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", seen[3].getHeader("MCP-Protocol-Version"))
        assertNull("no 2026 header to a legacy server", seen[3].getHeader("Mcp-Method"))
        assertEquals(session, seen[4].getHeader("Mcp-Session-Id"))
        seen.clear()
        val call = client().call(c, pin(listing(l), "HassTurnOff"), mapOf("name" to "kitchen"), secrets(), Abort(), "test")
        assertEquals("Turned off kitchen.", ((call.out as McpOut.Ok).value).text)
        assertEquals(McpEra.LEGACY, call.era)
        assertEquals("DELETE", methods().last())
    }

    @Test fun withoutTheTokenTheServerSaysNo() {
        serve(Replay("legacy-sse")) { _, r -> if (r.getHeader("Authorization") != "Bearer $token") MockResponse().setResponseCode(401) else null }
        val wrong = client().list(mcp(), ConnSecrets(token = "not-the-token"), Abort(), "test")
        assertEquals(401, (wrong.out as McpOut.Failed).status)
        assertEquals(listOf("server/discover"), methods())
        assertTrue(client().list(mcp(), secrets(), Abort(), "test").out is McpOut.Ok)
    }

    @Test fun aSession404StartsAgainOnceAndOnlyOnce() {
        val replay = Replay("legacy-sse")
        val listed = run { serve(replay); listing(client().list(mcp(), secrets(), Abort(), "test")) }
        var calls = 0
        seen.clear()
        serve(replay) { m, _ -> if (m == "tools/call" && ++calls == 1) MockResponse().setResponseCode(404) else null }
        val once = client().call(mcp(), pin(listed, "HassTurnOff"), mapOf("name" to "kitchen"), secrets(), Abort(), "test", reads = true)
        assertTrue(once.out is McpOut.Ok)
        assertEquals(2, methods().count { it == "initialize" })
        assertEquals(2, methods().count { it == "tools/call" })
        seen.clear()
        serve(replay) { m, _ -> if (m == "tools/call") MockResponse().setResponseCode(404) else null }
        val twice = client().call(mcp(), pin(listed, "HassTurnOff"), mapOf("name" to "kitchen"), secrets(), Abort(), "test", reads = true)
        assertEquals(McpRefusal.SESSION, (twice.out as McpOut.Refused).why)
        assertEquals(2, methods().count { it == "tools/call" })
    }

    /** fix-skills13-security 2: after a lost session's 404, a tool that may write is never sent again (it may have run). */
    @Test fun aSession404NeverSendsAWriteAgain() {
        val replay = Replay("legacy-sse")
        val listed = run { serve(replay); listing(client().list(mcp(), secrets(), Abort(), "test")) }
        var calls = 0
        seen.clear()
        serve(replay) { m, _ -> if (m == "tools/call" && ++calls == 1) MockResponse().setResponseCode(404) else null }
        val end = client().call(mcp(), pin(listed, "HassTurnOff"), mapOf("name" to "kitchen"), secrets(), Abort(), "test")
        assertEquals(McpRefusal.SESSION, (end.out as McpOut.Refused).why)
        assertTrue(end.sent)
        assertEquals(1, methods().count { it == "initialize" })
        assertEquals(1, methods().count { it == "tools/call" })
    }

    @Test fun aBrokenCallIsNeverSentAgain() {
        val replay = Replay("modern")
        val listed = run { serve(replay); listing(client().list(mcp(), secrets(), Abort(), "test")) }
        seen.clear()
        serve(replay) { m, _ -> if (m == "tools/call") MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else null }
        val end = client().call(mcp(), pin(listed, "HassTurnOff"), mapOf("name" to "kitchen"), secrets(), Abort(), "test")
        assertTrue(end.out is McpOut.Failed)
        assertTrue("it may have reached the server", end.sent)
        assertEquals(1, methods().count { it == "tools/call" })
    }

    @Test fun aWrongArgumentIsRefusedBeforeAnythingIsSent() {
        val replay = Replay("modern")
        val listed = run { serve(replay); listing(client().list(mcp(), secrets(), Abort(), "test")) }
        seen.clear()
        val end = client().call(mcp(), pin(listed, "HassTurnOff"), mapOf("name" to 5), secrets(), Abort(), "test")
        assertEquals(McpRefusal.ARGUMENTS, (end.out as McpOut.Refused).why)
        assertFalse(end.sent)
        assertTrue(seen.isEmpty())
        val extra = client().call(mcp(), pin(listed, "HassTurnOff"), mapOf("name" to "kitchen", "colour" to "red"), secrets(), Abort(), "test")
        assertEquals(McpRefusal.ARGUMENTS, (extra.out as McpOut.Refused).why)
        assertTrue(seen.isEmpty())
    }

    @Test fun aStreamHeldOpenIsClosedOnceItsAnswerCame() {
        val replay = Replay("legacy-sse")
        serve(replay) { m, r ->
            if (m != "tools/list") null else reply(replay.of(m), r).also { it.setHeader("Content-Length", it.getBody()!!.size + 10_000) }
        }
        val t0 = System.nanoTime()
        val l = client(callMs = 4_000).list(mcp(), secrets(), Abort(), "test")
        assertTrue(l.out is McpOut.Ok)
        assertTrue("read until the answer, not the timeout", (System.nanoTime() - t0) / 1_000_000 < 3_000)
    }

    @Test fun m2AChangedSchemaTurnsTheToolOff() {
        serve(Replay("modern"))
        val first = listing(client().list(mcp(), secrets(), Abort(), "test"))
        val listed = McpOps.afterList(McpToolsFile(), first.server, first.tools, first.listed).file
        val on = McpOps.set(listed, McpToolSetting("HassTurnOff", SkillLevel.ASK_FIRST, "Turn something off")).first!!
        assertEquals(listOf("HassTurnOff"), McpOps.ops(mcp(), on).map { it.id })
        seen.clear()
        serve(Replay("modern-changed"))
        val again = listing(client().list(mcp(), secrets(), Abort(), "test"))
        val after = McpOps.afterList(on, again.server, again.tools, again.listed)
        assertEquals(1, after.off)
        assertEquals(SkillLevel.OFF, after.file.setting("HassTurnOff").level)
        assertTrue(after.file.notes.any { it.tool == "HassTurnOff" })
        assertTrue(McpOps.ops(mcp(), after.file).isEmpty())
    }

    @Test fun m3LoopbackWithoutATokenIsRefusedWithOneItWorks() {
        serve(Replay("modern"))
        for (a in listOf("https://127.0.0.1:${server.port}/api/mcp", "https://localhost:${server.port}/api/mcp")) {
            val c = mcp(a, ConnAuth.None)
            assertEquals(ConnFailure.LOOPBACK_NO_KEY, ConnRules.refusal(c))
            assertEquals(ConnFailure.LOOPBACK_NO_KEY, (client().list(c, ConnSecrets(), Abort(), "test").out as McpOut.Failed).why)
        }
        // A name that leads to this phone: refused at its lookup.
        val named = mcp("https://robot-mcp.test:${server.port}/api/mcp", ConnAuth.None)
        val loop = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
        }
        assertEquals(ConnFailure.LOOPBACK_NO_KEY, (client(loop).list(named, ConnSecrets(), Abort(), "test").out as McpOut.Failed).why)
        assertTrue(seen.isEmpty())
        // With a token, over https on this phone, it works.
        assertNull(ConnRules.refusal(mcp()))
        assertTrue(client().list(mcp(), secrets(), Abort(), "test").out is McpOut.Ok)
    }

    /** Never the public internet (your answer of 4 Nov 2026): a name that resolves to a public address is refused at its lookup. */
    @Test fun aNameOnThePublicInternetIsRefusedATailnetNameIsNot() {
        serve(Replay("modern"))
        fun to(vararg a: ByteArray) = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = a.map { InetAddress.getByAddress(hostname, it) }
        }
        val public = to(byteArrayOf(1, 1, 1, 1))
        for (a in listOf("https://mcp.a-company.example/api/mcp", "https://box.tail1234.ts.net/api/mcp")) { // the second: Funnel
            val c = mcp(a)
            assertNull(ConnRules.refusal(c))
            assertEquals(a, ConnFailure.MCP_PUBLIC, (client(public).list(c, secrets(), Abort(), "test").out as McpOut.Failed).why)
        }
        assertTrue(seen.isEmpty())
        // A name that leads home (here the phone itself, so the test's server answers; its certificate names home.test) still works.
        val tailnet = mcp("https://home.test:${server.port}/api/mcp")
        assertTrue(client(to(byteArrayOf(127, 0, 0, 1))).list(tailnet, secrets(), Abort(), "test").out is McpOut.Ok)
    }

    /** Question 35 (your answer of 6 Nov 2026): every MCP connection is https with a valid certificate, token or none, but one on this phone. */
    @Test fun q35AnMcpServerOverPlainHttpIsRefusedButOnThisPhone() {
        for (a in listOf("http://100.101.102.103/api/mcp", "http://100.64.1.5:8123/api/mcp", "http://192.168.1.5:8123/api/mcp",
                "http://10.0.0.2/api/mcp", "http://[fd7a:115c:a1e0::1]/api/mcp", "http://nas.home.arpa/api/mcp"))
            for (auth in listOf(ConnAuth.Bearer, ConnAuth.None)) {
                assertEquals(a, ConnFailure.MCP_HTTP, ConnRules.refusal(mcp(a, auth)))
                assertTrue(a, ConnProblem.MCP_HTTP in ConnCheck.problems(mcp(a, auth)))
                assertFalse(a, ConnProblem.KEY_OVER_HTTP in ConnCheck.problems(mcp(a, auth)))
            }
        // Over the wire too: refused before its lookup, nothing sent; the lookup and the socket refuse plain http as well.
        serve(Replay("modern"))
        val home = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
        }
        assertEquals(ConnFailure.MCP_HTTP, (client(home).list(mcp("http://home.test:${server.port}/api/mcp"), secrets(), Abort(), "test").out as McpOut.Failed).why)
        assertTrue(seen.isEmpty())
        // localhost that leads off this phone: refused at its lookup and its socket.
        val off = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByAddress(hostname, byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))
        }
        assertEquals(ConnFailure.MCP_HTTP, (client(off).list(mcp("http://localhost:8123/api/mcp"), secrets(), Abort(), "test").out as McpOut.Failed).why)
        assertTrue(seen.isEmpty())
        val plain = CallPlace(false, phoneOnly = true)
        assertTrue(runCatching { plain.lookup(listOf(byteArrayOf(100, 101, 102, 103))) }.exceptionOrNull() is ConnRefused)
        assertTrue(runCatching { plain.lookup(listOf(byteArrayOf(127, 0, 0, 1), byteArrayOf(10, 0, 0, 2))) }.exceptionOrNull() is ConnRefused)
        plain.lookup(listOf(byteArrayOf(127, 0, 0, 1)))
        assertEquals(ConnFailure.MCP_HTTP, plain.socket(byteArrayOf(10, 0, 0, 2)))
        assertNull(plain.socket(byteArrayOf(127, 0, 0, 1)))
        // This phone over plain http is allowed, token or none, and said as not encrypted.
        for (a in listOf("http://127.0.0.1:8123/api/mcp", "http://127.8.9.1/api/mcp", "http://[::1]/api/mcp", "http://localhost:8123/api/mcp")) {
            assertNull(a, ConnRules.refusal(mcp(a)))
            assertTrue(a, ConnRules.mcpUnencrypted(mcp(a)))
            assertTrue(a, ConnProblem.MCP_HTTP !in ConnCheck.problems(mcp(a, ConnAuth.None)))
        }
        assertFalse(ConnRules.mcpUnencrypted(mcp()))
        // https with a certificate Android trusts works (a Tailscale address is no exception either way); one it doesn't is refused.
        assertNull(ConnRules.refusal(mcp("https://100.101.102.103/api/mcp")))
        assertTrue(client(home).list(mcp("https://home.test:${server.port}/api/mcp"), secrets(), Abort(), "test").out is McpOut.Ok)
        server.shutdown()
        server = MockWebServer().apply { useHttps(TlsFixture.selfSigned.socketFactory, false); start(InetAddress.getByName("127.0.0.1"), 0) }
        serve(Replay("modern"))
        assertEquals(ConnFailure.CERTIFICATE, (client().list(mcp(), secrets(), Abort(), "test").out as McpOut.Failed).why)
    }

    @Test fun d2ATokenOverHttpStaysRefusedForOtherKinds() {
        val hook = Connection("c-hook01", ConnKind.WEBHOOK, "Hook", ConnUrl.parse("http://192.168.1.5:8123/x")!!, auth = ConnAuth.Bearer)
        assertEquals(ConnFailure.KEY_OVER_HTTP, ConnRules.refusal(hook))
    }
}
