// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI

// Today H7b (home-design §6.1, "Through what"): a feed's GET through Tor. Tor gives a byte stream, not a socket the
// platform's HTTP client takes, so this writes the one request and reads the one answer itself, strictly: HTTP/1.1, one
// request a stream (Connection: close), no compression asked or taken, every line, header and body capped, a chunked or
// sized body read to exactly its end and never past FeedXml.MAX_BYTES. Pure: the TLS is FeedTls's, the stream the caller's.
object FeedHttp {
    const val MAX_LINE = 8 * 1024
    const val MAX_HEADERS = 100
    const val MAX_HEAD = 64 * 1024

    class Bad(val tooBig: Boolean = false) : IOException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** An answer: its status, its headers (names in lower case, the first of each), and its body (200 only). */
    class Answer(val code: Int, val headers: Map<String, String>, val body: ByteArray?, val cut: Boolean = false) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /**
     * Where [url] goes: its host, port and request target; null unless it's https with no user part and a clean target.
     * H8, [home] (a feed on your own network, its address checked by ConnRules first): plain http too, and a host that is
     * an address typed as a number or a name without a dot.
     */
    class Target(val host: String, val port: Int, val path: String, val https: Boolean = true)

    fun target(url: String, home: Boolean = false): Target? = try {
        val u = URI(url)
        val host = u.host?.lowercase()
        val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        val https = u.scheme == "https"
        val port = if (u.port == -1) (if (https) 443 else 80) else u.port
        if (!(https || (home && u.scheme == "http")) || u.rawUserInfo != null || host == null || port !in 1..65535) null
        else if (!(name(host) || (home && homeHost(host))) || !path.startsWith("/") || path.length > MAX_LINE / 2 || path.any { it <= ' ' || it > '~' }) null
        else Target(host, port, path, https)
    } catch (_: Exception) {
        null
    }

    /** A DNS name of two labels or more whose last has a letter: never an address literal (Tor resolves the name). */
    fun name(host: String): Boolean = host.length <= 253 && HOST.matches(host) && host.substringAfterLast('.').any { it in 'a'..'z' }

    /** H8: a name without a dot, or an address typed as a number ("[…]" for IPv6, as URI gives it). */
    fun homeHost(host: String): Boolean = LABEL.matches(host) || app.heyrobot.core.helpers.LanRule.literal(host.removePrefix("[").removeSuffix("]")) != null

    /** The request's bytes. [etag] and [modified] are StoredNews validators (printable ASCII, short). */
    fun request(t: Target, agent: String, accept: String, etag: String?, modified: String?): ByteArray = buildString {
        append("GET ").append(t.path).append(" HTTP/1.1\r\n") // l10n: not text (HTTP)
        append("Host: ").append(t.host).apply { if (t.port != (if (t.https) 443 else 80)) append(':').append(t.port) }.append("\r\n") // l10n: not text (HTTP)
        append("User-Agent: ").append(agent).append("\r\n") // l10n: not text (HTTP)
        append("Accept: ").append(accept).append("\r\n") // l10n: not text (HTTP)
        append("Accept-Encoding: identity\r\n") // l10n: not text (HTTP)
        StoredNews.validator(etag)?.let { append("If-None-Match: ").append(it).append("\r\n") } // l10n: not text (HTTP)
        StoredNews.validator(modified)?.let { append("If-Modified-Since: ").append(it).append("\r\n") } // l10n: not text (HTTP)
        append("Connection: close\r\n\r\n") // l10n: not text (HTTP)
    }.toByteArray(Charsets.US_ASCII)

