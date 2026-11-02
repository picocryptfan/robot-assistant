// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3.2; job L1): the envelope every carrier moves, LinkEnvelope v1.
//
//   offset 0   4  magic "RLK" and version 0x01
//          4   1  kind: 1 session message, 2 first message (with a prekey), 3 pairing hello, 4 transfer chunk, 5 connection hello
//          5  16  route
//         21   4  body length, unsigned
//         25   n  body: libsignal's serialized message, a pairing hello (PairHello), or a sealed transfer chunk
//
// Big-endian, nothing after the body. No identity and no name. What the route holds, by carrier:
//   - Push (one push body, at most 3,993 bytes in all: UnifiedPush AND_3.1.0's plaintext, R0 §5.3): kinds 1, 2 and 3
//     only, the route all zeros. The push address itself says which pairing (D6), and a pairing hello carries its code's
//     token inside its body. Transfer chunks and connection hellos never travel by push (§3.6).
//   - Live (a TCP stream, direct or Tor; at most 65,577 bytes an envelope: a 64 KiB chunk, its 16-byte tag and this
//     header): a connection hello (kind 5, an empty body) opens with the per-pairing connection token, never zeros; a
//     transfer chunk carries it too; a pairing hello carries zeros (the token is in its body). Session messages (1, 2)
//     carry the connection's token, or zeros on a connection that opened with a pairing hello: which one is right depends
//     on the connection, so the carrier (L3) checks it, not this decoder.
// A newer version is [EnvelopeRead.Newer]: dropped and counted, unanswered (LinkNode), so the sender retries until it expires; anything else malformed is
// [EnvelopeRead.Bad] (dropped and counted by its code, never answered, so a stranger probing gets nothing back).

/** What moves the bytes: one push body, or a live stream. */
enum class LinkCarrier(val maxEnvelope: Int) {
    PUSH(3_993),
    LIVE(65_577);

    val maxBody: Int get() = maxEnvelope - LinkEnvelope.HEADER
}

enum class EnvelopeKind(val code: Int) {
    SESSION(1),
    FIRST(2),
    PAIR_HELLO(3),
    CHUNK(4),
    CONNECT(5);

    companion object {
        fun of(code: Int): EnvelopeKind? = entries.firstOrNull { it.code == code }
    }
}

/** One envelope. [route]: 16 bytes; [body]: its bytes. Never shows either in a log line. */
class LinkEnvelope(val kind: EnvelopeKind, route: ByteArray, body: ByteArray) {
    private val r = route.copyOf()
    private val b = body.copyOf()
    val route: ByteArray get() = r.copyOf()
    val body: ByteArray get() = b.copyOf()
    val bodySize: Int get() = b.size

    init {
        require(route.size == ROUTE)
    }

    /** The bytes on the wire, when this envelope may travel by [carrier] ([check]); else IllegalArgumentException. */
    fun encode(carrier: LinkCarrier): ByteArray {
        require(check(kind, r, b.size.toLong(), carrier) == null)
        return BinWriter(HEADER + b.size).bytes(MAGIC).u8(kind.code).bytes(r).u32(b.size.toLong()).bytes(b).toByteArray()
    }

    override fun toString(): String = "LinkEnvelope(kind=$kind, body=${b.size} bytes)" // l10n: not text (toString for logs and tests)

