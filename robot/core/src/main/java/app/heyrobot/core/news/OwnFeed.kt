// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnRules
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.web.HostKind
import app.heyrobot.core.web.WebUrl
import java.util.Locale

// Pure. Today H8 (home-design §6.1, "Through what"): news from your own server. A feed reader there gives Robot one
// address, so the publishers see your server and not your phone. ConnRules decides where every feed request may go, as it
// does for Skills connections (D1): a lookup's addresses all home or all internet (home wins when it gives both), plain
// http only to your own network, and every hop of one request of the kind its first was ([sameKind]), so a server on the
// internet can't send the phone to your router. A feed is your own ([own]) when its address can only be on your network:
// an address on it typed as a number, a name without a dot, or a name under a suffix only your network answers. An own
// feed that resolves to anything else is refused. With Tor chosen an own feed goes direct (Tor can't reach your network,
// [route]) and every other feed still goes only through Tor, failing closed as H7 does.
object OwnFeed {
    /** Names only a local network answers: mDNS's .local, RFC 8375's .home.arpa, ICANN's .internal, and the routers' .lan. */
    private val LOCAL = listOf(".local", ".lan", ".home.arpa", ".internal") // l10n: not text (DNS suffixes)

    /** [url] can only be on your own network (Tor can't reach it, and its lookup must give your network). */
    fun own(url: String): Boolean {
        val u = WebUrl.parse(url) ?: return false
        return if (u.kind == HostKind.NAME) !u.host.contains('.') || LOCAL.any { u.host.lowercase(Locale.ROOT).endsWith(it) }
        else LanRule.literal(u.host)?.let { ConnRules.place(it) == ConnRules.Place.HOME } ?: false
    }

    /**
     * D1 on a feed request's lookup ([resolved], the addresses [url]'s host gave): ConnRules.lookupHomeWins, so plain http
     * only to your network; an own feed only to your network, whatever its scheme.
     */
    fun lookup(url: String, resolved: List<ByteArray>): ConnRules.Lookup {
        val u = WebUrl.parse(url) ?: return ConnRules.Lookup.Refused(ConnFailure.NOWHERE)
        val l = ConnRules.lookupHomeWins(u.https, resolved)
        return if (l is ConnRules.Lookup.Ok && l.where != ConnWhere.HOME && own(url)) ConnRules.Lookup.Refused(ConnFailure.MOVED) else l
    }

    /**
     * Q35 (the user, 7 Nov 2026): a feed's token is always encrypted, so an address carrying one ([secret]) goes over https,
     * whose certificate is checked; Robot doesn't take a private network such as Tailscale on trust. One exception: a
     * server on this phone ([onPhone]) over plain http, labelled "Not encrypted" ([note]) and connected only to loopback.
     */
    fun tokenRefused(url: String, secret: Boolean = NewsSecret.carries(url)): Boolean {
        val u = WebUrl.parse(url) ?: return secret
        return secret && !u.https && !onPhone(url)
    }

    /** Each hop [at] of a fetch from [first]: [tokenRefused], and from this phone's plain http with a token, no hop off the phone. */
    fun hopRefused(first: String, at: String, secret: Boolean): Boolean =
        tokenRefused(at, secret) || (note(first, secret) == Note.NOT_ENCRYPTED_ON_PHONE && !onPhone(at))

    /** [url]'s host is this phone: localhost, or a typed 127.0.0.0/8 or ::1. */
    fun onPhone(url: String): Boolean {
        val u = WebUrl.parse(url) ?: return false
        if (u.kind == HostKind.NAME) return u.host.lowercase(Locale.ROOT) == "localhost" // l10n: not text (a host name)
        return LanRule.literal(u.host)?.let(::loopback) == true
    }

    /** [a] is a loopback address: what a feed with a token over plain http must be connected to. */
    fun loopback(a: ByteArray): Boolean = (a.size == 4 || a.size == 16) && LanRule.classify(a) == LanRule.Range.LOOPBACK

    /** What the feed's line says under it. */
    enum class Note { PLAIN_HTTP, NOT_ENCRYPTED_ON_PHONE }

    /** finding 2 and Q35: plain http with a token to this phone says it isn't encrypted; plain http elsewhere says what that means. */
    fun note(url: String, secret: Boolean = NewsSecret.carries(url)): Note? {
        val u = WebUrl.parse(url) ?: return null
        return when {
            u.https -> null
            !onPhone(url) -> Note.PLAIN_HTTP
            secret -> Note.NOT_ENCRYPTED_ON_PHONE
            else -> null
        }
    }

    /**
     * finding 3: Find follows a page's feed [link] only when it's of the page's kind: a page that isn't your own ([page] on
     * the internet, or through Tor) can't send the phone to your network, a typed address, or out of Tor.
     */
    fun findLink(page: String, link: String): Boolean {
        if (own(page)) return true
        val u = WebUrl.parse(link) ?: return false
        return u.kind == HostKind.NAME && !own(link)
    }

    /** finding 4: from a hop on your network, a redirect is followed only to your own ([own]) or the same host, decided before any lookup. */
    fun homeHop(from: String, next: String): Boolean =
        own(next) || (WebUrl.parse(from)?.host?.lowercase(Locale.ROOT)?.let { it == WebUrl.parse(next)?.host?.lowercase(Locale.ROOT) } ?: false)

