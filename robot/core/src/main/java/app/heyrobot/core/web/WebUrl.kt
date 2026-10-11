package app.heyrobot.core.web

import java.net.IDN
import java.util.Locale

// Pure. Which pages Robot may open from the phone (design A7, A10.4): only URLs that came in the search service's own
// answer (never one the model wrote, never a link found inside a page), HTTPS only (an http:// result is tried once as
// https://), the default port, public unicast addresses only (an SSRF guard: no router, no LAN service, no loopback,
// in any numeric spelling), and at most 3 redirects, each hop checked again. The voice process's page fetcher applies
// the same address rule to every address a name resolves to and to the connected socket (PublicAddress.isPublic), so a
// name that resolves to a private address (DNS rebinding) is refused there.

/** How a URL names its host. */
enum class HostKind { NAME, IPV4, IPV6 }

/**
 * An absolute http(s) URL, parsed strictly and normalized as a browser would (WHATWG URL): the scheme and the name in
 * lower case, an internationalized name in its ASCII (punycode) form, any numeric IPv4 spelling ("0x7f.1",
 * "2130706433", "0177.0.0.1") as its dotted quad, the default port dropped, the fragment dropped. [path] holds the path
 * and the query and starts with "/". [port] is -1 for the scheme's default.
 */
data class WebUrl(val scheme: String, val host: String, val port: Int, val path: String, val kind: HostKind) {
    val https: Boolean get() = scheme == "https"

    override fun toString(): String = "$scheme://" + (if (kind == HostKind.IPV6) "[$host]" else host) + (if (port >= 0) ":$port" else "") + path

    /** The same URL over https on its default port (an http:// result's one try). */
    fun upgraded(): WebUrl = copy(scheme = "https", port = if (port == 80) -1 else port)

