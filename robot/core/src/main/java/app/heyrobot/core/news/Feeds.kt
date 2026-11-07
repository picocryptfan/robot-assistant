// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import app.heyrobot.core.web.HostKind
import app.heyrobot.core.web.WebUrl
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure. Today H7 (home-design §6.1): what Robot keeps of a feed. Titles, links, the feed's name and dates only: no pictures,
// no enclosures, no HTML, nothing that loads a second thing. A title is plain text with no control or direction marks
// (RTL tricks), a link is http(s) with no credentials and the common tracking parameters taken off.

/** One headline: its title, the page it opens in your browser, and when the feed says it came out. */
data class FeedItem(val title: String, val link: String, val at: Long)

/** A feed as read: its own name and its newest items. */
data class ParsedFeed(val name: String, val items: List<FeedItem>)

object FeedText {
    private val TAG = Regex("<[^<>]{0,500}>")
    private val SPACE = Regex("\\s+")

    /** [raw] as one line of plain text, at most [max] characters ("…" when cut). */
    fun clean(raw: String, max: Int): String {
        // Markup escaped inside a title (some feeds do): its text only, and the common entities read once more.
        var t = TAG.replace(raw, " ")
        t = TAG.replace(FeedXml.unescape(t), " ")
        val b = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> b.append(' ')
                // Controls, format characters (direction marks and overrides, zero-width ones), unpaired surrogates, private use.
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
                    type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() -> {}
                else -> b.appendCodePoint(cp)
            }
        }
        val one = SPACE.replace(b, " ").trim()
        return cut(one, max)
    }

    /** [t] cut to [max] characters at a code point, with "…". */
    fun cut(t: String, max: Int): String {
        if (t.length <= max) return t
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(t[end - 1])) end--
        return t.substring(0, end).trimEnd() + "…"
    }
}

