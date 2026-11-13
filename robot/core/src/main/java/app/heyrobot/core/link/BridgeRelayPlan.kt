// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeRoute

// Pure. 1.4, tor-bridge-relay (review-home-net-bind-c L4; STATUS question 85): a Tor bridge you save at a home address
// (10.x, 192.168.x, 100.64.x and the rest of HomeRoute.home) goes out under the same rule as every other home connection.
// Tor and lyrebird dial bridges themselves, so they can't use core HomeNets; with such a bridge, :link's TorRuntime starts
// a SOCKS5 relay inside Robot (link BridgeRelay) and Tor's torrc sends Tor (Socks5Proxy) and lyrebird (TOR_PT_PROXY, which
// Tor makes from the same options) through it. This file says when the relay is needed and where it may connect: only
// where the saved lines lead (each [BridgeTarget]), nothing else.

/** A host (an address literal or a name, in lower case) and port a bridge line has Tor or lyrebird connect to. */
class BridgeTarget(host: String, val port: Int) {
    val host: String = host.removePrefix("[").removeSuffix("]").lowercase()
    /** The address when [host] is a literal, else null (a name, looked up when the relay connects). */
    val literal: ByteArray? = LanRule.literal(this.host)

    fun matches(h: String, p: Int): Boolean {
        if (p != port) return false
        val bare = h.removePrefix("[").removeSuffix("]")
        val a = LanRule.literal(bare)
        return if (literal != null || a != null) a != null && literal != null && a.contentEquals(literal) else bare.equals(host, ignoreCase = true)
    }

    override fun toString(): String = "BridgeTarget" // l10n: not text (toString for logs and tests; never the address)
}

object BridgeRelayPlan {
    /**
     * Where [b] has Tor or lyrebird connect: a plain or obfs4 bridge, its address; WebTunnel, its addr argument or its
     * url's host (port 443 for https, 80 for http, as lyrebird 0.9.0's webtunnel client does); meek, each url and front in
     * its targets argument when it has one, else its front or its url's host (lyrebird puts the front in the url's place).
     * Snowflake: none (its broker is reached through a front and its relays over WebRTC, which the relay doesn't carry), so
     * its lines aren't used while the relay is. A line this can't read for sure leads nowhere the relay lets through.
     */
    fun targets(b: BridgeLine): List<BridgeTarget> = read(b) ?: emptyList()

    fun targets(lines: List<BridgeLine>): List<BridgeTarget> = lines.flatMap(::targets)

    /**
     * The relay is needed: one of [lines] leads to a home address, or can't be read for sure (review-tor-bridge-relay-b L1:
     * lyrebird dials a port name such as "https" too, and reads meek's targets before its url). Without one, Tor's torrc
     * is exactly as before.
     */
    fun needed(lines: List<BridgeLine>, home: (ByteArray) -> Boolean = HomeRoute::home): Boolean =
        lines.any { b -> read(b)?.any { t -> t.literal?.let(home) == true } ?: true }

    /** [targets], or null when [b] can't be read for sure. BridgeLine.parse refuses a key given twice. */
    private fun read(b: BridgeLine): List<BridgeTarget>? {
        val words = b.text.split(' ')
        val args = words.mapNotNull { w -> w.indexOf('=').takeIf { it > 0 }?.let { w.substring(0, it) to w.substring(it + 1) } }.toMap()
        return when (b.transport) {
            null, "obfs4" -> hostPort(words[if (b.transport == null) 0 else 1], null)?.let(::listOf) // l10n: not text (Tor's transport name)
            "webtunnel" -> { // l10n: not text (Tor's transport name)
                val url = args["url"] ?: return emptyList() // l10n: not text (a bridge line key; lyrebird dials nothing without it)
                (args["addr"]?.let { hostPort(it, null) ?: return null } ?: urlTarget(url))?.let(::listOf) // l10n: not text (a bridge line key)
            }
            "meek_lite" -> { // l10n: not text (Tor's transport name)
                args["targets"]?.let { return meekTargets(it) } // l10n: not text (a bridge line key)
                val url = args["url"] ?: return emptyList() // l10n: not text (a bridge line key)
                meekTarget(url, args["front"] ?: "")?.let(::listOf) // l10n: not text (a bridge line key)
            }
            "snowflake" -> emptyList() // l10n: not text (Tor's transport name)
            else -> null
        }
    }

    /** lyrebird 0.9.0's meek targets: "url|front+front,url|front"; an empty front is the url's own host. */
    private fun meekTargets(s: String): List<BridgeTarget>? {
        val out = ArrayList<BridgeTarget>()
        var rest = s
        while (true) {
            val bar = rest.indexOf('|')
            if (bar < 0) return null
            val url = rest.substring(0, bar)
            val after = rest.substring(bar + 1)
            val comma = after.indexOf(',')
            for (front in (if (comma < 0) after else after.substring(0, comma)).split('+')) out += meekTarget(url, front) ?: return null
            if (comma < 0) return out
            rest = after.substring(comma + 1)
        }
    }

    /** meek dials [front] (its own port, or the url scheme's) in the url's place, or the url's host when [front] is empty. */
    private fun meekTarget(url: String, front: String): BridgeTarget? =
        if (front.isEmpty()) urlTarget(url) else defaultPort(url)?.let { hostPort(front, it) }

    /** [targets] lets [host] and [port] through: anything else is refused. */
    fun allows(targets: List<BridgeTarget>, host: String, port: Int): Boolean = targets.any { it.matches(host, port) }

    /** tor-bridge-relay-c L4: your own Snowflake lines are skipped beside a home bridge (the bridges screen says so). */
    fun skipsSnowflake(lines: List<BridgeLine>, home: (ByteArray) -> Boolean = HomeRoute::home): Boolean =
        lines.any { it.transport == "snowflake" } && needed(lines, home) // l10n: not text (Tor's transport name)

    /** The lines Tor uses through the relay: all but Snowflake's. */
    fun relayed(lines: List<BridgeLine>): List<BridgeLine> = lines.filter { it.transport != "snowflake" } // l10n: not text (Tor's transport name)

    /** 443 for an https url, 80 for http; null for anything else. */
    private fun defaultPort(url: String): Int? = if (!url.contains("://")) null else when (url.substringBefore("://").lowercase()) {
        "https" -> 443 // l10n: not text (a url scheme)
        "http" -> 80 // l10n: not text (a url scheme)
        else -> null
    }

    /** An http or https url's host and port (its own, or the scheme's). */
    private fun urlTarget(url: String): BridgeTarget? {
        val port = defaultPort(url) ?: return null
        val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        // Go's url package decodes %XX in a host ("%31%39%32.168.1.5" is 192.168.1.5): not read for sure.
        return if (authority.isEmpty() || authority.contains('%')) null else hostPort(authority, port)
    }

    /** "host:port", "[v6]:port", or a bare host given [port]. */
    private fun hostPort(s: String, port: Int?): BridgeTarget? {
        val v6 = Regex("""\[([0-9A-Fa-f:.]+)](?::(\d{1,5}))?""").matchEntire(s) // l10n: not text (a pattern)
        if (v6 != null) return (v6.groupValues[2].toIntOrNull() ?: port)?.takeIf { it in 1..65_535 }?.let { BridgeTarget(v6.groupValues[1], it) }
        val i = s.lastIndexOf(':')
        if (i < 0) return port?.let { BridgeTarget(s, it) }
        if (s.indexOf(':') != i) return port?.let { BridgeTarget(s, it) }
        val p = s.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        return BridgeTarget(s.substring(0, i), p)
    }
}