    companion object {
        /** Longer URLs are refused (no page Robot reads needs one). */
        const val MAX_LENGTH = 2048
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
        private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")
        private const val PATH_SAFE = "-._~!$&'()*+,;=:@/?%"

        /** [raw] as a web URL, or null: not http(s), credentials in it, a space or control character, a malformed host or port. */
        fun parse(raw: String): WebUrl? {
            val s = raw.trim()
            if (s.isEmpty() || s.length > MAX_LENGTH) return null
            // WHATWG would drop tabs and line feeds and read "\" as "/": a URL that needs either is refused outright.
            if (s.any { it <= ' ' || it == '\u007F' || it == '\\' || it in '\u0080'..'\u009F' }) return null
            val sep = s.indexOf("://")
            if (sep <= 0 || !SCHEME.matches(s.substring(0, sep))) return null
            val scheme = s.substring(0, sep).lowercase(Locale.ROOT)
            if (scheme != "https" && scheme != "http") return null
            val rest = s.substring(sep + 3)
            val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authEnd)
            // "user:pass@host" (and "good.com@evil"): never.
            if (authority.isEmpty() || authority.contains('@')) return null
            val (hostPart, portPart) = if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val after = authority.substring(close + 1)
                if (after.isNotEmpty() && !after.startsWith(":")) return null
                authority.substring(0, close + 1) to after.removePrefix(":").takeIf { after.isNotEmpty() }
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) authority.substring(0, colon) to authority.substring(colon + 1) else authority to null
            }
            val defaultPort = if (scheme == "https") 443 else 80
            val port = when {
                portPart == null || portPart.isEmpty() -> -1
                portPart.length > 5 || !portPart.all { it in '0'..'9' } -> return null
                else -> portPart.toInt().takeIf { it in 1..65535 }?.let { if (it == defaultPort) -1 else it } ?: return null
            }
            val (host, kind) = host(hostPart) ?: return null
            val tail = rest.substring(authEnd).substringBefore('#')
            val path = encodePath(if (tail.isEmpty() || tail.startsWith("?")) "/$tail" else tail)
            return WebUrl(scheme, host, port, path, kind)
        }

        /** The host as the URL is normalized ([HostKind.IPV6] without brackets), or null when it is no valid host. */
        private fun host(raw: String): Pair<String, HostKind>? {
            if (raw.startsWith("[")) {
                val inner = raw.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
                // A zone ("fe80::1%25eth0") only ever names a local link.
                if (inner.contains('%')) return null
                PublicAddress.ipv6(inner) ?: return null
                return inner to HostKind.IPV6
            }
            if (raw.isEmpty() || raw.contains('%')) return null
            var h = raw.lowercase(Locale.ROOT)
            if (h.any { it.code > 0x7F }) h = try { IDN.toASCII(h) } catch (_: Exception) { return null }.lowercase(Locale.ROOT)
            if (h.endsWith(".")) h = h.dropLast(1)
            if (h.isEmpty() || h.length > 253) return null
            val labels = h.split('.')
            // WHATWG: a host whose last label is a number is an IPv4 address in some spelling, or no host at all.
            val last = labels.last()
            if (last.isNotEmpty() && (last.all { it in '0'..'9' } || (last.startsWith("0x") && last.drop(2).all { it in '0'..'9' || it in 'a'..'f' }))) {
                val v4 = PublicAddress.ipv4Loose(h) ?: return null
                return PublicAddress.dotted(v4) to HostKind.IPV4
            }
            if (labels.any { !LABEL.matches(it) }) return null
            return h to HostKind.NAME
        }

        /** Characters a path or query may not hold as they are (non-ASCII, quotes, angle brackets…) as %XX of their UTF-8. */
        private fun encodePath(p: String): String {
            if (p.all { it.isLetterOrDigit() && it.code < 0x80 || it in PATH_SAFE }) return p
            val out = StringBuilder()
            for (b in p.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c.code < 0x80 && (c.isLetterOrDigit() || c in PATH_SAFE)) out.append(c) else out.append('%').append(String.format(Locale.ROOT, "%02X", b.toInt() and 0xFF))
            }
            return out.toString()
        }

        /**
         * What a card shows for [url]: its host without "www." ("nhl.com", "en.wikipedia.org"); the text itself, cut, when
         * it is no web URL.
         */
        fun domain(url: String): String {
            val u = parse(url) ?: return url.take(60)
            return if (u.kind == HostKind.NAME) u.host.removePrefix("www.") else u.host
        }
    }
}

/** Why a result page wasn't read, as its card line says ("tsn.ca: not a public address") and the log counts it. */
enum class PageSkip(val code: Int, val text: String) {
    NOT_FROM_RESULTS(1, "not one of the search results"),
    NOT_WEB(2, "not a web address"),
    NOT_SECURE(3, "not secure"),
    NOT_PUBLIC(4, "not a public address"),
    PORT(5, "an unusual port"),
    TOO_MANY_REDIRECTS(6, "too many redirects"),
    PDF(7, "a PDF, which Robot can't read"),
    NOT_HTML(8, "not a web page Robot can read"),
    VIDEO(9, "a video"),
    NEEDS_JS(10, "needs JavaScript"),
    TOO_SLOW(11, "took too long"),
    FAILED(12, "didn't load"),
}

/** A URL Robot may open now ([upgraded]: an http:// result tried as https://), or why not. */
sealed class UrlCheck {
    data class Ok(val url: WebUrl, val upgraded: Boolean = false) : UrlCheck()
    data class Refused(val why: PageSkip) : UrlCheck()
}

/**
 * The SSRF guard for addresses (the inverse of 0.6.0's LanRule): a page fetch may only reach public unicast addresses.
 * Refused: 0.0.0.0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.0.2/24, 192.88.99/24, 192.168/16,
 * 198.18/15, 198.51.100/24, 203.0.113/24, 224/4 and 240/4 (broadcast included); for IPv6 anything outside global unicast
 * 2000::/3 (so ::, ::1, fe80::/10, fc00::/7, ff00::/8, NAT64 64:ff9b::/96 and 64:ff9b:1::/48, IPv4-compatible ::/96),
 * and inside it 2001::/23 (Teredo, benchmarking, ORCHID), 2001:db8::/32 and 3fff::/20 (documentation) and 2002::/16
 * (6to4, which carries an IPv4 address). An IPv4-mapped address (::ffff:a.b.c.d) is its IPv4 address.
 */