    companion object {
        const val VERSION = 1
        const val HEADER = 25
        const val ROUTE = 16
        /** A transfer chunk: SealStream's 64 KiB of ciphertext and its 16-byte tag at most (§3.6, §3.7). */
        const val MAX_CHUNK = 65_536 + 16
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), VERSION.toByte())
        val NO_ROUTE: ByteArray get() = ByteArray(ROUTE)

        /** Why [kind] with [route] and a body of [length] bytes can't travel by [carrier]; null when it can. */
        private fun check(kind: EnvelopeKind, route: ByteArray, length: Long, carrier: LinkCarrier): LinkProblem? {
            val zero = LinkBytes.allZero(route)
            when (carrier) {
                LinkCarrier.PUSH -> {
                    if (kind == EnvelopeKind.CHUNK || kind == EnvelopeKind.CONNECT) return LinkProblem.BAD_KIND
                    if (!zero) return LinkProblem.BAD_ROUTE
                }
                LinkCarrier.LIVE -> when (kind) {
                    EnvelopeKind.PAIR_HELLO -> if (!zero) return LinkProblem.BAD_ROUTE
                    EnvelopeKind.CHUNK, EnvelopeKind.CONNECT -> if (zero) return LinkProblem.BAD_ROUTE
                    EnvelopeKind.SESSION, EnvelopeKind.FIRST -> {}
                }
            }
            val ok = when (kind) {
                EnvelopeKind.CONNECT -> length == 0L
                EnvelopeKind.CHUNK -> length in 1..MAX_CHUNK.toLong()
                else -> length in 1..carrier.maxBody.toLong()
            }
            return if (!ok || length > carrier.maxBody) LinkProblem.BAD_LENGTH else null
        }

        /**
         * The first [HEADER] bytes of an envelope on a stream: its kind, route and how many body bytes follow, checked
         * before anything is read or allocated for the body.
         */
        fun header(bytes: ByteArray, carrier: LinkCarrier): HeaderRead {
            if (bytes.size < HEADER) return HeaderRead.Bad(LinkProblem.TRUNCATED)
            if (bytes.size > HEADER) return HeaderRead.Bad(LinkProblem.TRAILING)
            return readHeader(BinReader(bytes), carrier)
        }

        private fun readHeader(r: BinReader, carrier: LinkCarrier): HeaderRead = try {
            if (r.u8() != 'R'.code || r.u8() != 'L'.code || r.u8() != 'K'.code) {
                HeaderRead.Bad(LinkProblem.BAD_MAGIC)
            } else {
                val version = r.u8()
                when {
                    version > VERSION -> HeaderRead.Newer(version)
                    version != VERSION -> HeaderRead.Bad(LinkProblem.BAD_VERSION)
                    else -> {
                        val kind = EnvelopeKind.of(r.u8())
                        if (kind == null) {
                            HeaderRead.Bad(LinkProblem.BAD_KIND)
                        } else {
                            val route = r.bytes(ROUTE)
                            val length = r.u32()
                            when (val p = check(kind, route, length, carrier)) {
                                null -> HeaderRead.Ok(kind, route, length.toInt())
                                else -> HeaderRead.Bad(p)
                            }
                        }
                    }
                }
            }
        } catch (_: LinkBad) {
            HeaderRead.Bad(LinkProblem.TRUNCATED)
        }

        /** A whole envelope that came by [carrier]: exactly its header and the body its length says. */
        fun decode(bytes: ByteArray, carrier: LinkCarrier): EnvelopeRead {
            if (bytes.size > carrier.maxEnvelope) {
                // A newer Robot's envelope may be laid out otherwise; it is still answered, not dropped.
                if (bytes.size >= 4 && bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1] && bytes[2] == MAGIC[2] && (bytes[3].toInt() and 0xFF) > VERSION)
                    return EnvelopeRead.Newer(bytes[3].toInt() and 0xFF)
                return EnvelopeRead.Bad(LinkProblem.TOO_LONG)
            }
            if (bytes.size < 4) return EnvelopeRead.Bad(LinkProblem.TRUNCATED)
            val r = BinReader(bytes)
            return when (val h = readHeader(r, carrier)) {
                is HeaderRead.Bad -> EnvelopeRead.Bad(h.problem)
                is HeaderRead.Newer -> EnvelopeRead.Newer(h.version)
                is HeaderRead.Ok -> when {
                    r.remaining < h.length -> EnvelopeRead.Bad(LinkProblem.TRUNCATED)
                    r.remaining > h.length -> EnvelopeRead.Bad(LinkProblem.TRAILING)
                    else -> EnvelopeRead.Ok(LinkEnvelope(h.kind, h.route, r.bytes(h.length)))
                }
            }
        }
    }
}

sealed class HeaderRead {
    class Ok(val kind: EnvelopeKind, route: ByteArray, val length: Int) : HeaderRead() {
        private val r = route.copyOf()
        val route: ByteArray get() = r.copyOf()
        override fun toString(): String = "HeaderRead.Ok(kind=$kind, length=$length)" // l10n: not text (toString for logs and tests)
    }
    data class Newer(val version: Int) : HeaderRead()
    data class Bad(val problem: LinkProblem) : HeaderRead()
}

sealed class EnvelopeRead {
    class Ok(val envelope: LinkEnvelope) : EnvelopeRead() {
        override fun toString(): String = "EnvelopeRead.Ok($envelope)" // l10n: not text (toString for logs and tests)
    }
    /** A newer envelope version: answered `unsupported`, and shown here as "Sam's Robot is newer. Update Robot to read this." */
    data class Newer(val version: Int) : EnvelopeRead()
    data class Bad(val problem: LinkProblem) : EnvelopeRead()
}
