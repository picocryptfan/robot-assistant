// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3; job L1): the small pieces every Robot to Robot format shares.
// Ids are 16 random bytes written as 32 lower-case hex characters; other byte strings (keys, hashes, tokens) are lower-case
// hex of a fixed length; the pairing code's text is base64url without padding. Every reader here is exact: one spelling
// per value (no upper case, no padding, no spare bits), so a value read and written again is the same text.

/** Why a Robot to Robot decoder refused its input: a code for the link log, never anything of the input itself. */
enum class LinkProblem(val code: Int) {
    TRUNCATED(1),
    TRAILING(2),
    TOO_LONG(3),
    BAD_MAGIC(4),
    BAD_VERSION(5),
    BAD_KIND(6),
    BAD_ROUTE(7),
    BAD_LENGTH(8),
    BAD_FIELD(9),
    BAD_TEXT(10),
    BAD_PADDING(11),
    NOT_JSON(12),
    NOT_CANONICAL(13),
    BAD_KEYS(14),
    BAD_TYPE(15),
    BAD_CHECKSUM(16),
    NO_CODE(17),
    TWO_CODES(18),
    NOT_HERE(19),
    /** L4: :linkparse failed on a request (it crashed, hung, or its answer broke LinkParseWire's rules or named another request). */
    PARSER(20),
    /** L5-fix: a pairing hello not signed by the identity key its bundle names (one copied from another Robot's hello). */
    BAD_SIGNATURE(21);

    companion object {
        fun of(code: Int): LinkProblem? = entries.firstOrNull { it.code == code }
    }
}

/** Thrown inside the binary readers only, and always caught at their edge: no message, no stack trace, nothing of the input. */
internal object LinkBad : RuntimeException() {
    private fun readResolve(): Any = LinkBad
    override fun fillInStackTrace(): Throwable = this
}

/** A big-endian reader over [b] that refuses to read past its end ([LinkBad]). */
internal class BinReader(private val b: ByteArray) {
    var at = 0
        private set
    val remaining: Int get() = b.size - at

    fun need(n: Int) { if (n < 0 || n > remaining) throw LinkBad }
    fun u8(): Int { need(1); return b[at++].toInt() and 0xFF }
    fun u16(): Int { need(2); return (u8() shl 8) or u8() }
    /** An unsigned 32-bit number, as a Long so 0xFFFFFFFF is 4,294,967,295 and never negative. */
    fun u32(): Long { need(4); return (u16().toLong() shl 16) or u16().toLong() }
    /** A 64-bit number as written by [BinWriter.u64] (two's complement). */
    fun u64(): Long { need(8); return (u32() shl 32) or u32() }
    fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(at, at + n).also { at += n } }
    fun end() { if (remaining != 0) throw LinkBad }
}

/** A big-endian writer for the formats above. */
internal class BinWriter(capacity: Int = 256) {
    private val buf = java.io.ByteArrayOutputStream(capacity)
    fun u8(v: Int) = apply { require(v in 0..0xFF); buf.write(v) }
    fun u16(v: Int) = apply { require(v in 0..0xFFFF); buf.write(v ushr 8); buf.write(v and 0xFF) }
    fun u32(v: Long) = apply { require(v in 0..0xFFFF_FFFFL); u16((v ushr 16).toInt()); u16((v and 0xFFFF).toInt()) }
    fun u64(v: Long) = apply { u32(v ushr 32); u32(v and 0xFFFF_FFFFL) }
    fun bytes(b: ByteArray) = apply { buf.write(b) }
    fun toByteArray(): ByteArray = buf.toByteArray()
}

object LinkBytes {
    private val HEX = "0123456789abcdef".toCharArray() // l10n: not text (hex digits)
    private val random by lazy { SecureRandom() }

