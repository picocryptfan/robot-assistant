package app.heyrobot.core

import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperPrice
import app.heyrobot.core.helpers.HelperUsage
import app.heyrobot.core.helpers.Pricing
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

// Pure. Summaries of a conversation (docs/conversation-mode.md 7). Robot's own: Gemma reads the transcript in parts of
// about 2,400 estimated tokens (8 to 10 minutes of talk), each part gives at most 12 notes, and the notes are merged,
// in levels when there are more than 12 parts; every call fits the loaded model's window by TokenEstimate (4,096 tokens,
// or a short-context file's own since 0.4.3: its parts are smaller, and its reply room a quarter of its context). A
// deterministic check then flags numbers, names and [times] in the summary that the transcript doesn't have. For a long
// or important conversation Robot offers a stronger AI first, through the Helper interface: a helper's API or its app
// (the Claude API and the Claude app in 0.5.0, any helper since 0.6.0). A transcript goes to a helper only from a
// consent card listing exactly what it sends, after a tap on Send: a SummaryJob can't be made any other way.

/** One call to Gemma: its system prompt, the user turn, and the reply's cap in streamed chunks (about tokens). */
data class SummaryCall(val system: String, val user: String, val maxChunks: Int)

/**
 * Robot's plan for one summary: the map calls, and how many reduce levels follow (for the progress bar); [ranges] each
 * part's first and last line's time ("14:20" to "14:31").
 */
data class SummaryPlan(val map: List<SummaryCall>, val levels: Int, val ranges: List<Pair<String, String>> = emptyList()) {
    val parts: Int get() = map.size

    /** Merge calls to expect, about (FAN_IN notes per call, level by level): for the progress bar. */
    val reduceCalls: Int get() {
        var n = parts
        var calls = 0
        while (n > 0) {
            val c = (n + SummaryPlanner.FAN_IN - 1) / SummaryPlanner.FAN_IN
            calls += c
            if (c <= 1) break
            n = c
        }
        return calls
    }
}

object SummaryPlanner {
    /** A map part's lines, in estimated tokens (about 1,800 real ones). */
    const val CHUNK_TOKENS = 2_400
    /** The chat template's markers around the system prompt and the turn. */
    const val TEMPLATE_TOKENS = 40
    /** A map reply's cap ("at most 12 short bullet points"). */
    const val NOTE_TOKENS = 250
    /** A reduce reply's cap ("at most 250 words"). */
    const val REDUCE_TOKENS = 500
    /** Notes merged by one reduce call at most. */
    const val FAN_IN = 12

    /**
     * The estimated tokens one call's user turn may use next to [system] and the reply's room, in a context of
     * [maxTokens] (the loaded model's: 4,096, or a short-context file's own, 0.4.3).
     */
    fun budget(system: String, maxTokens: Int = ContextWindow.MAX_TOKENS): Int =
        maxTokens - ContextWindow.replyReserve(maxTokens) - TokenEstimate.text(system) - TEMPLATE_TOKENS - TokenEstimate.TURN_OVERHEAD

    /** True when [call] fits a window of [maxTokens] with the reply's room, by the estimate. */
    fun fits(call: SummaryCall, maxTokens: Int = ContextWindow.MAX_TOKENS): Boolean {
        val reserve = ContextWindow.replyReserve(maxTokens)
        return TokenEstimate.text(call.system) + TEMPLATE_TOKENS + TokenEstimate.turn(PromptTurn(PromptRole.USER, call.user)) + reserve <= maxTokens &&
            call.maxChunks <= reserve
    }

    /** "[14:03] Marie (fr): text", the line format Gemma reads. */
    fun line(time: String, speaker: String, lang: String?, text: String): String =
        "[$time] $speaker (${lang ?: "?"}): " + text.replace(Regex("\\s+"), " ").trim()

