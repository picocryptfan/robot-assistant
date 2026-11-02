// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.OnionKey

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2, D5; job L3): spelling a Robot's onion service. Pairing
// codes, hellos and net.hint carry an onion as its 32-byte ed25519 public key (core OnionKey); Tor wants the v3 address,
// rend-spec-v3 §6: base32(key | checksum | 0x03), the checksum the first two bytes of SHA3-256(".onion checksum" | key |
// 0x03), 56 lower-case characters without ".onion". Client authorization keys travel as 32 raw bytes; Tor wants a service's
// authorized client as base32 without padding (ADD_ONION's ClientAuthV3, 52 characters) and a client's own key as base64
// (ONION_CLIENT_AUTH_ADD's "x25519:").
//
// SHA3-256 here is a small Keccak of Robot's own (FIPS 202), not the platform's: whether Android 14's providers offer
// "SHA3-256" isn't something this build can count on (minSdk 34), and a hash that only spells a checksum doesn't need a
// provider. OnionAddressTest checks it against the JDK's SHA3-256 and FIPS 202's examples. A mistake here could only spell
// a wrong checksum, which Tor refuses ("Invalid v3 address"): it can't reach a different service.

object OnionAddress {
    const val LENGTH = 56
    private const val VERSION: Byte = 3
    private val CHECKSUM = ".onion checksum".toByteArray(Charsets.US_ASCII) // l10n: not text (rend-spec-v3's constant)
    private const val B32 = "abcdefghijklmnopqrstuvwxyz234567" // l10n: not text (RFC 4648's base32 alphabet, lower case)

    /** [key]'s v3 address, 56 characters, without ".onion". */
    fun of(key: OnionKey): String {
        val k = key.key
        val sum = Sha3.sha256(CHECKSUM + k + VERSION)
        return base32(k + sum[0] + sum[1] + VERSION)
    }

    /** The key a v3 address spells (56 lower-case characters, without ".onion"), when its checksum and version are right. */
    fun keyOf(address: String): OnionKey? {
        if (address.length != LENGTH) return null
        val b = unbase32(address) ?: return null
        if (b.size != 35 || b[34] != VERSION) return null
        val k = b.copyOf(32)
        val sum = Sha3.sha256(CHECKSUM + k + VERSION)
        if (b[32] != sum[0] || b[33] != sum[1] || k.all { it.toInt() == 0 }) return null
        return OnionKey(k)
    }

    /** An x25519 public key as ADD_ONION's ClientAuthV3 wants it: base32 without padding, 52 characters. */
    fun authorizedClient(publicKey: ByteArray): String {
        require(publicKey.size == 32)
        return base32(publicKey)
    }

    /** RFC 4648 base32, lower case, no padding. */
    fun base32(b: ByteArray): String {
        val sb = StringBuilder((b.size * 8 + 4) / 5)
        var buf = 0
        var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buf ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** The bytes [s] spells in lower-case base32 without padding, when no bit is left over; else null. */
    fun unbase32(s: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(s.length * 5 / 8)
        var buf = 0
        var bits = 0
        for (c in s) {
            val v = B32.indexOf(c)
            if (v < 0) return null
            buf = (buf shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buf ushr (bits - 8)) and 0xFF)
                bits -= 8
            }
            buf = buf and ((1 shl bits) - 1)
        }
        return if (buf != 0) null else out.toByteArray()
    }
}

/** SHA3-256 (FIPS 202): Keccak-f[1600], rate 136 bytes, the domain bits 0x06. Only OnionAddress uses it. */
internal object Sha3 {
    private const val RATE = 136
    private val RC = listOf(
        0x0000000000000001uL, 0x0000000000008082uL, 0x800000000000808AuL, 0x8000000080008000uL,
        0x000000000000808BuL, 0x0000000080000001uL, 0x8000000080008081uL, 0x8000000000008009uL,
        0x000000000000008AuL, 0x0000000000000088uL, 0x0000000080008009uL, 0x000000008000000AuL,
        0x000000008000808BuL, 0x800000000000008BuL, 0x8000000000008089uL, 0x8000000000008003uL,
        0x8000000000008002uL, 0x8000000000000080uL, 0x000000000000800AuL, 0x800000008000000AuL,
        0x8000000080008081uL, 0x8000000000008080uL, 0x0000000080000001uL, 0x8000000080008008uL,
    ).map { it.toLong() }.toLongArray()
    private val ROT = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)

    fun sha256(input: ByteArray): ByteArray {
        val s = LongArray(25)
        val padded = input.size / RATE * RATE + RATE
        val m = input.copyOf(padded)
        m[input.size] = (m[input.size].toInt() xor 0x06).toByte()
        m[padded - 1] = (m[padded - 1].toInt() xor 0x80).toByte()
        var off = 0
        while (off < padded) {
            for (i in 0 until RATE / 8) s[i] = s[i] xor lane(m, off + 8 * i)
            permute(s)
            off += RATE
        }
        val out = ByteArray(32)
        for (i in 0 until 4) for (j in 0 until 8) out[8 * i + j] = (s[i] ushr (8 * j)).toByte()
        return out
    }

    private fun lane(b: ByteArray, at: Int): Long {
        var v = 0L
        for (j in 7 downTo 0) v = (v shl 8) or (b[at + j].toLong() and 0xFF)
        return v
    }

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            for (x in 0 until 5) for (y in 0 until 5) b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROT[x + 5 * y])
            for (x in 0 until 5) for (y in 0 until 5) a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            a[0] = a[0] xor RC[round]
        }
    }
}
