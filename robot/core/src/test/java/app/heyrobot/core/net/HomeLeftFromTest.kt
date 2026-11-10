// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import app.heyrobot.core.helpers.LanRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * review-home-net-bind-b N2, N4: an unbound socket to a home address must have left from Wi-Fi or Ethernet that holds it
 * (the system sends what a VPN doesn't route over its default network, which may be mobile data). The test's sockets
 * leave from 127.0.0.1: as Wi-Fi's own address the check passes; as no Wi-Fi address (mobile data's), it refuses.
 */
class HomeLeftFromTest {
    private fun a(s: String) = LanRule.literal(s)!!
    private fun p(s: String) = HomeRoute.Prefix(a(s.substringBefore('/')), s.substringAfter('/').toInt())
    private val home = a("192.168.1.20")
    private fun wifi(at: String) = HomeRoute.Net(2, true, false, false, false, listOf(p("0.0.0.0/0"), p("192.168.1.0/24")), addrs = listOf(a(at)))
    private fun vpn(wifi: Boolean, cellular: Boolean) = HomeRoute.Net(3, wifi, false, true, cellular, listOf(p("10.8.0.0/24")))
    private val bound = Collections.synchronizedList(mutableListOf<Long>())
    private fun use(vararg n: HomeRoute.Net) = HomeNets.use(object : HomeNets.Source {
        override fun nets() = n.toList()
        override fun bind(s: Socket, id: Long) { bound += id }
    })

    @After fun tearDown() = HomeNets.use(null)

    private fun connect(server: ServerSocket) {
        val s = HomeNets.Factory { InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort) }.createSocket()
        try {
            s.connect(InetSocketAddress(InetAddress.getByAddress(home), 80), 2000)
        } catch (e: OffNetworkException) {
            assertTrue(!s.isConnected || s.isClosed); throw e // a connected socket is closed before anything is written
        }
        s.close()
    }

    @Test fun anUnboundSocketThatLeftOffTheHomeNetworkIsClosedBeforeAnythingIsWritten() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            use(wifi("127.0.0.1"), vpn(wifi = true, cellular = false))
            connect(server)
            use(wifi("192.168.1.7"), vpn(wifi = true, cellular = false))
            try { connect(server); fail("sent") } catch (_: OffNetworkException) {}
            assertEquals(emptyList<Long>(), bound)
        }
    }

    @Test fun aVpnOnBothOrNoTransportReachesHomeWhenTheSocketLeftOnWifi() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            for (v in listOf(vpn(wifi = true, cellular = true), vpn(wifi = false, cellular = false))) {
                use(wifi("127.0.0.1"), v)
                connect(server)
            }
            // A VPN on mobile data alone: refused before any connect.
            use(wifi("127.0.0.1"), vpn(wifi = false, cellular = true))
            try { connect(server); fail("sent") } catch (_: OffNetworkException) {}
            assertEquals(emptyList<Long>(), bound)
        }
    }

    @Test fun aFeedOrPeerSocketIsCheckedToo() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            val to: (InetSocketAddress) -> java.net.SocketAddress = { InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort) }
            use(wifi("127.0.0.1"), vpn(wifi = true, cellular = false))
            HomeNets.socket(home, toward = to).use { it.connect(InetSocketAddress(InetAddress.getByAddress(home), 80), 2000) }
            use(wifi("192.168.1.7"), vpn(wifi = true, cellular = false))
            val s = HomeNets.socket(home, toward = to)
            try { s.connect(InetSocketAddress(InetAddress.getByAddress(home), 80), 2000); fail("sent") } catch (_: OffNetworkException) {}
            assertTrue(s.isClosed)
        }
    }

    /** review-home-net-bind-c: the phone's own hotspot link holds the address; the socket must have left from that link's own address. */
    @Test fun aHotspotLinkPassesOnlyFromItsOwnAddress() {
        val guest = a("192.168.43.20")
        val own = listOf(p("192.168.43.1/24"))
        assertTrue(HomeRoute.leftFrom(guest, a("192.168.43.1"), emptyList(), own))
        assertFalse(HomeRoute.leftFrom(guest, a("10.20.30.40"), emptyList(), own)) // mobile data's address
        assertFalse(HomeRoute.leftFrom(a("192.168.1.20"), a("192.168.43.1"), emptyList(), own)) // the link doesn't hold it
        // Through a socket: the hotspot link sends it unbound; it left from 127.0.0.1, not the link's address, so it's closed.
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            HomeNets.use(object : HomeNets.Source {
                override fun nets() = emptyList<HomeRoute.Net>()
                override fun bind(s: Socket, id: Long) { bound += id }
                override fun own() = own
            })
            val s = HomeNets.Factory { InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort) }.createSocket()
            try { s.connect(InetSocketAddress(InetAddress.getByAddress(guest), 80), 2000); fail("sent") } catch (_: OffNetworkException) {}
            assertTrue(s.isClosed)
            assertEquals(emptyList<Long>(), bound)
        }
    }

    /** review-home-net-bind-c: IPv6 sources, as the socket reports them: link-local with its scope, a unique local address, and IPv4-mapped. */
    @Test fun anIpv6SourceMustBeOneOfTheWifiAddresses() {
        fun wifi6(route: String, at: String) = HomeRoute.Net(2, true, false, false, false, listOf(p("::/0"), p(route)), addrs = listOf(a(at)))
        fun scoped(s: String) = Inet6Address.getByAddress(null, a(s), 3).address
        val ll = a("fe80::20")
        val llWifi = wifi6("fe80::/64", "fe80::1234")
        assertTrue(HomeRoute.leftFrom(ll, scoped("fe80::1234"), listOf(llWifi), emptyList()))
        assertFalse(HomeRoute.leftFrom(ll, scoped("fe80::99"), listOf(llWifi), emptyList()))
        val ula = a("fd00::20")
        val ulaWifi = wifi6("fd00::/64", "fd00::7")
        assertTrue(HomeRoute.leftFrom(ula, a("fd00::7"), listOf(ulaWifi), emptyList()))
        assertFalse(HomeRoute.leftFrom(ula, a("2001:db8::5"), listOf(ulaWifi), emptyList())) // mobile data's address
        val mapped = ByteArray(10) + byteArrayOf(-1, -1, 192.toByte(), 168.toByte(), 1, 7)
        assertTrue(HomeRoute.leftFrom(home, mapped, listOf(wifi("192.168.1.7")), emptyList()))
        assertFalse(HomeRoute.leftFrom(home, mapped, listOf(wifi("192.168.1.8")), emptyList()))
    }
}
