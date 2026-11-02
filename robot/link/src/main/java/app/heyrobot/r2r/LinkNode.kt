// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.Admit
import app.heyrobot.core.link.Backoff
import app.heyrobot.core.link.BlockedView
import app.heyrobot.core.link.CodeAttempt
import app.heyrobot.core.link.CodeState
import app.heyrobot.core.link.EnvelopeKind
import app.heyrobot.core.link.EnvelopeRead
import app.heyrobot.core.link.HelloPurpose
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCarrier
import app.heyrobot.core.link.LinkEnvelope
import app.heyrobot.core.link.LinkEvent
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.LinkLog
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPolicy
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.LinkText
import app.heyrobot.core.link.LinkWay
import app.heyrobot.core.link.MakeCodeResult
import app.heyrobot.core.link.OnionKey
import app.heyrobot.core.link.PairCode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairCodeRead
import app.heyrobot.core.link.PairHello
import app.heyrobot.core.link.PairHelloRead
import app.heyrobot.core.link.PairStep
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.PeerRate
import app.heyrobot.core.link.RateVerdict
import app.heyrobot.core.link.ReplayWindow
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.Verified
import app.heyrobot.core.link.requests.Ack
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.Caps
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkCaps
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.link.requests.ModeLower
import app.heyrobot.core.link.requests.ModeRaise
import app.heyrobot.core.link.requests.NetHint
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.link.requests.RequestType
import org.signal.libsignal.protocol.ecc.ECKeyPair
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2 to §3.7, §4.6; job L3): the node, what moves sealed
// envelopes between your Robot and the Robots it is paired with. One per :link process (LinkService and the push job start
// it); the two-peer harness runs two in one JVM.
//
// Everything that changes state happens on one thread (the link thread): libsignal's stores, the carriers' records, the
// outbox, the replay windows and rate limits. Network work (dialling, Tor, posting a push) runs on other threads and hands
// its result back. Nothing a peer sent, and no name, address, key or token, is ever logged: the link log gets codes.
//
// Coming in (any carrier): the envelope is read strictly (core LinkEnvelope); a pairing hello is opened (HelloSeal), checked
// against the code it names (one use, five wrong tries, 24 hours) and answered in a new session; a session message is
// decrypted (libsignal, the pinned identity only), read by the decoder (:linkparse, L4), checked against the replay window
// (§3.4) and the rate limits (§4.6), and then either handled here (pairing and control: ack, net.hint, caps, unpair, mode)
// or handed to the sink (L6), with an ack. Anything malformed is dropped and counted, never answered.
//
// Going out: requests wait in the outbox (sealed records) until a carrier takes them and their ack comes, tried again with
// L1's backoff (1 minute, doubling to 6 hours) until their lifetime ends (§3.4). Each try encrypts once and offers the
// envelope to the pairing's carriers in order (§2.2): Private: an open connection, direct on a shared network, push, Tor;
// Anonymous: an open connection, push through Tor, Tor. Before any of it: Robot to Robot is on (the caller), and Robot's
// network access is allowed (LinkEnv.networkAllowed, the Network toggle), checked before every send, listen or Tor start.
//
// 1.5 L5, pairing and People (§2.3, §2.6, §2.7, §6.2, §6.3): in person, both Robots photograph each other's code and the one
// whose identity key sorts first sends the hello; the other keeps the photographed key on its own shown code, and a hello
// to that code from any other key is refused ("This code doesn't match the Robot that answered"). A pairing made from a
// photographed code is Verified in person; one made at a distance is Not verified until "They match" (the safety number,
// libsignal's numeric fingerprint). Each pairing keeps who it is in your People, a preset and a level per capability, the
// mode change asked either way (lowering takes both: each side lowers once both sent `mode.lower`; "Keep Anonymous" is an
// ack DECLINED), and two moved-hello tokens given inside the session (net.hint's `moved`). Block keeps the identity key:
// its hellos are dropped unread, before they touch a code. Their `unpair` leaves an ENDED note (no keys, no routes) for 30
// days. A moved hello (purpose MOVED, carrying the token you gave) pauses the pairing until you accept it after comparing
// the new safety number (its key pinned again, a new session, new tokens) or keep the old phone.

/** What a send was refused for, as a code (the IPC's answer). */
enum class QueueProblem(val code: Int) {
    NO_PAIRING(1),
    /** The request isn't a padded request (a multiple of 512 bytes, at most 32 KiB) or its fields are wrong. */
    BAD_REQUEST(2),
    /** The other Robot didn't list this type in its caps (D22). */
    NOT_READ(3),
    /** The outbox is full (500 in all, 200 for one person). */
    FULL(4),
    /** The state couldn't be written. */
    STATE(5),
}

/** L5: what "Make a code" gave: its result, and when one was made the code and its id (for CODE_STATE). */
class MadeCode(val result: MakeCodeResult, val code: PairCode? = null, val id: String? = null) {
    override fun toString(): String = "MadeCode($result)" // l10n: not text (toString for logs and tests)
}

/** L5: what a code you scanned or pasted gave, and the pairing's id when the result has one. */
class UsedCode(val result: UseCodeResult, val pairing: String? = null) {
    override fun toString(): String = "UsedCode($result)" // l10n: not text (toString for logs and tests)
}

