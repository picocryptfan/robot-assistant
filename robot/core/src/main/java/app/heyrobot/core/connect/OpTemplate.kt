// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

// Pure. Templates inside an operation (plan §3.3, C2): `{{param}}` names one of the operation's own arguments, as the whole
// value (a typed slot: a number stays a number in JSON) or inside words (text). Nothing else: no expressions, no
// filters, no dots, no nesting. Filled in one pass, so a value that holds `{{x}}` is never read as a template. (A skill's
// own templates, `{{who}}` and `{{eta.minutes}}`, are filled before a value reaches the operation: C4.)

object OpTemplate {
    /** An argument's name: as SkillCheck's step arguments (`[a-z][a-z0-9_]{0,31}`). */
    val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val WHOLE = Regex("^\\{\\{([a-z][a-z0-9_]{0,31})\\}\\}$")

    /** A piece of a template: words as written, or an argument. */
    sealed interface Part {
        data class Words(val text: String) : Part
        data class Slot(val name: String) : Part
    }

    /** The argument [v] names when it is exactly one template, else null. */
    fun whole(v: Any?): String? = (v as? String)?.let { WHOLE.matchEntire(it)?.groupValues?.get(1) }

    /** [text] in pieces, or null when a "{{" or "}}" in it isn't a template (refused). */
    fun parts(text: String): List<Part>? {
        val out = mutableListOf<Part>()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf("{{", i)
            val words = if (open < 0) text.substring(i) else text.substring(i, open)
            if ("}}" in words) return null
            if (words.isNotEmpty()) out += Part.Words(words)
            if (open < 0) break
            val close = text.indexOf("}}", open + 2)
            if (close < 0) return null
            val name = text.substring(open + 2, close)
            if (!NAME.matches(name)) return null
            out += Part.Slot(name)
            i = close + 2
        }
        return out
    }

    /** The words of [text] without its templates (what SecretDetector reads), or null when it doesn't read. */
    fun words(text: String): String? = parts(text)?.filterIsInstance<Part.Words>()?.joinToString(" ") { it.text }

    /** The arguments [text] names, in order; null when it doesn't read. */
    fun names(text: String): List<String>? = parts(text)?.filterIsInstance<Part.Slot>()?.map { it.name }

    /** Every string in a JSON template [tree] (values only, never keys), in order. */
    fun strings(tree: Any?): List<String> = when (tree) {
        is String -> listOf(tree)
        is Map<*, *> -> tree.values.flatMap(::strings)
        is List<*> -> tree.flatMap(::strings)
        else -> emptyList()
    }
}
