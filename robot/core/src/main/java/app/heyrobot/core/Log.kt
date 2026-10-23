// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import android.util.Log

/**
 * Logging without content (spec item 10). Robot never logs what was said, typed, heard or shown on
 * screen. Events carry a fixed name and fields whose values are numbers, booleans, enums or [Code]s
 * (identifiers chosen by the code, never user data). Any other value, a String included, is replaced
 * by "<redacted>", so passing a message or a file name by mistake cannot leak it.
 */
object RLog {
    const val TAG = "Robot"
    const val REDACTED = "<redacted>"

    /** A fixed identifier written by the code ("npu", "wake"), never text that came from a person or a file. */
    @JvmInline
    value class Code(val value: String)

    /** "event k=v k2=v2"; pure, so it is unit-tested. */
    fun format(event: String, fields: Array<out Pair<String, Any?>>): String = buildString {
        append(event)
        for ((k, v) in fields) {
            append(' ').append(k).append('=')
            append(
                when (v) {
                    null -> "null"
                    is Boolean, is Int, is Long, is Short, is Byte -> v.toString()
                    is Float -> if (v.isFinite()) v.toString() else "nan"
                    is Double -> if (v.isFinite()) v.toString() else "nan"
                    is Enum<*> -> v.name
                    is Code -> v.value.filter { it.isLetterOrDigit() || it in "_-.:" }.take(40)
                    else -> REDACTED
                },
            )
        }
    }

    /**
     * Every line also goes here when set: the tests that grep what Robot logs (an API key must never appear) hook in
     * through it. Null in the app.
     */
    @Volatile var sink: ((String) -> Unit)? = null

    fun i(event: String, vararg fields: Pair<String, Any?>) {
        val line = format(event, fields)
        sink?.invoke(line)
        try { Log.i(TAG, line) } catch (_: Throwable) {}
    }

    /**
     * A failure: the exception's class name, then its causes' (cause1=, cause2=, …), names only: messages can hold paths or
     * text. 1.1: an ExceptionInInitializerError said nothing of the PatternSyntaxException under it.
     */
    fun w(event: String, t: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val all = if (t == null) fields else arrayOf(*fields, *errorFields(t))
        val line = format(event, all)
        sink?.invoke(line)
        try { Log.w(TAG, line) } catch (_: Throwable) {}
    }

    /** At most this many causes are named. */
    const val MAX_CAUSES = 4

    /** "error" to [t]'s class name, then "cause1", "cause2"… to its causes' (never a message), stopping at a loop. */
    fun errorFields(t: Throwable): Array<Pair<String, Any?>> {
        val out = arrayListOf<Pair<String, Any?>>("error" to Code(name(t)))
        val seen = mutableSetOf(t)
        var c = t.cause
        while (c != null && out.size <= MAX_CAUSES && seen.add(c)) {
            out += "cause${out.size}" to Code(name(c)) // l10n: not text (a log field)
            c = c.cause
        }
        return out.toTypedArray()
    }

    /** The class's simple name; an anonymous class has none, so its name after the package. */
    private fun name(t: Throwable): String = t.javaClass.simpleName.ifEmpty { t.javaClass.name.substringAfterLast('.') }
}
