// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkLog
import app.heyrobot.core.link.LinkParseWire
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.LinkVCard
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.link.LocalAddress
import app.heyrobot.core.link.OnionKey
import app.heyrobot.core.link.PushRoute
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.seal.KeyException
import app.heyrobot.core.seal.KeyProblem
import app.heyrobot.core.seal.SecretWrap
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey as JcaEcPrivateKey
import java.security.interfaces.ECPublicKey as JcaEcPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// The two-peer JVM harness's pieces (robot-to-robot-plan.md §8.3, job L3): two complete link stacks in one JVM on
// libsignal's desktop library, each with its own sealed state on storage, joined by the direct carrier over loopback, a fake
// push server (real RFC 8291 both ways: the connector's encryption and decryption) and a fake Tor network (onion services
// that check client authorization, streams over pipes with an optional delay and drops), and an injected clock.

/** robot_link_v1 in software: AES-256-GCM with the additional data (as LinkStoresTest's). */
class SoftLinkKey : SecretWrap {
    private var master: ByteArray? = null

    override fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray {
        val m = master ?: if (create) ByteArray(32).also(SecureRandom()::nextBytes).also { master = it } else throw KeyException(KeyProblem.GONE)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(key)
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        val m = master ?: throw KeyException(KeyProblem.GONE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
        c.updateAAD(aad)
        return try { c.doFinal(wrapped, 12, wrapped.size - 12) } catch (_: Exception) { throw KeyException(KeyProblem.BAD_DATA) }
    }
}

class FakeEnv(start: Long) : LinkEnv {
    val clock = AtomicLong(start)
    val network = AtomicBoolean(true)
    override fun now(): Long = clock.get()
    override fun networkAllowed(): Boolean = network.get()
}

/** What a node handed on. */
class RecordingSink : LinkSink {
    val delivered = CopyOnWriteArrayList<Pair<String, LinkRequest>>()
    val acked = CopyOnWriteArrayList<Triple<String, String, AckState>>()
    val gaveUp = CopyOnWriteArrayList<String>()
    val paired = CopyOnWriteArrayList<String>()
    val unpaired = CopyOnWriteArrayList<String>()
    val busy = CopyOnWriteArrayList<Long>()
    val raised = CopyOnWriteArrayList<String>()
    val lowerAsked = CopyOnWriteArrayList<String>()
    val lowered = CopyOnWriteArrayList<String>()
    val moved = CopyOnWriteArrayList<String>()

    override fun delivered(pairing: String, request: LinkRequest): AckState { delivered += pairing to request; return AckState.RECEIVED }
    override fun acked(pairing: String, requestId: String, state: AckState) { acked += Triple(pairing, requestId, state) }
    override fun gaveUp(pairing: String, requestId: String) { gaveUp += requestId }
    override fun paired(pairing: String) { paired += pairing }
    override fun unpaired(pairing: String) { unpaired += pairing }
    override fun busy(pairing: String, until: Long) { busy += until }
    override fun raised(pairing: String) { raised += pairing }
    override fun lowerAsked(pairing: String) { lowerAsked += pairing }
    override fun lowered(pairing: String) { lowered += pairing }
    override fun moved(pairing: String) { moved += pairing }
}

/** A connected pair of pipes: what one side writes, the other reads. */
class PipeDuplex(override val input: InputStream, override val output: OutputStream, private val onClose: () -> Unit) : Duplex {
    override fun readTimeout(ms: Int) {}
    override fun close() { try { input.close() } catch (_: IOException) {}; try { output.close() } catch (_: IOException) {}; onClose() }

    companion object {
        fun pair(): Pair<PipeDuplex, PipeDuplex> {
            val aIn = PipedInputStream(1 shl 17); val bOut = PipedOutputStream(aIn)
            val bIn = PipedInputStream(1 shl 17); val aOut = PipedOutputStream(bIn)
            lateinit var a: PipeDuplex
            lateinit var b: PipeDuplex
            a = PipeDuplex(aIn, aOut) { try { bOut.close() } catch (_: IOException) {} }
            b = PipeDuplex(bIn, bOut) { try { aOut.close() } catch (_: IOException) {} }
            return a to b
        }
    }
}

/**
 * The direct carrier over loopback: each Robot "is" a private address (10.0.0.n) whose port maps to its real loopback
 * listener, as two phones on one Wi-Fi would be.
 */
class LoopbackLan {
    private val ports = ConcurrentHashMap<String, Int>()
    val dials = AtomicInteger()
    /** L5-fix: the network both phones are on is gone (each dial fails), while the listeners stay up. */
    @Volatile var down = false

