// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2, §2.3; job L1): the two connection modes, and the ways to reach
// a Robot that pairing codes, pairing hellos and `net.hint` carry. No network class: an address here is bytes, read and
// checked by hand, and only the carriers (L3, in the `link` module) ever connect to one.

/** The connection mode of a pairing (decision 1 of 12 Oct). */
enum class LinkMode(val code: Int, val wire: String) {
    /** Faster: direct on a shared network, else your push server (it sees that your phones talk), else Tor. */
    PRIVATE(1, "private"), // l10n: not text (a wire value)
    /** Neither network learns who talks to whom: Tor and push through Tor, never direct. */
    ANONYMOUS(2, "anonymous"); // l10n: not text (a wire value)

    companion object {
        fun of(code: Int): LinkMode? = entries.firstOrNull { it.code == code }

        /** L5-fix: as `net.hint` spells it ([wire]). */
        fun ofWire(wire: String): LinkMode? = entries.firstOrNull { it.wire == wire }

        /** The pairing uses the stricter of the two modes picked at first contact. */
        fun stricter(a: LinkMode, b: LinkMode): LinkMode = if (a == ANONYMOUS || b == ANONYMOUS) ANONYMOUS else PRIVATE
    }
}

/** Raising and lowering a pairing's mode (§2.2): raising is one-sided and at once; lowering needs the other side's owner. */
object ModeChange {
    /**
     * Either Robot raises a Private pairing to Anonymous at once (`mode.raise`), and a `mode.raise` that arrives switches
     * the other side at once too (it shows it). Raising an Anonymous pairing changes nothing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun raised(current: LinkMode): LinkMode = LinkMode.ANONYMOUS

    /**
     * The mode after asking to lower ([LinkMode.PRIVATE], `mode.lower`): Private only once the other side's owner accepted
     * it on a card ([otherAccepted]); until then, and if they decline, the pairing stays Anonymous.
     */
    fun lowered(current: LinkMode, otherAccepted: Boolean): LinkMode = if (otherAccepted) LinkMode.PRIVATE else current
}

/**
 * A push address (an UnifiedPush endpoint) and its RFC 8291 keys ([p256dh]: an uncompressed P-256 point, 65 bytes;
 * [auth]: 16 bytes). The endpoint is an https address of at most 1,000 bytes (AND_3.1.0) in printable ASCII.
 */
class PushRoute(val endpoint: String, p256dh: ByteArray, auth: ByteArray) {
    private val k = p256dh.copyOf()
    private val a = auth.copyOf()
    val p256dh: ByteArray get() = k.copyOf()
    val auth: ByteArray get() = a.copyOf()

    init {
        require(valid(endpoint, k, a))
    }

    internal fun write(w: BinWriter) {
        w.u16(endpoint.length).bytes(endpoint.toByteArray(Charsets.US_ASCII)).bytes(k).bytes(a)
    }

    override fun toString(): String = "PushRoute(endpoint=${endpoint.length} chars)" // l10n: not text (toString for logs and tests)

    companion object {
        const val MAX_ENDPOINT = 1_000
        const val KEY = 65
        const val AUTH = 16
        /** The worst case in bytes, as [write] lays it out. */
        const val MAX_WRITTEN = 2 + MAX_ENDPOINT + KEY + AUTH

        // A scheme check, not an address: no address is ever written in this package (the plan's linkStaysInItsProcess).
        private const val SCHEME = "https:" // l10n: not text (a URL scheme)

        fun endpointOk(e: String): Boolean =
            e.length in SCHEME.length + 3..MAX_ENDPOINT && e.all { it in '!'..'~' } && e.startsWith(SCHEME) &&
                e.regionMatches(SCHEME.length, "//", 0, 2) && e[SCHEME.length + 2] != '/'

        /** L16: an address a peer gave you: also one out on the internet. Your own distributor's stays yours to choose. */
        fun peerEndpointOk(e: String): Boolean = endpointOk(e) && PushHost.public(e)

        fun valid(endpoint: String, p256dh: ByteArray, auth: ByteArray): Boolean =
            endpointOk(endpoint) && p256dh.size == KEY && p256dh[0].toInt() == 0x04 && auth.size == AUTH && !LinkBytes.allZero(auth)

        internal fun read(r: BinReader): PushRoute {
            val n = r.u16()
            if (n == 0 || n > MAX_ENDPOINT) throw LinkBad
            val e = String(r.bytes(n), Charsets.ISO_8859_1)
            val k = r.bytes(KEY)
            val a = r.bytes(AUTH)
            if (!valid(e, k, a) || !PushHost.public(e)) throw LinkBad
            return PushRoute(e, k, a)
        }
    }
}

