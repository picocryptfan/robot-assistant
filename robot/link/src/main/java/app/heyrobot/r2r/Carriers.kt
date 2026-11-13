// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LocalAddress
import app.heyrobot.core.link.OnionKey
import app.heyrobot.core.link.PushRoute
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2, D2; job L3): the three carriers LinkNode moves sealed
// envelopes with, each behind an interface so the two-peer JVM harness (§8.3) runs the same node over loopback, a fake push
// server and a fake Tor; and what LinkNode hands on. Sockets, Tor and the push connector live in this module alone
// (ProcessIsolationTest's linkStaysInItsProcess); nothing here logs (the node writes codes to the link log).

/** What :link asks of the phone: the time and whether Robot may go online (GrapheneOS's Network toggle). */
interface LinkEnv {
    /** Wall clock, ms. */
    fun now(): Long

    /** Robot's INTERNET permission is granted (the Network toggle is on): checked before every send, listen or start. */
    fun networkAllowed(): Boolean
}

/**
 * Reads a decrypted request. On the phone this is :linkparse, the isolated reader (L4: app/link LinkParseLink), the only
 * place that decodes a request's JSON (core LinkRequests.decode); what comes back is checked in :link (core
 * LinkParseWire.check: the request rebuilt must be the plaintext exactly). Null when :linkparse can't be started now, and
 * then nothing is read or answered; a request it failed on comes back Bad, PARSER (dropped, never answered).
 */
/**
 * A request kept on this phone, read again (its sender has dropped its copy, so a reader's failure mustn't cost it): null when
 * no reader could be reached or one was ended mid-read, and the item stays. A reader that failed on it gets one more try, on a
 * fresh process; what comes back then is about the item itself.
 */
internal fun RequestDecoder.stored(plain: ByteArray, cap: Int): RequestRead? {
    fun read() = try { decode(plain, cap) } catch (_: Exception) { null }
    val first = read()
    return if (first is RequestRead.Bad && first.problem == app.heyrobot.core.link.LinkProblem.PARSER) read() else first
}

fun interface RequestDecoder {
    fun decode(plain: ByteArray, cap: Int): RequestRead?
}

/**
 * Where :link hands on what happened (L5's pairing screens, L6's cards, briefings and Requests screen). Called on :link's
 * thread. [delivered] may come twice for one request after a restart (the replay window is written after it): a sink keeps
 * one per request id.
 */
interface LinkSink {
    /**
     * A request from [pairing] passed every check: it's the sink's (cards, answers on their own, the waiting inbox). L6: its
     * answer is the ack sent back (RECEIVED, HANDLED, WAITING for a card, DECLINED for Off, BUSY when nothing can take it).
     */
    fun delivered(pairing: String, request: LinkRequest): AckState
    /** L6: a request of yours ([type], [id], sent at [at]) was queued for [pairing]'s Robot. */
    fun queued(pairing: String, type: app.heyrobot.core.link.requests.RequestType, id: String, at: Long) {}
    /** The ack for a request you sent. */
    fun acked(pairing: String, requestId: String, state: AckState) {}
    /** A request you sent couldn't be delivered in its lifetime: "Couldn't reach Sam's Robot for 7 days. Try again?". */
    fun gaveUp(pairing: String, requestId: String) {}
    /** A pairing has its session both ways: a hello to your code was accepted, or your hello was answered. */
    fun paired(pairing: String) {}
    /** The other Robot unpaired. */
    fun unpaired(pairing: String) {}
    /** [pairing] passed its rate limits: paused until [until] (Unix seconds); called once a pause. */
    fun busy(pairing: String, until: Long) {}
    /** The other Robot raised the pairing to Anonymous (`mode.raise`). */
    fun raised(pairing: String) {}
    /** The other Robot asks to lower the pairing to Private (`mode.lower`): a card. */
    fun lowerAsked(pairing: String) {}
    /** L5: both asked, so the pairing is Private now on this side. */
    fun lowered(pairing: String) {}
    /** L5: the other Robot says it moved to a new phone: the pairing is paused until you answer (a card). */
    fun moved(pairing: String) {}
}

