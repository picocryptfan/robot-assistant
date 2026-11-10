// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import app.heyrobot.core.helpers.LanRule
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

// review-link-net M1, M2 (1.3.2): a home address (private, link-local, unique local, 100.64/10) means your network only
// over the network that holds it. On mobile data, or a stranger's network with no VPN up, the same address can be anyone's
// machine (the carrier's or the hotel's), so Robot never sends to one there: a home address goes only over Wi-Fi or
// Ethernet, or over a VPN that is up and whose routes cover it, on a socket bound to that network (Network.bindSocket), so
// it can't fall back to mobile data; 100.64/10 only through such a VPN. No network qualifies: refused, nothing sent
// ([OffNetworkException]). Loopback and public addresses are not home addresses: unbound, as before (their own rules
// stand: LanRule, ConnRules). Nothing here names a VPN product: any VPN whose routes hold the address counts.

/** Pure: which network a home address may use. */
object HomeRoute {
    /**
     * One route's destination: [address] (IPv4-mapped kept as IPv4) and its [length] in bits; [sends] false for a route
     * that sends nothing (a VPN's excluded range: a throw or unreachable route); [blocks] true for an unreachable one
     * (review-home-net-bind-b N3: nothing goes past it, unlike a throw route).
     */
    class Prefix(address: ByteArray, val length: Int, val sends: Boolean = true, val blocks: Boolean = false) {
        private val net: ByteArray = v4(address) ?: address.copyOf()

        /** [x] is this prefix's own address (a link's address with its length, as [ownLinks] keeps it). */
        fun at(x: ByteArray): Boolean = (v4(x) ?: x).contentEquals(net)

        fun covers(a: ByteArray): Boolean {
            val x = v4(a) ?: a
            if (x.size != net.size || length < 0 || length > x.size * 8) return false
            for (i in 0 until length) {
                val bit = 0x80 ushr (i % 8)
                if ((x[i / 8].toInt() and bit) != (net[i / 8].toInt() and bit)) return false
            }
            return true
        }
    }

    /**
     * A network the phone has now: its transports and its routes (an id only the platform side reads). A VPN's transports
     * include those of the networks it rides on. [applies]: a VPN that applies to Robot (Robot's default network).
     */
    class Net(val id: Long, val wifi: Boolean, val ethernet: Boolean, val vpn: Boolean, val cellular: Boolean, val routes: List<Prefix>, val applies: Boolean = vpn,
              /** The network's own addresses (an unbound socket's local address shows which network it left on). */
              val addrs: List<ByteArray> = emptyList()) {
        override fun toString(): String = "Net(wifi=$wifi, ethernet=$ethernet, vpn=$vpn, cellular=$cellular)" // l10n: not text (toString, for tests)
    }

    sealed class Pick {
        /** Not a home address: the system's routing, as before. */
        object Direct : Pick()
        /** Bind the socket to network [id]. */
        data class Bind(val id: Long) : Pick()
        /** The VPN that applies to Robot doesn't route it and rides on Wi-Fi or Ethernet that holds it: the system sends it there. */
        object Unbound : Pick()
        /** No network holds it now: nothing is sent. */
        object Refused : Pick()
    }

    /** [a] is a home address: private, link-local, unique local or 100.64/10 (never loopback, which never leaves the phone). */
    fun home(a: ByteArray): Boolean = when (LanRule.classify(a)) {
        LanRule.Range.PRIVATE, LanRule.Range.LINK_LOCAL, LanRule.Range.CGNAT, LanRule.Range.ULA -> true
        else -> false
    }

    /**
     * Where [a] may go among [nets]. Under a VPN that applies to Robot (review-home-net-bind H1), as the system routes: its
     * own longest matching route decides first. It routes [a]: bound to it. It doesn't: the system sends [a] outside it, on
     * the network it rides on, and a VPN that can't be bypassed (VpnService's default; an app can't tell which) refuses a
     * bind to any other network (EPERM), so: unbound when Wi-Fi or Ethernet holds [a] and the VPN doesn't ride on mobile
     * data alone, the socket then checked to have left from that network ([leftFrom], review-home-net-bind-b N2, N4); else
     * refused. A VPN that doesn't apply to Robot never counts (M3: a bind to it fails too). No VPN
     * applies: Wi-Fi or Ethernet that isn't the mobile network (DirectPlaces' rule) with a route that covers [a], the
     * longest route winning. 100.64/10 only through the VPN.
     */
    fun pick(a: ByteArray, nets: List<Net>, own: List<Prefix> = emptyList()): Pick {
        if (!home(a)) return Pick.Direct
        val cgnat = LanRule.classify(a) == LanRule.Range.CGNAT
        val local = if (cgnat) null else nets.filter { !it.vpn && (it.wifi || it.ethernet) && !it.cellular }
            .mapNotNull { n -> longest(n, a)?.takeIf { it.sends }?.let { n to it.length } }.maxByOrNull { it.second }?.first
        val vpn = nets.firstOrNull { it.vpn && it.applies }
        val r = vpn?.let { longest(it, a) }
        // M2, review-home-net-bind-b N1: for 100.64/10 only a route at least as long as /10 counts (a mesh VPN adds 100.64/10
        // itself); a default route, or a full tunnel written as shorter routes (0.0.0.0/1 with 128.0.0.0/1, 64.0.0.0/2),
        // would carry plain http to the VPN provider's network. Private ranges keep them (a full tunnel to home).
        if (vpn != null && r != null && r.sends) return if (cgnat && r.length < 10) Pick.Refused else Pick.Bind(vpn.id)
        // N3: an unreachable route (the system adds one when a VPN blocks a family) sends nothing anywhere: refused, even
        // on the phone's own hotspot link (review-home-net-bind-c L3; the connect would fail with ENETUNREACH).
        if (r != null && r.blocks) return Pick.Refused
        // L5: a link the phone runs itself (its hotspot, USB or Bluetooth tethering) holds [a]: the system sends an unbound
        // socket there (its local table comes before any network's), never to mobile data.
        if (!cgnat && own.any { it.covers(a) }) return Pick.Unbound
        if (vpn == null) return local?.let { Pick.Bind(it.id) } ?: Pick.Refused
        // N2, N4: the transport flags don't always name the network the system sends it on (its default), so the socket's
        // own address decides once it connects; a VPN on mobile data alone is refused before anything goes out.
        return if (local != null && !(vpn.cellular && !vpn.wifi && !vpn.ethernet)) Pick.Unbound else Pick.Refused
    }

