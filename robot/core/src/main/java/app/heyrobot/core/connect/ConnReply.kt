// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ControlText
import app.heyrobot.core.PromptMarkers
import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.StepCheck
import app.heyrobot.core.skills.ValueSource
import java.util.Locale

// Pure. A connection's reply (plan D15, §3; C2). Every reply is untrusted: information, never instructions. At most
// 256 KiB (C3 reads one byte more and says so: a reply cut at the cap is refused, never read in part), JSON or plain text
// only, read with StrictJson from its bytes (UTF-8 exactly, RFC 8259, 32 levels). Only 2xx is a success; a redirect is
// never followed, so it is a failure too. Only the declared outputs are kept, each typed: a choice only one of the
// values you listed, a number only a number (Home Assistant's states are strings: "21.5" reads as 21.5), a yes or no
// only a yes or no; "unavailable" or anything else that doesn't fit leaves the output unset, never wrong. Free text is
// cleaned of control and format characters, cut to 1,500 characters and has its turn markers broken (ControlText), and
// keeps its source: ValueSource.ONLINE. A server's own error text is never read out.

/** What a reply gave. */
sealed class ReplyRead {
    /** A 2xx reply: [json] its value when JSON was wanted (null otherwise, or the text "null"). */
    class Ok(val status: Int, val json: Any?) : ReplyRead() {
        override fun toString(): String = "ReplyRead.Ok($status)" // l10n: not text (toString, for the logs)
    }

    /** Why it didn't work, with the HTTP [status] when there was one. */
    data class Failed(val why: ConnFailure, val status: Int? = null) : ReplyRead()
}

/** A pointer's target: [Found] (its value, which may be JSON null) or missing. */
sealed interface Pointed {
    data class Found(val value: Any?) : Pointed
    data object Missing : Pointed
}

object ConnReply {
    const val MAX_BYTES = 256 * 1024
    const val MAX_TEXT = 1500
    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")
    private val SPACES = Regex("\\s+")
    private val INTEGER = Regex("^-?[0-9]{1,18}$")
    private val NUMBER = Regex("^-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,17})?([eE][+-]?[0-9]{1,3})?$")
    private val markers by lazy { ControlText(PromptMarkers.KNOWN) }

    /** The failure an HTTP [status] means, or null for 2xx. */
    fun failure(status: Int): ConnFailure? = when (status) {
        in 200..299 -> null
        401 -> ConnFailure.KEY_REFUSED
        403 -> ConnFailure.NOT_ALLOWED
        429 -> ConnFailure.TOO_MANY
        else -> ConnFailure.SAID_NO
    }

    /**
     * Reads a reply: its [status], its Content-Type [contentType], its [body] (at most [max] bytes, [MAX_BYTES] for a
     * step's call; [over]: there was more, so it is refused when it is read). [wantJson]: outputs or a check read it, so it
     * must be JSON (labelled as JSON or plain text, or not labelled) and fit the cap; otherwise it is dropped unread,
     * whatever its size (C12's L4: a write whose server answers with a whole page did its work).
     */
    fun read(status: Int, contentType: String?, body: ByteArray, over: Boolean, wantJson: Boolean, max: Int = MAX_BYTES): ReplyRead {
        failure(status)?.let { return ReplyRead.Failed(it, status) }
        if (!wantJson) return ReplyRead.Ok(status, null)
        if (over || body.size > max) return ReplyRead.Failed(ConnFailure.UNREADABLE, status)
        if (!jsonOrText(contentType)) return ReplyRead.Failed(ConnFailure.UNREADABLE, status)
        val r = StrictJson.parse(body) ?: return ReplyRead.Failed(ConnFailure.UNREADABLE, status)
        return ReplyRead.Ok(status, r.value)
    }

    /** JSON (application/json, application/…+json), plain text, or no label; never a page (a captive portal's login, an error page). */
    fun jsonOrText(contentType: String?): Boolean {
        val t = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return true
        return t.isEmpty() || t == "application/json" || (t.startsWith("application/") && t.endsWith("+json")) || t == "text/plain" // l10n: not text (media types)
    }