    fun carrier(n: Int): DirectNet = object : DirectNet {
        private var server: ServerSocket? = null
        private var last = 0
        override fun listen(accept: (Duplex) -> Unit): Int {
            server?.let { return it.localPort }
            val s = (if (last != 0) try { ServerSocket(last, 50, InetAddress.getLoopbackAddress()) } catch (_: IOException) { null } else null)
                ?: ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            last = s.localPort
            server = s
            ports["10.0.0.$n:${s.localPort}"] = s.localPort
            Thread {
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: IOException) { break }
                    accept(SocketDuplex(c))
                }
            }.apply { isDaemon = true }.start()
            return s.localPort
        }
        override fun stopListening() { server?.close(); server = null }
        override fun localAddresses(port: Int): List<LocalAddress> = listOf(LocalAddress(byteArrayOf(10, 0, 0, n.toByte()), port))
        override fun dial(address: LocalAddress): Duplex {
            dials.incrementAndGet()
            if (down) throw IOException()
            val a = address.address
            val real = ports["${a[0]}.${a[1]}.${a[2]}.${a[3]}:${address.port}"] ?: throw IOException()
            val s = Socket()
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), real), 5_000)
            return SocketDuplex(s)
        }
    }
}

/**
 * A fake Tor network: onion services by key (derived from the key blob Tor would keep), each answering only clients
 * holding a key its owner authorized (empty: anyone, a pairing code's one-time onion), streams over pipes. [drop] refuses a
 * share of dials, as an unreliable network would; [delayMs] holds each one back.
 */
class FakeTorNetwork {
    private class Service(val clients: Set<String>, val accept: (Duplex) -> Unit)
    private val services = ConcurrentHashMap<String, Service>()
    @Volatile var drop: Double = 0.0
    @Volatile var delayMs: Long = 0
    val dials = AtomicInteger()
    val hostDials = AtomicInteger()
    val refused = AtomicInteger()
    private val random = java.util.Random(1502)
    var push: FakePushServer? = null

    fun carrier(): TorNet = object : TorNet {
        private val auth = ConcurrentHashMap<String, ByteArray>()
        private val mine = ConcurrentHashMap<Int, String>()
        @Volatile private var up = false
        override fun up(): Boolean { up = true; return true }
        override fun down() { up = false; for (o in mine.values) services.remove(o); mine.clear() }
        override val running: Boolean get() = up
        override fun publish(slot: Int, keyBlob: String?, clients: List<ByteArray>, accept: (Duplex) -> Unit): PublishedOnion {
            if (!up) throw TorException(TorProblem.NOT_READY)
            val blob = keyBlob ?: ("ED25519-V3:" + Base64.getEncoder().encodeToString(ByteArray(64).also(SecureRandom()::nextBytes)))
            val key = OnionKey(MessageDigest.getInstance("SHA-256").digest(blob.toByteArray()))
            val id = OnionAddress.of(key)
            mine.put(slot, id)?.let { services.remove(it) }
            services[id] = Service(clients.map { Base64.getEncoder().encodeToString(it) }.toSet(), accept)
            return PublishedOnion(id, blob)
        }
        override fun unpublish(slot: Int) { mine.remove(slot)?.let { services.remove(it) } }
        override fun clientAuth(onion: OnionKey, privateKey: ByteArray) { auth[OnionAddress.of(onion)] = privateKey.copyOf() }
        override fun dial(onion: OnionKey, isolation: String): Duplex {
            dials.incrementAndGet()
            if (!up) throw TorException(TorProblem.NOT_READY)
            if (delayMs > 0) Thread.sleep(delayMs)
            synchronized(random) { if (random.nextDouble() < drop) throw SocksException(SocksProblem.ONION_DOWN) }
            val id = OnionAddress.of(onion)
            val s = services[id] ?: throw SocksException(SocksProblem.ONION_DOWN)
            if (s.clients.isNotEmpty()) {
                val priv = auth[id]
                val pub = priv?.let { Base64.getEncoder().encodeToString(x25519Public(it)) }
                if (pub == null || pub !in s.clients) { refused.incrementAndGet(); throw SocksException(SocksProblem.ONION_AUTH) }
            }
            val (a, b) = PipeDuplex.pair()
            Thread { s.accept(b) }.apply { isDaemon = true }.start()
            return a
        }
        override fun dialHost(host: String, port: Int, isolation: String): Duplex { hostDials.incrementAndGet(); throw IOException() }
    }

    fun has(onion: OnionKey): Boolean = services.containsKey(OnionAddress.of(onion))

