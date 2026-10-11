package app.heyrobot.core.termux

// Pure. Secrets in Termux commands and their output (docs/plans/websearch-termux-design.md B7.5): private keys, API-key
// and token shapes, passwords in web addresses and after "password=" or "token:", and Robot's own keys by exact value.
// In output they are replaced before Gemma, the card, History or a helper sees it; in a command they make a warning, and
// one of Robot's own keys (or its shape, in a command Robot didn't take from your typing) stops the command (rule 7).
// No pattern can backtrack far: every repeat is either bounded or over a class the next token can't start with, and
// private-key blocks are found with indexOf, not a regular expression.

object SecretMask {
    enum class Kind(val en: String, val fr: String) {
        PRIVATE_KEY("looks like a private key", "ressemble à une clé privée"),
        API_KEY("looks like an API key", "ressemble à une clé d'API"),
        TOKEN("looks like a token", "ressemble à un jeton"),
        PASSWORD("looks like a password", "ressemble à un mot de passe"),
        /** One of Robot's own keys, by its exact value. */
        ROBOT_KEY("one of Robot's own keys", "une des clés de Robot"),
    }

    /**
     * Something secret-looking at [start] until [end] (exclusive). [robotShape]: it has the shape of a key Robot keeps
     * (an Anthropic key today; helper keys later), or is one of Robot's own keys.
     */
    data class Found(val start: Int, val end: Int, val kind: Kind, val robotShape: Boolean = false)

    /**
     * The prefixes of the keys Robot itself keeps or will keep: Anthropic ("sk-ant-", the Claude key since 0.3.0), and
     * the helpers' (0.6.0: OpenAI project keys, OpenRouter, xAI, Groq). Never put in a command by Robot or Gemma.
     */
    val ROBOT_KEY_PREFIXES: List<String> = listOf("sk-ant-", "sk-proj-", "sk-or-", "xai-", "gsk_")

    /** Robot's own secret values shorter than this aren't searched for (they'd match ordinary text). */
    const val MIN_OWN = 8

    private class Rule(pattern: String, val kind: Kind, val group: Int = 0, val robot: Boolean = false, val check: ((String) -> Boolean)? = null) {
        val regex = Regex(pattern)
    }

    private const val KEY_CHARS = "A-Za-z0-9_-"

    private val RULES = listOf(
        Rule("(?<![$KEY_CHARS])sk-ant-[$KEY_CHARS]{16,}", Kind.API_KEY, robot = true),
        Rule("(?<![$KEY_CHARS])sk-proj-[$KEY_CHARS]{16,}", Kind.API_KEY, robot = true),
        Rule("(?<![$KEY_CHARS])sk-or-[$KEY_CHARS]{16,}", Kind.API_KEY, robot = true),
        Rule("(?<![$KEY_CHARS])xai-[A-Za-z0-9]{20,}", Kind.API_KEY, robot = true),
        Rule("(?<![$KEY_CHARS])gsk_[A-Za-z0-9]{20,}", Kind.API_KEY, robot = true),
        // OpenAI's older keys: "sk-" and 32 or more letters and digits.
        Rule("(?<![$KEY_CHARS])sk-[A-Za-z0-9]{32,}", Kind.API_KEY),
        Rule("(?<![$KEY_CHARS])AIza[0-9A-Za-z_-]{35}", Kind.API_KEY),
        Rule("(?<![A-Za-z0-9_])(?:sk|rk)_live_[A-Za-z0-9]{16,}", Kind.API_KEY),
        Rule("(?<![A-Za-z0-9])(?:AKIA|ASIA)[0-9A-Z]{16}(?![0-9A-Z])", Kind.API_KEY),
        Rule("(?<![A-Za-z0-9_])gh[pousr]_[A-Za-z0-9]{30,}", Kind.TOKEN),
        Rule("(?<![A-Za-z0-9_])github_pat_[A-Za-z0-9_]{22,}", Kind.TOKEN),
        Rule("(?<![$KEY_CHARS])glpat-[$KEY_CHARS]{20,}", Kind.TOKEN),
        Rule("(?<![A-Za-z0-9-])xox[abprs]-[A-Za-z0-9-]{10,}", Kind.TOKEN),
        Rule("(?<![A-Za-z0-9_])hf_[A-Za-z0-9]{30,}", Kind.TOKEN),
        // A JSON Web Token: three base64url parts, the first two JSON objects ("eyJ").
        Rule("(?<![$KEY_CHARS])eyJ[$KEY_CHARS]{8,}\\.eyJ[$KEY_CHARS]{8,}\\.[$KEY_CHARS]{8,}", Kind.TOKEN),
        Rule("(?i)\\bbearer[ \\t]{1,5}([A-Za-z0-9._~+/-]{16,}=*)", Kind.TOKEN, group = 1),
        // https://user:password@host
        Rule("\\b[A-Za-z][A-Za-z0-9+.-]{1,15}://[^\\s/:@]{1,128}:([^\\s/@]{1,256})@", Kind.PASSWORD, group = 1),
        // password=…, "api_key": "…", token: …, client_secret=…
        Rule("(?i)(?:password|passwd|passphrase|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)[A-Za-z0-9_.-]{0,32}[\"']?[ \\t]{0,3}[:=][ \\t]{0,3}" +
            "(\"[^\"\\n]{1,256}\"|'[^'\\n]{1,256}'|[^\\s\"'&;,|)}]{1,256})", Kind.PASSWORD, group = 1, check = ::plausibleValue),
    )

