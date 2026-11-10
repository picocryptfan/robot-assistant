// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeRoute.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** review-home-net-bind L5: a laptop joined to the phone's own hotspot (a link the phone runs, not a Network). */
class HomeOwnLinkTest {
    private fun a(s: String) = LanRule.literal(s)!!
    private fun p(s: String) = HomeRoute.Prefix(a(s.substringBefore('/')), s.substringAfter('/').toInt())
    private fun net(id: Long, wifi: Boolean = false, vpn: Boolean = false, cellular: Boolean = false, vararg routes: String) =
        HomeRoute.Net(id, wifi, false, vpn, cellular, routes.map { p(it) })

    private val cell = net(1, cellular = true, routes = arrayOf("0.0.0.0/0", "10.0.0.0/8"))
    private val wifi = net(2, wifi = true, routes = arrayOf("0.0.0.0/0", "192.168.1.0/24"))
    private val hotspot = listOf(p("192.168.43.0/24"))

    @Test fun aClientOnThePhonesOwnHotspotIsReachedUnbound() {
        assertEquals(Pick.Unbound, HomeRoute.pick(a("192.168.43.20"), listOf(cell), hotspot))
        assertEquals(Pick.Unbound, HomeRoute.pick(a("192.168.43.20"), listOf(cell, wifi), hotspot))
        // A mesh VPN that routes only its own range: the system sends the rest past it, to the hotspot.
        assertEquals(Pick.Unbound, HomeRoute.pick(a("192.168.43.20"), listOf(cell, net(3, vpn = true, cellular = true, routes = arrayOf("100.64.0.0/10"))), hotspot))
        // A full tunnel routes it into the VPN, as the system does (its table comes first).
        assertEquals(Pick.Bind(4), HomeRoute.pick(a("192.168.43.20"), listOf(cell, net(4, vpn = true, cellular = true, routes = arrayOf("0.0.0.0/0"))), hotspot))
        // Outside the hotspot's range, mobile data stays refused, and Wi-Fi's own range stays bound.
        assertEquals(Pick.Refused, HomeRoute.pick(a("192.168.44.20"), listOf(cell), hotspot))
        assertEquals(Pick.Refused, HomeRoute.pick(a("10.0.0.5"), listOf(cell), hotspot))
        assertEquals(Pick.Bind(2), HomeRoute.pick(a("192.168.1.5"), listOf(cell, wifi), hotspot))
    }

    @Test fun onlyABroadcastLinkThePhoneRunsWithAPrivateRangeCounts() {
        fun link(name: String, addr: String, len: Int, broadcast: Boolean = true, up: Boolean = true, loopback: Boolean = false, p2p: Boolean = false, virtual: Boolean = false) =
            HomeRoute.Link(name, up, loopback, p2p, virtual, listOf(HomeRoute.Addr(a(addr), len, broadcast)))
        val own = HomeRoute.ownLinks(listOf(
            link("ap0", "192.168.43.1", 24),
            link("wlan0", "192.168.1.7", 24), // Wi-Fi: a Network, decided by its own rules
            link("rmnet_data0", "10.12.0.5", 30, broadcast = false), // mobile data
            link("tun0", "10.8.0.2", 24, p2p = true),
            link("lo", "127.0.0.1", 8, broadcast = false, loopback = true),
            link("rndis0", "192.168.42.1", 24, up = false),
            link("bt-pan", "100.64.1.1", 24), // not a private range
            link("eth9", "10.0.0.1", 4), // wider than any home link
            link("ap1", "fe80::1", 64, broadcast = false),
        ), setOf("wlan0", "rmnet_data0"))
        assertEquals(1, own.size)
        assertTrue(own[0].covers(a("192.168.43.9")))
        assertFalse(own[0].covers(a("192.168.42.9")))
    }
}