    companion object {
        /** X25519(private, 9): the public key Tor computes from a client's private key (libsignal's agreement with the base point). */
        fun x25519Public(priv: ByteArray): ByteArray =
            ECPrivateKey(priv).calculateAgreement(org.signal.libsignal.protocol.ecc.ECPublicKey(byteArrayOf(5, 9) + ByteArray(31)))
    }
}

/**
 * A fake push server and distributor: each instance a Robot registers gets an https address and RFC 8291 keys (P-256 and a
 * 16-byte secret, as the connector makes them); a body posted to an address is decrypted with them (the connector's
 * WebPushHybridDecrypt) and handed to that Robot's node as the push job would. [down] refuses posts.
 */
class FakePushServer {
    private class Instance(val node: () -> LinkNode?, val name: String, val pub: JcaEcPublicKey, val priv: JcaEcPrivateKey, val auth: ByteArray)
    private val byUrl = ConcurrentHashMap<String, Instance>()
    val posts = CopyOnWriteArrayList<Boolean>()
    /** Every body posted, as it crossed the push server (RFC 8291 ciphertext). */
    val bodies: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    /** The same, with the push address each went to. */
    val sentTo: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(ArrayList())
    @Volatile var down = false
    /** Holding: posts are taken (201) but delivered only at [release], as a slow push server would. */
    @Volatile var hold = false
    private val held = CopyOnWriteArrayList<() -> Unit>()

    fun release() { val all = held.toList(); held.clear(); all.forEach { it() } }

    /** A push app is chosen; false, as on a phone with none. */
    @Volatile var chosen = true
    val registered = java.util.concurrent.atomic.AtomicInteger()

    fun net(node: () -> LinkNode?): PushNet = object : PushNet {
        override fun available() = chosen
        override fun register(instance: String): Boolean {
            registered.incrementAndGet()
            val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val i = Instance(node, instance, g.public as JcaEcPublicKey, g.private as JcaEcPrivateKey, ByteArray(16).also(SecureRandom()::nextBytes))
            val url = "https://push.example.org/up/$instance"
            byUrl[url] = i
            Thread { node()?.onPush(PushEvent(PushEvent.Kind.ENDPOINT, instance, route = PushRoute(url, point(i.pub), i.auth))) }.start()
            return true
        }
        override fun unregister(instance: String) { byUrl.entries.removeIf { it.value.name == instance } }
    }

    val poster = PushPoster { route, body, _, viaTor, _ ->
        if (down) throw PushException(PushProblem.UNREACHABLE)
        val i = byUrl[route.endpoint] ?: throw PushException(PushProblem.GONE)
        require(body.size <= 4_096)
        posts += viaTor
        bodies += body.copyOf()
        sentTo += route.endpoint to body.copyOf()
        val plain = WebPushHybridDecrypt.Builder().withAuthSecret(i.auth).withRecipientPublicKey(i.pub).withRecipientPrivateKey(i.priv).build().decrypt(body, null)
        val deliver = { i.node()?.onPush(PushEvent(PushEvent.Kind.MESSAGE, i.name, plain)); Unit }
        if (hold) held += deliver else deliver()
    }

    companion object {
        fun point(k: JcaEcPublicKey): ByteArray = byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY)
        private fun fixed(b: BigInteger): ByteArray = b.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
    }
}

/** One Robot in the harness: its own sealed state in [dir], its own node over the shared fake networks. */
class TestRobot(
    val dir: File,
    val env: FakeEnv,
    lan: LoopbackLan?,
    lanIndex: Int,
    torNet: FakeTorNetwork?,
    pushServer: FakePushServer?,
    val name: String,
    created: Boolean = false,
    /** L6: the phone as the desk sees it; set: the node's sink is a real LinkDesk over an in-memory inbox. */
    val deskEnv: TestDeskEnv? = null,
    handlers: List<DeskHandler> = listOf(EtaHandler),
    /** L7: handlers over this Robot's own state (free/busy and plans keep theirs there). */
    more: (LinkStateStore) -> List<DeskHandler> = { emptyList() },
) {
    val key = SoftLinkKey()
    val records = LinkRecords(File(dir, "state"), key)
    val sink = RecordingSink()
    val log = LinkLog(null) { env.now() }
    val stores: LinkStores = LinkStores.create(records, env.now())
    val state = LinkStateStore(records).also { it.load(emptyMap()) }
    @Volatile var node: LinkNode? = null
    val direct = lan?.carrier(lanIndex)
    val tor = torNet?.carrier()
    val push = pushServer?.net { node }
    /** L16: this many reads find :linkparse unreachable, as LinkParseLink.decode's null. */
    val parseAway = java.util.concurrent.atomic.AtomicInteger(0)
    /** L4: as on the phone, the plaintext read as :linkparse reads it, answered in its frame, and checked again as :link does. */
    /** This many reads end with :linkparse failing on the request, as LinkParseLink.decode's Bad(PARSER). */
    val parseBad = java.util.concurrent.atomic.AtomicInteger(0)
    val decoder = RequestDecoder { plain, cap ->
        if (parseAway.getAndUpdate { maxOf(0, it - 1) } > 0) null
        else if (parseBad.getAndUpdate { maxOf(0, it - 1) } > 0) app.heyrobot.core.link.requests.RequestRead.Bad(app.heyrobot.core.link.LinkProblem.PARSER)
        else StandInParser.decode(plain, cap)
    }

    /** L6: the waiting inbox, in memory (sealed only on the phone): it opens only while [deskEnv] says unlocked. */
    val box = MemoryBox { deskEnv?.locked == true }
    val desk: LinkDesk? = deskEnv?.let { LinkDesk(state, box, it, decoder, handlers + more(state)) }

    init {
        node = LinkNode(stores, state, env, decoder, desk ?: sink, direct, tor, push, pushServer?.poster, log, { name })
        desk?.attach(node!!.deskOut)
    }

    fun n(): LinkNode = node!!
}

