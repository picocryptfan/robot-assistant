// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.StrictJson

// Pure. lists v1 (lists-plan.md §4.4): the lists' JSON inside lists.enc, and inside a backup's own encryption (LS7).
//
//   {"v":1,"lists":[{"id":"<32 hex>","role":"shopping","made":1761150000000,"rev":1,"from":"you","items":[
//     {"id":"<32 hex>","text":"oat milk","qty":"2 L","pos":1000,"at":…,"upd":…,"by":"you","how":"voice","rev":1,
//      "hist":[{"op":"add","at":…,"by":"you","how":"voice"}]}, …]}, …]}
//
// Versions: VERSION is the newest this Robot reads and the one it writes. A higher "v" is a newer Robot's: TooNew, never
// written over. Within v1 a later Robot may add keys (anywhere: the top, a list, an item, a change) and values of the
// named fields (role, from, by, how, op): this one keeps every key it doesn't know and every value it doesn't know, and
// writes them back as they were, so a 1.4 save loses nothing a 1.5 Robot wrote. 1.5's reserved keys ("shared" and
// "origin" on a list, "peer" on an item) are such keys: read as absent, never written by 1.4, kept. Changing a key's
// meaning or type, or raising a cap, takes a new version.
//
// Reading is strict: StrictJson (RFC 8259 only); a known key of the wrong type, a bad or repeated id, a second list with
// the same role, text that isn't one plain line, or anything over a cap makes the whole file Damaged. Unlike Files' index
// (whose dropped entries come back through Inbox from their sealed bytes), a list item left out would simply be gone at
// the next write, so a file this Robot can't read whole is never read in part and never written over: Settings › Lists
// offers Start over (keeping it aside) or a restore. Robot can't write such a file itself: every rule is checked when a
// value is built (ListsModel), and the encoder refuses a file over [ListsLimits.MAX_FILE].

/** What reading the lists' JSON gave. */
sealed class ListsParse {
    data class Ok(val file: ListsFile) : ListsParse()
    /** A newer Robot's: read nothing, write nothing. */
    data class TooNew(val version: Int) : ListsParse()
    /** Not lists v1 as written: never written over. */
    data object Damaged : ListsParse()
}

object ListsCodec {
    const val VERSION = 1

    /** The keys 1.4 knows, at each level. Anything else is kept as it was. */
    internal val FILE_KEYS = setOf("v", "lists")  // l10n: not text (JSON keys of the lists file)
    internal val LIST_KEYS = setOf("id", "role", "name", "made", "rev", "from", "items")  // l10n: not text (JSON keys of the lists file)
    internal val ITEM_KEYS = setOf("id", "text", "qty", "done", "pos", "at", "upd", "by", "how", "rev", "gone", "hist")  // l10n: not text (JSON keys of the lists file)
    internal val CHANGE_KEYS = setOf("op", "at", "by", "how")  // l10n: not text (JSON keys of the lists file)

    /** 1.5's reserved keys (§9): never written by 1.4, read as absent, kept with the other unknown keys. */
    val RESERVED_LIST = setOf("shared", "origin")  // l10n: not text (JSON keys of the lists file)
    val RESERVED_ITEM = setOf("peer")  // l10n: not text (JSON keys of the lists file)

    // Writing.

    /** [f] as UTF-8 JSON in a fixed order, or null when it is over [ListsLimits.MAX_FILE]. */
    fun encodeOrNull(f: ListsFile): ByteArray? = text(f).toByteArray(Charsets.UTF_8).takeIf { it.size <= ListsLimits.MAX_FILE }

    /** [f] as UTF-8 JSON; throws IllegalArgumentException over the cap (ListsEdits and ListsMerge check [fits] first). */
    fun encode(f: ListsFile): ByteArray = requireNotNull(encodeOrNull(f))

    /** [f]'s size in bytes once written. */
    fun size(f: ListsFile): Int = JsonOut.utf8Size(text(f))

    /** Whether [f] fits within [ListsLimits.MAX_FILE] once written. */
    fun fits(f: ListsFile): Boolean = size(f) <= ListsLimits.MAX_FILE

