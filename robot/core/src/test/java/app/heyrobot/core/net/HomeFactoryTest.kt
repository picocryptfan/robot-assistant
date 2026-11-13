// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import app.heyrobot.core.helpers.LanRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.Socket
import java.util.Collections

/** review-home-net-bind L1: a socket is bound or refused for where it connects, not for the client's last lookup. */
class HomeFactoryTest {
    private fun a(s: String) = LanRule.literal(s)!!
    private fun p(s: String) = HomeRoute.Prefix(a(s.substringBefore('/')), s.substringAfter('/').toInt())
    private val bound = Collections.synchronizedList(mutableListOf<Long>())
    private val phone = object : HomeNets.Source {
        override fun nets() = listOf(
            HomeRoute.Net(1, false, false, false, true, listOf(p("0.0.0.0/0"))),
            HomeRoute.Net(2, true, false, false, false, listOf(p("192.168.1.0/24"))),
        )
        override fun bind(s: Socket, id: Long) { bound += id }
    }

    @After fun tearDown() = HomeNets.use(null)

    @Test fun eachSocketIsBoundOrRefusedForTheAddressItConnectsTo() {
        HomeNets.use(phone)
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            val asked = Collections.synchronizedList(mutableListOf<String>())
            val f = HomeNets.Factory { e -> asked += e.address.hostAddress; InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort) }
            f.createSocket().use { it.connect(InetSocketAddress(InetAddress.getByAddress(a("192.168.1.5")), 80), 2000) }
            assertEquals(listOf(2L), bound)
            // The same factory, another address (another lookup, or a call at the same time): its own decision, nothing sent.
            val off = f.createSocket()
            try { off.connect(InetSocketAddress(InetAddress.getByAddress(a("10.9.9.9")), 80), 2000); fail("sent") } catch (_: OffNetworkException) {}
            off.close()
            f.createSocket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), 80), 2000) }
            assertEquals(listOf(2L), bound)
            assertEquals(listOf("192.168.1.5", "127.0.0.1"), asked)
        }
    }

    /** review-home-net-bind-c L5: an unbound socket's way out is checked before its SYN (a datagram socket, connected, sends nothing). */
    @Test fun anUnboundSocketIsCheckedBeforeItConnects() {
        // The phone's own hotspot holds 192.168.43.0/24 (so unbound), but the system would send from loopback: not that link.
        HomeNets.use(object : HomeNets.Source {
            override fun nets() = emptyList<HomeRoute.Net>()
            override fun bind(s: Socket, id: Long) = fail("bound")
            override fun own() = listOf(p("192.168.43.1/24"))
        })
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 500
            val s = HomeNets.Factory { InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort) }.createSocket()
            try { s.connect(InetSocketAddress(InetAddress.getByAddress(a("192.168.43.5")), 80), 2000); fail("sent") } catch (_: OffNetworkException) {}
            s.close()
            try { server.accept().close(); fail("its SYN went out") } catch (_: SocketTimeoutException) {}
        }
    }

    /**
     * N2's check after the connect stays (tor-bridge-relay-b): a socket the check before it let through (the system said
     * it would leave from the hotspot) that left from elsewhere (loopback) is closed before anything is written.
     */
    @Test fun anUnboundSocketThatLeftElsewhereIsClosedAfterItsConnect() {
        HomeNets.use(object : HomeNets.Source {
            override fun nets() = emptyList<HomeRoute.Net>()
            override fun bind(s: Socket, id: Long) = fail("bound")
            override fun own() = listOf(p("192.168.43.1/24"))
        })
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2_000
            val s = HomeNets.Factory({ InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort) }, { a("192.168.43.1") }).createSocket()
            try { s.connect(InetSocketAddress(InetAddress.getByAddress(a("192.168.43.5")), 80), 2000); fail("sent") } catch (_: OffNetworkException) {}
            assertTrue("left open", s.isClosed)
            server.accept().use { c -> c.soTimeout = 2_000; assertEquals("written", -1, c.getInputStream().read()) } // it connected, then EOF
        }
    }
}
