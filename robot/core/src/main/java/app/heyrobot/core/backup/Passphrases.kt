// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

// Pure. The backup's passphrase: Robot's generated one ([PassphraseMaker], the default: six words from a
// word list, about 77.5 bits with the EFF long list's 7,776 words), or yours, checked by [PassphraseCheck]. Neither is
// ever stored. The thresholds are EST; the estimate can't know a song lyric or a pet's name, so the screen says the
// generated one is the safe choice.

enum class PassphraseLevel {
    /** Fewer than [PassphraseCheck.MIN_CHARS] characters: "Use at least 12 characters, or Robot's passphrase." */
    TOO_SHORT,
    /** Under [PassphraseCheck.FAIR_BITS]: Back up stays greyed. */
    TOO_EASY,
    /** Allowed, "Robot's passphrase is stronger"; not with Keys and private codes. */
    FAIR,
    STRONG,
}

/** A passphrase's rating: its [level], the estimate in [bits], and whether it starts or ends with a space (the field warns). */
data class PassphraseRating(val level: PassphraseLevel, val bits: Double, val edgeSpace: Boolean) {
    override fun toString(): String = "PassphraseRating($level, ${bits.toInt()} bits)"  // l10n: not text (toString for logs and tests)
}

object PassphraseCheck {
    const val MIN_CHARS = 12
    const val FAIR_BITS = 45.0
    const val STRONG_BITS = 65.0
    /** A word of a [WORD_LIST_SIZE]-word list, picked at random: log2(7776) ≈ 12.9 bits. */
    const val WORD_BITS = 12.9
    const val WORD_LIST_SIZE = 7_776
    /** What a run, a sequence, a year or a common word is worth to a guesser, whatever its length (EST). */
    const val PATTERN_BITS = 10.0

