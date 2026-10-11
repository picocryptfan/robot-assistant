package app.heyrobot.core

import java.util.Locale

// Pure. The pieces of conversation mode's capture that need no microphone and no model (docs/conversation-mode.md 1,
// 2.1-2.3), so they are tested here: the speech flag from the voice detector's per-frame probability, the session's
// audio ring (segments and their padding are sliced from it), word times from the recognizer's tokens, a line's
// language, a line as the event log stores it, and the numbers the voice log gets once a minute. The :voice process
// strings them together (ConversationEngine).

/** What conversation mode does, in numbers. */
object ConversationRules {
    /** The heartbeat file is written this often while a session runs (crash recovery). */
    const val BEAT_MS = SessionRecovery.BEAT_MS
    /** A session stops by itself below this much free storage. */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024
    /** "Still transcribing — 4 h" (no cap on the length). */
    const val LONG_NOTICE_MS = 4 * 3_600_000L
    /** The live screen keeps the newest lines only; the file has them all. */
    const val LIVE_LINES = 500
    /** The open segment's words are decoded again this often while the screen is on (and the queue is empty). */
    const val PARTIAL_MS = 1_500L
    /** The session's audio ring: a whole capped segment with its padding, and room to spare. */
    const val RING_MS = 20_000
    /** Every line's label when speakers aren't told apart (no speaker model, or "Tell speakers apart" off). */
    const val SPEAKER = "S1"
    /** A stopped session waits this long for the segments still being decoded, so every line heard is saved. */
    const val DRAIN_MS = 30_000L

    /** Why the microphone went quiet: a call when the phone is in a call's audio mode, else another app took it. */
    fun silencedBy(inCall: Boolean): ConvPause = if (inCall) ConvPause.CALL else ConvPause.MIC_TAKEN

    /** The gap marker in the transcript for a pause of this kind. */
    fun gap(why: ConvPause): String = when (why) {
        ConvPause.CALL -> TranscriptEvent.GAP_CALL
        ConvPause.MIC_TAKEN -> TranscriptEvent.GAP_MIC
        else -> TranscriptEvent.GAP_PAUSE
    }

    /** Partials need the setting, the screen on, a cool phone, and nothing else waiting to be decoded. */
    fun partialNow(s: RobotSettings, screenOn: Boolean, warm: Boolean, queued: Int, sinceLastMs: Long): Boolean =
        ConversationSettings.partials(s, screenOn, warm) && queued == 0 && sinceLastMs >= PARTIAL_MS

    /** Stop for lack of space. */
    fun diskFull(freeBytes: Long): Boolean = freeBytes in 0 until MIN_FREE_BYTES
}

/**
 * Silero's per-frame speech probability as a flag, with hysteresis: speech from [on], and it stays speech until the
 * probability drops below [off] (Silero's own advice), so a word's quieter end doesn't flicker. The 0.25 s, 15 s and
 * padding rules are the Segmenter's.
 */
class VadFlag(private val on: Float = 0.5f, private val off: Float = 0.35f) {
    var speech: Boolean = false
        private set

    fun update(p: Float): Boolean {
        speech = if (speech) p >= off else p >= on
        return speech
    }

    fun reset() { speech = false }
}

/**
 * The newest [capacity] samples, addressed by their position since the ring started (sample 0). A segment is sliced
 * as it closes; what fell out of the ring is simply not there (the slice is shorter).
 */
class SampleRing(val capacity: Int) {
    private val buf = FloatArray(capacity)
    /** One past the newest sample's position. */
    var end: Long = 0
        private set

    /** The oldest position still held. */
    val start: Long get() = (end - capacity).coerceAtLeast(0)

    fun push(x: FloatArray, n: Int = x.size) {
        for (i in 0 until n) buf[((end + i) % capacity).toInt()] = x[i]
        end += n
    }

    /** Samples [from] to [to] (clipped to what the ring holds), oldest first. */
    fun slice(from: Long, to: Long): FloatArray {
        val a = from.coerceAtLeast(start)
        val b = to.coerceAtMost(end)
        if (b <= a) return FloatArray(0)
        return FloatArray((b - a).toInt()) { buf[((a + it) % capacity).toInt()] }
    }
}

