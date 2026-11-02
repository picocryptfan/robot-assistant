// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkPurpose
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.link.RecordKey
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.4 and §2.5, R0 §9 item 7; job L2): libsignal's stores,
// kept in this process's memory (read once at :link's start) and written through to :link's sealed records
// (core/link/LinkState.kt, robot_link_v1). Five of libsignal's six: identity, one-time prekeys (none: there is no server
// to hand them out, so that store holds nothing and refuses every id), signed prekeys, Kyber prekeys and sessions. Sender
// keys are libsignal's group messages, which Robot to Robot doesn't use (group plans are fanned out by the organizer's
// Robot over the pairwise sessions, §4.1), so there is no sender key store and no group cipher.
//
// Every change happens inside [transaction]: one libsignal call (a session started, a message decrypted or encrypted)
// makes several writes (a session, a Kyber prekey used, an identity), which collect here and reach storage only when the
// call succeeds, all or nothing; a failed call (a tampered message, a wrong key) leaves the store, in memory and on
// storage, as it was. Writes go first and deletions last, so a one-time Kyber prekey is gone only once the session it
// started is on storage (a crash between the two leaves the key, never a pairing without its session). Memory follows
// storage write by write, so a write that fails part way leaves the two the same and the call failed.
//
// Identities are pinned, never learned: a paired Robot's identity key is set by the pairing ([pin], L5, after the code or
// the hello was checked), and a session with any other key is refused (isTrustedIdentity). libsignal never replaces a
// pinned identity on its own; a new one (a "moved" hello) is pinned again only after you accept it (L5).

/** Each paired Robot's libsignal address: its pairing id (32 hex digits, LinkIds), device 1. */
object LinkAddress {
    const val DEVICE = 1

    /**
     * Your own side of every session. libsignal 0.105 binds both addresses into each message only when both are Signal
     * service ids (R0 §3.2, item 24); these aren't, so the name is a constant here. Whether pairings get UUID-form names
     * for that binding (38 bytes a message) is L3's call: change [of] and this together.
     */
    val SELF = SignalProtocolAddress("self", DEVICE) // l10n: not text (libsignal's address name)

    fun of(pairing: String): SignalProtocolAddress {
        require(LinkIds.isId(pairing))
        return SignalProtocolAddress(pairing, DEVICE)
    }

    /** The pairing an address stands for; anything else (another device, another name) is refused. */
    fun pairing(address: SignalProtocolAddress): String {
        require(address.deviceId == DEVICE && LinkIds.isId(address.name))
        return address.name
    }
}

/**
 * libsignal's stores over [records], starting from [loaded] (every record but your identity, as read at :link's start).
 * Changes only inside [transaction]; a write outside one is refused.
 */
