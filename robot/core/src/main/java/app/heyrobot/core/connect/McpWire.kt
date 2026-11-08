// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

// Pure. MCP over Streamable HTTP as Robot speaks it (design §8.5, 1.3 plan S3.5): the requests to send and what their
// replies mean. The client (S3.6's McpClient on ConnHttp, in :voice) sends them, adds the Bearer token, keeps the limits
// below, and cancels by closing the request's stream; it never resends a call whose stream broke (a write might have
// happened).
//
// Modern first: the 2026-07-28 revision. Every request is one POST whose params carry `_meta` with
// io.modelcontextprotocol/protocolVersion, …/clientCapabilities (none) and …/clientInfo, mirrored in the
// MCP-Protocol-Version and Mcp-Method headers, and Mcp-Name for tools/call (SEP-2243). server/discover picks the era:
// its supportedVersions holds 2026-07-28 (modern), or only versions of the initialize era (legacy), or neither (refused:
// an unknown era). A 4xx other than 401, 403 and 429, a JSON-RPC error, or an answer that isn't a discover result means
// a server of the initialize era, as the reference SDK reads a probe; only -32022 (unsupported version) with a list of
// versions of neither era is refused.
//
// Legacy fallback: the initialize era (2025-03-26 to 2025-11-25): initialize, notifications/initialized, then
// Mcp-Session-Id (visible ASCII only) and MCP-Protocol-Version on every request, and DELETE at the end. No 2026 header
// goes to a legacy server.
//
// Never: the HTTP+SSE transport of 2024-11-05 (its `endpoint` event, or a server that answers initialize with that
// version, is refused), sampling, roots, elicitation, logging, subscriptions/listen, tasks or batches. Robot declares no
// capability, so a request from the server on a reply's stream (sampling/createMessage, roots/list, elicitation/create,
// even ping) ends the call refused, as does a result that wants more input (resultType input_required: "The server asked
// for more information, which Robot can't give yet."), a task, or a 2026 result without resultType. Notifications on a
// stream (progress, log lines) are dropped unread.
//
// Results are content: only text blocks are kept, cleaned and cut to 1,500 characters (ConnReply.clean); images, audio,
// resource links and embedded resources are dropped, never followed. structuredContent is kept only when the tool has an
// outputSchema and fits it (the spec's SHOULD); a tool with one whose result doesn't fit is refused. A call's arguments are
// checked against the pinned inputSchema before anything is built (SchemaCheck, strict).

enum class McpEra(val id: String) { MODERN("modern"), LEGACY("legacy") }

/** A server as Robot found it: its [era] and [version], what it says it is, and a legacy [session]. */
data class McpServer(val era: McpEra, val version: String, val id: McpServerId?, val session: String? = null) {
    override fun toString(): String = "McpServer(${era.id} $version)" // l10n: not text (toString, for the logs; never the session)
}

/** Why Robot refused what a server did. [code]: what the voice log may keep. */
enum class McpRefusal(val code: Int) {
    UNKNOWN_ERA(1), OLD_TRANSPORT(2), NOT_MCP(3), NO_TOOLS(4), INPUT_REQUIRED(5), SERVER_ASKED(6), TASK(7), RESULT(8), OUTPUT(9),
    ARGUMENTS(10), SESSION(11), TOO_MANY(12), ERROR(13);

    /** What Robot says, with your name for the server: never the server's own words. [rpc]: a JSON-RPC error's code. */
    fun text(name: String, rpc: Long? = null): Phrase {
        val n = "name" to name
        return when (this) {
            UNKNOWN_ERA, OLD_TRANSPORT -> ConnStrings.mcpEra(n)
            NO_TOOLS -> ConnStrings.mcpNoTools(n)
            INPUT_REQUIRED, SERVER_ASKED -> ConnStrings.mcpMoreInput()
            ARGUMENTS -> ConnStrings.badValue(n)
            ERROR -> ConnStrings.saidNo(n, "code" to (rpc ?: 0L).toString())
            NOT_MCP, TASK, RESULT, OUTPUT, SESSION, TOO_MANY -> ConnStrings.unreadable(n)
        }
    }
}

/** What one exchange gave: a value, a failure of the connection (as ConnReply reads HTTP), or a refusal. */
sealed class McpOut<out T> {
    data class Ok<T>(val value: T) : McpOut<T>()
    data class Failed(val why: ConnFailure, val status: Int? = null) : McpOut<Nothing>()
    /** [rpc]: a JSON-RPC error's code; [wrong]: the arguments that didn't fit, for [McpRefusal.ARGUMENTS]. */
    data class Refused(val why: McpRefusal, val rpc: Long? = null, val wrong: List<SchemaCheck.Wrong> = emptyList()) : McpOut<Nothing>()
}

