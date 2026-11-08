// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.connect

import app.heyrobot.core.Abort
import app.heyrobot.core.connect.BodyPart
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HttpMethod
import app.heyrobot.core.connect.HttpRequest
import app.heyrobot.core.connect.McpCallEnd
import app.heyrobot.core.connect.McpCalled
import app.heyrobot.core.connect.McpEra
import app.heyrobot.core.connect.McpListed
import app.heyrobot.core.connect.McpOut
import app.heyrobot.core.connect.McpPin
import app.heyrobot.core.connect.McpProbe
import app.heyrobot.core.connect.McpRefusal
import app.heyrobot.core.connect.McpRequest
import app.heyrobot.core.connect.McpServer
import app.heyrobot.core.connect.McpTools
import app.heyrobot.core.connect.McpWire
import app.heyrobot.core.connect.ReqBody
import app.heyrobot.core.connect.SchemaCheck
import app.heyrobot.core.online.NetCause

// :voice only (1.3, design §8.5; S3.6). An MCP server's tools, listed or called, over ConnHttp.exchange: every rule a
// connection's call keeps (D1's home and socket checks, D2 with MCP's one exception, no redirects, no cookies, a pool of
// its own, What went online) holds for each exchange. McpWire builds the requests and reads the replies; this only sends
// them, in order:
//
// - server/discover first (the 2026-07-28 era); a server of the initialize era gets initialize, notifications/initialized,
//   and its Mcp-Session-Id on every request after, then DELETE at the end, whatever happened (best effort, 2 s).
// - Limits: 10 s for a whole listing (at most 10 pages), 30 s for a whole call, 256 KiB a reply. A stream's reply is read
//   until its answer has come; the stream is then closed, which is how a call is cancelled too (Stop closes it).
// - A call is never sent twice, with one exception the spec makes: a 404 for a legacy session means the server never took
//   the call, so a new session starts, once, and the call goes again in it.

/** A server's tools as listed: [server] as found, [tools] the raw tools (kept for their pins), [listed] as Robot reads them. */
class McpListing(val server: McpServer, val tools: List<Map<String, Any?>>, val listed: McpListed) {
    override fun toString(): String = "McpListing($server, ${tools.size})" // l10n: not text (toString, for the logs)
}

/** How a listing ended: [out] null when Stop came; [era] the server's, once known. */
class McpListEnd(val out: McpOut<McpListing>?, val era: McpEra?, val ms: Long, val where: ConnWhere? = null) {
    override fun toString(): String = "McpListEnd(${era?.id})" // l10n: not text (toString, for the logs)
}

class McpClient internal constructor(private val http: ConnHttp, private val listMs: Long, private val callMs: Long) {
    constructor() : this(ConnHttp(), McpWire.LIST_MS, McpWire.CALL_MS)

    /** The server's tools (Test, a tap). Blocks: a background thread. Never throws. */
    fun list(c: Connection, secrets: ConnSecrets, abort: Abort, robot: String, cause: NetCause = NetCause.TAP): McpListEnd {
        val s = Session(c, secrets, abort, robot, cause, listMs)
        try {
            var server = when (val o = s.open()) {
                is Opened.Server -> o.server
                is Opened.Ended -> return McpListEnd(o.out, s.era, s.ms())
            }
            var again = true
            while (true) {
                val pages = ArrayList<List<Any?>>()
                var cursor: String? = null
                var refused: McpOut<McpListing>? = null
                for (n in 0 until McpWire.MAX_PAGES) {
                    val id = s.next()
                    val got = when (val x = s.send(McpWire.list(server, id, cursor, robot), id)) {
                        is ConnExchange.Got -> x
                        is ConnExchange.Ended -> return McpListEnd(s.out(x.call), s.era, s.ms())
                    }
                    when (val p = McpWire.listReply(server, got.status, got.contentType, got.bytes, got.over, id)) {
                        is McpOut.Ok -> { pages += p.value.tools; cursor = p.value.next }
                        is McpOut.Failed -> return McpListEnd(p, s.era, s.ms())
                        is McpOut.Refused -> { refused = p; break }
                    }
                    if (cursor == null) break
                }
                if (refused is McpOut.Refused && refused.why == McpRefusal.SESSION && again && server.session != null) {
                    again = false
                    server = when (val o = s.legacy()) {
                        is Opened.Server -> o.server
                        is Opened.Ended -> return McpListEnd(o.out, s.era, s.ms())
                    }
                    continue
                }
                if (refused != null) return McpListEnd(refused, s.era, s.ms())
                if (cursor != null) return McpListEnd(McpOut.Refused(McpRefusal.TOO_MANY), s.era, s.ms())
                val listed = McpTools.read(pages) ?: return McpListEnd(McpOut.Refused(McpRefusal.TOO_MANY), s.era, s.ms())
                return McpListEnd(McpOut.Ok(McpListing(server, pages.flatten().mapNotNull(::tool), listed)), s.era, s.ms(), s.where)
            }
        } finally {
            s.close()
        }
    }

