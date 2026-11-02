// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §2.3; job L1): the pairing code, PairCode v1, and the maker's side of
// one code (OpenCode: one use, five wrong tries, 24 hours, Cancel).
//
// What a code holds, in this order, big-endian:
//   version u8 (1) | mode u8 | routes u8 (bit 0 push, bit 1 onion, bit 2 a local address; no other bit)
//   | the maker's identity public key, 33 bytes (libsignal's: type 0x05 and 32 bytes) | a one-time token, 16 bytes
//   | expiry u32 (Unix seconds) | [push: u16 length, the endpoint, 65-byte key, 16-byte auth] | [onion: 32 bytes]
//   | [local: u8 family 4 or 6, 4 or 16 bytes, u16 port] | checksum: the first 4 bytes of SHA-256 of everything before it
// No name, no phone number. A push address or an onion address is always there (how the hello reaches the maker); a local
// address only in a Private code shown in person (§2.3), never in one that was pasted.
//
// Its text is "ROBOT-PAIR-" and the bytes in base64url (no padding). Pasted, it may sit inside other words ("here's my
// code: ROBOT-PAIR-… see you"): the code is the one run of base64url characters right after the one prefix. The checksum
// catches a code a messaging app damaged before anything is sent, so a damaged paste never uses one of the five tries.
// In person, the same text is drawn as a QR code (core QrCode: byte mode, versions 1 to 10, at most 271 bytes), which a
// code with a push address never fits: codes shown in person carry the onion address (and in Private the local one),
// never a push address. Sizes (pinned in PairCodeTest): the largest in-person code is 111 bytes, 159 characters, QR
// version 8; the largest code with a push address (its endpoint 1,000 bytes) is 1,175 bytes, 1,578 characters, far past
// the "about 600 characters" the plan guessed (R0 §9 item 8).

enum class PairCodeForm {
    /** Read from a QR code photographed in person. */
    SCANNED,
    /** Pasted from a message: it may have travelled through anyone's hands, so it never carries a local address. */
    PASTED,
}