    /**
     * A speaker as Gemma reads them: the name you gave, else "User" for you, "Speaker 2" for S2, "Unknown speaker" for
     * "?" (the prompt says the transcript was recorded on the user's phone).
     */
    fun speakerName(t: Transcript, label: String): String = t.names[label] ?: when {
        label == t.me || label == SpeakerClusterer.ME -> "User"
        label == SpeakerClusterer.UNKNOWN -> "Unknown speaker"
        Regex("S(\\d+)\\D*").matches(label) -> "Speaker " + Regex("\\d+").find(label)!!.value
        else -> label
    }

    /** A transcript's lines as Gemma reads them. Translations are left out: Gemma reads both languages. */
    fun lines(meta: TranscriptMeta, t: Transcript, zone: ZoneId): List<String> =
        t.lines.map { l -> line(TranscriptExport.clock(meta, l.t0, zone), speakerName(t, l.speaker), l.lang, l.text) }

    /**
     * [lines] cut into parts of at most [CHUNK_TOKENS] estimated tokens (less if [system] leaves less room), only
     * between lines. A line too long for a part on its own (a long edited line) is cut between words, each piece
     * keeping the line's "[time] speaker (lang):" so nothing is lost.
     */
    fun chunks(lines: List<String>, system: String, maxTokens: Int = ContextWindow.MAX_TOKENS): List<List<String>> {
        val room = minOf(CHUNK_TOKENS, budget(system, maxTokens) - HEADER_TOKENS)
        val out = ArrayList<MutableList<String>>()
        var used = 0
        for (l in lines.flatMap { pieces(it, room) }) {
            val c = TokenEstimate.text(l + "\n")
            if (out.isEmpty() || used + c > room) {
                out += mutableListOf<String>()
                used = 0
            }
            out.last() += l
            used += c
        }
        return out
    }

    /** Room for "Part 12 of 20 (14:20–14:31):\n" and the like. */
    private const val HEADER_TOKENS = 40

    private fun pieces(line: String, room: Int): List<String> {
        if (TokenEstimate.text(line + "\n") <= room) return listOf(line)
        val head = line.substringBefore("): ", "").let { if (it.isEmpty()) "" else "$it): " }
        val words = line.removePrefix(head).split(' ')
        val out = ArrayList<String>()
        var cur = StringBuilder(head)
        for (w in words) {
            val next = if (cur.length > head.length) "$cur $w" else "$cur$w"
            if (TokenEstimate.text(next + "\n") > room && cur.length > head.length) {
                out += cur.toString()
                cur = StringBuilder(head).append(w)
            } else cur = StringBuilder(next)
        }
        if (cur.length > head.length) out += cur.toString()
        return out
    }

    /** "Part 3 of 7 (14:20–14:31):\n<lines>". */
    fun partText(part: Int, of: Int, from: String, to: String, lines: List<String>): String = "Part $part of $of ($from–$to):\n" + lines.joinToString("\n")

    /**
     * The map calls for [lines] (as [SummaryPlanner.lines] writes them) in Robot's summary language [lang], for a model
     * with a context of [maxTokens]; each part says which minutes it covers.
     */
    fun plan(lines: List<String>, lang: String, maxTokens: Int = ContextWindow.MAX_TOKENS): SummaryPlan {
        val system = Prompt.summaryMap(lang)
        val parts = chunks(lines, system, maxTokens)
        val calls = parts.mapIndexed { k, p -> SummaryCall(system, partText(k + 1, parts.size, timeOf(p.first()), timeOf(p.last()), p), NOTE_TOKENS) }
        return SummaryPlan(calls, levels(calls.size), parts.map { timeOf(it.first()) to timeOf(it.last()) })
    }

    private val TIME = Regex("""^\[(\d{1,2}:\d{2})]""")

    /** The "14:03" a line starts with. */
    fun timeOf(line: String): String = TIME.find(line)?.groupValues?.get(1) ?: ""

    /** Reduce levels after [parts] map calls: one merge for up to [FAN_IN] notes, another level for each [FAN_IN] times more. */
    fun levels(parts: Int): Int = if (parts <= 0) 0 else if (parts <= FAN_IN) 1 else 1 + levels(ceil(parts / FAN_IN.toDouble()).toInt())

