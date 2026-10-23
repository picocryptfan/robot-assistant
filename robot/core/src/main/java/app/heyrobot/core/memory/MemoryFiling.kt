// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import org.json.JSONArray
import org.json.JSONObject

// Pure. 1.4, Robot Files (files-1.4-plan.md §4.5, decision D6; job F8): a filing rule is a memory item, a FACT with the
// optional field `filing`:
//
//   "filing": {"match": {"kind": "RECEIPT", "merchant": "Esso"}, "then": {"drawer": "Car"}, "learned": true}
//
// match: kind (a Robot Files kind's name), merchant, words (letters, digits and spaces, 40 at most); then: drawer (a starter
// drawer's id or one of yours), tags, name ("date" or "name" first); learned: from your moves or tags, not your words. At
// least one key in each. The memory codec stays at version 1: the field is additive, so a 1.1 to 1.3 Robot reads the item as
// the plain fact its words say and, if it writes the file, drops the field (MemoryCodec ignores keys it doesn't know).
//
// Only your words (FilesRouter) and your taps (a move or tags in Files) make one; never the model, never content (D10). A
// rule's words may hold a file's merchant, so the item never goes into a prompt (MemoryEdits.forQuestions). A rule written
// by a newer Robot with keys this one doesn't know is kept as it was ([MemoryFiling.foreign]) and never applied: applied
// without its new conditions, it would match more than it says.

/**
 * A filing rule's condition and action, as kept in memory. [kind] is a Robot Files kind's name (core.files maps it). [foreign]:
 * the field as a newer Robot wrote it, written back unchanged; such a rule is shown, never applied.
 */
data class MemoryFiling(
    val kind: String? = null,
    val merchant: String? = null,
    val words: String? = null,
    val drawer: String? = null,
    val tags: List<String> = emptyList(),
    /** "date" (date first) or "name" (name first). */
    val name: String? = null,
    val learned: Boolean = false,
    val foreign: String? = null,
) {
    /** Every value within its caps, something to match and something to do. */
    val valid: Boolean get() = (kind != null || merchant != null || words != null) && (drawer != null || tags.isNotEmpty() || name != null) &&
        (kind == null || KIND.matches(kind)) && (merchant == null || matchWords(merchant)) && (words == null || matchWords(words)) &&
        (drawer == null || label(drawer)) && tags.size <= MAX_TAGS && tags.all(::label) && tags.toSet().size == tags.size &&
        (name == null || name in NAMES)

    /** Robot Files may apply it: valid, and nothing in it this Robot doesn't know. */
    val usable: Boolean get() = foreign == null && valid

    /** The same condition (case, accents and spacing aside). */
    fun sameMatch(o: MemoryFiling): Boolean = kind == o.kind && key(merchant) == key(o.merchant) && key(words) == key(o.words)

    /** The same rule. */
    fun sameAs(o: MemoryFiling): Boolean = sameMatch(o) && drawer == o.drawer && tags.toSet() == o.tags.toSet() && name == o.name

    /** Never a merchant, a drawer or a tag in a log line. */
    override fun toString(): String = "MemoryFiling(kind=$kind, merchant=${merchant != null}, words=${words != null}, drawer=${drawer != null}, tags=${tags.size}, " + // l10n: not text (toString for logs and tests)
        "name=$name, learned=$learned, foreign=${foreign != null})" // l10n: not text (toString for logs and tests)

    companion object {
        /** Letters and digits of a merchant or words, at most. */
        const val MAX_WORDS = 40
        /** A drawer's or a tag's characters at most (Robot Files' labels). */
        const val MAX_LABEL = 40
        const val MAX_TAGS = 8
        /** A rule from a newer Robot, kept as it was, at most this long (longer: read as no rule). */
        const val MAX_FOREIGN = 2_048
        val NAMES = setOf("date", "name")
        private val KIND = Regex("[A-Z][A-Z_]{0,23}")
        private val WORDS = Regex("[\\p{L}\\p{N}]+( [\\p{L}\\p{N}]+)*")

        /** [s] as a rule's merchant or words: letters, digits and single spaces, 1 to [MAX_WORDS] characters. */
        fun matchWords(s: String): Boolean = s.length in 1..MAX_WORDS && WORDS.matches(s)

        /** Anything as a rule's merchant or words: other characters become spaces, then trimmed and cut; null when nothing is left. */
        fun cleanWords(s: String?): String? {
            if (s == null) return null
            val t = s.map { if (it.isLetterOrDigit() && !Character.isSurrogate(it)) it else ' ' }.joinToString("").replace(Regex(" +"), " ").trim()
            return t.take(MAX_WORDS).trim().takeIf { it.isNotEmpty() && matchWords(it) }
        }

        /** A drawer's or a tag's name: one line, 1 to [MAX_LABEL] characters, not blank, no control or direction marks. */
        fun label(s: String): Boolean = s.length in 1..MAX_LABEL && s.isNotBlank() && s.none(::control) && s.indices.all { i ->
            val c = s[i]
            when {
                Character.isHighSurrogate(c) -> i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
                Character.isLowSurrogate(c) -> i > 0 && Character.isHighSurrogate(s[i - 1])
                else -> true
            }
        }

        private fun control(c: Char): Boolean = c < ' ' || c in '\u007F'..'\u009F' || c == ' ' || c == ' ' || c == '‎' || c == '‏' ||
            c in '‪'..'‮' || c in '⁦'..'⁩' || c == '؜'

        /** A merchant or words compared: folded (case and accents), letters and digits only. */
        fun key(s: String?): String = s?.let { MemoryText.fold(it).filter(Char::isLetterOrDigit) }.orEmpty()
    }
}

