// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

// Pure (org.json). RFC 8785's JSON Canonicalization Scheme over a parsed tree, for what a shared skill's signature covers
// (skills design §11.1): no whitespace, object keys sorted by UTF-16 code units, strings escaped as ECMAScript's
// JSON.stringify does (only `"`, `\` and controls, controls without a short form as lowercase \u00xx), numbers as
// ECMAScript's Number.prototype.toString writes a double. I-JSON's limits are kept: a lone surrogate, a non-finite number
// or a whole number beyond 2^53 isn't canonical and throws, so the file holding it is refused, never signed.

object Jcs {
    private const val MAX_SAFE = 9007199254740992L
    private const val MAX_DEPTH = 64

    /** [v]'s canonical form in UTF-8. Throws IllegalArgumentException when [v] holds what RFC 8785 can't write. */
    fun bytes(v: Any?): ByteArray = text(v).toByteArray(Charsets.UTF_8)

    fun text(v: Any?): String = StringBuilder().also { write(it, v, 0) }.toString()

    private fun write(b: StringBuilder, v: Any?, depth: Int) {
        require(depth <= MAX_DEPTH)
        when (v) {
            null -> b.append("null") // l10n: not text (JSON)
            is Boolean -> b.append(if (v) "true" else "false") // l10n: not text (JSON)
            is String -> string(b, v)
            is Int, is Long, is Short, is Byte -> b.append(whole((v as Number).toLong()))
            is BigInteger -> b.append(whole(v.also { require(it.bitLength() < 63) }.toLong()))
            is Double, is Float, is BigDecimal -> b.append(number((v as Number).toDouble()))
            is JSONObject -> {
                b.append('{')
                for ((i, k) in v.keys().asSequence().toList().sorted().withIndex()) {
                    if (i > 0) b.append(',')
                    string(b, k)
                    b.append(':')
                    write(b, v.get(k), depth + 1)
                }
                b.append('}')
            }
            is JSONArray -> {
                b.append('[')
                for (i in 0 until v.length()) {
                    if (i > 0) b.append(',')
                    write(b, v.get(i), depth + 1)
                }
                b.append(']')
            }
            else -> if (v == JSONObject.NULL) b.append("null") else throw IllegalArgumentException() // l10n: not text (JSON)
        }
    }

    private fun whole(n: Long): String = n.also { require(it in -MAX_SAFE..MAX_SAFE) }.toString()

    /** ECMAScript's Number.prototype.toString (ES2015 §7.1.12.1): the shortest digits that read back as [d]. */
    fun number(d: Double): String {
        require(!d.isNaN() && !d.isInfinite())
        if (d == 0.0) return "0"
        val exact = BigDecimal(Math.abs(d))
        var r = exact
        for (p in 1..17) {
            r = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (r.toString().toDouble() == Math.abs(d)) break
        }
        r = r.stripTrailingZeros()
        val digits = r.unscaledValue().toString()
        val k = digits.length
        val n = k - r.scale()
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                (if (k == 1) digits else digits[0] + "." + digits.substring(1)) + "e" + (if (e < 0) "-" else "+") + Math.abs(e) // l10n: not text (a number)
            }
        }
        return if (d < 0) "-$body" else body
    }

    private fun string(b: StringBuilder, s: String) {
        b.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\b' -> b.append("\\b")
                c == '\t' -> b.append("\\t")
                c == '\n' -> b.append("\\n")
                c == '\u000c' -> b.append("\\f")
                c == '\r' -> b.append("\\r")
                c < ' ' -> b.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                Character.isHighSurrogate(c) -> {
                    require(i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))
                    b.append(c).append(s[i + 1])
                    i++
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException()
                else -> b.append(c)
            }
            i++
        }
        b.append('"')
    }

    private const val HEX = "0123456789abcdef"
}
