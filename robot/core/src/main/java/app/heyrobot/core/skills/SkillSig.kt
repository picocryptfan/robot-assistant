// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

// Pure (java.security only, no new dependency). A shared skill's signature (skills design §11.1): ES256, ECDSA on P-256
// with SHA-256 (RFC 7518 §3.4), its value the 64 bytes r ‖ s, with s at most n/2 so one signature has one form. The key
// travels as its SubjectPublicKeyInfo (DER, RFC 5480), only in the one encoding a P-256 key with an uncompressed point
// has, so a key has one fingerprint: the SHA-256 of those 91 bytes. The signing key itself is S3.4's (the Android
// Keystore's `robot_skill_signing`, never exportable); this side only ever sees its public half and its signatures.

/**
 * What signs an export (S3.4: the Keystore key). [publicKey]: its SubjectPublicKeyInfo, DER; [sign]: a SHA256withECDSA
 * signature over the bytes, DER-encoded as java.security and the Android Keystore give it.
 */
interface SkillSigner {
    val publicKey: ByteArray
    fun sign(data: ByteArray): ByteArray
}

object SkillSig {
    const val ALG = "ES256" // l10n: not text (the signature's algorithm, RFC 7518)
    private const val VERIFY = "SHA256withECDSA" // l10n: not text (a java.security algorithm)
    private const val EC = "EC" // l10n: not text (a java.security algorithm)
    /** A P-256 SubjectPublicKeyInfo's first 27 bytes: id-ecPublicKey, prime256v1, a 66-byte bit string, an uncompressed point. */
    private val SPKI_PREFIX = bytes("3059301306072a8648ce3d020106082a8648ce3d03010703420004")
    private const val SPKI_SIZE = 91
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val HALF_N = N.shiftRight(1)

    /** [spki]'s fingerprint: the SHA-256 of the key's SubjectPublicKeyInfo, 64 lowercase hex digits (SkillImport.signer). */
    fun fingerprint(spki: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(spki)) // l10n: not text (an algorithm)

    /** A fingerprint as Settings › Skills › Sharing shows it: its first 16 hex digits, upper case, in groups of 4 ("5A7A 78CC A4A0 F420"). */
    fun shown(fingerprint: String): String = fingerprint.take(16).uppercase(java.util.Locale.ROOT).chunked(4).joinToString(" ")

    /** [spki] as a P-256 key, or null when it's anything else: another curve or encoding, extra bytes, a point off the curve. */
    fun publicKey(spki: ByteArray): ECPublicKey? = try {
        if (spki.size != SPKI_SIZE || !spki.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX)) null
        else {
            val x = BigInteger(1, spki.copyOfRange(27, 59))
            val y = BigInteger(1, spki.copyOfRange(59, 91))
            val onCurve = x < P && y < P && y.multiply(y).subtract(x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B)).mod(P).signum() == 0
            if (!onCurve) null
            else (KeyFactory.getInstance(EC).generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey).takeIf { it.encoded.contentEquals(spki) }
        }
    } catch (_: Exception) { null }

    /** Whether [sig] (r ‖ s, s low) is [spki]'s ES256 signature of [data]. Anything malformed is false. */
    fun verify(spki: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        val key = publicKey(spki)
        if (key == null || sig.size != 64) false
        else {
            val r = BigInteger(1, sig.copyOfRange(0, 32))
            val s = BigInteger(1, sig.copyOfRange(32, 64))
            if (r.signum() == 0 || r >= N || s.signum() == 0 || s > HALF_N) false
            else Signature.getInstance(VERIFY).run {
                initVerify(key)
                update(data)
                verify(der(r, s))
            }
        }
    } catch (_: Exception) { false }

    /** A DER signature (what [SkillSigner.sign] gives) as r ‖ s with s made low, or null when it isn't one. */
    fun raw(der: ByteArray): ByteArray? = try {
        require(der.size >= 8 && der[0] == 0x30.toByte() && der[1].toInt() and 0xff == der.size - 2)
        val (r, at) = integer(der, 2)
        val (s0, end) = integer(der, at)
        require(end == der.size && r.signum() > 0 && r < N && s0.signum() > 0 && s0 < N)
        val s = if (s0 > HALF_N) N.subtract(s0) else s0
        fixed(r) + fixed(s)
    } catch (_: Exception) { null }

    private fun integer(b: ByteArray, at: Int): Pair<BigInteger, Int> {
        require(b[at] == 0x02.toByte())
        val len = b[at + 1].toInt() and 0xff
        require(len in 1..33 && at + 2 + len <= b.size)
        val v = b.copyOfRange(at + 2, at + 2 + len)
        require(v[0] >= 0 && (len == 1 || v[0] != 0.toByte() || v[1] < 0))
        return BigInteger(1, v) to at + 2 + len
    }

    private fun der(r: BigInteger, s: BigInteger): ByteArray {
        val a = r.toByteArray()
        val b = s.toByteArray()
        return byteArrayOf(0x30, (a.size + b.size + 4).toByte(), 0x02, a.size.toByte()) + a + byteArrayOf(0x02, b.size.toByte()) + b
    }

    private fun fixed(v: BigInteger): ByteArray = v.toByteArray().let { b -> if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }

    fun hex(b: ByteArray): String = StringBuilder().apply { for (x in b) append(HEX[x.toInt() shr 4 and 15]).append(HEX[x.toInt() and 15]) }.toString()

    private fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private const val HEX = "0123456789abcdef"
}
