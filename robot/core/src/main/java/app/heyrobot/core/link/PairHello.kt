// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.LinkCaps
import app.heyrobot.core.link.requests.RequestType

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §2.3, R0 §9 item 25; job L1): the pairing hello, the one message
// sent before a session exists (an envelope of kind 3). The Robot that scanned or pasted a code sends it to the maker's
// one-time address; the maker checks the token (OpenCode), starts a session to the bundle, and answers in that session.
// A Robot restored on a new phone sends the same message with purpose MOVED to each person it was paired with (Q5's
// default; which token it carries is L5's and L12's to settle: the field is here, 16 bytes like a code's).
//
// Binary, as R0 found JSON with base64 too large for one push body (3,968 bytes of envelope body):
//   "RLH" 1 | purpose u8 (1 pair, 2 moved) | mode u8 | token 16
//   | registration id u32 (1 to 16,380) | identity key 33 (type 0x05)
//   | signed prekey id u32 (1 to 0xFFFFFF) | signed prekey 33 (type 0x05) | its signature 64
//   | Kyber prekey id u32 (1 to 0xFFFFFF) | Kyber-1024 prekey 1,569 (type 0x08) | its signature 64
//   | routes u8 (bit 0 push, bit 1 onion; at least one, no other bit) | [push: u16 length, endpoint, 65, 16]
//   | [onion 32, its client authorization 32] | name: u8 length, UTF-8 (LinkText: one line, at most 40 characters)
//   | caps: a 32-byte bitmap (LinkCaps) | signature 64 (L5-fix)
// The worst case (a 1,000-byte endpoint, a 40-character name of 4-byte characters, every bit of caps) is 3,202 bytes.
// libsignal checks the signatures; this reader checks shapes and lengths only.
//
// 1.5 L3 (before any release, so no Robot reads the older layout): the onion route carries its client authorization, the
// x25519 private key Tor's client authorization asks for (Tor's ONION_CLIENT_AUTH_ADD), made by the sender for this pairing
// alone: its onion service answers only Robots holding such a key (D5), so without it the maker couldn't answer there.
//
// 1.5 L5-fix (before any release, so no Robot reads the older layout): the hello ends with an XEdDSA signature (64 bytes) by
// the sender's identity key over "robot-link-hello-sig-v1" and every byte before it ([signedPart]), checked by the maker
// after opening it and before its token or any code is touched (the link module's NodeKeys.verifies). A prekey bundle can
// be copied out of an earlier hello (a maker sees each one it gets), and libsignal checks only that the bundle's prekeys are
// signed by its identity key: without this, someone holding your code could send another Robot's genuine bundle with
// routes of their own, spend the code and pair your Robot to a key that will never answer it. The worst case is 64 bytes
// more: 3,202 bytes, 3,251 sealed.
//
// What the hello shows: nothing, to anyone but the maker. The envelope's body (kind 3) is this hello sealed to the maker's
// identity key from the code (libsignal's HPKE, RFC 9180 base mode, X25519, HKDF-SHA256, AES-256-GCM: the link module's
// HelloSeal), so the name, the push address, the onion's key and caps cross a shared Wi-Fi (the direct carrier), a push
// server or Tor as ciphertext (§3.8). Sealed, the worst case is [SEALED_MAX] bytes, under the 3,968 bytes of one push
// body's envelope (PairCodeTest).

enum class HelloPurpose(val code: Int) {
    PAIR(1),
    MOVED(2);

    companion object {
        fun of(code: Int): HelloPurpose? = entries.firstOrNull { it.code == code }
    }
}