    /**
     * One reduce level: [notes] (labelled, in order) packed into calls of at most [FAN_IN] notes that fit the window
     * with [system]. A note too long for a call on its own is cut to fit; beside other notes, each is cut to half the
     * room ([noteRoom]), so every call but the last merges at least two and the levels end (at a short context two merges
     * written to their cap don't fit one call whole, and would otherwise be merged one by one for ever). The result lists
     * the notes' indexes per call.
     */
    fun reduceGroups(notes: List<String>, system: String, maxTokens: Int = ContextWindow.MAX_TOKENS): List<List<Int>> {
        val room = budget(system, maxTokens)
        val each = noteRoom(notes.size, room)
        val out = ArrayList<MutableList<Int>>()
        var used = 0
        for ((k, n) in notes.withIndex()) {
            val c = TokenEstimate.text(fitNote(n, each) + "\n\n")
            if (out.isEmpty() || out.last().size >= FAN_IN || used + c > room) {
                out += mutableListOf<Int>()
                used = 0
            }
            out.last() += k
            used += c
        }
        return out
    }

    /** A reduce call over [notes] (each already labelled, "Notes on part 3 (14:20–14:31):\n…"). */
    fun reduceCall(notes: List<String>, lang: String, maxTokens: Int = ContextWindow.MAX_TOKENS): SummaryCall {
        val system = Prompt.summaryReduce(lang)
        val each = noteRoom(notes.size, budget(system, maxTokens))
        return SummaryCall(system, notes.joinToString("\n\n") { fitNote(it, each) }, REDUCE_TOKENS)
    }

    /** A note's share of a merge call's [room]: all of it alone, at most half beside others (any two then fit together). */
    private fun noteRoom(notes: Int, room: Int): Int = if (notes > 1) room / 2 else room

    /** "Notes on part 3 (14:20–14:31):\n<note>" for a map note; "Summary of parts 1–12 (…):" for a merged one. */
    fun noteLabel(first: Int, last: Int, from: String, to: String): String =
        if (first == last) "Notes on part $first ($from–$to):" else "Summary of parts $first–$last ($from–$to):"

    private fun fitNote(n: String, room: Int): String {
        if (TokenEstimate.text(n + "\n\n") <= room) return n
        var k = n.length
        while (k > 0 && TokenEstimate.text(n.take(k) + " …\n\n") > room) k = k * 9 / 10
        return n.take(k).trimEnd() + " …"
    }
}

/** A word or number in a summary that the transcript doesn't have. */
data class SummaryMiss(val text: String, val kind: Kind) {
    enum class Kind { NUMBER, NAME, TIME }

    /** "Robot couldn't find “17 h” in the transcript." */
    val message: String get() = if (kind == Kind.TIME) "Robot couldn't find a line at $text." else "Robot couldn't find “$text” in the transcript."
}

/**
 * The number check (docs/conversation-mode.md 7.1): every number, every capitalised name and every [hh:mm] reference
 * in a summary must be in the transcript (its lines and translations), among the speakers' names or in People. It is
 * cheap and deterministic, and catches the worst small-model mistake: a number or a name made up.
 */
object SummaryCheck {
    private val TIME_REF = Regex("""\[(\d{1,2}):(\d{2})]""")
    private val NUMBER = Regex("""\d+(?:[.,\u00A0\u202F ]\d+)*(?:\s?(?:h\b|min\b|%|\$|€))?""")
    private val WORD = Regex("""\p{L}[\p{L}'’]*""")

    /** Number words up to twenty and the tens, English and French: "five" in the transcript backs a "5" in the summary. */
    private val NUMBER_WORDS: Map<String, Int> = run {
        val en = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
            "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
        val fr = listOf("zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize",
            "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf", "vingt")
        val m = HashMap<String, Int>()
        en.forEachIndexed { i, w -> m[w] = i }
        fr.forEachIndexed { i, w -> m[w] = i }
        m["une"] = 1
        listOf("thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "hundred" to 100, "thousand" to 1000, "trente" to 30, "quarante" to 40,
            "cinquante" to 50, "soixante" to 60, "cent" to 100, "mille" to 1000).forEach { (w, v) -> m[w] = v }
        m
    }

