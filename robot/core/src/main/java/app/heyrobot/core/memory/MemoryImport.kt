package app.heyrobot.core.memory

import app.heyrobot.core.StrictJson
import kotlin.random.Random

// Pure. Settings › Memory › Import (1.0): a memory export read for its preview, then added to memory through the vault's
// update, under its lock. 0.9's export and 1.0's are the same file (Settings › Memory › Export: MemoryCodec's JSON,
// version 1, indented); 1.0's items may also carry the import mark. The file isn't encrypted and may have been made or
// changed by someone else, so it is read as data from outside: at most [MemoryImport.MAX_BYTES] and
// [MemoryImport.MAX_ITEMS], strict JSON, unknown fields ignored, the ids and the People links in it never used (new ids),
// an item that holds a password, a PIN, a code or a card number refused (as the model's remember refuses one: you add it
// yourself), and every item content until you say otherwise (MemoryImported). Nothing here logs; the app logs counts.

/** Why a file isn't imported at all; the app says each in words. */
enum class ImportProblem {
    /** Over [MemoryImport.MAX_BYTES]. */
    TOO_BIG,
    /** Not strict JSON, not UTF-8, or not an export's shape: "This isn't a Robot memory export." */
    NOT_EXPORT,
    /** Written by a newer Robot (a version this one doesn't know). */
    TOO_NEW,
    /** More than [MemoryImport.MAX_ITEMS] items. */
    TOO_MANY,
}

/**
 * One item read from an export: what Robot keeps of it. Never its id or its People links. [contentMark]: the file says it
 * isn't your own words (kept from something Robot read, a source Robot doesn't know, or imported and never confirmed): it
 * stays content whatever you tick. [toString] never prints the text.
 */
class ImportEntry(
    val text: String,
    val kind: MemoryKind,
    val secret: Boolean,
    val pinned: Boolean,
    val source: MemorySource,
    val sourceLabel: String?,
    val language: String?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val expiresAt: Long?,
    val uses: Int,
    val usedAt: Long?,
    val contentMark: Boolean,
) {
    override fun toString(): String = "ImportEntry(kind=$kind, secret=$secret, content=$contentMark, chars=${text.length})"
}

/** What reading an export gave. */
sealed class ImportRead {
    /** [damaged]: entries that aren't items (no text, not an object), left out and counted. */
    class Ok(val entries: List<ImportEntry>, val damaged: Int) : ImportRead()
    class Refused(val problem: ImportProblem) : ImportRead()
}

/**
 * What importing an export into a memory file adds and skips. [add]: the new items (new ids, the import mark), in the
 * file's order. [inFile]: every entry in the file. Skipped: [duplicates] (the same words as an item already kept, or as an
 * earlier one in the file), [secrets] (a password, a PIN, a code or a card number: never shown), [ended] (past their end),
 * [damaged] (not readable as an item) and [noRoom] (past [MemoryLimits.MAX_ITEMS]).
 */
class ImportPlan(
    val add: List<MemoryItem>,
    val inFile: Int,
    val duplicates: Int,
    val secrets: Int,
    val ended: Int,
    val damaged: Int,
    val noRoom: Int,
) {
    /** Private items among those added: masked in the preview until tapped. */
    val private: Int get() = add.count { it.secret }
    val skipped: Int get() = duplicates + secrets + ended + damaged + noRoom
    override fun toString(): String = "ImportPlan(add=${add.size}, inFile=$inFile, skipped=$skipped)"
}

object MemoryImport {
    /** 5 MB: 0.9's and 1.0's exports hold 2,000 items of 300 characters at most, well under 2 MB. */
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_ITEMS = 5_000
    /** Where a content item came from ("a message from Mom"): Robot writes short ones. */
    const val MAX_LABEL = 100
    /** An item's use counter (it orders "what do you know about me?"'s examples). */
    const val MAX_USES = 10_000

    /**
     * The export in [bytes]. Refused when it is over [MAX_BYTES], isn't strict UTF-8 JSON, isn't an object with a version
     * ("v", a whole number) and an "items" list, was written by a newer Robot, holds more than [MAX_ITEMS] items, or none of
     * its items can be read. Otherwise the items, each checked: a text (a string, cleaned and cut as Robot keeps one), the
     * kind (else a fact), private (anything but false is private), pinned, the source (unknown: content), where content
     * came from, the language, the dates and the use counter (whole numbers, else left out). Every other field is ignored.
     */
    fun read(bytes: ByteArray): ImportRead {
        if (bytes.size > MAX_BYTES) return ImportRead.Refused(ImportProblem.TOO_BIG)
        val root = StrictJson.parse(bytes)?.value as? Map<*, *> ?: return ImportRead.Refused(ImportProblem.NOT_EXPORT)
        val v = root["v"] as? Long ?: return ImportRead.Refused(ImportProblem.NOT_EXPORT)
        if (v > MemoryCodec.VERSION) return ImportRead.Refused(ImportProblem.TOO_NEW)
        if (v < 1) return ImportRead.Refused(ImportProblem.NOT_EXPORT)
        val list = root["items"] as? List<*> ?: return ImportRead.Refused(ImportProblem.NOT_EXPORT)
        if (list.size > MAX_ITEMS) return ImportRead.Refused(ImportProblem.TOO_MANY)
        val entries = list.mapNotNull { (it as? Map<*, *>)?.let(::entry) }
        if (list.isNotEmpty() && entries.isEmpty()) return ImportRead.Refused(ImportProblem.NOT_EXPORT)
        return ImportRead.Ok(entries, list.size - entries.size)
    }

