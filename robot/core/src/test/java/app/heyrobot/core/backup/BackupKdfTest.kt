// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Argon2id through BouncyCastle: RFC 9106's vector, the header's bounds, NFC, nothing trimmed, zeroing. */
class BackupKdfTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 9106 §5.3: Argon2id, version 0x13, t=3, m=32 KiB, p=4, a 32-byte tag, with a secret and associated data. */
    @Test fun rfc9106Argon2idVector() {
        val tag = BackupKdf.argon2id(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            passes = 3,
            memoryKiB = 32,
            lanes = 4,
            length = 32,
            secret = ByteArray(8) { 0x03 },
            additional = ByteArray(12) { 0x04 },
        )
        assertArrayEquals(hex("0d 64 0d f5 8d 78 76 6c 08 c0 37 a3 4a 8b 53 c9 d0 1e f0 45 2d 75 b6 5e b5 25 20 e9 6b 01 e6 59"), tag)
    }

    /** RFC 7693 Appendix A: BLAKE2b-512("abc"), through Robot's copy of Bouncy Castle's digest (Argon2's hash). */
    @Test fun rfc7693Blake2bVector() {
        val d = app.heyrobot.thirdparty.bouncycastle.crypto.digests.Blake2bDigest(512)
        d.update("abc".toByteArray(), 0, 3)
        val out = ByteArray(64).also { d.doFinal(it, 0) }
        assertArrayEquals(hex("ba 80 a5 3f 98 1c 4d 0d 6a 27 97 b6 9f 12 f6 e9 4c 21 2f 14 68 5a c4 b7 4b 12 bb 6f db ff a2 d1" +
            "7d 87 c5 39 2a ab 79 2d c2 52 d5 de 45 33 cc 95 18 d3 8a a8 db f1 92 5a b9 23 86 ed d4 00 99 23"), out)
    }

    @Test fun theDesignsSettingsAreRfc9106sSecondOption() {
        assertEquals(KdfParams(BackupFormat.KDF_ARGON2ID, lanes = 4, passes = 3, memoryKiB = 65_536), KdfParams.CURRENT)
        assertTrue(BackupKdf.withinBounds(KdfParams.CURRENT))
        val h = BackupHeader.new(BackupKit.SALT, 1)
        assertEquals(KdfParams.CURRENT, BackupHeader.parse(h.encode()).kdf)
    }

    /** A stranger's file could ask for 4 GiB: outside t 1–10, m 19–256 MiB, p 1–8 the header is refused before any work. */
    @Test fun boundsAreRefused() {
        fun params(p: Int = 4, t: Int = 3, m: Int = 65_536) = KdfParams(BackupFormat.KDF_ARGON2ID, p, t, m)
        val bad = listOf(params(t = 0), params(t = 11), params(m = 19_455), params(m = 262_145), params(m = 4 * 1024 * 1024), params(p = 0), params(p = 9))
        for (k in bad) {
            assertFalse(k.toString(), BackupKdf.withinBounds(k))
            val bytes = BackupHeader(1, k, BackupKit.SALT, 1).encode()
            assertEquals(k.toString(), OpenProblem.KDF_REFUSED, BackupKit.problem { BackupHeader.parse(bytes) })
        }
        for (k in listOf(params(t = 1), params(t = 10), params(m = 19_456), params(m = 262_144), params(p = 1), params(p = 8))) {
            assertTrue(k.toString(), BackupKdf.withinBounds(k))
            BackupHeader.parse(BackupHeader(1, k, BackupKit.SALT, 1).encode())
        }
        // m over 2^31 KiB, written as an unsigned 32-bit number.
        val huge = BackupHeader(1, params(), BackupKit.SALT, 1).encode().also { it[16] = 0xFF.toByte() }
        assertEquals(OpenProblem.KDF_REFUSED, BackupKit.problem { BackupHeader.parse(huge) })
        // PBKDF2's id is reserved: no release writes it yet, so this reader refuses it.
        val pbkdf2 = BackupHeader(1, KdfParams(BackupFormat.KDF_PBKDF2, 0, 0, 1_200_000), BackupKit.SALT, 1).encode()
        assertEquals(OpenProblem.KDF_REFUSED, BackupKit.problem { BackupHeader.parse(pbkdf2) })
        // derive itself never runs outside them either.
        val outside = BackupHeader(1, params(m = 1024), BackupKit.SALT, 1)
        assertTrue(runCatching { BackupKdf.derive("x", outside) }.isFailure)
    }

    @Test fun nfcGivesOneKeyForTwoSpellings() {
        val composed = "café-crème-brûlée"
        val decomposed = "café-crème-brûlée"
        assertFalse(composed == decomposed)
        assertArrayEquals(BackupKdf.password(composed), BackupKdf.password(decomposed))
        val h = BackupHeader(1, BackupFixtures.LIGHT, BackupKit.SALT, 1)
        val a = BackupKdf.derive(composed, h)
        val b = BackupKdf.derive(decomposed, h)
        assertArrayEquals(a.bytes, b.bytes)
        // Nothing trimmed: a trailing space is part of the passphrase.
        BackupKdf.derive("$composed ", h).use { c -> assertFalse(c.bytes.contentEquals(a.bytes)) }
        // A different salt, a different key.
        BackupKdf.derive(composed, BackupHeader(1, BackupFixtures.LIGHT, ByteArray(16), 1)).use { d -> assertFalse(d.bytes.contentEquals(a.bytes)) }
        a.close()
        b.close()
    }

    @Test fun aLoneSurrogateIsRefusedNotGuessed() {
        assertTrue(runCatching { BackupKdf.password("abc\ud800def") }.isFailure)
    }

    @Test fun theBytesAreZeroed() {
        val pw = BackupKdf.password("a passphrase to zero")
        val key = BackupKdf.deriveFrom(pw, BackupKit.SALT, BackupFixtures.LIGHT)
        assertTrue("the password bytes are zeroed once Argon2id has them", pw.all { it == 0.toByte() })
        assertEquals(BackupKdf.KEY_BYTES, key.size)
        // Even when it fails.
        val pw2 = BackupKdf.password("another one")
        assertTrue(runCatching { BackupKdf.deriveFrom(pw2, BackupKit.SALT, KdfParams(1, 1, 1, 8)) }.isFailure)
        assertTrue(pw2.all { it == 0.toByte() })
        val k = BackupKey(key)
        val copy = k.bytes
        assertArrayEquals(key, copy)
        k.close()
        assertTrue(k.zeroed() && k.closed)
        assertTrue(runCatching { k.bytes }.isFailure)
        assertEquals("BackupKey(…)", k.toString())
    }

    /**
     * The design's settings on this machine: logged for the README. Not a speed limit,
     * just a sanity bound so a broken build that hangs is caught.
     */
    @Test fun timeTheDesignsSettings() {
        val h = BackupHeader.new(BackupKit.SALT, 1)
        BackupKdf.derive("warm-up-passphrase-here", BackupHeader(1, BackupFixtures.LIGHT, BackupKit.SALT, 1)).close()
        val times = (1..3).map {
            val t0 = System.nanoTime()
            BackupKdf.derive("crate-mosaic-unlocked-plaza-humming-oxygen", h).close()
            (System.nanoTime() - t0) / 1_000_000
        }
        System.out.println("BackupKdf: Argon2id t=3 m=64MiB p=4 took ${times.joinToString(", ")} ms (JVM ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cores)")
        assertTrue(times.min() < 60_000)
    }
}
