package app.heyrobot.core.feedback

import app.heyrobot.core.BrainModel
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.LogCode
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.Phone
import app.heyrobot.core.update.GitHubRepo
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

// Pure (0.7). Send feedback: a bug report, an idea or anything else, written in Robot and sent by you through Android's
// share sheet as plain text (SMS, Signal, Molly, Cheogram...) to the developer, who reads it and forwards it to an AI
// coding assistant. Robot sends nothing itself: no server, no email, nothing automatic. The message starts with a fixed
// header an assistant can parse (FeedbackReport), holds your words as you wrote them, and adds only the diagnostics you
// tick, each built from codes, numbers and switches (FeedbackDiagnostics), never from your conversations, history,
// transcripts, messages, memory, people, notes, calendar, wake phrases, helpers, the updater's repository or token, or
// where you are; a last pass leaves out any of those Robot knows of that still turned up in them (FeedbackPrivacy).

/** What you send. [header] is the message's "Kind:", [section] the heading over your words, [prompt] the text field's label. */
enum class FeedbackKind(override val id: Int, override val title: String, val label: String, val header: String, val section: String, val prompt: String) : LogCode {
    BUG(1, "bug report", "Bug", "Bug report", "What happened", "What happened?"),
    IDEA(2, "idea", "Idea", "Idea", "The idea", "What would you like Robot to do?"),
    OTHER(3, "other", "Other", "Other", "Message", "What would you like to tell the developer?"),
    ;

