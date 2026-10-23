// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

/**
 * Layout-insensitive source checks for the scans (ProcessIsolationTest): a [Flat] is a source text (already through
 * [Source.strip], so comments are gone) with every run of whitespace made one space and the spaces beside punctuation
 * dropped, so re-indenting, re-wrapping a call or a condition, or a trailing comma never changes what a check sees, while
 * `val x` still differs from `valx`. A needle is flattened the same way and matched whole: a needle that begins or ends
 * with a letter, digit or `_` never matches inside a longer name.
 */
internal object Scan {
    private const val STRING = -1
    private val space = Regex("""\s+""")
    private val besidePunctuation = Regex(""" ?([^\w\s]) ?""")
    private val trailingComma = Regex(""",(?=[)\]}])""")

    fun flat(s: String): String = s.replace(space, " ").replace(besidePunctuation, "$1").replace(trailingComma, "").trim()

    /** The needle as a regex on flat text, anchored at word boundaries where it starts or ends with a word character. */
    fun needle(n: String): Regex {
        val f = flat(n)
        require(f.isNotEmpty()) { "empty needle" }
        val head = if (f.first().isLetterOrDigit() || f.first() == '_') """(?<![\w$])""" else ""
        val tail = if (f.last().isLetterOrDigit() || f.last() == '_') """(?![\w$])""" else ""
        return Regex(head + Regex.escape(f) + tail)
    }

    /**
     * From [from] (inside or after a parameter list that [open] parentheses are still open in), the index of the `{` that
     * starts the body and of its matching `}`; string and character literals (with `${…}` templates) are skipped.
     */
    fun braces(s: String, from: Int, open: Int): IntRange? {
        var parens = open
        var depth = 0
        var start = -1
        var i = from
        // Each entry: STRING for a string literal, or a template's code (`${`) inside one with its own brace depth (0 and up).
        val stack = ArrayDeque<Int>()
        while (i < s.length) {
            val c = s[i]
            val top = stack.lastOrNull()
            when {
                top == STRING -> when {
                    c == '\\' -> i++
                    c == '"' -> stack.removeLast()
                    c == '$' && i + 1 < s.length && s[i + 1] == '{' -> { stack.addLast(0); i++ }
                }
                c == '"' -> stack.addLast(STRING)
                c == '\'' -> { i++; while (i < s.length && s[i] != '\'') { if (s[i] == '\\') i++; i++ } }
                top != null -> when (c) {
                    '{' -> stack[stack.lastIndex] = top + 1
                    '}' -> if (top == 0) stack.removeLast() else stack[stack.lastIndex] = top - 1
                }
                c == '(' -> parens++
                c == ')' -> parens--
                c == '{' -> if (start >= 0) depth++ else if (parens <= 0) { start = i; depth = 1 }
                c == '}' -> if (start >= 0 && --depth == 0) return start..i
            }
            i++
        }
        return null
    }
}

/** A flattened source text; see [Scan]. */
internal class Flat(raw: String, flattened: Boolean = false) {
    private val continuation = Regex("""^(?:catch\([^)]*\)|finally|else(?: if\(.*?\))?)\{""")

    val text: String = if (flattened) raw else Scan.flat(raw)

    fun has(needle: String): Boolean = Scan.needle(needle).containsMatchIn(text)

    /** For a check that something is absent: the needle anywhere, even inside a longer name, so the check never narrows. */
    fun hasLoose(needle: String): Boolean = text.contains(Scan.flat(needle))

    /** Where [needle] first matches, or -1. */
    fun at(needle: String): Int = Scan.needle(needle).find(text)?.range?.first ?: -1

    fun count(needle: String): Int = Scan.needle(needle).findAll(text).count()

    fun matches(r: Regex): Boolean = r.containsMatchIn(text)

    fun count(r: Regex): Int = r.findAll(text).count()

    /**
     * The braces (inclusive) of the one declaration or call that starts with [signature]: a signature found twice or not
     * at all fails, so a second overload or a rename can't quietly point the check somewhere else.
     */
    fun body(signature: String): Flat {
        val hits = Scan.needle(signature).findAll(text).toList()
        if (hits.size != 1) throw AssertionError("${hits.size} matches for \"$signature\" (want exactly one)")
        val sig = Scan.flat(signature)
        val open = sig.count { it == '(' } - sig.count { it == ')' }
        // A signature that ends with the body's own brace starts there, whatever parentheses it leaves open.
        var r = (if (sig.endsWith("{")) Scan.braces(text, hits.single().range.last, 0) else Scan.braces(text, hits.single().range.last + 1, open))
            ?: throw AssertionError("no body after \"$signature\"")
        // An expression body goes on through its catch, finally and else blocks (`= try { … } catch (e: X) { … }`).
        while (true) {
            val more = continuation.find(text.substring(r.last + 1)) ?: break
            r = r.first..(Scan.braces(text, r.last + 1 + more.range.last, 0) ?: break).last
        }
        return Flat(text.substring(r), flattened = true)
    }

    /** The text after the first [start] up to the first [end] after it, both required. */
    fun between(start: String, end: String): Flat {
        val a = Scan.needle(start).find(text) ?: throw AssertionError("not found: \"$start\"")
        val b = Scan.needle(end).find(text, a.range.last + 1) ?: throw AssertionError("not found after \"$start\": \"$end\"")
        return Flat(text.substring(a.range.last + 1, b.range.first), flattened = true)
    }

    /** The argument lists of every call [call] begins (a regex ending at the call's open parenthesis). */
    fun calls(call: Regex): List<String> = call.findAll(text).map { m ->
        var depth = 1
        var i = m.range.last + 1
        var quote = false
        while (i < text.length && depth > 0) {
            val c = text[i]
            if (quote) { if (c == '\\') i++ else if (c == '"') quote = false } else when (c) {
                '"' -> quote = true
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        text.substring(m.range.last + 1, (i - 1).coerceAtLeast(m.range.last + 1))
    }.toList()

    override fun toString() = text
}

/** Every needle is present, and their first matches come in this order. */
internal fun assertInOrder(f: Flat, vararg needles: String) {
    val at = needles.map { n -> f.at(n).also { if (it < 0) throw AssertionError("not found: \"$n\"\nin: ${f.text.take(4000)}") } }
    for (k in 1 until at.size) if (at[k - 1] >= at[k]) throw AssertionError("\"${needles[k - 1]}\" is not before \"${needles[k]}\"")
}

/** Every needle is present. */
internal fun assertHas(f: Flat, vararg needles: String) {
    for (n in needles) if (!f.has(n)) throw AssertionError("not found: \"$n\"\nin: ${f.text.take(4000)}")
}

/** No needle is present. */
internal fun assertHasNot(f: Flat, vararg needles: String) {
    for (n in needles) if (f.hasLoose(n)) throw AssertionError("found: \"$n\"")
}
