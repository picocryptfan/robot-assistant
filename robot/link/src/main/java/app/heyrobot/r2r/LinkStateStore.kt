// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.LinkPurpose
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.LinkStateProblem
import app.heyrobot.core.link.LocalAddress
import app.heyrobot.core.link.OnionKey
import app.heyrobot.core.link.OpenCode
import app.heyrobot.core.link.PairCode
import app.heyrobot.core.link.PushRoute
import app.heyrobot.core.link.RecordKey
import app.heyrobot.core.link.ReplayWindow
import app.heyrobot.core.link.Verified
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.link.requests.LinkCaps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2 to §3.7, D12; job L3): what the carriers keep, beside
// libsignal's own records, in :link's sealed state (core LinkRecords: one file per record, each sealed with a fresh key under
// robot_link_v1). Read once at :link's start, kept in memory, every change written through. Nothing here is ever shown or
// logged: a record's toString is its kind alone. Binary, read strictly (a record that doesn't read is DAMAGED, kept, and
// :link says the state is damaged, as L2's records do).
//
//   PAIRING  one per pairing: how the carriers reach the other Robot and how it reaches yours (§2.2, D6): the mode, its
//            claimed name and caps, its push address, its onion with the client authorization key it gave you, its
//            addresses on a shared network, the connection token it gave you, the tokens you gave it (current and the one
//            before), your push instance for it, the client authorization key you made for it. While your hello waits for
//            an answer (state SENT): the code you used and the sealed hello, to send again.
//   REPLAY   one per pairing: core LinkReplay's window.
//   OUTBOX   one per request waiting to go or for its ack: its padded bytes, times and tries (core LinkLimits' Backoff).
//   TOR      your onion service's key, as Tor wrote it.
//   CODE     one per pairing code you made that is still open: its token's state and its one-time routes.
//   SPOOL    a push message that arrived and waits for :link's job (the connector decrypted it; it is still the session's
//            ciphertext, or a sealed hello).
//   BLOCKED  (L5) a Robot you blocked: its identity key, the label you knew it by, when.
//   DESK     (L6) a line of the desk (core DeskEntry): what became of a request from another Robot or of one you sent.
//
// 1.5 L5, pairing and People (§2.3, §2.6, §2.7, §6.3, D8, D12), in the same PAIRING record (layout 2; layout 1, L3's, still
// reads, with L5's fields at their starting values): how you know their key (verified in person or by numbers, and when),
// who they are in your People, the preset and each capability's level, the mode change asked either way, the moved-hello
// tokens (yours, which a moved hello to you must carry, and theirs, which yours to them carries: each 16 random bytes given
// only inside the session, made again after each accepted move), a moved hello waiting for your answer, and for an ENDED
// note the other Robot's identity key (so Block still works) and when it ended. A CODE record (layout 2) may hold the key of
// a code you photographed in person while your own code waits for that Robot's hello (§2.3: the key that sorts first sends).

enum class PairingState(val code: Int) {
    /** Your hello went out to someone's code; their answer hasn't come yet. */
    SENT(1),
    /** A session both ways. */
    ACTIVE(2),
    /** L5: your Robot moved to this phone and sent its moved hello; their answer hasn't come (paused, §2.7). */
    MOVING(3),
    /** L5: their Robot says it moved: paused until you accept (its key pinned again, a new session) or keep the old one. */
    MOVED(4),
    /** L5: their Robot unpaired. A note only: no session, no keys, no routes; gone after 30 days or when you remove it. */
    ENDED(5);

    companion object {
        fun of(code: Int): PairingState? = entries.firstOrNull { it.code == code }
    }
}