    /**
     * review-home-net-bind-b N2: an unbound socket to home address [a], connected from [from], left on Wi-Fi or Ethernet
     * that holds [a] ([from] is one of its own addresses) or on a link the phone runs itself that holds it; never on
     * mobile data or a VPN. 100.64/10 never goes unbound.
     */
    fun leftFrom(a: ByteArray, from: ByteArray, nets: List<Net>, own: List<Prefix>): Boolean {
        if (!home(a) || LanRule.classify(a) == LanRule.Range.CGNAT) return false
        val f = v4(from) ?: from
        return nets.any { n ->
            !n.vpn && (n.wifi || n.ethernet) && !n.cellular && n.addrs.any { (v4(it) ?: it).contentEquals(f) } && longest(n, a)?.sends == true
        } || own.any { it.covers(a) && it.at(from) }
    }

    /** A network interface as java.net.NetworkInterface shows it, and one of its addresses. */
    class Link(val name: String, val up: Boolean, val loopback: Boolean, val p2p: Boolean, val virtual: Boolean, val addrs: List<Addr>)
    class Addr(val address: ByteArray, val length: Int, val broadcast: Boolean)

    /**
     * L5: the private IPv4 ranges of links the phone runs itself, which are not a Network an app sees (its hotspot, USB or
     * Bluetooth tethering): up, broadcast (not mobile data's raw link, a VPN's tunnel or loopback), and not the interface
     * of any network in [netNames] (Wi-Fi and Ethernet go by their own rules). No permission is needed to list them.
     */
    fun ownLinks(links: List<Link>, netNames: Set<String>): List<Prefix> = links
        .filter { it.up && !it.loopback && !it.p2p && !it.virtual && it.name !in netNames }
        .flatMap { l ->
            l.addrs.filter { it.broadcast && it.address.size == 4 && it.length in 8..30 && LanRule.classify(it.address) == LanRule.Range.PRIVATE }
                .map { Prefix(it.address, it.length) }
        }

    /** [n]'s longest route that covers [a] (an excluded range beats a shorter route that sends, as in the system's table). */
    private fun longest(n: Net, a: ByteArray): Prefix? = n.routes.filter { it.covers(a) }.maxByOrNull { it.length }

    private fun v4(a: ByteArray): ByteArray? =
        if (a.size == 16 && (0 until 10).all { a[it] == 0.toByte() } && a[10] == 0xFF.toByte() && a[11] == 0xFF.toByte()) a.copyOfRange(12, 16) else null
}

/** A home address while no network that holds it is up (mobile data, or no VPN for 100.64/10): nothing was sent. */
class OffNetworkException : NoRouteToHostException("not on that network") // l10n: not text (an exception's message)

/** The platform side of [HomeRoute]: the phone's networks, and sockets bound to the one a home address may use. */
object HomeNets {
    interface Source {
        fun nets(): List<HomeRoute.Net>
        /** Binds unconnected [s] to network [id]; throws if that network is gone. */
        fun bind(s: Socket, id: Long)
        /** Links the phone runs itself ([HomeRoute.ownLinks]). */
        fun own(): List<HomeRoute.Prefix> = emptyList()
    }

    /** Null until [start] (or a test's [use]): no network known, so every home address is refused (fail closed). */
    @Volatile private var source: Source? = null

    /** Once per process (RobotApp). */
    fun start(ctx: Context) {
        if (source == null) source = AndroidSource(ctx.applicationContext)
    }

    /** Tests: the networks to see (null: none). */
    fun use(s: Source?) { source = s }