    companion object {
        fun of(name: String?): FeedbackKind? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Where Send feedback was opened from; [key] is its name in the page's route. */
enum class FeedbackEntry(override val id: Int, override val title: String, val key: String) : LogCode {
    ABOUT(1, "Settings › About", "about"),
    MODEL_PROBLEM(2, "a model problem on the Model tab", "problem"),
    VOICE_LOG(3, "the voice log", "voicelog"),
    UPDATER(4, "an update error", "updates"),
    VOICE(5, "a voice command in the panel", "voice"),
    /** 1.0: "Robot closed unexpectedly" when you opened Robot after a crash (core AppCrash). */
    CRASH(6, "the notice that Robot closed unexpectedly", "crash"),
    ;

    companion object {
        fun of(key: String?): FeedbackEntry = entries.firstOrNull { it.key == key } ?: ABOUT
    }
}

/**
 * What Robot may add to your words, each only when you tick it, in this order. [bit]: its flag in the voice log's numbers.
 * A bug ticks the problem report (if there is one), the voice log and the settings summary for you (the updater's codes
 * too when you came from an update error); an idea or anything else ticks none.
 */
enum class Diagnostic(val bit: Int, val title: String, val detail: String) {
    PROBLEM(1, "Problem report", "The model problem's report: codes, this phone and its Android build, the model, how its process ended"),
    VOICE_LOG(2, "Voice log", "The last ${FeedbackDiagnostics.LOG_EVENTS} events, codes and numbers only"),
    SETTINGS(4, "Settings summary", "Switches and levels only: no names, addresses, phrases, server addresses or keys"),
    UPDATER(8, "Updater", "The updater's last check, error and install, as codes"),
    ;

    companion object {
        fun bits(set: Set<Diagnostic>): Int = set.sumOf { it.bit }

        fun of(bits: Int): Set<Diagnostic> = entries.filter { bits and it.bit != 0 }.toSet()

        /** "the problem report, the voice log", "no diagnostics". */
        fun words(bits: Int): String = of(bits).joinToString(", ") { "the " + it.title.lowercase(Locale.ROOT) }.ifEmpty { "no diagnostics" }

        fun defaults(kind: FeedbackKind, entry: FeedbackEntry): Set<Diagnostic> = when (kind) {
            FeedbackKind.BUG -> setOf(PROBLEM, VOICE_LOG, SETTINGS) + (if (entry == FeedbackEntry.UPDATER) setOf(UPDATER) else emptySet())
            FeedbackKind.IDEA, FeedbackKind.OTHER -> emptySet()
        }
    }
}

/** Your report as you write it: the kind, your words, for a bug the steps and what you expected, and what you ticked. */
data class FeedbackDraft(
    val kind: FeedbackKind,
    val text: String,
    val steps: String = "",
    val expected: String = "",
    val ticked: Set<Diagnostic> = emptySet(),
)

/**
 * The header's facts besides the kind and the reference: Robot's version and build, this phone, its chip and Android build
 * with whether it runs GrapheneOS, the model and where it runs, the look and the language. Every line is made by the
 * builders below from Android's fixed strings and Robot's own codes ([line] strips what could break the header).
 */
data class FeedbackFacts(
    val robot: String,
    val phone: String,
    val model: String,
    val look: String,
    val language: String,
) {
    companion object {
        /** "0.6.0 (60) · release build": a pre-release version is a test build, a debug build says so. */
        fun robot(version: String, code: Int, debug: Boolean): String {
            val v = version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(32).ifEmpty { "unknown" }
            val kind = when {
                debug -> "debug build"
                '-' in v -> "test build"
                else -> "release build"
            }
            return "$v ($code) · $kind"
        }

        /**
         * GrapheneOS numbers its builds by the date and hour they were made (BUILD_NUMBER, YYYYMMDDHH: Build.VERSION.INCREMENTAL
         * "2026100201"); the Pixel's own Android uses a plain counter ("13470043").
         */
        fun os(incremental: String): String = if (Regex("""20\d{8}""").matches(incremental.trim())) "GrapheneOS" else "not GrapheneOS (by its build number)"

        /** "Pixel 9a · Tensor G4 · Android 16 (BP2A.250805.005) · GrapheneOS". */
        fun phone(p: Phone, androidRelease: String, buildId: String, incremental: String): String =
            listOf(p.name, p.chip.title.ifEmpty { "not a Tensor" }, "Android ${line(androidRelease, 16)} (${line(buildId, 40)})", os(incremental))
                .joinToString(" · ") { line(it, 80) }

        /**
         * "Fast · Gemma 4 E2B · GPU", "Fast · Gemma 4 E2B · GPU (fell back to CPU on 2026-10-11)", "Your own model file (.gguf) ·
         * CPU" (never its name), and when the model you chose isn't ready, what Robot uses meanwhile.
         */
        fun model(chosen: BrainModel, backend: ModelBackend, gpuFailure: GpuFailure?, ownFormat: ModelFormat?, using: BrainModel? = null,
            zone: ZoneId = ZoneId.systemDefault()): String {
            val name = if (chosen == BrainModel.OWN) "Your own model file" + (ownFormat?.let { " (.${it.id})" } ?: "") else "${chosen.title} · ${chosen.modelName}"
            val where = if (gpuFailure != null && gpuFailure.model == chosen) "GPU (fell back to CPU on ${day(gpuFailure.time, zone)})" else backend.title
            val meanwhile = if (using != null && using != chosen) " · not ready: Robot uses ${using.title} · ${using.modelName}" else ""
            return "$name · $where$meanwhile"
        }

        fun look(classic: Boolean): String = if (classic) "classic" else "stock"

        /** A language tag ("en-CA"), or "unknown". */
        fun language(tag: String): String = tag.filter { it.isLetterOrDigit() || it == '-' }.take(24).ifEmpty { "unknown" }

        fun day(t: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).format(Instant.ofEpochMilli(t).atZone(zone))

        /** One line of the header: no line breaks or control characters, at most [max] characters. */
        fun line(s: String, max: Int): String = s.filter { !it.isISOControl() }.trim().take(max)
    }
}

/**
 * A diagnostic's lines as Robot would add them. [cuttable]: lines from the end may be left out to keep the message short
 * ([FeedbackReport]), each with [cutNote] saying how many.
 */
data class DiagnosticBlock(val diagnostic: Diagnostic, val title: String, val lines: List<String>) {
    fun cutNote(n: Int): String = when (diagnostic) {
        Diagnostic.VOICE_LOG -> "(the $n older event${if (n == 1) "" else "s"} left out to keep the message short)"
        else -> "($n more line${if (n == 1) "" else "s"} left out to keep the message short)"
    }
}

/** The report's reference: "RFB-" and 8 base32 letters and digits, random for each report (the same while you send it twice). */
object FeedbackRef {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val PATTERN = Regex("""RFB-[A-Z2-7]{8}""")

    fun new(random: Random = SecureRandom()): String = "RFB-" + (1..8).map { ALPHABET[random.nextInt(32)] }.joinToString("")

    fun valid(s: String?): Boolean = s != null && PATTERN.matches(s)
}

/**
 * The message: the fixed header, your words, for a bug the steps and what you expected, the diagnostics you ticked, and the
 * line that says what to do with it. Under [MAX_CHARS] when it can be: the voice log is cut first (its oldest events), then
 * the problem report, then the settings summary and the updater's lines; never your words. For a GitHub issue's link
 * ([GITHUB_MAX], measured URL-encoded) your words are cut last, saying so.
 */
object FeedbackReport {
    const val HEADER = "ROBOT FEEDBACK v1"
    const val MAX_CHARS = 12_000
    const val GITHUB_MAX = 7_000
    const val SEPARATOR = "---"
    const val FOOTER = "Forward this whole message to the developer's assistant as it is."
    const val TEXT_CUT = "… (cut to fit the link: Copy has the whole report)"

    /** The message, each ticked diagnostic's part of it exactly as it is there, what was cut, and whether your words were. */
    data class Composed(val text: String, val blocks: Map<Diagnostic, String>, val cut: Set<Diagnostic>, val textCut: Boolean)

    /** The order diagnostics are cut in. */
    private val CUT_ORDER = listOf(Diagnostic.VOICE_LOG, Diagnostic.PROBLEM, Diagnostic.SETTINGS, Diagnostic.UPDATER)

    /**
     * [blocks]: every diagnostic Robot could add (only the ticked ones go in, in [Diagnostic] order), their lines passed through
     * [FeedbackPrivacy.redact] with [secrets]. [measure]: the length that must stay under [limit] (the text's own, or its
     * URL-encoded length for a link). [cutText]: your words may be cut as a last resort (a link only).
     */
    fun compose(
        draft: FeedbackDraft,
        ref: String,
        facts: FeedbackFacts,
        blocks: List<DiagnosticBlock>,
        secrets: Collection<String> = emptyList(),
        limit: Int = MAX_CHARS,
        measure: (String) -> Int = { it.length },
        cutText: Boolean = false,
    ): Composed {
        val ticked = Diagnostic.entries.mapNotNull { d -> if (d in draft.ticked) blocks.firstOrNull { it.diagnostic == d } else null }
            .map { b -> b.copy(lines = b.lines.map { FeedbackPrivacy.redact(it, secrets) }) }
        val keep = ticked.associate { it.diagnostic to it.lines.size }.toMutableMap()
        var text = draft.text
        fun build(): Pair<String, Map<Diagnostic, String>> {
            val parts = ticked.associate { b -> b.diagnostic to block(b, keep.getValue(b.diagnostic)) }
            return render(draft, text, ref, facts, parts) to parts
        }
        var (out, parts) = build()
        for (d in CUT_ORDER) {
            if (measure(out) <= limit) break
            val b = ticked.firstOrNull { it.diagnostic == d } ?: continue
            // Halve what is left out until it fits, then give back what fits again: a few builds, not one per line.
            var lo = 0
            var hi = b.lines.size
            keep[d] = 0
            if (measure(build().first) > limit) { val r = build(); out = r.first; parts = r.second; continue }
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                keep[d] = mid
                if (measure(build().first) <= limit) lo = mid else hi = mid - 1
            }
            keep[d] = lo
            val r = build(); out = r.first; parts = r.second
        }
        var textCut = false
        if (cutText && measure(out) > limit && draft.text.isNotEmpty()) {
            var lo = 0
            var hi = draft.text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                text = draft.text.take(mid).trimEnd() + TEXT_CUT
                if (measure(build().first) <= limit) lo = mid else hi = mid - 1
            }
            text = draft.text.take(lo).trimEnd() + TEXT_CUT
            textCut = true
            val r = build(); out = r.first; parts = r.second
        }
        val cut = ticked.filter { keep.getValue(it.diagnostic) < it.lines.size }.map { it.diagnostic }.toSet()
        return Composed(out, parts, cut, textCut)
    }