/** A pairing as the carriers see it. Changed only on :link's thread (LinkNode). */
class PairingRecord(
    val id: String,
    var state: PairingState,
    var mode: LinkMode,
    val created: Long,
    /** The name the other Robot asked to be shown: a claim, never what picks a person (§2.3). Empty until it sent one. */
    var name: String,
    caps: ByteArray,
) {
    var caps: ByteArray = caps.copyOf()
    var peerPush: PushRoute? = null
    var peerOnion: OnionKey? = null
    /** The client authorization key the other Robot made for you, to reach [peerOnion]. */
    var peerOnionAuth: ByteArray? = null
    var peerAddrs: List<LocalAddress> = emptyList()
    var addrsAt: Long = 0
    /** The connection token the other Robot gave you for your next live connection to it. */
    var outToken: ByteArray? = null
    /** The tokens you gave it: the newest, and the one before (it may not have received the newest yet). */
    var inToken: ByteArray? = null
    var inPrev: ByteArray? = null
    /** Your push instance for this pairing (a random name, D6) and the address your distributor gave it. */
    var myInstance: String? = null
    var myPush: PushRoute? = null
    /** The client authorization key pair you made for this pairing: its public half authorizes it on your onion. */
    var myAuthPub: ByteArray? = null
    var myAuthPriv: ByteArray? = null
    /** SENT: the maker's code (its bytes) and your sealed hello, to send again until answered. */
    var code: ByteArray? = null
    var hello: ByteArray? = null
    var helloTries: Int = 0
    var helloNext: Long = 0
    /** When your routes (net.hint) were last queued for it; 0 never. */
    var hintAt: Long = 0

    // 1.5 L5 ------------------------------------------------------------------------------------------------------------
    var verified: Verified = Verified.NO
    /** Unix seconds; 0 while [verified] is NO. */
    var verifiedAt: Long = 0
    /** Who this is in your People (a People id): you said so ("Who is this"), never the name they sent. */
    var person: String? = null
    /** The preset you picked; until then "Someone else"'s levels apply (D8, §4.6). */
    var preset: LinkPreset? = null
    var levels: Map<LinkCapability, LinkLevel> = LinkLevels.of(LinkPreset.SOMEONE_ELSE)
    /** SENT from a code you photographed: Verified in person once their Robot answers (its key is the one scanned). */
    var scanned: Boolean = false
    /** Yours: what a moved hello to you for this pairing must carry. Theirs: what yours to them carries. 16 bytes each. */
    var movedIn: ByteArray? = null
    var movedOut: ByteArray? = null
    /** The id of the `mode.lower` you sent, until they answer; the id of theirs, until you do (§2.2). */
    var lowerAsked: String? = null
    var lowerOffer: String? = null
    /** When they last kept Anonymous after you asked; 0 never. */
    var lowerDeclinedAt: Long = 0
    /** MOVED: their moved hello as it was opened (core PairHello's bytes), until you answer. */
    var moved: ByteArray? = null
    /** The identity key of a move you declined: its hellos are dropped from then on. */
    var movedDeclined: ByteArray? = null
    /** When MOVING, MOVED or ENDED began (Unix seconds). */
    var since: Long = 0
    /** ENDED: their identity key, kept for Block. */
    var peerIdentity: ByteArray? = null

    init {
        require(LinkIds.isId(id) && this.caps.size == LinkCaps.BITMAP)
    }

    override fun toString(): String = "PairingRecord(state=$state, mode=$mode)" // l10n: not text (toString for logs and tests; nothing of the pairing)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), 2)

        fun encode(p: PairingRecord): ByteArray = Bin.write { w ->
            w.write(MAGIC); w.id(p.id); w.writeByte(p.state.code); w.writeByte(p.mode.code); w.writeLong(p.created)
            w.text(p.name, 160); w.write(p.caps)
            w.opt(p.peerPush) { w.push(it) }
            w.opt(p.peerOnion) { w.write(it.key); w.write(p.peerOnionAuth!!) }
            w.writeByte(p.peerAddrs.size); for (a in p.peerAddrs) w.local(a); w.writeLong(p.addrsAt)
            w.bytesOpt(p.outToken, 16); w.bytesOpt(p.inToken, 16); w.bytesOpt(p.inPrev, 16)
            w.opt(p.myInstance) { w.text(it, 64) }
            w.opt(p.myPush) { w.push(it) }
            w.opt(p.myAuthPub) { w.write(it); w.write(p.myAuthPriv!!) }
            w.opt(p.code) { w.blob(it, PairCode.MAX_BYTES) }
            w.opt(p.hello) { w.blob(it, app.heyrobot.core.link.PairHello.SEALED_MAX) }
            w.writeShort(p.helloTries); w.writeLong(p.helloNext); w.writeLong(p.hintAt)
            // Layout 2 (L5).
            w.writeByte(p.verified.code); w.writeLong(p.verifiedAt)
            w.opt(p.person) { w.text(it, 64) }
            w.writeByte(p.preset?.let(LinkLevels::presetCode) ?: 0)
            w.blob(LinkLevels.encode(p.levels), 64)
            w.writeBoolean(p.scanned)
            w.bytesOpt(p.movedIn, 16); w.bytesOpt(p.movedOut, 16)
            w.opt(p.lowerAsked) { w.id(it) }; w.opt(p.lowerOffer) { w.id(it) }; w.writeLong(p.lowerDeclinedAt)
            w.opt(p.moved) { w.blob(it, app.heyrobot.core.link.PairHello.MAX_BYTES) }
            w.bytesOpt(p.movedDeclined, 33)
            w.writeLong(p.since)
            w.bytesOpt(p.peerIdentity, 33)
        }

        fun decode(b: ByteArray): PairingRecord = Bin.read(b) { r ->
            val layout = r.layout(MAGIC, 2)
            val p = PairingRecord(r.id(), PairingState.of(r.readUnsignedByte()) ?: Bin.bad(), LinkMode.of(r.readUnsignedByte()) ?: Bin.bad(),
                r.readLong(), r.text(160), r.bytes(LinkCaps.BITMAP))
            if (r.flag()) p.peerPush = r.push()
            if (r.flag()) { p.peerOnion = OnionKey(r.bytes(32)); p.peerOnionAuth = r.bytes(32).also { if (!OnionKey.authOk(it)) Bin.bad() } }
            val n = r.readUnsignedByte(); if (n > 8) Bin.bad()
            p.peerAddrs = List(n) { r.local() }; p.addrsAt = r.readLong()
            p.outToken = r.bytesOpt(16); p.inToken = r.bytesOpt(16); p.inPrev = r.bytesOpt(16)
            if (r.flag()) p.myInstance = r.text(64)
            if (r.flag()) p.myPush = r.push()
            if (r.flag()) { p.myAuthPub = r.bytes(32); p.myAuthPriv = r.bytes(32) }
            if (r.flag()) p.code = r.blob(PairCode.MAX_BYTES)
            if (r.flag()) p.hello = r.blob(app.heyrobot.core.link.PairHello.SEALED_MAX)
            p.helloTries = r.readUnsignedShort(); p.helloNext = r.readLong(); p.hintAt = r.readLong()
            if (layout >= 2) {
                p.verified = Verified.of(r.readUnsignedByte()) ?: Bin.bad(); p.verifiedAt = r.readLong()
                if ((p.verified == Verified.NO) != (p.verifiedAt == 0L)) Bin.bad()
                if (r.flag()) p.person = r.text(64).also { if (!PeopleStore.validId(it)) Bin.bad() }
                p.preset = r.readUnsignedByte().let { c -> if (c == 0) null else LinkLevels.preset(c) ?: Bin.bad() }
                p.levels = LinkLevels.decode(r.blob(64)) ?: Bin.bad()
                p.scanned = r.readBoolean()
                p.movedIn = r.bytesOpt(16); p.movedOut = r.bytesOpt(16)
                if (r.flag()) p.lowerAsked = r.id()
                if (r.flag()) p.lowerOffer = r.id()
                p.lowerDeclinedAt = r.readLong()
                if (r.flag()) p.moved = r.blob(app.heyrobot.core.link.PairHello.MAX_BYTES)
                p.movedDeclined = r.bytesOpt(33)
                p.since = r.readLong()
                p.peerIdentity = r.bytesOpt(33)
            }
            p
        }
    }
}