    /**
     * The answer on [input]. A body is read for 200 only, at most [max] bytes; [count] gets every byte read, head included
     * (What went online), and [late] is asked between reads (true: past the time allowed). Throws [Bad] for anything else.
     */
    fun read(input: InputStream, max: Int, count: (Int) -> Unit, late: () -> Boolean, cut: Boolean = false): Answer {
        val r = Reader(input, count, late)
        val status = r.line()
        val m = STATUS.matchEntire(status) ?: throw Bad()
        val code = m.groupValues[1].toInt()
        val headers = LinkedHashMap<String, String>()
        var n = 0
        while (true) {
            val l = r.line()
            if (l.isEmpty()) break
            if (++n > MAX_HEADERS || r.head > MAX_HEAD) throw Bad()
            val colon = l.indexOf(':')
            if (colon <= 0 || l[0] == ' ' || l[0] == '\t') throw Bad()
            val name = l.substring(0, colon).trim().lowercase()
            if (!HEADER_NAME.matches(name)) throw Bad()
            headers.putIfAbsent(name, l.substring(colon + 1).trim())
        }
        if (code != 200) return Answer(code, headers, null)
        val coding = headers["content-encoding"]?.lowercase()
        if (coding != null && coding != "identity") throw Bad()
        val te = headers["transfer-encoding"]?.lowercase()
        val body = when {
            te != null -> if (te == "chunked") r.chunked(max, cut) else throw Bad()
            headers["content-length"] != null -> {
                val len = headers["content-length"]!!.toLongOrNull()?.takeIf { it >= 0 } ?: throw Bad()
                if (len > max && !cut) throw Bad(tooBig = true)
                r.exactly(minOf(len, max.toLong()).toInt())
            }
            else -> r.toEnd(max, cut)
        }
        // today-h7-fix 7, [cut] (Find's page): past [max] the first [max] bytes are kept (a page's head is at its top).
        return Answer(code, headers, body.let { if (cut && it.size > max) it.copyOf(max) else it }, cut && (body.size >= max))
    }

    private class Reader(private val input: InputStream, private val count: (Int) -> Unit, private val late: () -> Boolean) {
        private val buf = ByteArray(8192)
        private var pos = 0
        private var end = 0
        var head = 0

        private fun fill(): Boolean {
            if (late()) throw Bad()
            val n = input.read(buf)
            if (n < 0) return false
            count(n)
            pos = 0; end = n
            return true
        }

        private fun byte(): Int {
            if (pos == end && !fill()) return -1
            return buf[pos++].toInt() and 0xFF
        }

        /** One line, CRLF or LF, at most [MAX_LINE] bytes, ASCII and Latin-1 only (no control characters but tab). */
        fun line(): String {
            val s = StringBuilder()
            while (true) {
                val c = byte()
                if (c < 0) throw Bad()
                head++
                if (c == '\n'.code) break
                if (c == '\r'.code) continue
                if (c < 0x20 && c != '\t'.code || c == 0x7F) throw Bad()
                s.append(c.toChar())
                if (s.length > MAX_LINE) throw Bad()
            }
            return s.toString()
        }

        fun exactly(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                if (pos == end && !fill()) throw Bad()
                val k = minOf(end - pos, n - got)
                System.arraycopy(buf, pos, out, got, k)
                pos += k; got += k
            }
            return out
        }

        fun toEnd(max: Int, cut: Boolean = false): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                if (pos == end && !fill()) break
                out.write(buf, pos, end - pos)
                pos = end
                if (out.size() > max) { if (cut) return out.toByteArray() else throw Bad(tooBig = true) }
            }
            return out.toByteArray()
        }

        fun chunked(max: Int, cut: Boolean = false): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = line().substringBefore(';').trim()
                if (size.isEmpty() || size.length > 8 || !size.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) throw Bad()
                val n = size.toLong(16)
                if (out.size() + n > max) {
                    if (!cut) throw Bad(tooBig = true)
                    out.write(exactly((max - out.size()).toInt()))
                    return out.toByteArray()
                }
                if (n == 0L) {
                    // Trailers, capped as headers are, then the end.
                    var t = 0
                    while (line().isNotEmpty()) if (++t > MAX_HEADERS) throw Bad()
                    return out.toByteArray()
                }
                out.write(exactly(n.toInt()))
                if (line().isNotEmpty()) throw Bad()
            }
        }
    }

    private val STATUS = Regex("HTTP/1\\.[01] ([1-5][0-9][0-9])(?: .*)?") // l10n: not text (HTTP's status line)
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9a-z-]{1,64}") // l10n: not text (HTTP's header name)
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?") // l10n: not text (a DNS label)
    private val HOST = Regex("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)+") // l10n: not text (a DNS name)
}