/** One HTTP request to the server's address: [verb] POST or DELETE, [body] its JSON. The client adds the token. */
class McpRequest(val verb: String, val method: String?, val id: Long?, val body: String?, val headers: List<Pair<String, String>>) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    override fun toString(): String = "McpRequest($verb ${method.orEmpty()})" // l10n: not text (toString, for the logs)
}

/** What server/discover's reply means: the modern era, the legacy one (initialize next), or stop. */
sealed class McpProbe {
    data class Modern(val server: McpServer) : McpProbe()
    data object Legacy : McpProbe()
    data class Stop(val out: McpOut<Nothing>) : McpProbe()
}

/** One page of tools/list: its raw tools (McpTools reads them) and the next page's cursor. */
class McpPage(val tools: List<Any?>, val next: String?)

/** A tool's result as Robot keeps it: [text] cleaned and cut, [structured] only when it fit, [failed] isError. */
data class McpCalled(val text: String, val structured: Any?, val failed: Boolean, val dropped: Int)

/** A reply's body read as Server-Sent Events, chunk by chunk (the client feeds what it read). */
class McpSse {
    data class Event(val type: String, val data: String)

    private val line = StringBuilder()
    private val data = StringBuilder()
    private var hasData = false
    private var type = ""
    private var cr = false
    private var first = true

    /** The events [chunk] completes. An event ends at a blank line; one the stream never ends is never given. */
    fun feed(chunk: String): List<Event> {
        val out = ArrayList<Event>()
        for (c in chunk) {
            if (first) {
                first = false
                if (c == '﻿') continue
            }
            if (cr && c == '\n') {
                cr = false
                continue
            }
            cr = c == '\r'
            if (c == '\r' || c == '\n') endLine(out) else line.append(c)
        }
        return out
    }

    private fun endLine(out: MutableList<Event>) {
        val l = line.toString()
        line.setLength(0)
        if (l.isEmpty()) {
            if (hasData) out += Event(type.ifEmpty { "message" }, data.toString()) // l10n: not text (SSE's event type)
            data.setLength(0)
            hasData = false
            type = ""
            return
        }
        if (l.startsWith(":")) return
        val i = l.indexOf(':')
        val field = if (i < 0) l else l.substring(0, i)
        val value = (if (i < 0) "" else l.substring(i + 1)).removePrefix(" ")
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> type = value
        }
    }
}

object McpWire {
    const val MODERN = "2026-07-28"
    /** The initialize era, newest first: initialize asks for the first. */
    val LEGACY = listOf("2025-11-25", "2025-06-18", "2025-03-26")
    /** The HTTP+SSE transport's version: never. */
    const val OLD = "2024-11-05"
    const val LIST_MS = 10_000L
    const val CALL_MS = 30_000L
    const val MAX_BYTES = ConnReply.MAX_BYTES
    const val MAX_PAGES = 10
    const val UNSUPPORTED_VERSION = -32022L
    const val META_VERSION = "io.modelcontextprotocol/protocolVersion" // l10n: not text (the spec's keys, as below)
    const val META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities" // l10n: not text
    const val META_CLIENT = "io.modelcontextprotocol/clientInfo" // l10n: not text
    const val META_SERVER = "io.modelcontextprotocol/serverInfo" // l10n: not text
    private const val CLIENT = "Robot" // l10n: not text (clientInfo's name)
    private val SESSION = Regex("^[\\x21-\\x7E]{1,256}$")
    private val BASE = listOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream") // l10n: not text (headers)

    // Requests.

    fun discover(id: Long, robot: String): McpRequest = modern("server/discover", id, emptyMap(), robot, null)

    fun initialize(id: Long, robot: String): McpRequest = post("initialize", id, mapOf("protocolVersion" to LEGACY[0], // l10n: not text (the spec's keys)
        "capabilities" to emptyMap<String, Any?>(), "clientInfo" to client(robot)), BASE)

