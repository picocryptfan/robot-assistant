// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.helpers.LanRule
import java.util.Locale

// Pure. A connection's address, made strict (plan §5.2, C2): http or https (https when you type none), a host, an optional
// port, and a base path ("https://home.example.org/ha"). Refused: userinfo ("user@host": never), a fragment, a query
// (except in an address that is itself the key, [ConnUrl.parse]'s `whole`), a space or a control character, anything
// outside ASCII (type a name in its punycode form), a dot segment, an encoded "/" or "\", and a host Robot can't read
// one way only: digits and dots that aren't a plain dotted quad ("3232235777", "10.1", "010.0.0.1") or a last label in
// hex ("0x7f000001", "0x7f.1"), which an HTTP client or the resolver may read as an address of its own, past the
// address rule (D1). Parsed here by hand: core/connect names no network class (connections plan §4.3).

/**
 * [https], [host] (lower case; an IPv6 literal without its brackets, [ipv6]), [port] (null: the scheme's default, which
 * is never written) and [path] (empty, or "/…" without a trailing "/"; it may hold a query only for a whole address that
 * is a key).
 */
data class ConnUrl(val https: Boolean, val host: String, val ipv6: Boolean, val port: Int?, val path: String) {
    val scheme: String get() = if (https) "https" else "http" // l10n: not text (a URL scheme)

    /** "home.example.org:8123", "[fd00::5]": the host as a card shows it. */
    val shownHost: String get() = (if (ipv6) "[$host]" else host) + (port?.let { ":$it" } ?: "")

    /** "https://home.example.org:8123": what a first-use answer names (D13); a changed scheme, host or port asks again. */
    val origin: String get() = "$scheme://$shownHost"

    /** The address as connections.json keeps it. */
    val text: String get() = origin + path

    /** The same address without its path: what is kept of an address that is itself the key. */
    fun originOnly(): ConnUrl = copy(path = "")

    /** The bytes of a host typed as an address (IPv4 or IPv6), or null for a name (it is checked when it resolves, D1). */
    val literal: ByteArray? get() = LanRule.literal(host)

    override fun toString(): String = "ConnUrl(…)" // l10n: not text (toString, for the logs)

    companion object {
        const val MAX_LENGTH = 512
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        private val PORT = Regex("^[0-9]{1,5}$")
        /** RFC 3986's pchar, without "%" (checked on its own): unreserved, sub-delims, ":" and "@". */
        private const val PCHAR = "-._~!$&'()*+,;=:@" // l10n: not text (URL characters)
        private val HEX = Regex("^[0-9A-Fa-f]{2}$")

        /**
         * [typed] as an address, or null when Robot won't use it. [whole]: the address is itself the key (a webhook's), so
         * it may end in a query; otherwise a query is refused (an operation declares its own).
         */
        fun parse(typed: String, whole: Boolean = false): ConnUrl? {
            val t = typed.trim()
            if (t.isEmpty() || t.length > MAX_LENGTH) return null
            if (t.any { it <= ' ' || it.code >= 0x7F || it == '\\' }) return null
            val m = SCHEME.find(t)
            val scheme = m?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: "https" // l10n: not text (a URL scheme)
            if (scheme != "http" && scheme != "https") return null // l10n: not text (URL schemes)
            val rest = if (m != null) t.substring(m.value.length) else t
            if ('#' in rest || '@' in rest) return null
            val q = rest.indexOf('?')
            if (q >= 0 && !whole) return null
            val end = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, end)
            val pathPart = rest.substring(end)
            val hostPort = host(authority) ?: return null
            val defaultPort = if (scheme == "https") 443 else 80
            val port = hostPort.third?.takeIf { it != defaultPort }
            val path = path(pathPart, whole) ?: return null
            return ConnUrl(scheme == "https", hostPort.first, hostPort.second, port, path)
        }

