// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Replies recorded from the MCP SDK's own server, and a few written by hand in the same shapes (resources/mcp/SOURCES.txt).
class McpWireTest {
    private fun bytes(name: String) = javaClass.classLoader!!.getResourceAsStream("mcp/$name")!!.readBytes()
    private fun edit(name: String, old: String, new: String): ByteArray {
        val s = String(bytes(name), Charsets.UTF_8)
        assertTrue(old in s)
        return s.replace(old, new).toByteArray(Charsets.UTF_8)
    }
    private val json = "application/json"
    private val sse = "text/event-stream"
    private val modern = McpServer(McpEra.MODERN, McpWire.MODERN, McpServerId("weather-demo", "1.0.0"))
    private val legacy = McpServer(McpEra.LEGACY, "2025-11-25", McpServerId("weather-demo", "1.0.0"), "a1b2-c3")

    private fun page(server: McpServer, name: String, type: String): McpPage =
        (McpWire.listReply(server, 200, type, bytes(name), false, 2) as McpOut.Ok).value
    private fun pin(tool: String = "get_weather") = McpPolicy.pin(McpTools.read(listOf(page(modern, "list.json", json).tools))!!.tool(tool)!!)
    private fun refused(o: McpOut<*>): McpRefusal = (o as McpOut.Refused).why
    private fun stop(p: McpProbe): McpOut<Nothing> = (p as McpProbe.Stop).out
    private fun body(r: McpRequest) = StrictJson.parse(r.body!!)!!.value as Map<*, *>

    @Test fun modernDiscoverPicksTheModernEra() {
        val p = McpWire.probe(200, json, bytes("discover.json"), false, 1) as McpProbe.Modern
        assertEquals(McpServer(McpEra.MODERN, McpWire.MODERN, McpServerId("weather-demo", "1.0.0")), p.server)
        val noTools = edit("discover.json", "{\"tools\":{\"listChanged\":true}}", "{}")
        assertEquals(McpRefusal.NO_TOOLS, refused(stop(McpWire.probe(200, json, noTools, false, 1))))
    }