object PublicAddress {
    /** [a] (4 or 16 bytes, as InetAddress.getAddress gives them) is a public unicast address. */
    fun isPublic(a: ByteArray): Boolean = when (a.size) {
        4 -> v4Public(a.map { it.toInt() and 0xFF })
        16 -> v6Public(a.map { it.toInt() and 0xFF })
        else -> false
    }

    /** Only the public addresses of [resolved], in order: a name that also resolves to a private address keeps only its public ones. */
    fun publicOnly(resolved: List<ByteArray>): List<ByteArray> = resolved.filter(::isPublic)

    private fun v4Public(b: List<Int>): Boolean = !(
        b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
            (b[0] == 100 && b[1] in 64..127) ||
            (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 0 && b[2] == 0) ||
            (b[0] == 192 && b[1] == 0 && b[2] == 2) ||
            (b[0] == 192 && b[1] == 88 && b[2] == 99) ||
            (b[0] == 192 && b[1] == 168) ||
            (b[0] == 198 && b[1] in 18..19) ||
            (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
            (b[0] == 203 && b[1] == 0 && b[2] == 113)
        )

    private fun v6Public(b: List<Int>): Boolean {
        if (b.subList(0, 10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF) return v4Public(b.subList(12, 16))
        if ((b[0] and 0xE0) != 0x20) return false
        val w0 = (b[0] shl 8) or b[1]
        val w1 = (b[2] shl 8) or b[3]
        return !(
            (w0 == 0x2001 && w1 < 0x0200) ||
                (w0 == 0x2001 && w1 == 0x0DB8) ||
                w0 == 0x2002 ||
                (w0 == 0x3FFF && (w1 and 0xF000) == 0)
            )
    }

    /**
     * A name that only ever means something on the local network or nowhere: one label ("router"), "localhost" and
     * *.localhost, *.local (mDNS), *.lan, *.home, *.home.arpa, *.internal, *.intranet, *.corp, *.private, *.test,
     * *.invalid, *.onion.
     */
    fun localName(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).removeSuffix(".")
        if (!h.contains('.')) return true
        val tld = h.substringAfterLast('.')
        return h == "localhost" || tld in LOCAL_TLDS || h.endsWith(".home.arpa")
    }

    private val LOCAL_TLDS = setOf("localhost", "local", "lan", "home", "internal", "intranet", "corp", "private", "test", "invalid", "onion", "localdomain")

    /** A host as written in a URL is public: a literal address by its range, a name unless it is a local one (its addresses are checked when it resolves). */
    fun hostPublic(u: WebUrl): Boolean = when (u.kind) {
        HostKind.NAME -> !localName(u.host)
        HostKind.IPV4 -> ipv4Loose(u.host)?.let(::isPublic) == true
        HostKind.IPV6 -> ipv6(u.host)?.let(::isPublic) == true
    }

    /** "a.b.c.d" of 4 bytes. */
    fun dotted(a: ByteArray): String = a.joinToString(".") { (it.toInt() and 0xFF).toString() }

    /**
     * An IPv4 address in any spelling a browser takes (WHATWG's IPv4 parser): 1 to 4 parts, each decimal, octal (a
     * leading 0) or hex (0x); the last part fills the remaining bytes ("127.1" is 127.0.0.1, "2130706433" too). Null when
     * it isn't one.
     */
    fun ipv4Loose(s: String): ByteArray? {
        val parts = s.removeSuffix(".").split('.')
        if (parts.isEmpty() || parts.size > 4 || parts.any { it.isEmpty() }) return null
        val nums = parts.map { p ->
            val (digits, radix) = when {
                p.startsWith("0x") || p.startsWith("0X") -> p.substring(2) to 16
                p.length > 1 && p.startsWith("0") -> p.substring(1) to 8
                else -> p to 10
            }
            if (digits.isEmpty()) 0L else if (digits.length > 12) return null else digits.toLongOrNull(radix) ?: return null
        }
        if (nums.dropLast(1).any { it > 255 }) return null
        val lastMax = 1L shl (8 * (5 - nums.size))
        if (nums.last() >= lastMax) return null
        var v = nums.last()
        nums.dropLast(1).forEachIndexed { i, n -> v += n shl (8 * (3 - i)) }
        return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    /** A strict IPv6 literal ("::1", "2001:db8::1", "::ffff:127.0.0.1"), 16 bytes, or null. */
    fun ipv6(s: String): ByteArray? {
        if (s.isEmpty() || s.length > 45 || s.contains('%')) return null
        val text = s.lowercase(Locale.ROOT)
        val lastColon = text.lastIndexOf(':')
        if (lastColon < 0) return null
        var main = text
        var tail = ByteArray(0)
        // An IPv4 address in the last 32 bits ("::ffff:127.0.0.1"): strict dotted decimal only.
        val last = text.substring(lastColon + 1)
        if (last.contains('.')) {
            val parts = last.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } || it.toInt() > 255 || (it.length > 1 && it.startsWith("0")) }) return null
            tail = ByteArray(4) { parts[it].toInt().toByte() }
            main = text.substring(0, lastColon + 1).let { if (it.endsWith("::")) it else it.dropLast(1) }
        }
        val groupsWanted = (16 - tail.size) / 2
        val halves = main.split("::")
        if (halves.size > 2) return null
        fun groups(h: String): List<Int>? = if (h.isEmpty()) emptyList() else h.split(':').map { g ->
            if (g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it in 'a'..'f' }) return null
            g.toInt(16)
        }
        val head = groups(halves[0]) ?: return null
        val all = if (halves.size == 2) {
            val back = groups(halves[1]) ?: return null
            val fill = groupsWanted - head.size - back.size
            if (fill < 1) return null
            head + List(fill) { 0 } + back
        } else head
        if (all.size != groupsWanted) return null
        val out = ByteArray(16)
        all.forEachIndexed { i, g -> out[2 * i] = (g shr 8).toByte(); out[2 * i + 1] = g.toByte() }
        tail.copyInto(out, 16 - tail.size)
        return out
    }
}

