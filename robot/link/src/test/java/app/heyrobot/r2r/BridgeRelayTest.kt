// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.BridgeLine
import app.heyrobot.core.link.BridgeRelayPlan
import app.heyrobot.core.net.HomeNets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * fix-relay-port-test: nothing answers on [port] at 127.0.0.1. A connection Linux joins to itself (its own port picked
 * as the source, nothing listening) counts as shut: it's no listener.
 */
internal fun portShut(port: Int): Boolean =
    runCatching { Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { it.localPort == port } }.getOrDefault(true)

/** tor-bridge-relay: the relay takes only its own password, connects only where the saved lines lead, under HomeNets' rule. */
class BridgeRelayTest {
    private val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
    private val lo = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @After fun tearDown() = HomeNets.use(null)

    /** Greets [relay] offering [methods]; the method it picks, then (for 2) the password's status, or -1 when it closes. */
    private fun hello(relay: BridgeRelay, user: String, pass: String, methods: ByteArray = byteArrayOf(2)): Pair<Socket, Int> {
        val s = Socket(lo, relay.auth.port).apply { soTimeout = 3_000 }
        val i = DataInputStream(s.getInputStream())
        s.getOutputStream().write(byteArrayOf(5, methods.size.toByte()) + methods)
        i.readUnsignedByte()
        val m = i.readUnsignedByte()
        if (m != 2) return s to -m
        s.getOutputStream().write(byteArrayOf(1, user.length.toByte()) + user.toByteArray() + byteArrayOf(pass.length.toByte()) + pass.toByteArray())
        i.readUnsignedByte()
        return s to i.readUnsignedByte()
    }