    /** Days and months in both languages, by number, so "Tuesday" in the summary is backed by "mardi" in the transcript. */
    private val CALENDAR: Map<String, String> = run {
        val days = listOf("monday" to "lundi", "tuesday" to "mardi", "wednesday" to "mercredi", "thursday" to "jeudi", "friday" to "vendredi",
            "saturday" to "samedi", "sunday" to "dimanche")
        val months = listOf("january" to "janvier", "february" to "fevrier", "march" to "mars", "april" to "avril", "may" to "mai", "june" to "juin",
            "july" to "juillet", "august" to "aout", "september" to "septembre", "october" to "octobre", "november" to "novembre", "december" to "decembre")
        (days + months).flatMapIndexed { i, (en, fr) -> listOf(en to "c$i", fr to "c$i") }.toMap()
    }

    /** Capitalised words that are not names: the summary's own headings, and words every summary may use. */
    private val ALWAYS = setOf("i", "robot", "user", "speaker", "speakers", "unknown", "english", "french", "anglais", "francais", "quebec",
        "decisions", "decision", "to", "do", "numbers", "dates", "open", "questions", "overview", "part", "parts", "notes", "summary", "gemma", "claude",
        "ok", "okay", "am", "pm")

    /** Common words that start sentences, English and French: a capital there is not a name. */
    private val COMMON = setOf("the", "a", "an", "they", "we", "he", "she", "it", "you", "this", "that", "these", "those", "there", "both", "all",
        "no", "none", "some", "most", "one", "two", "each", "every", "after", "before", "then", "next", "also", "later", "when", "while", "if", "in",
        "on", "at", "for", "with", "by", "from", "about", "as", "and", "but", "or", "so", "not", "nothing", "everyone", "someone", "who", "what",
        "where", "why", "how", "which", "agreed", "discussed", "talked", "asked", "said", "will", "would", "should", "need", "needs", "plan", "plans",
        "le", "la", "les", "un", "une", "des", "ils", "elles", "il", "elle", "on", "nous", "vous", "ce", "cette", "ces", "tous", "aucun", "apres",
        "avant", "puis", "ensuite", "aussi", "quand", "si", "dans", "pour", "avec", "par", "de", "et", "mais", "ou", "pas", "rien", "qui", "quoi",
        "pourquoi", "comment", "lequel", "resume", "points", "questions", "ouvertes", "chiffres", "faire", "a")

    private fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT).replace('’', '\'').removeSuffix("'s")

    private fun digits(s: String): String = s.filter(Char::isDigit)

    private fun runs(s: String): List<String> = Regex("\\d+").findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    /**
     * What [summary] says that [transcript] (each line's words and translations), [names] (the speakers' names and
     * People) and [times] (the lines' clock times, "14:03") don't back up, in the order it says it.
     */
    fun misses(summary: String, transcript: List<String>, names: Collection<String> = emptyList(), times: Collection<String> = emptyList()): List<SummaryMiss> {
        val source = transcript.joinToString("\n")
        val srcNumbers = NUMBER.findAll(source).map { it.value }.toList()
        val srcDigits = srcNumbers.map(::digits).toSet()
        val srcRuns = srcNumbers.flatMap(::runs).toSet()
        val srcWords = (WORD.findAll(source).map { plain(it.value) } + names.flatMap { n -> WORD.findAll(n).map { plain(it.value) } } +
            Regex("[\\p{L}'’-]+").findAll(source).map { plain(it.value) }).toSet()
        val srcValues = srcWords.mapNotNull { NUMBER_WORDS[it] }.toSet()
        val srcCalendar = srcWords.mapNotNull { CALENDAR[it] }.toSet()
        val timeSet = times.map { it.padStart(5, '0') }.toSet()
        val out = LinkedHashMap<String, SummaryMiss>()

        // [hh:mm] references point at lines.
        for (m in TIME_REF.findAll(summary)) {
            val t = m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
            if (times.isNotEmpty() && t !in timeSet) out.getOrPut("t$t") { SummaryMiss(t, SummaryMiss.Kind.TIME) }
        }
        val text = summary.replace(TIME_REF, " ")
        for (m in NUMBER.findAll(text)) {
            val n = m.value.trim()
            val d = digits(n)
            val rs = runs(n)
            val backed = d in srcDigits || rs.all { it in srcRuns } || (rs.size == 1 && rs[0].toIntOrNull()?.let { it in srcValues } == true)
            if (!backed) out.getOrPut("n$n") { SummaryMiss(n, SummaryMiss.Kind.NUMBER) }
        }
        for (m in WORD.findAll(text)) {
            val w = m.value
            if (!w[0].isUpperCase()) continue
            val p = plain(w)
            if (p in ALWAYS) continue
            if (startsSentence(text, m.range.first) && p in COMMON) continue
            val backed = p in srcWords || CALENDAR[p]?.let { it in srcCalendar } == true
            if (!backed) out.getOrPut("w$p") { SummaryMiss(w.removeSuffix("'s").removeSuffix("’s"), SummaryMiss.Kind.NAME) }
        }
        return out.values.toList()
    }

    /** Nothing but spaces, bullets or markup since the start of the line or the end of a sentence. */
    private fun startsSentence(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t' || text[i] in "*_#>-–—•\"“«(")) i--
        return i < 0 || text[i] in ".!?:\n;"
    }
}