    /** Rows of the keyboards people use here (and digits), for sequences like "qwerty", "azerty", "1234". */
    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "azertyuiop", "qsdfghjklm", "wxcvbn", "1234567890", "abcdefghijklmnopqrstuvwxyz")
    /** Words a guesser tries first (English and French), counted as one guess each. */
    private val COMMON = listOf("password", "passw0rd", "motdepasse", "robot", "bonjour", "soleil", "iloveyou", "jetaime", "admin", "welcome",
        "letmein", "monkey", "dragon", "football", "baseball", "master", "hello", "secret", "princess", "sunshine", "azerty", "qwerty",
        "loulou", "chouchou", "doudou", "marseille", "paris", "montreal", "quebec", "canada", "france", "amour", "trustno1", "shadow",
        "superman", "batman", "pokemon", "starwars", "123456", "abc123")
    private val YEAR = Regex("(19|20)[0-9]{2}")
    private val SPLIT = Regex("[\\s\\-_.,;:/]+")

    /**
     * Rates [passphrase] (NFC, as the key uses it). Four or more words, all from [words] (lower case), count
     * [WORD_BITS] each. Anything else: log2(the pool of its kinds of characters: lower case 26, upper case 26, digits 10,
     * symbols 33, other letters such as accented ones 32) for each character, except that a run of one character (3 or
     * more), a keyboard or alphabet sequence (4 or more, either way), a year from 1900 to 2099 and a common word count
     * [PATTERN_BITS] each, whatever their length: a guesser tries them first.
     */
    fun rate(passphrase: String, words: Set<String> = emptySet()): PassphraseRating {
        val p = Normalizer.normalize(passphrase, Normalizer.Form.NFC)
        val edge = p.isNotEmpty() && (p.first().isWhitespace() || p.last().isWhitespace())
        val length = p.codePointCount(0, p.length)
        if (length < MIN_CHARS) return PassphraseRating(PassphraseLevel.TOO_SHORT, 0.0, edge)
        val parts = p.lowercase(Locale.ROOT).split(SPLIT).filter { it.isNotEmpty() }
        val bits = if (words.isNotEmpty() && parts.size >= 4 && parts.all { it in words }) parts.size * WORD_BITS else charBits(p, length)
        val level = when {
            bits < FAIR_BITS -> PassphraseLevel.TOO_EASY
            bits < STRONG_BITS -> PassphraseLevel.FAIR
            else -> PassphraseLevel.STRONG
        }
        return PassphraseRating(level, bits, edge)
    }

    /** Whether Back up may go ahead: Strong, or Fair without keys in the backup. */
    fun allowed(r: PassphraseRating, keys: Boolean): Boolean = r.level == PassphraseLevel.STRONG || (r.level == PassphraseLevel.FAIR && !keys)

    private fun charBits(p: String, length: Int): Double {
        var pool = 0
        if (p.any { it in 'a'..'z' }) pool += 26
        if (p.any { it in 'A'..'Z' }) pool += 26
        if (p.any { it in '0'..'9' }) pool += 10
        if (p.any { it.code in 0x20..0x7E && !it.isLetterOrDigit() }) pool += 33
        if (p.any { it.code > 0x7E }) pool += 32
        val patterns = patterns(p.lowercase(Locale.ROOT))
        val covered = patterns.sumOf { it.last - it.first + 1 }
        // Code points, as the length is: a pattern is found in ASCII only, so its chars are code points.
        return (length - covered) * ln(pool.toDouble()) / ln(2.0) + patterns.size * PATTERN_BITS
    }

    /** The guessable pieces of [s], as ranges that don't overlap (the longest first where two would). */
    private fun patterns(s: String): List<IntRange> {
        val found = ArrayList<IntRange>()
        var i = 0
        while (i < s.length) {
            var j = i
            while (j + 1 < s.length && s[j + 1] == s[i]) j++
            if (j - i + 1 >= 3) found += i..j
            i = j + 1
        }
        i = 0
        while (i + 4 <= s.length) {
            val k = (i + 4..s.length).lastOrNull { e -> sequence(s.substring(i, e)) }
            if (k != null) { found += i until k; i = k } else i++
        }
        YEAR.findAll(s).forEach { found += it.range }
        for (w in COMMON) {
            var at = s.indexOf(w)
            while (at >= 0) {
                found += at until at + w.length
                at = s.indexOf(w, at + w.length)
            }
        }
        val out = ArrayList<IntRange>()
        for (r in found.sortedWith(compareByDescending<IntRange> { it.last - it.first }.thenBy { it.first })) {
            if (out.none { it.first <= r.last && r.first <= it.last }) out += r
        }
        return out
    }

    private fun sequence(t: String): Boolean = ROWS.any { r -> r.contains(t) || r.reversed().contains(t) }
}

/**
 * Robot's passphrase: [count] words from [words], each picked with [random] (SecureRandom), lower case, joined by
 * hyphens. [bits] = count × log2(the list's size): 77.5 for six of the EFF long list's 7,776.
 */
class PassphraseMaker(words: List<String>, private val random: SecureRandom = SecureRandom()) {
    private val list: List<String> = words

    init {
        require(list.size >= 2 && list.toSet().size == list.size) { "list" }
        require(list.all { w -> w.isNotEmpty() && w.all { it in 'a'..'z' || it == '-' } }) { "words" }
    }

    val size: Int get() = list.size

    fun bits(count: Int = WORDS): Double = count * ln(list.size.toDouble()) / ln(2.0)

    fun make(count: Int = WORDS): String = (1..count).joinToString("-") { list[random.nextInt(list.size)] }

    override fun toString(): String = "PassphraseMaker(${list.size} words)"  // l10n: not text (toString for logs and tests)

    companion object {
        const val WORDS = 6

        /**
         * A word list as text: one word a line, or the EFF lists' "dice<TAB>word" lines. Blank lines are skipped; anything
         * that isn't a lower-case word refuses the list (null).
         */
        fun parse(text: String): List<String>? {
            val words = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringAfter('\t').trim() }.toList()
            return words.takeIf { w -> w.size >= 2 && w.toSet().size == w.size && w.all { x -> x.isNotEmpty() && x.all { it in 'a'..'z' || it == '-' } } }
        }
    }
}
