// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.HelloBundle
import app.heyrobot.core.link.LinkPurpose
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.LinkStateProblem
import app.heyrobot.core.link.RecordKey
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.3 to §2.5, job L2): your Robot's libsignal identity and
// its sessions with each paired Robot, over the sealed stores (LinkProtocolStore). Made once when you turn Robot to Robot
// on ([create]), read once at each :link start ([open]), kept in :link's memory only. Every call that changes anything is
// one store transaction: on storage whole, or not at all.
//
// What it holds (§2.4 and §2.5):
//   - the identity key pair and registration id (made here, by libsignal);
//   - one signed prekey, made again every 30 days (EST); a replaced one is kept 30 days more for first messages in flight;
//   - Kyber-1024 prekeys, one per pairing hello you send (L5), one-time: deleted with the session they start; one never
//     used goes after 7 days (EST; a pairing code lives 24 hours);
//   - each paired Robot's pinned identity key and its session (PQXDH, the Double Ratchet with SPQR, session version 4).
// No one-time EC prekeys and no sender keys (LinkProtocolStore says why). libsignal logs nothing here: Robot never calls
// SignalProtocolLoggerProvider (ProcessIsolationTest), so neither its Java nor its native side has a logger.
//
// What goes wrong is a [SessionException] with a code: libsignal's own exceptions are never passed on, since their messages
// may name an address.

/** A libsignal message as it travels: the first one of a session (with its prekeys; LinkEnvelope kind 2) or a later one (kind 1). */
class SessionBytes(val first: Boolean, bytes: ByteArray) {
    private val b = bytes.copyOf()
    val bytes: ByteArray get() = b.copyOf()

    override fun toString(): String = "SessionBytes(first=$first, ${b.size} bytes)" // l10n: not text (toString for logs and tests)
}

/** Why a session call failed, one code each (the link log). Codes are stable. */
enum class SessionProblem(val code: Int) {
    /** A message already decrypted, or one whose key was dropped (libsignal can't tell them apart, R0 §3.2). */
    DUPLICATE(1),
    /** A message that doesn't decrypt or check: changed, cut, from another session, or a newer version. */
    INVALID(2),
    /** The other Robot's identity key isn't the one pinned for this pairing. */
    UNTRUSTED(3),
    /** No session with this pairing. */
    NO_SESSION(4),
    /** The prekey a first message names is gone (used, expired) or never was. */
    NO_PREKEY(5),
    /** The sealed state couldn't be written (or read): see [SessionException.state]. Nothing changed. */
    STATE(6),
}

/** No message and no stack trace of its own: nothing of a message, a key or an address is kept in one. */
class SessionException(val problem: SessionProblem, val state: LinkStateProblem? = null) : Exception() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "SessionException(${problem.name}${state?.let { ", $it" } ?: ""})" // l10n: not text (toString for logs and tests)
}

/**
 * L5-fix: what LinkNode asks of your identity and its sessions, as [LinkStores] gives it. An interface so the app's
 * Robolectric tests, which never load libsignal (app/build.gradle), can run a real LinkNode over keys of their own.
 * Every call may throw [SessionException]; nothing else of libsignal's leaves here.
 */
interface NodeKeys {
    /** Your identity key, 33 bytes (type 0x05). */
    val identityKey: ByteArray
    fun newKyberPreKey(now: Long): Int
    fun bundle(kyberId: Int, now: Long): HelloBundle
    fun pin(pairing: String, identity: ByteArray)
    fun identityOf(pairing: String): ByteArray?
    fun startSession(pairing: String, bundle: HelloBundle, now: Long)
    fun replaceSession(pairing: String, identity: ByteArray, bundle: HelloBundle, now: Long)
    fun encrypt(pairing: String, plain: ByteArray, now: Long): SessionBytes
    fun decrypt(pairing: String, message: SessionBytes): ByteArray
    fun openHello(sealed: ByteArray): ByteArray?
    /** L5-fix: your identity key's XEdDSA signature over [message] (a pairing hello's signed part). */
    fun signHello(message: ByteArray): ByteArray
    /** L5-fix: whether [signature] is [identity]'s (33 bytes) XEdDSA signature over [message]; false for anything that isn't. */
    fun verifies(identity: ByteArray, message: ByteArray, signature: ByteArray): Boolean
    fun safetyNumber(theirs: ByteArray): String?
    fun forget(pairing: String)
    fun prune(now: Long)
}

