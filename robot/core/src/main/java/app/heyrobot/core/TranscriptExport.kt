// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure. A transcript as a Markdown or plain text file (docs/conversation-mode.md 6.3), made on demand when you export
// or share it, and the search over transcripts (6.5): an accent- and case-insensitive scan now, the hooks 0.7 memory
// will index through. Memory only ever sees a transcript you let Robot remember. 1.1: an export is written in the menus'
// language when you make it ([Langs], core ConversationStrings); the lines' times stay "14:03" in both, as the screens show
// them, so a summary's [14:03] still points at its line.

/** [translations]: a line's translations under it. [times]: the clock time of each line. [names]: names, or S1/S2. */
data class ExportOptions(val translations: Boolean = true, val times: Boolean = true, val names: Boolean = true)

/**
 * A transcript's dates in a language (1.1): "11 October 2026" / « 11 octobre 2026 », "11 Oct" / « 11 octobre », "14:02" /
 * « 14 h 02 ». English is 1.0's, to the character; French takes core Fmt's month names and times.
 */
object TranscriptDates {
    private val DAY_EN = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US)
    private val DAY_MONTH_EN = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun dayMonthYear(z: ZonedDateTime, lang: Lang): String = if (lang == Lang.FR) dayMonth(z, lang) + " " + z.year else DAY_EN.format(z)

    /** « 11 octobre », « 1er octobre » (Fmt.day without its weekday). */
    fun dayMonth(z: ZonedDateTime, lang: Lang): String =
        if (lang == Lang.FR) Fmt.day(z.toLocalDate(), Lang.FR).substringAfter(' ') else DAY_MONTH_EN.format(z)

    fun time(z: ZonedDateTime, lang: Lang): String = if (lang == Lang.FR) Fmt.clock(z.toLocalTime(), Lang.FR) else HM.format(z)
}