    private fun entry(o: Map<*, *>): ImportEntry? {
        val text = MemoryEdits.clean(o["text"] as? String ?: return null)
        if (text.isEmpty()) return null
        val source = MemorySource.entries.firstOrNull { it.name == o["source"] } ?: MemorySource.CONTENT
        // 1.0's export: "YOURS" was confirmed on the phone it came from; anything else there was never confirmed.
        val unconfirmed = o.containsKey("imported") && o["imported"] != MemoryImported.YOURS.name
        val content = !source.own || unconfirmed
        return ImportEntry(
            text = text,
            kind = MemoryKind.entries.firstOrNull { it.name == o["kind"] } ?: MemoryKind.FACT,
            secret = o.containsKey("secret") && o["secret"] != false,
            pinned = o["pinned"] == true,
            source = source,
            sourceLabel = if (!content) null else (o["sourceLabel"] as? String)?.let { MemoryEdits.clean(it).take(MAX_LABEL).trim() }?.takeIf { it.isNotEmpty() },
            language = (o["lang"] as? String)?.takeIf { it == "en" || it == "fr" },
            createdAt = time(o["created"]),
            updatedAt = time(o["updated"]),
            expiresAt = time(o["expires"]),
            uses = ((o["uses"] as? Long) ?: 0L).coerceIn(0L, MAX_USES.toLong()).toInt(),
            usedAt = time(o["used"]),
            contentMark = content,
        )
    }

    /** A time Robot wrote (ms since 1970, a whole number above 0), or null. */
    private fun time(v: Any?): Long? = (v as? Long)?.takeIf { it > 0 }

    /**
     * What importing [read] into [have] would add now ([now]), in the file's order. An item is skipped when it has ended,
     * has the same words as one in [have] or earlier in the file ([MemoryEdits.sameText]), holds a secret (the same
     * [SecretDetector] rule as what the model asks to remember: refused, counted, never shown), or memory is full.
     * [mine]: "These are my own memories, from my own export"; off (the default), every item is [MemoryImported.CONTENT],
     * and one the file marks as content stays so either way. Dates come along (a missing or future one is [now]), a
     * private item stays private and is never pinned, a pinned one is pinned while fewer than six are. Ids are new,
     * unique in [have].
     */
    fun plan(read: ImportRead.Ok, have: MemoryFile, mine: Boolean, now: Long, random: Random = Random.Default): ImportPlan {
        val keys = have.items.mapTo(HashSet()) { MemoryEdits.textKey(it.text) }
        val ids = have.items.mapTo(HashSet()) { it.id }
        var pinRoom = MemoryLimits.MAX_PINNED - have.items.count { it.pinned }
        var room = MemoryLimits.MAX_ITEMS - have.items.size
        val add = ArrayList<MemoryItem>()
        var duplicates = 0
        var secrets = 0
        var ended = 0
        var noRoom = 0
        // A time Robot couldn't have written yet (the file's clock ahead, or made up) reads as now.
        fun past(t: Long?): Long? = t?.takeIf { it <= now + MemoryLimits.DAY_MS }
        for (e in read.entries) {
            if (e.expiresAt != null && now >= e.expiresAt) { ended++; continue }
            if (!keys.add(MemoryEdits.textKey(e.text))) { duplicates++; continue }
            if (SecretDetector.isSecret(e.text)) { secrets++; continue }
            if (room <= 0) { noRoom++; continue }
            room--
            var id: String
            do { id = MemoryIds.newId(now, random) } while (!ids.add(id))
            val pinned = e.pinned && !e.secret && pinRoom > 0
            if (pinned) pinRoom--
            val created = past(e.createdAt) ?: now
            add += MemoryItem(
                id = id,
                text = e.text,
                kind = e.kind,
                secret = e.secret,
                pinned = pinned,
                source = e.source,
                sourceLabel = e.sourceLabel,
                language = e.language,
                createdAt = created,
                updatedAt = past(e.updatedAt)?.takeIf { it >= created } ?: created,
                expiresAt = e.expiresAt,
                uses = e.uses,
                usedAt = past(e.usedAt),
                imported = if (mine && !e.contentMark) MemoryImported.YOURS else MemoryImported.CONTENT,
            )
        }
        return ImportPlan(add, read.entries.size + read.damaged, duplicates, secrets, ended, read.damaged, noRoom)
    }

    /** [file] with [plan]'s items added after its own (the vault writes it, under its lock). */
    fun into(file: MemoryFile, plan: ImportPlan): MemoryFile = if (plan.add.isEmpty()) file else file.copy(items = file.items + plan.add)
}
