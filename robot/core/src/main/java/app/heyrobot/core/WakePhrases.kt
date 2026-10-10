package app.heyrobot.core

import java.util.Locale

// Pure. The wake phrases (0.2.3): a few phrases the owner picks in Settings › Voice › Wake words, each with a
// sensitivity, spelled for the keyword model on the phone (KwsSpeller, pure Kotlin, so the main app can check a
// phrase without sherpa-onnx) and handed to the keyword spotter as one line each:
// "<tokens> :<boost> #<threshold> @W<id>". The label says which phrase fired; "STOP" stays a separate keyword,
// added only while Robot speaks. Every keyword threshold Robot uses is in KwsThresholds.

/** How readily a wake phrase fires: Low wakes by accident least, High hears a quiet or far voice best. */
enum class WakeSensitivity(val title: String, val detail: String) {
    LOW("Low", "Fewer accidental wakes; say it clearly"),
    MEDIUM("Medium", "As “hey robot” was tested: 10 of 10 close, 5 of 5 across the room, no false wake in 2 hours"),
    HIGH("High", "Hears you more easily, wakes by accident more often"),
}

/** One wake phrase. [id] is stable (the keyword label "W<id>" and the voice log use it); [text] as the owner typed it. */
data class WakePhrase(val id: Int, val text: String, val sensitivity: WakeSensitivity = WakeSensitivity.MEDIUM, val enabled: Boolean = true) {
    val words: Int get() = WakePhrases.words(text).size
}

/**
 * Every keyword threshold and boost, in one place (sherpa-onnx: the threshold is the probability a keyword must reach,
 * higher = harder to trigger; the boost is added per token in the search, higher = easier to keep alive).
 */
object KwsThresholds {
    data class Preset(val threshold: Float, val boost: Float)

    /** Medium: phase 0's tested setting for "hey robot" (and the spotter's defaults). */
    val MEDIUM = Preset(0.25f, 1.5f)
    /** Low: needs a clearer match and gets less help in the search; for single words like "robot". */
    val LOW = Preset(0.35f, 1.0f)
    /** High: for a quiet voice or a far phone; more accidental wakes. */
    val HIGH = Preset(0.18f, 2.0f)

    /** "Stop" while Robot speaks, as 0.1.3 shipped it. */
    val STOP = Preset(0.25f, 1.5f)
    /**
     * "Stop" while Robot speaks and the microphone has the phone's echo cancellation on: Robot's own voice is mostly
     * taken out, so a slightly lower threshold catches a "stop" said over her (EchoGuard still applies).
     */
    val STOP_AEC = Preset(0.20f, 1.5f)

    fun wake(s: WakeSensitivity): Preset = when (s) {
        WakeSensitivity.LOW -> LOW
        WakeSensitivity.MEDIUM -> MEDIUM
        WakeSensitivity.HIGH -> HIGH
    }

    fun stop(echoCancelled: Boolean): Preset = if (echoCancelled) STOP_AEC else STOP
}

object WakePhrases {
    const val MAX_CHARS = 24
    const val MAX_WORDS = 4
    const val MAX_PHRASES = 8
    /** Said while Robot speaks, "stop" ends the reply: no wake phrase may use it. */
    const val RESERVED = "stop"
    const val SHORT_WARNING = "Short words like 'robot' trigger by accident more often."

    val DEFAULT: List<WakePhrase> = listOf(WakePhrase(1, "hey robot", WakeSensitivity.MEDIUM, true))

    private val ALLOWED = Regex("^[A-Za-z' ]+$")

    /** Trimmed, single spaces, typographic apostrophes made plain (what is stored and spelled). */
    fun clean(text: String): String = text.replace('’', '\'').replace('ʼ', '\'').replace('‘', '\'')
        .trim().replace(Regex("\\s+"), " ")

    fun words(text: String): List<String> = clean(text).split(' ').filter { it.isNotEmpty() }

    fun singleWord(text: String): Boolean = words(text).size == 1

    /** A new single word starts at Low: short words wake by accident more often. */
    fun defaultSensitivity(text: String): WakeSensitivity = if (singleWord(text)) WakeSensitivity.LOW else WakeSensitivity.MEDIUM

    /**
     * Why [text] can't be a wake phrase (shape only: the spelling is KwsSpeller's), or null. [others]: the phrases
     * already in the list (a phrase being edited is left out by the caller).
     */
    fun problem(text: String, others: List<WakePhrase> = emptyList()): String? {
        val t = clean(text)
        val w = words(t)
        return when {
            t.isEmpty() -> "Type a phrase."
            t.length > MAX_CHARS -> "At most $MAX_CHARS characters."
            !ALLOWED.matches(t) -> "Use only the letters A to Z, spaces and apostrophes."
            w.size > MAX_WORDS -> "Use 1 to $MAX_WORDS words."
            w.any { word -> word.none { it.isLetter() } } -> "Each word needs a letter."
            w.any { it.lowercase(Locale.ROOT).trim('\'') == RESERVED } -> "“Stop” is reserved: saying it stops Robot talking."
            others.any { key(it.text) == key(t) } -> "That phrase is already in the list."
            others.size >= MAX_PHRASES -> "At most $MAX_PHRASES wake phrases."
            else -> null
        }
    }

    private fun key(text: String) = clean(text).uppercase(Locale.ROOT)

    fun nextId(list: List<WakePhrase>): Int = (list.maxOfOrNull { it.id } ?: 0) + 1