/** A closed segment and its audio (padding included), as it goes on the decode queue. */
class Cut(val seg: Segment, val audio: FloatArray)

/**
 * One run's audio side, without the detector (docs/conversation-mode.md 2.1): each frame goes into the run's ring and,
 * with the detector's verdict, into the Segmenter; a segment that closes comes out with its audio, sliced at once
 * (the ring moves on). The session makes a new one for each run (start, or going on after a pause).
 */
class SegmentCutter(val rules: SegmentRules = SegmentRules(), ringMs: Int = ConversationRules.RING_MS) {
    private val segmenter = Segmenter(rules)
    private val ring = SampleRing(rules.sampleRate * ringMs / 1000)

    /** Samples fed so far. */
    val position: Long get() = ring.end

    /** One frame of [SegmentRules.frameSamples]: [speech] the detector's verdict, [robotSpeaking] Robot's voice playing. */
    fun frame(f: FloatArray, speech: Boolean, robotSpeaking: Boolean): Cut? {
        ring.push(f)
        var ss = 0.0
        for (x in f) ss += x * x
        return segmenter.feed(speech, level(ss / f.size.coerceAtLeast(1)), robotSpeaking)?.let(::cut)
    }

    /** The run ends (a pause, the stop): the open segment, if it holds enough speech. */
    fun flush(): Cut? = segmenter.flush()?.let(::cut)

    /** The open segment's audio so far, from its padding on (the live words), or null when none is open. */
    fun openAudio(): FloatArray? {
        val open = segmenter.openStart ?: return null
        return ring.slice((open - rules.samples(rules.padMs)).coerceAtLeast(0), ring.end)
    }

    private fun cut(seg: Segment): Cut? = ring.slice(seg.start, seg.end).takeIf { it.isNotEmpty() }?.let { Cut(seg, it) }

    companion object {
        /** A frame's loudness in dBFS (-120 for silence): the Segmenter cuts a long segment at its quietest frame. */
        fun level(meanSquare: Double): Float = if (meanSquare <= 1e-12) -120f else (10 * Math.log10(meanSquare)).toFloat()
    }
}

/** Gathers audio chunks of any length into frames of [size] samples (the voice detector's window). */
class FrameAssembler(private val size: Int) {
    private val cur = FloatArray(size)
    private var n = 0

    fun push(x: FloatArray, out: (FloatArray) -> Unit) {
        var i = 0
        while (i < x.size) {
            val k = minOf(size - n, x.size - i)
            System.arraycopy(x, i, cur, n, k)
            n += k
            i += k
            if (n == size) {
                out(cur.copyOf())
                n = 0
            }
        }
    }

    fun reset() { n = 0 }
}

/** What the recognizer gave for one stretch of audio: the text and its tokens with their start times (seconds). */
data class Heard(val text: String, val tokens: List<String> = emptyList(), val times: List<Float> = emptyList()) {
    val words: Int get() = Rehear.words(text)

    /**
     * Each word's start in centiseconds from the stretch's start: a token beginning with a space starts a word (and so
     * does the first token, and the one after a token that is only a word break). Tokens and times of different lengths
     * give none.
     */
    fun wordStarts(): List<Int> {
        if (tokens.isEmpty() || tokens.size != times.size) return emptyList()
        val out = ArrayList<Int>()
        var startsNext = true
        for ((k, t) in tokens.withIndex()) {
            // A lone word break (" " before a digit, say): the next token starts the word.
            if (t.isBlank()) { startsNext = true; continue }
            if (startsNext || t.startsWith(" ") || t.startsWith("▁")) out += Math.round(times[k] * 100).coerceAtLeast(0)
            startsNext = false
        }
        return out
    }

    companion object {
        /**
         * The re-heard halves of one segment as one (docs/conversation-mode.md 2.2): [a] decoded from the segment's
         * start, [b] from [shiftS] seconds later.
         */
        fun join(a: Heard, b: Heard, shiftS: Double): Heard = Heard(
            listOf(a.text, b.text).filter { it.isNotBlank() }.joinToString(" "),
            a.tokens + b.tokens,
            a.times + b.times.map { (it + shiftS).toFloat() },
        )
    }
}

