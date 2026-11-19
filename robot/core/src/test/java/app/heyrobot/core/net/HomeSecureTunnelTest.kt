// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeRoute.Pick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Socket
import java.net.SocketException

/**
 * review-home-net-secure A1: a Tailscale exit node turns the mesh VPN's own 100.64/10 route into a full tunnel
 * (0.0.0.0/0, or its shorter-route forms). On https the certificate's name says whose machine answered, so the /10 rule
 * that keeps plain http off a VPN provider's network isn't needed: 100.64/10 goes through a full tunnel. On plain http it
 * still doesn't, exactly as in review-home-net-bind-b N1.
 */
class HomeSecureTunnelTest {
    private fun a(s: String) = LanRule.literal(s)!!
    private fun p(s: String, sends: Boolean = true, blocks: Boolean = false) =
        HomeRoute.Prefix(a(s.substringBefore('/')), s.substringAfter('/').toInt(), sends, blocks)
    private fun vpn(vararg r: String) = HomeRoute.Net(3, false, false, true, true, r.map { p(it) })
    private val cell = HomeRoute.Net(1, false, false, false, true, listOf(p("0.0.0.0/0")))
    private val wifi = HomeRoute.Net(2, true, false, false, false, listOf(p("0.0.0.0/0"), p("192.168.1.0/24")))
    private val tailscale = a("100.80.50.201")

    @After fun tearDown() = HomeNets.use(null)

    private class Phone(vararg val list: HomeRoute.Net) : HomeNets.Source {
        val bound = mutableListOf<Long>()
        override fun nets() = list.toList()
        override fun bind(s: Socket, id: Long) {
            val vpn = list.firstOrNull { it.vpn && it.applies }
            if (vpn != null && id != vpn.id) throw SocketException("Binding socket to network $id failed: EPERM (Operation not permitted)") // l10n: not text (test)
            bound += id
        }
    }

    private fun refused(x: ByteArray, secure: Boolean): Boolean {
        val phone = Phone(cell, vpn("0.0.0.0/0"))
        HomeNets.use(phone)
        return try {
            HomeNets.socket(x, secure = secure).close()
            false
        } catch (_: OffNetworkException) {
            true
        }
    }

    // ------------------------------------------------------------------ the rule

    @Test fun aFullTunnelCarries100_64OnHttpsOnly() {
        for (v in listOf(vpn("0.0.0.0/0"), vpn("0.0.0.0/1", "128.0.0.0/1"), vpn("0.0.0.0/2", "64.0.0.0/2", "128.0.0.0/1"),
                vpn("96.0.0.0/3"), vpn("100.0.0.0/9")))
            for (under in listOf(cell, wifi)) {
                assertEquals(Pick.Refused, HomeRoute.pick(tailscale, listOf(under, v)))
                assertEquals(Pick.Bind(3), HomeRoute.pick(tailscale, listOf(under, v), secure = true))
            }
    }

    @Test fun aMeshVpnsOwnRouteCountsEitherWay() {
        val meshAddress = a("100.100.1.1")
        for (v in listOf(vpn("0.0.0.0/1", "128.0.0.0/1", "100.64.0.0/10"), vpn("100.100.0.0/16"), vpn("0.0.0.0/0", "100.64.0.0/10")))
            for (secure in listOf(false, true))
                assertEquals(Pick.Bind(3), HomeRoute.pick(meshAddress, listOf(cell, v), secure = secure))
    }

    @Test fun aBlockerStillRefusesOnHttps() {
        // A VPN that blocks a family (an unreachable route) sends nothing anywhere, secure or not.
        val blocked = HomeRoute.Net(3, false, false, true, true, listOf(p("0.0.0.0/0"), p("100.64.0.0/10", sends = false, blocks = true)))
        for (secure in listOf(false, true))
            assertEquals(Pick.Refused, HomeRoute.pick(tailscale, listOf(cell, blocked), secure = secure))
    }

    @Test fun privateAndUniqueLocalAddressesAreUnchanged() {
        // A full tunnel to home already carried private ranges; 100.64/10's rule was the only difference.
        for (x in listOf(a("192.168.1.20"), a("10.8.0.5"), a("fd7a:115c:a1e0::4338:32ca")))
            for (secure in listOf(false, true))
                assertEquals(Pick.Bind(3), HomeRoute.pick(x, listOf(cell, vpn("0.0.0.0/0", "::/0")), secure = secure))
        // No VPN at all: a phone's own link keeps its rule (Wi-Fi that holds the address), and 100.64/10 is still refused.
        assertEquals(Pick.Bind(2), HomeRoute.pick(a("192.168.1.20"), listOf(wifi), secure = true))
        assertEquals(Pick.Refused, HomeRoute.pick(tailscale, listOf(wifi), secure = true))
        // Loopback and public addresses never bind: the system routes them.
        assertEquals(Pick.Direct, HomeRoute.pick(a("8.8.8.8"), listOf(cell, vpn("0.0.0.0/0")), secure = true))
    }

    // ------------------------------------------------------------------ the sockets

    @Test fun anHttpsSocketAtATailscaleAddressIsBoundToTheFullTunnel() {
        val phone = Phone(cell, vpn("0.0.0.0/0"))
        HomeNets.use(phone)
        HomeNets.socket(tailscale, secure = true).close()
        assertEquals(listOf(3L), phone.bound)
    }

    @Test fun aPlainSocketAtATailscaleAddressThroughAFullTunnelIsRefusedUnsent() {
        val phone = Phone(cell, vpn("0.0.0.0/0"))
        HomeNets.use(phone)
        val made = mutableListOf<Socket>()
        try {
            HomeNets.socket(tailscale) { Socket().also { made += it } }
            fail("sent")
        } catch (_: OffNetworkException) {
        }
        assertEquals(0, made.size)
        assertEquals(emptyList<Long>(), phone.bound)
        assertFalse(refused(tailscale, secure = true))
        assertTrue(refused(tailscale, secure = false))
    }

    @Test fun aBoundSocketAtATailscaleAddressIsNeverClosedOnHttps() {
        val phone = Phone(cell, vpn("0.0.0.0/0"))
        HomeNets.use(phone)
        val s = HomeNets.socket(tailscale, secure = true)
        assertFalse(s.isClosed)
        s.close()
    }
}
