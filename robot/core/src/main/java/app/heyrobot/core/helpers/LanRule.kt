package app.heyrobot.core.helpers

import java.util.Locale

// Pure. Which addresses a helper may use without encryption (0.6.0, "your own server"). Robot sends http:// only when
// every address the connection uses is on your own network: loopback (a server on the phone itself), the RFC 1918 home
// ranges, link-local, 100.64.0.0/10 (CGNAT, where Tailscale gives every device an address) and IPv6 unique local
// addresses; an IPv4-mapped IPv6 address counts as its IPv4 address. Everything else is refused for cleartext, NAT64
// (64:ff9b::/96 maps to public IPv4) included. The rule runs on the addresses a name resolves to, at connect time (the
// :claude module's DNS filter and network interceptor), so a name that later resolves elsewhere can't take Robot's words
// to a public host. A typed address is normalized here too: userinfo, a query or a fragment, or a scheme other than
// http and https is refused.

object LanRule {
    /** Where an address is. Only [PUBLIC] and [BLOCKED] are refused for cleartext. */
    enum class Range(val cleartext: Boolean, val warning: String? = null) {
        LOOPBACK(true),
        PRIVATE(true),
        LINK_LOCAL(true, "a link-local address, which only works on a direct connection"),
        CGNAT(true, "a 100.64–100.127 address: private only through Tailscale (on mobile data without it, this space is the carrier's)"),
        ULA(true),
        PUBLIC(false),
        /** Unspecified, broadcast or multicast: never a server. */
        BLOCKED(false),
    }

    /** The range of an address given as its 4 or 16 bytes. */
    fun classify(a: ByteArray): Range = when (a.size) {
        4 -> v4(a.map { it.toInt() and 0xFF })
        16 -> v6(a.map { it.toInt() and 0xFF })
        else -> Range.BLOCKED
    }

    private fun v4(b: List<Int>): Range = when {
        b[0] == 127 -> Range.LOOPBACK
        b[0] == 10 -> Range.PRIVATE
        b[0] == 172 && b[1] in 16..31 -> Range.PRIVATE
        b[0] == 192 && b[1] == 168 -> Range.PRIVATE
        b[0] == 169 && b[1] == 254 -> Range.LINK_LOCAL
        b[0] == 100 && b[1] in 64..127 -> Range.CGNAT
        b.all { it == 0 } || b.all { it == 255 } || b[0] >= 224 -> Range.BLOCKED
        b[0] == 0 -> Range.BLOCKED
        else -> Range.PUBLIC
    }

    private fun v6(b: List<Int>): Range = when {
        b.subList(0, 15).all { it == 0 } && b[15] == 1 -> Range.LOOPBACK
        b.all { it == 0 } -> Range.BLOCKED
        // IPv4-mapped (::ffff:a.b.c.d): the IPv4 address decides.
        b.subList(0, 10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF -> v4(b.subList(12, 16))
        b[0] == 0xFF -> Range.BLOCKED
        b[0] == 0xFE && (b[1] and 0xC0) == 0x80 -> Range.LINK_LOCAL
        (b[0] and 0xFE) == 0xFC -> Range.ULA
        else -> Range.PUBLIC
    }

    /** What Robot does with an http:// (or https://) connection to these addresses. */
    sealed class Decision {
        /** Go ahead, using only [use] (for http, the private ones; a public address a name also gave is dropped). */
        data class Allow(val use: List<ByteArray>, val warning: String? = null) : Decision()

        /** Refused: nothing is sent. */
        data class Block(val why: String) : Decision()
    }

    const val NOT_LOCAL = "That address isn't on your home network, so Robot won't send to it without https."

    /** [https]: any address (TLS protects it); http: only the private ones, and none at all is a refusal. */
    fun decide(https: Boolean, resolved: List<ByteArray>): Decision {
        if (resolved.isEmpty()) return Decision.Block(NOT_LOCAL)
        if (https) return Decision.Allow(resolved.filter { classify(it) != Range.BLOCKED }.ifEmpty { return Decision.Block(NOT_LOCAL) })
        val ok = resolved.filter { classify(it).cleartext }
        if (ok.isEmpty()) return Decision.Block(NOT_LOCAL)
        return Decision.Allow(ok, ok.mapNotNull { classify(it).warning }.firstOrNull())
    }

    /** The private ones of [resolved], for a cleartext connection's DNS filter. */
    fun cleartextOnly(resolved: List<ByteArray>): List<ByteArray> = resolved.filter { classify(it).cleartext }

    // ------------------------------------------------------------------ typed addresses

    /** Ports a bare host is tried on, in this order: Ollama, LM Studio, llama.cpp (now), llama.cpp in Docker and LocalAI, vLLM, Open WebUI. */
    val PORTS = listOf(11434, 1234, 9931, 8080, 8000, 3000)

    /** Open WebUI serves its OpenAI-style API under /api, not /v1. */
    const val OPEN_WEBUI_PORT = 3000

    /** A typed address made usable: [candidates] are tried in order ([probe]: more than one, Test connection picks). */
    data class Typed(val https: Boolean, val host: String, val port: Int?, val path: String, val candidates: List<String>) {
        val probe: Boolean get() = candidates.size > 1
        /** A name Settings words hints for (.local, .lan, .home.arpa, .internal, .ts.net), or null for an address. */
        val hint: String? get() = when {
            literal(host) != null -> null
            host.endsWith(".ts.net") -> "a Tailscale name: it resolves only while Tailscale is on"
            host.endsWith(".local") -> "a .local name: if it doesn't resolve on this phone, use the server's IP address or its Tailscale name"
            host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".internal") -> "a name your router gives out: it works only on that network"
            else -> null
        }
    }