/**
 * Each line's language (docs/conversation-mode.md 2.3): the line's own words, and for a short or not-sure line its
 * speaker's usual language ([label]'s speakerLang, phase 5.3), else the previous line's, then the table's best guess,
 * then yours ([owner]).
 */
class LineLabeller(private val id: LangId, private val owner: String) {
    private var previous: String? = null

    fun label(text: String, speakerLang: String? = null): Pair<LangGuess, LineLang> {
        val g = id.detect(text)
        val l = LangLabel.choose(g, speaker = speakerLang, previous = previous, owner = owner)
        // A label from the prior doesn't become the prior of the next line by itself... unless nothing better is known.
        if (!l.fromPrior || previous == null) previous = l.lang
        return g to l
    }

    /** You fixed a line's language: the next short line leans on that. */
    fun corrected(lang: String) { previous = lang }
}

/** A decoded segment as the line the event log stores (docs/conversation-mode.md 6.2). Times in seconds. */
object ConversationLine {
    /**
     * Line [i] of a segment decoded from [seg]'s padded start, in a run that began [offsetS] seconds into the session,
     * by [speaker]: its time is the speech's (not the padding's), its words' starts are counted from there (a word in
     * the padding counts as 0).
     */
    fun make(i: Int, offsetS: Double, seg: Segment, heard: Heard, guess: LangGuess, lang: LineLang, reheard: Boolean, rate: Int = Pcm.RATE,
        speaker: String = ConversationRules.SPEAKER): TranscriptEvent.Line {
        val t0 = offsetS + seg.speechStart.toDouble() / rate
        val t1 = offsetS + seg.speechEnd.toDouble() / rate
        val shift = Math.round((seg.speechStart - seg.start) * 100.0 / rate).toInt()
        val ws = heard.wordStarts().map { (it - shift).coerceAtLeast(0) }
        return TranscriptEvent.Line(i, t0, t1, speaker, lang.lang, heard.text.trim(), Math.round(guess.margin * 100) / 100.0,
            lang.fromPrior, ws, reheard)
    }
}

/**
 * The numbers voice log events 104 (segments, median decode ms, longest queue) and 105 (French, English, not-sure
 * lines) get, once a minute. Counts only.
 */
class MinuteStats {
    private val decodes = ArrayList<Int>()
    private var maxQueue = 0
    private var fr = 0
    private var en = 0
    private var unsure = 0

    @Synchronized
    fun segment(decodeMs: Long, queued: Int) {
        decodes += decodeMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        maxQueue = maxOf(maxQueue, queued)
    }

    @Synchronized
    fun line(lang: LineLang) {
        when {
            lang.fromPrior -> unsure++
            lang.lang == "fr" -> fr++
            lang.lang == "en" -> en++
            else -> unsure++
        }
    }

    /** (104's three numbers, 105's three numbers) and a fresh minute; null when nothing happened. */
    @Synchronized
    fun take(): Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>>? {
        if (decodes.isEmpty() && fr + en + unsure == 0) return null
        val sorted = decodes.sorted()
        val median = if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]
        val out = Triple(decodes.size, median, maxQueue) to Triple(fr, en, unsure)
        decodes.clear(); maxQueue = 0; fr = 0; en = 0; unsure = 0
        return out
    }
}

/** How a transcript is called where it has no title of its own. */
object TranscriptTitles {
    private val DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US)

    /** "Conversation · 11 Oct, 14:02", or the title you gave it. */
    fun shown(meta: TranscriptMeta, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        meta.title.trim().ifEmpty { "Conversation · " + DAY.format(java.time.Instant.ofEpochMilli(meta.createdAt).atZone(zone)) }

    /** A title as you typed it: one line, at most 80 characters. */
    fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(80)

    /** "12 min", "1 h 05", "under a minute". */
    fun duration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m == 0L -> "under a minute"
            m < 60 -> "$m min"
            else -> String.format(Locale.US, "%d h %02d", m / 60, m % 60)
        }
    }
}