class LinkStores private constructor(private val store: LinkProtocolStore, private val random: SecureRandom) : NodeKeys {
    /** Your identity key, 33 bytes (type 0x05): what pairing codes and safety numbers show. */
    override val identityKey: ByteArray get() = store.getIdentityKeyPair().publicKey.serialize()

    val registrationId: Int get() = store.getLocalRegistrationId()

    /** The signed prekey to hand out at [now] (ms): the newest, made again when it is [SIGNED_PREKEY_DAYS] days old. */
    fun signedPreKey(now: Long): SignedPreKeyRecord {
        store.loadSignedPreKeys().maxByOrNull { it.timestamp }?.takeIf { now - it.timestamp < SIGNED_PREKEY_DAYS * DAY_MS }?.let { return it }
        return stateful {
            store.transaction {
                val id = newId { store.containsSignedPreKey(it) }
                val pair = ECKeyPair.generate()
                SignedPreKeyRecord(id, now, pair, store.getIdentityKeyPair().privateKey.calculateSignature(pair.publicKey.serialize()))
                    .also { store.storeSignedPreKey(id, it) }
            }
        }
    }

    /** A new one-time Kyber-1024 prekey, signed by your identity key: its id, for one pairing hello's bundle. */
    override fun newKyberPreKey(now: Long): Int = stateful {
        store.transaction {
            val id = newId { store.containsKyberPreKey(it) }
            val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            store.storeKyberPreKey(id, KyberPreKeyRecord(id, now, pair, store.getIdentityKeyPair().privateKey.calculateSignature(pair.publicKey.serialize())))
            id
        }
    }

    /** Your bundle for a pairing hello (core PairHello): the current signed prekey and the Kyber prekey [kyberId]. */
    override fun bundle(kyberId: Int, now: Long): HelloBundle {
        val spk = signedPreKey(now)
        val k = try { store.loadKyberPreKey(kyberId) } catch (_: InvalidKeyIdException) { throw SessionException(SessionProblem.NO_PREKEY) }
        return HelloBundle(registrationId, identityKey, spk.id, spk.keyPair.publicKey.serialize(), spk.signature,
            k.id, k.keyPair.publicKey.serialize(), k.signature)
    }

    /** Pins [identity] (33 bytes) as [pairing]'s identity key: the pairing checked it (L5). Replaces an earlier pin. */
    override fun pin(pairing: String, identity: ByteArray) {
        val key = IdentityKey(identity)
        stateful { store.transaction { store.pin(pairing, key) } }
    }

    /** [pairing]'s pinned identity key (33 bytes), or null. */
    override fun identityOf(pairing: String): ByteArray? = store.getIdentity(LinkAddress.of(pairing))?.serialize()

    fun hasSession(pairing: String): Boolean = store.containsSession(LinkAddress.of(pairing))

    /** The pairings with a session. */
    fun pairings(): List<String> = store.pairings()

    /**
     * Starts a session to [pairing]'s [bundle] (from its pairing hello), at [now] (ms): PQXDH with its signed and Kyber
     * prekeys, whose signatures libsignal checks against the identity key, which must be the one pinned for [pairing].
     */
    override fun startSession(pairing: String, bundle: HelloBundle, now: Long) = session {
        val b = preKeyBundle(bundle)
        store.transaction { SessionBuilder(store, store, store, store, LinkAddress.of(pairing), LinkAddress.SELF).process(b, Instant.ofEpochMilli(now)) }
    }

    /**
     * L5-fix, an accepted move (§2.7): [identity] pinned for [pairing] in place of the old key, the old session deleted (its
     * archived states with it, so nothing the old phone sends is even tried), and a new one started to [bundle], in one
     * transaction: when the bundle doesn't check, nothing changed (the old key and session stay).
     */
    override fun replaceSession(pairing: String, identity: ByteArray, bundle: HelloBundle, now: Long) = session {
        val key = IdentityKey(identity)
        val b = preKeyBundle(bundle)
        store.transaction {
            store.pin(pairing, key)
            store.deleteSession(LinkAddress.of(pairing))
            SessionBuilder(store, store, store, store, LinkAddress.of(pairing), LinkAddress.SELF).process(b, Instant.ofEpochMilli(now))
        }
    }

