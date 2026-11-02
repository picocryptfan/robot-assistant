// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link.requests

import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkText

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3.3; job L1): the canonical JSON writer and the checks each request
// body's keys go through. A request is read with core StrictJson (RFC 8259, UTF-8, a depth cap), its keys checked here
// (exactly the type's keys, each value typed and capped, JSON null never), then written again with [LinkJson]: only text
// that is byte for byte what this writer makes is accepted (LinkRequests.decode). So there is one spelling of each request:
// no white space, keys in the type's order, strings with JSON's short escapes and \u00xx for the other controls and
// everything else as UTF-8, whole numbers only (amounts in minor units, never decimals).

internal object LinkJson {
    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null") // l10n: not text (JSON; no request writes one, the tests do)
            is String -> string(v, sb)
            is Long, is Int -> sb.append(v.toString())
            is Boolean -> sb.append(if (v) "true" else "false") // l10n: not text (JSON)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(k as String, sb)
                    sb.append(':')
                    write(x, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(x, sb) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException()
        }
    }

    private fun string(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u00").append("0123456789abcdef"[c.code ushr 4]).append("0123456789abcdef"[c.code and 15]) // l10n: not text (hex digits)
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

/** What one value of a body may be. */
internal abstract class Spec {
    abstract fun ok(v: Any?): Boolean
}

/** Text another person wrote (LinkText): at most [max] characters, lines only where [lines]. */
internal class TextS(val max: Int, val lines: Boolean = false) : Spec() {
    override fun ok(v: Any?) = v is String && LinkText.ok(v, max, lines)
}

/** An id: 32 lower-case hex characters, not all zeros. */
internal object IdS : Spec() {
    override fun ok(v: Any?) = v is String && LinkIds.isId(v)
}

/** [bytes] bytes in lower-case hex, not all zeros. */
internal class HexS(val bytes: Int) : Spec() {
    override fun ok(v: Any?) = v is String && LinkBytes.unhex(v, bytes)?.let { !LinkBytes.allZero(it) } == true
}

/** A whole number in [min]..[max], and a multiple of [step]. */
internal class NumS(val min: Long, val max: Long, val step: Long = 1) : Spec() {
    override fun ok(v: Any?) = v is Long && v in min..max && v % step == 0L
}

internal object BoolS : Spec() {
    override fun ok(v: Any?) = v is Boolean
}

/** One of a few words. */
internal class OneOfS(vararg values: String) : Spec() {
    val values: Set<String> = values.toSet()
    override fun ok(v: Any?) = v is String && v in values
}

/** ASCII checked by [check] (a currency, a date, an address). */
internal class AsciiS(val max: Int, val check: (String) -> Boolean) : Spec() {
    override fun ok(v: Any?) = v is String && v.length <= max && v.all { it in ' '..'~' } && check(v)
}

/** A list of [min] to [max] values, each [of]; [unique]: no value twice. */
internal class ListS(val of: Spec, val min: Int, val max: Int, val unique: Boolean = false) : Spec() {
    override fun ok(v: Any?) = v is List<*> && v.size in min..max && v.all(of::ok) && (!unique || v.toSet().size == v.size)
}

/** An object with exactly the [required] keys and any of the [optional] ones, each value as its spec says. */
internal class ObjS(val required: Map<String, Spec>, val optional: Map<String, Spec> = emptyMap()) : Spec() {
    override fun ok(v: Any?): Boolean {
        if (v !is Map<*, *>) return false
        if (!v.keys.containsAll(required.keys)) return false
        for ((k, x) in v) {
            val spec = required[k] ?: optional[k] ?: return false
            if (!spec.ok(x)) return false
        }
        return true
    }
}

/** A JSON object built in the order its keys are put, absent values left out. */
internal class JsonObj {
    val map = LinkedHashMap<String, Any?>()
    fun put(k: String, v: Any?) = apply { if (v != null) map[k] = v }
}

internal fun obj(build: JsonObj.() -> Unit): Map<String, Any?> = JsonObj().apply(build).map

// Readers of a body already checked by its ObjS: the casts can't fail.
@Suppress("UNCHECKED_CAST")
internal class Fields(private val m: Map<String, Any?>) {
    fun s(k: String) = m[k] as String
    fun so(k: String) = m[k] as String?
    fun n(k: String) = m[k] as Long
    fun no(k: String) = m[k] as Long?
    fun b(k: String) = m[k] as Boolean
    fun bo(k: String) = m[k] as Boolean?
    fun strings(k: String) = m[k] as List<String>
    fun stringsOrEmpty(k: String) = (m[k] as List<String>?) ?: emptyList()
    fun longs(k: String) = m[k] as List<Long>
    fun objs(k: String) = (m[k] as List<Map<String, Any?>>).map(::Fields)
    fun objOrNull(k: String) = (m[k] as Map<String, Any?>?)?.let(::Fields)
    fun lists(k: String) = m[k] as List<List<Long>>
}