/** A request in the outbox: its padded bytes, how long it may wait, and how its tries went. */
class OutboxItem(
    val id: String,
    val pairing: String,
    /** Its type's code (core RequestType.code): only to know whether an ack answers it, and for the link log. */
    val type: Int,
    val at: Long,
    val ttl: Long,
    bytes: ByteArray,
) {
    private val b = bytes.copyOf()
    val bytes: ByteArray get() = b.copyOf()
    val size: Int get() = b.size
    var attempts: Int = 0
    var nextAt: Long = 0
    /** A carrier took it at least once; it now waits for its ack (or is sent again when [nextAt] comes). */
    var sent: Boolean = false

    val expiresAt: Long get() = at + ttl

    init {
        require(LinkIds.isId(id) && LinkIds.isId(pairing) && type in 1..255 && ttl > 0)
    }

    override fun toString(): String = "OutboxItem(type=$type, attempts=$attempts, sent=$sent)" // l10n: not text (toString for logs and tests)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'O'.code.toByte(), 1)

        fun encode(o: OutboxItem): ByteArray = Bin.write { w ->
            w.write(MAGIC); w.id(o.id); w.id(o.pairing); w.writeShort(o.type); w.writeLong(o.at); w.writeLong(o.ttl)
            w.writeShort(minOf(o.attempts, 0xFFFF)); w.writeLong(o.nextAt); w.writeBoolean(o.sent); w.blob(o.b, MAX_BYTES)
        }

        fun decode(b: ByteArray): OutboxItem = Bin.read(b) { r ->
            r.magic(MAGIC)
            val id = r.id(); val pairing = r.id(); val type = r.readUnsignedShort(); val at = r.readLong(); val ttl = r.readLong()
            val attempts = r.readUnsignedShort(); val next = r.readLong(); val sent = r.readBoolean()
            OutboxItem(id, pairing, type, at, ttl, r.blob(MAX_BYTES)).also { it.attempts = attempts; it.nextAt = next; it.sent = sent }
        }

        /** A live request's padded plaintext at most (core LinkRequests.LIVE). */
        const val MAX_BYTES = 32 * 1024
    }
}