    private fun preKeyBundle(bundle: HelloBundle) = PreKeyBundle(bundle.registrationId, LinkAddress.DEVICE, PreKeyBundle.NULL_PRE_KEY_ID, null,
        bundle.signedPreKeyId, ECPublicKey(bundle.signedPreKey), bundle.signedPreKeySignature, IdentityKey(bundle.identity),
        bundle.kyberPreKeyId, KEMPublicKey(bundle.kyberPreKey), bundle.kyberPreKeySignature)

    /** [plain] (a padded request) encrypted for [pairing] at [now] (ms): the session's first message until it has an answer. */
    override fun encrypt(pairing: String, plain: ByteArray, now: Long): SessionBytes = session {
        store.transaction {
            val m = cipher(pairing).encrypt(plain, Instant.ofEpochMilli(now))
            SessionBytes(m.type == CiphertextMessage.PREKEY_TYPE, m.serialize())
        }
    }

    /** [message] from [pairing], decrypted: its plaintext, or a [SessionException] and no change at all. */
    override fun decrypt(pairing: String, message: SessionBytes): ByteArray = session {
        store.transaction {
            if (message.first) cipher(pairing).decrypt(PreKeySignalMessage(message.bytes))
            else cipher(pairing).decrypt(SignalMessage(message.bytes))
        }
    }

    /**
     * A pairing hello sealed to your identity key (HelloSeal), opened with your identity's private key: the hello's bytes,
     * or null when it isn't sealed to you or was changed. libsignal's exception never leaves here (nothing of it is kept).
     */
    override fun openHello(sealed: ByteArray): ByteArray? = try {
        store.getIdentityKeyPair().privateKey.open(sealed, HelloSeal.INFO, HelloSeal.AAD)
    } catch (_: Exception) {
        null
    }

    override fun signHello(message: ByteArray): ByteArray = store.getIdentityKeyPair().privateKey.calculateSignature(message)

    override fun verifies(identity: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        IdentityKey(identity).publicKey.verifySignature(message, signature)
    } catch (_: Exception) {
        false
    }

    /**
     * L5: the safety number between your identity and [theirs] (33 bytes): 60 digits, the same on both phones whichever side
     * asks, which you compare aloud or side by side (§2.3, §6.2). libsignal's numeric fingerprint: each side's half is 5,200
     * rounds of SHA-512 over its identity key and a stable identifier, here one constant for every Robot (a Robot has no phone
     * number or account), so the number depends on the two keys alone. Null when [theirs] isn't a key.
     */
    override fun safetyNumber(theirs: ByteArray): String? = try {
        NumericFingerprintGenerator(FINGERPRINT_ROUNDS).createFor(FINGERPRINT_VERSION, STABLE_ID, store.getIdentityKeyPair().publicKey, STABLE_ID,
            IdentityKey(theirs)).displayableFingerprint.displayText.takeIf { app.heyrobot.core.link.SafetyNumber.ok(it) }
    } catch (_: Exception) {
        null
    }

    /** Unpair or block: [pairing]'s session and pinned identity gone. */
    override fun forget(pairing: String) = stateful { store.transaction { store.forget(pairing) } }

    /**
     * Housekeeping at [now] (ms): Kyber prekeys never used and older than [KYBER_DAYS] days, and signed prekeys other than
     * the newest made more than twice [SIGNED_PREKEY_DAYS] days ago, removed.
     */
    override fun prune(now: Long) = stateful {
        store.transaction {
            for (k in store.loadKyberPreKeys()) if (now - k.timestamp > KYBER_DAYS * DAY_MS) store.removeKyberPreKey(k.id)
            val signed = store.loadSignedPreKeys().sortedByDescending { it.timestamp }
            for (s in signed.drop(1)) if (now - s.timestamp > 2 * SIGNED_PREKEY_DAYS * DAY_MS) store.removeSignedPreKey(s.id)
        }
    }

    private fun cipher(pairing: String) = SessionCipher(store, store, store, store, store, LinkAddress.SELF, LinkAddress.of(pairing))

    /** A free random prekey id (libsignal's ids are 24 bits; HelloBundle takes 1 to 0xFFFFFF). */
    private fun newId(taken: (Int) -> Boolean): Int {
        while (true) {
            val id = 1 + random.nextInt(HelloBundle.MAX_PREKEY_ID)
            if (!taken(id)) return id
        }
    }

    private inline fun <T> stateful(block: () -> T): T = try {
        block()
    } catch (x: LinkStateException) {
        throw SessionException(SessionProblem.STATE, x.problem)
    }