object TranscriptExport {
    /** "Transcribed on this phone by Robot (Parakeet v3).", and [CREDIT] its English. */
    val credit: Phrase get() = ConversationStrings.credit()
    val CREDIT: String get() = credit.en()
    /** Translations by Robot's own model (the one chosen in Settings › Model, 0.4.1) or the fast engine. */
    val creditTranslations: Phrase get() = ConversationStrings.creditTranslations()
    val CREDIT_TRANSLATIONS: String get() = creditTranslations.en()

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.US)

    /** "French", "English" (« français », « anglais »); another language's code as it is. */
    fun langNameText(lang: String): Phrase = when (lang) {
        "fr" -> ConversationStrings.langFr()
        "en" -> ConversationStrings.langEn()
        else -> Phrase.plain(lang.uppercase(Locale.ROOT))
    }

    /** [langNameText] in English. */
    fun langName(lang: String): String = langNameText(lang).en()

    /** "FR", "FR?" (from the prior), "?" (not known). */
    fun langTag(l: TranscriptLine): String = l.lang?.let { it.uppercase(Locale.ROOT) + if (l.prior) "?" else "" } ?: "?"

    fun speakerText(t: Transcript, label: String, o: ExportOptions): Phrase = if (o.names) t.nameText(label) else Phrase.plain(label)

    /** [speakerText] in English. */
    fun speaker(t: Transcript, label: String, o: ExportOptions): String = speakerText(t, label, o).en()

    /** "[microphone unavailable 14:03–14:07]" / « [micro indisponible 14:03–14:07] ». */
    fun gapPhrase(meta: TranscriptMeta, g: TranscriptGap, zone: ZoneId): Phrase {
        val why = when (g.why) {
            TranscriptEvent.GAP_MIC -> ConversationStrings.gapMic
            TranscriptEvent.GAP_PAUSE -> ConversationStrings.gapPause
            TranscriptEvent.GAP_CALL -> ConversationStrings.gapCall
            TranscriptEvent.GAP_CRASH -> ConversationStrings.gapCrash
            TranscriptEvent.GAP_BEHIND -> ConversationStrings.gapBehind
            else -> ConversationStrings.gapOther
        }
        return ConversationStrings.gap("why" to why(), "from" to at(meta, g.t0, zone, HM), "to" to at(meta, g.t1, zone, HM))
    }

    /** [gapPhrase] in English. */
    fun gapText(meta: TranscriptMeta, g: TranscriptGap, zone: ZoneId): String = gapPhrase(meta, g, zone).en()

    private fun at(meta: TranscriptMeta, seconds: Double, zone: ZoneId, f: DateTimeFormatter): String =
        f.format(Instant.ofEpochMilli(meta.createdAt + (seconds * 1000).toLong()).atZone(zone))

    /** The clock time of a moment of the session ("14:03"), for summaries and their [time] links. */
    fun clock(meta: TranscriptMeta, seconds: Double, zone: ZoneId): String = at(meta, seconds, zone, HM)

    private fun header(meta: TranscriptMeta, t: Transcript, o: ExportOptions, zone: ZoneId, l: Langs): List<String> {
        val start = Instant.ofEpochMilli(meta.createdAt).atZone(zone)
        val endMs = if (meta.endedAt > meta.createdAt) meta.endedAt else meta.createdAt + (t.lines.maxOfOrNull { it.t1 } ?: 0.0).times(1000).toLong()
        val minutes = ((endMs - meta.createdAt) / 60_000).coerceAtLeast(0)
        val end = Instant.ofEpochMilli(endMs).atZone(zone)
        val speakers = t.speakers.joinToString(", ") { s ->
            val shown = speakerText(t, s, o).render(l)
            // "Marie (S1)", so a label in a summary or an older copy still points at someone; "You" for your enrolled voice.
            if (o.names && shown != s && s != SpeakerClusterer.ME) "$shown ($s)" else shown
        }
        val langs = t.langs.joinToString(", ") { langNameText(it).render(l) }
        val translated = o.translations && t.lines.any { it.translations.isNotEmpty() }
        return listOf(
            ConversationStrings.headerDate("day" to TranscriptDates.dayMonthYear(start, l.say), "from" to HM.format(start), "to" to HM.format(end),
                "min" to minutes.toString()),
            ConversationStrings.headerSpeakers("list" to speakers.ifEmpty { ConversationStrings.noSpeakers().render(l) }),
            ConversationStrings.headerLanguages("list" to langs.ifEmpty { ConversationStrings.noLanguages().render(l) }),
            if (translated) Phrase.join(credit, creditTranslations) else credit,
        ).map { it.render(l) }
    }

    /** Lines and gaps in time order. */
    private fun items(t: Transcript): List<Any> = (t.lines.map { it.t0 to it } + t.gaps.map { it.t0 to it }).sortedBy { it.first }.map { it.second }

    /** The export as Markdown, its words in [l] (the menus' language when you export it; English by default). */
    fun markdown(meta: TranscriptMeta, t: Transcript, o: ExportOptions = ExportOptions(), zone: ZoneId = ZoneId.systemDefault(), l: Langs = Langs.EN): String =
        buildString {
            val edited = ConversationStrings.edited().render(l)
            val fast = ConversationStrings.fast().render(l)
            append("# ").append(md(meta.title.ifBlank { ConversationStrings.titleDefault().render(l) })).append("\n\n")
            for (h in header(meta, t, o, zone, l)) append("- ").append(md(h)).append('\n')
            for (x in items(t)) {
                append('\n')
                when (x) {
                    is TranscriptGap -> append('*').append(md(gapPhrase(meta, x, zone).render(l))).append("*\n")
                    is TranscriptLine -> {
                        append("**")
                        if (o.times) append("[").append(at(meta, x.t0, zone, HMS)).append("] ")
                        append(md(speakerText(t, x.speaker, o).render(l))).append(" (").append(langTag(x)).append("):** ").append(md(x.text))
                        if (x.edited) append(" _(").append(edited).append(")_")
                        append('\n')
                        if (o.translations) for (tr in x.translations.values) {
                            append("> ").append(tr.to.uppercase(Locale.ROOT)).append(": ").append(md(tr.text))
                            if (tr.fast) append(" _(").append(fast).append(")_")
                            append('\n')
                        }
                    }
                }
            }
        }

    /** The export as plain text, its words in [l]. */
    fun text(meta: TranscriptMeta, t: Transcript, o: ExportOptions = ExportOptions(), zone: ZoneId = ZoneId.systemDefault(), l: Langs = Langs.EN): String =
        buildString {
            val edited = ConversationStrings.edited().render(l)
            val fast = ConversationStrings.fast().render(l)
            append(meta.title.ifBlank { ConversationStrings.titleDefault().render(l) }).append('\n')
            for (h in header(meta, t, o, zone, l)) append(h).append('\n')
            for (x in items(t)) {
                append('\n')
                when (x) {
                    is TranscriptGap -> append(gapPhrase(meta, x, zone).render(l)).append('\n')
                    is TranscriptLine -> {
                        if (o.times) append("[").append(at(meta, x.t0, zone, HMS)).append("] ")
                        append(speakerText(t, x.speaker, o).render(l)).append(" (").append(langTag(x)).append("): ").append(x.text)
                        if (x.edited) append(" (").append(edited).append(")")
                        append('\n')
                        if (o.translations) for (tr in x.translations.values) {
                            append("    ").append(tr.to.uppercase(Locale.ROOT)).append(": ").append(tr.text)
                            if (tr.fast) append(" (").append(fast).append(")")
                            append('\n')
                        }
                    }
                }
            }
        }

    /** "Transcript 2026-10-11 1402.md" / « Transcription 2026-10-11 1402.md ». */
    fun fileName(meta: TranscriptMeta, extension: String, zone: ZoneId = ZoneId.systemDefault(), l: Langs = Langs.EN): String {
        val name = ConversationStrings.fileName("stamp" to FILE.format(Instant.ofEpochMilli(meta.createdAt).atZone(zone))).render(l)
        return "$name.$extension"
    }

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
