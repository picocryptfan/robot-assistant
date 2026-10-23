// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.ZoneId

// Pure. Robot's own summary of a transcript, run step by step (docs/conversation-mode.md 7.1, phase 5.6): SummaryPlanner's
// parts, each summarized by Robot's model in a one-shot request at SUMMARY priority (it gives way to the panel, the
// interpreter and transcript translations, and starts over), then the notes merged, level by level, within the loaded
// model's context. Each part's notes are kept as they come (SummaryDraft, in the transcript's folder), so a summary
// stopped halfway goes on from the next part; then the number check (SummaryCheck) on the result.

/** The notes of a summary under way, kept so it can go on after a stop. [key]: the transcript, the language and the context they belong to. */
data class SummaryDraft(val key: String, val notes: List<String?>) {
    fun toJson(): String = JSONObject().put("key", key).put("notes", JSONArray().apply { notes.forEach { put(it ?: JSONObject.NULL) } }).toString()

    companion object {
        const val FILE = "summary-draft.json"

        /** What the notes depend on: the lines Robot reads, the summary's language and the model's context. */
        fun key(lines: List<String>, lang: String, maxTokens: Int): String {
            val d = MessageDigest.getInstance("SHA-256")
            d.update("$lang/$maxTokens\n".toByteArray(Charsets.UTF_8))
            for (l in lines) d.update((l + "\n").toByteArray(Charsets.UTF_8))
            return d.digest().take(12).joinToString("") { String.format("%02x", it) }
        }

        fun fromJson(text: String?): SummaryDraft? = try {
            val o = JSONObject(text ?: return null)
            val a = o.getJSONArray("notes")
            SummaryDraft(o.getString("key"), (0 until a.length()).map { if (a.isNull(it)) null else a.getString(it) })
        } catch (_: Exception) { null }
    }
}

/** Robot's summary, made with a [transport] to its model, each call within [budget]. */
class LocalSummary(private val transport: OneShotTransport, private val budget: OneShotBudget) {
    /** Step [step] of about [steps] ([merging]: the notes are being merged). */
    data class Progress(val step: Int, val steps: Int, val merging: Boolean)

    sealed class Outcome {
        data class Done(val text: String, val parts: Int) : Outcome()

        /**
         * It stopped at part or merge [step] ([why] in plain words, English; [whyText] for the screen); the notes made so
         * far are kept.
         */
        data class Failed(val why: String, val step: Int) : Outcome() {
            var whyText: Phrase = Phrase.plain(why)
                private set

            constructor(text: Phrase, step: Int) : this(text.en(), step) {
                whyText = text
            }
        }
    }

    /**
     * Summarizes [lines] (SummaryPlanner.lines) in [lang] for a model with a context of [maxTokens]. [cached]: notes of a
     * stopped run of the same plan (SummaryDraft), by part. [onNote] gets each part's new note (to keep it).
     */
    suspend fun run(lines: List<String>, lang: String, maxTokens: Int, cached: List<String?> = emptyList(), onProgress: (Progress) -> Unit = {},
        onNote: (Int, String) -> Unit = { _, _ -> }): Outcome {
        val plan = SummaryPlanner.plan(lines, lang, maxTokens)
        if (plan.parts == 0) return Outcome.Failed(ConversationStrings.nothingToSummarize(), 0)
        val steps = plan.parts + plan.reduceCalls
        val notes = ArrayList<String>()
        for ((k, call) in plan.map.withIndex()) {
            onProgress(Progress(k, steps, merging = false))
            val note = cached.getOrNull(k)?.takeIf { it.isNotBlank() } ?: when (val r = ask(OneShotKind.SUMMARY_MAP, call)) {
                is Step.Ok -> r.text.also { onNote(k, it) }
                is Step.No -> return Outcome.Failed(r.why, k)
            }
            val (from, to) = plan.ranges.getOrElse(k) { "" to "" }
            notes += SummaryPlanner.noteLabel(k + 1, k + 1, from, to) + "\n" + note
        }
        // Merged level by level until one summary is left (at least one merge: it gives the summary its shape).
        var level = notes.toList()
        var spans = plan.ranges.indices.map { it to it }
        var step = plan.parts
        while (true) {
            val system = Prompt.summaryReduce(lang)
            val groups = SummaryPlanner.reduceGroups(level, system, maxTokens)
            val merged = ArrayList<String>()
            val mergedSpans = ArrayList<Pair<Int, Int>>()
            for (g in groups) {
                onProgress(Progress(minOf(step, steps - 1), steps, merging = true))
                val call = SummaryPlanner.reduceCall(g.map { level[it] }, lang, maxTokens)
                val text = when (val r = ask(OneShotKind.SUMMARY_REDUCE, call)) {
                    is Step.Ok -> r.text
                    is Step.No -> return Outcome.Failed(r.why, step)
                }
                step++
                val first = spans[g.first()].first
                val last = spans[g.last()].second
                merged += if (groups.size == 1) text else SummaryPlanner.noteLabel(first + 1, last + 1, plan.ranges.getOrElse(first) { "" to "" }.first,
                    plan.ranges.getOrElse(last) { "" to "" }.second) + "\n" + text
                mergedSpans += first to last
            }
            if (groups.size == 1) {
                onProgress(Progress(steps, steps, merging = true))
                return Outcome.Done(merged.single().trim(), plan.parts)
            }
            level = merged
            spans = mergedSpans
        }
    }

