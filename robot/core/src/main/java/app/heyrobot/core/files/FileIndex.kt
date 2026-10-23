// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.StrictJson

// Pure. Robot Files' catalogue (files-1.4-plan.md §2.2, §4.4): every file's entry, kept as JSON sealed in index.enc
// (FileStore) and carried in plain inside a backup's own encryption (§2.8, F11). The index holds names, fields and text,
// so it is never on disk unsealed, and nothing of it goes in a log.
//
//   {"v":1,"entries":[{"id":"<32 hex>","name":"2026-08-14 Costco 84.12 CAD","kind":"RECEIPT","state":"FILED", …,
//     "from":{"source":"SHARE","at":1760270000000,"size":48213,"sha256":"<64 hex>", …}}, …]}
//
// Versions: VERSION is the newest this Robot reads and the one it writes. A higher "v" is a newer Robot's: read as TooNew,
// never written over (Settings › Files says so and offers Start over, which keeps it aside). Within a version a later
// Robot may add keys, and values to the named sets (kinds, sources, …): this one keeps every key it doesn't know, at the
// top, in an entry and in its provenance, and every value it doesn't know (read as UNKNOWN, written back as it was), so a
// file it rewrites loses nothing. Changing what a key means, or its type, takes a new version.
//
// Reading is strict where it counts: StrictJson (RFC 8259 only), the top must be an object with "v" and "entries"; an
// entry whose known keys are missing, of the wrong type or over their caps is dropped and counted (its sealed bytes are
// kept: FileStore adopts them into Inbox), as is a second entry with an id already read. 1.5's Robot to Robot fields
// (an entry's "peer", "mode" and "why", §2.9) aren't 1.4's: read as absent, kept as unknown keys.
//
// Caps (EST, §2.5): an entry is at most FileLimits.MAX_ENTRY bytes encoded, so the 5,000 files allowed fit in the index's
// 32 MiB whatever the script; its text at most MAX_TEXT characters and within that; its fields MAX_FIELDS bytes encoded.

/** What a file is. UNKNOWN: not known yet (a new file in Inbox), or a kind a newer Robot added. */
enum class FileKind { RECEIPT, BILL, DOCUMENT, ID, TAX_SLIP, TICKET, MEDICAL, PHOTO, VIDEO, NOTE, TRANSCRIPT, OTHER, DANGEROUS, UNKNOWN }

/** Where a file is: waiting in Inbox, or filed. UNKNOWN: a newer Robot's state, shown as Inbox. */
enum class FileState { INBOX, FILED, UNKNOWN }

/**
 * How a file came in. ROBOT is reserved for 1.5 (from a paired Robot): 1.4 never writes it. RECOVERED: Robot found its
 * sealed bytes without an entry (an add cut short, Start over) and put it in Inbox; its name and source were lost.
 * UNKNOWN: a newer Robot's source.
 */
enum class FileSource { SHARE, SCAN, PANEL, TRANSCRIPT, BACKUP, ROBOT, RECOVERED, UNKNOWN }

/** Who filed it where it is: Robot on its own, one of your rules, or you. UNKNOWN: a newer Robot's. */
enum class FiledBy { ROBOT, RULE, YOU, UNKNOWN }

/** Who set a field: Robot's reading, you, or (1.5, reserved) another Robot. UNKNOWN: a newer Robot's. */
enum class FieldBy { READ, YOU, PEER, UNKNOWN }

/**
 * Where Robot's filing of a file in Inbox stands (F6, §4.1): [NEW], not tried yet (also an entry with no "filing");
 * [WAIT], waiting for something that may change (a model that sees pictures); [ASK], waiting for you (a question, or Ask
 * me first); [HELD], kept in Inbox for good unless you file it (dangerous, unreadable, too big, its kind's filing Off);
 * [FILED], Robot or a rule filed it; [KEPT], you put it back in Inbox (Undo): Robot never files it on its own again.
 * UNKNOWN: a newer Robot's, left alone.
 */
enum class FilingStep { NEW, WAIT, ASK, HELD, FILED, KEPT, UNKNOWN }

