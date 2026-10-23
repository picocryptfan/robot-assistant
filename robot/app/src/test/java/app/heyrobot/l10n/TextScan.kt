// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.l10n

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * The user-visible text scan, a JVM port of robot/tools/strings_inventory.py's
 * lexer and classifier: both read robot/l10n/scan_rules.json, so the report and the guard ([UserTextGuardTest]) agree
 * on every literal. Keep the two in step: a change to one is made to the other (`strings_inventory.py robot --check`
 * compares the script's view with the baselines this test writes).
 */
internal object TextScan {
    /** One message: literals joined by + are one; a string inside a template is one of its own ([inTemplate]). */
    data class Message(val line: Int, val text: String, val raw: Boolean, val call: String, val named: String, val before: String, val inTemplate: Boolean)

    /** A classified message of a source file (its path from robot/). [guard]: "baseline", "marked", "bad-marker" or "". */
    data class Row(val file: String, val line: Int, val kind: String, val lang: String, val text: String, val area: String?, val guard: String) {
        val sha1: String get() = sha1(text)
    }

    fun sha1(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val ESCAPES = mapOf('n' to "\n", 't' to "\t", 'r' to "\r", '"' to "\"", '\'' to "'", '\\' to "\\", '$' to "$", 'b' to "\b")

    private class Lit(val start: Int, val line: Int) {
        var end = start
        var text = ""
        var raw = false
        var holes = 0
        val inner = mutableListOf<Lit>()
    }

    /** The lexer: the literals of [src] and its code mask (comments and string contents blanked, each opening quote kept). */
    private class Lexer(private val src: String) {
        val n = src.length
        val lits = mutableListOf<Lit>()
        val code = CharArray(n) { ' ' }
        private var line = 1

        fun run(): Lexer {
            var i = 0
            while (i < n) {
                val c = src[i]
                if (src.startsWith("//", i)) {
                    val k = src.indexOf('\n', i)
                    i = if (k == -1) n else k
                    continue
                }
                if (src.startsWith("/*", i)) {
                    var depth = 0
                    while (i < n) {
                        if (src.startsWith("/*", i)) { depth++; i += 2 }
                        else if (src.startsWith("*/", i)) {
                            depth--
                            i += 2
                            if (depth == 0) break
                        } else {
                            if (src[i] == '\n') line++
                            i++
                        }
                    }
                    continue
                }
                if (c == '"') {
                    val lit = readString(i)
                    lits += lit
                    code[lit.start] = '"'
                    i = lit.end
                    continue
                }
                if (c == '\'') { i = skipChar(i); continue }
                if (c == '\n') line++
                code[i] = c
                i++
            }
            return this
        }

        private fun skipChar(j: Int): Int {
            if (src.startsWith("'\\u", j)) return j + 8
            if (src.startsWith("'\\", j)) return j + 4
            val k = src.indexOf('\'', j + 1)
            return if (k != -1 && k - j <= 3) k + 1 else j + 1
        }

        private fun readString(start: Int): Lit {
            val raw = src.startsWith("\"\"\"", start)
            val lit = Lit(start, line)
            lit.raw = raw
            val text = StringBuilder()
            var i = start + if (raw) 3 else 1
            while (i < n) {
                val c = src[i]
                if (raw && src.startsWith("\"\"\"", i)) {
                    var j = i
                    while (j + 3 < n && src[j + 3] == '"') { text.append('"'); j++ }
                    i = j + 3
                    break
                }
                if (!raw && c == '"') { i++; break }
                if (!raw && c == '\\' && i + 1 < n) {
                    val e = src[i + 1]
                    if (e == 'u') {
                        val hex = src.substring(i + 2, minOf(i + 6, n))
                        text.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 6
                        continue
                    }
                    text.append(ESCAPES[e] ?: e.toString())
                    i += 2
                    continue
                }
                if (c == '$' && i + 1 < n && src[i + 1] == '{') {
                    var depth = 0
                    var j = i + 1
                    while (j < n) {
                        val cj = src[j]
                        when {
                            cj == '{' -> { depth++; j++ }
                            cj == '}' -> {
                                depth--
                                j++
                                if (depth == 0) break
                            }
                            cj == '"' -> {
                                val sub = readString(j)
                                lit.inner += sub
                                j = sub.end
                            }
                            cj == '\'' -> j = skipChar(j)
                            cj == '\n' -> { line++; j++ }
                            else -> j++
                        }
                    }
                    text.append("{}")
                    lit.holes++
                    i = j
                    continue
                }
                if (c == '$' && i + 1 < n) {
                    val m = IDENT.matchAt(src, i + 1)
                    if (m != null) {
                        text.append("{}")
                        lit.holes++
                        i = m.range.last + 1
                        continue
                    }
                }
                if (c == '\n') line++
                text.append(c)
                i++
            }
            lit.end = i
            lit.text = text.toString()
            return lit
        }
    }

    private fun identChar(c: Char, extra: String) = c.isLetterOrDigit() || c in extra

    /** The call holding [pos] ('{name}', '{block}', '[' for other brackets) and the named argument or assigned name. */
    private fun enclosingCall(code: CharArray, pos: Int): Pair<String, String?> {
        var depth = 0
        var j = pos - 1
        var named: String? = null
        while (j >= 0) {
            val c = code[j]
            if (c in ")]}") depth++
            else if (c in "([{") {
                if (depth == 0) {
                    var k = j - 1
                    if (c == '(' || c == '{') {
                        while (k >= 0 && code[k] in " \t\n") k--
                        if (c == '{' && k >= 0 && code[k] == ')') return "{block}" to named
                        val e = k + 1
                        while (k >= 0 && identChar(code[k], "_.?!")) k--
                        val name = String(code, k + 1, e - (k + 1)).trim('.', '?', '!')
                        return (if (c == '(') name else "{$name}") to named
                    }
                    return "[" to named
                }
                depth--
            } else if (c == ',' && depth == 0 && named == null) named = ""
            else if (c == '=' && depth == 0 && named == null) {
                if (j > 0 && code[j - 1] in "=!<>") Unit
                else if (j + 1 < code.size && code[j + 1] == '=') Unit
                else {
                    var k = j - 1
                    while (k >= 0 && code[k] in " \t") k--
                    val e = k + 1
                    while (k >= 0 && identChar(code[k], "_")) k--
                    named = String(code, k + 1, e - (k + 1))
                }
            }
            j--
        }
        return "" to named
    }

    private fun linePrefix(code: CharArray, pos: Int): String {
        var k = pos - 1
        while (k >= 0 && code[k] != '\n') k--
        return String(code, k + 1, pos - (k + 1)).trim()
    }

    fun messages(src: String): List<Message> {
        val lx = Lexer(src).run()
        val groups = mutableListOf<MutableList<Lit>>()
        var cur: MutableList<Lit>? = null
        for (lit in lx.lits) {
            if (cur != null) {
                if (String(lx.code, cur.last().end, lit.start - cur.last().end).trim() == "+") { cur += lit; continue }
                groups += cur
            }
            cur = mutableListOf(lit)
        }
        if (cur != null) groups += cur
        val out = mutableListOf<Message>()
        for (g in groups) {
            val first = g[0]
            val (call, named) = enclosingCall(lx.code, first.start)
            out += Message(first.line, g.joinToString("") { it.text }, g.any { it.raw }, call, named ?: "", linePrefix(lx.code, first.start).takeLast(100), false)
            for (x in g) for (sub in x.inner) out += Message(sub.line, sub.text, sub.raw, "{template}", "", "", true)
        }
        return out
    }

    /** robot/l10n/scan_rules.json. */
    class Rules(file: File) {
        val json = JSONObject(file.readText())
        private val p: Map<String, Regex> = json.getJSONObject("patterns").let { o ->
            o.keys().asSequence().associateWith { k ->
                Pattern.compile(o.getString(k), Pattern.UNICODE_CHARACTER_CLASS or if (k == "logcode_enum") Pattern.DOTALL else 0).toRegex()
            }
        }
        operator fun get(name: String): Regex = p.getValue(name)
        private fun list(key: String): List<String> = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        fun set(key: String): Set<String> = sets.getValue(key)
        private val sets = listOf("error_calls", "animate_calls", "ui_sink_not_visible", "visible_named", "tool_calls", "prompt_files", "diag_files",
            "fr_words", "en_words", "brands").associateWith { list(it).toSet() }
        val modules = list("modules")
        val exclude = list("exclude").map { Regex(it) }
        val allowedDirs = list("allowed_dirs").map { Regex(it) }
        val marker = Regex(json.getString("marker"))
        val markerAny = Regex(json.getString("marker_any"))
        val areaNames = list("area_names")
        private val areas: List<Pair<String, Regex>> = json.getJSONArray("areas").let { a ->
            (0 until a.length()).map { (a.get(it) as JSONArray).let { x -> x.getString(0) to Regex(x.getString(1)) } }
        }

        fun area(rel: String): String? = areas.firstOrNull { it.second.containsMatchIn(rel) }?.first
    }

    private val FOLD = "àâäéèêëîïôöùûüç".zip("aaaeeeeiioouuuc").toMap()

    private fun cps(s: String): Int = s.codePointCount(0, s.length)

    fun langOf(r: Rules, t: String): String {
        val tl = t.lowercase()
        val ws = r["lang_word"].findAll(tl).map { m -> m.value.map { FOLD[it] ?: it }.joinToString("") }.toList()
        val fr = ws.count { it in r.set("fr_words") } + 2 * r["fr_accent"].findAll(t).count() + 2 * r["fr_elision"].findAll(t).count()
        val en = ws.count { it in r.set("en_words") } + 2 * r["en_contraction"].findAll(tl).count()
        return if (fr > en) "FR" else "EN"
    }

    /** Line ranges of the enum classes that implement LogCode: the voice log's titles (diagnostics). */
    fun logcodeRanges(r: Rules, src: String): List<IntRange> = r["logcode_enum"].findAll(src).map { m ->
        var i = m.range.last + 1
        var depth = 1
        while (i < src.length && depth > 0) {
            if (src[i] == '{') depth++ else if (src[i] == '}') depth--
            i++
        }
        (src.substring(0, m.range.first).count { it == '\n' } + 1)..(src.substring(0, i).count { it == '\n' } + 1)
    }.toList()

    /** The kind of a message, as strings_inventory.py's classify(): the first rule that matches. */
    fun classify(r: Rules, rel: String, m: Message, ranges: List<IntRange>): String {
        val t = m.text
        val f = rel.substringAfterLast('/')
        val call = m.call
        val last = call.substringAfterLast('.')
        val named = m.named
        val before = m.before
        val stripped = t.replace("{}", " ")
        val ws = r["word"].findAll(stripped).map { it.value }.toList()
        if (m.inTemplate && ws.size < 2) return "glue"
        if (ws.isEmpty() || (ws.size == 1 && cps(ws[0]) <= 2 && !r["whitespace"].containsMatchIn(stripped.trim()))) return "glue"
        if (t.trim() in r.set("brands")) return "brand"
        if (last.startsWith("animate") || last in r.set("animate_calls")) return "key"
        if (r["log_call"].containsMatchIn(call) || call == "Log" || call == "RLog") return "log"
        if (last in r.set("error_calls") || before.trimEnd().endsWith("throw") || "Exception(" in before.takeLast(30)) return "error"
        if (f in r.set("diag_files") || (r["enum_call"].containsMatchIn(last) && ranges.any { m.line in it })) return "diag"
        val length = cps(t)
        val letters = t.codePoints().filter { Character.isLetter(it) }.count()
        if ((length > 12 && letters.toDouble() / length < 0.35) || r["svg_path"].containsMatchIn(t)) return "data"
        if (("SYSTEM" in named || "PROMPT" in named || r["prompt_call"].containsMatchIn(call) || "Prompt" in call) && ws.size >= 2) return "prompt"
        if ((r["regex_call"].containsMatchIn(call) && last in setOf("Regex", "toRegex", "compile", "containsMatchIn", "rx", "re")) ||
            (m.raw && r["regex_syntax"].containsMatchIn(t))) return "regex"
        if (r["regex_syntax"].containsMatchIn(t) && (' ' !in t || t.count { it == '\\' } >= 2 || "(?" in t)) return "regex"
        if (r["addr"].containsMatchIn(t.trim())) return "addr"
        if (last == "ofPattern" || (r["format"].containsMatchIn(t.trim()) && ws.size <= 3 && ' ' !in t.trim().drop(1) && !r["latin3"].containsMatchIn(t))) return "format"
        if (r["hex"].containsMatchIn(t.trim())) return "data"
        val multi = ws.size >= 2 && r["multi"].containsMatchIn(stripped)
        if (last in r.set("tool_calls") || (named == "description" && f == "Tools.kt")) return if (multi) "prompt" else "key"
        val sink = r["ui_sink"].containsMatchIn(call)
        val sinkVisible = sink && last !in r.set("ui_sink_not_visible")
        val visibleNamed = named in r.set("visible_named")
        if ((rel.startsWith("app") || rel.startsWith("voice")) && (sinkVisible || visibleNamed) && (multi || r["starts_upper"].containsMatchIn(t))) return "visible"
        if ((f in r.set("prompt_files") || "Prompt" in f || "PROMPT" in named || "Prompt" in named || "prompt" in named || "promptLine" in before) && multi) return "prompt"
        if (multi && r["prompt_rx"].containsMatchIn(t) && langOf(r, t) == "EN" && !r["you"].containsMatchIn(t.replace("the user", ""))) return "prompt"
        if (multi && r["the_user"].containsMatchIn(t) && langOf(r, t) == "EN") return "prompt"
        if (r["fr_accent"].containsMatchIn(t) && "Router" !in f && ws.isNotEmpty() && !r["regex_syntax"].containsMatchIn(t)) return "visible"
        if ("{}" in t && ws.isNotEmpty() && "Router" !in f && !r["json_call"].containsMatchIn(call) && r["word3"].containsMatchIn(stripped)) return "visible"
        if (r["json_call"].containsMatchIn(call) && !multi && !sink) return "key"
        if (r["cap_word_end"].containsMatchIn(t.trim())) return "visible"
        if (r["ident_shape"].containsMatchIn(t) && !r["whitespace"].containsMatchIn(t)) {
            if (r["cap_word"].containsMatchIn(t)) {
                return if (sink || visibleNamed || r["enum_call"].containsMatchIn(last) || before.trimEnd().endsWith("->")) "visible" else "single"
            }
            if (r["lower_word"].containsMatchIn(t) && (r["input_call"].containsMatchIn(call) || call in setOf("{when}", "{block}", "") ||
                    r["router_file"].containsMatchIn(f))) return "input"
            if (r["key_shape"].containsMatchIn(t)) return "key"
            if (r["lower_word"].containsMatchIn(t)) {
                if (last == "Text" || (r["enum_call"].containsMatchIn(last) && t.any { it.code > 127 })) return "visible"
                return if (rel.startsWith("app") || rel.startsWith("voice")) "key" else "input"
            }
            return "key"
        }
        val startsLower = r["starts_lower"].containsMatchIn(t)
        val endsSentence = r["ends_sentence"].containsMatchIn(t.trim())
        val inRouter = r["router_file"].containsMatchIn(f)
        val valuePos = before.trimEnd().endsWith(" to") || before.trimEnd().endsWith("->")
        if (sinkVisible || visibleNamed) return "visible"
        if (r["enum_call"].containsMatchIn(last) && multi) return "visible"
        if (startsLower && !endsSentence && "Router" in f && "{}" !in t) return "input"
        if (startsLower && !endsSentence && !valuePos && (r["input_call"].containsMatchIn(call) || inRouter || call == "{when}")) return "input"
        if (startsLower && !endsSentence && !multi) return "input"
        if (startsLower && r["input_call"].containsMatchIn(call) && !valuePos && "{}" !in t && ws.size <= 4 && !endsSentence) return "input"
        return "visible"
    }

    /** The Kotlin sources of the six modules' main code, by their path from robot/ (vendored code left out). */
    fun sources(root: File, r: Rules): List<Pair<String, File>> = r.modules.flatMap { m ->
        File(root, "$m/src/main/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
    }.filter { (rel, _) -> r.exclude.none { it.containsMatchIn(rel) } }.sortedBy { it.first }

    /** Every message of every source, classified, with what the guard does with it. */
    fun inventory(root: File, r: Rules): List<Row> = sources(root, r).flatMap { (rel, file) ->
        val src = file.readText()
        val lines = src.split('\n')
        val ranges = logcodeRanges(r, src)
        val area = r.area(rel)
        val allowed = r.allowedDirs.any { it.containsMatchIn(rel) }
        messages(src).map { m ->
            val kind = classify(r, rel, m, ranges)
            val lang = if (kind == "visible" || kind == "single") langOf(r, m.text) else ""
            val guard = if (kind != "visible" || allowed) "" else {
                val line = lines.getOrElse(m.line - 1) { "" }
                when {
                    r.marker.containsMatchIn(line) -> "marked"
                    r.markerAny.containsMatchIn(line) -> "bad-marker"
                    else -> "baseline"
                }
            }
            Row(rel, m.line, kind, lang, m.text, area, guard)
        }
    }
}