/** Your onion service's key as Tor gave it ("ED25519-V3:…") and the onion it is. */
class TorRecord(val keyBlob: String, val onion: OnionKey) {
    override fun toString(): String = "TorRecord" // l10n: not text (toString for logs and tests)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1)
        fun encode(t: TorRecord): ByteArray = Bin.write { w -> w.write(MAGIC); w.text(t.keyBlob, 128); w.write(t.onion.key) }
        fun decode(b: ByteArray): TorRecord = Bin.read(b) { r ->
            r.magic(MAGIC)
            TorRecord(r.text(128).also { if (!TorCommands.keyBlobOk(it)) Bin.bad() }, OnionKey(r.bytes(32)))
        }
    }
}

/** A pairing code you made: its token's state (core OpenCode), its mode, and the one-time routes it named. */
class CodeRecord(
    val id: String,
    var open: OpenCode,
    val mode: LinkMode,
    /** Shown in person (a QR code): a Private one carries your address on this network too. */
    val inPerson: Boolean,
    val created: Long,
) {
    var myInstance: String? = null
    var myPush: PushRoute? = null
    /** Its one-time onion service: its slot (TorPaths.service), Tor's key and the onion. */
    var onionSlot: Int = 0
    var onionKeyBlob: String? = null
    var onion: OnionKey? = null
    var local: LocalAddress? = null
    /**
     * L5, in person: the identity key of the code you photographed while this one was shown, when that Robot's key sorts
     * first (it sends the hello, §2.3). A hello to this code from any other key is refused.
     */
    var expect: ByteArray? = null

    override fun toString(): String = "CodeRecord(state=${open.state}, mode=$mode)" // l10n: not text (toString for logs and tests)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte(), 2)

        fun encode(c: CodeRecord): ByteArray = Bin.write { w ->
            w.write(MAGIC); w.id(c.id); w.write(c.open.token); w.writeLong(c.open.expires); w.writeByte(c.open.wrong)
            w.writeByte(c.open.state.ordinal); w.writeByte(c.mode.code); w.writeBoolean(c.inPerson); w.writeLong(c.created)
            w.opt(c.myInstance) { w.text(it, 64) }
            w.opt(c.myPush) { w.push(it) }
            w.writeByte(c.onionSlot)
            w.opt(c.onionKeyBlob) { w.text(it, 128); w.write(c.onion!!.key) }
            w.opt(c.local) { w.local(it) }
            w.bytesOpt(c.expect, 33)
        }

        fun decode(b: ByteArray): CodeRecord = Bin.read(b) { r ->
            val layout = r.layout(MAGIC, 2)
            val id = r.id(); val token = r.bytes(16); val expires = r.readLong(); val wrong = r.readUnsignedByte()
            val state = OpenCode.State.entries.getOrNull(r.readUnsignedByte()) ?: Bin.bad()
            val c = CodeRecord(id, OpenCode.restore(token, expires, wrong, state), LinkMode.of(r.readUnsignedByte()) ?: Bin.bad(), r.readBoolean(), r.readLong())
            if (r.flag()) c.myInstance = r.text(64)
            if (r.flag()) c.myPush = r.push()
            c.onionSlot = r.readUnsignedByte().also { if (it > TorPaths.MAX_SERVICES) Bin.bad() }
            if (r.flag()) { c.onionKeyBlob = r.text(128).also { if (!TorCommands.keyBlobOk(it)) Bin.bad() }; c.onion = OnionKey(r.bytes(32)) }
            if (r.flag()) c.local = r.local()
            if (layout >= 2) c.expect = r.bytesOpt(33)
            c
        }
    }
}

/** L5: a Robot you blocked (§2.6): its identity key (33 bytes), the label you knew it by, when (Unix seconds). */
class BlockRecord(val id: String, identity: ByteArray, val label: String, val at: Long) {
    private val k = identity.copyOf()
    val identity: ByteArray get() = k.copyOf()