/**
 * The direct carrier: TCP between two phones on one network, Private pairings only (§2.2). L5-fix: only on Wi-Fi, Ethernet
 * or a VPN, never the mobile network (whose carrier-grade addresses look private: 100.64/10, 10/8), and bound to this
 * phone's addresses there, never every interface.
 */
interface DirectNet {
    /** Listens on a random port on this phone's addresses on Wi-Fi, Ethernet or a VPN; [accept] gets each connection. The port; 0 when there are none. */
    fun listen(accept: (Duplex) -> Unit): Int
    fun stopListening()
    /** The addresses it listens on, which a paired Robot on the same network can reach: private ranges only (core LocalAddress). */
    fun localAddresses(port: Int): List<LocalAddress>
    fun dial(address: LocalAddress): Duplex
}

/** L5-fix: a network the phone is on, as Android describes it (ConnectivityManager): its transports and its addresses. */
class NetView(val wifi: Boolean, val ethernet: Boolean, val vpn: Boolean, val cellular: Boolean, addresses: List<ByteArray>) {
    val addresses: List<ByteArray> = addresses.map { it.copyOf() }
    override fun toString(): String = "NetView(wifi=$wifi, ethernet=$ethernet, vpn=$vpn, cellular=$cellular)" // l10n: not text (toString for logs and tests)
}

/** L5-fix: where the direct carrier may listen and which addresses it gives (in a net.hint, and an in-person Private code). */
object DirectPlaces {
    /**
     * Wi-Fi or Ethernet, or a VPN (whose transports may include the mobile network under it: a private network both phones
     * are on, §2.2); never the mobile network itself.
     */
    fun allowed(n: NetView): Boolean = n.vpn || ((n.wifi || n.ethernet) && !n.cellular)

    /** This phone's private addresses (core LocalAddress) on [networks] it may listen on, at most 4, each once. */
    fun addresses(networks: List<NetView>, port: Int): List<LocalAddress> =
        networks.filter(::allowed).flatMap { it.addresses }.filter { LocalAddress.valid(it, port) }.map { LocalAddress(it, port) }
            .distinct().take(app.heyrobot.core.link.requests.RequestCaps.ADDRS)
}

/** Tor: Robot's own onion services and streams to other Robots' (TorRuntime on the phone). */
interface TorNet {
    /** Starts Tor when it isn't running and turns its network on; true once it can carry streams. Blocks, bounded. */
    fun up(): Boolean
    fun down()
    val running: Boolean
    /**
     * Publishes the onion service in [slot] (0 yours, 1 and up pairing codes' one-time ones) with [keyBlob] or a new key,
     * for [clients] only when given (their x25519 public keys); [accept] gets each stream.
     */
    fun publish(slot: Int, keyBlob: String?, clients: List<ByteArray>, accept: (Duplex) -> Unit): PublishedOnion
    fun unpublish(slot: Int)
    /** Holds [privateKey] as this Robot's client authorization for [onion] (in Tor's memory only). */
    fun clientAuth(onion: OnionKey, privateKey: ByteArray)
    fun dial(onion: OnionKey, isolation: String): Duplex
    /** A stream to a host on the internet, through Tor (an Anonymous pairing's push server). */
    fun dialHost(host: String, port: Int, isolation: String): Duplex
    /** What went online: Tor's counters read when it's time, and an ended hour's row (LinkNode's pump; may block briefly). */
    fun tally() {}
    /** fix-push-tls: how many times Tor came up; a new start means your onion must be published again. */
    val starts: Int get() = 0
    /** Tor was started and its process has ended on its own (Robot didn't stop it). */
    val exited: Boolean get() = false
    /**
     * fix-bridges-and-wording-b L1: Tor is up over your own bridge lines that you've since removed, edited or switched away
     * from; [up] starts it again without them. Never waits on a start.
     */
    val stale: Boolean get() = false
    /**
     * fix-bridges-lows (review-d L5): stops Tor only if it's still [stale] once no start is under way, so a start that moved
     * to your new lines meanwhile keeps running.
     */
    fun letGo() { if (stale) down() }
    /** :link sleeps: Tor stops, and no [up] starts it again until [wake]. */
    fun sleep() = down()
    fun wake() {}
}

