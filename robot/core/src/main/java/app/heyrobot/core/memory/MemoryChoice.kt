// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import org.json.JSONObject

// Pure. Who answers a kind of question (docs/ask-claude.md "Who answers"): a memory item you can see, change and delete, a FACT
// with the optional field `choice`:
//
//   "choice": {"kind": "CODE", "to": "helper", "helper": "claude"}
//
// kind: a QuestionKind's name; to: "helper" or "robot"; helper: the helper you named, or none for the one Robot checks with.
// The memory codec stays at version 1: the field is additive, so an older Robot reads the item as the plain fact its words
// say and, if it writes the file, drops the field.
//
// Only your words and your taps (HelperChoice's commands, an offer's Remember, Settings › Memory) make one, never the model,
// never content: the words of an item never set a route, only this field does, and [MemoryChoices.applies] follows it only on
// an item that is your own. An export carries no route: MemoryImport doesn't read the field. It never goes into a prompt
// (MemoryEdits.forQuestions): it says who answers, it is no fact about you.

/** [kind]: a QuestionKind's name. [robot]: Robot answers; else helper [helper] (null: the one Robot checks with). */
data class MemoryChoice(val kind: String, val robot: Boolean, val helper: String? = null)

/** The `choice` field's JSON in memory.enc. */
object MemoryChoiceCodec {
    private val KEYS = setOf("kind", "to", "helper")
    private val KIND = Regex("[A-Z][A-Z_]{0,23}")
    private val ID = Regex("[A-Za-z0-9_]{1,64}")

    fun toJson(c: MemoryChoice): JSONObject = JSONObject().put("kind", c.kind).put("to", if (c.robot) "robot" else "helper").also { o ->
        c.helper?.let { o.put("helper", it) }
    }

    /** The field, or null when it is absent or damaged, or holds a key this Robot doesn't know: then the item is a plain fact and routes nothing. */
    fun read(o: JSONObject?): MemoryChoice? {
        if (o == null || o.keys().asSequence().any { it !in KEYS }) return null
        val kind = o.opt("kind") as? String ?: return null
        val to = o.opt("to") as? String ?: return null
        val helper = if (o.has("helper")) o.opt("helper") as? String ?: return null else null
        if (!KIND.matches(kind) || to !in setOf("helper", "robot") || (helper != null && !ID.matches(helper))) return null
        return MemoryChoice(kind, to == "robot", helper)
    }
}

/** The list changes for choices, applied under the vault's file lock, and the one test that decides whether an item routes. */
object MemoryChoices {
    private val OWN = setOf(MemorySource.SAID, MemorySource.TYPED, MemorySource.SETTINGS)

    /** Robot follows it: your own words or tap, not from a file, not kept from something Robot read, not past its end. */
    fun applies(i: MemoryItem, now: Long): Boolean =
        i.choice != null && i.source in OWN && i.imported == null && !i.tainted && !i.secret && !i.expired(now)

    /** The choice Robot follows for [kind], the newest of them, or null. */
    fun find(items: List<MemoryItem>, kind: String, now: Long): MemoryChoice? =
        items.filter { applies(it, now) && it.choice?.kind == kind }.maxByOrNull { it.updatedAt }?.choice

    /** [item] (with its [MemoryItem.choice]) kept: it replaces your earlier choice for that kind. Null: Memory is full. */
    fun put(file: MemoryFile, item: MemoryItem): MemoryFile? {
        val c = requireNotNull(item.choice)
        val kept = file.items.filterNot { applies(it, item.createdAt) && it.choice?.kind == c.kind }
        if (kept.size >= MemoryLimits.MAX_ITEMS) return null
        return file.copy(items = kept + item.copy(kind = MemoryKind.FACT, pinned = false))
    }

    /** The file without your choice for [kind], and what went (for the answer). */
    fun drop(file: MemoryFile, kind: String, now: Long): Pair<MemoryFile, List<MemoryItem>> =
        MemoryEdits.forget(file, file.items.filter { it.choice?.kind == kind && applies(it, now) }.map { it.id })

    /** Settings › Memory's change: item [id] now sends its kind to Robot, or to the helper ([robot] false), said in [text]. */
    fun change(file: MemoryFile, id: String, robot: Boolean, text: String, now: Long): MemoryFile? {
        val i = file.items.firstOrNull { it.id == id } ?: return null
        val c = i.choice ?: return null
        return file.copy(items = file.items.map {
            if (it.id == id) it.copy(text = text, choice = c.copy(robot = robot, helper = null), source = MemorySource.SETTINGS, updatedAt = now) else it
        })
    }
}
