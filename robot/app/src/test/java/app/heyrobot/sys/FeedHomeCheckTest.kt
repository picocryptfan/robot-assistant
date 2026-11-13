// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeNets
import app.heyrobot.core.net.HomeRoute
import app.heyrobot.core.news.NewsFail
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetEntry
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
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
 * review-home-net-bind-b N2 for the feed (home-net-bind-f): a home feed's unbound socket (Wi-Fi holds the address, a VPN is
 * up) goes ahead only when its local address is that Wi-Fi's; otherwise it is refused before its connect
 * (review-home-net-bind-c L5), so nothing reaches the server, and What went online has no row. The server listens on this machine's own private address, so the socket's local address is it.
 */
class FeedHomeCheckTest {
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

    @After fun tearDown() {
        HomeNets.use(null)
        NetRecord.sink = null
    }

    /** Accepts one connection on [server] and gives the bytes it got before the request's end or EOF; [answer]: a feed. */
    private fun serve(server: ServerSocket, answer: Boolean) = Executors.newSingleThreadExecutor().let { ex ->
        ex.submit(Callable {
            server.accept().use { c ->
                c.soTimeout = 3_000
                val got = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (!got.toString(Charsets.ISO_8859_1.name()).contains("\r\n\r\n")) {
                    val n = try { c.getInputStream().read(buf) } catch (_: SocketTimeoutException) { -1 }
                    if (n < 0) break
                    got.write(buf, 0, n)
                }
                if (answer && got.size() > 0) {
                    c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/rss+xml\r\nContent-Length: ${FEED.length}\r\n" +
                        "Connection: close\r\n\r\n$FEED").toByteArray(Charsets.ISO_8859_1))
                    c.getOutputStream().flush()
                }
                got.size()
            }
        }).also { ex.shutdown() }
    }

    private fun get(port: Int) = FeedClient.homeGet("http://${InetAddress.getByAddress(at).hostAddress}:$port/feed.xml", listOf(at!!),
        NetCause.ASKED, null, null, System.currentTimeMillis() + 5_000, false)

    @Test fun aHomeFeedThatLeftOffTheNetworkHoldingItIsClosedBeforeTheRequest() {
        assumeTrue("no private IPv4 address on this machine", at != null)
        NetRecord.sink = NetSink { rows += it }
        ServerSocket(0, 5, InetAddress.getByAddress(at)).use { server ->
            server.soTimeout = 500
            leftFrom(other)
            try { get(server.localPort); fail("sent") } catch (f: FeedClient.Failure) { assertEquals(NewsFail.OFF_NETWORK, f.why) }
            try { server.accept().close(); fail("it connected") } catch (_: SocketTimeoutException) {} // not even a SYN (L5)
        }
        assertEquals(emptyList<NetEntry>(), rows.toList())
    }

    @Test fun aHomeFeedThatLeftOnTheNetworkHoldingItGoesAhead() {
        assumeTrue("no private IPv4 address on this machine", at != null)
        ServerSocket(0, 5, InetAddress.getByAddress(at)).use { server ->
            server.soTimeout = 3_000
            leftFrom(at!!)
            val got = serve(server, answer = true)
            val reply = get(server.localPort)
            assertEquals(200, reply.code)
            assertEquals(FEED, String(reply.body!!, Charsets.ISO_8859_1))
            assertTrue(got.get(5, TimeUnit.SECONDS) > 0)
        }
    }

    private companion object {
        const val FEED = "<rss version=\"2.0\"><channel><title>t</title></channel></rss>"
    }
}
