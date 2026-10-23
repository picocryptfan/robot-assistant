// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.StrictJson
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

// Pure. What the decrypted stream holds: records, in order.
//
//   record = kind(1) ‖ meta_len(u32) ‖ meta(StrictJson object, ≤ 4 KiB) ‖ [data_len(u64) ‖ data]   (data for 'E' only)
//
//   'M' manifest  {"robot":"1.1.0","code":1100099,"made":1760270000000,"format":1,"categories":{"notes":{"entries":37,"bytes":122880},…},"keys":false}
//                 first, exactly once
//   'E' entry     {"c":"notes","n":"3f2a….json","v":1}   a category, a name of that category, the file's own schema
//   'Z' end       {"entries":1043,"bytes":24117248}       last, exactly once; must equal what was read
//
// Reading is strict: an unknown kind, a meta over 4 KiB or not strict JSON, a missing field, a second manifest, a record
// after the end, the same entry twice, an end whose counts differ, or anything after it is [OpenProblem.DAMAGED]. Whether
// a name fits its category, and the category's caps, are BackupCategory's (a refused entry refuses its category, not the
// file); an entry of a category this Robot doesn't know is read through and skipped. No compression in format 1.

/** [s] as a JSON string, the same on the JVM and on Android (their org.json quote "/" differently). */
internal fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c < ' ' -> append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
        else -> append(c)
    }
    append('"')
}

object RecordKind {
    const val MANIFEST = 'M'.code
    const val ENTRY = 'E'.code
    const val END = 'Z'.code
}

/** What one category holds, as the manifest lists it. */
data class CategoryCount(val entries: Long, val bytes: Long)

/**
 * The first record. [robot]: the version name of the Robot that made it; [code] its version code; [made] when (wall clock
 * ms); [categories] what the writer chose and planned to write, by category id (the reader counts for itself);
 * [keys]: Keys and private codes are in it.
 */
