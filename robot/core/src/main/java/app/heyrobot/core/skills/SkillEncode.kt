// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

// Pure. Values as a step uses them, and how each is written where it goes (design §3.5, connections plan §5.1, C1).
//
// Every value keeps where it came from (ValueSource): fixed in the skill by you, your words this turn, the model's words,
// read on the phone, or from outside (a connection's reply, a web page, a script's output). A typed value from outside (a
// number, a known state) may flow on; free text from outside going into a send needs a tap (C4's CONNECTION_TAP), and
// never becomes a condition's value or a Termux argument. A value made of words and templates carries every part's source.
//
// Encoding by place: JSON-escaped in a body, percent-encoded (RFC 3986, UTF-8) in a path or query, one argument-list
// element for Termux (never joined and split again), a URI-encoded slot or a typed extra for a hand-off. A slot of one
// type refuses a value of another. Each encoder gives null when a value can't go there; nothing is repaired.

/** Where a value came from (design §3.5). */
enum class ValueSource {
    /** Saved in the skill by you. */
    FIXED,
    /** A question filled from your own words this turn. */
    YOURS,
    /** A question the model filled with words that aren't yours. */
    MODEL,
    /** Read on the phone: calendar, notes, contacts, phone status. */
    PHONE,
    /** From outside the phone: a connection's reply, the weather, a web page, a script's output. */
    ONLINE;

    companion object {
        fun of(f: FillSource): ValueSource = when (f) {
            FillSource.YOURS -> YOURS
            FillSource.MODEL -> MODEL
        }
    }
}

/**
 * A value as a step uses it: [value] typed as [SkillTemplate.typed] gives it (String, Long, Double or Boolean), [type] its
 * type, [sources] where its parts came from, and [free] the sources of the free text in it (words, not a number or a known
 * state): what decides a tap.
 */
data class SkillValue(val value: Any, val type: ParamType, val sources: Set<ValueSource>, val free: Set<ValueSource>) {
    /** Free text from outside the phone is in it: a send needs a tap first (plan §5.4, CONNECTION_TAP). */
    val onlineText: Boolean get() = ValueSource.ONLINE in free

    /** May be a Termux script's argument (D15): only what you fixed, said, or the model filled; nothing read on the phone or from outside. */
    val mayBeArgument: Boolean get() = sources.all { it == ValueSource.FIXED || it == ValueSource.YOURS || it == ValueSource.MODEL }

    companion object {
        fun of(value: Any, type: ParamType, source: ValueSource): SkillValue =
            SkillValue(value, type, setOf(source), if (type.free) setOf(source) else emptySet())

        /** A question's value as a step uses it: typed, from [source]. */
        fun of(p: SkillParam, v: String, source: ValueSource): SkillValue = of(SkillTemplate.typed(p.type, v), p.type, source)

        /**
         * A text argument with its templates filled (one pass): [lookup] gives each template's value. Null when one isn't
         * there or has no words (a yes_no, a duration): C4 decides with the step's condition, never by guessing.
         */
        fun inText(text: String, lookup: (SkillRef) -> SkillValue?): SkillValue? {
            val parts = SkillTemplate.parts(text) ?: return null
            val sources = mutableSetOf<ValueSource>()
            val free = mutableSetOf<ValueSource>()
            val out = StringBuilder()
            for (p in parts) when (p) {
                is SkillTemplate.Part.Words -> { out.append(p.text); sources += ValueSource.FIXED; free += ValueSource.FIXED }
                is SkillTemplate.Part.Ref -> {
                    val v = lookup(p.ref) ?: return null
                    if (!SkillEncode.hasWords(v.type)) return null
                    out.append(SkillTemplate.text(v.value))
                    sources += v.sources
                    free += v.free
                }
            }
            return SkillValue(out.toString(), ParamType.TEXT, sources, free)
        }
    }
}

object SkillEncode {
    /** Whether a value of [t] can sit inside words: not a yes or no, and not a duration's seconds. */
    fun hasWords(t: ParamType): Boolean = t != ParamType.YES_NO && t != ParamType.DURATION

    /** A value of [v]'s type fits a slot of [slot]'s: words take any value that has words; a number takes a whole number too; a choice only one of its values. */
    fun fits(v: SkillValue, slot: SlotType): Boolean = when (slot.type) {
        ParamType.TEXT -> hasWords(v.type)
        ParamType.NUMBER -> v.type == ParamType.NUMBER || v.type == ParamType.INTEGER
        ParamType.CHOICE -> v.type == ParamType.CHOICE && v.value is String && (slot.options.isEmpty() || v.value in slot.options)
        else -> v.type == slot.type
    }