/** Why a file waits, what Robot asks, or why it holds it (FilesStrings says each). UNKNOWN: a newer Robot's. */
enum class FilingWhy { NO_VISION, NO_MODEL, SMALL_CONTEXT, KIND, TOTAL, FILE_THEM, UNREADABLE, TOO_BIG, DANGEROUS, OFF, UNKNOWN,
    /**
     * F11: from a backup that isn't yours (core/backup FilesBackup): it waits in Inbox for you, its old drawer only a
     * suggestion ([Filing.suggest]). Appended: the voice log and older 1.4 Robots read these by their order and name.
     */
    NOT_YOURS }

/**
 * Robot's filing of one file (the entry's "filing", F6; additive in index v1, so a 1.4 Robot from before F6 keeps it as an
 * unknown key): [step], [why], [tries] (reads started that came to no verdict, for the retry cap), [choices] (the kinds a
 * KIND question weighs), and what Undo puts back: the name and tags the file had before Robot filed it ([wasName],
 * [wasTags]; file content, so it lives here in the sealed index and nowhere else). [suggest] (F11): the drawer a file from
 * someone else's backup was in there, offered to you and never applied by Robot. [extra]: keys this Robot doesn't know.
 */
data class Filing(
    val step: FilingStep,
    val why: FilingWhy? = null,
    val tries: Int = 0,
    val choices: List<FileKind> = emptyList(),
    val wasName: String? = null,
    val wasTags: List<String>? = null,
    val extra: Map<String, Any?> = emptyMap(),
    internal val raw: Map<String, String> = emptyMap(),
    val suggest: String? = null,
) {
    init {
        require(tries in 0..FileIndexCodec.MAX_TRIES && choices.size <= 4)
        require(wasName == null || (wasName.length <= FileIndexCodec.MAX_NAME && FileIndexCodec.plain(wasName)))
        require(wasTags == null || (wasTags.size <= FileIndexCodec.MAX_TAGS && wasTags.all(FileIndexCodec::label)))
        require(suggest == null || FileIndexCodec.label(suggest))
        FileIndexCodec.checkExtra(extra)
    }

    override fun toString(): String = "Filing($step, $why, $tries)" // l10n: not text (codes for logs and tests)
}

/**
 * One field's value ("84.12", "2026-10-17") and who set it. [extra]: keys this Robot doesn't know, kept; [rawBy]: a "by"
 * this Robot doesn't know, kept as it was.
 */
data class FileField(val value: String, val by: FieldBy, val extra: Map<String, Any?> = emptyMap(), internal val rawBy: String? = null) {
    init {
        require(value.length in 1..FileIndexCodec.MAX_FIELD_VALUE && FileIndexCodec.plain(value))
        FileIndexCodec.checkExtra(extra)
    }
}

/**
 * Where a file came from and what Robot knows of its bytes (§4.4): [source]; [app], the sending app's package only when
 * Android confirmed it; [at], when it came in (ms); [size] and [sha256] of its plaintext (sha256 null only for a damaged
 * file Robot recovered without being able to read it); [type], the type sniffed from its first bytes (never the name's or
 * the sender's); whether you [opened] it; who filed it and when. [extra]: keys this Robot doesn't know, kept.
 */
data class Provenance(
    val source: FileSource,
    val at: Long,
    val size: Long,
    val sha256: String? = null,
    val app: String? = null,
    val type: String? = null,
    val opened: Boolean = false,
    val filedBy: FiledBy? = null,
    val filedAt: Long? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    init {
        require(at >= 0 && size in 0..FileIndexCodec.MAX_SIZE)
        require(sha256 == null || FileIndexCodec.SHA256.matches(sha256))
        require(app == null || (app.length <= FileIndexCodec.MAX_APP && FileIndexCodec.PACKAGE.matches(app)))
        require(type == null || (type.length <= FileIndexCodec.MAX_TYPE && FileIndexCodec.MIME.matches(type)))
        require(filedAt == null || filedAt >= 0)
        FileIndexCodec.checkExtra(extra)
    }
}