    private fun text(f: ListsFile): String {
        val out = StringBuilder()
        out.append("{\"v\":").append(VERSION).append(",\"lists\":[")  // l10n: not text (JSON of the lists file)
        f.lists.forEachIndexed { i, l ->
            if (i > 0) out.append(',')
            list(out, l)
        }
        out.append(']')
        for ((k, v) in f.extra) if (k !in FILE_KEYS) {
            out.append(',')
            JsonOut.value(out, k)
            out.append(':')
            JsonOut.value(out, v)
        }
        return out.append('}').toString()
    }

    private fun list(out: StringBuilder, l: ListsList) {
        val w = JsonOut.Obj(out)
        w.str("id", l.id.hex)
        (l.role?.json ?: l.raw["role"])?.let { w.str("role", it) }
        l.name?.let { w.str("name", it) }
        w.long("made", l.made)
        w.long("rev", l.rev)
        w.str("from", named(l.from, ListFrom.UNKNOWN, l.raw["from"]))
        w.key("items")
        out.append('[')
        l.items.forEachIndexed { i, it ->
            if (i > 0) out.append(',')
            item(out, it)
        }
        out.append(']')
        for ((k, v) in l.extra) if (k !in LIST_KEYS) w.field(k, v)
        w.done()
    }

    private fun item(out: StringBuilder, i: ListItem) {
        val w = JsonOut.Obj(out)
        w.str("id", i.id.hex)
        w.str("text", i.text)
        i.qty?.let { w.str("qty", it) }
        if (i.done) w.bool("done", true)
        w.long("pos", i.pos)
        w.long("at", i.at)
        w.long("upd", i.upd)
        w.str("by", named(i.by, ItemBy.UNKNOWN, i.raw["by"]))
        w.str("how", named(i.how, ItemHow.UNKNOWN, i.raw["how"]))
        w.long("rev", i.rev)
        i.gone?.let { w.long("gone", it) }
        if (i.hist.isNotEmpty()) {
            w.key("hist")
            out.append('[')
            i.hist.forEachIndexed { k, c ->
                if (k > 0) out.append(',')
                change(out, c)
            }
            out.append(']')
        }
        for ((k, v) in i.extra) if (k !in ITEM_KEYS) w.field(k, v)
        w.done()
    }

    private fun change(out: StringBuilder, c: ListChange) {
        val w = JsonOut.Obj(out)
        w.str("op", named(c.op, ItemOp.UNKNOWN, c.raw["op"]))
        w.long("at", c.at)
        w.str("by", named(c.by, ItemBy.UNKNOWN, c.raw["by"]))
        w.str("how", named(c.how, ItemHow.UNKNOWN, c.raw["how"]))
        for ((k, v) in c.extra) if (k !in CHANGE_KEYS) w.field(k, v)
        w.done()
    }

    /** A named value as written: its lower-case name, or, for one this Robot didn't know, the value it read. */
    private fun <E : Enum<E>> named(e: E, unknown: E, raw: String?): String =
        if (e == unknown) requireNotNull(raw) else e.name.lowercase(java.util.Locale.ROOT)

    // Reading.