data class BackupManifest(
    val robot: String,
    val code: Long,
    val made: Long,
    val categories: Map<String, CategoryCount>,
    val keys: Boolean,
    val format: Int = BackupFormat.FORMAT,
) {
    init {
        require(robot.length in 1..MAX_VERSION && robot.all { it in '!'..'~' })
        require(code >= 0 && made >= 0 && categories.size <= MAX_CATEGORIES)
    }

    /** Written by hand in a fixed order (org.json's order differs between the JVM and Android). */
    fun toJson(): String = buildString {
        append("{\"robot\":").append(jsonString(robot))  // l10n: not text (JSON keys of the manifest)
        append(",\"code\":").append(code)  // l10n: not text (JSON keys of the manifest)
        append(",\"made\":").append(made)  // l10n: not text (JSON keys of the manifest)
        append(",\"format\":").append(format)  // l10n: not text (JSON keys of the manifest)
        append(",\"categories\":{")
        categories.entries.forEachIndexed { i, (c, n) ->
            if (i > 0) append(',')
            append(jsonString(c)).append(":{\"entries\":").append(n.entries).append(",\"bytes\":").append(n.bytes).append('}')  // l10n: not text (JSON keys of the manifest)
        }
        append("},\"keys\":").append(keys).append('}')  // l10n: not text (JSON keys of the manifest)
    }

    companion object {
        const val MAX_VERSION = 40
        const val MAX_CATEGORIES = 32

        /** The manifest in [meta], or null when it isn't one. Unknown fields are ignored (a newer Robot's). */
        fun parse(meta: Map<*, *>): BackupManifest? = try {
            val cats = meta["categories"] as? Map<*, *> ?: return null
            BackupManifest(
                robot = meta["robot"] as? String ?: return null,
                code = meta["code"] as? Long ?: return null,
                made = meta["made"] as? Long ?: return null,
                format = (meta["format"] as? Long)?.toInt() ?: return null,
                keys = meta["keys"] as? Boolean ?: return null,
                categories = cats.entries.associate { (k, v) ->
                    val o = v as? Map<*, *> ?: return null
                    val c = k as? String ?: return null
                    if (c.isEmpty() || c.length > 32) return null
                    c to CategoryCount(
                        (o["entries"] as? Long)?.takeIf { it >= 0 } ?: return null,
                        (o["bytes"] as? Long)?.takeIf { it >= 0 } ?: return null,
                    )
                },
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** One record as read. An [Entry]'s [Entry.data] gives exactly its [Entry.length] bytes; what isn't read is skipped. */
sealed class BackupRecord {
    class Manifest(val manifest: BackupManifest) : BackupRecord() {
        override fun toString(): String = "Manifest(${manifest.robot}, ${manifest.categories.keys})"  // l10n: not text (toString for logs and tests)
    }

    /** [category]: the "c" as written ([known]: one this Robot has); [schema]: the "v", 0 when absent. */
    class Entry(val category: String, val name: String, val schema: Int, val length: Long, val data: InputStream) : BackupRecord() {
        val known: BackupCategory? get() = BackupCategory.of(category)
        override fun toString(): String = "Entry($category, ${length}B)"  // l10n: not text (toString for logs and tests)
    }

    class End(val entries: Long, val bytes: Long) : BackupRecord() {
        override fun toString(): String = "End($entries, $bytes)"  // l10n: not text (toString for logs and tests)
    }
}

/**
 * Writes the records to [out] (a [BackupWriter]): [manifest] first, then each [entry], then [end]. Refuses (throws
 * IllegalArgumentException or IllegalStateException) anything a reader would refuse, so Robot never writes a file it
 * can't read: a name outside its category, an entry over its cap, the same entry twice, records out of order.
 */
class BackupContainerWriter(private val out: OutputStream) {
    private var started = false
    private var ended = false
    private var entries = 0L
    private var bytes = 0L
    private val names = HashSet<String>()
    private val perCategory = HashMap<BackupCategory, Int>()

    fun manifest(m: BackupManifest) {
        check(!started)
        started = true
        record(RecordKind.MANIFEST, m.toJson())
    }

    fun entry(category: BackupCategory, name: String, data: ByteArray, schema: Int? = category.schema(name)) =
        entry(category, name, data.size.toLong(), data.inputStream(), schema)

    /** Writes exactly [length] bytes of [data] (fewer is refused: the file changed while it was read). */
    fun entry(category: BackupCategory, name: String, length: Long, data: InputStream, schema: Int? = category.schema(name)) {
        check(started && !ended)
        val max = requireNotNull(category.maxBytes(name)) { "name" }
        require(length in 0..max) { "size" }
        require(schema == null || schema in 0..(category.schema(name) ?: 0)) { "schema" }
        require(names.add(category.id + "\u0000" + name)) { "twice" }
        val n = (perCategory[category] ?: 0) + 1
        require(n <= category.maxEntries) { "entries" }
        perCategory[category] = n
        require(entries + 2 < BackupCaps.MAX_RECORDS && bytes + length <= BackupCaps.MAX_TOTAL) { "file" }
        val meta = "{\"c\":" + jsonString(category.id) + ",\"n\":" + jsonString(name) + (if (schema != null && schema > 0) ",\"v\":$schema" else "") + "}"
        record(RecordKind.ENTRY, meta)
        writeLong(length)
        val buf = ByteArray(BackupFormat.CHUNK)
        var left = length
        while (left > 0) {
            val k = data.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (k < 0) throw EOFException()
            out.write(buf, 0, k)
            left -= k
        }
        entries++
        bytes += length
    }

    fun end() {
        check(started && !ended)
        ended = true
        record(RecordKind.END, "{\"entries\":$entries,\"bytes\":$bytes}")  // l10n: not text (JSON of the end record)
    }

    private fun record(kind: Int, meta: String) {
        val m = meta.toByteArray(Charsets.UTF_8)
        require(m.size <= BackupCaps.META)
        out.write(kind)
        writeInt(m.size.toLong())
        out.write(m)
    }

    private fun writeInt(v: Long) {
        for (s in 24 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt())
    }

    private fun writeLong(v: Long) {
        for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt())
    }
}

/**
 * Reads the records from [input] (a [BackupReader]) and checks the structure as it goes. [next] gives each record and
 * null after the end record, once the stream is checked to end there; anything wrong throws [BackupException].
 */
class BackupContainerReader(private val input: InputStream) {
    private var records = 0
    private var entries = 0L
    private var bytes = 0L
    private var manifest: BackupManifest? = null
    private var ended = false
    private var open: Bounded? = null
    private val names = HashSet<String>()

    val manifestRead: BackupManifest? get() = manifest

    fun next(): BackupRecord? {
        open?.let { it.skipRest(); open = null }
        if (ended) return null
        val kind = input.read()
        if (kind < 0) throw BackupException(OpenProblem.DAMAGED)
        if (++records > BackupCaps.MAX_RECORDS) throw BackupException(OpenProblem.TOO_BIG)
        val meta = meta()
        return when (kind) {
            RecordKind.MANIFEST -> {
                if (manifest != null) damaged()
                val m = BackupManifest.parse(meta) ?: damaged()
                if (m.format != BackupFormat.FORMAT) damaged()
                manifest = m
                BackupRecord.Manifest(m)
            }
            RecordKind.ENTRY -> {
                if (manifest == null) damaged()
                if (meta.keys.any { it != "c" && it != "n" && it != "v" }) damaged()
                val c = meta["c"] as? String ?: damaged()
                val n = meta["n"] as? String ?: damaged()
                val v = when (val x = meta["v"]) {
                    null -> 0
                    is Long -> if (x in 0..Int.MAX_VALUE) x.toInt() else damaged()
                    else -> damaged()
                }
                if (c.isEmpty() || c.length > 32 || n.isEmpty() || n.length > BackupCategory.MAX_NAME) damaged()
                if (!names.add(c + "\u0000" + n)) damaged()
                val length = readLong()
                if (length < 0) damaged()
                // The whole file's cap; a category's own caps are BackupCategory's (the reader refuses that category).
                if (bytes + length > BackupCaps.MAX_TOTAL) throw BackupException(OpenProblem.TOO_BIG)
                entries++
                bytes += length
                val data = Bounded(input, length)
                open = data
                BackupRecord.Entry(c, n, v, length, data)
            }
            RecordKind.END -> {
                if (manifest == null) damaged()
                val e = meta["entries"] as? Long ?: damaged()
                val b = meta["bytes"] as? Long ?: damaged()
                if (e != entries || b != bytes) damaged()
                // Nothing may follow the end record: reading on also makes the stream check its last chunk.
                if (input.read() >= 0) damaged()
                ended = true
                BackupRecord.End(e, b)
            }
            else -> damaged()
        }
    }

    private fun meta(): Map<*, *> {
        val len = readInt()
        if (len < 2 || len > BackupCaps.META) damaged()
        val m = ByteArray(len.toInt())
        readFully(m)
        return StrictJson.parse(m)?.value as? Map<*, *> ?: damaged()
    }

    private fun readFully(b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val k = input.read(b, n, b.size - n)
            if (k < 0) damaged()
            n += k
        }
    }

    private fun readInt(): Long {
        val b = ByteArray(4).also(::readFully)
        return b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
    }

    private fun readLong(): Long {
        val b = ByteArray(8).also(::readFully)
        return b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
    }

    private fun damaged(): Nothing = throw BackupException(OpenProblem.DAMAGED)

    /** An entry's data: exactly [left] bytes of [input]; ending early is [OpenProblem.DAMAGED]. */
    private class Bounded(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b < 0) throw BackupException(OpenProblem.DAMAGED)
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            if (len == 0) return 0
            val k = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (k < 0) throw BackupException(OpenProblem.DAMAGED)
            left -= k
            return k
        }

        override fun available(): Int = minOf(left, input.available().toLong()).toInt()

        fun skipRest() {
            val buf = ByteArray(8192)
            while (left > 0) read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        }

        /** Not the backup's stream: closing one entry's data closes nothing. */
        override fun close() {}
    }
}