    /** [v] as a JSON value in a body's slot of [slot]: a string escaped, a number, true or false. */
    fun json(v: SkillValue, slot: SlotType): String? {
        if (!fits(v, slot)) return null
        return when (slot.type) {
            ParamType.INTEGER -> (v.value as? Long)?.toString()
            ParamType.NUMBER -> (v.value as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let(SkillNumbers::text)
            ParamType.YES_NO -> (v.value as? Boolean)?.toString()
            else -> jsonString(SkillTemplate.text(v.value))
        }
    }

    /** [s] as a JSON string (RFC 8259): quotes and backslashes escaped, every control character as \uXXXX, U+2028 and U+2029 too. */
    fun jsonString(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' || c == '\u007f' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code)) // l10n: not text (a JSON escape)
            else -> append(c)
        }
        append('"')
    }

    /**
     * [v] in a path segment of a slot of [slot], percent-encoded. Never a "/", "\", ".." or a control character, so a
     * value can't climb out of its place; a choice of yours may hold "/" (kept as is), never "..".
     */
    fun path(v: SkillValue, slot: SlotType): String? = urlPart(v, slot)

    /**
     * [v] in a query's value, percent-encoded whole ("/" as %2F), so only a control character is refused (C12's L5: a
     * query value is words, not a path: "On my way..." and "back at 3/4" go).
     */
    fun query(v: SkillValue, slot: SlotType): String? = words(v, slot)

    /**
     * [v] in a hand-off's data address (plan §3.5), percent-encoded. [inPath]: the slot sits in the address's path (an
     * https address's, or an app's own "scheme:/…" one, before any "?" or "#"), where [path]'s rules hold; anywhere else
     * (a query, a fragment, an address with no path such as sms: or tel:) as [query].
     */
    fun uri(v: SkillValue, slot: SlotType, inPath: Boolean): String? = if (inPath) urlPart(v, slot) else words(v, slot)

    /** Words in a query or outside a path: every character but a control one, percent-encoded. */
    private fun words(v: SkillValue, slot: SlotType): String? {
        if (!fits(v, slot) || v.type == ParamType.YES_NO) return null
        val s = SkillTemplate.text(v.value)
        if (s.isEmpty() || s.any { it < ' ' || it == '\u007f' }) return null
        return percent(s)
    }

    private fun urlPart(v: SkillValue, slot: SlotType): String? {
        if (!fits(v, slot) || v.type == ParamType.YES_NO) return null
        val s = SkillTemplate.text(v.value)
        if (s.isEmpty() || s.any { it < ' ' || it == '\u007f' } || '\\' in s) return null
        val choice = slot.type == ParamType.CHOICE
        if ((!choice && '/' in s) || s.split('/').any { it == ".." } || (!choice && ".." in s)) return null
        return percent(s, keep = if (choice) "/" else "")
    }

    /** RFC 3986: every UTF-8 byte except the unreserved characters (and [keep]) as %XX. */
    fun percent(s: String, keep: String = ""): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~' || c in keep) append(c)
            else append('%').append("%02X".format(b.toInt() and 0xff)) // l10n: not text (percent-encoding)
        }
    }

    /**
     * [v] as one element of a Termux script's argument list (D18): one line, no control characters, nothing read on the
     * phone or from outside (D15), and never starting with "-" unless the script said `dash=ok`. Never joined with others or
     * split again.
     */
    fun argument(v: SkillValue, slot: SlotType, dashOk: Boolean = false): String? {
        if (!fits(v, slot) || !v.mayBeArgument) return null
        val s = when (slot.type) {
            ParamType.YES_NO -> if (v.value == true) "yes" else "no" // l10n: not text (a script's argument, as its `# param: yes_no` reads it)
            else -> SkillTemplate.text(v.value)
        }
        if (s.any { it < ' ' || it == '\u007f' || it == ' ' || it == ' ' }) return null
        if (s.startsWith("-") && !dashOk) return null
        return s
    }

    /** [v] as a hand-off's extra (plan §3.5): a String, a whole number or a switch, nothing else. */
    fun extra(v: SkillValue, slot: SlotType): Any? {
        if (!fits(v, slot)) return null
        return when (slot.type) {
            ParamType.INTEGER -> v.value as? Long
            ParamType.YES_NO -> v.value as? Boolean
            ParamType.NUMBER -> null
            else -> SkillTemplate.text(v.value).takeIf { s -> '\u0000' !in s }
        }
    }
}
