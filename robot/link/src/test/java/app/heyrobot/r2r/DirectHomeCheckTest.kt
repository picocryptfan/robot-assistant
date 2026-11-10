// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.link.LocalAddress
import app.heyrobot.core.net.HomeNets
import app.heyrobot.core.net.HomeRoute
import app.heyrobot.core.online.NetEntry
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * review-home-net-bind-b N2 for Robot to Robot's direct carrier (home-net-bind-f): a dial to a peer's home address on an
 * unbound socket (Wi-Fi holds it, a VPN is up) goes ahead only when its local address is that Wi-Fi's; otherwise it is
 * closed before anything is written and no failed dial is recorded. The peer listens on this machine's own private
 * address, so the socket's local address is it.
 */
class DirectHomeCheckTest {
    private val rows = Collections.synchronizedList(mutableListOf<NetEntry>())
    private val at: ByteArray? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.address }
        .firstOrNull { HomeRoute.home(it) && LanRule.classify(it) != LanRule.Range.CGNAT }
    private val other = LanRule.literal(if (at?.get(0) == 10.toByte()) "192.168.255.254" else "10.255.255.254")!!

    /** Wi-Fi holds [at] and left from [from]; a VPN that doesn't route it, on Wi-Fi: the socket goes unbound. */
    private fun leftFrom(from: ByteArray) = HomeNets.use(object : HomeNets.Source {
        override fun nets() = listOf(
            HomeRoute.Net(2, true, false, false, false, listOf(HomeRoute.Prefix(ByteArray(4), 0), HomeRoute.Prefix(at!!, 32)), addrs = listOf(from)),
            HomeRoute.Net(3, true, false, true, false, listOf(HomeRoute.Prefix(LanRule.literal("198.18.0.0")!!, 15))))
        override fun bind(s: Socket, id: Long) = fail("bound")
    })

    @Before fun capture() { NetRecord.sink = NetSink { rows += it } }

    @After fun reset() {
        NetRecord.sink = null
        HomeNets.use(null)
    }

    /** Accepts one connection on [server] and gives the bytes it got before EOF. */
    private fun serve(server: ServerSocket) = Executors.newSingleThreadExecutor().let { ex ->
        ex.submit(Callable {
            server.accept().use { c ->
                c.soTimeout = 3_000
                var got = 0
                val buf = ByteArray(256)
                while (true) {
                    val n = try { c.getInputStream().read(buf) } catch (_: SocketTimeoutException) { -1 }
                    if (n < 0) break
                    got += n
                }
                got
            }
        }).also { ex.shutdown() }
    }

    @Test fun aDialThatLeftOffTheNetworkHoldingThePeerIsClosedBeforeAnythingIsWritten() {
        assumeTrue("no private IPv4 address on this machine", at != null)
        ServerSocket(0, 5, InetAddress.getByAddress(at)).use { server ->
            server.soTimeout = 3_000
            leftFrom(other)
            val got = serve(server)
            val d = runCatching { SocketDirectNet({ emptyList() }, 2_000).dial(LocalAddress(at!!, server.localPort)) }
            d.getOrNull()?.let { it.output.write(byteArrayOf(1, 2)); it.output.flush(); it.close() }
            assertTrue("dialled", d.isFailure)
            assertEquals(0, got.get(5, TimeUnit.SECONDS)) // it connected, and nothing was written
        }
        assertEquals(emptyList<NetEntry>(), rows.toList()) // no failed dial
    }

    @Test fun aDialThatLeftOnTheNetworkHoldingThePeerGoesAhead() {
        assumeTrue("no private IPv4 address on this machine", at != null)
        ServerSocket(0, 5, InetAddress.getByAddress(at)).use { server ->
            server.soTimeout = 3_000
            leftFrom(at!!)
            val got = serve(server)
            SocketDirectNet({ emptyList() }, 2_000).dial(LocalAddress(at!!, server.localPort)).use { it.output.write(byteArrayOf(1, 2)); it.output.flush() }
            assertEquals(2, got.get(5, TimeUnit.SECONDS))
        }
    }
}
