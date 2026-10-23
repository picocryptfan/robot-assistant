// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure (org.json, java.io). Conversation transcripts on the phone (docs/conversation-mode.md 6): personal content in
// filesDir/transcripts/<id>/, never in a log, never sent anywhere unless you send it. lines.jsonl is an append-only event
// log, one JSON object per line, fsynced after each record so a crash loses at most the line being decoded; editing
// never rewrites it, every change is one more event. Reading folds the log into a Transcript: a torn last line (a crash
// mid-write) is dropped, and an event this version doesn't know is kept in the file and skipped, so a newer Robot's
// transcripts still open. snapshot.json (written atomically on stop and every 200 events) is the fold so far, so a
// long transcript opens without replaying every event; meta.json holds the title, status and speakers' names.

/** Where a transcript's files live (filesDir/[DIR]/<id>/...). */
object TranscriptFiles {
    const val DIR = "transcripts"
    const val META = "meta.json"
    const val LOG = "lines.jsonl"
    const val SNAPSHOT = "snapshot.json"
    const val AUDIO = "audio.ogg"
    /** A snapshot is written every this many events (and on stop). */
    const val SNAPSHOT_EVERY = 200

    fun summary(n: Int): String = "summary-$n.json"

    /**
     * Appends one [record] (an encoded event, no line break) to the log and fsyncs it. If a crash left the log's last
     * line unfinished, a line break goes first, so the torn line stays alone (and is skipped) and the new record is whole.
     */
    fun append(log: File, record: String) {
        require('\n' !in record) { "one line per record" } // l10n: not text (a programming error's message)
        log.parentFile?.mkdirs()
        val torn = log.isFile && log.length() > 0 && RandomAccessFile(log, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
        FileOutputStream(log, true).use { out ->
            out.write(((if (torn) "\n" else "") + record + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }
}

/** Transcript ids: time-sortable and random ("20261011-1402-7f3a"), safe as a directory name. */
object TranscriptIds {
    private val SHAPE = Regex("^\\d{8}-\\d{4}-[0-9a-f]{4}$")

    fun new(now: LocalDateTime, random: java.util.Random = java.security.SecureRandom()): String =
        now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US)) + "-" + String.format(Locale.US, "%04x", random.nextInt(0x10000))

    fun valid(id: String): Boolean = SHAPE.matches(id)
}

/** One event of the log. [i] is a line's index, given once when the line is made and never reused. */
sealed class TranscriptEvent {
    /**
     * A line heard: [t0] and [t1] in seconds from the session's start, [speaker] ("S2", "Me", "?"), [lang] (null: not
     * sure and no prior), [lid] the language margin, [prior] the language came from the prior ("FR?"), [words] each
     * word's start in centiseconds after [t0], [reheard] decoded again in two halves.
     */
    data class Line(val i: Int, val t0: Double, val t1: Double, val speaker: String, val lang: String?, val text: String,
        val lid: Double = 0.0, val prior: Boolean = false, val words: List<Int> = emptyList(), val reheard: Boolean = false) : TranscriptEvent()

    /** A translation of line [i] into [to], by [by] ([BY_GEMMA] or [BY_BERGAMOT]); a newer one replaces it. */
    data class Translation(val i: Int, val to: String, val by: String, val text: String) : TranscriptEvent()

    /** You corrected line [i]'s words. */
    data class Edit(val i: Int, val text: String) : TranscriptEvent()

    /** You moved line [i] to another speaker. */
    data class Speaker(val i: Int, val speaker: String) : TranscriptEvent()

    /** You changed line [i]'s language (its translations are made again). */
    data class Lang(val i: Int, val lang: String) : TranscriptEvent()

    /** You named a speaker (an empty name removes it). Stored in the transcript only, never linked to a voiceprint. */
    data class Rename(val speaker: String, val name: String) : TranscriptEvent()

    /** "S3 is S1": every line of [from] is [to]'s. */
    data class Merge(val from: String, val to: String) : TranscriptEvent()

    /** "This is me": [speaker] is you. */
    data class Me(val speaker: String) : TranscriptEvent()

    /**
     * You split line [i] at character [at] of its current text: the rest becomes line [n] (a new index), spoken by
     * [speaker] (null: the same speaker).
     */
    data class Split(val i: Int, val at: Int, val n: Int, val speaker: String? = null) : TranscriptEvent()

    data class Delete(val i: Int) : TranscriptEvent()

    /**
     * Nothing was heard from [t0] to [t1] (seconds): [why] is [GAP_MIC], [GAP_PAUSE], [GAP_CALL], [GAP_CRASH] or
     * [GAP_BEHIND] (another value, from a newer Robot, reads as "nothing recorded").
     */
    data class Gap(val t0: Double, val t1: Double, val why: String) : TranscriptEvent()

    /** An event this version doesn't know (a newer Robot wrote it): kept in the file, skipped by the fold. */
    data class Unknown(val raw: String) : TranscriptEvent()

    companion object {
        const val BY_GEMMA = "gemma"
        const val BY_BERGAMOT = "bergamot"
        const val GAP_MIC = "mic"
        const val GAP_PAUSE = "pause"
        const val GAP_CALL = "call"
        const val GAP_CRASH = "crash"
        /** Speech heard but let go undecoded: decoding had fallen too far behind (the queue's bound, 0.5.0). */
        const val GAP_BEHIND = "behind"
    }
}

data class LineTranslation(val to: String, val by: String, val text: String) {
    /** Made by the fast engine: marked "fast". */
    val fast: Boolean get() = by == TranscriptEvent.BY_BERGAMOT
}

/** A line as it stands after every event. [edited]: you changed its words, so they are no longer exactly what was heard. */
data class TranscriptLine(
    val i: Int,
    val t0: Double,
    val t1: Double,
    val speaker: String,
    val lang: String?,
    val text: String,
    val lid: Double = 0.0,
    val prior: Boolean = false,
    val words: List<Int> = emptyList(),
    val reheard: Boolean = false,
    val edited: Boolean = false,
    val translations: Map<String, LineTranslation> = emptyMap(),
)

data class TranscriptGap(val t0: Double, val t1: Double, val why: String)

/**
 * A transcript folded from its log. [lines] in time order, speakers already merged; [names] the speakers' names;
 * [me] the speaker who is you; [records] the log lines folded (whole lines, skipped ones included); [skipped]
 * unknown events and damaged lines; [torn] 1 when the last line was cut off.
 */
data class Transcript(
    val lines: List<TranscriptLine> = emptyList(),
    val gaps: List<TranscriptGap> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    /** Speakers merged into others ("S3" to "S1"), for lines that still arrive with the old label. */
    val merges: Map<String, String> = emptyMap(),
    val me: String? = null,
    val records: Int = 0,
    val skipped: Int = 0,
    val torn: Int = 0,
    /**
     * Names you gave that no speaker has now, by the speaker they went with: a speaker's earlier name, and one a merge
     * dropped (under the speaker it merged into). "Replace names" replaces them too.
     */
    val formerNames: Map<String, List<String>> = emptyMap(),
) {
    /** The speakers in the order they first spoke. */
    val speakers: List<String> get() = lines.map { it.speaker }.distinct()

    /** The languages heard, most lines first. */
    val langs: List<String> get() = lines.mapNotNull { it.lang }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    val words: Int get() = lines.sumOf { Rehear.words(it.text) }

    /** Seconds from the first line's start to the last line's end. */
    val seconds: Double get() = if (lines.isEmpty()) 0.0 else lines.maxOf { it.t1 } - lines.minOf { it.t0 }

    fun line(i: Int): TranscriptLine? = lines.firstOrNull { it.i == i }

    /** A speaker as shown and exported: the name you gave, else "You" for you (« Moi »), else the label (S1, S2…, "?"). */
    fun nameText(speaker: String): Phrase = names[speaker]?.let { Phrase.plain(it) }
        ?: if (speaker == me || speaker == SpeakerClusterer.ME) SpeakerLabels.you else Phrase.plain(speaker)

    /** [nameText] in English. */
    fun nameOf(speaker: String): String = nameText(speaker).en()

    /** One voice only (speaker labels off, or one person heard) and no names: the screens show "Speaker". */
    val oneVoice: Boolean get() = speakers.size <= 1 && names.isEmpty() && me == null && SpeakerClusterer.ME !in speakers
}

/** Folds events one by one (the reader, and the live session's own copy). */
class TranscriptFold(start: Transcript = Transcript()) {
    private val lines = LinkedHashMap<Int, TranscriptLine>().apply { start.lines.forEach { put(it.i, it) } }
    private val gaps = ArrayList(start.gaps)
    private val names = LinkedHashMap(start.names)
    private val merges = LinkedHashMap(start.merges)
    private val former = LinkedHashMap<String, MutableList<String>>().apply { start.formerNames.forEach { (k, v) -> put(k, v.toMutableList()) } }
    private var me = start.me
    private var records = start.records
    private var skipped = start.skipped
    private var torn = start.torn

    fun apply(e: TranscriptEvent?): TranscriptFold {
        records++
        when (e) {
            null -> skipped++
            is TranscriptEvent.Unknown -> skipped++
            is TranscriptEvent.Line -> if (e.i !in lines) lines[e.i] = TranscriptLine(e.i, e.t0, e.t1, resolve(e.speaker), e.lang, e.text, e.lid,
                e.prior, e.words, e.reheard)
            is TranscriptEvent.Translation -> change(e.i) { it.copy(translations = it.translations + (e.to to LineTranslation(e.to, e.by, e.text))) }
            is TranscriptEvent.Edit -> change(e.i) { it.copy(text = e.text, edited = true) }
            is TranscriptEvent.Speaker -> change(e.i) { it.copy(speaker = resolve(e.speaker)) }
            is TranscriptEvent.Lang -> change(e.i) { it.copy(lang = e.lang, prior = false, translations = emptyMap()) }
            is TranscriptEvent.Rename -> {
                val old = names[e.speaker]
                if (e.name.isBlank()) names.remove(e.speaker) else names[e.speaker] = e.name.trim()
                if (old != null && old != names[e.speaker]) formerName(e.speaker, old)
            }
            is TranscriptEvent.Merge -> if (e.from != e.to && resolve(e.to) != e.from) {
                val to = resolve(e.to)
                merges[e.from] = to
                for ((k, l) in lines) if (l.speaker == e.from) lines[k] = l.copy(speaker = to)
                if (me == e.from) me = to
                if (e.from in names && to !in names) names[to] = names.getValue(e.from)
                else if (e.from in names && names[to] != names[e.from]) formerName(to, names.getValue(e.from))
                names.remove(e.from)
            }
            is TranscriptEvent.Me -> me = resolve(e.speaker)
            is TranscriptEvent.Split -> split(e)
            is TranscriptEvent.Delete -> lines.remove(e.i)
            is TranscriptEvent.Gap -> gaps += TranscriptGap(e.t0, e.t1, e.why)
        }
        return this
    }

    private fun formerName(speaker: String, name: String) {
        val l = former.getOrPut(speaker) { ArrayList() }
        if (name !in l) l += name
    }

    /** A cut-off last line: counted, not folded. */
    fun torn(): TranscriptFold {
        torn++
        return this
    }

    private fun resolve(speaker: String): String {
        var s = speaker
        repeat(16) { s = merges[s] ?: return s }
        return s
    }

    private inline fun change(i: Int, f: (TranscriptLine) -> TranscriptLine) {
        lines[i]?.let { lines[i] = f(it) }
    }

    private fun split(e: TranscriptEvent.Split) {
        val l = lines[e.i] ?: return
        if (e.at <= 0 || e.at >= l.text.length || e.n in lines) return
        val head = l.text.substring(0, e.at).trim()
        val tail = l.text.substring(e.at).trim()
        if (head.isEmpty() || tail.isEmpty()) return
        // The time of the split: the start of the word it falls on when the words still match what was heard, else in
        // proportion to the characters.
        val k = Rehear.words(l.text.substring(0, e.at))
        val wordTime = if (!l.edited && k < l.words.size && l.words.size == Rehear.words(l.text)) l.t0 + l.words[k] / 100.0 else null
        // Kept to the centisecond, like every time in the log (so a snapshot holds the same numbers).
        val t = Math.round((wordTime ?: (l.t0 + (l.t1 - l.t0) * e.at / l.text.length)).coerceIn(l.t0, l.t1) * 100) / 100.0
        val keep = !l.edited && wordTime != null
        lines[e.i] = l.copy(t1 = t, text = head, words = if (keep) l.words.take(k) else emptyList(), translations = emptyMap())
        val shift = Math.round((t - l.t0) * 100).toInt()
        lines[e.n] = l.copy(i = e.n, t0 = t, text = tail, speaker = e.speaker?.let(::resolve) ?: l.speaker,
            words = if (keep) l.words.drop(k).map { it - shift } else emptyList(), translations = emptyMap())
    }

    fun result(): Transcript = Transcript(
        lines = lines.values.sortedWith(compareBy<TranscriptLine> { it.t0 }.thenBy { it.i }),
        gaps = gaps.sortedBy { it.t0 },
        names = names.toMap(),
        merges = merges.toMap(),
        me = me,
        records = records,
        skipped = skipped,
        torn = torn,
        formerNames = former.mapValues { it.value.toList() },
    )
}

object TranscriptCodec {
    const val VERSION = 1

    /** One event as one line of the log (no line break: JSON escapes those inside the text). */
    fun encode(e: TranscriptEvent): String = when (e) {
        is TranscriptEvent.Line -> JSONObject().put("e", "line").put("i", e.i).put("t0", r2(e.t0)).put("t1", r2(e.t1)).put("spk", e.speaker).apply {
            e.lang?.let { put("lang", it) }
            put("lid", r2(e.lid))
            if (e.prior) put("lp", true)
            put("text", e.text)
            if (e.words.isNotEmpty()) put("ws", JSONArray(e.words))
            put("reheard", e.reheard)
        }
        is TranscriptEvent.Translation -> JSONObject().put("e", "tr").put("i", e.i).put("to", e.to).put("by", e.by).put("text", e.text)
        is TranscriptEvent.Edit -> JSONObject().put("e", "edit").put("i", e.i).put("text", e.text)
        is TranscriptEvent.Speaker -> JSONObject().put("e", "spk").put("i", e.i).put("spk", e.speaker)
        is TranscriptEvent.Lang -> JSONObject().put("e", "lang").put("i", e.i).put("lang", e.lang)
        is TranscriptEvent.Rename -> JSONObject().put("e", "rename").put("spk", e.speaker).put("name", e.name)
        is TranscriptEvent.Merge -> JSONObject().put("e", "merge").put("from", e.from).put("to", e.to)
        is TranscriptEvent.Me -> JSONObject().put("e", "me").put("spk", e.speaker)
        is TranscriptEvent.Split -> JSONObject().put("e", "split").put("i", e.i).put("at", e.at).put("n", e.n).apply { e.speaker?.let { put("spk", it) } }
        is TranscriptEvent.Delete -> JSONObject().put("e", "del").put("i", e.i)
        is TranscriptEvent.Gap -> JSONObject().put("e", "gap").put("t0", r2(e.t0)).put("t1", r2(e.t1)).put("why", e.why)
        is TranscriptEvent.Unknown -> return e.raw
    }.toString()

    private fun r2(d: Double): Double = if (d.isFinite()) Math.round(d * 100) / 100.0 else 0.0

    /** One log line: the event, [TranscriptEvent.Unknown] for a kind this version doesn't know, null for a damaged line. */
    fun decode(line: String): TranscriptEvent? = try {
        val o = JSONObject(line)
        when (o.getString("e")) {
            "line" -> TranscriptEvent.Line(o.getInt("i"), o.getDouble("t0"), o.getDouble("t1"), o.getString("spk"),
                o.optString("lang").takeIf { o.has("lang") && it.isNotEmpty() }, o.getString("text"), o.optDouble("lid", 0.0).takeIf { it.isFinite() } ?: 0.0,
                o.optBoolean("lp", false), o.optJSONArray("ws")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(), o.optBoolean("reheard", false))
            "tr" -> TranscriptEvent.Translation(o.getInt("i"), o.getString("to"), o.getString("by"), o.getString("text"))
            "edit" -> TranscriptEvent.Edit(o.getInt("i"), o.getString("text"))
            "spk" -> TranscriptEvent.Speaker(o.getInt("i"), o.getString("spk"))
            "lang" -> TranscriptEvent.Lang(o.getInt("i"), o.getString("lang"))
            "rename" -> TranscriptEvent.Rename(o.getString("spk"), o.optString("name"))
            "merge" -> TranscriptEvent.Merge(o.getString("from"), o.getString("to"))
            "me" -> TranscriptEvent.Me(o.getString("spk"))
            "split" -> TranscriptEvent.Split(o.getInt("i"), o.getInt("at"), o.getInt("n"), o.optString("spk").takeIf { o.has("spk") && it.isNotEmpty() })
            "del" -> TranscriptEvent.Delete(o.getInt("i"))
            "gap" -> TranscriptEvent.Gap(o.getDouble("t0"), o.getDouble("t1"), o.optString("why", TranscriptEvent.GAP_MIC))
            else -> TranscriptEvent.Unknown(line)
        }
    } catch (_: Exception) { null }

    /** The log's whole lines, and whether a cut-off last line followed them. */
    fun records(log: String): Pair<List<String>, Boolean> {
        val parts = log.split('\n')
        val torn = parts.last().isNotEmpty()
        return parts.dropLast(1).filter { it.isNotBlank() } to torn
    }

    /** Folds a whole log. */
    fun read(log: String?): Transcript = load(null, log)

    /**
     * Opens a transcript: the [snapshot] (when it is readable), then the log's records after the ones it covers. A
     * damaged snapshot is ignored and the whole log folded.
     */
    fun load(snapshot: String?, log: String?): Transcript {
        val start = fromSnapshot(snapshot)
        val (lines, torn) = records(log ?: "")
        val fold = TranscriptFold(start ?: Transcript())
        val from = (start?.records ?: 0).coerceAtMost(lines.size)
        for (l in lines.drop(from)) fold.apply(decode(l))
        if (torn) fold.torn()
        return fold.result()
    }

    /** The fold so far, for snapshot.json. */
    fun snapshot(t: Transcript): String = JSONObject().apply {
        put("v", VERSION)
        put("records", t.records)
        put("skipped", t.skipped)
        put("lines", JSONArray().apply {
            for (l in t.lines) put(JSONObject(encode(TranscriptEvent.Line(l.i, l.t0, l.t1, l.speaker, l.lang, l.text, l.lid, l.prior, l.words, l.reheard))).apply {
                remove("e")
                if (l.edited) put("edited", true)
                if (l.translations.isNotEmpty()) put("tr", JSONArray().apply {
                    l.translations.values.forEach { put(JSONObject().put("to", it.to).put("by", it.by).put("text", it.text)) }
                })
            })
        })
        put("gaps", JSONArray().apply { t.gaps.forEach { put(JSONObject().put("t0", r2(it.t0)).put("t1", r2(it.t1)).put("why", it.why)) } })
        put("names", JSONObject(t.names))
        put("merges", JSONObject(t.merges))
        t.me?.let { put("me", it) }
        put("former", JSONObject().apply { t.formerNames.forEach { (k, v) -> put(k, JSONArray(v)) } })
    }.toString()

    fun fromSnapshot(text: String?): Transcript? = try {
        val o = JSONObject(text ?: return null)
        if (o.optInt("v", 0) != VERSION) return null
        // A snapshot from before earlier names were kept: the whole log is folded instead, so they're found.
        val fs = o.optJSONObject("former") ?: return null
        val ls = o.getJSONArray("lines")
        val gs = o.optJSONArray("gaps") ?: JSONArray()
        val ns = o.optJSONObject("names") ?: JSONObject()
        val ms = o.optJSONObject("merges") ?: JSONObject()
        Transcript(
            lines = (0 until ls.length()).map { k ->
                val x = ls.getJSONObject(k)
                val l = decode(JSONObject(x.toString()).put("e", "line").toString()) as TranscriptEvent.Line
                val tr = x.optJSONArray("tr") ?: JSONArray()
                TranscriptLine(l.i, l.t0, l.t1, l.speaker, l.lang, l.text, l.lid, l.prior, l.words, l.reheard, x.optBoolean("edited", false),
                    (0 until tr.length()).associate { j -> tr.getJSONObject(j).let { it.getString("to") to LineTranslation(it.getString("to"), it.getString("by"), it.getString("text")) } })
            },
            gaps = (0 until gs.length()).map { k -> gs.getJSONObject(k).let { TranscriptGap(it.getDouble("t0"), it.getDouble("t1"), it.optString("why")) } },
            names = ns.keys().asSequence().associateWith { ns.getString(it) },
            merges = ms.keys().asSequence().associateWith { ms.getString(it) },
            me = o.optString("me").takeIf { o.has("me") && it.isNotEmpty() },
            records = o.getInt("records"),
            skipped = o.optInt("skipped"),
            formerNames = fs.keys().asSequence().associateWith { k -> fs.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } },
        )
    } catch (_: Exception) { null }
}

/** A speaker as meta.json lists them: [colour] an index into the screen's fixed palette. */
data class SpeakerInfo(val id: String, val name: String? = null, val colour: Int = 0, val me: Boolean = false)

/**
 * meta.json (written atomically). [memory]: you let Robot remember this conversation (0.7); off unless you turn it on,
 * and memory never reads a transcript with it off. [important]: you marked it, so the stronger AI is offered first.
 */
data class TranscriptMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val endedAt: Long = 0,
    val status: SessionStatus = SessionStatus.RUNNING,
    val mode: ConvMode = ConvMode.TRANSCRIBE,
    val pair: LangPair = LangPair.DEFAULT,
    val speakers: List<SpeakerInfo> = emptyList(),
    val keepAudio: Boolean = false,
    val memory: Boolean = false,
    val important: Boolean = false,
    /**
     * Speakers who sounded alike when the session ended ("S3" to "S1"), offered as merges on the transcript until you
     * merge or turn one down (phase 5.3). The voices themselves were dropped with the session.
     */
    val similar: List<Pair<String, String>> = emptyList(),
    val version: Int = VERSION,
) {
    /**
     * [t]'s speakers as meta.json lists them (in the order they first spoke, with the names you gave and who is you), and
     * the proposals that still make sense: both speakers still there, and not the same one since.
     */
    fun withSpeakers(t: Transcript): TranscriptMeta = copy(
        speakers = t.speakers.map { SpeakerInfo(it, t.names[it], SpeakerLabels.colour(it), it == t.me || it == SpeakerClusterer.ME) },
        similar = similar.filter { (a, b) -> a != b && a in t.speakers && b in t.speakers },
    )

    /** Without the proposal of [from] and [to] (merged, or "not the same person"), in either order. */
    fun without(from: String, to: String): TranscriptMeta = copy(similar = similar.filter { it != from to to && it != to to from })

    fun toJson(): String = JSONObject().apply {
        put("v", version)
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("endedAt", endedAt)
        put("status", status.name)
        put("mode", mode.name)
        put("pair", pair.id)
        put("speakers", JSONArray().apply {
            speakers.forEach { s -> put(JSONObject().put("id", s.id).put("colour", s.colour).put("me", s.me).apply { s.name?.let { put("name", it) } }) }
        })
        put("keepAudio", keepAudio)
        put("memory", memory)
        put("important", important)
        if (similar.isNotEmpty()) put("similar", JSONArray().apply { similar.forEach { (a, b) -> put(JSONArray().put(a).put(b)) } })
    }.toString()

    companion object {
        const val VERSION = 1

        fun fromJson(text: String?): TranscriptMeta? = try {
            val o = JSONObject(text ?: return null)
            val sp = o.optJSONArray("speakers") ?: JSONArray()
            TranscriptMeta(
                id = o.getString("id").also { require(TranscriptIds.valid(it)) },
                title = o.optString("title"),
                createdAt = o.getLong("createdAt"),
                endedAt = o.optLong("endedAt"),
                status = enumOr(o.optString("status"), SessionStatus.INTERRUPTED),
                mode = enumOr(o.optString("mode"), ConvMode.TRANSCRIBE),
                pair = LangPair.of(o.optString("pair")),
                speakers = (0 until sp.length()).mapNotNull { k ->
                    val x = sp.optJSONObject(k) ?: return@mapNotNull null
                    SpeakerInfo(x.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null, x.optString("name").takeIf { x.has("name") && it.isNotBlank() },
                        x.optInt("colour"), x.optBoolean("me"))
                },
                keepAudio = o.optBoolean("keepAudio"),
                // Absent or unreadable: off. Memory never reads a transcript you didn't let it.
                memory = o.optBoolean("memory", false),
                important = o.optBoolean("important"),
                similar = (o.optJSONArray("similar") ?: JSONArray()).let { a ->
                    (0 until a.length()).mapNotNull { k ->
                        val p = a.optJSONArray(k) ?: return@mapNotNull null
                        val from = p.optString(0)
                        val to = p.optString(1)
                        if (SpeakerLabels.valid(from) && SpeakerLabels.valid(to) && from != to) from to to else null
                    }
                },
                version = o.optInt("v", VERSION),
            )
        } catch (_: Exception) { null }
    }
}