    /**
     * "192.168.1.20" → every common port, "desktop.lan:11434" → http://desktop.lan:11434/v1, "https://box.tail1234.ts.net"
     * → https://box.tail1234.ts.net/v1; a path you typed is kept. Null (refused): empty, userinfo, a query or fragment,
     * another scheme, no host, a bad port.
     */
    fun normalize(typed: String): Typed? {
        var t = typed.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() || it.code < 0x21 }) return null
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(t)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (scheme != null && scheme != "http" && scheme != "https") return null
        if (scheme != null) t = t.substring(scheme.length + 3)
        if ('?' in t || '#' in t || '@' in t) return null
        val slash = t.indexOf('/')
        val authority = if (slash >= 0) t.substring(0, slash) else t
        val path = if (slash >= 0) t.substring(slash).trimEnd('/') else ""
        val host: String
        val port: Int?
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            host = authority.substring(1, end).lowercase(Locale.ROOT)
            val rest = authority.substring(end + 1)
            port = if (rest.isEmpty()) null else rest.removePrefix(":").toIntOrNull()?.takeIf { rest.startsWith(":") } ?: return null
            if (parse6(host) == null) return null
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0 && authority.indexOf(':') != colon) return null
            host = (if (colon >= 0) authority.substring(0, colon) else authority).lowercase(Locale.ROOT).trimEnd('.')
            port = if (colon >= 0) authority.substring(colon + 1).toIntOrNull() ?: return null else null
            if (!Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*").matches(host)) return null
        }
        if (host.isEmpty() || (port != null && port !in 1..65535)) return null
        val https = scheme == "https"
        val shown = if (':' in host) "[$host]" else host
        val proto = if (https) "https" else "http"
        val probe = PORTS.map { p -> "http://$shown:$p" + if (p == OPEN_WEBUI_PORT) "/api" else "/v1" }
        val candidates = when {
            // "http://192.168.1.20" without a port: port 80 as typed first, then the usual servers' ports.
            scheme == "http" && port == null && path.isEmpty() -> listOf("http://$shown/v1") + probe
            port != null || https || scheme != null -> listOf("$proto://$shown${port?.let { ":$it" } ?: ""}" + (path.ifEmpty { if (port == OPEN_WEBUI_PORT) "/api" else "/v1" }))
            path.isNotEmpty() -> listOf("http://$shown$path")
            else -> probe
        }
        return Typed(https, host, port, path, candidates)
    }

    /**
     * [a] and [b] (typed addresses) name the same host, so a key saved for one may go to the other; false when either
     * isn't an address Robot would use. The port, path and scheme don't count (the same computer).
     */
    fun sameHost(a: String?, b: String?): Boolean {
        val x = a?.let(::normalize)?.host ?: return false
        val y = b?.let(::normalize)?.host ?: return false
        val lx = literal(x)
        val ly = literal(y)
        return if (lx != null || ly != null) lx != null && ly != null && lx.contentEquals(ly) else x == y
    }

    /** The bytes of an IP address literal (IPv4 dotted quad, or IPv6), or null for a name. */
    fun literal(host: String): ByteArray? = parse4(host) ?: parse6(host.removePrefix("[").removeSuffix("]"))

    private fun parse4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val b = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit) || (p.length > 1 && p[0] == '0')) return null else p.toInt() }
        if (b.any { it > 255 }) return null
        return ByteArray(4) { b[it].toByte() }
    }

    private fun parse6(s: String): ByteArray? {
        if (s.isEmpty() || s.count { it == ':' } < 2) return null
        val zone = s.substringBefore('%')
        val halves = zone.split("::")
        if (halves.size > 2) return null
        fun groups(h: String): List<Int>? {
            if (h.isEmpty()) return emptyList()
            val out = ArrayList<Int>()
            val parts = h.split(':')
            for ((i, p) in parts.withIndex()) {
                if (i == parts.lastIndex && '.' in p) {
                    val v4 = parse4(p) ?: return null
                    out += ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
                    out += ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
                } else {
                    if (p.isEmpty() || p.length > 4) return null
                    out += p.toIntOrNull(16) ?: return null
                }
            }
            return out
        }
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val all = if (halves.size == 2) {
            if (head.size + tail.size > 7) return null
            head + List(8 - head.size - tail.size) { 0 } + tail
        } else head
        if (all.size != 8) return null
        return ByteArray(16) { i -> (if (i % 2 == 0) all[i / 2] shr 8 else all[i / 2] and 0xFF).toByte() }
    }
}