/** A libsignal prekey bundle as a hello carries it (no one-time prekey: there is no server to hand them out, §2.5). */
class HelloBundle(
    val registrationId: Int,
    identity: ByteArray,
    val signedPreKeyId: Int,
    signedPreKey: ByteArray,
    signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Int,
    kyberPreKey: ByteArray,
    kyberPreKeySignature: ByteArray,
) {
    private val parts = arrayOf(identity.copyOf(), signedPreKey.copyOf(), signedPreKeySignature.copyOf(), kyberPreKey.copyOf(), kyberPreKeySignature.copyOf())
    val identity: ByteArray get() = parts[0].copyOf()
    val signedPreKey: ByteArray get() = parts[1].copyOf()
    val signedPreKeySignature: ByteArray get() = parts[2].copyOf()
    val kyberPreKey: ByteArray get() = parts[3].copyOf()
    val kyberPreKeySignature: ByteArray get() = parts[4].copyOf()

    init {
        require(registrationId in 1..MAX_REGISTRATION && signedPreKeyId in 1..MAX_PREKEY_ID && kyberPreKeyId in 1..MAX_PREKEY_ID)
        require(parts[0].size == PairCode.IDENTITY && parts[0][0].toInt() == EC_TYPE)
        require(parts[1].size == EC_KEY && parts[1][0].toInt() == EC_TYPE && parts[2].size == SIGNATURE)
        require(parts[3].size == KYBER_KEY && parts[3][0].toInt() == KYBER_TYPE && parts[4].size == SIGNATURE)
    }

    internal fun write(w: BinWriter) {
        w.u32(registrationId.toLong()).bytes(parts[0])
        w.u32(signedPreKeyId.toLong()).bytes(parts[1]).bytes(parts[2])
        w.u32(kyberPreKeyId.toLong()).bytes(parts[3]).bytes(parts[4])
    }

    override fun toString(): String = "HelloBundle" // l10n: not text (toString for logs and tests)

    companion object {
        /** libsignal's registration ids are 14 bits, never 0 (KeyHelper's range). */
        const val MAX_REGISTRATION = 16_380
        /** libsignal's prekey ids are 24 bits (Medium.MAX_VALUE). */
        const val MAX_PREKEY_ID = 0xFF_FFFF
        const val EC_TYPE = 0x05
        const val EC_KEY = 33
        const val KYBER_TYPE = 0x08
        /** KEMKeyType.KYBER_1024's serialized public key: its type and 1,568 bytes (R0 §3.2). */
        const val KYBER_KEY = 1_569
        const val SIGNATURE = 64
        const val SIZE = 4 + PairCode.IDENTITY + 4 + EC_KEY + SIGNATURE + 4 + KYBER_KEY + SIGNATURE

        internal fun read(r: BinReader): HelloBundle {
            val reg = r.u32()
            val identity = r.bytes(PairCode.IDENTITY)
            val spkId = r.u32()
            val spk = r.bytes(EC_KEY)
            val spkSig = r.bytes(SIGNATURE)
            val kId = r.u32()
            val k = r.bytes(KYBER_KEY)
            val kSig = r.bytes(SIGNATURE)
            if (reg !in 1..MAX_REGISTRATION.toLong() || spkId !in 1..MAX_PREKEY_ID.toLong() || kId !in 1..MAX_PREKEY_ID.toLong()) throw LinkBad
            if (identity[0].toInt() != EC_TYPE || spk[0].toInt() != EC_TYPE || k[0].toInt() != KYBER_TYPE) throw LinkBad
            return HelloBundle(reg.toInt(), identity, spkId.toInt(), spk, spkSig, kId.toInt(), k, kSig)
        }
    }
}