    private inline fun <T> session(block: () -> T): T = try {
        block()
    } catch (x: SessionException) {
        throw x
    } catch (x: LinkStateException) {
        throw SessionException(SessionProblem.STATE, x.problem)
    } catch (_: DuplicateMessageException) {
        throw SessionException(SessionProblem.DUPLICATE)
    } catch (_: UntrustedIdentityException) {
        throw SessionException(SessionProblem.UNTRUSTED)
    } catch (_: NoSessionException) {
        throw SessionException(SessionProblem.NO_SESSION)
    } catch (_: InvalidKeyIdException) {
        throw SessionException(SessionProblem.NO_PREKEY)
    } catch (x: Exception) {
        // InvalidMessageException, InvalidKeyException, InvalidVersionException, LegacyMessageException, and a store's
        // refusal (an identity not pinned): all "doesn't check".
        throw SessionException(SessionProblem.INVALID)
    }

    companion object {
        /** §2.5: one signed prekey, made again every 30 days (EST). */
        const val SIGNED_PREKEY_DAYS = 30L
        /** A Kyber prekey never used goes after 7 days (EST): a pairing code lives 24 hours (§2.3). */
        const val KYBER_DAYS = 7L
        private const val DAY_MS = 86_400_000L
        /** libsignal's registration ids: 14 bits, never 0. */
        private const val MAX_REGISTRATION = HelloBundle.MAX_REGISTRATION
        /** Signal's own rounds and version for safety numbers. */
        private const val FINGERPRINT_ROUNDS = 5_200
        private const val FINGERPRINT_VERSION = 2
        /** Every Robot's stable identifier in a safety number: the same on both sides, so only the keys count. */
        private val STABLE_ID = "robot-link-v1".toByteArray(Charsets.US_ASCII) // l10n: not text (bytes hashed into the safety number)

        /**
         * Your Robot's identity, made when you turn Robot to Robot on, at [now] (ms): a new identity key pair, registration
         * id and first signed prekey, sealed into [records] (which must hold nothing yet: never over another identity).
         */
        fun create(records: LinkRecords, now: Long, random: SecureRandom = SecureRandom()): LinkStores {
            if (records.anything()) throw LinkStateException(LinkStateProblem.DAMAGED)
            val identity = IdentityKeyPair.generate()
            val registration = 1 + random.nextInt(MAX_REGISTRATION)
            records.write(RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO), IdentityRecord.encode(identity, registration))
            return LinkStores(LinkProtocolStore(records, identity, registration, emptyMap()), random).also {
                try { it.signedPreKey(now) } catch (x: SessionException) { throw LinkStateException(x.state ?: LinkStateProblem.IO) }
            }
        }

        /**
         * The state in [records], read once (at :link's start). Throws [LinkStateException]: LOCKED (not now), GONE or
         * DAMAGED (kept; "pair again with each person" and Start over), NEWER.
         */
        fun open(records: LinkRecords, random: SecureRandom = SecureRandom(), all: Map<RecordKey, ByteArray> = records.readAll()): LinkStores {
            val id = all[RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO)] ?: throw LinkStateException(LinkStateProblem.DAMAGED)
            val (identity, registration) = try { IdentityRecord.decode(id) } catch (_: Exception) { throw LinkStateException(LinkStateProblem.DAMAGED) }
            return LinkStores(LinkProtocolStore(records, identity, registration, all), random)
        }
    }
}

/** Your identity: "RLI" 1 | registration id u32 | the identity key pair's length u16, then libsignal's serialization. */
internal object IdentityRecord {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'I'.code.toByte(), 1)

    fun encode(identity: IdentityKeyPair, registration: Int): ByteArray {
        val pair = identity.serialize()
        return ByteBuffer.allocate(MAGIC.size + 4 + 2 + pair.size).put(MAGIC).putInt(registration).putShort(pair.size.toShort()).put(pair).array()
            .also { pair.fill(0) }
    }

    fun decode(b: ByteArray): Pair<IdentityKeyPair, Int> {
        val r = ByteBuffer.wrap(b)
        val magic = ByteArray(MAGIC.size).also { r.get(it) }
        require(magic.contentEquals(MAGIC))
        val registration = r.int
        require(registration in 1..HelloBundle.MAX_REGISTRATION)
        val n = r.short.toInt() and 0xFFFF
        require(r.remaining() == n)
        val pair = ByteArray(n).also { r.get(it) }
        try {
            return IdentityKeyPair(pair) to registration
        } finally {
            pair.fill(0)
        }
    }
}
