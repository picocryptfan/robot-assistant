// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeRoute.Pick
import org.junit.Assert.assertEquals
import org.junit.Test

/** review-link-net M1, M2: which network a home address may use. */
class HomeRouteTest {
    private fun a(s: String) = LanRule.literal(s)!!
    private fun net(id: Long, wifi: Boolean = false, ethernet: Boolean = false, vpn: Boolean = false, cellular: Boolean = false, vararg routes: String) =
        HomeRoute.Net(id, wifi, ethernet, vpn, cellular, routes.map { HomeRoute.Prefix(a(it.substringBefore('/')), it.substringAfter('/').toInt()) })

    private val cell = net(1, cellular = true, routes = arrayOf("0.0.0.0/0", "::/0", "10.0.0.0/8"))
    private val wifi = net(2, wifi = true, routes = arrayOf("0.0.0.0/0", "192.168.1.0/24", "::/0"))
    private val vpn = net(3, vpn = true, cellular = true, routes = arrayOf("100.64.0.0/10", "192.168.1.0/24"))

    @Test fun mobileDataNeverCarriesAHomeAddress() {
        for (x in listOf("10.1.2.3", "192.168.1.5", "172.16.0.1", "169.254.1.1", "100.100.1.1", "fd00::1", "fe80::1", "::ffff:10.1.2.3"))
            assertEquals(x, Pick.Refused, HomeRoute.pick(a(x), listOf(cell)))
        assertEquals(Pick.Refused, HomeRoute.pick(a("10.1.2.3"), emptyList()))
    }

    @Test fun wifiOrEthernetCarriesPrivateAddressesNot100_64() {
        assertEquals(Pick.Bind(2), HomeRoute.pick(a("10.1.2.3"), listOf(cell, wifi)))
        assertEquals(Pick.Bind(2), HomeRoute.pick(a("fd00::1"), listOf(cell, wifi)))
        assertEquals(Pick.Bind(5), HomeRoute.pick(a("::ffff:192.168.0.9"), listOf(net(5, ethernet = true, routes = arrayOf("192.168.0.0/16")))))
        assertEquals(Pick.Refused, HomeRoute.pick(a("100.100.1.1"), listOf(cell, wifi)))
        // Wi-Fi that is also the mobile network (a carrier's Wi-Fi calling transport): not a home network.
        assertEquals(Pick.Refused, HomeRoute.pick(a("10.1.2.3"), listOf(net(6, wifi = true, cellular = true, routes = arrayOf("0.0.0.0/0")))))
    }

    @Test fun aVpnCarriesWhatItsRoutesCoverAndWinsATie() {
        assertEquals(Pick.Bind(3), HomeRoute.pick(a("100.100.1.1"), listOf(cell, wifi, vpn)))
        assertEquals(Pick.Bind(3), HomeRoute.pick(a("192.168.1.5"), listOf(wifi, vpn)))
        assertEquals(Pick.Refused, HomeRoute.pick(a("10.9.9.9"), listOf(cell, vpn)))
        // A VPN that applies decides first, as the system routes (its table before Wi-Fi's): a bind to Wi-Fi would fail there.
        assertEquals(Pick.Bind(8), HomeRoute.pick(a("192.168.1.5"), listOf(wifi, net(8, vpn = true, routes = arrayOf("192.168.0.0/16")))))
    }

    @Test fun loopbackAndPublicAddressesAreNotHomeAddresses() {
        for (x in listOf("127.0.0.1", "::1", "8.8.8.8", "2606:4700::1111")) assertEquals(x, Pick.Direct, HomeRoute.pick(a(x), listOf(cell)))
    }
}