internal class LinkProtocolStore(
    private val records: LinkRecords,
    private val identity: IdentityKeyPair,
    private val registrationId: Int,
    loaded: Map<RecordKey, ByteArray>,
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {
    // Only libsignal's own records: the carriers' (L3: pairings, replay windows, the outbox and the rest) are LinkState's.
    private val committed = HashMap<RecordKey, ByteArray>().apply { loaded.forEach { (k, v) -> if (k.purpose in PROTOCOL) put(k, v.copyOf()) } }
    /** This transaction's changes, in order; null is a deletion. */
    private var pending: LinkedHashMap<RecordKey, ByteArray?>? = null

    private fun get(k: RecordKey): ByteArray? {
        pending?.let { p -> if (p.containsKey(k)) return p[k] }
        return committed[k]
    }

    private fun put(k: RecordKey, v: ByteArray?) {
        val p = checkNotNull(pending) { "outside a transaction" } // l10n: not text (a programming error)
        p[k] = v
    }

    private fun keysOf(purpose: LinkPurpose): Set<RecordKey> {
        val all = committed.keys.filterTo(HashSet()) { it.purpose == purpose }
        pending?.forEach { (k, v) -> if (k.purpose == purpose) { if (v == null) all -= k else all += k } }
        return all
    }

    /** Runs [block] as one change: committed to storage if it returns, dropped whole if it throws. One at a time. */
    fun <T> transaction(block: () -> T): T = synchronized(this) {
        check(pending == null)
        val p = LinkedHashMap<RecordKey, ByteArray?>()
        pending = p
        try {
            val r = block()
            pending = null
            commit(p)
            r
        } finally {
            pending = null
        }
    }

    private fun commit(p: Map<RecordKey, ByteArray?>) {
        for ((k, v) in p) if (v != null) {
            records.write(k, v)
            committed[k] = v
        }
        for ((k, v) in p) if (v == null && committed.containsKey(k)) {
            records.delete(k)
            committed.remove(k)
        }
    }

    // Identity ----------------------------------------------------------------------------------------------------------

    override fun getIdentityKeyPair(): IdentityKeyPair = identity

    override fun getLocalRegistrationId(): Int = registrationId

    /** Pins [key] as [pairing]'s identity (the pairing checked it). Inside a transaction. */
    fun pin(pairing: String, key: IdentityKey) = put(peer(pairing), PeerRecord.encode(key))

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val pinned = getIdentity(address)
        // libsignal asks isTrustedIdentity first, so only the pinned key ever reaches here; anything else fails the call.
        check(pinned != null && pinned == identityKey) { "identity not pinned" } // l10n: not text (a programming error)
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean =
        getIdentity(address) == identityKey

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = get(peer(LinkAddress.pairing(address)))?.let(PeerRecord::decode)

    // One-time EC prekeys: none (no server hands them out, §2.5). ----------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = throw InvalidKeyIdException("no one-time prekeys") // l10n: not text (libsignal's exception)

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = throw UnsupportedOperationException()

    override fun containsPreKey(preKeyId: Int): Boolean = false

    override fun removePreKey(preKeyId: Int) {}

    // Signed prekeys ------------------------------------------------------------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        get(signed(signedPreKeyId))?.let(::SignedPreKeyRecord) ?: throw InvalidKeyIdException("no such signed prekey") // l10n: not text (libsignal's exception)

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = keysOf(LinkPurpose.SIGNED_PREKEY).mapNotNull { get(it)?.let(::SignedPreKeyRecord) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = put(signed(signedPreKeyId), record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = get(signed(signedPreKeyId)) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = put(signed(signedPreKeyId), null)

    // Kyber prekeys: one-time (R0 item 7), unlike libsignal's in-memory store, which keeps every one as last-resort. --------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        get(kyber(kyberPreKeyId))?.let(::KyberPreKeyRecord) ?: throw InvalidKeyIdException("no such Kyber prekey") // l10n: not text (libsignal's exception)

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = keysOf(LinkPurpose.KYBER_PREKEY).mapNotNull { get(it)?.let(::KyberPreKeyRecord) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = put(kyber(kyberPreKeyId), record.serialize())

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = get(kyber(kyberPreKeyId)) != null

    /**
     * Used once: deleted with this transaction's commit, after the session it started is stored. A second use can't
     * happen: the key is gone, so libsignal finds no such Kyber prekey (and a replayed first message matches the stored
     * session's base key first).
     */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) = put(kyber(kyberPreKeyId), null)

    /** A Kyber prekey is removed (unused and expired). Inside a transaction. */
    fun removeKyberPreKey(kyberPreKeyId: Int) = put(kyber(kyberPreKeyId), null)

    // Sessions -------------------------------------------------------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? = get(session(LinkAddress.pairing(address)))?.let(::SessionRecord)

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a -> loadSession(a) ?: throw NoSessionException(a, "no session") } // l10n: not text (libsignal's exception)

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = put(session(LinkAddress.pairing(address)), record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean = get(session(LinkAddress.pairing(address))) != null

    override fun deleteSession(address: SignalProtocolAddress) = put(session(LinkAddress.pairing(address)), null)

    override fun deleteAllSessions(name: String) = deleteSession(SignalProtocolAddress(name, LinkAddress.DEVICE))

    /** Unpair or block: [pairing]'s session and pinned identity go. Inside a transaction. */
    fun forget(pairing: String) {
        put(session(pairing), null)
        put(peer(pairing), null)
    }

    /** The pairings with a session, for :link's start (tests). */
    fun pairings(): List<String> = keysOf(LinkPurpose.SESSION).map { it.id }.sorted()

    private companion object {
        val PROTOCOL = setOf(LinkPurpose.PEER, LinkPurpose.SIGNED_PREKEY, LinkPurpose.KYBER_PREKEY, LinkPurpose.SESSION)
    }

    private fun peer(pairing: String) = RecordKey(LinkPurpose.PEER, pairing)
    private fun session(pairing: String) = RecordKey(LinkPurpose.SESSION, pairing)
    private fun signed(id: Int) = RecordKey(LinkPurpose.SIGNED_PREKEY, RecordKey.number(id))
    private fun kyber(id: Int) = RecordKey(LinkPurpose.KYBER_PREKEY, RecordKey.number(id))
}

/** A paired Robot's pinned identity key: "RLP" 1, then libsignal's 33-byte serialized identity key. */
internal object PeerRecord {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), 1)

    fun encode(key: IdentityKey): ByteArray = MAGIC + key.serialize()

    fun decode(b: ByteArray): IdentityKey {
        require(b.size == MAGIC.size + 33 && b.copyOf(MAGIC.size).contentEquals(MAGIC))
        return IdentityKey(b, MAGIC.size)
    }
}