    fun initialized(server: McpServer): McpRequest = McpRequest("POST", "notifications/initialized", null,
        ConnJson.write(mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized")), legacy(server)) // l10n: not text (JSON-RPC)

    fun list(server: McpServer, id: Long, cursor: String?, robot: String): McpRequest {
        val params = if (cursor != null) mapOf("cursor" to cursor) else emptyMap()
        return if (server.era == McpEra.MODERN) modern("tools/list", id, params, robot, null) else post("tools/list", id, params, legacy(server))
    }

    /** tools/call for [pin] with [args], or refused before anything is built: [args] don't fit the pinned inputSchema. */
    fun call(server: McpServer, id: Long, pin: McpPin, args: Map<String, Any?>, robot: String): McpOut<McpRequest> {
        val wrong = SchemaCheck.check(pin.input, args, strict = true)
        if (wrong.isNotEmpty()) return McpOut.Refused(McpRefusal.ARGUMENTS, wrong = wrong)
        val declared = McpPolicy.headers(pin.input) ?: return McpOut.Refused(McpRefusal.ARGUMENTS)
        val params = mapOf("name" to pin.name, "arguments" to args)
        return McpOut.Ok(if (server.era == McpEra.MODERN) modern("tools/call", id, params, robot, pin.name, McpPolicy.paramHeaders(declared, args))
            else post("tools/call", id, params, legacy(server)))
    }

    /** The legacy session's end, or null when there's none to end. */
    fun end(server: McpServer): McpRequest? = server.session?.let { McpRequest("DELETE", null, null, null, listOf("Mcp-Session-Id" to it, "MCP-Protocol-Version" to server.version)) }

    private fun client(robot: String) = mapOf("name" to CLIENT, "version" to robot)

    private fun modern(method: String, id: Long, params: Map<String, Any?>, robot: String, name: String?,
        more: List<Pair<String, String>> = emptyList()): McpRequest {
        val meta = mapOf(META_VERSION to MODERN, META_CAPABILITIES to emptyMap<String, Any?>(), META_CLIENT to client(robot))
        val headers = BASE + listOf("MCP-Protocol-Version" to MODERN, "Mcp-Method" to method) + // l10n: not text (headers)
            listOfNotNull(name?.let { "Mcp-Name" to McpPolicy.headerValue(it) }) + more
        return post(method, id, params + ("_meta" to meta), headers)
    }

    private fun legacy(server: McpServer): List<Pair<String, String>> =
        BASE + ("MCP-Protocol-Version" to server.version) + listOfNotNull(server.session?.let { "Mcp-Session-Id" to it }) // l10n: not text (headers)

    private fun post(method: String, id: Long, params: Map<String, Any?>, headers: List<Pair<String, String>>): McpRequest =
        McpRequest("POST", method, id, ConnJson.write(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)), headers) // l10n: not text (JSON-RPC)

    // Replies.

    /** JSON-RPC's answer to one request: its result, its error, or why the reply isn't one. */
    private sealed class Rpc {
        class Result(val value: Map<String, Any?>) : Rpc()
        class Error(val code: Long?, val data: Any?) : Rpc()
        class Bad(val out: McpOut<Nothing>) : Rpc()
    }

    /** The JSON-RPC messages in a body: one JSON object, or the events of a stream. */
    fun messages(contentType: String?, body: ByteArray): McpOut<List<Any?>> {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return when (type) {
            "application/json" -> {
                val v = StrictJson.parse(body) ?: return McpOut.Refused(McpRefusal.NOT_MCP)
                if (v.value is Map<*, *>) McpOut.Ok(listOf(v.value)) else McpOut.Refused(McpRefusal.NOT_MCP)
            }
            "text/event-stream" -> {
                val text = utf8(body) ?: return McpOut.Refused(McpRefusal.NOT_MCP)
                val out = ArrayList<Any?>()
                for (e in McpSse().feed(text)) when (e.type) {
                    "endpoint" -> return McpOut.Refused(McpRefusal.OLD_TRANSPORT)
                    "message" -> out += (StrictJson.parse(e.data) ?: return McpOut.Refused(McpRefusal.NOT_MCP)).value
                }
                McpOut.Ok(out)
            }
            else -> McpOut.Failed(ConnFailure.UNREADABLE)
        }
    }

    private fun utf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun rpc(messages: List<Any?>, id: Long): Rpc {
        for (m in messages) {
            if (m !is Map<*, *> || m["jsonrpc"] != "2.0") return Rpc.Bad(McpOut.Refused(McpRefusal.NOT_MCP))
            val method = m["method"]
            if (method != null) {
                if (!m.containsKey("id")) continue // a notification: dropped unread
                return Rpc.Bad(McpOut.Refused(if (method == "elicitation/create") McpRefusal.INPUT_REQUIRED else McpRefusal.SERVER_ASKED))
            }
            // An error may carry a null id when the server couldn't read the request's (JSON-RPC 2.0 §5).
            if (m["id"] != id && !(m["id"] == null && m.containsKey("error"))) return Rpc.Bad(McpOut.Refused(McpRefusal.NOT_MCP))
            (m["result"] as? Map<*, *>)?.let { r -> return if (r.keys.all { it is String }) Rpc.Result(r as Map<String, Any?>) else Rpc.Bad(McpOut.Refused(McpRefusal.NOT_MCP)) }
            val e = m["error"] as? Map<*, *> ?: return Rpc.Bad(McpOut.Refused(McpRefusal.NOT_MCP))
            return Rpc.Error(e["code"] as? Long, e["data"])
        }
        return Rpc.Bad(McpOut.Refused(McpRefusal.NOT_MCP))
    }

    private fun answer(status: Int, contentType: String?, body: ByteArray, over: Boolean, id: Long, session: Boolean): Rpc {
        ConnReply.failure(status)?.let { return Rpc.Bad(if (status == 404 && session) McpOut.Refused(McpRefusal.SESSION) else McpOut.Failed(it, status)) }
        if (over || body.size > MAX_BYTES) return Rpc.Bad(McpOut.Failed(ConnFailure.UNREADABLE, status))
        return when (val m = messages(contentType, body)) {
            is McpOut.Ok -> rpc(m.value, id)
            is McpOut.Failed -> Rpc.Bad(m)
            is McpOut.Refused -> Rpc.Bad(m)
        }
    }

    /** A request's result, refused when it's an error or the reply isn't its answer. [server]: null for initialize. */
    private fun result(server: McpServer?, status: Int, contentType: String?, body: ByteArray, over: Boolean, id: Long): McpOut<Map<String, Any?>> =
        when (val r = answer(status, contentType, body, over, id, server?.session != null)) {
            is Rpc.Result -> McpOut.Ok(r.value)
            is Rpc.Error -> McpOut.Refused(McpRefusal.ERROR, r.code)
            is Rpc.Bad -> r.out
        }

    /** What server/discover's reply ([id]) says about the era. */
    fun probe(status: Int, contentType: String?, body: ByteArray, over: Boolean, id: Long): McpProbe {
        if (status == 401 || status == 403 || status == 429 || status >= 500 || status in 300..399)
            return McpProbe.Stop(McpOut.Failed(ConnReply.failure(status) ?: ConnFailure.SAID_NO, status))
        if (over || body.size > MAX_BYTES) return McpProbe.Stop(McpOut.Failed(ConnFailure.UNREADABLE, status))
        val m = messages(contentType, body)
        if (m is McpOut.Refused && m.why == McpRefusal.OLD_TRANSPORT) return McpProbe.Stop(m)
        val r = (m as? McpOut.Ok)?.let { rpc(it.value, id) } ?: return McpProbe.Legacy
        if (status !in 200..299) return if (r is Rpc.Error) era(r) else McpProbe.Legacy
        return when (r) {
            is Rpc.Result -> discovered(r.value)
            is Rpc.Error -> era(r)
            is Rpc.Bad -> if ((r.out as? McpOut.Refused)?.why in setOf(McpRefusal.INPUT_REQUIRED, McpRefusal.SERVER_ASKED)) McpProbe.Stop(r.out) else McpProbe.Legacy
        }
    }

    private fun era(e: Rpc.Error): McpProbe {
        if (e.code != UNSUPPORTED_VERSION) return McpProbe.Legacy
        val supported = ((e.data as? Map<*, *>)?.get("supported") as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is String } } ?: return McpProbe.Legacy
        return if (supported.any { it in LEGACY }) McpProbe.Legacy else McpProbe.Stop(McpOut.Refused(McpRefusal.UNKNOWN_ERA))
    }