/** Your push distributor: one instance per pairing and per open code, each with a random name (D6, R0 item 14). */
interface PushNet {
    /** A distributor app is installed and chosen. */
    fun available(): Boolean
    /** Asks the distributor for [instance]'s push address; it comes later, through LinkNode.onEndpoint. */
    fun register(instance: String): Boolean
    fun unregister(instance: String)
}

/** Posts one push body (RFC 8291 already applied) to [route], directly or through Tor ([viaTor]). */
fun interface PushPoster {
    fun post(route: PushRoute, body: ByteArray, ttlSeconds: Long, viaTor: Boolean, isolation: String)
}

/** RFC 8291 for a push address's keys: the connector's copy of Tink's web push encryption (R0 §5.5; Robot writes none). */
object WebPush {
    /** At most this much plaintext in one push body (UnifiedPush AND_3.1.0: 4,096 bytes of ciphertext). */
    const val MAX_PLAIN = 3_993

    fun seal(route: PushRoute, plain: ByteArray): ByteArray {
        require(plain.size in 1..MAX_PLAIN)
        return WebPushHybridEncrypt.Builder().withAuthSecret(route.auth).withRecipientPublicKey(route.p256dh).build().encrypt(plain, null)
    }
}

/** A socket as a [Duplex]. */
class SocketDuplex(private val s: Socket) : Duplex {
    override val input: InputStream = s.getInputStream()
    override val output: OutputStream = s.getOutputStream()
    override fun readTimeout(ms: Int) { s.soTimeout = ms }
    override fun close() { try { s.close() } catch (_: IOException) {} }
}

/**
 * The direct carrier over the phone's sockets. L5-fix: one listener per address [networks] gives on Wi-Fi, Ethernet or a
 * VPN (DirectPlaces), all on one port, never the wildcard address.
 */
class SocketDirectNet(
    private val networks: () -> List<NetView>,
    private val connectTimeoutMs: Int = 10_000,
    /** Where a dial goes (tests: loopback, which a LocalAddress never is). */
    private val socketAddress: (LocalAddress) -> InetSocketAddress = { InetSocketAddress(InetAddress.getByAddress(it.address), it.port) },
) : DirectNet {
    @Volatile private var servers: List<ServerSocket> = emptyList()
    @Volatile private var bound: List<LocalAddress> = emptyList()
    /** The port the listeners had last: taken again when free, so paired Robots' addresses for this phone stay right. */
    @Volatile private var lastPort = 0

    @Synchronized
    override fun listen(accept: (Duplex) -> Unit): Int {
        servers.firstOrNull()?.let { return it.localPort }
        val places = try { DirectPlaces.addresses(networks(), LISTEN_CHECK_PORT) } catch (_: Exception) { emptyList() }
        val open = ArrayList<ServerSocket>()
        var port = lastPort
        for (a in places) {
            val at = InetAddress.getByAddress(a.address)
            val s = try { ServerSocket(port, BACKLOG, at) } catch (_: IOException) {
                // The last port is taken on the first address: a new one, then the same on the others; else this address goes.
                if (open.isEmpty() && port != 0) try { ServerSocket(0, BACKLOG, at) } catch (_: IOException) { null } else null
            } ?: continue
            if (open.isEmpty()) port = s.localPort
            open += s
        }
        if (open.isEmpty()) return 0
        lastPort = port
        servers = open
        bound = open.map { LocalAddress(it.inetAddress.address, port) }
        for (s in open) Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                try { c.tcpNoDelay = true; accept(DirectRecord.accepted(SocketDuplex(c))) } catch (_: Exception) { try { c.close() } catch (_: IOException) {} }
            }
        }, "robot-link-direct").apply { isDaemon = true }.start() // l10n: not text (a thread's name)
        return port
    }

    @Synchronized
    override fun stopListening() {
        for (s in servers) try { s.close() } catch (_: IOException) {}
        servers = emptyList()
        bound = emptyList()
    }

    override fun localAddresses(port: Int): List<LocalAddress> = bound.filter { it.port == port }

    override fun dial(address: LocalAddress): Duplex {
        // review-link-net L1: a peer's private address only over the network that holds it (Wi-Fi, Ethernet or a VPN, as
        // DirectPlaces listens), the socket bound there (core HomeNets); otherwise no dial, nothing sent.
        val s = try { app.heyrobot.core.net.HomeNets.socket(address.address) } catch (_: IOException) { throw IOException() }
        try {
            s.connect(socketAddress(address), connectTimeoutMs)
            s.tcpNoDelay = true
            return DirectRecord.dialled(SocketDuplex(s))
        } catch (_: app.heyrobot.core.net.OffNetworkException) {
            // review-home-net-bind-b N2: it left off the network that holds it, closed before anything was written: no dial.
            try { s.close() } catch (_: IOException) {}
            throw IOException()
        } catch (x: IOException) {
            try { s.close() } catch (_: IOException) {}
            DirectRecord.failed()
            throw IOException()
        }
    }

    private companion object {
        const val BACKLOG = 8
        /** Any valid port: DirectPlaces checks addresses with one, and the listeners' own port replaces it. */
        const val LISTEN_CHECK_PORT = 1024
    }
}

