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

    fun i(event: String, vararg fields: Pair<String, Any?>) {
        try { Log.i(TAG, format(event, fields)) } catch (_: Throwable) {}
    }

    /** A failure: the exception's class name only (messages can hold paths or text). */
    fun w(event: String, t: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val all = if (t == null) fields else arrayOf(*fields, "error" to Code(t.javaClass.simpleName))
        try { Log.w(TAG, format(event, all)) } catch (_: Throwable) {}
    }
}