/** The rules each page URL and each redirect hop passes before anything is fetched. */
object UrlPolicy {
    /** Redirects followed at most (each hop https and public). */
    const val MAX_REDIRECTS = 3

    /**
     * [raw] (a URL from the search service's results) may be fetched: an http:// one becomes https:// (its one try),
     * the port must be the default, and the host must not be a local name or a non-public literal address.
     */
    fun page(raw: String): UrlCheck {
        val parsed = WebUrl.parse(raw) ?: return UrlCheck.Refused(PageSkip.NOT_WEB)
        val upgraded = !parsed.https
        val u = if (upgraded) parsed.upgraded() else parsed
        return checked(u, upgraded)
    }

    /**
     * A redirect from [from] to [location] (the Location header), after [redirectsSoFar] redirects: at most
     * [MAX_REDIRECTS]; a relative location is resolved against [from]; it must be https already (a redirect down to
     * http:// is refused, not upgraded), on the default port, to a public host. No <meta refresh>, no JavaScript.
     */
    fun redirect(from: WebUrl, location: String?, redirectsSoFar: Int): UrlCheck {
        if (redirectsSoFar >= MAX_REDIRECTS) return UrlCheck.Refused(PageSkip.TOO_MANY_REDIRECTS)
        val target = location?.trim()?.takeIf { it.isNotEmpty() }?.let { resolve(from, it) } ?: return UrlCheck.Refused(PageSkip.NOT_WEB)
        val u = WebUrl.parse(target) ?: return UrlCheck.Refused(PageSkip.NOT_WEB)
        if (!u.https) return UrlCheck.Refused(PageSkip.NOT_SECURE)
        return checked(u, false)
    }