    /** RFC 6901's [pointer] in [json]: "" is the whole value; "/a/0" a key, then an index ("~1" is "/", "~0" is "~"). */
    fun pointer(json: Any?, pointer: String): Pointed {
        if (pointer.isEmpty()) return Pointed.Found(json)
        if (!pointer.startsWith("/")) return Pointed.Missing
        var at: Any? = json
        for (raw in pointer.substring(1).split('/')) {
            val token = raw.replace("~1", "/").replace("~0", "~")
            at = when (at) {
                is Map<*, *> -> if (at.containsKey(token)) at[token] else return Pointed.Missing
                is List<*> -> {
                    if (token.isEmpty() || (token.length > 1 && token[0] == '0') || !token.all { it in '0'..'9' } || token.length > 9) return Pointed.Missing
                    at.getOrNull(token.toInt()) ?: if (token.toInt() < at.size) null else return Pointed.Missing
                }
                else -> return Pointed.Missing
            }
        }
        return Pointed.Found(at)
    }

    /** The declared [outputs] found in [json], each typed, from outside the phone. One that is missing or doesn't fit is left out (unset). */
    fun outputs(json: Any?, outputs: Map<String, OpOutput>): Map<String, SkillValue> {
        val out = LinkedHashMap<String, SkillValue>()
        for ((name, o) in outputs) {
            val found = pointer(json, o.pointer) as? Pointed.Found ?: continue
            typed(found.value, o)?.let { out[name] = it }
        }
        return out
    }

    /** A reply's [raw] value as output [o] takes it, or null. */
    fun typed(raw: Any?, o: OpOutput): SkillValue? {
        val v: Any = when (o.type) {
            ParamType.CHOICE -> (raw as? String)?.takeIf { it in o.options } ?: return null
            ParamType.INTEGER -> when (raw) {
                is Long -> raw
                is Double -> raw.takeIf { it.isFinite() && it == Math.floor(it) && Math.abs(it) < 1e15 }?.toLong() ?: return null
                is String -> raw.trim().takeIf { INTEGER.matches(it) }?.toLongOrNull() ?: return null
                else -> return null
            }
            ParamType.NUMBER -> when (raw) {
                is Long -> raw.toDouble()
                is Double -> raw.takeIf { it.isFinite() } ?: return null
                is String -> raw.trim().takeIf { NUMBER.matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                else -> return null
            }
            ParamType.YES_NO -> when (raw) {
                is Boolean -> raw
                is String -> when (raw.trim().lowercase(Locale.ROOT)) {
                    "true", "on", "yes" -> true // l10n: not text (values in a reply)
                    "false", "off", "no" -> false // l10n: not text (values in a reply)
                    else -> return null
                }
                else -> return null
            }
            ParamType.TEXT -> when (raw) {
                is String -> clean(raw).ifEmpty { return null }
                is Long, is Double, is Boolean -> raw.toString()
                else -> return null
            }
            else -> return null
        }
        return SkillValue.of(v, o.type, ValueSource.ONLINE)
    }

    /**
     * Free text from a reply as Robot keeps it: control and format characters (and U+2028, U+2029) as spaces, white space
     * collapsed, cut to [MAX_TEXT] characters (never inside a character), then every model's turn markers broken. In that
     * order: breaking adds an invisible joiner, which stripping would take out again.
     */
    fun clean(s: String): String {
        val plain = CONTROL.replace(s, " ").replace(SPACES, " ").trim()
        val cut = if (plain.length <= MAX_TEXT) plain else plain.substring(0, if (plain[MAX_TEXT - 1].isHighSurrogate()) MAX_TEXT - 1 else MAX_TEXT).trimEnd()
        return markers.defuse(cut)
    }

    /** A check's or a reply's words as a number: digits, with one decimal point or comma; never "1e5" or "1d". */
    fun number(s: String): Double? = if (WORDS_NUMBER.matches(s.trim())) s.trim().replace(',', '.').toDoubleOrNull() else null

    /** A step's (or an operation's) check: [check]'s pointer in [json] equals its value (numbers compared as numbers, a number in words included). */
    fun check(json: Any?, check: StepCheck.Equals): Boolean {
        val found = pointer(json, check.pointer) as? Pointed.Found ?: return false
        val v = found.value
        val want = check.equals
        return when {
            want is Boolean || v is Boolean -> v == want
            want is Number && v is Number -> v.toDouble() == want.toDouble()
            want is Number && v is String -> number(v) == want.toDouble()
            want is String && v is Number -> number(want) == v.toDouble()
            want is String && v is String -> v == want
            else -> false
        }
    }

    private val WORDS_NUMBER = Regex("^-?[0-9]+([.,][0-9]+)?$")
}
