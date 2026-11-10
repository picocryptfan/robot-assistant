// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import app.heyrobot.core.helpers.LanRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Socket
import java.net.SocketException

/**
 * review-home-net-bind H1, M3: under a VPN that applies to Robot. Binding fakes the platform: under a VPN that can't be
 * bypassed (VpnService's default), Network.bindSocket to any network but that VPN fails with EPERM, and so does a VPN that
 * doesn't apply to Robot.
 */
class HomeNetsVpnTest {
    private fun a(s: String) = LanRule.literal(s)!!
    private fun p(r: String, sends: Boolean = true) = HomeRoute.Prefix(a(r.substringBefore('/')), r.substringAfter('/').toInt(), sends)
    private fun net(id: Long, wifi: Boolean = false, cellular: Boolean = false, vpn: Boolean = false, applies: Boolean = vpn, vararg routes: HomeRoute.Prefix) =
        HomeRoute.Net(id, wifi, false, vpn, cellular, routes.toList(), applies)

    private class Phone(vararg val list: HomeRoute.Net) : HomeNets.Source {
        val bound = mutableListOf<Long>()
        override fun nets() = list.toList()
        override fun bind(s: Socket, id: Long) {
            val vpn = list.firstOrNull { it.vpn && it.applies }
            val other = list.any { it.id == id && it.vpn && !it.applies }
            if ((vpn != null && id != vpn.id) || other) throw SocketException("Binding socket to network $id failed: EPERM (Operation not permitted)") // l10n: not text (test)
            bound += id
        }
    }

    private val wifi = net(2, wifi = true, routes = arrayOf(p("0.0.0.0/0"), p("192.168.1.0/24")))
    private val cell = net(1, cellular = true, routes = arrayOf(p("0.0.0.0/0")))
    private val home = a("192.168.1.20")

    @After fun tearDown() = HomeNets.use(null)

    private fun refused(phone: Phone, x: ByteArray = home): List<Socket> {
        HomeNets.use(phone)
        val made = mutableListOf<Socket>()
        try { HomeNets.socket(x) { Socket().also { made += it } }; fail("sent") } catch (_: OffNetworkException) {}
        return made
    }

    @Test fun aVpnThatDoesntRouteTheLanSendsItUnboundOnTheWifiItRidesOn() {
        for (vpn in listOf(net(3, wifi = true, vpn = true, routes = arrayOf(p("10.8.0.0/24"))),
                net(3, wifi = true, vpn = true, routes = arrayOf(p("0.0.0.0/0"), p("192.168.0.0/16", sends = false))))) {
            val phone = Phone(wifi, vpn)
            HomeNets.use(phone)
            val s = HomeNets.socket(home)
            assertEquals(emptyList<Long>(), phone.bound)
            assertTrue(!s.isClosed)
            s.close()
        }
    }

    // review-home-net-bind-b N4: a VPN on both or no transport goes unbound and is checked when it connects (HomeLeftFromTest).
    @Test fun aVpnOnMobileDataAloneRefuses() {
        assertEquals(0, refused(Phone(cell, net(3, cellular = true, vpn = true, routes = arrayOf(p("10.8.0.0/24"))))).size)
        assertEquals(0, refused(Phone(wifi, net(3, cellular = true, vpn = true, routes = arrayOf(p("10.8.0.0/24"))))).size)
    }

    @Test fun anEpermFromBindIsRefusedNeverSentUnbound() {
        // A blocked always-on VPN: no VPN network up, Robot has no default network, and every bind fails with EPERM.
        val blocked = object : HomeNets.Source {
            override fun nets() = listOf(wifi)
            override fun bind(s: Socket, id: Long) = throw SocketException("EPERM (Operation not permitted)") // l10n: not text (test)
        }
        HomeNets.use(blocked)
        val made = mutableListOf<Socket>()
        try { HomeNets.socket(home) { Socket().also { made += it } }; fail("sent") } catch (_: OffNetworkException) {}
        assertEquals(1, made.size)
        assertTrue(made[0].isClosed)
    }

    @Test fun aVpnThatDoesntApplyToRobotNeverWins() {
        val phone = Phone(wifi, net(8, vpn = true, applies = false, routes = arrayOf(p("192.168.1.0/24"))))
        HomeNets.use(phone)
        HomeNets.socket(home).close()
        assertEquals(listOf(2L), phone.bound)
    }

    @Test fun aFullTunnelVpnCarries100_64OnlyWithItsOwnRoute() {
        for (under in listOf(cell, wifi)) {
            val full = net(3, cellular = under === cell, wifi = under === wifi, vpn = true, routes = arrayOf(p("0.0.0.0/0")))
            assertEquals(0, refused(Phone(under, full), a("100.100.1.1")).size)
            // A full tunnel still carries private ranges (a VPN to home).
            val phone = Phone(under, full)
            HomeNets.use(phone)
            HomeNets.socket(home).close()
            assertEquals(listOf(3L), phone.bound)
        }
        val mesh = Phone(cell, net(3, cellular = true, vpn = true, routes = arrayOf(p("0.0.0.0/0"), p("100.64.0.0/10"))))
        HomeNets.use(mesh)
        HomeNets.socket(a("100.100.1.1")).close()
        assertEquals(listOf(3L), mesh.bound)
    }
}
