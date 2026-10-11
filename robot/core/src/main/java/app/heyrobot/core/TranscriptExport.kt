package app.heyrobot.core

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure. A transcript as a Markdown or plain text file (docs/conversation-mode.md 6.3), made on demand when you export
// or share it, and the search over transcripts (6.5): an accent- and case-insensitive scan now, the hooks 0.7 memory
// will index through. Memory only ever sees a transcript you let Robot remember.

/** [translations]: a line's translations under it. [times]: the clock time of each line. [names]: names, or S1/S2. */
data class ExportOptions(val translations: Boolean = true, val times: Boolean = true, val names: Boolean = true)

object TranscriptExport {
    const val CREDIT = "Transcribed on this phone by Robot (Parakeet v3)."
    /** Translations by Robot's own model (the one chosen in Settings › Model, 0.4.1) or the fast engine. */
    const val CREDIT_TRANSLATIONS = "Translations: Robot's model / Bergamot (marked \"fast\")."

    private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.US)

    private val LANG_NAMES = mapOf("fr" to "French", "en" to "English")

    fun langName(lang: String): String = LANG_NAMES[lang] ?: lang.uppercase(Locale.ROOT)

    /** "FR", "FR?" (from the prior), "?" (not known). */
    fun langTag(l: TranscriptLine): String = l.lang?.let { it.uppercase(Locale.ROOT) + if (l.prior) "?" else "" } ?: "?"

    fun speaker(t: Transcript, label: String, o: ExportOptions): String = if (o.names) t.nameOf(label) else label

    /** "[microphone unavailable 14:03–14:07]". */
    fun gapText(meta: TranscriptMeta, g: TranscriptGap, zone: ZoneId): String {
        val why = when (g.why) {
            TranscriptEvent.GAP_MIC -> "microphone unavailable"
            TranscriptEvent.GAP_PAUSE -> "paused"
            TranscriptEvent.GAP_CALL -> "phone call"
            TranscriptEvent.GAP_CRASH -> "stopped unexpectedly"
            TranscriptEvent.GAP_BEHIND -> "not transcribed"
            else -> "nothing recorded"
        }
        return "[$why ${at(meta, g.t0, zone, HM)}–${at(meta, g.t1, zone, HM)}]"
    }

    private fun at(meta: TranscriptMeta, seconds: Double, zone: ZoneId, f: DateTimeFormatter): String =
        f.format(Instant.ofEpochMilli(meta.createdAt + (seconds * 1000).toLong()).atZone(zone))

    /** The clock time of a moment of the session ("14:03"), for summaries and their [time] links. */
    fun clock(meta: TranscriptMeta, seconds: Double, zone: ZoneId): String = at(meta, seconds, zone, HM)

    private fun header(meta: TranscriptMeta, t: Transcript, o: ExportOptions, zone: ZoneId): List<String> {
        val start = Instant.ofEpochMilli(meta.createdAt).atZone(zone)
        val endMs = if (meta.endedAt > meta.createdAt) meta.endedAt else meta.createdAt + (t.lines.maxOfOrNull { it.t1 } ?: 0.0).times(1000).toLong()
        val minutes = ((endMs - meta.createdAt) / 60_000).coerceAtLeast(0)
        val end = Instant.ofEpochMilli(endMs).atZone(zone)
        val speakers = t.speakers.joinToString(", ") { s ->
            val shown = speaker(t, s, o)
            // "Marie (S1)", so a label in a summary or an older copy still points at someone; "You" for your enrolled voice.
            if (o.names && shown != s && s != SpeakerClusterer.ME) "$shown ($s)" else shown
        }
        val translated = o.translations && t.lines.any { it.translations.isNotEmpty() }
        return listOfNotNull(
            "Date: ${DAY.format(start)}, ${HM.format(start)}–${HM.format(end)} ($minutes min)",
            "Speakers: ${speakers.ifEmpty { "none" }}",
            "Languages: ${t.langs.joinToString(", ") { langName(it) }.ifEmpty { "none" }}",
            CREDIT + if (translated) " $CREDIT_TRANSLATIONS" else "",
        )
    }

    /** Lines and gaps in time order. */
    private fun items(t: Transcript): List<Any> = (t.lines.map { it.t0 to it } + t.gaps.map { it.t0 to it }).sortedBy { it.first }.map { it.second }

    fun markdown(meta: TranscriptMeta, t: Transcript, o: ExportOptions = ExportOptions(), zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("# ").append(md(meta.title.ifBlank { "Conversation" })).append("\n\n")
        for (h in header(meta, t, o, zone)) append("- ").append(md(h)).append('\n')
        for (x in items(t)) {
            append('\n')
            when (x) {
                is TranscriptGap -> append('*').append(md(gapText(meta, x, zone))).append("*\n")
                is TranscriptLine -> {
                    append("**")
                    if (o.times) append("[").append(at(meta, x.t0, zone, HMS)).append("] ")
                    append(md(speaker(t, x.speaker, o))).append(" (").append(langTag(x)).append("):** ").append(md(x.text))
                    if (x.edited) append(" _(edited)_")
                    append('\n')
                    if (o.translations) for (tr in x.translations.values) {
                        append("> ").append(tr.to.uppercase(Locale.ROOT)).append(": ").append(md(tr.text))
                        if (tr.fast) append(" _(fast)_")
                        append('\n')
                    }
                }
            }
        }
    }

    fun text(meta: TranscriptMeta, t: Transcript, o: ExportOptions = ExportOptions(), zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append(meta.title.ifBlank { "Conversation" }).append('\n')
        for (h in header(meta, t, o, zone)) append(h).append('\n')
        for (x in items(t)) {
            append('\n')
            when (x) {
                is TranscriptGap -> append(gapText(meta, x, zone)).append('\n')
                is TranscriptLine -> {
                    if (o.times) append("[").append(at(meta, x.t0, zone, HMS)).append("] ")
                    append(speaker(t, x.speaker, o)).append(" (").append(langTag(x)).append("): ").append(x.text)
                    if (x.edited) append(" (edited)")
                    append('\n')
                    if (o.translations) for (tr in x.translations.values) {
                        append("    ").append(tr.to.uppercase(Locale.ROOT)).append(": ").append(tr.text)
                        if (tr.fast) append(" (fast)")
                        append('\n')
                    }
                }
            }
        }
    }

    /** "Transcript 2026-10-11 1402.md". */
    fun fileName(meta: TranscriptMeta, extension: String, zone: ZoneId = ZoneId.systemDefault()): String =
        "Transcript " + FILE.format(Instant.ofEpochMilli(meta.createdAt).atZone(zone)) + "." + extension

    /**
     * Words people said, safe in Markdown: the characters that would start emphasis, a link, code or HTML are escaped,
     * and a line break inside a line becomes a space.
     */
    fun md(s: String): String = buildString {
        for (c in s.replace(Regex("\\s*\\n\\s*"), " ")) {
            if (c in "\\`*_[]<>#|") append('\\')
            append(c)
        }
    }
}

