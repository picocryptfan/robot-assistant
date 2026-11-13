// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.json.JSONArray
import org.json.JSONObject

// Pure. Ask about your screenshots' index (screenshots-plan.md, "What's new" 1 to 4): one entry per screenshot read, by
// its MediaStore id, written only after ShotSecrets has hidden its secrets. The index is a list of batches, each sealed
// on its own (ShotSeal): a pass adds entries and drops those whose screenshot is gone, and folding the batches in order
// gives the index. Search is by words, names and numbers weighted, in English and French.

/** One screenshot's entry: [id] its MediaStore id, [at] when it was taken (ms), [text] what it shows, secrets as tokens. */
data class ShotEntry(val id: Long, val at: Long, val text: String, val kinds: Set<SecretKind> = emptySet(), val skipped: Boolean = false)

/** What one pass wrote: entries added, and the ids of screenshots gone, trashed or no longer granted. */
data class ShotBatch(val add: List<ShotEntry>, val drop: Set<Long> = emptySet()) {
    fun toJson(): String = JSONObject().put("v", 1)
        .put("add", JSONArray().apply {
            add.forEach { e -> put(JSONObject().put("id", e.id).put("at", e.at).put("text", e.text).put("kinds", JSONArray(e.kinds.map { it.name })).apply { if (e.skipped) put("skip", true) }) }
        })
        .put("drop", JSONArray(drop.toList())).toString()

    companion object {
        fun fromJson(s: String): ShotBatch {
            val o = JSONObject(s)
            val add = o.optJSONArray("add") ?: JSONArray()
            val drop = o.optJSONArray("drop") ?: JSONArray()
            return ShotBatch((0 until add.length()).map { i ->
                val e = add.getJSONObject(i)
                val k = e.optJSONArray("kinds") ?: JSONArray()
                ShotEntry(e.getLong("id"), e.optLong("at"), e.optString("text"),
                    (0 until k.length()).mapNotNull { j -> SecretKind.entries.firstOrNull { it.name == k.optString(j) } }.toSet(), e.optBoolean("skip"))
            }, (0 until drop.length()).map { drop.getLong(it) }.toSet())
        }
    }
}

object ShotIndex {
    /** The batches folded in order: a later batch's drop removes an earlier entry; a later add replaces one. */
    fun fold(batches: List<ShotBatch>): Map<Long, ShotEntry> {
        val m = LinkedHashMap<Long, ShotEntry>()
        for (b in batches) {
            b.drop.forEach { m.remove(it) }
            b.add.forEach { m[it.id] = it }
        }
        return m
    }

    private val STOP = setOf("the", "a", "an", "in", "on", "of", "my", "what", "was", "is", "which", "when", "where", "who", "did", "do",
        "from", "that", "this", "to", "for", "and", "it", "me", "i", "screenshot", "screenshots", "screen", "shot", "le", "la", "les", // l10n: not text (words ignored in a search)
        "un", "une", "des", "de", "du", "dans", "sur", "mon", "ma", "mes", "quel", "quelle", "quels", "quelles", "était", "est", "qui", // l10n: not text (words ignored in a search)
        "quand", "où", "capture", "captures", "écran", "d'écran", "et", "pour") // l10n: not text (words ignored in a search)

    fun words(s: String): List<String> = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 && it !in STOP } // l10n: not text (a pattern)

    /** The best [max] entries for [question]: each word found counts 1, a number or a name (capitalized) 2. */
    fun search(entries: Collection<ShotEntry>, question: String, max: Int = 3): List<ShotEntry> {
        val asked = words(question).toSet()
        if (asked.isEmpty()) return emptyList()
        val weight = question.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() && (it.any(Char::isDigit) || it[0].isUpperCase()) } // l10n: not text (a pattern)
            .map { it.lowercase() }.toSet()
        return entries.map { e ->
            val have = words(e.text).toSet()
            e to asked.sumOf { w -> if (have.any { it == w || (w.length > 3 && it.startsWith(w)) }) (if (w in weight) 2 else 1) as Int else 0 }
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<ShotEntry, Int>> { it.second }.thenByDescending { it.first.at }).take(max).map { it.first }
    }
}
