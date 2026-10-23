// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import org.json.JSONArray
import org.json.JSONObject

// 1.4 F8: memory's codec exactly as 1.1 to 1.3 ship it (develop/1.1 8d99b5ae, MemoryCodec.kt's object, renamed; nothing
// else changed), so FilingRuleCodecTest can show what an older Robot makes of a filing rule: it reads the item as a plain
// fact and, when it writes the file, drops the field. Never edit it to follow the real codec.
object MemoryCodec11 {
    const val VERSION = 1

    fun toJson(f: MemoryFile): String = JSONObject().put("v", VERSION).put("items", JSONArray().apply {
        for (i in f.items) put(JSONObject().apply {
            put("id", i.id)
            put("text", i.text)
            put("kind", i.kind.name)
            if (i.secret) put("secret", true)
            if (i.pinned) put("pinned", true)
            put("source", i.source.name)
            i.sourceLabel?.let { put("sourceLabel", it) }
            i.language?.let { put("lang", it) }
            if (i.about.isNotEmpty()) put("about", JSONArray(i.about))
            put("created", i.createdAt)
            put("updated", i.updatedAt)
            i.expiresAt?.let { put("expires", it) }
            if (i.uses > 0) put("uses", i.uses)
            i.usedAt?.let { put("used", it) }
            i.imported?.let { put("imported", it.name) }
        })
    }).put("suggestions", JSONArray().apply {
        for (s in f.suggestions) put(JSONObject().put("id", s.id).put("text", s.text).put("kind", s.kind.name).put("created", s.createdAt))
    }).put("negatives", JSONArray(f.negatives)).toString()

    /**
     * The file in [text]. No text is an empty file. An entry with a bad id, no text or a repeated id is dropped and
     * counted; text past [MemoryLimits.MAX_CHARS] is cut. An unknown kind reads as a fact, and an unknown source as
     * [MemorySource.CONTENT]: what Robot can't place is never taken for your own words (an unknown import mark, 1.0, as
     * [MemoryImported.CONTENT] too).
     */
    fun read(text: String?): MemoryParse {
        if (text.isNullOrBlank()) return MemoryParse.Ok(MemoryFile())
        val o = try { JSONObject(text) } catch (_: Exception) { return MemoryParse.Damaged }
        val v = o.optInt("v", -1)
        if (v > VERSION) return MemoryParse.TooNew(v)
        if (v < 1) return MemoryParse.Damaged
        val arr = o.optJSONArray("items") ?: return MemoryParse.Damaged
        var dropped = 0
        val seen = HashSet<String>()
        val items = ArrayList<MemoryItem>(arr.length())
        for (k in 0 until arr.length()) {
            val item = arr.optJSONObject(k)?.let(::item)
            if (item == null || !seen.add(item.id)) dropped++ else items += item
        }
        val sArr = o.optJSONArray("suggestions")
        val suggestions = (0 until (sArr?.length() ?: 0)).mapNotNull { k ->
            val s = sArr!!.optJSONObject(k) ?: return@mapNotNull null.also { dropped++ }
            val id = s.optString("id").takeIf { MemoryIds.valid(it) }
            val t = MemoryEdits.clean(s.optString("text"))
            if (id == null || t.isEmpty()) { dropped++; null }
            else MemorySuggestion(id, t, enumOr(s.optString("kind"), MemoryKind.FACT), s.optLong("created"))
        }
        val nArr = o.optJSONArray("negatives")
        val negatives = (0 until (nArr?.length() ?: 0)).mapNotNull { k -> nArr!!.optString(k).takeIf { it.isNotBlank() } }
        return MemoryParse.Ok(MemoryFile(items.take(MemoryLimits.MAX_ITEMS), suggestions, negatives), dropped + (items.size - MemoryLimits.MAX_ITEMS).coerceAtLeast(0))
    }

    private fun item(o: JSONObject): MemoryItem? {
        val id = o.optString("id").takeIf { MemoryIds.valid(it) } ?: return null
        val text = MemoryEdits.clean(o.optString("text"))
        if (text.isEmpty()) return null
        val created = o.optLong("created", 0)
        val secret = o.optBoolean("secret", false)
        return MemoryItem(
            id = id,
            text = text,
            kind = enumOr(o.optString("kind"), MemoryKind.FACT),
            secret = secret,
            pinned = o.optBoolean("pinned", false) && !secret,
            source = enumOr(o.optString("source"), MemorySource.CONTENT),
            sourceLabel = o.optString("sourceLabel").takeIf { it.isNotBlank() },
            language = o.optString("lang").takeIf { it.isNotBlank() },
            about = o.optJSONArray("about")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty(),
            createdAt = created,
            updatedAt = o.optLong("updated", created),
            expiresAt = if (o.has("expires")) o.optLong("expires") else null,
            uses = o.optInt("uses", 0).coerceAtLeast(0),
            usedAt = if (o.has("used")) o.optLong("used") else null,
            imported = if (o.has("imported")) enumOr(o.optString("imported"), MemoryImported.CONTENT) else null,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