    private fun discovered(r: Map<String, Any?>): McpProbe {
        val versions = (r["supportedVersions"] as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is String } } ?: return McpProbe.Legacy
        if (MODERN !in versions) return if (versions.any { it in LEGACY }) McpProbe.Legacy else McpProbe.Stop(McpOut.Refused(McpRefusal.UNKNOWN_ERA))
        val caps = r["capabilities"] as? Map<*, *> ?: return McpProbe.Legacy
        if (caps["tools"] !is Map<*, *>) return McpProbe.Stop(McpOut.Refused(McpRefusal.NO_TOOLS))
        return McpProbe.Modern(McpServer(McpEra.MODERN, MODERN, serverId((r["_meta"] as? Map<*, *>)?.get(META_SERVER))))
    }

    private fun serverId(v: Any?): McpServerId? {
        val m = v as? Map<*, *> ?: return null
        val name = m["name"] as? String ?: return null
        return McpServerId(name, m["version"] as? String ?: "")
    }

    /** initialize's reply: the server of the era it chose, with its session ([sessionHeader]: Mcp-Session-Id, if any). */
    fun initializeReply(status: Int, contentType: String?, body: ByteArray, over: Boolean, id: Long, sessionHeader: String?): McpOut<McpServer> {
        val r = when (val o = result(null, status, contentType, body, over, id)) {
            is McpOut.Ok -> o.value
            is McpOut.Failed -> return o
            is McpOut.Refused -> return o
        }
        val v = r["protocolVersion"] as? String
        if (v == OLD) return McpOut.Refused(McpRefusal.OLD_TRANSPORT)
        if (v !in LEGACY) return McpOut.Refused(McpRefusal.UNKNOWN_ERA)
        if ((r["capabilities"] as? Map<*, *>)?.get("tools") !is Map<*, *>) return McpOut.Refused(McpRefusal.NO_TOOLS)
        if (sessionHeader != null && !SESSION.matches(sessionHeader)) return McpOut.Refused(McpRefusal.SESSION)
        return McpOut.Ok(McpServer(McpEra.LEGACY, v!!, serverId(r["serverInfo"]), sessionHeader))
    }

    /** Why a result isn't a finished one: it wants more input, it's a task, or (2026) it doesn't say it's complete. */
    private fun unfinished(server: McpServer, r: Map<String, Any?>): McpRefusal? = when {
        r["task"] is Map<*, *> -> McpRefusal.TASK
        r["resultType"] == "input_required" || r.containsKey("inputRequests") -> McpRefusal.INPUT_REQUIRED
        r["resultType"] == "complete" -> null
        r["resultType"] == null && server.era == McpEra.LEGACY -> null
        else -> McpRefusal.RESULT
    }

    fun listReply(server: McpServer, status: Int, contentType: String?, body: ByteArray, over: Boolean, id: Long): McpOut<McpPage> {
        val r = when (val o = result(server, status, contentType, body, over, id)) {
            is McpOut.Ok -> o.value
            is McpOut.Failed -> return o
            is McpOut.Refused -> return o
        }
        unfinished(server, r)?.let { return McpOut.Refused(it) }
        val tools = r["tools"] as? List<*> ?: return McpOut.Refused(McpRefusal.RESULT)
        return McpOut.Ok(McpPage(tools, McpTools.next(r)))
    }

    /** tools/call's reply for [pin]: its text and structured content as Robot keeps them, or refused. */
    fun callReply(server: McpServer, pin: McpPin, status: Int, contentType: String?, body: ByteArray, over: Boolean, id: Long): McpOut<McpCalled> {
        val r = when (val o = result(server, status, contentType, body, over, id)) {
            is McpOut.Ok -> o.value
            is McpOut.Failed -> return o
            is McpOut.Refused -> return o
        }
        unfinished(server, r)?.let { return McpOut.Refused(it) }
        val content = (r["content"] ?: emptyList<Any?>()) as? List<*> ?: return McpOut.Refused(McpRefusal.RESULT)
        val texts = ArrayList<String>()
        var dropped = 0
        for (b in content) {
            val block = b as? Map<*, *> ?: return McpOut.Refused(McpRefusal.RESULT)
            when (block["type"] as? String ?: return McpOut.Refused(McpRefusal.RESULT)) {
                "text" -> texts += block["text"] as? String ?: return McpOut.Refused(McpRefusal.RESULT)
                else -> dropped++ // image, audio, resource_link, resource: never followed, never kept
            }
        }
        val isError = r["isError"]
        if (isError != null && isError !is Boolean) return McpOut.Refused(McpRefusal.RESULT)
        val failed = isError == true
        var structured: Any? = null
        if (!failed && pin.output != null) {
            if (!r.containsKey("structuredContent") || SchemaCheck.check(pin.output, r["structuredContent"], strict = false).isNotEmpty())
                return McpOut.Refused(McpRefusal.OUTPUT)
            structured = r["structuredContent"]
        }
        return McpOut.Ok(McpCalled(ConnReply.clean(texts.joinToString("\n")), structured, failed, dropped))
    }

    /** What Robot says when a tool answered that it failed (isError): never the server's words. */
    fun failedText(name: String): Phrase = ConnStrings.mcpToolFailed("name" to name)
}
