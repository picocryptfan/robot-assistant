// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

// Pure. 1.5, Robot to Robot's Tor bridges (robot-to-robot-carriers-research.md, "The build job"; job L-bridges): which way
// Tor reaches its first relay when a network blocks Tor. Settings › Robot to Robot › Tor bridges keeps a [BridgeChoice] in
// link_settings.json (LinkSettings.bridges, Off by default); :link's TorRuntime turns it into [TorBridges.routes] and writes
// each route's Bridge lines into the torrc (link TorConfig), only when a bridge is on. Lyrebird (0.9.0, built from source,
// liblyrebird.so) carries obfs4, meek, Snowflake and WebTunnel; Tor starts it itself, and only for a bridge line that
// names one of them.
//
// Every bridge line Tor reads passes [BridgeLine.parse]: printable ASCII words, a known transport (or none, for a plain
// bridge), an address and port, a fingerprint where the transport needs one, key=value arguments. Nothing else, so a line
// you paste can't add any other torrc option (a new line, a comment, a quote or a second option are all refused).

/** Settings › Robot to Robot › Tor bridges. */
enum class BridgeChoice {
    /** Tor connects as it always has, with no bridge (the default). */
    OFF,
    /** Tor on its own first; when it stalls, Snowflake, then the built-in obfs4 bridges, then meek. */
    AUTO,
    SNOWFLAKE,
    /** Tor Browser's built-in obfs4 bridges. */
    OBFS4,
    /** Bridge lines you enter (LinkSettings.ownBridges). */
    OWN,
}

/** One way Tor reaches the Tor network. [code]: stable, the link log's TOR entry (b, when Tor finished bootstrapping). */
enum class TorRoute(val code: Int) {
    PLAIN(1), SNOWFLAKE(2), OBFS4(3), MEEK(4), OWN(5);

    /** A bridge is in use: Tor's first hop is a bridge, not a public relay. */
    val bridged: Boolean get() = this != PLAIN
}

/** One bridge as Tor's Bridge option takes it: [transport] (null for a plain bridge) and the whole line, [text]. */
class BridgeLine private constructor(val transport: String?, val text: String) {
    override fun equals(other: Any?): Boolean = other is BridgeLine && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = "BridgeLine(${transport ?: "plain"})" // l10n: not text (toString for logs and tests; never the address)