    /** A diagnostic as the message holds it: a bullet with its title, then its first [keep] lines and what was left out. */
    private fun block(b: DiagnosticBlock, keep: Int): String = buildString {
        append("• ").append(b.title).append('\n')
        for (l in b.lines.take(keep)) append(l).append('\n')
        if (keep < b.lines.size) append(b.cutNote(b.lines.size - keep)).append('\n')
    }

    private fun clean(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun render(draft: FeedbackDraft, text: String, ref: String, facts: FeedbackFacts, parts: Map<Diagnostic, String>): String = buildString {
        append(HEADER).append('\n')
        append("Kind: ").append(draft.kind.header).append('\n')
        append("Ref: ").append(ref).append('\n')
        append("Robot: ").append(FeedbackFacts.line(facts.robot, 120)).append('\n')
        append("Phone: ").append(FeedbackFacts.line(facts.phone, 200)).append('\n')
        append("Model: ").append(FeedbackFacts.line(facts.model, 200)).append('\n')
        append("Look: ").append(FeedbackFacts.line(facts.look, 20)).append(" · Language: ").append(FeedbackFacts.line(facts.language, 24)).append('\n')
        append(SEPARATOR).append('\n')
        append(draft.kind.section).append(":\n")
        append(clean(text).ifEmpty { "(nothing written)" }).append('\n')
        val steps = clean(draft.steps)
        val expected = clean(draft.expected)
        if (draft.kind == FeedbackKind.BUG && (steps.isNotEmpty() || expected.isNotEmpty())) {
            append(SEPARATOR).append('\n')
            append("Steps:\n").append(steps.ifEmpty { "(none written)" }).append('\n')
            append("Expected:\n").append(expected.ifEmpty { "(none written)" }).append('\n')
        }
        append(SEPARATOR).append('\n')
        if (parts.isEmpty()) append("Diagnostics: none ticked by the tester\n")
        else {
            append("Diagnostics (ticked by the tester):\n")
            for (d in Diagnostic.entries) parts[d]?.let { append(it) }
        }
        append(SEPARATOR).append('\n')
        append(FOOTER).append('\n')
    }

    /** "Open a GitHub issue": the link, how long its text is, and whether anything was cut to fit it. */
    data class IssueLink(val url: String, val chars: Int, val cut: Boolean)

    /**
     * The new-issue link for [repo]: the same message as the body, kept to [GITHUB_MAX] characters once URL-encoded (the
     * diagnostics cut first, then your words, saying so), and the kind with the start of your words as the title.
     */
    fun issue(repo: GitHubRepo, draft: FeedbackDraft, ref: String, facts: FeedbackFacts, blocks: List<DiagnosticBlock>,
        secrets: Collection<String> = emptyList()): IssueLink {
        val c = compose(draft, ref, facts, blocks, secrets, GITHUB_MAX, { GitHubRepo.encode(it).length }, cutText = true)
        return IssueLink(repo.newIssueUrl(issueTitle(draft, ref), c.text), c.text.length, c.cut.isNotEmpty() || c.textCut)
    }

    /** A GitHub issue's title: the kind and the start of your first line (the whole report is the body). */
    fun issueTitle(draft: FeedbackDraft, ref: String): String {
        val first = clean(draft.text).lineSequence().firstOrNull().orEmpty().trim()
        val short = if (first.length > 72) first.take(71).trimEnd() + "…" else first
        return draft.kind.header + ": " + short.ifEmpty { ref }
    }
}