    init {
        require(LinkIds.isId(id) && k.size == 33 && (label.isEmpty() || app.heyrobot.core.link.LinkText.ok(label, app.heyrobot.core.link.BlockedView.BLOCK_LABEL)))
    }

    fun blocks(key: ByteArray): Boolean = LinkBytes.same(k, key)

    override fun toString(): String = "BlockRecord" // l10n: not text (toString for logs and tests)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 1)
        fun encode(b: BlockRecord): ByteArray = Bin.write { w -> w.write(MAGIC); w.id(b.id); w.write(b.k); w.text(b.label, 320); w.writeLong(b.at) }
        fun decode(b: ByteArray): BlockRecord = Bin.read(b) { r -> r.magic(MAGIC); BlockRecord(r.id(), r.bytes(33), r.text(320), r.readLong()) }
    }
}

/** A push message waiting for :link's job: the instance it came to, when, and its bytes. */
class SpoolItem(val id: String, val instance: String, val at: Long, bytes: ByteArray) {
    private val b = bytes.copyOf()
    val bytes: ByteArray get() = b.copyOf()

    override fun toString(): String = "SpoolItem" // l10n: not text (toString for logs and tests)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 1)
        fun encode(s: SpoolItem): ByteArray = Bin.write { w -> w.write(MAGIC); w.id(s.id); w.text(s.instance, 64); w.writeLong(s.at); w.blob(s.b, 4_096) }
        fun decode(b: ByteArray): SpoolItem = Bin.read(b) { r -> r.magic(MAGIC); SpoolItem(r.id(), r.text(64), r.readLong(), r.blob(4_096)) }
    }
}

/**
 * The carriers' records over [records] (robot_link_v1), read once ([load]) and written through. Every call may throw
 * [LinkStateException]. Used on :link's thread only.
 */
class LinkStateStore(private val records: LinkRecords) {
    val pairings = LinkedHashMap<String, PairingRecord>()
    val replay = HashMap<String, ReplayWindow>()
    val listRate = HashMap<String, ByteArray>()
    /** F73: inbox items waiting per pairing, as the desk last counted them. */
    val inboxShare = HashMap<String, Int>()
    val outbox = LinkedHashMap<String, OutboxItem>()
    val codes = LinkedHashMap<String, CodeRecord>()
    val spool = LinkedHashMap<String, SpoolItem>()
    val blocked = LinkedHashMap<String, BlockRecord>()
    /** L6: the desk's lines (core DeskEntry), by request id (a "Not now"'s own id). */
    val desk = LinkedHashMap<String, app.heyrobot.core.link.DeskEntry>()
    /** L7: the plans shared with other Robots (core LinkPlan), by LinkPlan.key (pairing and plan id). */
    val plans = LinkedHashMap<String, app.heyrobot.core.link.LinkPlan>()
    /** L8: files between Robots (TransferRecord), by this Robot's own id for each. */
    val transfers = LinkedHashMap<String, TransferRecord>()
    /** 1.5: where each shared list stands (core ListShareRec), by ListShareRec.key. */
    val listShares = LinkedHashMap<String, app.heyrobot.core.link.ListShareRec>()
    /** L11: shared folders (core FolderRec), by FolderRec.key. */
    val folders = LinkedHashMap<String, app.heyrobot.core.link.FolderRec>()
    /** L11: group plans you organize (core LinkGroup), by id. */
    val groups = LinkedHashMap<String, app.heyrobot.core.link.LinkGroup>()
    /** L11: the organizer's word on who's in a group plan you're in (core GroupSeen), by GroupSeen.key. */
    val groupSeen = LinkedHashMap<String, app.heyrobot.core.link.GroupSeen>()
    var tor: TorRecord? = null
        private set