/** Posts push bodies over [http]: directly (a TCP socket to the push server) or through [tor] (Anonymous). */
class HttpPushPoster(
    private val http: PushHttp,
    private val tor: () -> TorNet?,
    private val connectTimeoutMs: Int = 15_000,
    private val lookup: (String) -> List<InetAddress> = PUBLIC,
) : PushPoster {
    companion object {
        /** The phone's TLS (Conscrypt and the system's CAs, a new context each post) and its host name verifier. */
        fun platform(tor: () -> TorNet?): HttpPushPoster =
            HttpPushPoster(PushHttp(PushHttp.platformTrust(), hostnames = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()), tor)

        /** fix-push-tls: a direct post's host looked up, and refused unless every address is on the internet (not your network or the phone). */
        val PUBLIC: (String) -> List<InetAddress> = { host ->
            val all = InetAddress.getAllByName(host).toList()
            if (all.isEmpty() || all.any { !app.heyrobot.core.web.PublicAddress.isPublic(it.address) }) throw PushException(PushProblem.BAD_ADDRESS)
            all
        }
    }

    override fun post(route: PushRoute, body: ByteArray, ttlSeconds: Long, viaTor: Boolean, isolation: String) {
        val target = PushTarget.of(route.endpoint) ?: throw PushException(PushProblem.BAD_ADDRESS)
        // What went online: a post through Tor is part of Tor's hourly row (its counters hold it, and an Anonymous pairing's
        // push server stays out of the record); a direct one is a row with the server's host, the socket's bytes each way and how it ended.
        if (viaTor) return send(target, body, ttlSeconds, true, isolation) { _, _ -> }
        var sent = false
        var out = 0L
        var got = 0L
        try {
            send(target, body, ttlSeconds, false, isolation) { o, i -> out = o; got = i }
            sent = true
        } finally {
            NetRecord.record(NetPurpose.LINK_PUSH, target.host, NetCause.SWITCH, out, got, if (sent) NetResult.DONE else NetResult.FAILED)
        }
    }

    private fun send(target: PushTarget, body: ByteArray, ttlSeconds: Long, viaTor: Boolean, isolation: String, ended: (Long, Long) -> Unit) {
        val stream: Duplex = try {
            if (viaTor) {
                (tor() ?: throw PushException(PushProblem.UNREACHABLE)).dialHost(target.host, target.port, isolation)
            } else {
                val at = lookup(target.host).first()
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(at, target.port), connectTimeoutMs)
                } catch (x: IOException) {
                    try { s.close() } catch (_: IOException) {}
                    throw PushException(PushProblem.UNREACHABLE)
                }
                CountedDuplex(SocketDuplex(s), ended)
            }
        } catch (x: PushException) {
            throw x
        } catch (_: Exception) {
            throw PushException(PushProblem.UNREACHABLE)
        }
        stream.use { http.post(it, target, body, ttlSeconds) }
    }
}

/** How a pairing's mode limits its carriers (§2.2): direct only for Private; push through Tor for Anonymous. */
object Ways {
    fun directAllowed(mode: LinkMode): Boolean = mode == LinkMode.PRIVATE
    fun pushViaTor(mode: LinkMode): Boolean = mode == LinkMode.ANONYMOUS
}