/** A Robot's onion service, as its 32-byte ed25519 public key (the v3 address, base32 with its checksum, is the carrier's to spell). */
class OnionKey(key: ByteArray) {
    private val k = key.copyOf()
    val key: ByteArray get() = k.copyOf()

    init {
        require(k.size == SIZE && !LinkBytes.allZero(k))
    }

    override fun equals(other: Any?): Boolean = other is OnionKey && other.k.contentEquals(k)
    override fun hashCode(): Int = k.contentHashCode()
    override fun toString(): String = "OnionKey" // l10n: not text (toString for logs and tests)

    companion object {
        const val SIZE = 32

        /**
         * A client authorization key for an onion service (Tor's x25519 key, 32 bytes; L3): not all zeros. Its owner made it
         * for one pairing and gives it only inside a sealed hello or the session.
         */
        fun authOk(key: ByteArray): Boolean = key.size == SIZE && !LinkBytes.allZero(key)

        internal fun read(r: BinReader): OnionKey = r.bytes(SIZE).let { if (LinkBytes.allZero(it)) throw LinkBad else OnionKey(it) }
    }
}

/**
 * An address on a network both phones are on, for a Private pairing's direct carrier: IPv4 in 10/8, 172.16/12,
 * 192.168/16 or 100.64/10 (a private network such as a mesh VPN), IPv6 in fc00::/7, and a port from 1,024 up. Never a
 * public address: a code or a hint can't make a Robot connect anywhere else.
 */
class LocalAddress(address: ByteArray, val port: Int) {
    private val a = address.copyOf()
    val address: ByteArray get() = a.copyOf()
    val v6: Boolean get() = a.size == 16

    init {
        require(valid(a, port))
    }

    internal fun write(w: BinWriter) {
        w.u8(if (v6) 6 else 4).bytes(a).u16(port)
    }

    /** As `net.hint` carries it: the hex of [write]'s bytes. */
    fun hex(): String = LinkBytes.hex(BinWriter(19).also(::write).toByteArray())

    override fun equals(other: Any?): Boolean = other is LocalAddress && other.a.contentEquals(a) && other.port == port
    override fun hashCode(): Int = a.contentHashCode() * 31 + port
    override fun toString(): String = "LocalAddress(v6=$v6)" // l10n: not text (toString for logs and tests)

    companion object {
        const val MAX_WRITTEN = 1 + 16 + 2

        fun valid(a: ByteArray, port: Int): Boolean {
            if (port !in 1024..65535) return false
            fun u(i: Int) = a[i].toInt() and 0xFF
            return when (a.size) {
                4 -> u(0) == 10 || (u(0) == 172 && u(1) in 16..31) || (u(0) == 192 && u(1) == 168) || (u(0) == 100 && u(1) in 64..127)
                16 -> (u(0) and 0xFE) == 0xFC
                else -> false
            }
        }

        internal fun read(r: BinReader): LocalAddress {
            val a = when (r.u8()) { 4 -> r.bytes(4); 6 -> r.bytes(16); else -> throw LinkBad }
            val port = r.u16()
            if (!valid(a, port)) throw LinkBad
            return LocalAddress(a, port)
        }

        /** The address [hex] spells ([hex]'s form), or null. */
        fun ofHex(hex: String): LocalAddress? {
            val b = (if (hex.length == 14) LinkBytes.unhex(hex, 7) else if (hex.length == 38) LinkBytes.unhex(hex, 19) else null) ?: return null
            return try { BinReader(b).let { r -> read(r).also { r.end() } } } catch (_: LinkBad) { null }
        }
    }
}