/** L6: the phone's state for the desk, set by the test. */
class TestDeskEnv : DeskEnv {
    @Volatile var locked = false
    @Volatile var phoneLocked = false
    @Volatile var settings = app.heyrobot.core.link.LinkSettings(on = true)
    val pings = AtomicInteger()
    override fun locked() = locked
    override fun phoneLock() = phoneLocked
    override fun settings() = settings
    override fun ping() { pings.incrementAndGet() }
}

/** L6: the waiting inbox in memory: put works locked, open only unlocked (as robot_inbox_v1 does), at most [max] items. */
class MemoryBox(private val max: Int = app.heyrobot.core.link.LinkInbox.MAX, private val isLocked: () -> Boolean) : DeskBox {
    private val items = java.util.LinkedHashMap<String, app.heyrobot.core.link.Waiting>()
    @Synchronized override fun put(pairing: String, at: Long, request: ByteArray): String {
        if (items.size >= max) throw app.heyrobot.core.link.LinkStateException(app.heyrobot.core.link.LinkStateProblem.FULL)
        val id = app.heyrobot.core.link.LinkIds.newId()
        items[id] = app.heyrobot.core.link.Waiting(id, pairing, at, request)
        return id
    }
    @Synchronized override fun ids(): List<String> = items.keys.toList()
    @Synchronized override fun count(): Int = items.size
    @Synchronized override fun open(id: String): app.heyrobot.core.link.Waiting? {
        if (isLocked()) throw app.heyrobot.core.link.LinkStateException(app.heyrobot.core.link.LinkStateProblem.LOCKED)
        return items[id]
    }
    @Synchronized override fun delete(id: String) { items.remove(id) }
}

/**
 * :linkparse's reading (app/linkparse LinkParser, which this module can't name), for the harness: L1's decoder, the vCard
 * subset, its answer as LinkParseWire's frame. The node then gets only what LinkParseWire.check keeps, as on the phone.
 */
object StandInParser {
    /** As app/link's LinkParseLink.decode: a length refused by itself, else the answer checked, and PARSER for one :link wouldn't keep. */
    fun decode(plain: ByteArray, cap: Int): RequestRead {
        if (!LinkParseWire.sizeOk(plain.size, cap)) return RequestRead.Bad(if (plain.size > cap) LinkProblem.TOO_LONG else LinkProblem.BAD_PADDING)
        return LinkParseWire.check(answer(plain, cap), plain, cap) ?: RequestRead.Bad(LinkProblem.PARSER)
    }

    fun answer(plain: ByteArray, cap: Int): ByteArray {
        var read = LinkRequests.decode(plain, cap)
        if (read is RequestRead.Unsupported && !LinkParseWire.unsupportedOk(plain, read.id, read.newer)) read = RequestRead.Bad(LinkProblem.NOT_CANONICAL)
        val ok = read as? RequestRead.Ok
        val offer = ok?.request?.body as? CardOffer
        if (ok != null && offer != null) {
            val card = LinkVCard.read(offer.vcard)
            read = if (card == null) RequestRead.Bad(LinkProblem.BAD_FIELD) else RequestRead.Ok(with(ok.request) { LinkRequest(id, at, ttl, lang, CardOffer(offer.vcard, card)) })
        }
        return LinkParseWire.encodeReply(read)
    }
}