    /**
     * Calls [pin]'s tool with [args] (checked against its pinned inputSchema again by McpWire.call: nothing is sent when they
     * don't fit). [reads]: the tool only reads (McpPolicy.onlyReads), so a call a lost session may have run can go again.
     * Blocks: a background thread. Never throws.
     */
    fun call(c: Connection, pin: McpPin, args: Map<String, Any?>, secrets: ConnSecrets, abort: Abort, robot: String,
        cause: NetCause = NetCause.SKILL, reads: Boolean = false): McpCallEnd {
        // Arguments that don't fit the pinned inputSchema: nothing is sent, not even the probe.
        if (SchemaCheck.check(pin.input, args, strict = true).isNotEmpty()) return McpCallEnd(McpOut.Refused(McpRefusal.ARGUMENTS), sent = false, era = null)
        val s = Session(c, secrets, abort, robot, cause, callMs)
        try {
            var server = when (val o = s.open()) {
                is Opened.Server -> o.server
                is Opened.Ended -> return McpCallEnd(o.out, sent = false, era = s.era, ms = s.ms())
            }
            var again = true
            while (true) {
                val id = s.next()
                val request = when (val r = McpWire.call(server, id, pin, args, robot)) {
                    is McpOut.Ok -> r.value
                    is McpOut.Failed -> return McpCallEnd(r, sent = false, era = s.era, ms = s.ms())
                    is McpOut.Refused -> return McpCallEnd(r, sent = false, era = s.era, ms = s.ms())
                }
                val got = when (val x = s.send(request, id)) {
                    is ConnExchange.Got -> x
                    // Broken after it may have gone: never sent again.
                    is ConnExchange.Ended -> return McpCallEnd(s.out(x.call), sent = x.call.sent(), era = s.era, ms = s.ms())
                }
                val out = McpWire.callReply(server, pin, got.status, got.contentType, got.bytes, got.over, id)
                // The spec's 404 for a session: a new session, once, and only for a tool that only reads, since a server
                // that ran the call and then lost its session would run it again (an unlock, a payment).
                if (out is McpOut.Refused && out.why == McpRefusal.SESSION && again && reads && server.session != null) {
                    again = false
                    server = when (val o = s.legacy()) {
                        is Opened.Server -> o.server
                        is Opened.Ended -> return McpCallEnd(o.out, sent = false, era = s.era, ms = s.ms())
                    }
                    continue
                }
                return McpCallEnd(out, sent = true, era = s.era, ms = s.ms(), bytes = got.bytes.size)
            }
        } finally {
            s.close()
        }
    }

    private sealed class Opened {
        class Server(val server: McpServer) : Opened()
        /** [out] null: Stop came. */
        class Ended(val out: McpOut<Nothing>?) : Opened()
    }