/** When Robot offers the stronger AI first (docs/conversation-mode.md 7.1). */
object SummaryOffer {
    /** Longer than this many parts (about 25 to 30 minutes). */
    const val LONG_PARTS = 3

    fun helperFirst(s: RobotSettings, parts: Int, important: Boolean, flagged: Boolean): Boolean =
        s.convAutoOfferHelper && (parts > LONG_PARTS || important || flagged)

    const val CARD = "This conversation is long; a stronger AI will summarize it more accurately."
}

/** How a helper gets the transcript: the API (Robot gets the answer) or a share to an app (you send it there). */
enum class HelperRoute { API, APP_SHARE }

/**
 * Where a helper sends a transcript, as the consent card says it: [provider] ("Anthropic"), [model] ("Claude Opus 5.5",
 * null when the app picks), [network] how it gets there, [retention] what the provider keeps (null: their own terms),
 * [cost] the estimated price line (null: none to Robot).
 */
data class Disclosure(val provider: String, val model: String?, val network: String, val retention: String? = null, val cost: String? = null,
    val warning: String? = null) {
    /** "Goes to: Anthropic (Claude Opus 5.5), over the internet, with your API key. ≈ 9¢." */
    val line: String get() = "Goes to: $provider" + (model?.let { " ($it)" } ?: "") + ", $network." + (retention?.let { " $it" } ?: "") +
        (cost?.let { " ${it.replaceFirstChar { c -> c.uppercase() }}." } ?: "") + (warning?.let { " $it" } ?: "")

    companion object {
        fun claudeApi(model: AnthropicModel, dollars: Double): Disclosure =
            Disclosure("Anthropic", model.title, "over the internet, with your API key", cost = CostText.line(dollars))

        fun claudeApp(): Disclosure = app("Claude", "with your claude.ai account, when you press Send there")

        /** 1.0.0: a helper's own app ("ChatGPT"): your account there, your Send. */
        fun app(name: String, how: String = "with your own account there, when you press Send there"): Disclosure = Disclosure("the $name app", null, how)

        /**
         * 1.0.0, any helper's API: its company and model, how it gets there, and the cost line the panel shows ([cost]:
         * [SummaryCost.line]); [budgetUsed]: today's budget for helpers (or this helper's own) is used up, said on the card.
         */
        fun api(h: HelperConfig, cost: String?, budgetUsed: Boolean): Disclosure =
            Disclosure(if (h.home) "your own server" else h.presetOf.company, h.catalogModel?.title ?: h.model.ifEmpty { null },
                if (h.home) "over your network" else "over the internet, with your API key", cost = cost,
                warning = if (budgetUsed) SummaryCost.OVER_BUDGET else null)
    }
}