object FeedLinks {
    private val TRACKING = setOf("fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "mc_cid", "mc_eid", "igshid", "_hsenc",
        "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "wt_mc", "cmpid", "ocid", "sr_share")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** [raw] (absolute, or relative to [base]) as a link Robot may open, or null: not http(s), credentials, malformed. */
    fun clean(raw: String, base: String?): String? {
        val r = raw.trim()
        if (r.isEmpty() || r.length > WebUrl.MAX_LENGTH) return null
        val abs = when {
            r.startsWith("//") -> "https:$r"
            SCHEME.containsMatchIn(r) -> r
            base != null -> try { URI(base).resolve(r).toString() } catch (_: Exception) { return null }
            else -> return null
        }
        val u = WebUrl.parse(abs) ?: return null
        return u.copy(path = untracked(u.path)).toString()
    }

    /** [path] without utm_ parameters and the other common trackers. */
    fun untracked(path: String): String {
        val q = path.indexOf('?')
        if (q < 0) return path
        val kept = path.substring(q + 1).split('&').filter { p ->
            val k = p.substringBefore('=').lowercase(Locale.ROOT)
            p.isNotEmpty() && !k.startsWith("utm_") && k !in TRACKING
        }
        return path.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    /** Only a link's host is ever shown or recorded. */
    fun host(url: String): String? = WebUrl.parse(url)?.host
}

object NewsRules {
    /**
     * What you typed or pasted as a feed or site address: https, or plain http (H8: allowed only to your own network, which
     * ConnRules decides on each lookup, OwnFeed.lookup); a name, or an address on your own network typed as a number (never
     * one on the internet); a name without a dot only typed with its scheme ("http://nas:8080/feed"); no credentials.
     * "example.com" is read as https://example.com/.
     */
    fun address(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val typed = t.contains("://")
        val u = WebUrl.parse(if (typed) t else "https://$t") ?: return null
        val ok = if (u.kind == HostKind.NAME) typed || u.host.contains('.') else OwnFeed.own(u.toString())
        return if (ok) u.toString() else null
    }
}

object FeedRead {
    const val MAX_ITEMS = 20
    const val TITLE_MAX = 200
    const val NAME_MAX = 80
    /** A date further ahead than this is read as the fetch's time: a feed can't pin itself on top by claiming the future. */
    const val AHEAD_MS = 24L * 3_600_000

    /** [bytes] from [base] as a feed (RSS 2.0, RSS 1.0 or Atom), or null when it isn't one. */
    fun read(bytes: ByteArray, base: String, now: Long): ParsedFeed? {
        val root = FeedXml.parse(bytes) ?: return null
        val (title, items) = when (root.name) {
            "rss" -> (root.child("channel") ?: return null).let { it.child("title") to it.all("item") }
            "rdf" -> root.child("channel")?.child("title") to root.all("item")
            "feed" -> root.child("title") to root.all("entry")
            else -> return null
        }
        val atom = root.name == "feed"
        val read = items.asSequence().take(200).mapNotNull { item(it, atom, base, now) }.distinctBy { it.link }
            .sortedByDescending { it.at }.take(MAX_ITEMS).toList()
        val name = title?.let { FeedText.clean(it.text, NAME_MAX) }?.takeIf { it.isNotEmpty() } ?: FeedLinks.host(base) ?: return null
        return ParsedFeed(name, read)
    }

    private fun item(n: XmlNode, atom: Boolean, base: String, now: Long): FeedItem? {
        val title = FeedText.clean(n.child("title")?.text ?: return null, TITLE_MAX).takeIf { it.isNotEmpty() } ?: return null
        val raw = if (atom) {
            val links = n.all("link")
            (links.firstOrNull { (it.attrs["rel"] ?: "alternate") == "alternate" } ?: links.firstOrNull())?.attrs?.get("href")
        } else {
            n.all("link").firstNotNullOfOrNull { it.text.trim().takeIf(String::isNotEmpty) ?: it.attrs["href"] }
                ?: n.child("guid")?.takeIf { it.attrs["ispermalink"] != "false" }?.text
        }
        val link = raw?.let { FeedLinks.clean(it, base) } ?: return null
        val said = (if (atom) n.child("updated") ?: n.child("published") else n.child("pubdate") ?: n.child("date"))?.text?.let(::date)
        val at = if (said == null || said > now + AHEAD_MS) now else said
        return FeedItem(title, link, at)
    }

    /** RFC 822 (RSS) or ISO 8601 (Atom, Dublin Core), in epoch milliseconds, or null. */
    fun date(raw: String): Long? {
        val t = raw.trim().replace(SPACES, " ")
        if (t.isEmpty() || t.length > 64) return null
        return try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: Exception) { null }
            ?: try { Instant.parse(t).toEpochMilli() } catch (_: Exception) { null }
            ?: try { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) { null }
            ?: try { ZonedDateTime.parse(t.substringAfter(", "), NO_DAY).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }
    private val SPACES = Regex("\\s+")
    private val NO_DAY = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm[:ss] [z][Z][O]", Locale.ROOT)
}

object FeedFind {
    private val LINK = Regex("<link\\b[^<>]{0,2000}>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([A-Za-z-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+)")
    private val TYPES = setOf("application/rss+xml", "application/atom+xml", "application/rdf+xml", "application/xml", "text/xml")

    /** The feeds a web page at [base] names in its head (link rel="alternate"), at most 3, https only. */
    fun links(html: String, base: String): List<String> = LINK.findAll(html.take(FeedXml.MAX_BYTES)).mapNotNull { m ->
        val a = ATTR.findAll(m.value).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim('"', '\'') }
        val rel = a["rel"]?.lowercase(Locale.ROOT)?.split(' ') ?: return@mapNotNull null
        val type = a["type"]?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()
        if ("alternate" !in rel || type !in TYPES) return@mapNotNull null
        a["href"]?.let { FeedLinks.clean(FeedXml.unescape(it), base) }?.takeIf { it.startsWith("https://") }
    }.distinct().take(3).toList()
}