    /** Reads every carrier record (the map L2's LinkRecords.readAll gives, or a fresh read). */
    fun load(all: Map<RecordKey, ByteArray> = records.readAll()) {
        for ((k, v) in all) try {
            when (k.purpose) {
                LinkPurpose.PAIRING -> PairingRecord.decode(v).also { if (it.id != k.id) Bin.bad() }.let { pairings[it.id] = it }
                LinkPurpose.REPLAY -> replay[k.id] = ReplayWindow.decode(v) ?: Bin.bad()
                LinkPurpose.LIST_RATE -> listRate[k.id] = v
                LinkPurpose.INBOX_SHARE -> inboxShare.putAll(InboxShare.decode(v))
                LinkPurpose.OUTBOX -> OutboxItem.decode(v).also { if (it.id != k.id) Bin.bad() }.let { outbox[it.id] = it }
                LinkPurpose.CODE -> CodeRecord.decode(v).also { if (it.id != k.id) Bin.bad() }.let { codes[it.id] = it }
                LinkPurpose.SPOOL -> SpoolItem.decode(v).also { if (it.id != k.id) Bin.bad() }.let { spool[it.id] = it }
                LinkPurpose.TOR -> tor = TorRecord.decode(v)
                LinkPurpose.BLOCKED -> BlockRecord.decode(v).also { if (it.id != k.id) Bin.bad() }.let { blocked[it.id] = it }
                LinkPurpose.DESK -> (app.heyrobot.core.link.DeskEntry.decode(v) ?: Bin.bad()).also { if (it.id != k.id) Bin.bad() }.let { desk[it.id] = it }
                LinkPurpose.PLAN -> (app.heyrobot.core.link.LinkPlan.decode(v) ?: Bin.bad()).also { if (app.heyrobot.core.link.LinkPlan.key(it.pairing, it.id) != k.id) Bin.bad() }.let { plans[k.id] = it }
                LinkPurpose.TRANSFER -> TransferRecord.decode(v).also { if (it.id != k.id) Bin.bad() }.let { transfers[it.id] = it }
                LinkPurpose.LIST_SHARE -> (app.heyrobot.core.link.ListShareRec.decode(v) ?: Bin.bad()).also { if (app.heyrobot.core.link.ListShareRec.key(it.pairing, it.list) != k.id) Bin.bad() }.let { listShares[k.id] = it }
                LinkPurpose.FOLDER -> (app.heyrobot.core.link.FolderRec.decode(v) ?: Bin.bad()).also { if (app.heyrobot.core.link.FolderRec.key(it.pairing, it.fold) != k.id) Bin.bad() }.let { folders[k.id] = it }
                LinkPurpose.GROUP -> (app.heyrobot.core.link.LinkGroup.decode(v) ?: Bin.bad()).also { if (it.id != k.id) Bin.bad() }.let { groups[k.id] = it }
                LinkPurpose.GROUP_SEEN -> (app.heyrobot.core.link.GroupSeen.decode(v) ?: Bin.bad()).also { if (app.heyrobot.core.link.GroupSeen.key(it.pairing, it.grp) != k.id) Bin.bad() }.let { groupSeen[k.id] = it }
                else -> {}
            }
        } catch (_: BadRecord) {
            throw LinkStateException(LinkStateProblem.DAMAGED)
        } catch (_: RuntimeException) {
            throw LinkStateException(LinkStateProblem.DAMAGED)
        }
    }

