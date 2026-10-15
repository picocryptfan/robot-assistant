// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

// Pure (java.io, org.json). Robot Notes (docs/phase-2.md "Notes"): one JSON file per note in app-private storage, like
// the history. The assistant adds notes (add_note) and may undo the one it just added; the main app's Notes screen
// lists, edits, deletes and shares them. One file per note, written atomically, so the two processes never write the
// same file at the same time in practice and a reader never sees half a note.

data class Note(val id: String, val title: String, val text: String, val created: Long, val updated: Long) {
    /** The title, or the text's first line when it has none. */
    val displayTitle: String get() = title.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { NoteSearch.cut(it, 60) } ?: "Note" }
}

object NoteCodec {
    const val VERSION = 1

    fun toJson(n: Note): String = JSONObject().put("v", VERSION).put("id", n.id).put("title", n.title).put("text", n.text)
        .put("created", n.created).put("updated", n.updated).toString()

    fun fromJson(s: String): Note? = try {
        val o = JSONObject(s)
        val id = o.getString("id")
        if (!NoteStore.validId(id)) null
        else Note(id, o.optString("title"), o.optString("text"), o.optLong("created"), o.optLong("updated", o.optLong("created")))
    } catch (_: Exception) { null }
}

class NoteStore(private val dir: File) {
    private fun file(id: String): File? = if (validId(id)) File(dir, "$id.json") else null

    /** Newest change first. */
    fun list(): List<Note> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> readOrNull(f)?.let(NoteCodec::fromJson) }
        .sortedWith(compareByDescending<Note> { it.updated }.thenByDescending { it.created })

    fun get(id: String): Note? = file(id)?.let(::readOrNull)?.let(NoteCodec::fromJson)

    /** A new note; null if it couldn't be written. */
    fun add(text: String, title: String?, now: Long, id: String = UUID.randomUUID().toString()): Note? {
        val n = Note(id, title?.trim().orEmpty(), text.trim(), now, now)
        return if (save(n)) n else null
    }

    fun save(n: Note): Boolean {
        val f = file(n.id) ?: return false
        return try { writeAtomic(f, NoteCodec.toJson(n)); true } catch (_: Exception) { false }
    }

    fun delete(id: String): Boolean = file(id)?.delete() == true

    companion object {
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
        fun validId(id: String?): Boolean = id != null && ID.matches(id)
    }
}

object NoteSearch {
    /** find_notes returns at most this many notes ... */
    const val MAX_RESULTS = 3
    /** ... with the title and this much of the text each. */
    const val SNIPPET = 400

    /** Words that say "my notes", not what they are about. */
    private val STOP = setOf("a", "an", "the", "my", "me", "i", "about", "note", "notes", "noted", "on", "of", "for", "to", "what",
        "did", "do", "all", "any", "some", "with", "and", "or", "in", "that", "this", "is", "was", "find", "search", "show", "list")

    fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * The notes that match [query], best first: each query word found in the title counts twice, in the text once
     * (a word matches the same word, a longer one starting with it, or its singular: "filter" finds "filters"). A query
     * with nothing but words like "my notes" lists the newest notes.
     */
    fun find(query: String, notes: List<Note>, limit: Int = MAX_RESULTS): List<Note> {
        val q = words(query).filter { it !in STOP }.distinct()
        if (q.isEmpty()) return notes.sortedByDescending { it.updated }.take(limit)
        return notes.mapNotNull { n ->
            val title = words(n.title)
            val text = words(n.text)
            val score = q.sumOf { w -> (if (title.any { matches(it, w) }) 2 else 0) + (if (text.any { matches(it, w) }) 1 else 0) }
            val all = q.all { w -> title.any { matches(it, w) } || text.any { matches(it, w) } }
            if (score == 0) null else Triple(n, score + if (all) 10 else 0, n.updated)
        }.sortedWith(compareByDescending<Triple<Note, Int, Long>> { it.second }.thenByDescending { it.third }).take(limit).map { it.first }
    }

    private fun matches(word: String, q: String): Boolean =
        word == q || (q.length >= 3 && word.startsWith(q)) || (q.length >= 4 && q.endsWith("s") && word == q.dropLast(1))

    /** find_notes' answer for the model: the title and the first [SNIPPET] characters of each note. */
    fun results(notes: List<Note>): List<Map<String, Any?>> = notes.map { mapOf("title" to it.displayTitle, "text" to cut(it.text, SNIPPET)) }

    /** At most [max] characters, whitespace tidied; a cut ends with "…". */
    fun cut(s: String, max: Int): String {
        val t = s.replace(Regex("[ \\t]+"), " ").trim()
        return if (t.length <= max) t else ToolCalls.safeTake(t, max - 1).trimEnd() + "…"
    }
}
