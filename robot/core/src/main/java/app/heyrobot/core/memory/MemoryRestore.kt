// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import kotlin.random.Random

// Pure. Memory from a backup (1.1): written through the vault's update under
// memory.lock, which seals it with this phone's key. [MemoryRestore.add] follows Import's rules (MemoryImport.plan: the
// same words skipped, new ids, memory's 2,000 cap, content unless the backup is yours) and says where each item of the file
// landed, so History's links and People's follow; [MemoryRestore.replace] takes the file's items with their ids, so
// History's links still work. Memory's items that hold a code (Keys and private codes) join as private items, only from
// a backup you marked as yours. [people] gives where each People id of the file landed (null: nobody here).

/** What a restore made of memory: the [file] to write, where each item of the backup landed ([landed]), and counts. */
class MemoryRestored(val file: MemoryFile, val landed: Map<String, String>, val added: Int, val skipped: Int) {
    override fun toString(): String = "MemoryRestored(items=${file.items.size}, added=$added, skipped=$skipped)"  // l10n: not text (toString for logs and tests)
}

object MemoryRestore {
    /** Add to what's here. [codes]: the backup's items holding a code (only when it is yours and Keys was chosen). */
    fun add(have: MemoryFile, items: List<MemoryItem>, codes: List<MemoryItem>, mine: Boolean, now: Long, people: (String) -> String?,
            random: Random = Random.Default): MemoryRestored {
        val entries = items.map(::entry)
        val plan = MemoryImport.plan(ImportRead.Ok(entries, 0), have, mine, now, random)
        val landed = HashMap<String, String>()
        val source = HashMap<String, MemoryItem>()
        items.forEachIndexed { i, item ->
            plan.landed.getOrNull(i)?.let { id ->
                landed[item.id] = id
                source.putIfAbsent(id, item)
            }
        }
        // Import never keeps People links; a restore keeps those that landed.
        val added = plan.add.map { a -> source[a.id]?.let { a.copy(about = about(it, people)) } ?: a }
        var file = have.copy(items = have.items + added)
        var codesAdded = 0
        if (mine) {
            val keys = file.items.mapTo(HashSet()) { MemoryEdits.textKey(it.text) }
            val ids = file.items.mapTo(HashSet()) { it.id }
            val more = ArrayList<MemoryItem>()
            for (c in codes) {
                val key = MemoryEdits.textKey(c.text)
                if (c.expired(now) || !keys.add(key) || file.items.size + more.size >= MemoryLimits.MAX_ITEMS) continue
                var id: String
                do { id = MemoryIds.newId(now, random) } while (!ids.add(id))
                more += c.copy(id = id, secret = true, pinned = false, about = about(c, people))
                landed[c.id] = id
            }
            codesAdded = more.size
            file = file.copy(items = file.items + more)
        }
        val total = items.size + if (mine) codes.size else 0
        return MemoryRestored(file, landed, added.size + codesAdded, total - added.size - codesAdded)
    }

    /**
     * Replace what's here: the backup's items with their ids (and its codes, when [mine]), at memory's caps (2,000 items,
     * 6 pinned, a private item never pinned); content unless [mine], as Import marks it. This phone's suggestions stay.
     */
    fun replace(have: MemoryFile, items: List<MemoryItem>, codes: List<MemoryItem>, mine: Boolean, people: (String) -> String?): MemoryRestored {
        val seen = HashSet<String>()
        var pins = 0
        val out = ArrayList<MemoryItem>()
        val all = items.map { it to false } + (if (mine) codes.map { it to true } else emptyList())
        for ((item, code) in all) {
            if (out.size >= MemoryLimits.MAX_ITEMS || !MemoryIds.valid(item.id) || !seen.add(item.id)) continue
            val secret = item.secret || code
            val pinned = item.pinned && !secret && pins < MemoryLimits.MAX_PINNED
            if (pinned) pins++
            out += item.copy(
                secret = secret,
                pinned = pinned,
                about = about(item, people),
                imported = if (mine) item.imported else MemoryImported.CONTENT,
            )
        }
        val file = have.copy(items = out)
        return MemoryRestored(file, out.associate { it.id to it.id }, out.size, all.size - out.size)
    }

    private fun about(item: MemoryItem, people: (String) -> String?): List<String> = item.about.mapNotNull(people).distinct()

    /** An item as Import reads one: never its id or links; content when it wasn't your own words. */
    private fun entry(i: MemoryItem) = ImportEntry(
        text = i.text,
        kind = i.kind,
        secret = i.secret,
        pinned = i.pinned,
        source = i.source,
        sourceLabel = i.sourceLabel,
        language = i.language,
        createdAt = i.createdAt.takeIf { it > 0 },
        updatedAt = i.updatedAt.takeIf { it > 0 },
        expiresAt = i.expiresAt,
        uses = i.uses.coerceIn(0, MemoryImport.MAX_USES),
        usedAt = i.usedAt,
        contentMark = i.tainted,
        filing = i.filing,
    )
}