    private sealed class Step {
        data class Ok(val text: String) : Step()
        data class No(val why: Phrase) : Step()
    }

    /** One step: its text, or why it failed. A step that gave way starts over inside the transport; one cut at its cap keeps what came. */
    private suspend fun ask(kind: OneShotKind, call: SummaryCall): Step =
        when (val r = transport.run(BrainMsg.OneShot(kind, Priority.SUMMARY, call.system, call.user, call.maxChunks), budget)) {
            is OneShotResult.Done -> Step.Ok(r.text.trim().ifEmpty { "- (nothing noted)" }) // l10n: not text (a note the model reads next, English)
            is OneShotResult.TimedOut -> Step.No(ConversationStrings.modelTookTooLong())
            OneShotResult.NotStarted -> Step.No(ConversationStrings.modelBusy())
            OneShotResult.Cancelled -> Step.No(ConversationStrings.modelStopped())
            is OneShotResult.Failed -> Step.No(when {
                r.unavailable -> ConversationStrings.modelNotReadyOpen("page" to UiStrings.inSettings(UiStrings.model))
                r.code == BrainError.TOO_LONG -> ConversationStrings.partTooLong()
                else -> ConversationStrings.modelProblem()
            })
        }
}

/** The number check on a summary of transcript [t] (docs/conversation-mode.md 7.1), with what it is checked against. */
object SummaryCheckFor {
    /**
     * [summary]'s misses against [t]'s lines and translations, the speakers as the summary names them (the names you gave,
     * "User", "Speaker 2"), [people] (the People list's names) and the lines' times.
     */
    fun misses(summary: String, meta: TranscriptMeta, t: Transcript, zone: ZoneId, people: Collection<String> = emptyList()): List<SummaryMiss> {
        val texts = t.lines.flatMap { listOf(it.text) + it.translations.values.map { tr -> tr.text } }
        val names = t.speakers.map { SummaryPlanner.speakerName(t, it) } + t.names.values + people
        val times = t.lines.map { TranscriptExport.clock(meta, it.t0, zone) }
        return SummaryCheck.misses(summary, texts, names, times)
    }

    /** The [hh:mm] references in [summary], each with the first line said at that minute (its index), for the links. */
    fun links(summary: String, meta: TranscriptMeta, t: Transcript, zone: ZoneId): List<Pair<IntRange, Int>> {
        val byTime = LinkedHashMap<String, Int>()
        for (l in t.lines.sortedBy { it.t0 }) byTime.putIfAbsent(TranscriptExport.clock(meta, l.t0, zone), l.i)
        return Regex("""\[(\d{1,2}):(\d{2})]""").findAll(summary).mapNotNull { m ->
            val key = m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
            byTime[key]?.let { m.range to it }
        }.toList()
    }
}

/**
 * A summary step's limits (docs/conversation-mode.md 7.1): it waits its turn; once started, about 20 s on the TPU (EST), far
 * longer on the CPU, and on 0.4.7's GPU (Fast on a Pixel 9a: about 25 to 70 s for a step by the estimates, unmeasured) the
 * CPU's limit, so a slower GPU than estimated never cuts a step short.
 */
object SummaryBudget {
    fun of(backend: ModelBackend?): OneShotBudget = OneShotBudget(null, if (TranslatePolicy.slow(backend)) 600_000 else 120_000, waitForLoad = true)
}