/**
 * 1.0.0: the price of a summary by any helper, from its own price ([HelperConfig.price]: published, yours, reported or
 * free) and the same estimate of tokens as before ([HelperCost]), for the card's line. Never a guess: a helper whose price
 * Robot doesn't know says so.
 */
object SummaryCost {
    /** The estimate in dollars for [chars] characters to [h], or null when its price isn't known. */
    fun estimate(h: HelperConfig, chars: Int, at: Instant): Double? =
        Pricing.dollars(h.price, HelperUsage(input = HelperCost.tokens(chars), output = HelperCost.OUTPUT_TOKENS), at)

    /** "≈ 9¢", "cost unknown: Robot has no price for this model", or nothing for a free server (as the panel's cost line). */
    fun line(h: HelperConfig, chars: Int, at: Instant): String? {
        val d = estimate(h, chars, at)
        return if (d == null) UNKNOWN else Pricing.line(d, free = h.price is HelperPrice.Free)
    }

    const val UNKNOWN = "cost unknown: Robot has no price for this model (you can enter it on the helper's page)"

    /** As an ask you make yourself: never refused, but the card says so (Automatic would have asked first). */
    const val OVER_BUDGET = "Today's budget for helpers is used up: this adds to it."
}

/** A helper's progress while it works. */
interface HelperListener {
    fun text(soFar: String) {}
}

sealed class HelperResult {
    /** The summary came back ([by]: the model's id), and its estimated price. */
    data class Done(val text: String, val by: String, val costMicros: Long = 0) : HelperResult()

    /** Handed to an app: you send it there, and Robot doesn't hear the answer. */
    data class Shared(val how: AppShareCode) : HelperResult()

    data class Failed(val reason: String) : HelperResult()
}

/**
 * A stronger AI that can summarize a transcript (docs/conversation-mode.md 7.2). 0.5.0 has the Claude API and the Claude
 * app (in :voice, where the key and the SDK live); 0.6.0 adds an OpenAI-compatible helper and a home server behind the
 * same card.
 */
interface Helper {
    val id: String
    val title: String
    val disclosure: Disclosure
    val route: HelperRoute

    fun maxInputTokens(): Int

    /** API route only. Never called without your tap: a [SummaryJob] exists only from [ConsentCard.approve]. */
    suspend fun summarize(job: SummaryJob, listener: HelperListener): HelperResult
}

/**
 * Exactly what goes to a helper, and the card that says so (docs/conversation-mode.md 7.2). [blocks] are the first user
 * turn for the API (context line, the conversation line, the transcript, the request); [appText] is what the helper's app
 * gets. Built from the same parts, so the card can't list something the payload doesn't hold, or the other way round.
 */