        /** (host, ipv6, port) of an authority, or null. */
        private fun host(authority: String): Triple<String, Boolean, Int?>? {
            if (authority.isEmpty()) return null
            val hostText: String
            val portText: String?
            val ipv6: Boolean
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                hostText = authority.substring(1, close).lowercase(Locale.ROOT)
                val after = authority.substring(close + 1)
                if (after.isNotEmpty() && !after.startsWith(":")) return null
                portText = after.removePrefix(":").takeIf { after.isNotEmpty() }
                // A zone ("fe80::1%25wlan0") only ever names a link: never.
                if ('%' in hostText || hostText.count { it == ':' } < 2) return null
                if (LanRule.literal(hostText)?.size != 16) return null
                ipv6 = true
            } else {
                val colon = authority.indexOf(':')
                if (colon >= 0 && authority.lastIndexOf(':') != colon) return null
                hostText = (if (colon >= 0) authority.substring(0, colon) else authority).lowercase(Locale.ROOT).removeSuffix(".")
                portText = if (colon >= 0) authority.substring(colon + 1) else null
                if (!nameOrQuad(hostText)) return null
                ipv6 = false
            }
            val port = when (portText) {
                null -> null
                else -> if (!PORT.matches(portText)) return null else portText.toInt().takeIf { it in 1..65535 } ?: return null
            }
            return Triple(hostText, ipv6, port)
        }

        /** A DNS name of letters, digits and hyphens, or a plain dotted quad; never digits that read another way. */
        private fun nameOrQuad(h: String): Boolean {
            if (h.isEmpty() || h.length > 253) return false
            if (LanRule.numericNotQuad(h)) return false
            val labels = h.split('.')
            if (labels.any { !LABEL.matches(it) }) return false
            val last = labels.last()
            // WHATWG reads a host whose last label is a number (or 0x…) as an IPv4 address in some spelling: only a plain quad.
            if (last.all { it.isDigit() } || last.startsWith("0x")) return LanRule.literal(h)?.size == 4
            return true
        }

        /**
         * A base path: empty, or "/" segments of URL characters (percent-escapes allowed), never "." or "..", never an
         * empty segment, never an escape for "/", "\" or a control character; a trailing "/" is dropped. [whole]: a query
         * may follow.
         */
        private fun path(p: String, whole: Boolean): String? {
            if (p.isEmpty() || p == "/") return ""
            val qi = p.indexOf('?')
            val pathOnly = if (qi >= 0) p.substring(0, qi) else p
            val query = if (qi >= 0) p.substring(qi + 1) else null
            if (query != null && (!whole || query.isEmpty() || !chars(query, "/?"))) return null
            var path = pathOnly
            if (path.isNotEmpty()) {
                if (!path.startsWith("/")) return null
                path = path.removeSuffix("/")
                if (path.isNotEmpty()) for (seg in path.substring(1).split('/')) if (!segment(seg)) return null
            }
            // A whole address's query after no path: "https://host/?k=v".
            if (path.isEmpty() && query != null) path = "/"
            return path + (query?.let { "?$it" } ?: "")
        }

        /** One path segment: not empty, not a dot segment (raw or escaped), URL characters only. */
        fun segment(seg: String): Boolean {
            if (seg.isEmpty() || !chars(seg, "")) return false
            val decoded = decodedAscii(seg) ?: return false
            return decoded != "." && decoded != ".."
        }

        /** [s] holds only pchar, [more] and well-formed escapes, none of them for "/", "\" or a control character. */
        fun chars(s: String, more: String): Boolean {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 3 > s.length || !HEX.matches(s.substring(i + 1, i + 3))) return false
                    val b = s.substring(i + 1, i + 3).toInt(16)
                    if (b < 0x20 || b == 0x7F || b == '/'.code || b == '\\'.code) return false
                    i += 3
                    continue
                }
                if (!(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in PCHAR || c in more)) return false
                i++
            }
            return true
        }

        /** [s] with its ASCII escapes decoded (to compare with "." and ".."); null when an escape is malformed. */
        private fun decodedAscii(s: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] == '%') {
                    if (i + 3 > s.length) return null
                    out.append(s.substring(i + 1, i + 3).toIntOrNull(16)?.toChar() ?: return null)
                    i += 3
                } else out.append(s[i++])
            }
            return out.toString()
        }
    }
}