    /** CONNECT [host] [port] on [s]; the relay's reply code. */
    private fun ask(s: Socket, host: String, port: Int): Int {
        val a = runCatching { InetAddress.getByName(host).address }.getOrNull()?.takeIf { host.first().isDigit() || host.contains(':') }
        val target = a?.let { byteArrayOf(5, 1, 0, if (it.size == 4) 1 else 4) + it } ?: (byteArrayOf(5, 1, 0, 3, host.length.toByte()) + host.toByteArray())
        s.getOutputStream().write(target + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val i = DataInputStream(s.getInputStream())
        i.readUnsignedByte()
        val code = i.readUnsignedByte()
        i.readFully(ByteArray(8))
        return code
    }

    @Test fun onlyItsOwnPasswordAndOnlyWhereTheSavedLinesLead() {
        ServerSocket(0, 5, lo).use { bridge ->
            bridge.soTimeout = 3_000
            val lines = listOf(BridgeLine.parse("${lo.hostAddress}:${bridge.localPort} $fp")!!, BridgeLine.parse("10.9.9.9:443 $fp")!!)
            val relay = BridgeRelay.start(BridgeRelayPlan.targets(lines))
            val again = BridgeRelay.start(BridgeRelayPlan.targets(lines))
            try {
                assertTrue(Regex("[0-9a-f]{32}").matches(relay.auth.user) && Regex("[0-9a-f]{32}").matches(relay.auth.pass))
                assertNotEquals(relay.auth.pass, again.auth.pass)
                assertEquals("127.0.0.1", relay.auth.host)
                // No password offered, or a wrong one: refused, then closed.
                hello(relay, "", "", byteArrayOf(0)).let { (s, m) -> assertEquals(-0xFF, m); assertEquals(-1, s.getInputStream().read()); s.close() }
                hello(relay, relay.auth.user, again.auth.pass).let { (s, st) -> assertEquals(1, st); assertEquals(-1, s.getInputStream().read()); s.close() }
                // The right one, somewhere no saved line leads: refused before any connection.
                hello(relay, relay.auth.user, relay.auth.pass).let { (s, st) -> assertEquals(0, st); assertEquals(2, ask(s, lo.hostAddress!!, bridge.localPort + 1)); s.close() }
                hello(relay, relay.auth.user, relay.auth.pass).let { (s, _) -> assertEquals(2, ask(s, "example.org", 443)); s.close() }
                // A home address with no network that holds it (here: none at all): refused, nothing sent.
                hello(relay, relay.auth.user, relay.auth.pass).let { (s, _) -> assertEquals(3, ask(s, "10.9.9.9", 443)); s.close() }
                // The saved bridge: connected, bytes both ways.
                hello(relay, relay.auth.user, relay.auth.pass).let { (s, _) ->
                    assertEquals(0, ask(s, lo.hostAddress!!, bridge.localPort))
                    bridge.accept().use { b ->
                        s.getOutputStream().write("ping".toByteArray())
                        assertEquals("ping", String(DataInputStream(b.getInputStream()).let { d -> ByteArray(4).also(d::readFully) }))
                        b.getOutputStream().write("pong".toByteArray())
                        assertEquals("pong", String(DataInputStream(s.getInputStream()).let { d -> ByteArray(4).also(d::readFully) }))
                        // Closed with Tor: the connection it carried and its port.
                        relay.close()
                        assertEquals(-1, s.getInputStream().read())
                    }
                    s.close()
                }
                assertTrue(portShut(relay.auth.port))
                try { bridge.accept().close(); throw AssertionError("connected") } catch (_: SocketTimeoutException) {}
            } finally { relay.close(); again.close() }
        }
    }

    /**
     * fix-relay-port-test: the port is shut when close() returns. The JDK closes a listening socket that a thread is
     * blocked accepting on only once that thread wakes, so under load a connection could still be taken after close().
     * Busy threads keep the CPUs taken, so the accept thread waits for one; one relay at a time, so no other relay
     * here can take a port just freed.
     */
    @Test fun closedMeansThePortIsShutWhenCloseReturns() {
        val done = java.util.concurrent.atomic.AtomicBoolean()
        val busy = (0 until 2 * Runtime.getRuntime().availableProcessors()).map { Thread { while (!done.get()) Thread.onSpinWait() }.apply { isDaemon = true; start() } }
        try {
            val open = (0 until 1_000).count { val r = BridgeRelay.start(emptyList()); r.close(); !portShut(r.auth.port) }
            assertEquals(0, open)
        } finally { done.set(true); busy.forEach { it.join(5_000) } }
    }

    /** tor-bridge-relay-c L2: connections failing at once each get their own code (off the home network 3, unreachable 4). */
    @Test fun eachConnectionKeepsItsOwnFailure() {
        val lines = listOf(BridgeLine.parse("10.0.0.5:443 $fp")!!, BridgeLine.parse("10.0.0.6:443 $fp")!!)
        val relay = BridgeRelay.start(BridgeRelayPlan.targets(lines)) { a, _ -> Thread.sleep(5); if (a[3].toInt() == 5) throw app.heyrobot.core.net.OffNetworkException() else throw java.io.IOException() }
        try {
            val wrong = java.util.concurrent.atomic.AtomicInteger()
            (0 until 12).map { n ->
                Thread {
                    repeat(10) {
                        val (s, _) = hello(relay, relay.auth.user, relay.auth.pass)
                        s.use { if (ask(it, if (n % 2 == 0) "10.0.0.5" else "10.0.0.6", 443) != if (n % 2 == 0) 3 else 4) wrong.incrementAndGet() }
                    }
                }.apply { start() }
            }.forEach { it.join(30_000) }
            assertEquals(0, wrong.get())
        } finally { relay.close() }
    }

    /** tor-bridge-relay-c L1: the whole handshake within one deadline; a byte at a time inside each read's limit holds nothing longer. */
    @Test fun theWholeHandshakeHasOneDeadline() {
        val relay = BridgeRelay.start(emptyList())
        try {
            Socket(lo, relay.auth.port).use { s ->
                s.soTimeout = 3_000
                val o = s.getOutputStream()
                o.write(byteArrayOf(5, 100))
                repeat(5) { Thread.sleep(1_800); o.write(2); o.flush() }
                Thread.sleep(2_000)
                assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-2))
            }
        } finally { relay.close() }
    }

    /** tor-bridge-relay-c L1: at most 16 connections wait in the handshake at once; the next is closed unread, served once one leaves. */
    @Test fun atMostSixteenWaitAtOnce() {
        val relay = BridgeRelay.start(emptyList())
        val idle = ArrayList<Socket>()
        try {
            repeat(16) { idle += Socket(lo, relay.auth.port) }
            Thread.sleep(300)
            Socket(lo, relay.auth.port).use { s -> s.soTimeout = 3_000; assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-2)) }
            idle.removeAt(0).close()
            Thread.sleep(300)
            val (s, status) = hello(relay, relay.auth.user, relay.auth.pass)
            s.use { assertEquals(0, status) }
        } finally { idle.forEach { it.close() }; relay.close() }
    }

    /** review-tor-bridge-relay-b L2: a connection with the password no longer holds a handshake slot while it connects. */
    @Test fun torsOwnSlowConnectionsHoldNoSlot() {
        val dialling = java.util.concurrent.CountDownLatch(16)
        val release = java.util.concurrent.CountDownLatch(1)
        val relay = BridgeRelay.start(BridgeRelayPlan.targets(listOf(BridgeLine.parse("10.0.0.5:443 $fp")!!))) { _, _ ->
            dialling.countDown(); release.await(20, java.util.concurrent.TimeUnit.SECONDS); throw java.io.IOException()
        }
        val slow = ArrayList<Socket>()
        try {
            repeat(16) {
                val (s, st) = hello(relay, relay.auth.user, relay.auth.pass)
                assertEquals(0, st)
                s.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 10, 0, 0, 5, 1, 0xBB.toByte()))
                slow += s
            }
            assertTrue(dialling.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, runCatching { hello(relay, relay.auth.user, relay.auth.pass).let { (s, st) -> s.close(); st } }.getOrDefault(-9))
        } finally { release.countDown(); slow.forEach { it.close() }; relay.close() }
    }

    /** review-tor-bridge-relay-b L3: a stream that throws once connected (Tor stopping) is caught, never a crash of :link. */
    @Test fun aStreamThatThrowsOnceConnectedIsNoCrash() {
        val crashed = java.util.concurrent.CountDownLatch(1)
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> crashed.countDown() }
        // An unconnected socket: its streams throw SocketException, as a closed one's do.
        val relay = BridgeRelay.start(BridgeRelayPlan.targets(listOf(BridgeLine.parse("10.0.0.5:443 $fp")!!))) { _, _ -> Socket() }
        try {
            val (s, _) = hello(relay, relay.auth.user, relay.auth.pass)
            s.use {
                assertEquals(0, ask(it, "10.0.0.5", 443))
                assertEquals(-1, runCatching { it.getInputStream().read() }.getOrDefault(-1))
            }
            assertTrue("an uncaught exception", !crashed.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally { relay.close(); Thread.setDefaultUncaughtExceptionHandler(before) }
    }

    /**
     * tor-bridge-relay-c H1: on Android the loopback address is ::1, which Tor's torrc check refuses; the relay binds 127.0.0.1
     * itself. A JVM that prefers IPv6 has ::1 as its loopback too: the relay started there still listens on 127.0.0.1.
     */
    @Test fun itListensOn127001WhereLoopbackIsIpv6() {
        val jvm = java.io.File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(jvm, "-Djava.net.preferIPv6Addresses=true", "-cp", System.getProperty("java.class.path"), LoopbackProbe::class.java.name)
            .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).start()
        assertTrue("probe took over 60 s", p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || run { p.destroyForcibly(); false })
        val out = p.inputStream.bufferedReader().readText().trim().lines()
        org.junit.Assume.assumeTrue("no IPv6 loopback here: ${out}", out.firstOrNull() == "::1" || out.firstOrNull() == "0:0:0:0:0:0:0:1")
        assertEquals(out.toString(), listOf("127.0.0.1", "127.0.0.1"), out.drop(1))
    }
}

/** Run by [BridgeRelayTest.itListensOn127001WhereLoopbackIsIpv6] in a JVM that prefers IPv6: the loopback, then where the relay listens. */
object LoopbackProbe {
    @JvmStatic fun main(args: Array<String>) {
        println(InetAddress.getLoopbackAddress().hostAddress)
        val r = BridgeRelay.start(emptyList())
        println(r.auth.host)
        println(Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), r.auth.port).use { it.inetAddress.hostAddress })
        r.close()
    }
}