/** The `filing` field's JSON, both ways: memory.enc (org.json) and a memory export (StrictJson's maps and lists). */
object MemoryFilingCodec {
    private val TOP = setOf("match", "then", "learned")
    private val MATCH = setOf("kind", "merchant", "words")
    private val THEN = setOf("drawer", "tags", "name")

    fun toJson(f: MemoryFiling): JSONObject {
        f.foreign?.let { return JSONObject(it) }
        val match = JSONObject()
        f.kind?.let { match.put("kind", it) }
        f.merchant?.let { match.put("merchant", it) }
        f.words?.let { match.put("words", it) }
        val then = JSONObject()
        f.drawer?.let { then.put("drawer", it) }
        if (f.tags.isNotEmpty()) then.put("tags", JSONArray(f.tags))
        f.name?.let { then.put("name", it) }
        val o = JSONObject().put("match", match).put("then", then)
        if (f.learned) o.put("learned", true)
        return o
    }

    /** The field in memory.enc's item, or null when it is absent or damaged (the item is then a plain fact). */
    fun read(o: JSONObject?): MemoryFiling? = o?.let { read(plainOf(it)) }

    /**
     * The field as plain maps and lists, or null when it isn't a rule: a wrong type, a value past its cap, nothing to match
     * or nothing to do. Keys this Robot doesn't know make it a newer Robot's rule, kept as it was and never applied.
     */
    fun read(v: Any?): MemoryFiling? {
        val o = v as? Map<*, *> ?: return null
        val match = o["match"] as? Map<*, *> ?: return null
        val then = o["then"] as? Map<*, *> ?: return null
        if (o.containsKey("learned") && o["learned"] !is Boolean) return null
        fun str(m: Map<*, *>, k: String): String? = if (!m.containsKey(k)) null else m[k] as? String ?: throw IllegalArgumentException()
        val f = try {
            MemoryFiling(
                kind = str(match, "kind"),
                merchant = str(match, "merchant"),
                words = str(match, "words"),
                drawer = str(then, "drawer"),
                tags = if (!then.containsKey("tags")) emptyList() else (then["tags"] as? List<*> ?: return null).map { it as? String ?: return null },
                name = str(then, "name"),
                learned = o["learned"] == true,
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
        val unknown = o.keys.any { it !in TOP } || match.keys.any { it !in MATCH } || then.keys.any { it !in THEN }
        if (!unknown) return f.takeIf { it.valid }
        // A newer Robot's rule: kept whole (within a size), shown in its words, never applied.
        val raw = try { jsonOf(o).toString() } catch (_: Exception) { return null }
        return if (raw.length <= MemoryFiling.MAX_FOREIGN) f.copy(foreign = raw) else null
    }

    private fun plainOf(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plainOf(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { plainOf(v.opt(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    private fun jsonOf(v: Any?): Any? = when (v) {
        is Map<*, *> -> JSONObject().apply { for ((k, x) in v) put(k as String, jsonOf(x) ?: JSONObject.NULL) }
        is List<*> -> JSONArray().apply { for (x in v) put(jsonOf(x) ?: JSONObject.NULL) }
        else -> v
    }
}