    companion object {
        /** The transports lyrebird carries that Robot writes (obfs2, obfs3 and ScrambleSuit are long retired). */
        val TRANSPORTS = listOf("meek_lite", "obfs4", "snowflake", "webtunnel") // l10n: not text (Tor's transport names)
        /** A Snowflake line with its STUN servers is about 500 characters; WebTunnel's url can be long. */
        const val MAX_LENGTH = 1_500
        const val MAX_ARGS = 16

        private val KEY = Regex("[A-Za-z0-9_-]{1,32}") // l10n: not text (a pattern)
        private val FINGERPRINT = Regex("[0-9A-Fa-f]{40}") // l10n: not text (a pattern)
        private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})""") // l10n: not text (a pattern)
        private val IPV6 = Regex("""\[([0-9A-Fa-f:.]{2,45})]:(\d{1,5})""") // l10n: not text (a pattern)
        /** The arguments each transport can't do without. */
        private val NEEDS = mapOf(
            "obfs4" to setOf("cert", "iat-mode"), "meek_lite" to setOf("url"), "snowflake" to setOf("url"), "webtunnel" to setOf("url"), // l10n: not text (bridge line keys)
        )
        /** Transports whose lines carry the bridge's fingerprint (meek and Snowflake may leave it out). */
        private val FINGERPRINTED = setOf(null, "obfs4", "webtunnel") // l10n: not text (Tor's transport names)

        /** A bridge line from Tor Browser, bridges.torproject.org or a friend ("Bridge " in front allowed), or null. */
        fun parse(raw: String): BridgeLine? {
            if (raw.length > MAX_LENGTH + 10) return null
            var s = raw.trim()
            if (s.length >= 7 && s.substring(0, 7).equals("Bridge ", ignoreCase = true)) s = s.substring(7).trim() // l10n: not text (Tor's option name)
            if (s.isEmpty() || s.length > MAX_LENGTH) return null
            // Printable ASCII words split by spaces or tabs only: no line break, no quote, no backslash, no torrc comment.
            if (s.any { !(it == ' ' || it == '\t' || it.code in 0x21..0x7E) || it == '"' || it == '\\' || it == '#' }) return null
            val words = s.split(' ', '\t').filter { it.isNotEmpty() }
            var i = 0
            val transport = words[0].takeIf { it in TRANSPORTS }?.also { i++ }
            val address = words.getOrNull(i++) ?: return null
            if (!addressOk(address)) return null
            val fingerprint = words.getOrNull(i)?.takeIf { FINGERPRINT.matches(it) }?.also { i++ }
            if (fingerprint == null && transport in FINGERPRINTED) return null
            val args = words.drop(i)
            if (args.size > MAX_ARGS || (transport == null && args.isNotEmpty())) return null
            val keys = HashSet<String>()
            for (a in args) {
                val eq = a.indexOf('=')
                if (eq <= 0 || eq == a.length - 1) return null
                val k = a.substring(0, eq)
                if (!KEY.matches(k) || !keys.add(k)) return null
            }
            if (transport != null && !keys.containsAll(NEEDS.getValue(transport))) return null
            val text = (listOfNotNull(transport, address, fingerprint?.uppercase()) + args).joinToString(" ")
            return if (text.length <= MAX_LENGTH) BridgeLine(transport, text) else null
        }

        private fun addressOk(a: String): Boolean {
            IPV4.matchEntire(a)?.let { m ->
                val g = m.groupValues
                return (1..4).all { g[it].toInt() in 0..255 && (g[it].length == 1 || !g[it].startsWith("0")) } && portOk(g[5])
            }
            IPV6.matchEntire(a)?.let { m -> return m.groupValues[1].count { it == ':' } in 2..7 && portOk(m.groupValues[2]) }
            return false
        }

        private fun portOk(p: String): Boolean = !p.startsWith("0") && p.toInt() in 1..65_535
    }
}

object TorBridges {
    /** "Your own bridge": at most this many lines. */
    const val MAX_OWN = 8
    /** All of "Your own bridge" as you type or paste it. */
    const val MAX_OWN_TEXT = MAX_OWN * (BridgeLine.MAX_LENGTH + 1)

    /** The routes Tor tries for [choice], in order. Your own bridge without a line is no route at all: Tor stays off. */
    fun routes(choice: BridgeChoice, own: List<BridgeLine>): List<TorRoute> = when (choice) {
        BridgeChoice.OFF -> listOf(TorRoute.PLAIN)
        BridgeChoice.AUTO -> listOf(TorRoute.PLAIN, TorRoute.SNOWFLAKE, TorRoute.OBFS4, TorRoute.MEEK)
        BridgeChoice.SNOWFLAKE -> listOf(TorRoute.SNOWFLAKE)
        BridgeChoice.OBFS4 -> listOf(TorRoute.OBFS4)
        BridgeChoice.OWN -> if (own.isEmpty()) emptyList() else listOf(TorRoute.OWN)
    }

    /** The Bridge lines of [route]: none for plain Tor. */
    fun lines(route: TorRoute, own: List<BridgeLine>): List<BridgeLine> = when (route) {
        TorRoute.PLAIN -> emptyList()
        TorRoute.SNOWFLAKE -> TorBuiltInBridges.snowflake
        TorRoute.OBFS4 -> TorBuiltInBridges.obfs4
        TorRoute.MEEK -> TorBuiltInBridges.meek
        TorRoute.OWN -> own
    }

    /**
     * "Your own bridge" as typed: one bridge a line, blank lines skipped. Null when a line isn't a bridge line, when there
     * is none, more than [MAX_OWN], or the same bridge twice.
     */
    fun parseOwn(text: String): List<BridgeLine>? {
        if (text.length > MAX_OWN_TEXT) return null
        val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.isEmpty() || lines.size > MAX_OWN) return null
        val out = lines.map { BridgeLine.parse(it) ?: return null }
        return if (out.distinct().size == out.size) out else null
    }
}

/**
 * Tor Browser's built-in bridges, copied from tor-browser-build's projects/tor-expert-bundle/pt_config.json (main, last
 * changed by 01d3e9d35aab on 9 Apr 2026, "Update STUN Server List for Snowflake Default Bridge Line"; the file's SHA-256
 * 6169848125847b9d471de800b7c4278f2fb142e4bffc03e84700aa654d90ae1c), taken 28 Oct 2026; checked again 2 Nov 2026, the same file. Its obfs4 and meek lines are the
 * same as Briar's onionwrapper 0.1.7 lists; its Snowflake lines differ only in their STUN servers. Refreshed at each release
 * (TorBridgesTest pins them, so a change is deliberate). WebTunnel has no built-in bridge: you enter one as your own.
 * Snowflake's broker, front domains and STUN servers come from these lines, not from lyrebird, so they change without a
 * new lyrebird.
 */
object TorBuiltInBridges {
    const val TAKEN = "2026-11-02" // l10n: not text (a date)

    private val SNOWFLAKE = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn", // l10n: not text (a bridge line)
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn", // l10n: not text (a bridge line)
    )
    private val OBFS4 = listOf(
        "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0", // l10n: not text (a bridge line)
        "obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNinLmxQDpVa+7kTOa9/m+tGWT1SmSYpQ9uTBGa6Hw iat-mode=0", // l10n: not text (a bridge line)
        "obfs4 146.57.248.225:22 10A6CD36A537FCE513A322361547444B393989F0 cert=K1gDtDAIcUfeLqbstggjIw2rtgIKqdIhUlHp82XRqNSq/mtAjp1BIC9vHKJ2FAEpGssTPw iat-mode=0", // l10n: not text (a bridge line)
        "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0", // l10n: not text (a bridge line)
        "obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 cert=2uplIpLQ0q9+0qMFrK5pkaYRDOe460LL9WHBvatgkuRr/SL31wBOEupaMMJ6koRE6Ld0ew iat-mode=0", // l10n: not text (a bridge line)
        "obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1", // l10n: not text (a bridge line)
        "obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=1", // l10n: not text (a bridge line)
    )
    private val MEEK = listOf(
        "meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN", // l10n: not text (a bridge line)
    )

    /** The texts as taken (TorBridgesTest pins their hash). */
    val texts: Map<TorRoute, List<String>> = mapOf(TorRoute.SNOWFLAKE to SNOWFLAKE, TorRoute.OBFS4 to OBFS4, TorRoute.MEEK to MEEK)

    val snowflake: List<BridgeLine> = SNOWFLAKE.map { BridgeLine.parse(it)!! }
    val obfs4: List<BridgeLine> = OBFS4.map { BridgeLine.parse(it)!! }
    val meek: List<BridgeLine> = MEEK.map { BridgeLine.parse(it)!! }
}

/** What :link's Tor uses: the routes it tries, in order, and your own bridge lines (LinkSettings.torPlan). */
class TorPlan(val routes: List<TorRoute>, val own: List<BridgeLine> = emptyList()) {
    fun lines(route: TorRoute): List<BridgeLine> = TorBridges.lines(route, own)

    /** [route] needs lyrebird: one of its lines names a transport (a plain bridge doesn't). */
    fun needsLyrebird(route: TorRoute): Boolean = lines(route).any { it.transport != null }

    /**
     * The order this start tries them in: from the route Tor is still connecting over ([running]), else the one that
     * last worked ([worked]), else the first; each once, then round to the start.
     */
    fun order(running: TorRoute?, worked: TorRoute?): List<TorRoute> {
        val from = routes.indexOf(running).takeIf { it >= 0 } ?: routes.indexOf(worked).takeIf { it >= 0 } ?: 0
        return routes.indices.map { routes[(from + it) % routes.size] }
    }

    override fun toString(): String = "TorPlan($routes)" // l10n: not text (toString for logs and tests; never a bridge)

    companion object {
        /** Off: Tor as it always was. */
        val PLAIN = TorPlan(listOf(TorRoute.PLAIN))

        fun of(s: LinkSettings): TorPlan = TorPlan(s.torRoutes, s.ownBridges)
    }
}

/**
 * Tor's bootstrap on one route, watched from [start] (milliseconds): done at 100%; with more routes to try, stalled once
 * its progress hasn't moved for [stallMs]; given up at [timeoutMs] either way.
 */
class BootstrapWatch(private val start: Long, private val stallMs: Long, private val timeoutMs: Long, private val fallback: Boolean) {
    enum class Verdict { DONE, WAIT, STALLED, TIMED_OUT }

    private var best = -1
    private var since = start

    fun see(percent: Int, now: Long): Verdict {
        if (percent >= 100) return Verdict.DONE
        if (percent > best) { best = percent; since = now }
        if (now - start >= timeoutMs) return Verdict.TIMED_OUT
        if (fallback && now - since >= stallMs) return Verdict.STALLED
        return Verdict.WAIT
    }

    companion object {
        /** Plain Tor stalled this long without progress: try the next route (EST, the research's 30 s; measured on the phones). */
        const val STALL_PLAIN_MS = 30_000L
        /** A bridge's first contact (Snowflake's broker, a proxy, then the bridge) is slower: 60 s (EST). */
        const val STALL_BRIDGE_MS = 60_000L
    }
}