    /** Lower-case hex of [b]. */
    fun hex(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) { val v = b[i].toInt() and 0xFF; c[2 * i] = HEX[v ushr 4]; c[2 * i + 1] = HEX[v and 15] }
        return String(c)
    }

    /** The [bytes] bytes [s] spells in lower-case hex, exactly that long; null otherwise (upper case included). */
    fun unhex(s: String, bytes: Int): ByteArray? {
        if (s.length != bytes * 2) return null
        val out = ByteArray(bytes)
        for (i in 0 until bytes) {
            val hi = digit(s[2 * i]); val lo = digit(s[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) { in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; else -> -1 }

    /** base64url without padding. */
    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** The bytes [s] spells in base64url, when it is the one spelling [b64] writes (no padding, no spare bits); else null. */
    fun unb64(s: String): ByteArray? {
        if (s.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
        val b = try { Base64.getUrlDecoder().decode(s) } catch (_: IllegalArgumentException) { return null }
        return b.takeIf { b64(it) == s }
    }

    /** Strict UTF-8 (no malformed or cut sequence, no lone surrogate), or null. */
    fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() } // l10n: not text (an algorithm name)

    /** Equal in time that doesn't depend on where they differ (tokens). */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun allZero(b: ByteArray): Boolean = b.all { it.toInt() == 0 }

    fun random(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    /** Unsigned, byte by byte, as libsignal's keys sort; a shorter prefix first. */
    fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}

/** Ids of requests, pairings and the things requests change: 16 random bytes, 32 lower-case hex characters, never all zero. */
object LinkIds {
    const val BYTES = 16

    fun isId(s: String): Boolean = LinkBytes.unhex(s, BYTES)?.let { !LinkBytes.allZero(it) } == true

    fun newId(): String {
        while (true) LinkBytes.random(BYTES).takeIf { !LinkBytes.allZero(it) }?.let { return LinkBytes.hex(it) }
    }
}

/**
 * Text another Robot sends in a field: a title, a note, a name. Read strictly (§4.5's "each typed and capped"): NFC, no
 * leading or trailing white space, at most [max] characters (code points), never blank, and none of the characters that
 * hide or reorder what a card shows or fake a line: C0 and C1 controls (a line feed only where a field allows lines),
 * the bidi controls, line and paragraph separators, the Unicode tag block ("ASCII smuggling"), interlinear annotations,
 * the byte-order mark and noncharacters. Zero-width joiners and variation selectors stay (emoji and some scripts need
 * them); the card and the model's frame strip invisibles again when they show the text (PeerQuote).
 */
object LinkText {
    fun forbidden(cp: Int, multiline: Boolean): Boolean =
        (cp < 0x20 && !(multiline && cp == 0x0A)) || cp in 0x7F..0x9F ||
            cp == 0x061C || cp == 0x200E || cp == 0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 ||
            cp == 0x2028 || cp == 0x2029 || cp in 0xE0000..0xE007F || cp in 0xFFF9..0xFFFB || cp == 0xFEFF ||
            cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE

    /** [s] may travel in a field of at most [max] characters. */
    fun ok(s: String, max: Int, multiline: Boolean = false): Boolean {
        if (s.isEmpty() || s.isBlank() || s != s.trim()) return false
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isSurrogate(s[i]) && Character.charCount(cp) == 1) return false
            if (forbidden(cp, multiline)) return false
            n++
            if (n > max) return false
            i += Character.charCount(cp)
        }
        if (multiline && (s.contains("\n\n\n") || s.lines().any { it != it.trimEnd() })) return false
        return Normalizer.isNormalized(s, Normalizer.Form.NFC)
    }

    /**
     * What a sender puts in such a field from [s]: NFC, the forbidden characters dropped (CR LF and CR become a line feed
     * where lines are allowed, a space elsewhere), each line's end trimmed, at most two blank lines in a row, cut to [max]
     * characters on a character's boundary, trimmed. Null when nothing is left.
     */
    fun clean(s: String, max: Int, multiline: Boolean = false): String? {
        val src = Normalizer.normalize(s.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
        val sb = StringBuilder()
        var i = 0
        while (i < src.length) {
            val cp = src.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0x0A && !multiline -> sb.append(' ')
                cp == 0x09 -> sb.append(' ')
                Character.isSurrogate(cp.toChar()) && cp <= 0xFFFF -> {}
                forbidden(cp, multiline) -> {}
                else -> sb.appendCodePoint(cp)
            }
        }
        var t = sb.toString()
        if (multiline) {
            t = t.lines().joinToString("\n") { it.trimEnd() }
            while (t.contains("\n\n\n")) t = t.replace("\n\n\n", "\n\n")
        }
        t = t.trim()
        if (t.codePointCount(0, t.length) > max) t = t.substring(0, t.offsetByCodePoints(0, max)).trim()
        t = Normalizer.normalize(t, Normalizer.Form.NFC)
        return t.takeIf { it.isNotEmpty() && ok(it, max, multiline) }
    }
}