/** One place a search found: the transcript, the line's index, and the line with the match in it. */
data class TranscriptHit(val transcriptId: String, val line: Int, val snippet: String)

/** What changed among the transcripts, for 0.7 memory's index (TranscriptEvents). */
sealed class TranscriptChange {
    abstract val id: String

    data class Created(override val id: String) : TranscriptChange()

    data class Updated(override val id: String) : TranscriptChange()

    data class Deleted(override val id: String) : TranscriptChange()
}

/** Who hears of every change (0.7 memory); the store calls [publish] after each write. */
object TranscriptEvents {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(TranscriptChange) -> Unit>()

    fun listen(l: (TranscriptChange) -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    fun publish(c: TranscriptChange) {
        for (l in listeners) try { l(c) } catch (t: Throwable) { RLog.w("transcript_listener", t) }
    }
}

object TranscriptIndex {
    const val SNIPPET = 80
    const val LIMIT = 100

    /** A character as search compares it: no accents, no case, ’ as ', œ as oe. */
    private fun fold(c: Char): String = when (c) {
        'œ', 'Œ' -> "oe"
        'æ', 'Æ' -> "ae"
        'ß' -> "ss"
        '’', 'ʼ' -> "'"
        else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
    }

    /** [s] folded, and for each folded character the index of the character it came from. */
    fun fold(s: String): Pair<String, IntArray> {
        val out = StringBuilder()
        val from = ArrayList<Int>()
        for ((i, c) in s.withIndex()) for (x in fold(c)) {
            out.append(x)
            from += i
        }
        return out.toString() to from.toIntArray()
    }

    /**
     * Every line (its words and its translations) that contains [query], ignoring accents and case, newest transcript
     * first, at most [limit]. 0.5.0 scans the transcripts as they are; 0.7 can put an index behind the same call.
     */
    fun search(query: String, transcripts: List<Pair<TranscriptMeta, Transcript>>, limit: Int = LIMIT): List<TranscriptHit> {
        val q = fold(query.trim().replace(Regex("\\s+"), " ")).first
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<TranscriptHit>()
        for ((meta, t) in transcripts.sortedByDescending { it.first.createdAt }) for (l in t.lines) {
            val hit = sequenceOf(l.text).plus(l.translations.values.map { it.text }).firstNotNullOfOrNull { snippet(it, q) } ?: continue
            out += TranscriptHit(meta.id, l.i, hit)
            if (out.size >= limit) return out
        }
        return out
    }

    /** [text] around the first match of the folded [q], about [SNIPPET] characters, with "…" where it was cut. */
    fun snippet(text: String, q: String): String? {
        val (f, from) = fold(text)
        val k = f.indexOf(q)
        if (k < 0) return null
        val start = from[k]
        val end = from[k + q.length - 1] + 1
        val room = ((SNIPPET - (end - start)) / 2).coerceAtLeast(0)
        val a = (start - room).coerceAtLeast(0)
        val b = (end + room).coerceAtMost(text.length)
        return (if (a > 0) "…" else "") + text.substring(a, b).trim() + (if (b < text.length) "…" else "")
    }

    /** What 0.7 memory may read: only the transcripts you let Robot remember. */
    fun forMemory(transcripts: List<Pair<TranscriptMeta, Transcript>>): List<Pair<TranscriptMeta, Transcript>> = transcripts.filter { it.first.memory }
}