    /** The file in [bytes] (UTF-8 JSON). Any rule broken: Damaged. */
    fun decode(bytes: ByteArray): ListsParse {
        if (bytes.size > ListsLimits.MAX_FILE) return ListsParse.Damaged
        val top = StrictJson.parse(bytes)?.value as? Map<*, *> ?: return ListsParse.Damaged
        val v = top["v"] as? Long ?: return ListsParse.Damaged
        if (v > VERSION) return ListsParse.TooNew(v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (v < 1) return ListsParse.Damaged
        return try {
            val lists = (top["lists"] as? List<*> ?: throw Bad()).map(::readList)
            @Suppress("UNCHECKED_CAST")
            ListsParse.Ok(ListsFile(lists, (top as Map<String, Any?>).filterKeys { it !in FILE_KEYS }))
        } catch (_: Bad) {
            ListsParse.Damaged
        } catch (_: IllegalArgumentException) {
            ListsParse.Damaged
        } catch (_: ClassCastException) {
            ListsParse.Damaged
        }
    }

    private fun readList(x: Any?): ListsList {
        val o = obj(x)
        val raw = HashMap<String, String>()
        val role = if (o.containsKey("role")) named(o["role"], null, "role", raw) { s -> ListRole.entries.firstOrNull { it.json == s } } else null
        val items = (o["items"] as? List<*> ?: throw Bad()).map(::readItem)
        return ListsList(
            id = ListsId.of(o["id"] as? String) ?: throw Bad(),
            role = role,
            name = optStr(o, "name"),
            made = long(o, "made"),
            rev = long(o, "rev"),
            from = named(o["from"], ListFrom.UNKNOWN, "from", raw),
            items = items,
            extra = o.filterKeys { it !in LIST_KEYS },
            raw = raw,
        )
    }

    private fun readItem(x: Any?): ListItem {
        val o = obj(x)
        val raw = HashMap<String, String>()
        val hist = if (o.containsKey("hist")) (o["hist"] as? List<*> ?: throw Bad()).map(::readChange) else emptyList()
        return ListItem(
            id = ListsId.of(o["id"] as? String) ?: throw Bad(),
            text = o["text"] as? String ?: throw Bad(),
            qty = optStr(o, "qty"),
            done = if (o.containsKey("done")) o["done"] as? Boolean ?: throw Bad() else false,
            pos = long(o, "pos"),
            at = long(o, "at"),
            upd = long(o, "upd"),
            by = named(o["by"], ItemBy.UNKNOWN, "by", raw),
            how = named(o["how"], ItemHow.UNKNOWN, "how", raw),
            rev = long(o, "rev"),
            gone = if (o.containsKey("gone")) long(o, "gone") else null,
            hist = hist,
            extra = o.filterKeys { it !in ITEM_KEYS },
            raw = raw,
        )
    }

    private fun readChange(x: Any?): ListChange {
        val o = obj(x)
        val raw = HashMap<String, String>()
        return ListChange(
            op = named(o["op"], ItemOp.UNKNOWN, "op", raw),
            at = long(o, "at"),
            by = named(o["by"], ItemBy.UNKNOWN, "by", raw),
            how = named(o["how"], ItemHow.UNKNOWN, "how", raw),
            extra = o.filterKeys { it !in CHANGE_KEYS },
            raw = raw,
        )
    }

    private inline fun <reified E : Enum<E>> named(v: Any?, unknown: E, key: String, raw: MutableMap<String, String>): E =
        named(v, unknown, key, raw) { s -> enumValues<E>().firstOrNull { it != unknown && it.name.lowercase(java.util.Locale.ROOT) == s } }

    /**
     * A named value: a string of 1 to 40 plain characters; [known] maps it, else it is [unknown] and the string is kept in
     * [raw] under [key], to be written back as it was.
     */
    private fun <E> named(v: Any?, unknown: E, key: String, raw: MutableMap<String, String>, known: (String) -> E?): E {
        val s = v as? String ?: throw Bad()
        if (!ListsText.line(s, ListsLimits.MAX_NAME)) throw Bad()
        return known(s) ?: unknown.also { raw[key] = s }
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(x: Any?): Map<String, Any?> = x as? Map<String, Any?> ?: throw Bad()

    private fun long(o: Map<String, Any?>, k: String): Long = o[k] as? Long ?: throw Bad()

    private fun optStr(o: Map<String, Any?>, k: String): String? = if (o.containsKey(k)) o[k] as? String ?: throw Bad() else null

    /** A rule broken: the whole file is Damaged. No message, no stack: nothing of the file is kept in one. */
    internal class Bad : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }
}

/** JSON written in a fixed order, as StrictJson reads it back: lists' own writer (Files' index has the same rules). */
internal object JsonOut {
    /** A JSON object written into [b] in the order its keys are given. */
    class Obj(private val b: StringBuilder) {
        private var first = true

        init {
            b.append('{')
        }

        fun key(k: String) {
            if (!first) b.append(',')
            first = false
            value(b, k)
            b.append(':')
        }

        fun str(k: String, v: String) { key(k); value(b, v) }
        fun long(k: String, v: Long) { key(k); b.append(v) }
        fun bool(k: String, v: Boolean) { key(k); b.append(v) }
        fun field(k: String, v: Any?) { key(k); value(b, v) }
        fun done() { b.append('}') }
    }

    /** [v] (a value StrictJson gives) as JSON. Strings keep their characters as UTF-8; only quotes, backslashes and controls are escaped. */
    fun value(b: StringBuilder, v: Any?) {
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
                    c < ' ' || c == ' ' || c == ' ' -> b.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
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

    /** UTF-8 bytes of [s] (a pair is 4, a lone surrogate never occurs: values are checked well formed). */
    fun utf8Size(s: CharSequence): Int {
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
}
