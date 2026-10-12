package app.heyrobot

/**
 * What the source scans (ProcessIsolationTest and the like) read of a Kotlin or Java file: its comments gone, its string
 * literals kept or emptied to `""`. A small lexer rather than regexes, so a string such as the "any file" type (star, slash,
 * star) or "https://…" is never read as a comment's start, and a quote in a comment never as a string's: it walks string
 * literals (escapes, and Kotlin's `${…}` templates, whose code may hold strings of its own), raw strings and Java text
 * blocks, character literals ('"', '\''), backticked names, line comments and block comments (nested in Kotlin, as its
 * compiler reads them).
 */
internal object Source {
    fun strip(src: String, keepStrings: Boolean, kotlin: Boolean = true): String = Lexer(src, keepStrings, kotlin).apply { code(template = false) }.out.toString()

    private class Lexer(private val s: String, private val keep: Boolean, private val kotlin: Boolean) {
        val out = StringBuilder(s.length)
        private var i = 0

        /** Code to the end, or (in a template) to the brace that closes it, which is written and consumed. */
        fun code(template: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                    s.startsWith("/*", i) -> comment()
                    s.startsWith("\"\"\"", i) -> raw()
                    c == '"' -> string()
                    c == '\'' -> char()
                    c == '`' -> name()
                    template && c == '{' -> { depth++; out.append(c); i++ }
                    template && c == '}' -> {
                        out.append(c)
                        i++
                        if (depth-- == 0) return
                    }
                    else -> { out.append(c); i++ }
                }
            }
        }

        /** A block comment, gone; in Kotlin one may hold another, and it ends where the outer one does. */
        private fun comment() {
            i += 2
            var depth = 1
            while (i < s.length && depth > 0) when {
                s.startsWith("*/", i) -> { depth--; i += 2 }
                kotlin && s.startsWith("/*", i) -> { depth++; i += 2 }
                else -> i++
            }
        }

        /** "…" with its escapes and templates; it ends at its quote (or, unterminated, at the line's end). */
        private fun string() {
            val start = out.length
            out.append('"')
            i++
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> { out.append(s, i, minOf(i + 2, s.length)); i += 2 }
                    c == '"' -> { out.append(c); i++; break }
                    c == '\n' -> break
                    kotlin && s.startsWith("\${", i) -> { out.append("\${"); i += 2; code(template = true) }
                    else -> { out.append(c); i++ }
                }
            }
            if (!keep) { out.setLength(start); out.append("\"\"") }
        }

        /** """…""" (no escapes; it ends at the last quote of the first run of three or more). */
        private fun raw() {
            val start = out.length
            out.append("\"\"\"")
            i += 3
            while (i < s.length) {
                if (s.startsWith("\"\"\"", i)) {
                    var j = i
                    while (j < s.length && s[j] == '"') j++
                    out.append(s, i, j)
                    i = j
                    break
                }
                if (kotlin && s.startsWith("\${", i)) { out.append("\${"); i += 2; code(template = true); continue }
                out.append(s[i])
                i++
            }
            if (!keep) { out.setLength(start); out.append("\"\"") }
        }

        /** 'x', '"', '\'', a unicode escape: kept as they are. */
        private fun char() {
            val end = if (i + 1 < s.length && s[i + 1] == '\\') s.indexOf('\'', i + 3) else if (i + 2 < s.length && s[i + 2] == '\'') i + 2 else -1
            if (end < 0) { out.append(s[i]); i++; return }
            out.append(s, i, end + 1)
            i = end + 1
        }

        /** `a name with spaces`: kept as it is. */
        private fun name() {
            val end = s.indexOf('`', i + 1).takeIf { it >= 0 && s.indexOf('\n', i + 1).let { n -> n < 0 || n > it } } ?: i
            out.append(s, i, end + 1)
            i = end + 1
        }
    }
}