class LinkNode(
    private val stores: NodeKeys,
    private val st: LinkStateStore,
    private val env: LinkEnv,
    private val decoder: RequestDecoder,
    private val sink: LinkSink,
    private val direct: DirectNet?,
    private val tor: TorNet?,
    private val push: PushNet?,
    private val poster: PushPoster?,
    private val log: LinkLog,
    /** "Name other Robots see" (link_settings.json), sent in caps and pairing hellos. */
    private val myName: () -> String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "robot-link").apply { isDaemon = true } } // l10n: not text (a thread's name)
    private var parseWaiting = 0
    private val io: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "robot-link-io").apply { isDaemon = true } } // l10n: not text (a thread's name)
    /** Tor's starts, stops and publications, one at a time. */
    private val torThread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-link-tor").apply { isDaemon = true } } // l10n: not text (a thread's name)

    // The link thread's own state.
    private val conns = ArrayList<LiveConn>()
    private val rates = HashMap<String, PeerRate>()
    private val inFlight = HashSet<String>()
    private val waitingEndpoints = HashMap<String, CompletableFuture<Unit>>()
    /** Codes being made, by their push instance: their endpoint may come before they are stored. */
    private val making = HashMap<String, CodeRecord>()
    /** L5: what became of each code you made this run, once it is no longer open: used (with its pairing) or a mismatch. */
    private val codeOutcomes = HashMap<String, Pair<CodeState, String?>>()
    /** L5-fix: in person, hellos to each code you show that came before your photo of theirs (in memory only). */
    private val held = HashMap<String, MutableList<Held>>()

    private class Held(val hello: PairHello, val conn: LiveConn?)

    /** L5-fix: a cross-paste this side wins: its SENT pairing's id, and its own code the other Robot's hello came to. */
    private val crossed = HashMap<String, String>()
    private var awake = false
    private var listenPort = 0
    private var onionClients: Set<String>? = null
    private var stopped = false

    private fun nowMs() = env.now()
    private fun nowS() = env.now() / 1000

    // Lifecycle ---------------------------------------------------------------------------------------------------------

    /** Only the push job holds :link now; hints it held back wait in [hintsOwed]. Both on the node's thread. */
    private var quietWake = false
    private var hintsOwed = false

    /** Starts the node: the outbox's pump every 30 seconds, and what waits in the push spool. */
    fun start() {
        exec.scheduleWithFixedDelay({ guard { pumpNow(); sweep() } }, 0, PUMP_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * :link is in use (a client is bound, or the push job runs): listen on a shared network, and run Tor when needed.
     * [quiet]: the push job's wake. It sends no net.hint and registers nothing: a hint would wake the other phone, whose job
     * would hint back, and a failed registration would run a job that registers again. Both wait for a client's wake.
     */
    fun wake(quiet: Boolean = false) = post {
        awake = true
        quietWake = quiet
        if (!quiet) retryPush()
        refreshCarriers()
        if (!quiet && hintsOwed) {
            hintsOwed = false
            for (p in st.pairings.values) if (p.state == PairingState.ACTIVE) sendHint(p, rotate = false)
        }
    }

    /** Your routes changed while awake: told now, or at the next client's wake while only the push job holds :link. */
    private fun wakeHint(p: PairingRecord) {
        if (quietWake) hintsOwed = true else sendHint(p, rotate = false)
    }

    /** Nothing needs :link now: the direct listener stops, Tor stops, open connections close. */
    fun sleep() = post {
        awake = false
        direct?.stopListening(); listenPort = 0
        conns.toList().forEach { it.close() }; conns.clear()
        val t = tor
        if (t != null) torThread.execute { try { t.down() } catch (_: Exception) {} }
        onionClients = null
    }

    /** L5-fix: closed for good (Robot to Robot turned off, or Start over): nothing of it runs again. */
    @Volatile var isClosed = false
        private set

    fun close() {
        isClosed = true
        sleep()
        post { stopped = true }
        exec.shutdown()
        try { exec.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        io.shutdownNow(); torThread.shutdownNow()
    }

    /** Runs the outbox now (the IPC's nudge, and the tests' injected clock). */
    fun pump(): CompletableFuture<Unit> = call { pumpNow(); sweep() }

    /** Waits until everything posted before this ran (tests). */
    fun settle(): CompletableFuture<Unit> = call { }

    // Sending -----------------------------------------------------------------------------------------------------------

    /**
     * A request from :voice for [pairing]: [bytes] as core LinkRequests.encode wrote them (padded), [type]'s code, its
     * [id], `at` (Unix seconds) and `ttl`, given beside it so :link never reads the JSON. Null when queued; else why not.
     * L5-fix: requests only. Pairing and control types (ack, net.hint, caps, unpair, the mode changes) are :link's own and
     * refused here, and the bytes must start with the canonical header for exactly these fields (a byte compare).
     */
    fun queue(pairing: String, type: Int, id: String, at: Long, ttl: Long, bytes: ByteArray): CompletableFuture<QueueProblem?> = call {
        val p = st.pairings[pairing]?.takeIf { it.state == PairingState.ACTIVE } ?: return@call QueueProblem.NO_PAIRING
        val t = RequestType.ofCode(type)
        if (t == null || t.control || !LinkIds.isId(id) || ttl !in LinkRequests.MIN_TTL..LinkRequests.MAX_TTL || at <= 0 ||
            bytes.isEmpty() || bytes.size % LinkRequests.PAD != 0 || bytes.size > LinkRequests.LIVE || st.outbox.containsKey(id) ||
            !LinkRequests.headerOk(bytes, id, at, ttl, t)) return@call QueueProblem.BAD_REQUEST
        if (t !in LinkCaps.fromBitmap(p.caps)) return@call QueueProblem.NOT_READ
        enqueue(p, t, id, at, ttl, bytes).also { if (it == null) sink.queued(p.id, t, id, at) }
    }

    private fun enqueue(p: PairingRecord, t: RequestType, id: String, at: Long, ttl: Long, bytes: ByteArray): QueueProblem? {
        val forPerson = st.outbox.values.count { it.pairing == p.id }
        if (!LinkLimits.canQueue(st.outbox.size, forPerson)) return QueueProblem.FULL
        val o = OutboxItem(id, p.id, t.code, at, ttl, bytes).also { it.nextAt = nowS() }
        if (!stateful { st.put(o) }) return QueueProblem.STATE
        exec.execute { guard { pumpNow() } }
        return null
    }

    /** A control request of this node's own (ack, net.hint, caps): encoded here (nothing of a peer's is ever parsed here). */
    /** L16: the answer to a request nothing was done for, within its budget: past it the sender just retries or expires. */
    private fun refusal(p: PairingRecord, id: String, state: AckState) {
        if (st.outbox.values.count { it.pairing == p.id && it.type == RequestType.ACK.code } >= LinkLimits.REFUSALS_QUEUED) return
        queueBody(p, Ack(id, state))
    }

    /** F74: an answer to a request, within [LinkLimits.ACKS_QUEUED]: the newest replaces the oldest not on its way; the sender asks again. */
    private fun answer(p: PairingRecord, id: String, state: AckState): String? {
        val acks = st.outbox.values.filter { it.pairing == p.id && it.type == RequestType.ACK.code }
        if (acks.size >= LinkLimits.ACKS_QUEUED) {
            val oldest = acks.filter { it.id !in inFlight }.minByOrNull { it.at } ?: return null
            stateful { st.deleteOutbox(oldest.id) }
        }
        return queueBody(p, Ack(id, state))
    }

    private fun queueBody(p: PairingRecord, body: LinkBody, ttl: Long = CONTROL_TTL): String? {
        val r = LinkRequest.of(body, nowS(), LANG, ttl)
        val bytes = LinkRequests.encode(r, LinkRequests.LIVE) ?: return null
        return if (enqueue(p, body.type, r.id, r.at, r.ttl, bytes) == null) r.id else null
    }

    private fun pumpNow() {
        if (stopped) return
        // What went online's hourly row for Tor (E4): off this thread, as it asks Tor's control socket.
        tor?.let { t -> io.execute { t.tally() } }
        // Robot's network access off (the Network toggle): nothing is tried, nothing counted; the next pump tries again.
        if (!env.networkAllowed()) {
            if (st.outbox.isNotEmpty()) log.add(LinkEvent.NO_NETWORK)
            return
        }
        val now = nowS()
        for (o in st.outbox.values.sortedBy { it.nextAt }.toList()) {
            if (o.id in inFlight || o.nextAt > now) continue
            if (now > o.expiresAt) {
                stateful { st.deleteOutbox(o.id) }
                log.add(LinkEvent.EXPIRED, o.type, 0)
                // L5-fix: your ask to switch to Private never reached them in its 30 days: you can ask again.
                st.pairings[o.pairing]?.takeIf { it.lowerAsked == o.id }?.let { q -> q.lowerAsked = null; stateful { st.put(q) } }
                if (RequestType.ofCode(o.type)?.answeredByAck == true) sink.gaveUp(o.pairing, o.id)
                continue
            }
            attempt(o)
        }
        for (p in st.pairings.values.filter { (it.state == PairingState.SENT || it.state == PairingState.MOVING) && it.hello != null && it.helloNext <= now }) sendHello(p)
    }

    private fun attempt(o: OutboxItem) {
        val p = st.pairings[o.pairing] ?: run { stateful { st.deleteOutbox(o.id) }; return }
        // Paused (a moved hello waits for your answer, L5): it waits, untried, until the pairing is active again.
        if (p.state != PairingState.ACTIVE) return
        if (!env.networkAllowed()) return
        // No carrier for it now: nothing encrypted (each try spends a step of the ratchet), tried again later.
        if (routesFor(p, o.size, false).isEmpty()) { retry(o); return }
        val sb = try { stores.encrypt(p.id, o.bytes, nowMs()) } catch (x: SessionException) {
            log.add(LinkEvent.SEND_FAILED, 0, 100 + x.problem.code)
            retry(o)
            return
        }
        val kind = if (sb.first) EnvelopeKind.FIRST else EnvelopeKind.SESSION
        val routes = routesFor(p, o.size, sb.first)
        if (routes.isEmpty()) { retry(o); return }
        inFlight += o.id
        val body = sb.bytes
        val mode = p.mode
        val peerPush = p.peerPush
        io.execute {
            var way: LinkWay? = null
            var problem = 0
            for (r in routes) {
                try {
                    // L5-fix: on a connection that hasn't proven itself, it goes by the next carrier too.
                    if (!deliver(r, p.id, kind, body, mode, peerPush, o.ttl)) continue
                    way = r.way
                    break
                } catch (x: Exception) {
                    problem = problemCode(x)
                    log.add(LinkEvent.SEND_FAILED, r.way.code, problem)
                    if (x is PushException && x.problem == PushProblem.GONE) post { st.pairings[p.id]?.let { q -> if (q.peerPush === peerPush) { q.peerPush = null; stateful { st.put(q) } } } }
                }
            }
            post {
                inFlight -= o.id
                val cur = st.outbox[o.id] ?: return@post
                if (way != null) {
                    log.add(LinkEvent.REQUEST_OUT, o.type, way.code)
                    val acked = RequestType.ofCode(o.type)?.answeredByAck == true
                    if (!acked) {
                        stateful { st.deleteOutbox(o.id) }
                    } else {
                        cur.sent = true
                        cur.attempts++
                        cur.nextAt = nowS() + maxOf(ACK_WAIT_SECONDS, Backoff.delay(minOf(cur.attempts, 20)))
                        stateful { st.put(cur) }
                    }
                } else {
                    retry(cur)
                }
            }
        }
    }

    private fun retry(o: OutboxItem) {
        val next = Backoff.next(minOf(o.attempts, 20), nowS(), o.expiresAt)
        o.attempts++
        o.nextAt = next ?: (o.expiresAt + 1)
        stateful { st.put(o) }
    }

    private class Route(val way: LinkWay, val conn: LiveConn? = null)

    /**
     * The pairing's carriers for one try, in order (§2.2). L5-fix: an open connection that proved itself first; else one that
     * hasn't yet (what goes on it doesn't count, so the next carrier follows); a new direct connection only when none of
     * yours to it is open.
     */
    private fun routesFor(p: PairingRecord, size: Int, first: Boolean): List<Route> {
        val out = ArrayList<Route>()
        val open = conns.filter { it.pairing == p.id && !it.closed && (it.way != LinkWay.DIRECT || Ways.directAllowed(p.mode)) }
        (open.firstOrNull { it.proven } ?: open.firstOrNull())?.let { out += Route(it.way, it) }
        val token = p.outToken
        // L5-fix: dialled only while listening, so only from Wi-Fi, Ethernet or a VPN (never over the mobile network).
        if (Ways.directAllowed(p.mode) && direct != null && awake && listenPort != 0 && token != null && p.peerAddrs.isNotEmpty() &&
            open.none { it.outbound && it.way == LinkWay.DIRECT }) out += Route(LinkWay.DIRECT)
        val pushCap = if (first) LinkRequests.PUSH_FIRST else LinkRequests.PUSH
        // L5-fix: Tor only while :link is awake (a client bound, or the push job): a pump while it sleeps never starts it.
        val torOk = tor != null && awake
        val viaTor = Ways.pushViaTor(p.mode)
        if (p.peerPush != null && poster != null && size <= pushCap && (!viaTor || torOk)) out += Route(if (viaTor) LinkWay.PUSH_TOR else LinkWay.PUSH)
        if (torOk && p.peerOnion != null && p.peerOnionAuth != null && token != null) out += Route(LinkWay.TOR)
        return out
    }

    /**
     * One envelope by one carrier (on an io thread). Throws when this carrier couldn't take it. L5-fix: true when it counts as
     * delivered (the push server took it, or a connection that proved itself did); false when it went on a connection that
     * hasn't, which anyone who saw a token could have opened.
     */
    private fun deliver(r: Route, pairing: String, kind: EnvelopeKind, body: ByteArray, mode: LinkMode, peerPush: app.heyrobot.core.link.PushRoute?, ttl: Long): Boolean {
        return when (r.way) {
            LinkWay.PUSH, LinkWay.PUSH_TOR -> {
                val route = peerPush ?: throw java.io.IOException()
                val env = LinkEnvelope(kind, LinkEnvelope.NO_ROUTE, body).encode(LinkCarrier.PUSH)
                if (r.way == LinkWay.PUSH_TOR && tor?.up() != true) throw TorException(TorProblem.NOT_READY)
                poster!!.post(route, WebPush.seal(route, env), minOf(ttl, PushHttp.MAX_TTL), r.way == LinkWay.PUSH_TOR, pairing)
                true
            }
            else -> {
                val c = r.conn ?: dialPairing(r.way, pairing)
                c.send(LinkEnvelope(kind, c.route, body))
                c.proven
            }
        }
    }

    /** A new live connection to [pairing] by [way], opened with its connection hello (on an io thread). */
    private fun dialPairing(way: LinkWay, pairing: String): LiveConn {
        val snap = call { st.pairings[pairing]?.let { Triple(it.outToken, it.peerAddrs, Pair(it.peerOnion, it.peerOnionAuth)) } }.get(10, TimeUnit.SECONDS)
            ?: throw java.io.IOException()
        val token = snap.first ?: throw java.io.IOException()
        if (!call { LinkLimits.canConnect(conns.count { !it.closed && (it.outbound || it.pairing != null) }) }.get(10, TimeUnit.SECONDS)) throw java.io.IOException()
        val d: Duplex = when (way) {
            LinkWay.DIRECT -> {
                var got: Duplex? = null
                for (a in snap.second) { got = try { direct!!.dial(a) } catch (_: Exception) { null }; if (got != null) break }
                got ?: throw java.io.IOException()
            }
            LinkWay.TOR -> {
                val t = tor ?: throw java.io.IOException()
                if (!t.up()) throw TorException(TorProblem.NOT_READY)
                val (onion, auth) = snap.third
                t.clientAuth(onion ?: throw java.io.IOException(), auth ?: throw java.io.IOException())
                t.dial(onion, pairing)
            }
            else -> throw java.io.IOException()
        }
        val c = LiveConn(d, way, true, nowMs())
        c.pairing = pairing
        c.route = token.copyOf()
        // L5-fix: Tor reached the onion that Robot gave inside the session (its key checked); a direct address anyone on the
        // network could answer, so that connection proves itself first.
        c.proven = way == LinkWay.TOR
        c.readTimeout(IDLE_MS.toInt())
        try {
            c.send(LiveFrames.connectHello(token))
        } catch (x: Exception) {
            c.close(); throw x
        }
        call { conns += c; log.add(LinkEvent.CONNECT, way.code, 1) }.get(10, TimeUnit.SECONDS)
        c.startReading(::onFrame)
        return c
    }

    // Live connections coming in -----------------------------------------------------------------------------------------

    /** A connection accepted by a listener: direct, your onion, or a code's one-time onion ([code]). */
    fun accepted(d: Duplex, way: LinkWay, code: String? = null) {
        val c = LiveConn(d, way, false, nowMs())
        c.helloCode = code
        post {
            // L5-fix: those still waiting for a hello have their own cap, so silent ones never stop bound or dialled ones.
            if (!env.networkAllowed()) { c.close(); return@post }
            // F75: the oldest silent one goes, so a few that say nothing can't turn away a paired Robot's.
            val silent = conns.filter { !it.closed && !it.outbound && it.pairing == null }
            if (!LinkLimits.canAccept(silent.size)) silent.first().let { it.close(); conns -= it }
            // A hello: to a code's one-time onion; on this network to an open in-person code; or on your onion, which only
            // paired Robots reach (client authorization), a moved hello (L5; onHello refuses any other there).
            c.helloAllowed = code != null || way == LinkWay.TOR || (way == LinkWay.DIRECT && st.codes.values.any { it.inPerson && it.local != null && it.open.open(nowS()) })
            conns += c
            log.add(LinkEvent.CONNECT, way.code, 0)
            d.readTimeout((LinkLimits.CONNECT_HELLO_SECONDS * 1000).toInt())
            c.startReading(::onFrame)
            exec.schedule({ guard { if (c.pairing == null) { c.close(); conns -= c } } }, LinkLimits.CONNECT_HELLO_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun onFrame(c: LiveConn, f: Frame) {
        // L8: on the connection's own thread, so a slow disk slows the sender instead of filling memory with chunks.
        if (f is Frame.Got && f.envelope.kind == EnvelopeKind.CHUNK) return onChunk(c, f.envelope)
        // F70: the same for every other frame: past a few waiting for this thread, the reader stops and so does the sender.
        val got = f is Frame.Got
        if (got && !c.takeTurn()) return
        post { try { onFrameHere(c, f) } finally { if (got) c.turnDone() } }
    }

    /** F69: at most [LinkLimits.LIVE_PER_PAIRING] coming in for a pairing, with room for one more: those unproven go first, then the oldest. What you dial is yours and stays. */
    private fun roomForOneMore(pairing: String) {
        val open = conns.filter { !it.closed && !it.outbound && it.pairing == pairing }
        val over = open.size - (LinkLimits.LIVE_PER_PAIRING - 1)
        if (over > 0) open.sortedWith(compareBy({ it.proven }, { it.opened })).take(over).forEach { it.close(); conns -= it }
    }

    private fun onChunk(c: LiveConn, e: LinkEnvelope) {
        c.lastAt = nowMs()
        val p = c.pairing
        val keep = p != null && LinkBytes.same(e.route, c.route) && transfers?.chunk(c, p, e.body) == true
        if (!keep) post { drop(c, LinkProblem.BAD_KIND) }
    }

    private fun onFrameHere(c: LiveConn, f: Frame) {
        // F69: a session message counts as use only once it decrypts (onSession): garbage doesn't keep a connection open.
        if (!(f is Frame.Got && (f.envelope.kind == EnvelopeKind.FIRST || f.envelope.kind == EnvelopeKind.SESSION))) c.lastAt = nowMs()
        when (f) {
            is Frame.Closed -> { conns -= c; log.add(LinkEvent.DISCONNECT, c.way.code, 0) }
            is Frame.Bad -> { log.add(LinkEvent.DROPPED, c.way.code, f.problem.code); c.close(); conns -= c }
            is Frame.Newer -> { log.add(LinkEvent.DROPPED, c.way.code, LinkProblem.BAD_VERSION.code); c.close(); conns -= c }
            is Frame.Got -> onLive(c, f.envelope)
        }
    }

    private fun onLive(c: LiveConn, e: LinkEnvelope) {
        val bound = c.pairing
        when (e.kind) {
            EnvelopeKind.CONNECT -> {
                // L5-fix: a code's one-time onion takes a pairing hello and nothing else.
                if (bound != null || c.outbound || c.helloCode != null) return drop(c, LinkProblem.BAD_KIND)
                val token = e.route
                val p = st.pairings.values.firstOrNull { q ->
                    (q.inToken?.let { LinkBytes.same(it, token) } == true) || (q.inPrev?.let { LinkBytes.same(it, token) } == true)
                } ?: return drop(c, LinkProblem.BAD_ROUTE)
                if (c.way == LinkWay.DIRECT && !Ways.directAllowed(p.mode)) return drop(c, LinkProblem.NOT_HERE)
                roomForOneMore(p.id)
                if (!LinkLimits.canConnect(conns.count { !it.closed && (it.outbound || it.pairing != null) })) return drop(c, LinkProblem.NOT_HERE)
                c.pairing = p.id
                c.route = token
                c.helloAllowed = false
                c.readTimeout(IDLE_MS.toInt())
                // L5-fix: bound, not proven: the next token is given once a session message on it decrypts (onSession), so a
                // token seen on the network can't make this Robot rotate.
                c.newest = p.inToken?.let { LinkBytes.same(it, token) } == true
                exec.execute { guard { pumpNow() } }
            }
            EnvelopeKind.PAIR_HELLO -> {
                if (bound != null || c.outbound || !c.helloAllowed) return drop(c, LinkProblem.BAD_KIND)
                onHello(e.body, c.helloCode, c)
            }
            EnvelopeKind.FIRST, EnvelopeKind.SESSION -> {
                val p = bound?.let { st.pairings[it] } ?: return drop(c, LinkProblem.BAD_ROUTE)
                if (!LinkBytes.same(e.route, c.route)) return drop(c, LinkProblem.BAD_ROUTE)
                onSession(p, SessionBytes(e.kind == EnvelopeKind.FIRST, e.body), c.way, c)
            }
            EnvelopeKind.CHUNK -> drop(c, LinkProblem.BAD_KIND) // never here: onFrame takes them
        }
    }

    private fun drop(c: LiveConn, why: LinkProblem) {
        log.add(LinkEvent.DROPPED, c.way.code, why.code)
        c.close()
        conns -= c
    }

    // Push coming in ------------------------------------------------------------------------------------------------------

    /** An event from your distributor (the push job hands it here). */
    fun onPush(e: PushEvent): CompletableFuture<Unit> = call {
        when (e.kind) {
            PushEvent.Kind.MESSAGE -> onPushMessage(e.instance, e.bytes ?: return@call)
            PushEvent.Kind.ENDPOINT -> onEndpoint(e.instance, e.route ?: return@call)
            PushEvent.Kind.UNREGISTERED, PushEvent.Kind.FAILED -> {
                log.add(LinkEvent.PUSH, 3, e.kind.code)
                pushLost(e.instance)
            }
            PushEvent.Kind.REFUSED -> log.add(LinkEvent.PUSH, 4, 0)
        }
    }

    private fun onPushMessage(instance: String, bytes: ByteArray) {
        val code = st.codes.values.firstOrNull { it.myInstance == instance }
        val p = st.pairings.values.firstOrNull { it.myInstance == instance }
        if (code == null && p == null) { log.add(LinkEvent.DROPPED, LinkWay.PUSH.code, LinkProblem.NOT_HERE.code); return }
        val e = when (val r = LinkEnvelope.decode(bytes, LinkCarrier.PUSH)) {
            is EnvelopeRead.Ok -> r.envelope
            is EnvelopeRead.Newer -> { log.add(LinkEvent.DROPPED, LinkWay.PUSH.code, LinkProblem.BAD_VERSION.code); return }
            is EnvelopeRead.Bad -> { log.add(LinkEvent.DROPPED, LinkWay.PUSH.code, r.problem.code); return }
        }
        when {
            e.kind == EnvelopeKind.PAIR_HELLO && code != null -> onHello(e.body, code.id, null)
            // L5: on a pairing's own push address, only a moved hello from that pairing's Robot.
            e.kind == EnvelopeKind.PAIR_HELLO && p != null -> onHello(e.body, null, null, p)
            (e.kind == EnvelopeKind.FIRST || e.kind == EnvelopeKind.SESSION) && p != null -> onSession(p, SessionBytes(e.kind == EnvelopeKind.FIRST, e.body), LinkWay.PUSH)
            else -> log.add(LinkEvent.DROPPED, LinkWay.PUSH.code, LinkProblem.BAD_KIND.code)
        }
    }

    /**
     * [instance]'s address is gone. A pairing's registers again at the next client's wake ([retryPush]), and the Push app
     * row counts it until then.
     */
    private fun pushLost(instance: String) {
        st.pairings.values.firstOrNull { it.myInstance == instance }?.let { p -> p.myInstance = null; p.myPush = null; stateful { st.put(p) } }
        st.codes.values.firstOrNull { it.myInstance == instance }?.let { c -> c.myPush = null; stateful { st.put(c) } }
        waitingEndpoints.remove(instance)?.complete(Unit)
    }

    /** Every active pairing without a push address asks your push app for one; on your network switch, and a client's wake. */
    private fun retryPush() {
        val n = push ?: return
        if (!env.networkAllowed() || !n.available()) return
        for (p in st.pairings.values) if (p.state == PairingState.ACTIVE && p.myPush == null) {
            val i = p.myInstance
            if (i == null) { newPushInstance(p); stateful { st.put(p) } } else io.execute { n.register(i) }
        }
    }

    /**
     * F27, your tap in the Push app row: what the old app holds is dropped, [save] stores your choice, and when [use] says
     * there is one, each active pairing registers with it (its address reaches them in a net.hint).
     */
    fun choosePush(use: Boolean, save: () -> Boolean): CompletableFuture<Boolean> {
        val out = CompletableFuture<Boolean>()
        post {
            val n = push
            if (n == null) { out.complete(false); return@post }
            val old = st.pairings.values.mapNotNull { it.myInstance } + st.codes.values.mapNotNull { it.myInstance }
            for (p in st.pairings.values) if (p.myInstance != null || p.myPush != null) { p.myInstance = null; p.myPush = null; stateful { st.put(p) } }
            for (c in st.codes.values) if (c.myInstance != null || c.myPush != null) { c.myInstance = null; c.myPush = null; stateful { st.put(c) } }
            io.execute {
                old.forEach { n.unregister(it) }
                val ok = try { save() } catch (_: Exception) { false }
                post { if (ok && use) retryPush(); out.complete(ok) }
            }
        }
        return out
    }

    /** Active pairings with no push address, for the Push app row. */
    fun pushWaiting(): CompletableFuture<Int> = call { st.pairings.values.count { it.state == PairingState.ACTIVE && it.myPush == null } }

    private fun onEndpoint(instance: String, route: app.heyrobot.core.link.PushRoute) {
        // Another Robot refuses an address that isn't out on the internet, and writing one in a net.hint or a code would
        // throw: an app that gives one counts as having given none.
        if (!app.heyrobot.core.link.PushRoute.peerEndpointOk(route.endpoint)) {
            log.add(LinkEvent.PUSH, 3, PushEvent.Kind.FAILED.code)
            pushLost(instance)
            push?.let { n -> io.execute { n.unregister(instance) } }
            return
        }
        log.add(LinkEvent.PUSH, 2, 0)
        st.pairings.values.firstOrNull { it.myInstance == instance }?.let { p ->
            p.myPush = route
            stateful { st.put(p) }
            if (p.state == PairingState.ACTIVE) sendHint(p, rotate = false)
        }
        st.codes.values.firstOrNull { it.myInstance == instance }?.let { c -> c.myPush = route; stateful { st.put(c) } }
        making[instance]?.let { c -> c.myPush = route }
        waitingEndpoints.remove(instance)?.complete(Unit)
    }

    // Sessions coming in --------------------------------------------------------------------------------------------------

    /** A session message for [p] by [way]; [c], the live connection it came on. */
    private fun onSession(p: PairingRecord, sb: SessionBytes, way: LinkWay, c: LiveConn? = null) {
        // Paused (L5): nothing is read while a moved hello waits for your answer, nor from an ENDED note; the other Robot
        // sends it again later (nothing was decrypted, so no step of the ratchet is lost).
        if (p.state == PairingState.MOVED || p.state == PairingState.ENDED) { log.add(LinkEvent.DROPPED, way.code, LinkProblem.NOT_HERE.code); return }
        val plain = try { stores.decrypt(p.id, sb) } catch (x: SessionException) {
            log.add(LinkEvent.UNDECRYPTED, way.code, x.problem.code)
            // L5-fix: a connection that hasn't proven itself and carries what doesn't decrypt goes (a copy that came twice,
            // once by another carrier, is no reason).
            if (c != null && !c.proven && x.problem != SessionProblem.DUPLICATE) { c.close(); conns -= c }
            return
        }
        c?.lastAt = nowMs()
        if (c != null && !c.proven) {
            c.proven = true
            // Bound by the newest token you gave: the next one now (§3.2), and only now that the connection is theirs.
            if (c.newest && p.state == PairingState.ACTIVE && p.inToken?.let { LinkBytes.same(it, c.route) } == true) { c.newest = false; sendHint(p, rotate = true) }
            // L8: a stalled file asks again on it, once the request that proved it has been read below.
            exec.execute { guard { transfers?.linkUp(p.id) } }
        }
        if (p.state == PairingState.SENT || p.state == PairingState.MOVING) {
            // The maker's answer (or, after a move, the answer of the Robot that accepted it): the session works both ways now.
            val moving = p.state == PairingState.MOVING
            p.state = PairingState.ACTIVE
            p.code = null; p.hello = null; p.since = 0
            // L5: the code was photographed in person, and only the key in it can answer in this session (pinned).
            if (p.scanned && p.verified == Verified.NO) { p.verified = Verified.IN_PERSON; p.verifiedAt = nowS(); log.add(LinkEvent.PAIR, PairStep.VERIFIED_IN_PERSON.code) }
            p.scanned = false
            if (moving) p.movedIn = null // a new one goes in the hint below: replaced on each accepted move
            stateful { st.put(p) }
            // L5-fix: a cross-paste this side won: its own code, which the other Robot pasted, is used by this pairing too.
            crossed.remove(p.id)?.let { id -> st.codes[id]?.let { k -> codeOutcomes[k.id] = CodeState.USED to p.id; dropCode(k) } }
            log.add(LinkEvent.HELLO, 2, 0)
            sink.paired(p.id)
            sendHint(p, rotate = true)
        }
        val cap = when (way) {
            LinkWay.PUSH, LinkWay.PUSH_TOR -> if (sb.first) LinkRequests.PUSH_FIRST else LinkRequests.PUSH
            else -> LinkRequests.LIVE
        }
        readRequest(p.id, plain, cap, way, 0)
    }

    /**
     * L16: :linkparse couldn't be reached: nothing read, nothing answered. The session has moved on and an `unpair` is never
     * sent twice, so the plaintext waits in memory for a few tries; only then is it lost. The other Robot's retry, if any, is
     * the replay window's to catch.
     */
    private fun readRequest(pairing: String, plain: ByteArray, cap: Int, way: LinkWay, tries: Int) {
        val p = st.pairings[pairing] ?: return
        val read = try { decoder.decode(plain, cap) } catch (_: Exception) { null }
        if (read != null) return onRequest(p, read, way)
        log.add(LinkEvent.DROPPED, way.code, LinkProblem.NOT_HERE.code)
        if (tries >= PARSE_TRIES || parseWaiting >= PARSE_WAITING) return
        parseWaiting++
        exec.schedule({ guard { parseWaiting--; if (!stopped) readRequest(pairing, plain, cap, way, tries + 1) } }, PARSE_RETRY_S shl tries, TimeUnit.SECONDS)
    }

    private fun onRequest(p: PairingRecord, read: RequestRead, way: LinkWay) {
        val r = when (read) {
            is RequestRead.Bad -> { log.add(LinkEvent.DROPPED, way.code, read.problem.code); return }
            is RequestRead.Unsupported -> {
                log.add(LinkEvent.REQUEST_REFUSED, 0, AckState.UNSUPPORTED.ordinal + 1)
                refusal(p, read.id, AckState.UNSUPPORTED)
                return
            }
            is RequestRead.Ok -> read.request
        }
        val t = r.type
        // L16: a type with a name but not built (splits until L9) gets the same answer as one this build has never heard of.
        if (t !in LinkCaps.SUPPORTED) {
            log.add(LinkEvent.REQUEST_REFUSED, t.code, AckState.UNSUPPORTED.ordinal + 1)
            refusal(p, r.id, AckState.UNSUPPORTED)
            return
        }
        val window = st.replay.getOrPut(p.id) { ReplayWindow() }
        when (window.admit(r.id, r.at, r.ttl, nowS())) {
            Admit.REPEAT -> { if (t.answeredByAck) refusal(p, r.id, AckState.RECEIVED); return }
            Admit.FUTURE, Admit.EXPIRED -> {
                log.add(LinkEvent.REQUEST_REFUSED, t.code, AckState.EXPIRED.ordinal + 1)
                if (t.answeredByAck) refusal(p, r.id, AckState.EXPIRED)
                return
            }
            Admit.ACCEPTED -> {}
        }
        val ops = (r.body as? LsOp)?.ops?.size ?: 0
        val rate = rates.getOrPut(p.id) { PeerRate().also { r -> st.listRate[p.id]?.let { r.restoreListOps(it, nowS()) } } }
        val verdict = rate.admit(t, nowS(), ops)
        if (ops > 0 && verdict !is RateVerdict.Busy) stateful { st.putListRate(p.id, rate.savedListOps(nowS())) }
        if (verdict is RateVerdict.Busy) {
            // L5-fix: not handled, so not seen: the same request sent again after the pause is let in.
            window.forget(r.id)
            log.add(LinkEvent.REQUEST_REFUSED, t.code, AckState.BUSY.ordinal + 1)
            if (t.answeredByAck) refusal(p, r.id, AckState.BUSY)
            if (verdict.notify) sink.busy(p.id, verdict.until)
            stateful { st.putReplay(p.id, window) }
            return
        }
        log.add(LinkEvent.REQUEST_IN, t.code, way.code)
        var ack: AckState? = AckState.RECEIVED
        when (val b = r.body) {
            is Ack -> {
                ack = null
                log.add(LinkEvent.ACK_IN, b.state.ordinal, 0)
                st.outbox[b.re]?.takeIf { it.pairing == p.id }?.let { o ->
                    if (b.state == AckState.BUSY || b.state == AckState.EXPIRED) {
                        // L5-fix: refused for now (too many from you this hour, or their clock): kept, and sent again after
                        // at least an hour, until its own lifetime ends.
                        o.nextAt = maxOf(o.nextAt, nowS() + REFUSED_WAIT_SECONDS)
                        stateful { st.put(o) }
                    } else {
                        stateful { st.deleteOutbox(o.id) }
                    }
                }
                if (b.re == p.lowerAsked) answeredLower(p, b.state)
                sink.acked(p.id, b.re, b.state)
            }
            is NetHint -> {
                // L5-fix: the stricter mode wins. Theirs says Anonymous (and not that a switch to Private is being agreed):
                // Anonymous here too. Its addresses only while Private; the rest of it (its token) always (no DECLINED).
                if (b.mode == LinkMode.ANONYMOUS && p.mode == LinkMode.PRIVATE) {
                    toAnonymous(p)
                    log.add(LinkEvent.PAIR, PairStep.RAISED_BY_THEM.code)
                    sink.raised(p.id)
                }
                applyHint(p, b)
                // Theirs says Private while you hold it to Anonymous: your hint now, so theirs is raised without waiting for
                // the next one (only ever this way round, so two hints never answer each other).
                if (b.mode == LinkMode.PRIVATE && holdsTo(p) == LinkMode.ANONYMOUS) sendHint(p, rotate = false, force = true)
            }
            is Caps -> {
                p.caps = LinkCaps.bitmap(b.known)
                if (b.name.isNotEmpty()) p.name = b.name
                stateful { st.put(p) }
            }
            is app.heyrobot.core.link.requests.Unpair -> {
                ack = null
                // L5: "Sam's Robot unpaired" stays as a note (ENDED), with nothing of the pairing but who it was.
                end(p)
                sink.unpaired(p.id)
                return
            }
            is ModeRaise -> {
                val was = p.mode
                toAnonymous(p)
                stateful { st.put(p) }
                ack = AckState.HANDLED
                // Shown once: a raise that arrives after a hint already raised it (or sent again) changes nothing.
                if (was == LinkMode.PRIVATE) {
                    log.add(LinkEvent.PAIR, PairStep.RAISED_BY_THEM.code)
                    sink.raised(p.id)
                }
            }
            is ModeLower -> {
                when {
                    p.mode == LinkMode.PRIVATE -> ack = AckState.HANDLED
                    // You asked too: both agree, so it's Private on this side now (theirs lowered when it got yours).
                    p.lowerAsked != null -> {
                        toPrivate(p)
                        ack = AckState.HANDLED
                    }
                    else -> {
                        p.lowerOffer = r.id
                        stateful { st.put(p) }
                        ack = AckState.WAITING
                        log.add(LinkEvent.PAIR, PairStep.LOWER_ASKED_BY_THEM.code)
                        sink.lowerAsked(p.id)
                    }
                }
            }
            else -> {
                // L6: the desk's answer (a card waits, Robot answered it, Off declined it). Busy: nothing could take it now, so
                // it isn't seen and comes again after the pause.
                ack = sink.delivered(p.id, r)
                if (ack == AckState.BUSY) window.forget(r.id)
            }
        }
        // Written after it was handed on: a restart in between may hand it on twice (the sink keeps one per id), never lose it.
        stateful { st.putReplay(p.id, window) }
        if (ack != null && t.answeredByAck) answer(p, r.id, ack)
    }

    private fun applyHint(p: PairingRecord, h: NetHint) {
        h.push?.let { p.peerPush = it }
        if (h.onion != null && h.onionAuth != null) {
            p.peerOnion = h.onion; p.peerOnionAuth = h.onionAuth
            val t = tor
            if (t != null && t.running) { val o = h.onion!!; val a = h.onionAuth!!; torThread.execute { try { t.clientAuth(o, a) } catch (_: Exception) {} } }
        }
        LinkPolicy.hintAddrs(h, p.mode).takeIf { it.isNotEmpty() }?.let { p.peerAddrs = it; p.addrsAt = nowS() }
        h.token?.let { p.outToken = LinkBytes.unhex(it, LinkIds.BYTES) }
        // L5: what a moved hello from you to them must carry, should you move to a new phone (§2.7).
        h.moved?.let { p.movedOut = LinkBytes.unhex(it, LinkIds.BYTES) }
        stateful { st.put(p) }
        exec.execute { guard { pumpNow() } }
    }

    /**
     * Your routes for [p], in one net.hint: a new connection token when [rotate], your push address, your onion, your
     * addresses; L5-fix: and the mode you hold it to, sent even with nothing new when [force].
     */
    private fun sendHint(p: PairingRecord, rotate: Boolean, force: Boolean = false) {
        if (p.state != PairingState.ACTIVE) return
        // L5: your moved-hello token for them, made once per pairing (and again after each move you accept), in every hint.
        val freshMoved = p.movedIn == null
        if (freshMoved) p.movedIn = newToken()
        var token: String? = null
        if (rotate || p.inToken == null) {
            val t = newToken()
            p.inPrev = p.inToken
            p.inToken = t
            token = LinkBytes.hex(t)
        }
        val onion = st.tor?.onion?.takeIf { p.myAuthPub != null && onionClients?.contains(LinkBytes.hex(p.myAuthPub!!)) == true }
        val addrs = if (Ways.directAllowed(p.mode) && listenPort != 0) direct?.localAddresses(listenPort).orEmpty() else emptyList()
        if (token == null && p.myPush == null && onion == null && addrs.isEmpty() && !freshMoved && !force) return
        p.hintAt = nowS()
        stateful { st.put(p) }
        queueBody(p, NetHint(holdsTo(p), p.myPush, onion, addrs, token, if (onion != null) p.myAuthPriv else null, LinkBytes.hex(p.movedIn!!)))
    }

    /**
     * L5-fix: the mode a net.hint says you hold [p] to: Private while it is, or while your ask to switch to Private waits
     * for their answer (so a hint never undoes a switch being agreed); else Anonymous, which raises their side.
     */
    private fun holdsTo(p: PairingRecord): LinkMode = if (p.mode == LinkMode.PRIVATE || p.lowerAsked != null) LinkMode.PRIVATE else LinkMode.ANONYMOUS

    private fun forget(p: PairingRecord) {
        crossed.remove(p.id)
        transfers?.forget(p.id)
        try { stores.forget(p.id) } catch (_: SessionException) {}
        p.myInstance?.let { i -> push?.let { n -> io.execute { n.unregister(i) } } }
        stateful { st.deletePairing(p.id) }
        conns.filter { it.pairing == p.id }.forEach { it.close(); conns -= it }
        rates.remove(p.id)
        refreshOnion()
    }

    /**
     * L5: their Robot unpaired (§2.6). As [forget], but the record stays as an ENDED note: who it was in your People, the
     * mode, when, and their identity key (so you can still Block it). No session, key of yours, token or route is left.
     */
    private fun end(p: PairingRecord) {
        transfers?.forget(p.id)
        p.peerIdentity = stores.identityOf(p.id) ?: p.peerIdentity
        try { stores.forget(p.id) } catch (_: SessionException) {}
        p.myInstance?.let { i -> push?.let { n -> io.execute { n.unregister(i) } } }
        p.peerPush = null; p.peerOnion = null; p.peerOnionAuth = null; p.peerAddrs = emptyList(); p.addrsAt = 0
        p.outToken = null; p.inToken = null; p.inPrev = null; p.myInstance = null; p.myPush = null; p.myAuthPub = null; p.myAuthPriv = null
        p.code = null; p.hello = null; p.movedIn = null; p.movedOut = null; p.lowerAsked = null; p.lowerOffer = null; p.moved = null
        p.state = PairingState.ENDED; p.since = nowS()
        stateful { st.deleteTraffic(p.id); st.put(p) }
        conns.filter { it.pairing == p.id }.forEach { it.close(); conns -= it }
        rates.remove(p.id)
        log.add(LinkEvent.PAIR, PairStep.ENDED.code)
        refreshOnion()
    }

    /** Unpair (your tap): `unpair` sent (best effort, on an open connection or by push), then everything of it gone. */
    fun unpair(pairing: String): CompletableFuture<Boolean> = call { unpairNow(pairing) }

    private fun unpairNow(pairing: String): Boolean {
        val p = st.pairings[pairing] ?: return false
        if (p.state == PairingState.ACTIVE) sendUnpair(p)
        forget(p)
        return true
    }

    /**
     * `unpair` to [p]'s Robot, best effort: encrypted now, then offered to its carriers in order on an io thread until one
     * takes it (or none does). Completes once tried.
     */
    private fun sendUnpair(p: PairingRecord): CompletableFuture<Unit> {
        val tried = CompletableFuture<Unit>()
        try {
            val r = LinkRequest.of(app.heyrobot.core.link.requests.Unpair(), nowS(), LANG, CONTROL_TTL)
            val bytes = LinkRequests.encode(r, LinkRequests.LIVE)
            if (bytes != null && env.networkAllowed()) {
                val sb = stores.encrypt(p.id, bytes, nowMs())
                val routes = routesFor(p, bytes.size, sb.first)
                val kind = if (sb.first) EnvelopeKind.FIRST else EnvelopeKind.SESSION
                val mode = p.mode; val pp = p.peerPush; val pairing = p.id
                io.execute {
                    try {
                        for (rt in routes) try { if (deliver(rt, pairing, kind, sb.bytes, mode, pp, CONTROL_TTL)) break } catch (_: Exception) {}
                    } finally {
                        tried.complete(Unit)
                    }
                }
                return tried
            }
        } catch (_: Exception) {}
        tried.complete(Unit)
        return tried
    }

    /**
     * L5-fix, Start over (§6.1): `unpair` to every active pairing's Robot (best effort), so each one's pairing ends there too
     * rather than waiting on keys about to be deleted. Nothing of yours changes here (Start over deletes it all); completes
     * once every one was tried. Call it awake, so every carrier may take it.
     */
    fun unpairAll(): CompletableFuture<Unit> =
        call { st.pairings.values.filter { it.state == PairingState.ACTIVE }.map { sendUnpair(it) } }
            .thenCompose { tries -> CompletableFuture.allOf(*tries.toTypedArray()).thenApply { } }

    // Pairing: the maker's side -----------------------------------------------------------------------------------------

    /**
     * A new pairing code in [mode]: shown in person ([inPerson]: a QR code; it names a one-time onion, and in Private this
     * phone's address on the network too) or shared at a distance (a one-time push address when a distributor is chosen,
     * else a one-time onion). Null when no route could be made (no network, no Tor, no distributor).
     */
    fun makeCode(mode: LinkMode, inPerson: Boolean): CompletableFuture<MadeCode> {
        val out = CompletableFuture<MadeCode>()
        post {
            if (!env.networkAllowed()) { log.add(LinkEvent.NO_NETWORK); out.complete(MadeCode(MakeCodeResult.NO_NETWORK)); return@post }
            if (st.codes.values.count { it.open.open(nowS()) } >= MAX_CODES) { out.complete(MadeCode(MakeCodeResult.TOO_MANY)); return@post }
            if (!canPairMore()) { out.complete(MadeCode(MakeCodeResult.LIMIT)); return@post }
            val c = CodeRecord(LinkIds.newId(), app.heyrobot.core.link.OpenCode.of(ByteArray(16) { 1 }, 0), mode, inPerson, nowS())
            val wantPush = !inPerson && push?.available() == true
            val instance = if (wantPush) UUID.randomUUID().toString() else null
            val endpoint = instance?.let { i -> CompletableFuture<Unit>().also { waitingEndpoints[i] = it } }
            c.myInstance = instance
            if (instance != null) making[instance] = c
            val slot = (1..TorPaths.MAX_SERVICES).firstOrNull { s -> st.codes.values.none { it.onionSlot == s && it.onionKeyBlob != null } } ?: 0
            io.execute {
                try {
                    if (instance != null && push!!.register(instance)) {
                        try { endpoint!!.get(ENDPOINT_WAIT_SECONDS, TimeUnit.SECONDS) } catch (_: Exception) {}
                    }
                    val havePush = call { c.myPush != null }.get(10, TimeUnit.SECONDS)
                    var onion: PublishedOnion? = null
                    if ((inPerson || !havePush) && tor != null && slot != 0) {
                        onion = try {
                            torThread.submit<PublishedOnion?> { if (tor.up()) tor.publish(slot, null, emptyList()) { d -> accepted(d, LinkWay.TOR, c.id) } else null }.get(TOR_WAIT_SECONDS, TimeUnit.SECONDS)
                        } catch (_: Exception) { null }
                    }
                    post {
                        if (onion != null) { c.onionSlot = slot; c.onionKeyBlob = onion.keyBlob; c.onion = OnionAddress.keyOf(onion.serviceId) }
                        if (inPerson && mode == LinkMode.PRIVATE && direct != null && awake) {
                            if (listenPort == 0) startListening()
                            if (listenPort != 0) c.local = direct.localAddresses(listenPort).firstOrNull { !it.v6 } ?: direct.localAddresses(listenPort).firstOrNull()
                        }
                        val pushRoute = if (inPerson) null else c.myPush
                        if (pushRoute == null && c.onion == null) {
                            dropCode(c)
                            out.complete(MadeCode(MakeCodeResult.NO_ROUTE))
                            return@post
                        }
                        val (code, open) = PairCode.make(mode, stores.identityKey, nowS(), pushRoute, c.onion, c.local)
                        c.open = open
                        c.myInstance?.let { making.remove(it) }
                        if (!stateful { st.put(c) }) { dropCode(c); out.complete(MadeCode(MakeCodeResult.FAILED)); return@post }
                        out.complete(MadeCode(MakeCodeResult.OK, code, c.id))
                    }
                } catch (_: Exception) {
                    post { dropCode(c) }
                    out.complete(MadeCode(MakeCodeResult.FAILED))
                }
            }
        }
        return out
    }

    /** "Cancel this code": its one-time routes deleted at once. */
    fun cancelCodes(): CompletableFuture<Unit> = call { for (c in st.codes.values.toList()) { c.open = c.open.cancel(); dropCode(c) } }

    /** L5: the code [id] alone cancelled (its one-time routes deleted at once). False when it isn't open. */
    fun cancelCode(id: String): CompletableFuture<Boolean> = call {
        val c = st.codes[id] ?: return@call false
        c.open = c.open.cancel()
        dropCode(c)
        true
    }

    /** L5: where the code [id] you made stands (the pairing screen asks while it shows it). */
    fun codeState(id: String): CompletableFuture<Pair<CodeState, String?>> = call {
        codeOutcomes[id] ?: st.codes[id]?.let { if (it.open.open(nowS())) (if (held[id].isNullOrEmpty()) CodeState.OPEN else CodeState.HELD) to null else null }
            ?: (CodeState.CLOSED to null)
    }

    private fun dropCode(c: CodeRecord) {
        held.remove(c.id)?.forEach { h -> h.conn?.let { x -> x.close(); conns -= x } }
        c.myInstance?.let { i -> push?.let { n -> io.execute { n.unregister(i) } }; waitingEndpoints.remove(i); making.remove(i) }
        if (c.onionKeyBlob != null) tor?.let { t -> val s = c.onionSlot; torThread.execute { try { t.unpublish(s) } catch (_: Exception) {} } }
        stateful { st.deleteCode(c.id) }
    }

    /**
     * A pairing hello came: to a code's one-time route ([codeId]), or on the direct listener (any open in-person code); L5:
     * or a moved hello, on [viaPairing]'s own push address or on your onion (which only paired Robots reach).
     */
    private fun onHello(sealed: ByteArray, codeId: String?, c: LiveConn?, viaPairing: PairingRecord? = null) {
        val way = c?.way ?: LinkWay.PUSH
        val plain = stores.openHello(sealed) ?: return refuseHello(c, way, LinkProblem.BAD_FIELD.code)
        val hello = (PairHello.read(plain) as? PairHelloRead.Ok)?.hello ?: return refuseHello(c, way, LinkProblem.BAD_FIELD.code)
        // L5-fix: signed by the identity key its bundle names, checked before its token or any code is touched: another
        // Robot's genuine bundle, copied out of an earlier hello and sent with someone else's routes, never pairs.
        if (!stores.verifies(hello.bundle.identity, hello.signedPart(), hello.signature)) return refuseHello(c, way, LinkProblem.BAD_SIGNATURE.code)
        // L5: a Robot you blocked is dropped here, unread beyond its key, before it can spend a try of any code: no card.
        if (blocked(hello.bundle.identity)) {
            log.add(LinkEvent.PAIR, PairStep.BLOCKED_HELLO.code)
            return refuseHello(c, way, LinkProblem.NOT_HERE.code)
        }
        if (hello.purpose == HelloPurpose.MOVED) return onMoved(hello, plain, viaPairing, c, way)
        // A first pairing hello comes only to a code: never on a pairing's push address, nor on your onion.
        if (viaPairing != null || (codeId == null && c?.way != LinkWay.DIRECT)) return refuseHello(c, way, LinkProblem.NOT_HERE.code)
        // L5-fix: both pasted each other's codes at once, so each one's hello went to the other's code. One pairing must come
        // of it: the Robot whose key sorts first keeps its own hello and refuses this one before it touches a code (no try
        // spent); the other forgets its own and answers this one as the maker.
        // L16: only with this code's token: anyone can re-seal the other Robot's signed hello, and only the token shows it was
        // meant for this code. Without it, it is a wrong try below and your pairing in progress stays.
        val now = nowS()
        val candidates = if (codeId != null) listOfNotNull(st.codes[codeId]) else st.codes.values.filter { it.inPerson && it.local != null }
        if (candidates.isEmpty()) return refuseHello(c, way, LinkProblem.NOT_HERE.code)
        if (candidates.any { it.open.accepts(hello.token, now) }) sentTo(hello.bundle.identity)?.let { q ->
            if (PairCode.firstSends(stores.identityKey, hello.bundle.identity) == true) {
                codeId?.let { crossed[q.id] = it }
                log.add(LinkEvent.PAIR, PairStep.CROSSED.code, 1)
                return refuseHello(c, way, LinkProblem.NOT_HERE.code)
            }
            log.add(LinkEvent.PAIR, PairStep.CROSSED.code, 0)
            forget(q)
        }
        // The code whose token it carries, checked without spending it (L5-fix: in person it may only be held).
        val code = candidates.firstOrNull { it.open.accepts(hello.token, now) }
        if (code == null) {
            var last = CodeAttempt.WRONG
            for (k in candidates) {
                val (next, attempt) = k.open.attempt(hello.token, now)
                k.open = next; last = attempt
                if (k.open.open(now)) stateful { st.put(k) } else dropCode(k)
            }
            return refuseHello(c, way, last.ordinal)
        }
        val theirs = hello.bundle.identity
        if (code.inPerson) {
            val expect = code.expect
            // L5-fix: before your photo of their code, a hello is held: not answered, the code not spent. Your photo pairs the
            // one from the key it shows (useCode); one from any other key never pairs.
            if (expect == null) return hold(code, hello, c, way)
            // The photographed key only. Another one is a wrong try, not the end of the code: the right one may still come.
            if (!LinkBytes.same(expect, theirs)) {
                code.open = code.open.miss(now)
                if (code.open.open(now)) stateful { st.put(code) } else { codeOutcomes[code.id] = CodeState.MISMATCH to null; dropCode(code) }
                log.add(LinkEvent.PAIR, PairStep.MISMATCH.code)
                return refuseHello(c, way, LinkProblem.NOT_HERE.code)
            }
        }
        code.open = code.open.attempt(hello.token, now).first
        pairFromHello(code, hello, c, way)
    }

    /**
     * L5-fix, in person (§2.3): a hello with [code]'s token before your photo of their code: kept in memory (at most
     * [MAX_HELD] a code, one per key, the newest), unanswered, on its connection [c] while that stays open. Your photo pairs
     * the one whose key it shows and refuses the rest (useCode).
     */
    private fun hold(code: CodeRecord, hello: PairHello, c: LiveConn?, way: LinkWay) {
        val list = held.getOrPut(code.id) { ArrayList() }
        list.filter { LinkBytes.same(it.hello.bundle.identity, hello.bundle.identity) }.forEach { h ->
            list -= h
            h.conn?.takeIf { it !== c }?.let { x -> x.close(); conns -= x }
        }
        if (list.size >= MAX_HELD) return refuseHello(c, way, LinkProblem.NOT_HERE.code)
        list += Held(hello, c)
        log.add(LinkEvent.PAIR, PairStep.HELD.code)
    }

    /**
     * The pairing a hello to [accepted] (its token just spent) makes: a session to the hello's bundle, answered in it with
     * caps and your routes. In person (L5-fix: only ever the key you photographed), Verified in person. The pairing's id, or
     * null when refused.
     */
    private fun pairFromHello(accepted: CodeRecord, hello: PairHello, c: LiveConn?, way: LinkWay): String? {
        val now = nowS()
        val theirs = hello.bundle.identity
        val mine = stores.identityKey
        if (theirs.contentEquals(mine) || pairedWith(theirs) != null || !canPairMore()) {
            dropCode(accepted)
            refuseHello(c, way, LinkProblem.NOT_HERE.code)
            return null
        }
        // A hello of yours to that Robot still waiting for its answer (to a code of theirs that was spent or closed, so it
        // never will be): the hello in hand wins, and the stale one goes.
        sentTo(theirs)?.let { forget(it) }
        val p = PairingRecord(LinkIds.newId(), PairingState.ACTIVE, LinkMode.stricter(accepted.mode, hello.mode), now, hello.name, hello.capsBitmap)
        p.peerPush = hello.push
        p.peerOnion = hello.onion; p.peerOnionAuth = hello.onionAuth
        p.outToken = hello.token // the code's token opens the first connection to its Robot (it keeps it until it gives one)
        if (accepted.inPerson) { p.verified = Verified.IN_PERSON; p.verifiedAt = now }
        try {
            stores.pin(p.id, theirs)
            stores.startSession(p.id, hello.bundle, nowMs())
        } catch (x: SessionException) {
            try { stores.forget(p.id) } catch (_: SessionException) {}
            dropCode(accepted)
            refuseHello(c, way, 100 + x.problem.code)
            return null
        }
        if (tor != null) newOnionAuth(p)
        if (push?.available() == true) newPushInstance(p)
        if (!stateful { st.put(p) }) { refuseHello(c, way, LinkProblem.NOT_HERE.code); return null }
        codeOutcomes[accepted.id] = CodeState.USED to p.id
        dropCode(accepted)
        if (c != null && !c.closed) { c.pairing = p.id; c.route = LinkEnvelope.NO_ROUTE; c.helloAllowed = false; c.readTimeout(IDLE_MS.toInt()) }
        log.add(LinkEvent.HELLO, 2, CodeAttempt.ACCEPTED.ordinal)
        if (accepted.inPerson) log.add(LinkEvent.PAIR, PairStep.VERIFIED_IN_PERSON.code)
        refreshOnion()
        // The answer, in the session (R0 item 25: no bundle, only caps and routes).
        queueBody(p, Caps.of(myName().ifEmpty { DEFAULT_NAME }))
        sendHint(p, rotate = true)
        sink.paired(p.id)
        return p.id
    }

    /**
     * L5, a moved hello (§2.7, Q5): their Robot, restored on a new phone with a new identity, asks to carry on. It must carry
     * the moved token you gave that pairing inside the session; it pauses the pairing (MOVED) until you compare the new safety
     * number and accept it, or keep the old phone. Nothing of it is trusted before your answer, and nothing is answered.
     */
    private fun onMoved(hello: PairHello, plain: ByteArray, viaPairing: PairingRecord?, c: LiveConn?, way: LinkWay) {
        fun refuse(why: LinkProblem) { log.add(LinkEvent.PAIR, PairStep.MOVED_REFUSED.code, why.code); refuseHello(c, way, why.code) }
        val token = hello.token
        val p = viaPairing ?: st.pairings.values.firstOrNull { q -> q.movedIn?.let { LinkBytes.same(it, token) } == true }
        val mine = p?.movedIn
        if (p == null || mine == null || !LinkBytes.same(mine, token)) return refuse(LinkProblem.BAD_ROUTE)
        if (p.state != PairingState.ACTIVE && p.state != PairingState.MOVED) return refuse(LinkProblem.NOT_HERE)
        val theirs = hello.bundle.identity
        if (theirs.contentEquals(stores.identityKey) || stores.identityOf(p.id)?.let { LinkBytes.same(it, theirs) } == true) return refuse(LinkProblem.NOT_HERE)
        if (p.movedDeclined?.let { LinkBytes.same(it, theirs) } == true) return refuse(LinkProblem.NOT_HERE)
        val waiting = p.moved?.let { (PairHello.read(it) as? PairHelloRead.Ok)?.hello }
        // One move waits at a time: the same new phone may send it again (its routes may have changed); another one may not.
        if (p.state == PairingState.MOVED && waiting != null && !LinkBytes.same(waiting.bundle.identity, theirs)) return refuse(LinkProblem.NOT_HERE)
        val first = p.state != PairingState.MOVED
        p.state = PairingState.MOVED
        p.moved = plain.copyOf()
        if (first) p.since = nowS()
        if (!stateful { st.put(p) }) return
        if (c != null) { c.close(); conns -= c }
        log.add(LinkEvent.PAIR, PairStep.MOVED_IN.code)
        if (first) sink.moved(p.id)
    }

    private fun refuseHello(c: LiveConn?, way: LinkWay, why: Int) {
        log.add(LinkEvent.HELLO, 3, why)
        if (c != null) { c.close(); conns -= c }
    }

    // Pairing: the side that scanned or pasted ------------------------------------------------------------------------------

    /**
     * Pairs with the code in [text] ([form]: scanned in person, or pasted), in [mode] (yours; the pairing uses the stricter
     * of yours and the code's): a pairing in state SENT whose sealed hello goes to the code's routes until it is answered or
     * the code expires.
     *
     * L5, in person (§2.3): both Robots photograph each other's code, and only the one whose identity key sorts first sends;
     * the other keeps the photographed key on its own open in-person codes (WAITING), so the hello that comes must be from
     * that key. A pairing already made with the photographed key becomes Verified in person (VERIFIED). A Robot with no code
     * of its own showing sends whatever the order (it can't have been photographed).
     */
    fun useCode(text: String, form: PairCodeForm, mode: LinkMode): CompletableFuture<UsedCode> {
        val out = CompletableFuture<UsedCode>()
        post {
            val now = nowS()
            val code = when (val read = PairCode.read(text, form, now)) {
                is PairCodeRead.Ok -> read.code
                PairCodeRead.Expired -> { out.complete(UsedCode(UseCodeResult.EXPIRED)); return@post }
                is PairCodeRead.Newer -> { out.complete(UsedCode(UseCodeResult.NEWER)); return@post }
                is PairCodeRead.Bad -> { out.complete(UsedCode(UseCodeResult.BAD)); return@post }
            }
            val theirs = code.identity
            val first = PairCode.firstSends(stores.identityKey, theirs)
            if (first == null) { out.complete(UsedCode(UseCodeResult.OWN)); return@post }
            if (blocked(theirs)) { out.complete(UsedCode(UseCodeResult.BLOCKED)); return@post }
            sentTo(theirs)?.let { q ->
                // The same code again: its hello is on its way already. Another code from that Robot: the old hello goes.
                val same = (q.code?.let { PairCode.read(it, PairCodeForm.SCANNED, now) } as? PairCodeRead.Ok)?.code?.token?.let { LinkBytes.same(it, code.token) } == true
                if (same) { out.complete(UsedCode(UseCodeResult.SENT, q.id)); return@post }
                forget(q)
            }
            pairedWith(theirs)?.let { q ->
                if (form == PairCodeForm.SCANNED && q.state == PairingState.ACTIVE) {
                    if (q.verified == Verified.NO) { q.verified = Verified.IN_PERSON; q.verifiedAt = now; stateful { st.put(q) } }
                    log.add(LinkEvent.PAIR, PairStep.VERIFIED_IN_PERSON.code)
                    out.complete(UsedCode(UseCodeResult.VERIFIED, q.id))
                } else {
                    out.complete(UsedCode(UseCodeResult.ALREADY, q.id))
                }
                return@post
            }
            if (!env.networkAllowed()) { out.complete(UsedCode(UseCodeResult.NO_NETWORK)); return@post }
            if (!canPairMore()) { out.complete(UsedCode(UseCodeResult.LIMIT)); return@post }
            if (form == PairCodeForm.SCANNED) {
                // L5-fix: your photo says whose Robot this is. A hello it sent to a code you show before you took it pairs now,
                // Verified in person; the others held are refused (each a wrong try), and from now on a hello to your code
                // from any other key is refused too, whichever key sorts first.
                val shown = st.codes.values.filter { it.inPerson && it.open.open(now) }
                val match = shown.firstNotNullOfOrNull { c -> held[c.id]?.firstOrNull { LinkBytes.same(it.hello.bundle.identity, theirs) }?.let { c to it } }
                for (c in shown) {
                    for (h in held.remove(c.id).orEmpty()) if (h !== match?.second) {
                        h.conn?.let { x -> x.close(); conns -= x }
                        c.open = c.open.miss(now)
                        log.add(LinkEvent.PAIR, PairStep.MISMATCH.code)
                    }
                    c.expect = theirs
                }
                val pairing = match?.let { (c, h) ->
                    if (!c.open.accepts(h.hello.token, now)) return@let null
                    c.open = c.open.attempt(h.hello.token, now).first
                    pairFromHello(c, h.hello, h.conn?.takeIf { !it.closed }, h.conn?.way ?: LinkWay.TOR)
                }
                for (c in shown) if (st.codes.containsKey(c.id)) { if (c.open.open(now)) stateful { st.put(c) } else { codeOutcomes[c.id] = CodeState.MISMATCH to null; dropCode(c) } }
                if (pairing != null) {
                    out.complete(UsedCode(UseCodeResult.VERIFIED, pairing))
                    return@post
                }
                // Their key sorts first: they send the hello, and your code waits for it (from that key alone).
                if (!first && shown.any { it.open.open(now) }) {
                    log.add(LinkEvent.PAIR, PairStep.SCAN_KEPT.code)
                    out.complete(UsedCode(UseCodeResult.WAITING))
                    return@post
                }
            }
            val p = PairingRecord(LinkIds.newId(), PairingState.SENT, LinkMode.stricter(code.mode, mode), now, "", ByteArray(LinkCaps.BITMAP))
            p.inToken = code.token // the maker's first connection to you opens with it
            p.code = code.bytes()
            p.scanned = form == PairCodeForm.SCANNED
            try { stores.pin(p.id, theirs) } catch (_: SessionException) { out.complete(UsedCode(UseCodeResult.FAILED)); return@post }
            val instance = if (push?.available() == true) newPushInstance(p) else null
            if (tor != null) newOnionAuth(p)
            if (!stateful { st.put(p) }) { out.complete(UsedCode(UseCodeResult.FAILED)); return@post }
            if (p.scanned) log.add(LinkEvent.PAIR, PairStep.SCAN_SENT.code)
            val endpoint = instance?.let { i -> CompletableFuture<Unit>().also { waitingEndpoints[i] = it } }
            io.execute {
                try { endpoint?.get(ENDPOINT_WAIT_SECONDS, TimeUnit.SECONDS) } catch (_: Exception) {}
                val onionReady = if (tor != null) try { torThread.submit<Boolean> { refreshOnionNow() }.get(TOR_WAIT_SECONDS, TimeUnit.SECONDS) } catch (_: Exception) { false } else false
                post {
                    val q = st.pairings[p.id] ?: run { out.complete(UsedCode(UseCodeResult.FAILED)); return@post }
                    val onion = st.tor?.onion?.takeIf { onionReady }
                    if (q.myPush == null && onion == null) { forget(q); out.complete(UsedCode(UseCodeResult.NO_ROUTE)); return@post }
                    val bundle = try { stores.bundle(stores.newKyberPreKey(nowMs()), nowMs()) } catch (_: SessionException) { forget(q); out.complete(UsedCode(UseCodeResult.FAILED)); return@post }
                    val unsigned = PairHello(HelloPurpose.PAIR, mode, code.token, bundle, q.myPush, onion, if (onion != null) q.myAuthPriv else null,
                        myName().ifEmpty { DEFAULT_NAME }, LinkCaps.bitmap(LinkCaps.SUPPORTED))
                    val hello = unsigned.signed(stores.signHello(unsigned.signedPart()))
                    q.hello = HelloSeal.seal(code.identity, hello.bytes())
                    q.helloNext = 0
                    stateful { st.put(q) }
                    sendHello(q)
                    out.complete(UsedCode(UseCodeResult.SENT, q.id))
                }
            }
        }
        return out
    }

    /** L12: what a backup holds of Robot to Robot (LinkBackupStore), or null when it can't be read now. */
    fun backupExport(): CompletableFuture<app.heyrobot.core.link.LinkBackupFile?> {
        val out = CompletableFuture<app.heyrobot.core.link.LinkBackupFile?>()
        post { out.complete(try { LinkBackupStore.export(st, stores) } catch (_: Exception) { null }) }
        return out
    }

    /** L12: a restored backup's pairings, MOVING and paused (LinkBackupStore). False: not all written; call again. */
    fun restoreBackup(file: app.heyrobot.core.link.LinkBackupFile): CompletableFuture<Boolean> {
        val out = CompletableFuture<Boolean>()
        post { out.complete(stateful { LinkBackupStore.restore(st, stores, file) }) }
        return out
    }

    /**
     * L5: your Robot moved to this phone (a restore, L12): [pairing] is a MOVING record holding their pinned identity key,
     * their push address and the moved token they gave you, but no session (no backup carries a Tor client authorization, so a
     * pairing with only an onion answers false: it pairs again). A moved
     * hello with a new bundle goes to their routes, sealed to their key; it is sent again with backoff for [MOVE_DAYS] days
     * until their Robot answers (after its owner compared the new safety number and accepted). False when it can't go.
     */
    fun sendMoved(pairing: String): CompletableFuture<Boolean> {
        val out = CompletableFuture<Boolean>()
        post {
            val p = st.pairings[pairing]?.takeIf { it.state == PairingState.MOVING }
            val theirs = p?.let { stores.identityOf(it.id) }
            val token = p?.movedOut
            if (p == null || theirs == null || token == null || (p.peerPush == null && p.peerOnion == null) || !env.networkAllowed()) { out.complete(false); return@post }
            // Their first connection to this phone opens with the token they gave (as a code's token does).
            p.inToken = token.copyOf(); p.inPrev = null
            p.since = nowS() // F131: every tap gets its own week of tries
            val instance = if (p.myInstance == null && push?.available() == true) newPushInstance(p) else null
            if (tor != null && p.myAuthPub == null) newOnionAuth(p)
            if (!stateful { st.put(p) }) { out.complete(false); return@post }
            val endpoint = instance?.let { i -> CompletableFuture<Unit>().also { waitingEndpoints[i] = it } }
            io.execute {
                try { endpoint?.get(ENDPOINT_WAIT_SECONDS, TimeUnit.SECONDS) } catch (_: Exception) {}
                val onionReady = if (tor != null) try { torThread.submit<Boolean> { refreshOnionNow() }.get(TOR_WAIT_SECONDS, TimeUnit.SECONDS) } catch (_: Exception) { false } else false
                post {
                    val q = st.pairings[pairing]?.takeIf { it.state == PairingState.MOVING } ?: run { out.complete(false); return@post }
                    val onion = st.tor?.onion?.takeIf { onionReady }
                    if (q.myPush == null && onion == null) { out.complete(false); return@post }
                    val bundle = try { stores.bundle(stores.newKyberPreKey(nowMs()), nowMs()) } catch (_: SessionException) { out.complete(false); return@post }
                    val unsigned = PairHello(HelloPurpose.MOVED, q.mode, token, bundle, q.myPush, onion, if (onion != null) q.myAuthPriv else null,
                        myName().ifEmpty { DEFAULT_NAME }, LinkCaps.bitmap(LinkCaps.SUPPORTED))
                    val hello = unsigned.signed(stores.signHello(unsigned.signedPart()))
                    q.hello = HelloSeal.seal(theirs, hello.bytes())
                    q.helloNext = 0; q.helloTries = 0
                    stateful { st.put(q) }
                    log.add(LinkEvent.PAIR, PairStep.MOVED_SENT.code)
                    sendHello(q)
                    out.complete(true)
                }
            }
        }
        return out
    }

    /**
     * Sends [p]'s sealed hello: for a SENT pairing to its code's routes (this network in Private in person, the one-time
     * onion, the push address); for a MOVING one (L5) to the pairing's own routes (their push address, their onion with your
     * client authorization for it).
     */
    private fun sendHello(p: PairingRecord) {
        val hello = p.hello ?: return
        val local: app.heyrobot.core.link.LocalAddress?
        val onion: OnionKey?
        val onionAuth: ByteArray?
        val pushTo: app.heyrobot.core.link.PushRoute?
        if (p.state == PairingState.MOVING) {
            if (nowS() > p.since + MOVE_DAYS * LinkLimits.DAY) { p.hello = null; stateful { st.put(p) }; return } // stays MOVING: "Try again" or Unpair
            local = null; onion = p.peerOnion; onionAuth = p.peerOnionAuth; pushTo = p.peerPush
        } else {
            val code = (p.code?.let { PairCode.read(it, PairCodeForm.SCANNED, nowS()) } as? PairCodeRead.Ok)?.code
            if (code == null) { forget(p); return } // expired: the pairing never happened
            local = code.local; onion = code.onion; onionAuth = null; pushTo = code.push
        }
        if (!env.networkAllowed()) { log.add(LinkEvent.NO_NETWORK); return }
        p.helloTries++
        p.helloNext = nowS() + Backoff.delay(minOf(p.helloTries - 1, 20))
        stateful { st.put(p) }
        val env = LinkEnvelope(EnvelopeKind.PAIR_HELLO, LinkEnvelope.NO_ROUTE, hello)
        val id = p.id
        val mode = p.mode
        // L5-fix: Tor (the onion, or push through it) only while awake.
        val torOk = tor != null && awake
        io.execute {
            var ok = false
            if (!ok && local != null && Ways.directAllowed(mode) && direct != null) ok = try {
                helloLive(direct.dial(local), LinkWay.DIRECT, id, env); true
            } catch (_: Exception) { false }
            if (!ok && onion != null && tor != null && torOk) ok = try {
                if (!tor.up()) throw TorException(TorProblem.NOT_READY)
                if (onionAuth != null) tor.clientAuth(onion, onionAuth)
                helloLive(tor.dial(onion, id), LinkWay.TOR, id, env); true
            } catch (_: Exception) { false }
            if (!ok && pushTo != null && poster != null && (!Ways.pushViaTor(mode) || torOk)) ok = try {
                val viaTor = Ways.pushViaTor(mode)
                if (viaTor && tor?.up() != true) throw TorException(TorProblem.NOT_READY)
                poster.post(pushTo, WebPush.seal(pushTo, env.encode(LinkCarrier.PUSH)), HELLO_TTL, viaTor, id); true
            } catch (_: Exception) { false }
            log.add(LinkEvent.HELLO, 1, if (ok) 1 else 0)
        }
    }

    private fun helloLive(d: Duplex, way: LinkWay, pairing: String, env: LinkEnvelope) {
        val c = LiveConn(d, way, true, nowMs())
        c.pairing = pairing
        c.route = LinkEnvelope.NO_ROUTE
        c.readTimeout(IDLE_MS.toInt())
        try { c.send(env) } catch (x: Exception) { c.close(); throw x }
        post { conns += c; log.add(LinkEvent.CONNECT, way.code, 1) }
        c.startReading(::onFrame)
    }

    // Your routes ---------------------------------------------------------------------------------------------------------

    private fun newPushInstance(p: PairingRecord): String {
        val i = UUID.randomUUID().toString()
        p.myInstance = i
        val n = push
        if (n != null) io.execute { if (n.register(i)) log.add(LinkEvent.PUSH, 1, 0) }
        return i
    }

    /** A client authorization key pair for [p] (x25519: libsignal's curve25519 keys are exactly Tor's x25519 keys). */
    private fun newOnionAuth(p: PairingRecord) {
        val k = ECKeyPair.generate()
        p.myAuthPub = k.publicKey.publicKeyBytes
        p.myAuthPriv = k.privateKey.serialize()
    }

    /** The clients your onion should answer: every pairing's key. Changed: published again (on Tor's thread). */
    private fun refreshOnion() {
        if (tor == null) return
        // L5-fix: only while awake; asleep, Tor is down and the next wake publishes it for the clients as they are then.
        if (!awake) return
        torThread.execute { try { refreshOnionNow() } catch (_: Exception) {} }
    }

    /** On Tor's thread or an io thread: publishes your onion for the current clients when they changed. True when it's up. */
    private fun refreshOnionNow(): Boolean {
        val t = tor ?: return false
        val clients = call { st.pairings.values.mapNotNull { it.myAuthPub } }.get(10, TimeUnit.SECONDS)
        val key = clients.map { LinkBytes.hex(it) }.toSet()
        if (clients.isEmpty()) { t.unpublish(0); post { onionClients = emptySet() }; return false }
        if (call { onionClients == key }.get(10, TimeUnit.SECONDS) && t.running) return true
        if (!env.networkAllowed() || !t.up()) return false
        val blob = call { st.tor?.keyBlob }.get(10, TimeUnit.SECONDS)
        val pub = try { t.publish(0, blob, clients) { d -> accepted(d, LinkWay.TOR) } } catch (_: Exception) { log.add(LinkEvent.TOR, 5, 0); return false }
        val onion = OnionAddress.keyOf(pub.serviceId) ?: return false
        call {
            if (st.tor == null) stateful { st.putTor(TorRecord(pub.keyBlob, onion)) }
            val before = onionClients.orEmpty()
            onionClients = key
            // Pairings your onion now answers that weren't told yet: their net.hint, with the key you made for them.
            for (p in st.pairings.values) if (p.state == PairingState.ACTIVE && p.myAuthPub?.let { LinkBytes.hex(it) }?.let { it in key && it !in before } == true) wakeHint(p)
            // Every paired Robot's client authorization, for streams to their onions.
            for (p in st.pairings.values) { val o = p.peerOnion; val a = p.peerOnionAuth; if (o != null && a != null) io.execute { try { t.clientAuth(o, a) } catch (_: Exception) {} } }
        }.get(10, TimeUnit.SECONDS)
        log.add(LinkEvent.TOR, 4, 0)
        return true
    }

    /** What should run while awake: the direct listener for Private pairings and open in-person codes, your onion. */
    private fun refreshCarriers() {
        if (!awake) return
        if (!env.networkAllowed()) { log.add(LinkEvent.NO_NETWORK); return }
        val wantListen = direct != null && (st.pairings.values.any { it.state == PairingState.ACTIVE && Ways.directAllowed(it.mode) } ||
            st.codes.values.any { it.inPerson && it.local != null })
        if (wantListen && listenPort == 0) {
            startListening()
            for (p in st.pairings.values.filter { it.state == PairingState.ACTIVE && Ways.directAllowed(it.mode) }) wakeHint(p)
        }
        if (!wantListen && listenPort != 0) { direct?.stopListening(); listenPort = 0; log.add(LinkEvent.LISTEN, 0, 0) }
        if (tor != null && st.pairings.values.any { it.myAuthPub != null }) refreshOnion()
        // Open codes' one-time onions, published again after Tor stopped.
        val t = tor
        if (t != null) for (c in st.codes.values.filter { it.onionKeyBlob != null && it.open.open(nowS()) }) {
            val slot = c.onionSlot; val blob = c.onionKeyBlob; val id = c.id
            torThread.execute { try { if (t.up()) t.publish(slot, blob, emptyList()) { d -> accepted(d, LinkWay.TOR, id) } } catch (_: Exception) {} }
        }
    }

    private fun startListening() {
        val d = direct ?: return
        listenPort = try { d.listen { s -> accepted(s, LinkWay.DIRECT) } } catch (_: Exception) { 0 }
        if (listenPort != 0) log.add(LinkEvent.LISTEN, 1, 0)
    }

    // Housekeeping --------------------------------------------------------------------------------------------------------

    private fun sweep() {
        val now = nowS()
        for (c in st.codes.values.toList()) if (!c.open.open(now)) dropCode(c)
        for (p in st.pairings.values.toList()) if (p.state == PairingState.SENT) {
            val code = p.code?.let { PairCode.read(it, PairCodeForm.SCANNED, now) }
            if (code !is PairCodeRead.Ok) forget(p)
        }
        // L5: an ENDED note goes after 30 days.
        for (p in st.pairings.values.toList()) if (p.state == PairingState.ENDED && now > p.since + ENDED_DAYS * LinkLimits.DAY) stateful { st.deletePairing(p.id) }
        for (w in st.replay.values) w.purge(now)
        conns.removeAll { it.closed }
        for (c in conns.toList()) if (nowMs() - c.lastAt > IDLE_MS) { c.close(); conns -= c }
        try { stores.prune(nowMs()) } catch (_: SessionException) {}
        transfers?.sweep()
    }

    // Pairings and People (L5: main's screens, through LinkService) ------------------------------------------------------

    /** Every pairing as main may see it: no key, token, address or route (core PairingView). */
    fun views(): CompletableFuture<List<PairingView>> = call {
        st.pairings.values.sortedBy { it.created }.take(PairingView.MAX).map { p ->
            val claimed = p.name.takeIf { it.isEmpty() || LinkText.ok(it, PairHello.MAX_NAME) } ?: ""
            val movedClaimed = p.moved?.let { (PairHello.read(it) as? PairHelloRead.Ok)?.hello?.name }.orEmpty()
            PairingView(p.id, PairingPhase.of(p.state.code)!!, p.mode, p.created, claimed, p.person, p.verified, p.verifiedAt, p.preset, p.levels,
                p.lowerAsked != null, p.lowerOffer != null, p.lowerDeclinedAt, movedClaimed, p.since)
        }
    }

    /**
     * [pairing]'s safety number: 60 digits from your identity key and theirs, the same on both phones; while a moved hello
     * waits, with the new key it brings (what you compare before accepting it). Null without a key to compare.
     */
    fun safety(pairing: String): CompletableFuture<String?> = call {
        val p = st.pairings[pairing] ?: return@call null
        val theirs = when (p.state) {
            PairingState.MOVED -> p.moved?.let { (PairHello.read(it) as? PairHelloRead.Ok)?.hello?.bundle?.identity }
            PairingState.ENDED -> null
            else -> stores.identityOf(p.id)
        } ?: return@call null
        stores.safetyNumber(theirs)
    }

    /** "They match" (§2.3): you compared the safety number; the pairing is verified from now. */
    fun verify(pairing: String): CompletableFuture<Boolean> = change(pairing) { p ->
        if (p.state != PairingState.ACTIVE) return@change false
        p.verified = Verified.COMPARED; p.verifiedAt = nowS()
        log.add(LinkEvent.PAIR, PairStep.COMPARED.code)
        true
    }

    /** "Who is this" (§2.3): [person] in your People (null: nobody yet). The name their Robot sent never picks one. */
    fun setPerson(pairing: String, person: String?): CompletableFuture<Boolean> = change(pairing) { p ->
        if (person != null && !app.heyrobot.core.PeopleStore.validId(person)) return@change false
        p.person = person
        log.add(LinkEvent.PAIR, PairStep.PERSON_SET.code)
        true
    }

    /** A preset (D8): every level set to the preset's. Nothing starts at Answer on its own. */
    fun setPreset(pairing: String, preset: LinkPreset): CompletableFuture<Boolean> = change(pairing) { p ->
        if (p.state == PairingState.ENDED) return@change false
        p.preset = preset; p.levels = LinkLevels.of(preset)
        log.add(LinkEvent.PAIR, PairStep.LEVELS_SET.code, LinkLevels.presetCode(preset))
        true
    }

    /** Your tap on the permissions page: [cap] to [level], where it may be (core LinkLevels.set). */
    fun setLevel(pairing: String, cap: LinkCapability, level: LinkLevel): CompletableFuture<Boolean> = change(pairing) { p ->
        if (p.state == PairingState.ENDED) return@change false
        p.levels = LinkLevels.set(p.levels, cap, level) ?: return@change false
        log.add(LinkEvent.PAIR, PairStep.LEVELS_SET.code)
        true
    }

    /** "Raise to Anonymous" (§2.2): at once here, and `mode.raise` switches theirs. */
    fun raise(pairing: String): CompletableFuture<Boolean> = call {
        val p = st.pairings[pairing]?.takeIf { it.state == PairingState.ACTIVE && it.mode == LinkMode.PRIVATE } ?: return@call false
        toAnonymous(p)
        if (!stateful { st.put(p) }) return@call false
        // L5-fix: it waits 30 days for them, not one (a phone off for a day must not leave the two modes apart).
        queueBody(p, ModeRaise(), MODE_TTL)
        log.add(LinkEvent.PAIR, PairStep.RAISED.code)
        true
    }

    /**
     * "Ask to switch to Private" (§2.2): `mode.lower`, and the pairing stays Anonymous until they agree. When they asked
     * first, this is your agreement: Private here at once, and your `mode.lower` lowers theirs.
     */
    fun lower(pairing: String): CompletableFuture<Boolean> = call {
        val p = st.pairings[pairing]?.takeIf { it.state == PairingState.ACTIVE && it.mode == LinkMode.ANONYMOUS } ?: return@call false
        if (p.lowerOffer != null) {
            toPrivate(p)
            queueBody(p, ModeLower(), MODE_TTL)
            return@call true
        }
        if (p.lowerAsked != null) return@call true
        val id = queueBody(p, ModeLower(), MODE_TTL) ?: return@call false
        p.lowerAsked = id
        stateful { st.put(p) }
        log.add(LinkEvent.PAIR, PairStep.LOWER_ASKED.code)
        true
    }

    /** "Keep Anonymous": their ask declined (their `mode.lower` answered DECLINED). */
    fun keepMode(pairing: String): CompletableFuture<Boolean> = call {
        val p = st.pairings[pairing]?.takeIf { it.state == PairingState.ACTIVE } ?: return@call false
        val offer = p.lowerOffer ?: return@call false
        p.lowerOffer = null
        stateful { st.put(p) }
        queueBody(p, Ack(offer, AckState.DECLINED))
        log.add(LinkEvent.PAIR, PairStep.LOWER_DECLINED.code)
        true
    }

    /**
     * Block (§2.6): Unpair, and their identity key (with a waiting move's new key) goes on the block list with [label], the
     * name you knew them by: a hello from it is dropped from then on, unread, with no card.
     */
    fun block(pairing: String, label: String): CompletableFuture<Boolean> = call {
        val p = st.pairings[pairing] ?: return@call false
        val keys = listOfNotNull(stores.identityOf(p.id) ?: p.peerIdentity, p.moved?.let { (PairHello.read(it) as? PairHelloRead.Ok)?.hello?.bundle?.identity })
            .distinctBy { LinkBytes.hex(it) }.filter { !blocked(it) }
        if (st.blocked.size + keys.size > BlockedView.MAX) return@call false
        val text = BlockedView.label(label)
        for (k in keys) if (!stateful { st.put(BlockRecord(LinkIds.newId(), k, text, nowS())) }) return@call false
        unpairNow(pairing)
        log.add(LinkEvent.PAIR, PairStep.BLOCKED.code)
        true
    }

    /** The Robots you blocked (Settings › Robot to Robot › Blocked). */
    fun blockedList(): CompletableFuture<List<BlockedView>> = call { st.blocked.values.sortedBy { it.at }.map { BlockedView(it.id, it.label, it.at) } }

    fun unblock(id: String): CompletableFuture<Boolean> = call {
        if (!st.blocked.containsKey(id)) return@call false
        val ok = stateful { st.deleteBlock(id) }
        if (ok) log.add(LinkEvent.PAIR, PairStep.UNBLOCKED.code)
        ok
    }

    /**
     * Your answer to a moved hello (§2.7). [accept], after "They match" on the new safety number: the new key pinned in place
     * of the old, a session started to the new phone's bundle, its routes taken, new tokens both ways (the moved tokens
     * included), verified as compared; the old phone can't read or send for this pairing again. Otherwise the old phone is
     * kept: the pairing goes on as it was, and hellos from that new key are dropped from then on.
     */
    fun answerMoved(pairing: String, accept: Boolean): CompletableFuture<Boolean> = call {
        val p = st.pairings[pairing]?.takeIf { it.state == PairingState.MOVED } ?: return@call false
        val hello = p.moved?.let { (PairHello.read(it) as? PairHelloRead.Ok)?.hello }
        val old = stores.identityOf(p.id)
        if (hello == null || old == null || !accept) {
            hello?.let { p.movedDeclined = it.bundle.identity }
            p.state = PairingState.ACTIVE; p.moved = null; p.since = 0
            // L5-fix: the moved token that hello carried came from a backup someone else may hold: a new one, in a hint
            // (sendHint makes it), so another identity can't pause the pairing again with it.
            p.movedIn = null
            stateful { st.put(p) }
            log.add(LinkEvent.PAIR, PairStep.MOVED_DECLINED.code)
            sendHint(p, rotate = false)
            return@call hello != null && !accept
        }
        try {
            // L5-fix: the new key pinned, the old session deleted (archived states and all) and a new one started, in one go.
            stores.replaceSession(p.id, hello.bundle.identity, hello.bundle, nowMs())
        } catch (x: SessionException) {
            // Its bundle doesn't check (libsignal): nothing changed (the old key and session stay), and this move is refused for good.
            p.movedDeclined = hello.bundle.identity
            p.state = PairingState.ACTIVE; p.moved = null; p.since = 0
            stateful { st.put(p) }
            log.add(LinkEvent.PAIR, PairStep.MOVED_REFUSED.code, 100 + x.problem.code)
            return@call false
        }
        p.mode = LinkMode.stricter(p.mode, hello.mode)
        p.peerPush = hello.push; p.peerOnion = hello.onion; p.peerOnionAuth = hello.onionAuth; p.peerAddrs = emptyList(); p.addrsAt = 0
        p.outToken = hello.token // the moved token opens your first connection to the new phone, as a code's token does
        p.inToken = null; p.inPrev = null
        p.caps = hello.capsBitmap; p.name = hello.name
        p.movedIn = null; p.movedOut = null; p.movedDeclined = null
        p.lowerAsked = null; p.lowerOffer = null
        p.verified = Verified.COMPARED; p.verifiedAt = nowS()
        p.state = PairingState.ACTIVE; p.moved = null; p.since = 0
        conns.filter { it.pairing == p.id }.forEach { it.close(); conns -= it }
        // L5-fix: the old phone's routes to you go too. It holds this pairing's push address and its client key for your
        // onion: a new push address (the old one unregistered) and a new client key, which the new phone gets in a hint.
        p.myInstance?.let { i -> push?.let { n -> io.execute { n.unregister(i) } } }
        p.myInstance = null; p.myPush = null
        if (push?.available() == true) newPushInstance(p)
        if (tor != null) newOnionAuth(p)
        if (!stateful { st.put(p) }) return@call false
        log.add(LinkEvent.PAIR, PairStep.MOVED_ACCEPTED.code)
        refreshOnion()
        queueBody(p, Caps.of(myName().ifEmpty { DEFAULT_NAME }))
        sendHint(p, rotate = true)
        sink.paired(p.id)
        true
    }

    /**
     * L5-fix: their answer to your ask to switch to Private. HANDLED: they are Private (they agreed too, or were already),
     * so it's Private here. DECLINED: they kept Anonymous. RECEIVED (a copy they had handled before, its first answer lost)
     * or UNSUPPORTED: the ask is over; you may ask again. WAITING (their card), BUSY or EXPIRED (sent again): still waiting.
     */
    private fun answeredLower(p: PairingRecord, state: AckState) {
        when (state) {
            AckState.HANDLED -> if (p.mode == LinkMode.ANONYMOUS) toPrivate(p) else { p.lowerAsked = null; stateful { st.put(p) } }
            AckState.DECLINED -> {
                p.lowerAsked = null; p.lowerDeclinedAt = nowS()
                stateful { st.put(p) }
                log.add(LinkEvent.PAIR, PairStep.LOWER_DECLINED.code)
            }
            AckState.RECEIVED, AckState.UNSUPPORTED -> { p.lowerAsked = null; stateful { st.put(p) } }
            AckState.WAITING, AckState.BUSY, AckState.EXPIRED -> {}
        }
    }

    /** [block] applied to [pairing]'s record on the link thread and written; false when it says no or the write failed. */
    private fun change(pairing: String, block: (PairingRecord) -> Boolean): CompletableFuture<Boolean> = call {
        val p = st.pairings[pairing] ?: return@call false
        block(p) && stateful { st.put(p) }
    }

    private fun toAnonymous(p: PairingRecord) {
        p.mode = LinkMode.ANONYMOUS
        p.peerAddrs = emptyList(); p.addrsAt = 0
        p.lowerAsked = null; p.lowerOffer = null
        // Nothing goes direct again: an open connection on this network closes.
        conns.filter { it.pairing == p.id && it.way == LinkWay.DIRECT }.forEach { it.close(); conns -= it }
    }

    /** Both asked: Private on this side, and your addresses on a shared network told while you listen. */
    private fun toPrivate(p: PairingRecord) {
        p.mode = LinkMode.PRIVATE
        p.lowerAsked = null; p.lowerOffer = null; p.lowerDeclinedAt = 0
        stateful { st.put(p) }
        log.add(LinkEvent.PAIR, PairStep.LOWERED.code)
        sink.lowered(p.id)
        refreshCarriers()
        if (listenPort != 0) sendHint(p, rotate = false)
    }

    private fun blocked(identity: ByteArray): Boolean = st.blocked.values.any { it.blocks(identity) }

    /** A pairing made, paused or moving (not a hello still waiting, nor an ENDED note) whose pinned key is [identity]. */
    private fun pairedWith(identity: ByteArray): PairingRecord? =
        st.pairings.values.firstOrNull { it.state != PairingState.ENDED && it.state != PairingState.SENT && stores.identityOf(it.id)?.let { k -> LinkBytes.same(k, identity) } == true }

    /** Your hello to [identity]'s code, still waiting for its answer. */
    private fun sentTo(identity: ByteArray): PairingRecord? =
        st.pairings.values.firstOrNull { it.state == PairingState.SENT && stores.identityOf(it.id)?.let { k -> LinkBytes.same(k, identity) } == true }

    /** §2.3's limits: at most 100 pairings and 10 new ones a day (ENDED notes don't count). */
    private fun canPairMore(): Boolean {
        val live = st.pairings.values.filter { it.state != PairingState.ENDED }
        return LinkLimits.canPair(live.size, live.count { it.created > nowS() - LinkLimits.DAY })
    }

    // Status (the IPC's) ----------------------------------------------------------------------------------------------------

    /** [due]: outbox requests a carrier hasn't taken yet and that may go now, or are on their way. */
    class Status(val pairings: Int, val sent: Int, val outbox: Int, val due: Int, val codes: Int, val connections: Int, val listening: Boolean, val torRunning: Boolean, val awake: Boolean)

    fun status(): CompletableFuture<Status> = call {
        val now = nowS()
        Status(st.pairings.values.count { it.state == PairingState.ACTIVE }, st.pairings.values.count { it.state == PairingState.SENT },
            st.outbox.size, st.outbox.values.count { (!it.sent && it.nextAt <= now) || it.id in inFlight }, st.codes.size,
            conns.count { !it.closed }, listenPort != 0, tor?.running == true, awake)
    }

    /**
     * The phone's network changed (a new Wi-Fi): Private pairings get your new addresses on it. L5-fix: the listeners are
     * bound to this phone's addresses, so they are bound again on the new network, or stay off away from Wi-Fi, Ethernet
     * and a VPN; refreshCarriers sends the hints once they listen.
     */
    fun networkChanged() = post {
        pumpNow()
        if (!awake) return@post
        if (listenPort != 0) { direct?.stopListening(); listenPort = 0; log.add(LinkEvent.LISTEN, 0, 0) }
        refreshCarriers()
    }

    /** The pairings' ids and states (L5's screens read more through their own calls). */
    fun pairings(): CompletableFuture<Map<String, PairingState>> = call { st.pairings.mapValues { it.value.state } }

    // L6, the desk -----------------------------------------------------------------------------------------------------------

    /** L6: the desk, when it is this node's sink (LinkService's desk calls run through [onLinkThread]). */
    val desk: LinkDesk? get() = sink as? LinkDesk

    /** Runs [block] on the link thread (the desk's calls from LinkService: its state is this thread's). */
    fun <T> onLinkThread(block: () -> T): CompletableFuture<T> = call(block)

    /** L8: files between Robots. Set once, before [start]. */
    @Volatile private var transfers: LinkTransfers? = null
    val fileTransfers: LinkTransfers? get() = transfers

    /** L8: [t] joins the node, on the link thread, [notice] being the desk's way to tell :voice. */
    fun attachTransfers(t: LinkTransfers, notice: (app.heyrobot.core.link.DeskNotice) -> Unit): CompletableFuture<Unit> = call {
        transfers = t
        t.attach(xferLine, deskOut, notice)
    }

    private val xferLine = object : XferLine {
        override fun proven(pairing: String): LiveConn? =
            conns.firstOrNull { it.pairing == pairing && !it.closed && it.proven && st.pairings[pairing]?.let { p -> it.way != LinkWay.DIRECT || Ways.directAllowed(p.mode) } == true }

        override fun dial(pairing: String) {
            val p = call { st.pairings[pairing] }.get(10, TimeUnit.SECONDS) ?: throw java.io.IOException()
            val direct = direct != null && listenPort != 0 && awake && Ways.directAllowed(p.mode) && p.peerAddrs.isNotEmpty()
            if (direct) { try { dialPairing(LinkWay.DIRECT, pairing); return } catch (_: Exception) {} }
            if (tor == null || !awake) throw java.io.IOException()
            dialPairing(LinkWay.TOR, pairing)
        }

        override fun post(block: () -> Unit) = this@LinkNode.post(block)
        override fun io(block: () -> Unit) { io.execute(block) }
        override fun now(): Long = nowS()
    }

    /**
     * What the desk (the sink) may send, on the link thread only: an ack for a request it holds, and a body of its own (a
     * handler's answer, encoded here like every control request; never a peer's bytes). Each returns the queued request's
     * id, or null when it couldn't be queued (no active pairing, the outbox full).
     */
    val deskOut: DeskOut = object : DeskOut {
        override fun ack(pairing: String, re: String, state: AckState): String? =
            st.pairings[pairing]?.takeIf { it.state == PairingState.ACTIVE && LinkIds.isId(re) }?.let { answer(it, re, state) }

        override fun send(pairing: String, body: LinkBody, ttl: Long): String? {
            if (body.type.control) return null
            val p = st.pairings[pairing]?.takeIf { it.state == PairingState.ACTIVE } ?: return null
            if (body.type !in LinkCaps.fromBitmap(p.caps)) return null
            return queueBody(p, body, ttl)
        }

        override fun room(pairing: String): Boolean = st.pairings[pairing]?.state == PairingState.ACTIVE &&
            LinkLimits.canQueue(st.outbox.size + 1, st.outbox.values.count { it.pairing == pairing } + 1)

        override fun now(): Long = nowS()
    }

    // The link thread -------------------------------------------------------------------------------------------------------

    private fun post(block: () -> Unit) {
        try { exec.execute { guard(block) } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun <T> call(block: () -> T): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        try {
            exec.execute {
                // Its caller stopped waiting and was told it failed ([within]): it mustn't happen later after all.
                if (f.isCancelled) return@execute
                try { f.complete(block()) } catch (x: Throwable) { f.completeExceptionally(x) }
            }
        } catch (x: java.util.concurrent.RejectedExecutionException) {
            f.completeExceptionally(x)
        }
        return f
    }

    /** Nothing thrown on the link thread escapes it: a failure is a code in the link log. */
    private inline fun guard(block: () -> Unit) {
        try { block() } catch (x: Exception) { log.add(LinkEvent.DROPPED, 0, problemCode(x)) }
    }

    /** A write of the state: false (and the link log's code) when it couldn't be written; nothing else changes then. */
    private inline fun stateful(block: () -> Unit): Boolean = try {
        block(); true
    } catch (x: app.heyrobot.core.link.LinkStateException) {
        log.add(LinkEvent.DROPPED, 0, 200 + x.problem.id)
        false
    }

    private fun newToken(): ByteArray {
        while (true) ByteArray(LinkIds.BYTES).also(random::nextBytes).takeIf { !LinkBytes.allZero(it) }?.let { return it }
    }

    companion object {
        const val PUMP_SECONDS = 30L
        /** How long a sent request waits for its ack before it is sent again (then L1's backoff). */
        const val ACK_WAIT_SECONDS = 15 * 60L
        /** Acks, net.hints and caps: a day is plenty (they are sent again when something changes). */
        const val CONTROL_TTL = 86_400L
        /** L16: a request read while :linkparse is away: tries at 2, 4, 8 and 16 seconds, and this many waiting at once. */
        const val PARSE_RETRY_S = 2L
        const val PARSE_TRIES = 4
        const val PARSE_WAITING = 16
        /** L5-fix: `mode.raise` and `mode.lower` wait 30 days for the other Robot, the longest a request may (§3.4). */
        const val MODE_TTL = LinkRequests.MAX_TTL
        /** L5-fix: a request answered `busy` or `expired` goes again after at least an hour. */
        const val REFUSED_WAIT_SECONDS = 3_600L
        const val HELLO_TTL = 86_400L
        const val ENDPOINT_WAIT_SECONDS = 20L
        const val TOR_WAIT_SECONDS = 150L
        const val IDLE_MS = 5 * 60_000L
        /** Open pairing codes at once. */
        const val MAX_CODES = 5
        /** L5-fix: in person, hellos held for one code until your photo (one per key). */
        const val MAX_HELD = 4
        /** L5: a moved hello is sent again (with backoff) for a week; then "Try again" (§2.7). */
        const val MOVE_DAYS = 7L
        /** L5: how long "Sam's Robot unpaired" stays on the person's page (§2.6). */
        const val ENDED_DAYS = 30L
        /** The name sent before you set one ("Name other Robots see" is asked when you turn Robot to Robot on). */
        const val DEFAULT_NAME = "Robot" // l10n: not text (a product name, sent to the other Robot, never shown here)
        private const val LANG = "en" // l10n: not text (control requests carry no text in a language)

        /** A failure as a number for the link log: the carriers' problem codes, never a message. */
        fun problemCode(x: Throwable): Int = when (x) {
            is PushException -> 10 + x.problem.code
            is SocksException -> 30 + x.problem.code
            is TorException -> 50 + x.problem.code
            is SessionException -> 100 + x.problem.code
            is app.heyrobot.core.link.LinkStateException -> 200 + x.problem.id
            is java.net.SocketTimeoutException -> 2
            is java.io.IOException -> 1
            else -> 3
        }
    }
}

/**
 * This node call's answer within [seconds]. Past that its caller says it failed, so work still queued on the link thread is
 * called off (work already running finishes: the link thread is never interrupted).
 */
fun <T> CompletableFuture<T>.within(seconds: Long): T = try {
    get(seconds, TimeUnit.SECONDS)
} catch (x: java.util.concurrent.TimeoutException) {
    cancel(false)
    throw x
}