    private fun checked(u: WebUrl, upgraded: Boolean): UrlCheck = when {
        u.port != -1 -> UrlCheck.Refused(PageSkip.PORT)
        !PublicAddress.hostPublic(u) -> UrlCheck.Refused(PageSkip.NOT_PUBLIC)
        else -> UrlCheck.Ok(u, upgraded)
    }

    /** [ref] resolved against [base] (RFC 3986: absolute, "//host/…", "/path", "?query" or a relative path), or null. */
    fun resolve(base: WebUrl, ref: String): String? {
        val r = ref.trim()
        if (r.any { it <= ' ' || it == '\\' }) return null
        val colon = r.indexOf(':')
        val firstDelim = r.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (colon > 0 && (firstDelim < 0 || colon < firstDelim)) return r
        val origin = "${base.scheme}://" + (if (base.kind == HostKind.IPV6) "[${base.host}]" else base.host) + (if (base.port >= 0) ":${base.port}" else "")
        return when {
            r.startsWith("//") -> "${base.scheme}:$r"
            r.startsWith("/") -> origin + r
            r.startsWith("?") -> origin + base.path.substringBefore('?') + r
            r.startsWith("#") || r.isEmpty() -> base.toString()
            else -> origin + base.path.substringBefore('?').substringBeforeLast('/') + "/" + r
        }
    }
}

/**
 * The pages one answer may read: only the URLs its search service (or Wikipedia) returned, compared after
 * normalization. A URL the model wrote, or a link found in a page, is refused before any other rule.
 */
class PageUrls(results: List<SearchResult>) {
    private val allowed: Set<String> = results.mapNotNull { r -> WebUrl.parse(r.url)?.let(::key) }.toSet()

    private fun key(u: WebUrl): String = u.copy(scheme = "https", port = if (u.port == 80) -1 else u.port).toString()

    /** [raw] is one of the results and passes [UrlPolicy.page]. */
    fun check(raw: String): UrlCheck {
        val u = WebUrl.parse(raw) ?: return UrlCheck.Refused(PageSkip.NOT_WEB)
        if (key(u) !in allowed) return UrlCheck.Refused(PageSkip.NOT_FROM_RESULTS)
        return UrlPolicy.page(raw)
    }
}

/** What Robot reads: HTML and plain text only (no document parsers, no media). */
object PageType {
    enum class Kind { HTML, TEXT }

    /** The answer's Content-Type: HTML (text/html, application/xhtml+xml), plain text, or null (skip it). */
    fun of(contentType: String?): Kind? = when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "text/html", "application/xhtml+xml" -> Kind.HTML
        "text/plain" -> Kind.TEXT
        else -> null
    }

    /** Why a Content-Type is skipped, for the card. */
    fun skip(contentType: String?): PageSkip =
        if (contentType?.lowercase(Locale.ROOT)?.contains("pdf") == true) PageSkip.PDF else PageSkip.NOT_HTML

    private val MEDIA = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "zip", "gz", "rar", "7z", "apk", "exe", "dmg",
        "jpg", "jpeg", "png", "gif", "webp", "svg", "mp3", "mp4", "m4a", "mov", "webm", "avi", "mkv", "ogg", "wav", "flac", "epub")
    private val VIDEO_HOSTS = setOf("youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "dailymotion.com", "twitch.tv", "rumble.com")

    /** A result that isn't worth fetching by its URL alone: a PDF or other file, or a video site. */
    fun skipUrl(u: WebUrl): PageSkip? {
        val ext = u.path.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        val host = u.host.removePrefix("www.").removePrefix("m.")
        return when {
            ext == "pdf" -> PageSkip.PDF
            ext in MEDIA -> PageSkip.NOT_HTML
            VIDEO_HOSTS.any { host == it || host.endsWith(".$it") } -> PageSkip.VIDEO
            else -> null
        }
    }
}