class PairCode(
    val mode: LinkMode,
    identity: ByteArray,
    token: ByteArray,
    /** Unix seconds. */
    val expires: Long,
    val push: PushRoute? = null,
    val onion: OnionKey? = null,
    val local: LocalAddress? = null,
) {
    private val id = identity.copyOf()
    private val tok = token.copyOf()
    val identity: ByteArray get() = id.copyOf()
    val token: ByteArray get() = tok.copyOf()

    init {
        require(id.size == IDENTITY && id[0].toInt() == IDENTITY_TYPE && tok.size == TOKEN_BYTES && !LinkBytes.allZero(tok))
        require(expires in 0..0xFFFF_FFFFL)
        require(push != null || onion != null)
        require(local == null || mode == LinkMode.PRIVATE)
        // A local address is for a code shown in person, a push address for one shared at a distance: never both.
        require(local == null || push == null)
    }

    fun bytes(): ByteArray {
        val w = BinWriter(MAX_BYTES)
        val routes = (if (push != null) PUSH else 0) or (if (onion != null) ONION else 0) or (if (local != null) LOCAL else 0)
        w.u8(VERSION).u8(mode.code).u8(routes).bytes(id).bytes(tok).u32(expires)
        push?.write(w)
        onion?.let { w.bytes(it.key) }
        local?.write(w)
        val body = w.toByteArray()
        return body + LinkBytes.sha256(body).copyOf(CHECKSUM)
    }

    /** The text you share ("Share code") or see as a QR code. */
    fun text(): String = PREFIX + LinkBytes.b64(bytes())

    /** The text drawn as the QR code in person; null when it has a push address or is too long for one (core QrCode). */
    fun qrText(): String? = if (push != null) null else text().takeIf { it.length <= QR_MAX_BYTES }

    override fun toString(): String = "PairCode(mode=$mode, push=${push != null}, onion=${onion != null}, local=${local != null})" // l10n: not text (toString for logs and tests)

    companion object {
        const val VERSION = 1
        const val IDENTITY = 33
        const val IDENTITY_TYPE = 0x05
        const val TOKEN_BYTES = 16
        const val CHECKSUM = 4
        /** §2.3: a code works for 24 hours (EST). */
        const val LIFETIME_SECONDS = 24 * 3600L
        /** A maker's clock may be ahead of the reader's: a code may say up to this much more than [LIFETIME_SECONDS]. */
        const val CLOCK_SLACK_SECONDS = 3600L
        const val PREFIX = "ROBOT-PAIR-" // l10n: not text (the pairing code's marker)
        /** What core QrCode holds at its largest version (10, level L, byte mode). */
        const val QR_MAX_BYTES = 271
        private const val PUSH = 1
        private const val ONION = 2
        private const val LOCAL = 4
        private const val FIXED = 3 + IDENTITY + TOKEN_BYTES + 4
        const val MAX_BYTES = FIXED + PushRoute.MAX_WRITTEN + OnionKey.SIZE + CHECKSUM
        /** The longest text a code can be. */
        val MAX_TEXT: Int = PREFIX.length + (MAX_BYTES * 4 + 2) / 3

        /** A new code: a fresh token, valid for 24 hours from [now]. The maker keeps its [OpenCode]. */
        fun make(mode: LinkMode, identity: ByteArray, now: Long, push: PushRoute? = null, onion: OnionKey? = null, local: LocalAddress? = null): Pair<PairCode, OpenCode> {
            val token = LinkBytes.random(TOKEN_BYTES).also { if (LinkBytes.allZero(it)) it[0] = 1 }
            val code = PairCode(mode, identity, token, now + LIFETIME_SECONDS, push, onion, local)
            return code to OpenCode.of(token, code.expires)
        }

        /**
         * In person both Robots scan each other's code; only one sends the pairing hello, so only one session starts: the one
         * whose identity key sorts first (unsigned, byte by byte). Null for the same key twice (a Robot scanned itself).
         */
        fun firstSends(mine: ByteArray, theirs: ByteArray): Boolean? = LinkBytes.compare(mine, theirs).let { if (it == 0) null else it < 0 }

        /** The code in [text] (a QR code's text, or what you pasted), checked at [now] (Unix seconds). */
        fun read(text: String, form: PairCodeForm, now: Long): PairCodeRead {
            if (text.length > 64 * 1024) return PairCodeRead.Bad(LinkProblem.TOO_LONG)
            val at = text.indexOf(PREFIX)
            if (at < 0) return PairCodeRead.Bad(LinkProblem.NO_CODE)
            if (text.indexOf(PREFIX, at + 1) >= 0) return PairCodeRead.Bad(LinkProblem.TWO_CODES)
            var end = at + PREFIX.length
            while (end < text.length && text[end].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) end++
            val payload = text.substring(at + PREFIX.length, end)
            if (payload.length > MAX_TEXT - PREFIX.length) return PairCodeRead.Bad(LinkProblem.TOO_LONG)
            val bytes = LinkBytes.unb64(payload) ?: return PairCodeRead.Bad(LinkProblem.BAD_CHECKSUM)
            return read(bytes, form, now)
        }

        /** The code in [bytes], as [bytes] writes it. */
        fun read(bytes: ByteArray, form: PairCodeForm, now: Long): PairCodeRead {
            if (bytes.size < CHECKSUM + 1) return PairCodeRead.Bad(LinkProblem.TRUNCATED)
            if (bytes.size > MAX_BYTES) return PairCodeRead.Bad(LinkProblem.TOO_LONG)
            val body = bytes.copyOf(bytes.size - CHECKSUM)
            if (!LinkBytes.same(LinkBytes.sha256(body).copyOf(CHECKSUM), bytes.copyOfRange(body.size, bytes.size)))
                return PairCodeRead.Bad(LinkProblem.BAD_CHECKSUM)
            val r = BinReader(body)
            return try {
                val version = r.u8()
                if (version > VERSION) return PairCodeRead.Newer(version)
                if (version != VERSION) return PairCodeRead.Bad(LinkProblem.BAD_VERSION)
                val mode = LinkMode.of(r.u8()) ?: return PairCodeRead.Bad(LinkProblem.BAD_FIELD)
                val routes = r.u8()
                if (routes and (PUSH or ONION or LOCAL).inv() != 0 || routes and (PUSH or ONION) == 0) return PairCodeRead.Bad(LinkProblem.BAD_FIELD)
                val identity = r.bytes(IDENTITY)
                if (identity[0].toInt() != IDENTITY_TYPE) return PairCodeRead.Bad(LinkProblem.BAD_FIELD)
                val token = r.bytes(TOKEN_BYTES)
                if (LinkBytes.allZero(token)) return PairCodeRead.Bad(LinkProblem.BAD_FIELD)
                val expires = r.u32()
                val push = if (routes and PUSH != 0) PushRoute.read(r) else null
                val onion = if (routes and ONION != 0) OnionKey.read(r) else null
                val local = if (routes and LOCAL != 0) LocalAddress.read(r) else null
                r.end()
                if (local != null && (mode != LinkMode.PRIVATE || form != PairCodeForm.SCANNED)) return PairCodeRead.Bad(LinkProblem.NOT_HERE)
                if (local != null && push != null) return PairCodeRead.Bad(LinkProblem.BAD_FIELD)
                if (expires > now + LIFETIME_SECONDS + CLOCK_SLACK_SECONDS) return PairCodeRead.Bad(LinkProblem.BAD_FIELD)
                val code = PairCode(mode, identity, token, expires, push, onion, local)
                if (expires <= now) PairCodeRead.Expired else PairCodeRead.Ok(code)
            } catch (_: LinkBad) {
                PairCodeRead.Bad(LinkProblem.TRUNCATED)
            } catch (_: IllegalArgumentException) {
                PairCodeRead.Bad(LinkProblem.BAD_FIELD)
            }
        }
    }
}

