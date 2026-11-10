// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeRoute.Pick
import org.junit.Assert.assertEquals
import org.junit.Test

/** review-home-net-bind-b N3: an unreachable route sends nothing anywhere, so it refuses (the off-network line, no row). */
class HomeUnreachableTest {
    private fun a(s: String) = LanRule.literal(s)!!
    private fun p(s: String) = HomeRoute.Prefix(a(s.substringBefore('/')), s.substringAfter('/').toInt())

    @Test fun anUnreachableRouteRefusesWhereAThrowRouteFallsThrough() {
        val ula = a("fd00::20")
        val wifi = HomeRoute.Net(2, true, false, false, false, listOf(p("::/0"), p("fd00::/64")))
        fun vpn(blocks: Boolean) = HomeRoute.Net(3, true, false, true, false, listOf(p("0.0.0.0/0"), HomeRoute.Prefix(a("::"), 0, sends = false, blocks = blocks)))
        assertEquals(Pick.Unbound, HomeRoute.pick(ula, listOf(wifi, vpn(blocks = false))))
        assertEquals(Pick.Refused, HomeRoute.pick(ula, listOf(wifi, vpn(blocks = true))))
    }

    /** review-home-net-bind-c L3: the phone's own hotspot link doesn't go round a VPN's unreachable route (the connect would fail with ENETUNREACH). */
    @Test fun anUnreachableRouteRefusesTheHotspotLinkToo() {
        val hotspot = a("192.168.43.20")
        val own = listOf(HomeRoute.Prefix(a("192.168.43.1"), 24))
        fun vpn(blocks: Boolean) = HomeRoute.Net(3, true, false, true, false, listOf(p("10.8.0.0/24"), HomeRoute.Prefix(a("0.0.0.0"), 0, sends = false, blocks = blocks)))
        assertEquals(Pick.Unbound, HomeRoute.pick(hotspot, listOf(vpn(blocks = false)), own))
        assertEquals(Pick.Refused, HomeRoute.pick(hotspot, listOf(vpn(blocks = true)), own))
    }
}
