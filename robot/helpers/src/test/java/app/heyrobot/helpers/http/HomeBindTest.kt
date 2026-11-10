package app.heyrobot.helpers.http

import app.heyrobot.core.Abort
import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeNets
import app.heyrobot.core.net.HomeRoute
import app.heyrobot.core.net.OffNetworkException
import app.heyrobot.core.web.SearchRequest
import app.heyrobot.core.web.WebFail
import app.heyrobot.helpers.web.WebFailure
import app.heyrobot.helpers.web.WebTransport
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.Collections

/**
 * review-link-net M1, the clients home-net-bind left: a helper at a home address over https, and your SearXNG over http
 * or https, go only over the network that holds the address, the socket bound there (core HomeNets), else refused unsent.
 * The addresses are a ULA and a link-local one that nothing here answers: what counts is the bind or the refusal.
 */
class HomeBindTest {
    private class Nets(vararg val list: HomeRoute.Net) : HomeNets.Source {
        val bound = Collections.synchronizedList(mutableListOf<Long>())
        override fun nets() = list.toList()
        override fun bind(s: Socket, id: Long) { bound += id }
    }

    private fun net(id: Long, wifi: Boolean = false, cellular: Boolean = false, routes: List<String>) =
        HomeRoute.Net(id, wifi, false, false, cellular, routes.map { r -> HomeRoute.Prefix(LanRule.literal(r.substringBefore('/'))!!, r.substringAfter('/').toInt()) })

    private val ula = InetAddress.getByName("fd12:3456:789a::5")
    private val homeDns = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, ula.address)) }
    private val wifi = Nets(net(3, wifi = true, routes = listOf("0.0.0.0/0", "::/0", "fd12:3456:789a::/64", "169.254.0.0/16")))
    private val mobile = Nets(net(7, cellular = true, routes = listOf("0.0.0.0/0", "::/0", "fd12:3456:789a::/64", "169.254.0.0/16")))

    @After fun tearDown() = HomeNets.use(null)

    private fun helper(url: String): Throwable? = runCatching {
        HelperHttp.create(url, null, home = true, dns = homeDns, connectMs = 300)!!.use { it.get("/v1/models").execute().close() }
    }.exceptionOrNull()

    private fun search(url: String, nets: Nets): Throwable? {
        HomeNets.use(nets)
        return runCatching { WebTransport(homeDns, connectMs = 300, readMs = 1_000).send(SearchRequest("GET", url, listOf("User-Agent" to "Robot")), WebTransport.Route.HOME, Abort()) }.exceptionOrNull()
    }

    @Test fun aHomeHelperOverHttpsIsBoundToTheNetworkThatHoldsIt() {
        HomeNets.use(wifi)
        assertTrue(helper("https://nas.example:8443") != null)
        assertEquals(listOf(3L), wifi.bound.toList())
    }

    @Test fun aHomeHelperOverHttpsOnMobileDataIsRefusedUnsent() {
        HomeNets.use(mobile)
        assertTrue(helper("https://nas.example:8443").toString(), helper("https://nas.example:8443") is OffNetworkException)
        assertEquals(emptyList<Long>(), mobile.bound.toList())
    }

    @Test fun yourSearxngIsBoundToTheNetworkThatHoldsIt() {
        for (url in listOf("http://searx.example:8888/search?q=x", "https://searx.example/search?q=x", "http://169.254.77.77:8888/search?q=x")) {
            wifi.bound.clear()
            assertTrue(search(url, wifi) is WebFailure)
            assertEquals(url, listOf(3L), wifi.bound.toList())
        }
    }

    @Test fun yourSearxngOnMobileDataIsRefusedAsNotHome() {
        for (url in listOf("http://searx.example:8888/search?q=x", "https://searx.example/search?q=x", "http://169.254.77.77:8888/search?q=x")) {
            assertEquals(url, WebFail.NOT_HOME, (search(url, mobile) as WebFailure).why)
            assertEquals(url, emptyList<Long>(), mobile.bound.toList())
        }
    }
}