/**
 * One file's entry. [name]: what it is called ("" until Robot or you name it); [named]: you named it, and Robot never
 * renames it. [kind], [state], [drawer] (the drawer it is filed in, a view's id), [tags], [people] (People ids, from your
 * words, taps or rules only: D14), [fields] (by name: "total", "due"…), [text] (its search text), [thumb] (a sealed
 * thumbnail exists), [damaged] (its bytes failed their check), [filing] (Robot's filing of it, F6). [extra]: keys this
 * Robot doesn't know, kept.
 */
data class FileEntry(
    val id: FileId,
    val name: String,
    val kind: FileKind,
    val state: FileState,
    val from: Provenance,
    val named: Boolean = false,
    val drawer: String? = null,
    val tags: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val fields: Map<String, FileField> = emptyMap(),
    val text: String = "",
    val thumb: Boolean = false,
    val damaged: Boolean = false,
    /** Robot's filing of it (F6): null for a file Robot hasn't tried yet. */
    val filing: Filing? = null,
    val extra: Map<String, Any?> = emptyMap(),
    /** Known keys whose value this Robot didn't know ("kind" → "WARRANTY"), written back as they were while unchanged. */
    internal val raw: Map<String, String> = emptyMap(),
) {
    init {
        require(name.length <= FileIndexCodec.MAX_NAME && FileIndexCodec.plain(name))
        require(drawer == null || FileIndexCodec.label(drawer))
        require(tags.size <= FileIndexCodec.MAX_TAGS && tags.all(FileIndexCodec::label) && tags.toSet().size == tags.size)
        require(people.size <= FileIndexCodec.MAX_PEOPLE && people.all { FileIndexCodec.PERSON.matches(it) } && people.toSet().size == people.size)
        require(fields.size <= FileIndexCodec.MAX_FIELD_COUNT && fields.keys.all { FileIndexCodec.FIELD_NAME.matches(it) })
        require(text.length <= FileLimits.MAX_TEXT && FileIndexCodec.wellFormed(text))
        FileIndexCodec.checkExtra(extra)
    }

    override fun toString(): String = "FileEntry($id, $kind, $state)"  // l10n: not text (toString for logs and tests: never a name or text)
}

/**
 * The catalogue: entries in order, and the top's keys this Robot doesn't know, kept. F12: [outOfSight], the drawers a
 * travel-mode view leaves out (TravelMode.shown): never written (FileStore refuses it), never encoded, only so the screens
 * and words reading a view know those drawers aren't there.
 */
data class FileIndex(val entries: List<FileEntry> = emptyList(), val extra: Map<String, Any?> = emptyMap(), val outOfSight: Set<String> = emptySet()) {
    fun entry(id: FileId): FileEntry? = entries.firstOrNull { it.id == id }
    val ids: Set<FileId> get() = entries.mapTo(LinkedHashSet()) { it.id }

    override fun toString(): String = "FileIndex(${entries.size} entries)"  // l10n: not text (toString for logs and tests)
}

/** What reading the index's JSON gave. */
sealed class IndexParse {
    /** [dropped]: entries left out (damaged, over a cap, a repeated id); their bytes stay and come back through Inbox. */
    data class Ok(val index: FileIndex, val dropped: Int = 0) : IndexParse()
    /** A newer Robot's: read nothing, write nothing. */
    data class TooNew(val version: Int) : IndexParse()
    /** Not the index's JSON at all. */
    data object Damaged : IndexParse()
}

object FileIndexCodec {
    const val VERSION = 1
    const val MAX_NAME = 200
    const val MAX_LABEL = 40
    const val MAX_TAGS = 32
    const val MAX_PEOPLE = 32
    const val MAX_FIELD_COUNT = 24
    const val MAX_FIELD_VALUE = 200
    const val MAX_APP = 255
    const val MAX_TYPE = 100
    /** A size beyond any phone's storage (1 TiB): a newer Robot's larger cap is kept, not refused. */
    const val MAX_SIZE = 1L shl 40
    /** Unknown keys: how deep their values may nest (StrictJson's own limit is deeper). */
    const val MAX_EXTRA_DEPTH = 8
    /** A file's filing tries: the retry cap (FilingPolicy.MAX_TRIES) is far below; a larger count is no 1.4 Robot's. */
    const val MAX_TRIES = 99