    fun put(p: PairingRecord) { records.write(RecordKey(LinkPurpose.PAIRING, p.id), PairingRecord.encode(p)); pairings[p.id] = p }
    fun putReplay(pairing: String, w: ReplayWindow) { records.write(RecordKey(LinkPurpose.REPLAY, pairing), w.encode()); replay[pairing] = w }
    fun putInboxShare(share: Map<String, Int>) {
        val key = RecordKey(LinkPurpose.INBOX_SHARE, RecordKey.ZERO)
        if (share.isEmpty()) records.delete(key) else records.write(key, InboxShare.encode(share))
        inboxShare.clear(); inboxShare.putAll(share)
    }
    fun putListRate(pairing: String, b: ByteArray) { records.write(RecordKey(LinkPurpose.LIST_RATE, pairing), b); listRate[pairing] = b }
    fun put(o: OutboxItem) { records.write(RecordKey(LinkPurpose.OUTBOX, o.id), OutboxItem.encode(o)); outbox[o.id] = o }
    fun put(c: CodeRecord) { records.write(RecordKey(LinkPurpose.CODE, c.id), CodeRecord.encode(c)); codes[c.id] = c }
    fun put(s: SpoolItem) { records.write(RecordKey(LinkPurpose.SPOOL, s.id), SpoolItem.encode(s)); spool[s.id] = s }
    fun put(b: BlockRecord) { records.write(RecordKey(LinkPurpose.BLOCKED, b.id), BlockRecord.encode(b)); blocked[b.id] = b }
    fun put(e: app.heyrobot.core.link.DeskEntry) { records.write(RecordKey(LinkPurpose.DESK, e.id), app.heyrobot.core.link.DeskEntry.encode(e)); desk[e.id] = e }
    fun put(p: app.heyrobot.core.link.LinkPlan) = app.heyrobot.core.link.LinkPlan.key(p.pairing, p.id).let { k -> records.write(RecordKey(LinkPurpose.PLAN, k), app.heyrobot.core.link.LinkPlan.encode(p)); plans[k] = p }
    fun put(t: TransferRecord) { records.write(RecordKey(LinkPurpose.TRANSFER, t.id), TransferRecord.encode(t)); transfers[t.id] = t }
    fun deleteTransfer(id: String) { records.delete(RecordKey(LinkPurpose.TRANSFER, id)); transfers.remove(id) }
    fun put(r: app.heyrobot.core.link.ListShareRec) = app.heyrobot.core.link.ListShareRec.key(r.pairing, r.list).let { k -> records.write(RecordKey(LinkPurpose.LIST_SHARE, k), r.encode()); listShares[k] = r }
    fun put(f: app.heyrobot.core.link.FolderRec) = app.heyrobot.core.link.FolderRec.key(f.pairing, f.fold).let { k -> records.write(RecordKey(LinkPurpose.FOLDER, k), app.heyrobot.core.link.FolderRec.encode(f)); folders[k] = f }
    fun deleteFolder(f: app.heyrobot.core.link.FolderRec) = app.heyrobot.core.link.FolderRec.key(f.pairing, f.fold).let { k -> records.delete(RecordKey(LinkPurpose.FOLDER, k)); folders.remove(k) }
    fun put(g: app.heyrobot.core.link.LinkGroup) { records.write(RecordKey(LinkPurpose.GROUP, g.id), app.heyrobot.core.link.LinkGroup.encode(g)); groups[g.id] = g }
    fun deleteGroup(id: String) { records.delete(RecordKey(LinkPurpose.GROUP, id)); groups.remove(id) }
    fun put(s: app.heyrobot.core.link.GroupSeen) = app.heyrobot.core.link.GroupSeen.key(s.pairing, s.grp).let { k -> records.write(RecordKey(LinkPurpose.GROUP_SEEN, k), app.heyrobot.core.link.GroupSeen.encode(s)); groupSeen[k] = s }
    fun deleteSeen(s: app.heyrobot.core.link.GroupSeen) = app.heyrobot.core.link.GroupSeen.key(s.pairing, s.grp).let { k -> records.delete(RecordKey(LinkPurpose.GROUP_SEEN, k)); groupSeen.remove(k) }
    fun putTor(t: TorRecord) { records.write(RecordKey(LinkPurpose.TOR, RecordKey.ZERO), TorRecord.encode(t)); tor = t }

    fun deletePairing(id: String) {
        records.delete(RecordKey(LinkPurpose.PAIRING, id)); pairings.remove(id)
        records.delete(RecordKey(LinkPurpose.REPLAY, id)); replay.remove(id)
        records.delete(RecordKey(LinkPurpose.LIST_RATE, id)); listRate.remove(id)
        for (o in outbox.values.filter { it.pairing == id }) deleteOutbox(o.id)
    }
    /** An ENDED note keeps its record: its replay window and outbox go. */
    fun deleteTraffic(id: String) {
        records.delete(RecordKey(LinkPurpose.REPLAY, id)); replay.remove(id)
        records.delete(RecordKey(LinkPurpose.LIST_RATE, id)); listRate.remove(id)
        for (o in outbox.values.filter { it.pairing == id }) deleteOutbox(o.id)
    }
    fun deleteOutbox(id: String) { records.delete(RecordKey(LinkPurpose.OUTBOX, id)); outbox.remove(id) }
    fun deleteCode(id: String) { records.delete(RecordKey(LinkPurpose.CODE, id)); codes.remove(id) }
    fun deleteSpool(id: String) { records.delete(RecordKey(LinkPurpose.SPOOL, id)); spool.remove(id) }
    fun deleteBlock(id: String) { records.delete(RecordKey(LinkPurpose.BLOCKED, id)); blocked.remove(id) }
    fun deleteListShare(r: app.heyrobot.core.link.ListShareRec) = app.heyrobot.core.link.ListShareRec.key(r.pairing, r.list).let { k -> records.delete(RecordKey(LinkPurpose.LIST_SHARE, k)); listShares.remove(k) }
    fun deleteDesk(id: String) { records.delete(RecordKey(LinkPurpose.DESK, id)); desk.remove(id) }
    fun deletePlan(p: app.heyrobot.core.link.LinkPlan) = app.heyrobot.core.link.LinkPlan.key(p.pairing, p.id).let { k -> records.delete(RecordKey(LinkPurpose.PLAN, k)); plans.remove(k) }
}