sealed class PairCodeRead {
    class Ok(val code: PairCode) : PairCodeRead() {
        override fun toString(): String = "PairCodeRead.Ok($code)" // l10n: not text (toString for logs and tests)
    }
    /** "This code has expired. Ask for a new one." */
    object Expired : PairCodeRead()
    /** A newer Robot's code: "Update Robot to pair with this code." */
    data class Newer(val version: Int) : PairCodeRead()
    data class Bad(val problem: LinkProblem) : PairCodeRead()
}

/** What became of a pairing hello's token at the code it names. */
enum class CodeAttempt {
    /** The right token, in time: the code is spent by this hello. */
    ACCEPTED,
    /** A wrong token: counted. */
    WRONG,
    /** The fifth wrong token, or any token after it: the code is closed for good. */
    CLOSED,
    EXPIRED,
    /** Already used by an earlier hello: a second use is refused without a reply. */
    SPENT,
    /** You cancelled the code ("Cancel this code"). */
    CANCELLED,
}

/** The maker's side of one code it showed or shared. Immutable: each [attempt] gives the code as it now stands. */
class OpenCode private constructor(token: ByteArray, val expires: Long, val wrong: Int, val state: State) {
    private val tok = token.copyOf()
    /** The code's token: kept by :link in its sealed state (L3's CODE record), never shown or logged. */
    val token: ByteArray get() = tok.copyOf()

    enum class State { OPEN, SPENT, CLOSED, CANCELLED }

    /** A hello carrying [token] arrived at [now]. */
    fun attempt(token: ByteArray, now: Long): Pair<OpenCode, CodeAttempt> = when (state) {
        State.SPENT -> this to CodeAttempt.SPENT
        State.CLOSED -> this to CodeAttempt.CLOSED
        State.CANCELLED -> this to CodeAttempt.CANCELLED
        State.OPEN -> when {
            now >= expires -> this to CodeAttempt.EXPIRED
            token.size == tok.size && LinkBytes.same(token, tok) -> OpenCode(tok, expires, wrong, State.SPENT) to CodeAttempt.ACCEPTED
            wrong + 1 >= WRONG_TRIES -> OpenCode(tok, expires, wrong + 1, State.CLOSED) to CodeAttempt.CLOSED
            else -> OpenCode(tok, expires, wrong + 1, State.OPEN) to CodeAttempt.WRONG
        }
    }

    /**
     * L5-fix: whether a hello carrying [token] would be let in at [now], without spending the code: in person, a hello that
     * comes before your photo of their code is held, and spends the code only once your photo says it's theirs.
     */
    fun accepts(token: ByteArray, now: Long): Boolean = state == State.OPEN && now < expires && token.size == tok.size && LinkBytes.same(token, tok)

    /**
     * L5-fix: a wrong try that carried the right token: in person, a hello from another key than the one you photographed.
     * Counted as any wrong try (five close the code), so the right Robot's hello can still come.
     */
    fun miss(now: Long): OpenCode = when {
        state != State.OPEN || now >= expires -> this
        wrong + 1 >= WRONG_TRIES -> OpenCode(tok, expires, wrong + 1, State.CLOSED)
        else -> OpenCode(tok, expires, wrong + 1, State.OPEN)
    }

    fun cancel(): OpenCode = if (state == State.OPEN) OpenCode(tok, expires, wrong, State.CANCELLED) else this

    /** Still worth keeping its one-time address for (open and not expired at [now]). */
    fun open(now: Long): Boolean = state == State.OPEN && now < expires

    override fun toString(): String = "OpenCode(state=$state, wrong=$wrong)" // l10n: not text (toString for logs and tests)

    companion object {
        /** §2.3: five wrong tries close a code. */
        const val WRONG_TRIES = 5

        fun of(token: ByteArray, expires: Long): OpenCode {
            require(token.size == PairCode.TOKEN_BYTES && !LinkBytes.allZero(token))
            return OpenCode(token, expires, 0, State.OPEN)
        }

        /** A code as :link kept it (1.5 L3): its token, expiry, wrong tries so far and state, read back from its record. */
        fun restore(token: ByteArray, expires: Long, wrong: Int, state: State): OpenCode {
            require(token.size == PairCode.TOKEN_BYTES && !LinkBytes.allZero(token) && wrong in 0..WRONG_TRIES)
            require(state != State.OPEN || wrong < WRONG_TRIES)
            return OpenCode(token, expires, wrong, state)
        }
    }
}