    /** One listing or call: its deadline, its JSON-RPC ids, and the legacy session to end. */
    private inner class Session(val c: Connection, val secrets: ConnSecrets, val abort: Abort, val robot: String, val cause: NetCause, limit: Long) {
        private val t0 = System.nanoTime()
        private val end = t0 + limit * 1_000_000
        private var id = 0L
        private var session: McpServer? = null
        var era: McpEra? = null
        var where: ConnWhere? = null

        fun ms(): Long = (System.nanoTime() - t0) / 1_000_000
        fun left(): Long = (end - System.nanoTime()) / 1_000_000
        fun next(): Long = ++id

        fun send(m: McpRequest, id: Long?): ConnExchange {
            if (left() <= 0) return ConnExchange.Ended(ConnCall.Failed(ConnFailure.TIMEOUT, sent = false, ms = ms()))
            val r = request(c, m) ?: return ConnExchange.Ended(ConnCall.Failed(ConnFailure.NOWHERE, sent = false, ms = ms()))
            return http.exchange(c, r, secrets, abort, left(), cause = cause, enough = id?.let { answered(it) }).also { if (it is ConnExchange.Got) where = it.where }
        }

        /** A broken exchange as McpOut: null for Stop. */
        fun out(call: ConnCall): McpOut<Nothing>? = when (call) {
            is ConnCall.Stopped -> null
            is ConnCall.Failed -> McpOut.Failed(call.why)
            is ConnCall.Answered -> McpOut.Failed(ConnFailure.UNREADABLE)
        }

        /** server/discover, then initialize for a server of that era. */
        fun open(): Opened {
            val id = next()
            val got = when (val x = send(McpWire.discover(id, robot), id)) {
                is ConnExchange.Got -> x
                is ConnExchange.Ended -> return Opened.Ended(out(x.call))
            }
            return when (val p = McpWire.probe(got.status, got.contentType, got.bytes, got.over, id)) {
                is McpProbe.Modern -> { era = McpEra.MODERN; Opened.Server(p.server) }
                is McpProbe.Legacy -> legacy()
                is McpProbe.Stop -> Opened.Ended(p.out)
            }
        }

        /** initialize and notifications/initialized: a new legacy session (the one before, if any, ended first). */
        fun legacy(): Opened {
            era = McpEra.LEGACY
            close()
            val id = next()
            val got = when (val x = send(McpWire.initialize(id, robot), id)) {
                is ConnExchange.Got -> x
                is ConnExchange.Ended -> return Opened.Ended(out(x.call))
            }
            val server = when (val s = McpWire.initializeReply(got.status, got.contentType, got.bytes, got.over, id, got.session)) {
                is McpOut.Ok -> s.value
                is McpOut.Failed -> return Opened.Ended(s)
                is McpOut.Refused -> return Opened.Ended(s)
            }
            session = server.takeIf { it.session != null }
            when (val x = send(McpWire.initialized(server), null)) {
                is ConnExchange.Got -> if (x.status !in 200..299) return Opened.Ended(McpOut.Failed(ConnFailure.SAID_NO, x.status))
                is ConnExchange.Ended -> return Opened.Ended(out(x.call))
            }
            return Opened.Server(server)
        }

        /** DELETE for a legacy session, whatever happened: its own short time, even after Stop. */
        fun close() {
            val s = session ?: return
            session = null
            val m = McpWire.end(s) ?: return
            val r = request(c, m) ?: return
            http.exchange(c, r, secrets, Abort(), END_MS, cause = cause)
        }
    }

    companion object {
        /** DELETE's own time: a session left open is the server's to end, so this never waits long. */
        const val END_MS = 2_000L

        /** The request for [m] at [c]'s address: its path, its headers, its JSON; the token goes in ConnWire. */
        internal fun request(c: Connection, m: McpRequest): HttpRequest? {
            val base = c.address ?: return null
            val method = if (m.verb == "DELETE") HttpMethod.DELETE else HttpMethod.POST // l10n: not text (an HTTP verb)
            val headers = m.headers.filterNot { it.first.equals("Content-Type", ignoreCase = true) } // l10n: not text (the body says it)
            val body = m.body?.let { ReqBody(listOf(BodyPart.Text(it)), "application/json") } // l10n: not text (a media type)
            return HttpRequest(method, base, c.secretAddress, base.path, emptyList(), headers, body, c.auth, read = false, json = true)
        }

        /** A stream's reply is whole once it holds the answer to [id]: a result or an error. */
        internal fun answered(id: Long): (String?, ByteArray) -> Boolean = { type, bytes ->
            type?.substringBefore(';')?.trim().equals("text/event-stream", ignoreCase = true) && // l10n: not text (a media type)
                (McpWire.messages(type, bytes) as? McpOut.Ok)?.value?.any { m ->
                    m is Map<*, *> && (m["id"] as? Number)?.toLong() == id && ("result" in m || "error" in m) // l10n: not text (JSON-RPC)
                } == true
        }

        private fun tool(v: Any?): Map<String, Any?>? = (v as? Map<*, *>)?.entries?.associate { (k, x) -> k.toString() to x }
    }
}

private fun ConnCall.sent(): Boolean = when (this) {
    is ConnCall.Failed -> sent
    is ConnCall.Stopped -> sent
    is ConnCall.Answered -> true
}