    /** A new unconnected socket from [make] for [a]: bound to the network that holds a home address, or refused. */
    fun socket(a: ByteArray, toward: (InetSocketAddress) -> SocketAddress = { it }, make: () -> Socket = ::Socket): Socket {
        val (src, p) = choose(a)
        if (p == HomeRoute.Pick.Refused) throw OffNetworkException()
        // review-home-net-bind-b N2: unbound, it is checked once it connects ([HomeSocket] picks again then). [toward]: tests.
        if (p == HomeRoute.Pick.Unbound) return HomeSocket(toward)
        val s = make()
        if (p is HomeRoute.Pick.Bind) bind(src, s, p.id)
        return s
    }

    private fun choose(a: ByteArray): Pair<Source?, HomeRoute.Pick> {
        val src = source
        val nets = try { src?.nets().orEmpty() } catch (_: Exception) { emptyList() }
        val own = if (src == null || !HomeRoute.home(a)) emptyList() else try { src.own() } catch (_: Exception) { emptyList() }
        return src to HomeRoute.pick(a, nets, own)
    }

    /** Binds [s] to [id], or closes it and refuses (a VPN's EPERM, a network gone). */
    private fun bind(src: Source?, s: Socket, id: Long) {
        try {
            (src ?: throw OffNetworkException()).bind(s, id)
        } catch (e: Exception) {
            try { s.close() } catch (_: IOException) {}
            throw OffNetworkException()
        }
    }

    /**
     * review-home-net-bind L1: a socket that is bound or refused when it connects, for the address it connects to (the
     * route OkHttp picked), never for a lookup made before: refused, nothing is sent. [toward]: where it really connects
     * (tests: a local server).
     */
    class HomeSocket internal constructor(private val toward: (InetSocketAddress) -> SocketAddress) : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val e = endpoint as? InetSocketAddress
            val a = e?.address?.address ?: throw OffNetworkException()
            val (src, p) = choose(a)
            if (p == HomeRoute.Pick.Refused) throw OffNetworkException()
            if (p is HomeRoute.Pick.Bind) bind(src, this, p.id)
            super.connect(toward(e), timeout)
            if (p == HomeRoute.Pick.Unbound) leftHome(src, a, this)
        }
    }

    /** N2: [s], connected unbound to [a], left on a network that holds [a]; else closed before anything is written (only its SYN went out) and refused. */
    private fun leftHome(src: Source?, a: ByteArray, s: Socket) {
        val from = s.localAddress?.address
        val nets = try { src?.nets().orEmpty() } catch (_: Exception) { emptyList() }
        val own = if (src == null) emptyList() else try { src.own() } catch (_: Exception) { emptyList() }
        if (from == null || !HomeRoute.leftFrom(a, from, nets, own)) {
            try { s.close() } catch (_: IOException) {}
            throw OffNetworkException()
        }
    }

    /** OkHttp's sockets: each a [HomeSocket]. [toward]: tests only, where sockets really connect. */
    class Factory(private val toward: (InetSocketAddress) -> SocketAddress = { it }) : SocketFactory() {
        override fun createSocket(): Socket = HomeSocket(toward)
        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
    }

    private class AndroidSource(private val ctx: Context) : Source {
        private fun cm(): ConnectivityManager = ctx.getSystemService(ConnectivityManager::class.java) ?: throw IOException()

        @Suppress("DEPRECATION") // getAllNetworks: still the one call that lists every network an app may see
        override fun nets(): List<HomeRoute.Net> {
            val cm = cm()
            // A VPN that applies to Robot is Robot's default network; one that doesn't (a work profile's, a per-app VPN
            // that leaves Robot out) may still be listed. Blocked (an always-on VPN that is down): null, so none applies.
            val active = cm.activeNetwork?.networkHandle
            return cm.allNetworks.mapNotNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
                // A VPN's excluded ranges are throw or unreachable routes: kept, as routes that send nothing.
                val routes = lp.routes
                    .map { HomeRoute.Prefix(it.destination.address.address, it.destination.prefixLength, it.type == RouteInfo.RTN_UNICAST, it.type == RouteInfo.RTN_UNREACHABLE) } +
                    lp.linkAddresses.map { HomeRoute.Prefix(it.address.address, it.prefixLength) }
                HomeRoute.Net(n.networkHandle, caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    vpn, caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), routes, vpn && n.networkHandle == active, lp.linkAddresses.map { it.address.address })
            }
        }

        @Suppress("DEPRECATION")
        override fun own(): List<HomeRoute.Prefix> {
            val cm = cm()
            val names = cm.allNetworks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
            val links = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { i ->
                HomeRoute.Link(i.name, i.isUp, i.isLoopback, i.isPointToPoint, i.isVirtual,
                    i.interfaceAddresses.map { HomeRoute.Addr(it.address.address, it.networkPrefixLength.toInt(), it.broadcast != null) })
            }
            return HomeRoute.ownLinks(links, names)
        }

        @Suppress("DEPRECATION")
        override fun bind(s: Socket, id: Long) {
            val cm = cm()
            (cm.allNetworks.firstOrNull { it.networkHandle == id } ?: throw IOException()).bindSocket(s)
        }
    }
}
