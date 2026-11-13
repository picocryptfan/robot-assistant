// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.link.BridgeRelayPlan
import app.heyrobot.core.link.BridgeTarget
import app.heyrobot.core.net.HomeNets
import app.heyrobot.core.net.OffNetworkException
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

// :LINK PROCESS. 1.4, tor-bridge-relay (review-home-net-bind-c L4; STATUS question 85): Tor's SOCKS5 proxy while a bridge
// you saved leads to a home address (core BridgeRelayPlan). Tor reaches its bridges through it (Socks5Proxy), and lyrebird
// too (Tor hands it the same proxy as TOR_PT_PROXY), so each connection to a home address goes out under core HomeNets'
// rule (Wi-Fi, Ethernet or a VPN that routes it; never mobile data), like every other home connection Robot makes.
//
// It listens on a loopback port the system picks and asks a username and password made fresh at each Tor start (RFC 1929;
// a wrong one is answered and closed at once), so no other app on the phone can use it. It connects only where the saved
// lines lead (anything else is refused before any lookup), carries bytes and nothing else, holds no data, logs nothing, and
// is closed with Tor (TorRuntime.stopProcess), with every connection it carries.

/** What Tor's torrc names: Socks5Proxy [host]:[port], Socks5ProxyUsername [user], Socks5ProxyPassword [pass]. */
class RelayAuth(val host: String, val port: Int, val user: String, val pass: String) {
    override fun toString(): String = "RelayAuth" // l10n: not text (toString for logs and tests; never the password)
}

