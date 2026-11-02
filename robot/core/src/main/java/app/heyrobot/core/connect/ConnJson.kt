// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

// Imported under another name: a scan for a file name (code.json, Phone lock's) reads "SkillEncode.json" as one.
import app.heyrobot.core.skills.SkillEncode as Encoding

// Pure. JSON as connections.json and a request's body are written (RFC 8259): keys in the order given, a string with
// SkillEncode's escapes (every control character as \uXXXX, U+2028 and U+2029 too), a whole number as its digits, a
// number as Double.toString writes it (so it reads back a Double, its type kept), true, false and null. StrictJson reads
// it back to the same values. A value of any other kind, or a number that isn't finite, is refused (an exception).

object ConnJson {
    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    /** [s] as a JSON string. */
    fun string(s: String): String = Encoding.jsonString(s)

    fun write(out: StringBuilder, v: Any?) {
        when (v) {
            null -> out.append("null")
            is String -> out.append(Encoding.jsonString(v))
            is Boolean -> out.append(v.toString())
            is Int -> out.append(v.toString())
            is Long -> out.append(v.toString())
            is Double -> {
                require(v.isFinite())
                out.append(v.toString())
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, x) in v) {
                    require(k is String)
                    if (!first) out.append(',')
                    first = false
                    out.append(Encoding.jsonString(k)).append(':')
                    write(out, x)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) out.append(',')
                    write(out, x)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException()
        }
    }
}