    /** For the settings file: drops entries that are not a valid phrase or reuse an id (never fails). */
    fun sanitize(list: List<WakePhrase>): List<WakePhrase> {
        val seen = HashSet<Int>()
        val out = mutableListOf<WakePhrase>()
        for (p in list) {
            val t = clean(p.text)
            if (p.id <= 0 || !seen.add(p.id) || problem(t, out) != null) continue
            out += p.copy(text = t)
        }
        return out
    }

    /** The last word of each enabled phrase, for EchoGuard: Robot saying it may wake her through the microphone. */
    fun echoWords(list: List<WakePhrase>): List<String> =
        list.filter { it.enabled }.mapNotNull { words(it.text).lastOrNull()?.lowercase(Locale.ROOT)?.trim('\'') }.filter { it.isNotEmpty() }.distinct()
}

/**
 * The keyword model's spelling: its SentencePiece model (kws/bpe.model) and its tokens.txt. Every token of a phrase
 * must be in tokens.txt, or the phrase is refused: sherpa-onnx refuses the whole keyword set otherwise (its stream
 * comes back empty).
 */
class KwsSpeller(private val encoder: SentencePieceEncoder, val tokens: Set<String>) {
    sealed class Result {
        data class Ok(val tokens: List<String>) : Result()
        data class Refused(val why: String) : Result()
    }

    fun spell(phrase: String): Result {
        val t = WakePhrases.clean(phrase).uppercase(Locale.ROOT)
        if (t.isEmpty()) return Result.Refused("Type a phrase.")
        val pieces = try { encoder.encode(t) } catch (e: Exception) { return Result.Refused("Robot couldn't spell that for the wake-word model.") }
        if (pieces.isEmpty()) return Result.Refused("Type a phrase.")
        val unknown = pieces.firstOrNull { it !in tokens }
        if (unknown != null) return Result.Refused("The wake-word model can't spell “${unknown.trimStart(SentencePieceEncoder.SPACE)}”.")
        return Result.Ok(pieces)
    }

    companion object {
        /** tokens.txt: "<token> <id>" per line. */
        fun parseTokens(text: String): Set<String> =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringBefore(' ') }.toSet()

        fun load(bpeModel: ByteArray, tokensTxt: String): KwsSpeller =
            KwsSpeller(SentencePieceEncoder(SentencePieceModel.parse(bpeModel)), parseTokens(tokensTxt))
    }
}

/** The keyword lines sherpa-onnx's KeywordSpotter.createStream takes, one keyword per line. */
object KeywordBuffer {
    /** The label of "STOP" (kws/stop.txt). */
    const val STOP_LABEL = "STOP"
    private val LABEL = Regex("^W([0-9]{1,6})$")

    fun label(id: Int): String = "W$id"

    /** The phrase id behind a wake label, or null ("STOP", anything else). */
    fun idOf(label: String): Int? = LABEL.find(label)?.groupValues?.get(1)?.toIntOrNull()

    /** "<tokens> :<boost> #<threshold> @<label>" (Float.toString is locale-free, as sherpa's stof reads it). */
    fun line(tokens: List<String>, preset: KwsThresholds.Preset, label: String): String {
        require(tokens.isNotEmpty() && tokens.none { it.isBlank() || it.any(Char::isWhitespace) }) { "tokens" }
        require(label.isNotEmpty() && label.none { it.isWhitespace() || it == '/' }) { "label" }
        return tokens.joinToString(" ") + " :" + preset.boost + " #" + preset.threshold + " @" + label
    }

    data class Built(val text: String, val ids: List<Int>, val refused: List<Int>)

    /** One line per enabled phrase that spells; the ones that don't are listed in [Built.refused]. */
    fun wake(phrases: List<WakePhrase>, speller: KwsSpeller): Built {
        val lines = mutableListOf<String>()
        val ids = mutableListOf<Int>()
        val refused = mutableListOf<Int>()
        for (p in phrases) {
            if (!p.enabled) continue
            when (val r = speller.spell(p.text)) {
                is KwsSpeller.Result.Ok -> {
                    lines += line(r.tokens, KwsThresholds.wake(p.sensitivity), label(p.id))
                    ids += p.id
                }
                is KwsSpeller.Result.Refused -> refused += p.id
            }
        }
        return Built(lines.joinToString("\n"), ids, refused)
    }

    /** The tokens of a keyword file line ("▁ST O P @STOP" → [▁ST, O, P]): everything before the first annotation. */
    fun tokensOf(line: String): List<String> =
        line.trim().split(' ').filter { it.isNotEmpty() }.takeWhile { it[0] != ':' && it[0] != '#' && it[0] != '@' }

    /** Every token is in tokens.txt, the line has a label, and nothing else is on it: safe to give sherpa-onnx. */
    fun validLine(line: String, tokens: Set<String>): Boolean {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        val toks = tokensOf(line)
        if (toks.isEmpty() || toks.any { it !in tokens }) return false
        val rest = parts.drop(toks.size)
        return rest.any { it.startsWith("@") && it.length > 1 } && rest.all { p ->
            when (p[0]) {
                ':', '#' -> p.drop(1).toFloatOrNull()?.let { it.isFinite() && it > 0f } == true
                '@' -> p.length > 1
                else -> false
            }
        }
    }

    /** Joins non-empty keyword sets into one buffer. */
    fun join(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString("\n")
}