class BridgeRelay private constructor(
    private val server: ServerSocket,
    private val targets: List<BridgeTarget>,
    private val socketFor: (ByteArray) -> Socket,
    private val reach: (Socket, InetSocketAddress) -> Unit,
) : Closeable {
    val auth = RelayAuth(server.inetAddress.hostAddress ?: throw IOException(), server.localPort, token(), token())
    private val open = HashSet<Socket>()
    private var closed = false
    /**
     * tor-bridge-relay-c L1: connections still in the handshake (no password yet), at most [WAITING] at once. A slot is
     * freed as soon as the password checks (review-tor-bridge-relay-b L2), so Tor's own connections, slow to connect or
     * not, never hold one.
     */
    private val waiting = AtomicInteger()
    private val loop = Thread(::acceptLoop, "bridge-relay").apply { isDaemon = true } // l10n: not text (a thread name)

    private fun acceptLoop() {
        while (true) {
            val c = try { server.accept() } catch (_: IOException) { return }
            if (waiting.incrementAndGet() > WAITING) { waiting.decrementAndGet(); quiet(c); continue }
            if (!track(c)) { waiting.decrementAndGet(); return }
            Thread({ serve(c) }, "bridge-relay").apply { isDaemon = true }.start() // l10n: not text (a thread name)
        }
    }

    /** One connection from Tor or lyrebird: the greeting, the password, one CONNECT to where a saved line leads, then bytes. */
    private fun serve(c: Socket) {
        var far: Socket? = null
        var shaking = true
        try {
            c.tcpNoDelay = true
            val i = DataInputStream(Deadline(c, System.nanoTime() + HANDSHAKE_MS * 1_000_000L))
            val o = c.getOutputStream()
            if (i.readUnsignedByte() != 5) return
            val methods = ByteArray(i.readUnsignedByte()).also(i::readFully)
            if (methods.none { it.toInt() == 2 }) { o.write(byteArrayOf(5, 0xFF.toByte())); return }
            o.write(byteArrayOf(5, 2))
            if (i.readUnsignedByte() != 1) return
            val u = ByteArray(i.readUnsignedByte()).also(i::readFully)
            val p = ByteArray(i.readUnsignedByte()).also(i::readFully)
            val ok = MessageDigest.isEqual(u, auth.user.toByteArray(Charsets.US_ASCII)) and MessageDigest.isEqual(p, auth.pass.toByteArray(Charsets.US_ASCII))
            o.write(byteArrayOf(1, if (ok) 0 else 1))
            if (!ok) return
            shaking = false; waiting.decrementAndGet()
            if (i.readUnsignedByte() != 5) return
            val cmd = i.readUnsignedByte()
            i.readUnsignedByte()
            val host = when (i.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(i::readFully)).hostAddress
                4 -> InetAddress.getByAddress(ByteArray(16).also(i::readFully)).hostAddress
                3 -> String(ByteArray(i.readUnsignedByte()).also(i::readFully), Charsets.US_ASCII)
                else -> { reply(o, ADDRESS_TYPE); return }
            } ?: return
            val port = i.readUnsignedShort()
            if (cmd != 1) { reply(o, COMMAND); return }
            if (!BridgeRelayPlan.allows(targets, host, port)) { reply(o, NOT_ALLOWED); return }
            val (s, failure) = connect(host, port)
            if (s == null) { reply(o, failure); return }
            far = s
            reply(o, 0)
            c.soTimeout = 0
            // review-tor-bridge-relay-b L3: every stream taken here, inside the try (a close() from Tor stopping makes them throw).
            val fromFar = s.getInputStream()
            val fromNear = c.getInputStream()
            val toFar = s.getOutputStream()
            Thread({ pipe(fromFar, o, c, s) }, "bridge-relay").apply { isDaemon = true }.start() // l10n: not text (a thread name)
            pipe(fromNear, toFar, c, s)
        } catch (_: Exception) {
        } finally {
            if (shaking) waiting.decrementAndGet()
            quiet(c); far?.let(::quiet)
            synchronized(open) { open.remove(c); far?.let(open::remove) }
        }
    }

    /**
     * A literal as it is, a name looked up; each address under core HomeNets' rule (a home one off its network is refused unsent).
     * The socket, or none and this connection's own reply code (tor-bridge-relay-c L2: never another connection's).
     * fix-tor-lows-141 (review-141-relay-native L3): each socket is tracked before it connects, so [close] stops a dial in
     * progress, and one that ends after [close] is already closed; nothing opens once Tor has stopped.
     */
    private fun connect(host: String, port: Int): Pair<Socket?, Int> {
        val addrs = LanRule.literal(host)?.let { listOf(it) } ?: try { InetAddress.getAllByName(host).map { it.address } } catch (_: Exception) { return null to HOST_UNREACHABLE }
        var off = false
        for (a in addrs) {
            val s = try { socketFor(a) } catch (_: OffNetworkException) { off = true; continue } catch (_: Exception) { continue }
            if (!track(s)) return null to HOST_UNREACHABLE
            try {
                reach(s, InetSocketAddress(InetAddress.getByAddress(a), port))
                return s to 0
            } catch (_: OffNetworkException) { off = true } catch (_: Exception) {}
            quiet(s)
            synchronized(open) { open.remove(s) }
        }
        return null to if (off) NETWORK_UNREACHABLE else HOST_UNREACHABLE
    }

    /** L1: each read gets what is left of one deadline for the whole handshake (a byte at a time holds nothing open longer). */
    private class Deadline(private val c: Socket, private val until: Long) : InputStream() {
        private val raw = c.getInputStream()
        private fun left() {
            val ms = (until - System.nanoTime()) / 1_000_000
            if (ms <= 0) throw SocketTimeoutException()
            c.soTimeout = ms.toInt()
        }
        override fun read(): Int { left(); return raw.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { left(); return raw.read(b, off, len) }
    }

    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        } finally { quiet(a); quiet(b) }
    }

    private fun reply(o: OutputStream, code: Int) = o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))

    /** Tests only: the sockets tracked now (each connection's near one and its far one or its dial). */
    internal fun tracked(): List<Socket> = synchronized(open) { open.toList() }

    private fun track(s: Socket): Boolean {
        val ok = synchronized(open) { !closed && open.add(s) }
        if (!ok) quiet(s)
        return ok
    }

    /** With Tor: the port and every connection it carries. */
    override fun close() {
        val all = synchronized(open) { closed = true; open.toList().also { open.clear() } }
        quiet(server)
        // fix-relay-port-test: the JDK frees a port that a thread is blocked accepting on only when that thread wakes, so
        // until then a connection could still be taken. Wait for the accept loop to end: the port is shut on return.
        // fix-relay-join-interrupt: the connections first, so nothing that happens to the wait can leave them open.
        all.forEach(::quiet)
        if (Thread.currentThread() !== loop) awaitLoop()
    }

    /**
     * fix-relay-join-interrupt: waits up to [CLOSE_MS] for the accept loop to end, through an interrupt too (so the port
     * is shut on return whatever happens), and leaves the thread marked interrupted again if it was.
     */
    private fun awaitLoop() {
        val end = System.nanoTime() + CLOSE_MS * 1_000_000L
        var interrupted = false
        while (loop.isAlive) {
            val left = (end - System.nanoTime()) / 1_000_000L
            if (left <= 0) break
            try { loop.join(left) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        private const val HANDSHAKE_MS = 10_000
        private const val CONNECT_MS = 30_000
        private const val CLOSE_MS = 5_000L
        private const val BACKLOG = 16
        private const val WAITING = 16
        private const val NOT_ALLOWED = 2
        private const val NETWORK_UNREACHABLE = 3
        private const val HOST_UNREACHABLE = 4
        private const val COMMAND = 7
        private const val ADDRESS_TYPE = 8

        /**
         * Listening at once on a loopback port (127.0.0.1 itself: Android's loopback address is ::1, which the torrc refuses).
         * [socketFor] (an unconnected socket under core HomeNets' rule) and [reach] (its connect): tests only.
         */
        fun start(
            targets: List<BridgeTarget>,
            socketFor: (ByteArray) -> Socket = ::homeSocket,
            reach: (Socket, InetSocketAddress) -> Unit = { s, to -> s.connect(to, CONNECT_MS) },
        ): BridgeRelay {
            val r = BridgeRelay(ServerSocket(0, BACKLOG, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))), targets, socketFor, reach)
            r.loop.start()
            return r
        }

        /** An unconnected socket under core HomeNets' rule (bound to the network that holds a home address, or refused). */
        private fun homeSocket(a: ByteArray): Socket = HomeNets.socket(a)

        private fun token(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } // l10n: not text (a format)

        private fun quiet(c: Closeable) = try { c.close() } catch (_: IOException) {}
    }
}