class ConsentCard private constructor(
    val transcriptId: String,
    /** The helper's id, and its name in "Send this conversation to Jarvis?". */
    val helper: String,
    val to: String,
    val disclosure: Disclosure,
    /** "41 min · 2 speakers · 6,120 words · French and English." */
    val stats: String,
    /** "It includes what other people said and the names you gave them." */
    val includes: String,
    /** "Replace names with “Speaker 1, 2”" is ticked. */
    val anonymized: Boolean,
    val blocks: List<HelperBlock.Text>,
    val appText: String,
    val words: Int,
    val speakers: Int,
    private val rebuild: (Boolean) -> ConsentCard,
) {
    val title: String get() = "Send this conversation to $to?"

    /** The names toggle: the same card, its payload made again. */
    fun withNames(replace: Boolean): ConsentCard = if (replace == anonymized) this else rebuild(replace)

    /** Your tap on Send: the only way a transcript becomes a job a helper may run. */
    fun approve(now: Long): SummaryJob = SummaryJob.approved(this, now)

    companion object {
        const val TOGGLE = "Replace names with “Speaker 1, 2”"
        /** A helper's app takes at most this much text in a share (about 1.5 h of talk; the Claude app's limit). */
        const val APP_MAX_CHARS = 100_000
        const val TOO_LONG_FOR_APP = "Too long for the app; export and attach the file."

        /**
         * The card for sending [t] to [helper]. [now] and [region] make the context line (HelperPrompt.context);
         * [summaryLang] the language asked for; [anonymize] the toggle.
         */
        fun build(helper: String, to: String, disclosure: Disclosure, meta: TranscriptMeta, t: Transcript, now: ZonedDateTime, region: String,
            summaryLang: String, zone: ZoneId, anonymize: Boolean = false): ConsentCard {
            val named = t.speakers.filter { t.names[it] != null }
            val label = t.speakers.withIndex().associate { (k, s) -> s to if (anonymize) "Speaker ${k + 1}" else s }
            val who = t.speakers.joinToString(", ") { s ->
                val name = t.names[s]
                label.getValue(s) + when {
                    s == t.me || s == SpeakerClusterer.ME -> " = the user"
                    name != null && !anonymize -> " = $name"
                    else -> ""
                }
            }
            val day = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US).format(Instant.ofEpochMilli(meta.createdAt).atZone(zone))
            val minutes = minutes(meta, t)
            val lines = t.lines.joinToString("\n") { l ->
                var text = l.text.replace(Regex("\\s+"), " ").trim()
                // The names you gave go wherever they appear, when you asked: the card says what is left.
                if (anonymize) for (s in named) text = text.replace(Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(t.names.getValue(s)) + "(?![\\p{L}\\p{N}])"), label.getValue(s))
                SummaryPlanner.line(TranscriptExport.clock(meta, l.t0, zone), label.getValue(l.speaker), l.lang, text)
            }
            val ask = "Summarize this conversation in ${TranscriptExport.langName(summaryLang)}."
            val convo = "[Conversation of $day, $minutes minutes. Speakers: $who.]"
            val blocks = listOf(HelperBlock.Text(HelperPrompt.context(now, region)), HelperBlock.Text(convo), HelperBlock.Text(lines), HelperBlock.Text(ask))
            val words = t.words
            val langs = t.langs.map { TranscriptExport.langName(it) }
            val stats = "$minutes min · ${t.speakers.size} speaker${if (t.speakers.size == 1) "" else "s"} · ${String.format(Locale.US, "%,d", words)} words" +
                (if (langs.isEmpty()) "" else " · " + langs.joinToString(" and ")) + "."
            val includes = if (named.isEmpty() || anonymize) "It includes what other people said." else "It includes what other people said and the names you gave them."
            return ConsentCard(meta.id, helper, to, disclosure, stats, includes, anonymize, blocks, "$ask\n\n$convo\n$lines", words, t.speakers.size) {
                build(helper, to, disclosure, meta, t, now, region, summaryLang, zone, it)
            }
        }

        fun minutes(meta: TranscriptMeta, t: Transcript): Long {
            val end = if (meta.endedAt > meta.createdAt) meta.endedAt else meta.createdAt + (t.lines.maxOfOrNull { it.t1 } ?: 0.0).times(1000).toLong()
            return ((end - meta.createdAt + 30_000) / 60_000).coerceAtLeast(if (t.lines.isEmpty()) 0 else 1)
        }

        /** The share text for the helper's app, or null when it is too long (the card offers export instead). */
        fun appShare(card: ConsentCard): String? = card.appText.takeIf { it.length <= APP_MAX_CHARS }

        /** Too long for one request to a helper's API (Helper.maxInputTokens): nothing is sent, the card offers export. */
        const val TOO_LONG_FOR_API = "Too long for one request; export it and attach the file."

        /** A request of [blocks] fits a helper's API that takes at most [maxTokens] in (by the card's estimate, HelperCost). */
        fun apiFits(blocks: List<HelperBlock.Text>, maxTokens: Int): Boolean = HelperCost.tokens(blocks.sumOf { it.text.length }) <= maxTokens
    }
}

/**
 * A transcript you agreed to send: made only by [ConsentCard.approve], so [consent] is always true and the text is the
 * card's. Helpers check [HelperGate] first anyway.
 */