/** A record that doesn't read. */
internal class BadRecord : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/** Strict binary records: DataOutputStream's big-endian numbers, lengths capped, nothing left over. */
/** F73: "RIS" 1 | count u16 | each person: pairing id 16, items waiting u16. */
internal object InboxShare {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 1)

    fun encode(share: Map<String, Int>): ByteArray {
        val b = java.nio.ByteBuffer.allocate(MAGIC.size + 2 + share.size * (LinkIds.BYTES + 2)).put(MAGIC).putShort(share.size.toShort())
        for ((pairing, n) in share) b.put(LinkBytes.unhex(pairing, LinkIds.BYTES)!!).putShort(n.toShort())
        return b.array()
    }

    fun decode(v: ByteArray): Map<String, Int> {
        val b = java.nio.ByteBuffer.wrap(v)
        val magic = ByteArray(MAGIC.size).also { b.get(it) }
        if (!magic.contentEquals(MAGIC)) Bin.bad()
        val n = b.short.toInt() and 0xFFFF
        if (b.remaining() != n * (LinkIds.BYTES + 2)) Bin.bad()
        return (0 until n).associate { LinkBytes.hex(ByteArray(LinkIds.BYTES).also { p -> b.get(p) }) to (b.short.toInt() and 0xFFFF) }
    }
}

internal object Bin {
    fun bad(): Nothing = throw BadRecord()

    fun write(block: (W) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(256)
        block(W(bytes))
        return bytes.toByteArray()
    }

    fun <T> read(b: ByteArray, block: (R) -> T): T {
        val input = ByteArrayInputStream(b)
        val r = R(input)
        val v = try { block(r) } catch (_: EOFException) { bad() } catch (_: IllegalArgumentException) { bad() }
        if (input.available() != 0) bad()
        return v
    }

    class W(out: ByteArrayOutputStream) : DataOutputStream(out) {
        fun id(id: String) = write(LinkBytes.unhex(id, LinkIds.BYTES)!!)
        fun text(s: String, maxBytes: Int) { val b = s.toByteArray(Charsets.UTF_8); require(b.size <= maxBytes); writeShort(b.size); write(b) }
        fun blob(b: ByteArray, max: Int) { require(b.size <= max); writeInt(b.size); write(b) }
        fun <T> opt(v: T?, block: (T) -> Unit) { writeBoolean(v != null); if (v != null) block(v) }
        fun bytesOpt(b: ByteArray?, n: Int) { writeBoolean(b != null); if (b != null) { require(b.size == n); write(b) } }
        fun push(p: PushRoute) { text(p.endpoint, PushRoute.MAX_ENDPOINT); write(p.p256dh); write(p.auth) }
        fun local(a: LocalAddress) { val x = a.address; writeByte(x.size); write(x); writeShort(a.port) }
    }

    class R(input: ByteArrayInputStream) : DataInputStream(input) {
        fun magic(m: ByteArray) { if (!bytes(m.size).contentEquals(m)) bad() }
        /** [m]'s three letters, then its layout: 1 to [newest] (an older layout reads with the newer fields at their starts). */
        fun layout(m: ByteArray, newest: Int): Int {
            val got = bytes(m.size)
            if (!got.copyOf(m.size - 1).contentEquals(m.copyOf(m.size - 1))) bad()
            val v = got[m.size - 1].toInt() and 0xFF
            if (v !in 1..newest) bad()
            return v
        }
        fun id(): String = LinkBytes.hex(bytes(LinkIds.BYTES)).also { if (!LinkIds.isId(it)) bad() }
        fun bytes(n: Int): ByteArray = ByteArray(n).also { readFully(it) }
        fun flag(): Boolean = when (readUnsignedByte()) { 0 -> false; 1 -> true; else -> bad() }
        fun text(maxBytes: Int): String {
            val n = readUnsignedShort()
            if (n > maxBytes) bad()
            return LinkBytes.utf8(bytes(n)) ?: bad()
        }
        fun blob(max: Int): ByteArray {
            val n = readInt()
            if (n < 0 || n > max) bad()
            return bytes(n)
        }
        fun bytesOpt(n: Int): ByteArray? = if (flag()) bytes(n) else null
        fun push(): PushRoute {
            val e = text(PushRoute.MAX_ENDPOINT)
            val k = bytes(PushRoute.KEY); val a = bytes(PushRoute.AUTH)
            if (!PushRoute.valid(e, k, a)) bad()
            return PushRoute(e, k, a)
        }
        fun local(): LocalAddress {
            val n = readUnsignedByte()
            if (n != 4 && n != 16) bad()
            val a = bytes(n); val port = readUnsignedShort()
            if (!LocalAddress.valid(a, port)) bad()
            return LocalAddress(a, port)
        }
    }
}