    /** D1 across redirects: a hop that reached [now] after one request's first reached [first] (null: this is the first). */
    fun sameKind(first: ConnWhere?, now: ConnWhere): Boolean = first == null || first == now

    enum class Route { DIRECT, TOR, HOME }

    /** Which way [url] goes when you chose [via]: through Tor unless it's your own (then direct, and the dialog says so). */
    fun route(url: String, via: NewsVia): Route = when {
        via == NewsVia.DIRECT -> Route.DIRECT
        own(url) -> Route.HOME
        else -> Route.TOR
    }
}

// H8: a feed address carrying a token is a secret, as a Skills connection's address that is itself the key is (ConnSecrets):
// it lives only in its key file (app NewsKeys: KeystoreSecret, robot_feed_<id>), read just before its request, and
// home_settings.json, today_news.json, the backup, History, the logs and the model see only its stand-in ([standIn]: its
// server and the key's id). Settings shows it [masked]. A token is a query value whose name says it's one (token, key, auth,
// …, or ending so: rssToken), or a path part that looks random (16 or more letters and digits, both), as feed readers
// share their feeds. One that looks like neither isn't found: name it in the query.
object NewsSecret {
    private val NAMES = setOf("token", "key", "apikey", "api_key", "auth", "access_token", "secret", "password", "pass", "pwd", "hash", "sig", "signature") // l10n: not text (query names)
    private val ENDS = listOf("token", "key", "secret") // l10n: not text (query names)
    private val RANDOM = Regex("[A-Za-z0-9]{16,}|[A-Za-z0-9_-]{32,}")
    private val ID = Regex("[a-z0-9]{16}")

    private fun secretName(n: String): Boolean = n.lowercase(Locale.ROOT).let { k -> k in NAMES || ENDS.any { k.endsWith(it) } }
    private fun random(p: String): Boolean = whole(p) || (p.contains('.') && whole(p.substringBeforeLast('.')))
    private fun whole(p: String): Boolean = RANDOM.matches(p) && p.any { it.isDigit() } && p.any { it.isLetter() }

    /** [url] with each secret value masked as ConnSecrets.mask does ("…AB12"); [url] itself when it carries none. */
    fun masked(url: String): String {
        val q = url.indexOf('?')
        val head = if (q < 0) url else url.substring(0, q)
        val start = head.indexOf("://").let { if (it < 0) 0 else head.indexOf('/', it + 3).let { s -> if (s < 0) head.length else s } }
        val path = head.substring(start).split('/').joinToString("/") { if (random(it)) ConnSecrets.mask(it) else it }
        val query = if (q < 0) "" else "?" + url.substring(q + 1).split('&').joinToString("&") { p ->
            val eq = p.indexOf('=')
            if (eq > 0 && eq < p.length - 1 && secretName(p.substring(0, eq))) p.substring(0, eq + 1) + ConnSecrets.mask(p.substring(eq + 1)) else p
        }
        return head.substring(0, start) + path + query
    }

    fun carries(url: String): Boolean = masked(url) != url

    /** "https://host:port/": the server only. */
    fun origin(url: String): String? = WebUrl.parse(url)?.let { it.copy(path = "/").toString() }

    /** What's kept in place of an address that carries a secret: its server and the key file's [id]. */
    fun standIn(url: String, id: String): String? = if (ID.matches(id)) origin(url)?.let { "$it#$id" } else null

    fun isStandIn(s: String, id: String): Boolean = standIn(s, id) == s

    fun id(): String = java.security.SecureRandom().let { r -> String(CharArray(16) { "abcdefghijklmnopqrstuvwxyz0123456789"[r.nextInt(36)] }) } // l10n: not text (an id's letters)

    /**
     * The base a feed's relative links are read against: for one that carries a secret, its address without the query when
     * its path holds none (finding 6), else its server only, so no base holds it.
     */
    fun base(url: String): String = if (!carries(url)) url else url.substringBefore('?').substringBefore('#').takeIf { !carries(it) && WebUrl.parse(it) != null } ?: origin(url) ?: url

    /** The secret values [url] carries: random-looking path parts (with and without their extension) and secret query values. */
    fun values(url: String): List<String> {
        val q = url.indexOf('?')
        val head = if (q < 0) url else url.substring(0, q)
        val start = head.indexOf("://").let { if (it < 0) 0 else head.indexOf('/', it + 3).let { s -> if (s < 0) head.length else s } }
        val path = head.substring(start).split('/').filter(::random).flatMap { listOf(it, it.substringBeforeLast('.')) }
        val query = if (q < 0) emptyList() else url.substring(q + 1).substringBefore('#').split('&').mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq > 0 && eq < p.length - 1 && secretName(p.substring(0, eq))) p.substring(eq + 1) else null
        }
        return (path + query).filter { it.length >= 6 }.distinct()
    }

    /** finding 6: [feed] read from [address] without the items whose link carries a secret or the address's own (kept and opened otherwise). */
    fun cleaned(feed: ParsedFeed, address: String): ParsedFeed {
        if (!carries(address)) return feed
        val own = values(address)
        return feed.copy(items = feed.items.filterNot { i -> carries(i.link) || own.any { i.link.contains(it) } })
    }
}