    @Test fun aServerOfTheInitializeEraFallsBack() {
        assertEquals(McpProbe.Legacy, McpWire.probe(400, json, bytes("legacy-probe-400.json"), false, 1))
        assertEquals(McpProbe.Legacy, McpWire.probe(404, "text/html", "<h1>Not found</h1>".toByteArray(), false, 1))
        assertEquals(McpProbe.Legacy, McpWire.probe(200, json, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".toByteArray(), false, 1))
        assertEquals(McpProbe.Legacy, McpWire.probe(400, json, edit("discover-unsupported.json", "[\"2026-07-28\"]", "[\"2025-06-18\"]"), false, 1))
    }

    @Test fun anEraRobotDoesntKnowIsRefused() {
        assertEquals(McpRefusal.UNKNOWN_ERA, refused(stop(McpWire.probe(400, json, bytes("discover-unsupported.json"), false, 1))))
        val later = edit("discover.json", "[\"2026-07-28\"]", "[\"2027-03-01\"]")
        assertEquals(McpRefusal.UNKNOWN_ERA, refused(stop(McpWire.probe(200, json, later, false, 1))))
        assertEquals("Your Weather station uses a version of MCP that Robot can't use.", McpRefusal.UNKNOWN_ERA.text("Weather station").en())
    }

    @Test fun keyAndServerFailuresStopTheProbe() {
        assertEquals(McpOut.Failed(ConnFailure.KEY_REFUSED, 401), stop(McpWire.probe(401, json, ByteArray(0), false, 1)))
        assertEquals(McpOut.Failed(ConnFailure.SAID_NO, 503), stop(McpWire.probe(503, json, ByteArray(0), false, 1)))
        assertEquals(McpOut.Failed(ConnFailure.UNREADABLE, 200), stop(McpWire.probe(200, json, bytes("discover.json"), true, 1)))
    }

    @Test fun theOldTransportIsNeverUsed() {
        assertEquals(McpRefusal.OLD_TRANSPORT, refused(stop(McpWire.probe(200, sse, bytes("old-transport.sse"), false, 1))))
        val old = edit("legacy-initialize.sse", "2025-11-25", McpWire.OLD)
        assertEquals(McpRefusal.OLD_TRANSPORT, refused(McpWire.initializeReply(200, sse, old, false, 1, null)))
    }

    @Test fun legacyInitializeGivesASession() {
        assertEquals(legacy, (McpWire.initializeReply(200, sse, bytes("legacy-initialize.sse"), false, 1, "a1b2-c3") as McpOut.Ok).value)
        assertEquals(McpRefusal.SESSION, refused(McpWire.initializeReply(200, sse, bytes("legacy-initialize.sse"), false, 1, "a1 b2")))
        val noTools = edit("legacy-initialize.sse", "{\"tools\":{\"listChanged\":true}}", "{}")
        assertEquals(McpRefusal.NO_TOOLS, refused(McpWire.initializeReply(200, sse, noTools, false, 1, null)))
    }

    @Test fun modernRequestsSayTheirVersionInMetaAndHeaders() {
        val r = McpWire.discover(1, "1.3.0")
        assertEquals("POST", r.verb)
        assertEquals(McpWire.MODERN, r.header("mcp-protocol-version"))
        assertEquals("server/discover", r.header("Mcp-Method"))
        assertNull(r.header("Mcp-Session-Id"))
        val meta = (body(r)["params"] as Map<*, *>)["_meta"] as Map<*, *>
        assertEquals(McpWire.MODERN, meta[McpWire.META_VERSION])
        assertEquals(emptyMap<String, Any?>(), meta[McpWire.META_CAPABILITIES])
        assertEquals(mapOf("name" to "Robot", "version" to "1.3.0"), meta[McpWire.META_CLIENT])
    }

    @Test fun noModernHeaderGoesToALegacyServer() {
        val r = McpWire.list(legacy, 2, "c1", "1.3.0")
        assertEquals("2025-11-25", r.header("MCP-Protocol-Version"))
        assertEquals("a1b2-c3", r.header("Mcp-Session-Id"))
        assertNull(r.header("Mcp-Method"))
        assertEquals(mapOf("cursor" to "c1"), body(r)["params"])
        val init = McpWire.initialize(1, "1.3.0")
        assertNull(init.header("MCP-Protocol-Version"))
        assertEquals(McpWire.LEGACY[0], (body(init)["params"] as Map<*, *>)["protocolVersion"])
        assertEquals(listOf("Mcp-Session-Id" to "a1b2-c3", "MCP-Protocol-Version" to "2025-11-25"), McpWire.end(legacy)!!.headers)
        assertNull(McpWire.end(modern))
    }

    @Test fun aWrongArgumentIsRefusedBeforeSending() {
        val p = pin()
        fun wrong(args: Map<String, Any?>) = (McpWire.call(modern, 3, p, args, "1.3.0") as McpOut.Refused).also { assertEquals(McpRefusal.ARGUMENTS, it.why) }.wrong
        assertEquals(listOf(SchemaCheck.Wrong("/city", SchemaCheck.Problem.TYPE)), wrong(mapOf("city" to 12L)))
        assertEquals(listOf(SchemaCheck.Wrong("/city", SchemaCheck.Problem.MISSING)), wrong(emptyMap()))
        assertEquals(listOf(SchemaCheck.Wrong("/city", SchemaCheck.Problem.SHORT)), wrong(mapOf("city" to "")))
        assertEquals(listOf(SchemaCheck.Wrong("/units", SchemaCheck.Problem.NOT_ALLOWED)), wrong(mapOf("city" to "Halifax", "units" to "kelvin")))
        assertEquals(listOf(SchemaCheck.Wrong("/days", SchemaCheck.Problem.UNKNOWN)), wrong(mapOf("city" to "Halifax", "days" to 3L)))
        val ok = (McpWire.call(modern, 3, p, mapOf("city" to "Halifax"), "1.3.0") as McpOut.Ok).value
        assertEquals("get_weather", ok.header("Mcp-Name"))
        assertEquals("tools/call", ok.header("Mcp-Method"))
        assertEquals(mapOf("name" to "get_weather", "arguments" to mapOf("city" to "Halifax")), (body(ok)["params"] as Map<*, *>) - "_meta")
    }

    @Test fun listingsAreReadInBothEras() {
        val m = page(modern, "list.json", json)
        assertEquals(2, m.tools.size)
        assertNull(m.next)
        assertEquals(listOf("get_weather", "add_note"), McpTools.read(listOf(page(legacy, "legacy-list.sse", sse).tools))!!.tools.map { it.name })
        val bare = edit("list.json", "\"resultType\":\"complete\",", "")
        assertEquals(McpRefusal.RESULT, refused(McpWire.listReply(modern, 200, json, bare, false, 2)))
        assertEquals(McpRefusal.NOT_MCP, refused(McpWire.listReply(modern, 200, json, bytes("list.json"), false, 9)))
        assertEquals(McpRefusal.SESSION, refused(McpWire.listReply(legacy, 404, json, ByteArray(0), false, 2)))
        assertEquals(McpOut.Failed(ConnFailure.UNREADABLE, 200), McpWire.listReply(modern, 200, json, bytes("list.json"), true, 2))
    }

    @Test fun callResultsKeepOnlyText() {
        val w = (McpWire.callReply(modern, pin(), 200, json, bytes("call-weather.json"), false, 3) as McpOut.Ok).value
        assertEquals(McpCalled("It is 12 degrees and cloudy in Halifax.", mapOf("temperature" to 12L, "conditions" to "cloudy"), false, 0), w)
        val n = (McpWire.callReply(modern, pin("add_note"), 200, json, bytes("call-note.json"), false, 4) as McpOut.Ok).value
        assertEquals(McpCalled("Saved.", null, false, 1), n)
        val bad = (McpWire.callReply(modern, pin(), 200, json, bytes("call-invalid.json"), false, 5) as McpOut.Ok).value
        assertTrue(bad.failed)
        assertNull(bad.structured)
        assertEquals("Your Weather station couldn't do that.", McpWire.failedText("Weather station").en())
        assertEquals(McpRefusal.OUTPUT, refused(McpWire.callReply(modern, pin(), 200, json, bytes("output-mismatch.json"), false, 3)))
    }

    @Test fun whatRobotNeverDoes() {
        assertEquals(McpRefusal.INPUT_REQUIRED, refused(McpWire.callReply(modern, pin(), 200, json, bytes("input-required.json"), false, 3)))
        assertEquals("The server asked for more information, which Robot can't give yet.", McpRefusal.INPUT_REQUIRED.text("Weather station").en())
        assertEquals(McpRefusal.SERVER_ASKED, refused(McpWire.callReply(legacy, pin(), 200, sse, bytes("sampling.sse"), false, 3)))
        assertEquals(McpRefusal.INPUT_REQUIRED, refused(McpWire.callReply(legacy, pin(), 200, sse, bytes("elicitation.sse"), false, 3)))
        assertEquals(McpRefusal.TASK, refused(McpWire.callReply(legacy, pin(), 200, json, bytes("task.json"), false, 3)))
        val batch = "[" + String(bytes("call-weather.json"), Charsets.UTF_8) + "]"
        assertEquals(McpRefusal.NOT_MCP, refused(McpWire.callReply(modern, pin(), 200, json, batch.toByteArray(), false, 3)))
        val w = McpWire.callReply(legacy, pin(), 200, sse, bytes("progress-then-result.sse"), false, 3) as McpOut.Ok
        assertEquals("It is 12 degrees and cloudy in Halifax.", w.value.text)
    }

    @Test fun streamsAreReadAcrossChunks() {
        val p = McpSse()
        assertEquals(emptyList<McpSse.Event>(), p.feed(": ping\r\nevent: message\r\ndata: {\"a\":"))
        assertEquals(emptyList<McpSse.Event>(), p.feed("1}\r"))
        assertEquals(listOf(McpSse.Event("message", "{\"a\":1}\nx")), p.feed("\ndata:x\r\n\r\n"))
        assertEquals(listOf(McpSse.Event("message", "y")), p.feed("id: 4\ndata: y\n\n"))
    }
}
