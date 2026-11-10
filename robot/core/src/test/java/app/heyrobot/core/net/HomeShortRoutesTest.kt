// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeRoute.Pick
import org.junit.Assert.assertEquals
import org.junit.Test

/** review-home-net-bind-b N1: a full tunnel written as shorter routes still doesn't carry 100.64/10; a /10 or longer does. */
class HomeShortRoutesTest {
    private fun a(s: String) = LanRule.literal(s)!!
    private fun p(s: String) = HomeRoute.Prefix(a(s.substringBefore('/')), s.substringAfter('/').toInt())
    private fun vpn(vararg r: String) = HomeRoute.Net(3, false, false, true, true, r.map { p(it) })
    private val cell = HomeRoute.Net(1, false, false, false, true, listOf(p("0.0.0.0/0")))

    @Test fun aFullTunnelInShorterRoutesStillRefuses100_64() {
        val x = a("100.100.1.1")
        for (v in listOf(vpn("0.0.0.0/1", "128.0.0.0/1"), vpn("0.0.0.0/2", "64.0.0.0/2", "128.0.0.0/1"), vpn("96.0.0.0/3"), vpn("100.0.0.0/9")))
            assertEquals(Pick.Refused, HomeRoute.pick(x, listOf(cell, v)))
        // A mesh VPN's own 100.64/10, or a longer route, counts.
        for (v in listOf(vpn("0.0.0.0/1", "128.0.0.0/1", "100.64.0.0/10"), vpn("100.100.0.0/16")))
            assertEquals(Pick.Bind(3), HomeRoute.pick(x, listOf(cell, v)))
        // Private ranges keep the short routes (a full tunnel to home).
        assertEquals(Pick.Bind(3), HomeRoute.pick(a("192.168.1.20"), listOf(cell, vpn("0.0.0.0/1", "128.0.0.0/1"))))
    }
}