    /** A value after "password=" or "token:" that could be a secret: not a variable, a placeholder or a small number. */
    private fun plausibleValue(raw: String): Boolean {
        val v = raw.trim('"', '\'')
        if (v.length < 6) return false
        if (v.startsWith("$") || v.startsWith("<") || v.startsWith("[") || v.startsWith("{") || v.startsWith("%")) return false
        if (v.all { it == v[0] }) return false
        if (v.all { it in '0'..'9' } && v.length < 10) return false
        return v.lowercase() !in setOf("null", "none", "true", "false", "hidden", "redacted", "changeme", "password", "required", "optional")
    }

    /**
     * Everything secret-looking in [text], in order, overlaps merged. [own]: Robot's own secrets (its keys), found by exact
     * value; held only by the caller (`:voice`) and never logged.
     */
    fun find(text: String, own: Collection<String> = emptyList()): List<Found> {
        val found = ArrayList<Found>()
        privateKeys(text, found)
        for (r in RULES) {
            for (m in r.regex.findAll(text)) {
                val g = m.groups[r.group] ?: continue
                if (r.check != null && !r.check.invoke(g.value)) continue
                val range = g.range
                found += Found(range.first, range.last + 1, r.kind, r.robot)
            }
        }
        for (s in own) {
            if (s.length < MIN_OWN) continue
            var at = text.indexOf(s)
            while (at >= 0) {
                found += Found(at, at + s.length, Kind.ROBOT_KEY, robotShape = true)
                at = text.indexOf(s, at + s.length)
            }
        }
        return merge(found)
    }

    /** [text] holds one of Robot's own keys ([own], exact) or something with the shape of one ([ROBOT_KEY_PREFIXES]). */
    fun hasRobotKey(text: String, own: Collection<String> = emptyList()): Boolean = find(text, own).any { it.robotShape }

    /** [text] holds one of Robot's own secrets, by exact value. */
    fun hasOwn(text: String, own: Collection<String>): Boolean = own.any { it.length >= MIN_OWN && text.contains(it) }

    /** [text] with every secret replaced by "[hidden: looks like a private key]" (French: "[masqué : …]"). */
    fun mask(text: String, own: Collection<String> = emptyList(), fr: Boolean = false): String {
        val found = find(text, own)
        if (found.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var at = 0
        for (f in found) {
            sb.append(text, at, f.start)
            sb.append(if (fr) "[masqué : ${f.kind.fr}]" else "[hidden: ${f.kind.en}]")
            at = f.end
        }
        sb.append(text, at, text.length)
        return sb.toString()
    }

    /**
     * "-----BEGIN … PRIVATE KEY-----" (PEM, OpenSSH, PGP's "PRIVATE KEY BLOCK") through its END line; a block whose END
     * never comes is hidden to the end of the text, and an END line with no BEGIN before it everything before it (a cut
     * key is still secret).
     */
    private fun privateKeys(text: String, out: MutableList<Found>) {
        // Output Termux cut from its start can begin inside a key: its END line with no BEGIN before it hides all before.
        val firstBegin = text.indexOf("-----BEGIN ")
        var e = text.indexOf("-----END ")
        while (e >= 0 && (firstBegin < 0 || e < firstBegin)) {
            val close = text.indexOf("-----", e + 9)
            if (close in 0..e + 89 && text.substring(e + 9, close).contains("PRIVATE KEY")) {
                out += Found(0, close + 5, Kind.PRIVATE_KEY)
                break
            }
            e = text.indexOf("-----END ", e + 9)
        }
        var from = 0
        while (true) {
            val begin = text.indexOf("-----BEGIN ", from)
            if (begin < 0) return
            val headerEnd = text.indexOf("-----", begin + 11)
            if (headerEnd < 0 || headerEnd - begin > 80) { from = begin + 11; continue }
            val label = text.substring(begin + 11, headerEnd)
            if (!label.contains("PRIVATE KEY")) { from = headerEnd + 5; continue }
            val endMark = text.indexOf("-----END ", headerEnd + 5)
            val stop = if (endMark < 0) text.length else {
                val close = text.indexOf("-----", endMark + 9)
                if (close < 0) text.length else close + 5
            }
            out += Found(begin, stop, Kind.PRIVATE_KEY)
            if (stop >= text.length) return
            from = stop
        }
    }

    private fun merge(found: List<Found>): List<Found> {
        if (found.size < 2) return found
        val sorted = found.sortedWith(compareBy<Found> { it.start }.thenByDescending { it.end })
        val out = ArrayList<Found>()
        for (f in sorted) {
            val last = out.lastOrNull()
            if (last != null && f.start < last.end) {
                if (f.end > last.end || (f.robotShape && !last.robotShape)) {
                    out[out.size - 1] = last.copy(end = maxOf(last.end, f.end), robotShape = last.robotShape || f.robotShape,
                        kind = if (f.kind == Kind.ROBOT_KEY) f.kind else last.kind)
                }
            } else out += f
        }
        return out
    }
}