class PairHello(
    val purpose: HelloPurpose,
    val mode: LinkMode,
    token: ByteArray,
    val bundle: HelloBundle,
    val push: PushRoute?,
    val onion: OnionKey?,
    /** With [onion]: the key Tor's client authorization asks for to reach it (x25519, 32 bytes), for this pairing alone. */
    onionAuth: ByteArray?,
    /** The name its sender wants shown: a claim, never what picks a person (§2.3). */
    val name: String,
    caps: ByteArray,
    /** L5-fix: the sender's identity key's XEdDSA signature over [signedPart]; all zeros until [signed] (never valid). */
    signature: ByteArray = ByteArray(SIGNATURE),
) {
    private val tok = token.copyOf()
    private val capBits = caps.copyOf()
    private val oauth = onionAuth?.copyOf()
    private val sig = signature.copyOf()
    val signature: ByteArray get() = sig.copyOf()
    val token: ByteArray get() = tok.copyOf()
    val onionAuth: ByteArray? get() = oauth?.copyOf()
    /** The bitmap as it came, unknown bits included (a newer Robot's types). */
    val capsBitmap: ByteArray get() = capBits.copyOf()
    /** The types this build knows that the sender reads. */
    val caps: Set<RequestType> get() = LinkCaps.fromBitmap(capBits)

    init {
        require(tok.size == PairCode.TOKEN_BYTES && !LinkBytes.allZero(tok))
        require(push != null || onion != null)
        require((onion == null) == (oauth == null) && (oauth == null || OnionKey.authOk(oauth)))
        require(LinkText.ok(name, MAX_NAME) && name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
        require(capBits.size == LinkCaps.BITMAP)
        require(sig.size == SIGNATURE)
    }

    /** The hello's bytes: the link module's HelloSeal seals them to the maker's identity key as the envelope's body (kind 3). */
    fun bytes(): ByteArray = body() + sig

    /** L5-fix: what the sender's identity key signs: [SIG_CONTEXT] and every byte of the hello before its signature. */
    fun signedPart(): ByteArray = SIG_CONTEXT + body()

    /** L5-fix: this hello with [signature] (64 bytes, the sender's identity key's over [signedPart]). */
    fun signed(signature: ByteArray): PairHello = PairHello(purpose, mode, tok, bundle, push, onion, oauth, name, capBits, signature)

    private fun body(): ByteArray {
        val w = BinWriter(MAX_BYTES - SIGNATURE)
        w.bytes(MAGIC).u8(purpose.code).u8(mode.code).bytes(tok)
        bundle.write(w)
        w.u8((if (push != null) PUSH else 0) or (if (onion != null) ONION else 0))
        push?.write(w)
        onion?.let { w.bytes(it.key).bytes(oauth!!) }
        val n = name.toByteArray(Charsets.UTF_8)
        w.u8(n.size).bytes(n).bytes(capBits)
        return w.toByteArray()
    }

    override fun toString(): String = "PairHello(purpose=$purpose, mode=$mode, push=${push != null}, onion=${onion != null})" // l10n: not text (toString for logs and tests)

    companion object {
        const val VERSION = 1
        /** "Name other Robots see": one line, at most 40 characters. */
        const val MAX_NAME = 40
        const val MAX_NAME_BYTES = MAX_NAME * 4
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'H'.code.toByte(), VERSION.toByte())
        private const val PUSH = 1
        private const val ONION = 2
        /** L5-fix: an XEdDSA signature's size. */
        const val SIGNATURE = 64
        /** L5-fix: what the signature covers first, so it can never be taken for a signature over anything else. */
        val SIG_CONTEXT: ByteArray get() = "robot-link-hello-sig-v1".toByteArray(Charsets.US_ASCII) // l10n: not text (bytes signed)
        const val MAX_BYTES = 4 + 2 + PairCode.TOKEN_BYTES + HelloBundle.SIZE + 1 + PushRoute.MAX_WRITTEN + 2 * OnionKey.SIZE + 1 + MAX_NAME_BYTES + LinkCaps.BITMAP + SIGNATURE
        /** libsignal's HPKE adds a type byte, its 32-byte encapsulated key and the 16-byte tag. */
        const val SEAL_OVERHEAD = 1 + 32 + 16
        const val SEALED_MAX = MAX_BYTES + SEAL_OVERHEAD

        /** The hello in [body], as HelloSeal opened it from an envelope's body. */
        fun read(body: ByteArray): PairHelloRead {
            if (body.size > MAX_BYTES) return PairHelloRead.Bad(LinkProblem.TOO_LONG)
            val r = BinReader(body)
            return try {
                if (r.u8() != 'R'.code || r.u8() != 'L'.code || r.u8() != 'H'.code) return PairHelloRead.Bad(LinkProblem.BAD_MAGIC)
                val version = r.u8()
                if (version > VERSION) return PairHelloRead.Newer(version)
                if (version != VERSION) return PairHelloRead.Bad(LinkProblem.BAD_VERSION)
                val purpose = HelloPurpose.of(r.u8()) ?: return PairHelloRead.Bad(LinkProblem.BAD_FIELD)
                val mode = LinkMode.of(r.u8()) ?: return PairHelloRead.Bad(LinkProblem.BAD_FIELD)
                val token = r.bytes(PairCode.TOKEN_BYTES)
                if (LinkBytes.allZero(token)) return PairHelloRead.Bad(LinkProblem.BAD_FIELD)
                val bundle = HelloBundle.read(r)
                val routes = r.u8()
                if (routes and (PUSH or ONION).inv() != 0 || routes == 0) return PairHelloRead.Bad(LinkProblem.BAD_FIELD)
                val push = if (routes and PUSH != 0) PushRoute.read(r) else null
                val onion = if (routes and ONION != 0) OnionKey.read(r) else null
                val oauth = if (onion != null) r.bytes(OnionKey.SIZE).also { if (!OnionKey.authOk(it)) return PairHelloRead.Bad(LinkProblem.BAD_FIELD) } else null
                val n = r.u8()
                if (n == 0 || n > MAX_NAME_BYTES) return PairHelloRead.Bad(LinkProblem.BAD_TEXT)
                val name = LinkBytes.utf8(r.bytes(n)) ?: return PairHelloRead.Bad(LinkProblem.BAD_TEXT)
                if (!LinkText.ok(name, MAX_NAME)) return PairHelloRead.Bad(LinkProblem.BAD_TEXT)
                val caps = r.bytes(LinkCaps.BITMAP)
                val sig = r.bytes(SIGNATURE)
                if (r.remaining != 0) return PairHelloRead.Bad(LinkProblem.TRAILING)
                PairHelloRead.Ok(PairHello(purpose, mode, token, bundle, push, onion, oauth, name, caps, sig))
            } catch (_: LinkBad) {
                PairHelloRead.Bad(LinkProblem.TRUNCATED)
            } catch (_: IllegalArgumentException) {
                PairHelloRead.Bad(LinkProblem.BAD_FIELD)
            }
        }
    }
}

sealed class PairHelloRead {
    class Ok(val hello: PairHello) : PairHelloRead() {
        override fun toString(): String = "PairHelloRead.Ok($hello)" // l10n: not text (toString for logs and tests)
    }
    data class Newer(val version: Int) : PairHelloRead()
    data class Bad(val problem: LinkProblem) : PairHelloRead()
}