    internal val SHA256 = Regex("[0-9a-f]{64}")
    internal val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    internal val MIME = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
    /** People's ids, as PeopleStore.validId checks them. */
    internal val PERSON = Regex("[A-Za-z0-9-]{1,64}")
    internal val FIELD_NAME = Regex("[a-z][a-z0-9_]{0,23}")

    /** No unpaired surrogate (it can't be written as UTF-8 without changing). */
    fun wellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }

    /** One line of words: well formed, no control characters and no direction overrides (a name, a field's value). */
    fun plain(s: String): Boolean = wellFormed(s) && s.none(::control)

    /**
     * A control character, a line or paragraph separator, or a direction mark or override: these can make a name show as
     * something else (a right-to-left override turns "receipt<RLO>fdp.exe" into what reads as "receipt" and "exe.pdf"), so
     * names and field values never hold them.
     */
    private fun control(c: Char): Boolean = c < ' ' || c in '\u007F'..'\u009F' || c == '\u2028' || c == '\u2029' || c == '\u200E' || c == '\u200F' ||
        c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u061C'

    /**
     * A name from outside (the sender's file name, untrusted): control characters and direction marks become spaces,
     * unpaired surrogates go, runs of spaces become one, and it is trimmed and cut to [MAX_NAME] (never inside a pair).
     */
    fun cleanName(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    b.append(c).append(s[i + 1])
                    i += 2
                    continue
                }
                Character.isSurrogate(c) -> {}
                control(c) || Character.isWhitespace(c) -> if (b.isNotEmpty() && b.last() != ' ') b.append(' ')
                else -> b.append(c)
            }
            i++
        }
        var out = b.toString().trim()
        if (out.length > MAX_NAME) {
            out = out.substring(0, if (Character.isHighSurrogate(out[MAX_NAME - 1])) MAX_NAME - 1 else MAX_NAME).trimEnd()
        }
        return out
    }

    /** A drawer's or a tag's id: 1 to 40 characters on one line, not blank. */
    fun label(s: String): Boolean = s.length in 1..MAX_LABEL && plain(s) && s.isNotBlank()

    /** Unknown keys' values must be what StrictJson gives and this codec writes back: maps, lists, strings, numbers, booleans, null. */
    internal fun checkExtra(v: Any?, depth: Int = 0) {
        require(depth <= MAX_EXTRA_DEPTH)
        when (v) {
            null, is Boolean, is Long -> {}
            is Double -> require(v.isFinite())
            is String -> require(wellFormed(v))
            is Map<*, *> -> v.forEach { (k, x) -> require(k is String && wellFormed(k)); checkExtra(x, depth + 1) }
            is List<*> -> v.forEach { checkExtra(it, depth + 1) }
            else -> throw IllegalArgumentException()
        }
    }

    // Writing.

    /** The index as UTF-8 JSON, in a fixed order. Throws [FileException] (TOO_BIG) over the index's cap, and refuses (IllegalArgumentException) an entry over its own. */
    fun encode(index: FileIndex): ByteArray {
        val out = StringBuilder()
        out.append("{\"v\":").append(VERSION).append(",\"entries\":[")  // l10n: not text (JSON keys of the index)
        index.entries.forEachIndexed { i, e ->
            if (i > 0) out.append(',')
            val one = entry(e)
            require(utf8Size(one) <= FileLimits.MAX_ENTRY)
            out.append(one)
        }
        out.append(']')
        for ((k, v) in index.extra) if (k != "v" && k != "entries") {
            out.append(',')
            value(out, k)
            out.append(':')
            value(out, v)
        }
        out.append('}')
        val bytes = out.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > FileLimits.MAX_INDEX) throw FileException(FileProblem.TOO_BIG)
        return bytes
    }

    /** [e]'s JSON object. */
    fun entry(e: FileEntry): String {
        val w = Obj()
        w.str("id", e.id.hex)
        w.str("name", e.name)
        w.str("kind", e.raw["kind"]?.takeIf { e.kind == FileKind.UNKNOWN } ?: e.kind.name)
        w.str("state", e.raw["state"]?.takeIf { e.state == FileState.UNKNOWN } ?: e.state.name)
        if (e.named) w.bool("named", true)
        e.drawer?.let { w.str("drawer", it) }
        if (e.tags.isNotEmpty()) w.field("tags", e.tags)
        if (e.people.isNotEmpty()) w.field("people", e.people)
        if (e.fields.isNotEmpty()) w.put("fields", fieldsJson(e.fields))
        if (e.text.isNotEmpty()) w.str("text", e.text)
        if (e.thumb) w.bool("thumb", true)
        if (e.damaged) w.bool("damaged", true)
        e.filing?.let { w.put("filing", filingJson(it)) }
        w.put("from", provenance(e.from, e.raw))
        for ((k, v) in e.extra) if (k !in ENTRY_KEYS) w.field(k, v)
        return w.done()
    }

    /** Whether [e] fits within an entry's cap (its fields within theirs). */
    fun fits(e: FileEntry): Boolean = try {
        utf8Size(entry(e)) <= FileLimits.MAX_ENTRY
    } catch (_: IllegalArgumentException) {
        false
    }

    /** [e] with its text cut (at a character boundary, never inside a pair) until the entry fits; null when even no text doesn't. */
    fun fitText(e: FileEntry): FileEntry? {
        if (fits(e)) return e
        val bare = e.copy(text = "")
        if (!fits(bare)) return null
        var lo = 0
        var hi = e.text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(e.copy(text = cut(e.text, mid)))) lo = mid else hi = mid - 1
        }
        return e.copy(text = cut(e.text, lo))
    }

    private fun cut(s: String, n: Int): String = if (n > 0 && n < s.length && Character.isHighSurrogate(s[n - 1])) s.substring(0, n - 1) else s.substring(0, n)

    private fun fieldsJson(fields: Map<String, FileField>): String {
        val w = Obj()
        for ((k, f) in fields) {
            val one = Obj()
            one.str("v", f.value)
            one.str("by", f.rawBy?.takeIf { f.by == FieldBy.UNKNOWN } ?: f.by.name)
            for ((x, v) in f.extra) if (x != "v" && x != "by") one.field(x, v)
            w.put(k, one.done())
        }
        val s = w.done()
        require(utf8Size(s) <= FileLimits.MAX_FIELDS)
        return s
    }

    private fun filingJson(f: Filing): String {
        val w = Obj()
        w.str("step", f.raw["step"]?.takeIf { f.step == FilingStep.UNKNOWN } ?: f.step.name)
        f.why?.let { w.str("why", f.raw["why"]?.takeIf { _ -> it == FilingWhy.UNKNOWN } ?: it.name) }
        if (f.tries > 0) w.long("tries", f.tries.toLong())
        if (f.choices.isNotEmpty()) w.field("choices", f.choices.map { it.name })
        f.wasName?.let { w.str("was", it) }
        f.wasTags?.let { w.field("wasTags", it) }
        f.suggest?.let { w.str("suggest", it) }
        for ((k, v) in f.extra) if (k !in FILING_KEYS) w.field(k, v)
        return w.done()
    }

    private fun provenance(p: Provenance, raw: Map<String, String>): String {
        val w = Obj()
        w.str("source", raw["from.source"]?.takeIf { p.source == FileSource.UNKNOWN } ?: p.source.name)
        w.long("at", p.at)
        w.long("size", p.size)
        p.sha256?.let { w.str("sha256", it) }
        p.app?.let { w.str("app", it) }
        p.type?.let { w.str("type", it) }
        if (p.opened) w.bool("opened", true)
        p.filedBy?.let { w.str("filedBy", raw["from.filedBy"]?.takeIf { _ -> it == FiledBy.UNKNOWN } ?: it.name) }
        p.filedAt?.let { w.long("filedAt", it) }
        for ((k, v) in p.extra) if (k !in FROM_KEYS) w.field(k, v)
        return w.done()
    }

    private val ENTRY_KEYS = setOf("id", "name", "kind", "state", "named", "drawer", "tags", "people", "fields", "text", "thumb", "damaged", "filing", "from")  // l10n: not text (JSON keys of the index)
    private val FILING_KEYS = setOf("step", "why", "tries", "choices", "was", "wasTags", "suggest")  // l10n: not text (JSON keys of the index)
    private val FROM_KEYS = setOf("source", "at", "size", "sha256", "app", "type", "opened", "filedBy", "filedAt")  // l10n: not text (JSON keys of the index)

    /** A JSON object written in the order its keys are given. */
    private class Obj {
        private val b = StringBuilder("{")
        private var first = true

        private fun key(k: String) {
            if (!first) b.append(',')
            first = false
            value(b, k)
            b.append(':')
        }

        fun str(k: String, v: String) { key(k); value(b, v) }
        fun long(k: String, v: Long) { key(k); b.append(v) }
        fun bool(k: String, v: Boolean) { key(k); b.append(v) }
        /** [v] is JSON already (a nested object this codec wrote). */
        fun put(k: String, v: String) { key(k); b.append(v) }
        fun field(k: String, v: Any?) { key(k); value(b, v) }
        fun done(): String = b.append('}').toString()
    }

    /** [v] (a value StrictJson gives) as JSON. Strings keep their characters as UTF-8; only quotes, backslashes and controls are escaped. */
    private fun value(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is Boolean -> b.append(v)
            is Long -> b.append(v)
            is Int -> b.append(v)
            is Double -> b.append(if (v.isFinite()) v.toString() else "null")
            is String -> {
                b.append('"')
                for (c in v) when {
                    c == '"' -> b.append("\\\"")
                    c == '\\' -> b.append("\\\\")
                    c == '\n' -> b.append("\\n")
                    c == '\r' -> b.append("\\r")
                    c == '\t' -> b.append("\\t")
                    c < ' ' || c == '\u2028' || c == '\u2029' -> b.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                    else -> b.append(c)
                }
                b.append('"')
            }
            is Map<*, *> -> {
                b.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) b.append(',')
                    first = false
                    value(b, k as String)
                    b.append(':')
                    value(b, x)
                }
                b.append('}')
            }
            is List<*> -> {
                b.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) b.append(',')
                    value(b, x)
                }
                b.append(']')
            }
            else -> throw IllegalArgumentException()
        }
    }

    private fun utf8Size(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    // Reading.

    /** The index in [bytes] (UTF-8 JSON, at most the index's cap). */
    fun decode(bytes: ByteArray): IndexParse {
        if (bytes.size > FileLimits.MAX_INDEX) return IndexParse.Damaged
        val top = StrictJson.parse(bytes)?.value as? Map<*, *> ?: return IndexParse.Damaged
        val v = top["v"] as? Long ?: return IndexParse.Damaged
        if (v > VERSION) return IndexParse.TooNew(v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (v < 1) return IndexParse.Damaged
        val list = top["entries"] as? List<*> ?: return IndexParse.Damaged
        val seen = HashSet<FileId>()
        val entries = ArrayList<FileEntry>(list.size)
        var dropped = 0
        for (x in list) {
            val e = readEntry(x)
            if (e == null || !seen.add(e.id)) {
                dropped++
                continue
            }
            entries += e
        }
        @Suppress("UNCHECKED_CAST")
        val extra = (top as Map<String, Any?>).filterKeys { it != "v" && it != "entries" }
        return IndexParse.Ok(FileIndex(entries, extra), dropped)
    }

    /** One entry, or null when it breaks a rule (and is dropped). */
    private fun readEntry(x: Any?): FileEntry? = try {
        @Suppress("UNCHECKED_CAST")
        val o = x as? Map<String, Any?> ?: throw IllegalArgumentException()
        val raw = HashMap<String, String>()
        val from = o["from"] as? Map<*, *> ?: throw IllegalArgumentException()
        val e = FileEntry(
            id = FileId.of(o["id"] as? String) ?: throw IllegalArgumentException(),
            name = o["name"] as? String ?: throw IllegalArgumentException(),
            kind = named(o["kind"], FileKind.UNKNOWN, "kind", raw),
            state = named(o["state"], FileState.UNKNOWN, "state", raw),
            named = optBool(o, "named"),
            drawer = optStr(o, "drawer"),
            tags = strings(o["tags"]),
            people = strings(o["people"]),
            fields = fields(o["fields"]),
            text = optStr(o, "text") ?: "",
            thumb = optBool(o, "thumb"),
            damaged = optBool(o, "damaged"),
            filing = filing(o["filing"]),
            from = provenance(from, raw),
            extra = o.filterKeys { it !in ENTRY_KEYS },
            raw = raw,
        )
        if (!fits(e)) throw IllegalArgumentException()
        e
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    private fun provenance(o: Map<*, *>, raw: MutableMap<String, String>): Provenance {
        @Suppress("UNCHECKED_CAST")
        val m = o as Map<String, Any?>
        return Provenance(
            source = named(m["source"], FileSource.UNKNOWN, "from.source", raw),
            at = m["at"] as? Long ?: throw IllegalArgumentException(),
            size = m["size"] as? Long ?: throw IllegalArgumentException(),
            sha256 = optStr(m, "sha256"),
            app = optStr(m, "app"),
            type = optStr(m, "type"),
            opened = optBool(m, "opened"),
            filedBy = if (m["filedBy"] == null) null else named(m["filedBy"], FiledBy.UNKNOWN, "from.filedBy", raw),
            filedAt = if (m.containsKey("filedAt")) m["filedAt"] as? Long ?: throw IllegalArgumentException() else null,
            extra = m.filterKeys { it !in FROM_KEYS },
        )
    }

    /**
     * An entry's "filing": Robot's own working state, so one that breaks a rule is read as none (the file is tried again
     * from the start) rather than dropping the entry and its file. Kinds in "choices" this Robot doesn't know are left out.
     */
    private fun filing(v: Any?): Filing? {
        if (v == null) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val m = v as? Map<String, Any?> ?: return null
            val raw = HashMap<String, String>()
            val step = named(m["step"], FilingStep.UNKNOWN, "step", raw)
            val why = if (m["why"] == null) null else named(m["why"], FilingWhy.UNKNOWN, "why", raw)
            val tries = if (m.containsKey("tries")) (m["tries"] as? Long ?: return null) else 0L
            if (tries !in 0..MAX_TRIES.toLong()) return null
            val choices = strings(m["choices"]).mapNotNull { n -> FileKind.entries.firstOrNull { it.name == n && it != FileKind.UNKNOWN } }.take(4)
            Filing(step, why, tries.toInt(), choices, if (m.containsKey("was")) m["was"] as? String ?: return null else null,
                if (m.containsKey("wasTags")) strings(m["wasTags"]) else null, m.filterKeys { it !in FILING_KEYS }, raw,
                suggest = if (m.containsKey("suggest")) m["suggest"] as? String ?: return null else null)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    /** A named value of [E]: a known name, or [unknown] with the string kept in [raw] under [key]. */
    private inline fun <reified E : Enum<E>> named(v: Any?, unknown: E, key: String, raw: MutableMap<String, String>): E {
        val s = v as? String ?: throw IllegalArgumentException()
        if (s.isEmpty() || s.length > MAX_LABEL || !plain(s)) throw IllegalArgumentException()
        if (s == unknown.name) return unknown
        return enumValues<E>().firstOrNull { it.name == s } ?: unknown.also { raw[key] = s }
    }

    private fun optStr(o: Map<String, Any?>, k: String): String? = if (o.containsKey(k)) o[k] as? String ?: throw IllegalArgumentException() else null

    private fun optBool(o: Map<String, Any?>, k: String): Boolean = if (o.containsKey(k)) o[k] as? Boolean ?: throw IllegalArgumentException() else false

    private fun strings(v: Any?): List<String> {
        if (v == null) return emptyList()
        val l = v as? List<*> ?: throw IllegalArgumentException()
        return l.map { it as? String ?: throw IllegalArgumentException() }
    }

    private fun fields(v: Any?): Map<String, FileField> {
        if (v == null) return emptyMap()
        val m = v as? Map<*, *> ?: throw IllegalArgumentException()
        val out = LinkedHashMap<String, FileField>()
        for ((k, x) in m) {
            @Suppress("UNCHECKED_CAST")
            val f = x as? Map<String, Any?> ?: throw IllegalArgumentException()
            val raw = HashMap<String, String>()
            val by = named(f["by"], FieldBy.UNKNOWN, "by", raw)
            out[k as String] = FileField(f["v"] as? String ?: throw IllegalArgumentException(), by, f.filterKeys { it != "v" && it != "by" }, raw["by"])
        }
        return out
    }
}
