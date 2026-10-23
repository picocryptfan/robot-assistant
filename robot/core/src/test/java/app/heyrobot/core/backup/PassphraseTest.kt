// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The passphrase: each rule of the check, and the generator's six words from the list. */
class PassphraseTest {
    /** 7,776 distinct lower-case words, as the EFF long list has (its own file is an app asset, phase 2). */
    private val words: List<String> = (0 until 7_776).map { i ->
        val a = "bcdfghjklmnpqrstvwxz"
        val v = "aeiou"
        buildString {
            var n = i
            repeat(4) { k -> append(if (k % 2 == 0) a[n % a.length] else v[n % v.length]); n /= if (k % 2 == 0) a.length else v.length }
            append(a[n % a.length])
        }
    }

    private fun level(p: String, w: Set<String> = emptySet()) = PassphraseCheck.rate(p, w).level

    @Test fun tooShort() {
        assertEquals(PassphraseLevel.TOO_SHORT, level("Tr0ub4dor&3"))
        assertEquals(PassphraseLevel.TOO_SHORT, level(""))
        // Characters, not bytes: eleven accented letters are still too short.
        assertEquals(PassphraseLevel.TOO_SHORT, level("éèêëàâäôöûü"))
        assertTrue(level("éèêëàâäôöûüç") != PassphraseLevel.TOO_SHORT)
    }

    @Test fun tooEasy() {
        for (p in listOf("password1234", "aaaaaaaaaaaa", "qwertyuiop12", "azertyuiop!!", "123456789012", "robot2026robot", "motdepasse2025", "iloveyou1990")) {
            assertEquals(p, PassphraseLevel.TOO_EASY, level(p))
        }
    }

    @Test fun fairAndStrong() {
        // Twelve lower-case letters with nothing guessable: 12 × log2(26) ≈ 56 bits, Fair.
        assertEquals(PassphraseLevel.FAIR, level("kvmrtzdpwxjq"))
        // Every kind of character, longer: Strong.
        assertEquals(PassphraseLevel.STRONG, level("Kv7#mR2!tZ9@dP4"))
        assertEquals(PassphraseLevel.STRONG, level("correct horse battery staple is long"))
        // Accented letters count as their own kind.
        assertTrue(PassphraseCheck.rate("kvmrtzdpwxjé").bits > PassphraseCheck.rate("kvmrtzdpwxjq").bits)
        // A run, a sequence, a year or a common word counts about 10 bits, whatever its length.
        val digits = PassphraseCheck.rate("kvmrtzdp1739hnbg").bits
        assertEquals(digits - 4 * Math.log(36.0) / Math.log(2.0) + PassphraseCheck.PATTERN_BITS, PassphraseCheck.rate("kvmrtzdp1984hnbg").bits, 0.001)
        val plain = PassphraseCheck.rate("kvmrtzdpqxwjhnbg").bits
        for (p in listOf("kvmrtzdpabcdhnbg", "kvmrtzdpzzzzhnbg", "kvmrtzdpdcbahnbg", "kvmrtzdpasdfhnbg", "kvmrtzdrobothnbg")) {
            assertTrue(p, PassphraseCheck.rate(p).bits < plain)
        }
        assertEquals(PassphraseCheck.PATTERN_BITS, PassphraseCheck.rate("aaaaaaaaaaaa").bits, 0.001)
    }

    @Test fun wordsFromTheListCountAsWords() {
        val set = words.toSet()
        val four = words.take(4).joinToString("-")
        // Four words: 4 × 12.9 = 51.6 bits, Fair; six: 77.4, Strong.
        assertEquals(4 * PassphraseCheck.WORD_BITS, PassphraseCheck.rate(four, set).bits, 0.001)
        assertEquals(PassphraseLevel.FAIR, level(four, set))
        assertEquals(PassphraseLevel.STRONG, level(words.take(6).joinToString(" "), set))
        // Three words, or one not on the list: rated as characters.
        assertFalse(PassphraseCheck.rate(words.take(3).joinToString("-") + "-xyzzy", set).bits == 4 * PassphraseCheck.WORD_BITS)
    }

    @Test fun keysNeedStrong() {
        val fair = PassphraseCheck.rate("kvmrtzdpwxjq")
        val strong = PassphraseCheck.rate("Kv7#mR2!tZ9@dP4")
        assertTrue(PassphraseCheck.allowed(fair, keys = false))
        assertFalse(PassphraseCheck.allowed(fair, keys = true))
        assertTrue(PassphraseCheck.allowed(strong, keys = true))
        assertFalse(PassphraseCheck.allowed(PassphraseCheck.rate("password1234"), keys = false))
    }

    @Test fun aSpaceAtEitherEndIsFlaggedAndKept() {
        assertTrue(PassphraseCheck.rate(" kvmrtzdpwxjq").edgeSpace)
        assertTrue(PassphraseCheck.rate("kvmrtzdpwxjq ").edgeSpace)
        assertFalse(PassphraseCheck.rate("kvmrt zdpwxjq").edgeSpace)
    }

    @Test fun theGeneratorPicksSixWordsWithSecureRandom() {
        var calls = 0
        val random = object : SecureRandom() {
            override fun nextInt(bound: Int): Int {
                calls++
                return super.nextInt(bound)
            }
        }
        val maker = PassphraseMaker(words, random)
        val set = words.toSet()
        repeat(50) {
            val p = maker.make()
            val parts = p.split("-")
            assertEquals(6, parts.size)
            assertTrue(parts.all { it in set })
            assertEquals(p.lowercase(), p)
            assertEquals(PassphraseLevel.STRONG, level(p, set))
        }
        assertEquals(300, calls)
        assertEquals(77.5, maker.bits(), 0.05)
        assertEquals("PassphraseMaker(7776 words)", maker.toString())
        // Two in a row differ (2^-77 that they don't).
        assertFalse(maker.make() == maker.make())
    }

    @Test fun theWordListIsParsedStrictly() {
        assertEquals(listOf("abacus", "abdomen"), PassphraseMaker.parse("11111\tabacus\n11112\tabdomen\n\n"))
        assertEquals(listOf("abacus", "abdomen"), PassphraseMaker.parse("abacus\nabdomen"))
        assertNull(PassphraseMaker.parse("abacus\nAbdomen"))
        assertNull(PassphraseMaker.parse("abacus\nabacus"))
        assertNull(PassphraseMaker.parse("abacus"))
        assertTrue(runCatching { PassphraseMaker(listOf("a", "B")) }.isFailure)
        assertEquals("PassphraseRating(FAIR, 56 bits)", PassphraseCheck.rate("kvmrtzdpwxjq").toString())
    }
}