class SummaryJob private constructor(
    val transcriptId: String,
    val helper: String,
    val blocks: List<HelperBlock.Text>,
    val appText: String,
    val anonymized: Boolean,
    val consent: Boolean,
    val approvedAt: Long,
) {
    companion object {
        internal fun approved(card: ConsentCard, now: Long): SummaryJob =
            SummaryJob(card.transcriptId, card.helper, card.blocks, card.appText, card.anonymized, consent = true, approvedAt = now)
    }
}

/** The last check before anything leaves the phone. */
object HelperGate {
    fun allowed(job: SummaryJob?): Boolean = job != null && job.consent && job.approvedAt > 0
}

/** The estimated price on the card: characters ÷ 3.5 in, plus room for thinking and the summary out. */
object HelperCost {
    const val CHARS_PER_TOKEN = 3.5
    const val OUTPUT_TOKENS = 2_500L

    fun estimate(model: AnthropicModel, chars: Int): Double = AnthropicCost.dollars(model, TokenUsage(input = tokens(chars), output = OUTPUT_TOKENS))

    /** The input of a request of [chars] characters, with the system prompt, about. */
    fun tokens(chars: Int): Long = (chars / CHARS_PER_TOKEN).roundToLong() + SYSTEM_TOKENS

    private const val SYSTEM_TOKENS = 200L
}

/** summary-<n>.json: one summary of a transcript, with who made it and, for a helper, your consent. */
data class SummaryRecord(
    /** "robot" or the helper's model id ("claude-opus-5-5"). */
    val by: String,
    val model: String,
    /** "phone", "api" or "app". */
    val via: String,
    val createdAt: Long,
    val text: String,
    val consent: Boolean = false,
    val sentAt: Long = 0,
    val parts: Int = 0,
    /** 1.0.0: the helper's name ("ChatGPT") and where it went ("OpenAI"); null in a record from before (Claude's). */
    val helperName: String? = null,
    val company: String? = null,
) {
    fun toJson(): String = JSONObject().put("by", by).put("model", model).put("via", via).put("createdAt", createdAt).put("consent", consent)
        .put("sentAt", sentAt).put("chunks", parts).apply { helperName?.let { put("helper", it) }; company?.let { put("company", it) } }
        .put("text", text).toString()

    /**
     * "Robot's summary — made on this phone by Gemma 4 E2B. …" (the model that made it: [model] is its BrainModel id) or
     * "Claude's summary — sent to Anthropic with your OK on 11 Oct, 14:52."
     */
    fun label(zone: ZoneId = ZoneId.systemDefault()): String = if (via == VIA_PHONE) robotLabel(model)
    else "${helperName ?: "Claude"}'s summary — sent to ${company ?: "Anthropic"} with your OK on " +
        DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US).format(Instant.ofEpochMilli(if (sentAt > 0) sentAt else createdAt).atZone(zone)) + "."

    companion object {
        const val VIA_PHONE = "phone"
        const val VIA_API = "api"
        const val VIA_APP = "app"

        /** Robot's own summary's label, naming the model it was made with (0.4.1: the one chosen in Settings › Model). */
        fun robotLabel(model: String): String = "Robot's summary — made on this phone by ${modelName(model)}. It can miss or mix up details; check the transcript."

        /** A model as a summary names it: "Gemma 4 E2B", "Gemma 4 E4B", "your own model file"; Robot's model when unknown. */
        fun modelName(model: String): String = when (val m = BrainModel.byId(model)) {
            null -> "Robot's model"
            BrainModel.OWN -> "your own model file"
            else -> m.modelName.substringBefore(" (")
        }

        fun fromJson(text: String?): SummaryRecord? = try {
            val o = JSONObject(text ?: return null)
            SummaryRecord(o.getString("by"), o.optString("model"), o.optString("via", VIA_PHONE), o.optLong("createdAt"), o.getString("text"),
                o.optBoolean("consent"), o.optLong("sentAt"), o.optInt("chunks"), o.optString("helper").takeIf { it.isNotEmpty() },
                o.optString("company").takeIf { it.isNotEmpty() })
        } catch (_: Exception) { null }
    }
}
