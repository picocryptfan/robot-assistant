// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LocalAddress
import app.heyrobot.core.link.PushRoute
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetEntry
import app.heyrobot.core.online.NetHost
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import app.heyrobot.core.online.NetSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections

/** E4: Robot to Robot's direct and push carriers in What went online: hosts and bytes, never an address or a path. */
class CarrierRecordTest {
    private val rows: MutableList<NetEntry> = Collections.synchronizedList(ArrayList())
    private val phone = LocalAddress(byteArrayOf(10, 0, 0, 2), 4_000)
    private val loopback = InetAddress.getLoopbackAddress()

    @Before fun capture() {
        NetRecord.sink = NetSink { rows += it }
    }

    @After fun reset() {
        NetRecord.sink = null
    }

    @Test fun aDialIsOneRowWhenItClosesWithItsBytesAndNoAddress() {
        ServerSocket(0, 1, loopback).use { server ->
            Thread { server.accept().use { s -> s.getInputStream().readNBytes(5); s.getOutputStream().write(ByteArray(3)); s.getOutputStream().flush() } }.apply { isDaemon = true }.start()
            val d = SocketDirectNet({ emptyList() }, 2_000, { InetSocketAddress(loopback, server.localPort) }).dial(phone)
            d.output.write(ByteArray(4))
            d.output.write(1)
            d.output.flush()
            assertEquals(3, d.input.readNBytes(3).size)
            assertEquals(0, rows.size)
            d.close()
            d.close()
        }
        val r = rows.single()
        assertEquals(NetPurpose.LINK_DIRECT, r.purpose)
        assertEquals(NetHost.YOUR_NETWORK, r.host)
        assertEquals(NetCause.SWITCH, r.cause)
        assertEquals(NetResult.DONE, r.result)
        assertEquals(5L to 3L, r.out to r.inBytes)
        assertFalse(r.toString().contains("10.0.0.2"))
    }

    @Test fun aDialThatFailsIsARow() {
        val closed = ServerSocket(0, 1, loopback).use { it.localPort }
        val x = runCatching { SocketDirectNet({ emptyList() }, 2_000, { InetSocketAddress(loopback, closed) }).dial(phone) }
        assertTrue(x.isFailure)
        val r = rows.single()
        assertEquals(NetResult.FAILED to NetHost.YOUR_NETWORK, r.result to r.host)
    }

    @Test fun aConnectionThatCameInIsARowOnlyOnceRobotSentSomething() {
        val (a, _) = PipeDuplex.pair()
        DirectRecord.accepted(a).close()
        assertEquals(0, rows.size)
        val (b, c) = PipeDuplex.pair()
        val d = DirectRecord.accepted(b)
        d.output.write(ByteArray(2))
        d.output.flush()
        c.output.write(1)
        c.output.flush()
        d.input.read()
        d.close()
        assertEquals(2L to 1L, rows.single().out to rows.single().inBytes)
    }

    private fun route(url: String) = PushRoute(url, ByteArray(65).also { it[0] = 4 }, ByteArray(16) { 1 })

    private fun poster(tor: () -> TorNet? = { null }) = HttpPushPoster(PushHttp(PushHttp.platformTrust()) { _, _ -> true }, tor, 500, { InetAddress.getAllByName(it).toList() })

    /** fix-push-tls: a push host that resolves to your network or the phone itself is refused before any connection. */
    @Test fun aPushHostOnYourNetworkIsRefusedBeforeAnyConnection() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            val p = HttpPushPoster(PushHttp(PushHttp.platformTrust()) { _, _ -> true }, { null }, 500)
            val x = runCatching { p.post(route("https://localhost:${ss.localPort}/up/x"), ByteArray(50), 60, false, "p") }
            assertEquals(PushProblem.BAD_ADDRESS, (x.exceptionOrNull() as PushException).problem)
            ss.soTimeout = 300
            assertTrue(runCatching { ss.accept() }.exceptionOrNull() is java.net.SocketTimeoutException)
        }
        assertEquals(NetResult.FAILED, rows.single().result)
    }

    /** fix-push-tls: a server that trickles its answer a byte at a time can't hold a post past its deadline. */
    @Test fun aPostEndsAtItsDeadline() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            Thread {
                try {
                    ss.accept().use { c ->
                        val o = c.getOutputStream()
                        // A TLS record header for 16 KiB of handshake, then a byte every 50 ms: never a whole record.
                        o.write(byteArrayOf(0x16, 3, 3, 0x40, 0)); o.flush()
                        while (true) { Thread.sleep(50); o.write(0); o.flush() }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            val started = System.nanoTime()
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
            val f = pool.submit<Throwable?> {
                java.net.Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { s ->
                    runCatching { PushHttp(PushHttp.platformTrust(), 800) { _, _ -> true }.post(SocketDuplex(s), PushTarget.of("https://localhost:${ss.localPort}/up")!!, ByteArray(10), 60) }.exceptionOrNull()
                }
            }
            val x = f.get(20, java.util.concurrent.TimeUnit.SECONDS)
            pool.shutdown()
            assertEquals(PushProblem.FAILED, (x as PushException).problem)
            assertTrue(System.nanoTime() - started < 10_000_000_000L)
        }
    }

    @Test fun aPostThatFailsIsARowWithTheHostAlone() {
        val x = runCatching { poster().post(route("https://push.example.org:1/up/secret?x=1"), ByteArray(50), 60, false, "p") }
        assertEquals(PushProblem.UNREACHABLE, (x.exceptionOrNull() as PushException).problem)
        val r = rows.single()
        assertEquals(NetPurpose.LINK_PUSH, r.purpose)
        assertEquals("push.example.org", r.host)
        assertEquals(NetResult.FAILED, r.result)
        assertEquals(0L to 0L, r.out to r.inBytes)
        assertFalse(r.toString().contains("secret"))
    }

    /** A post cut off part way still shows what went and what came back. */
    @Test fun aPostCutOffPartWayKeepsItsBytes() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            val server = Thread {
                ss.accept().use { c ->
                    c.getInputStream().read(ByteArray(64))
                    c.getOutputStream().apply { write(ByteArray(7) { 21 }); flush() }
                }
            }.apply { isDaemon = true; start() }
            assertTrue(runCatching { poster().post(route("https://localhost:${ss.localPort}/up/x"), ByteArray(50), 60, false, "p") }.isFailure)
            server.join(5_000)
        }
        val r = rows.single()
        assertEquals(NetResult.FAILED, r.result)
        assertTrue("" + r.out, r.out > 0)
        assertTrue("" + r.inBytes, r.inBytes > 0)
    }

    /** Its bytes are Tor's, in Tor's hourly row; an Anonymous pairing's push server stays out of the record. */
    @Test fun aPostThroughTorIsNoRowOfItsOwn() {
        val x = runCatching { poster().post(route("https://push.invalid/up/x"), ByteArray(50), 60, true, "p") }
        assertEquals(PushProblem.UNREACHABLE, (x.exceptionOrNull() as PushException).problem)
        assertEquals(0, rows.size)
    }

    @Test fun anAddressRobotWontPostToIsNoRow() {
        val x = runCatching { poster().post(route("https://push.example.org/up/x#frag"), ByteArray(50), 60, false, "p") }
        assertEquals(PushProblem.BAD_ADDRESS, (x.exceptionOrNull() as PushException).problem)
        assertEquals(0, rows.size)
    }
}
