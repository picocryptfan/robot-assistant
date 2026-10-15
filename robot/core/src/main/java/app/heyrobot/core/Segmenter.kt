// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. How conversation mode cuts speech into segments to decode (docs/conversation-mode.md 2.1, 2.2, 1.3). Fed one
// VAD frame at a time (512 samples, 32 ms at 16 kHz): whether the frame is speech, how loud it is, and whether Robot is
// talking. A segment closes after 0.25 s of silence (not the 0.5 s questions use: one that starts in English and ends
// in French loses the English), is cut at 15 s at its quietest frame of the last 2 s, and gets 0.2 s of audio before
// and after it (never Robot's voice, never another segment's audio). Frames while Robot talks, and for 300 ms after,
// count as silence, so an open segment closes cleanly instead of swallowing her voice. Positions are samples from the
// session's start; the engine slices the audio from its ring buffer.

/** Conversation mode's segment rules (the question mode keeps its own). */
data class SegmentRules(
    val sampleRate: Int = 16_000,
    val frameSamples: Int = 512,
    val minSilenceMs: Int = 250,
    val minSpeechMs: Int = 250,
    val maxSpeechMs: Int = 15_000,
    val padMs: Int = 200,
    /** A segment at [maxSpeechMs] is cut at its quietest frame within this much of its end. */
    val cutWindowMs: Int = 2_000,
    /** Frames this long after Robot stops talking still count as silence. */
    val gateTailMs: Int = 300,
    /** [Segment.split] stays this far from either end of the speech. */
    val splitEdgeMs: Int = 500,
) {
    val frameMs: Double get() = frameSamples * 1000.0 / sampleRate

    fun samples(ms: Int): Long = ms.toLong() * sampleRate / 1000
}

/** Why a segment ended. */
enum class SegmentEnd {
    /** [SegmentRules.minSilenceMs] of silence. */
    SILENCE,
    /** Cut at [SegmentRules.maxSpeechMs]; the speech goes on in the next segment. */
    CAP,
    /** Robot started talking (her voice and its tail count as silence). */
    GATE,
    /** The session paused or stopped. */
    FLUSH,
}

/**
 * A stretch to decode: [start] to [end] with the padding, [speechStart] to [speechEnd] what the VAD heard (samples).
 * [speechMs]: speech frames in it. [split]: its quietest frame away from both ends of the speech, where [Rehear] cuts
 * it in two (null when the speech is too short to split).
 */
data class Segment(val start: Long, val end: Long, val speechStart: Long, val speechEnd: Long, val speechMs: Int, val ended: SegmentEnd, val split: Long?)

class Segmenter(val rules: SegmentRules = SegmentRules()) {
    private val fs = rules.frameSamples.toLong()
    private val minSilenceFrames = Math.ceil(rules.minSilenceMs / rules.frameMs).toInt()
    private val minSpeechFrames = Math.ceil(rules.minSpeechMs / rules.frameMs).toInt()
    private val maxFrames = (rules.maxSpeechMs / rules.frameMs).toInt()
    private val cutFrames = (rules.cutWindowMs / rules.frameMs).toInt().coerceAtLeast(1)
    private val tailFrames = Math.ceil(rules.gateTailMs / rules.frameMs).toInt()
    private val edgeFrames = Math.ceil(rules.splitEdgeMs / rules.frameMs).toInt()
    private val pad = rules.samples(rules.padMs)

    /** Frames fed so far (the next frame's index). */
    var frames: Long = 0
        private set
    private var lastRobot = Long.MIN_VALUE / 2
    /** Where the latest gated stretch (Robot talking, and its tail) began and ended, in samples. */
    private var gateStart = Long.MAX_VALUE
    private var gateEnd = 0L
    private var wasGated = false
    /** Where the last segment handed out ended: the next one's padding never reaches back past it. */
    private var prevEnd = 0L

    // The open segment: its first frame, and each frame's speech flag and level from there on.
    private var openAt = -1L
    private val speech = ArrayList<Boolean>()
    private val levels = ArrayList<Float>()
    private var lastSpeech = -1L
    /** The open segment began where the previous one was cut at the cap (no padding before it). */
    private var continued = false

    val open: Boolean get() = openAt >= 0

    /** Where the open segment's audio starts (samples; its padding is not counted), for the live words; null when none is open. */
    val openStart: Long? get() = if (open) openAt * fs else null

    /** One frame: [isSpeech] the VAD's verdict, [level] its loudness (any scale), [robotSpeaking] Robot's voice is playing. */
    fun feed(isSpeech: Boolean, level: Float, robotSpeaking: Boolean): Segment? {
        val f = frames++
        if (robotSpeaking) lastRobot = f
        val gated = f - lastRobot <= tailFrames
        if (gated) {
            if (!wasGated) gateStart = f * fs
            gateEnd = (f + 1) * fs
        }
        wasGated = gated
        val heard = isSpeech && !gated
        if (!open) {
            if (!heard) return null
            openAt = f
            continued = false
        }
        speech += heard
        levels += level
        if (heard) lastSpeech = f
        val silence = f - lastSpeech
        if (silence >= minSilenceFrames) return close(if (gated) SegmentEnd.GATE else SegmentEnd.SILENCE, f)
        if (speech.size >= maxFrames) return cap()
        return null
    }

    /** The session paused or stopped: the open segment, if it holds enough speech. */
    fun flush(): Segment? = if (open) close(SegmentEnd.FLUSH, frames - 1) else null

    private fun close(why: SegmentEnd, now: Long): Segment? {
        val first = openAt + speech.indexOfFirst { it }.coerceAtLeast(0)
        val last = lastSpeech
        val count = speech.count { it }
        val split = splitFrame(first, last)
        val wasContinued = continued
        val openStart = openAt * fs
        reset()
        if (count < minSpeechFrames || last < first) return null
        val speechStart = first * fs
        val speechEnd = (last + 1) * fs
        // After a cut at the cap the audio runs on from the cut; otherwise the padding, kept clear of the last segment and of Robot's voice.
        val start = if (wasContinued) openStart else maxOf(speechStart - pad, prevEnd, gateEnd, 0L)
        val ceiling = if (why == SegmentEnd.GATE && gateStart >= speechEnd) gateStart else (now + 1) * fs
        val end = minOf(speechEnd + pad, ceiling).coerceAtLeast(speechEnd)
        prevEnd = end
        return Segment(start, end, speechStart, speechEnd, (count * rules.frameMs).toInt(), why, split)
    }

    /** At the cap: the quietest frame of the last [cutFrames] (a frame the VAD called silence first), never the open one's first. */
    private fun cap(): Segment? {
        val n = speech.size
        val from = maxOf(1, n - cutFrames)
        val quiet = (from until n).filter { !speech[it] }.ifEmpty { (from until n).toList() }
        val k = quiet.minWith(compareBy<Int> { levels[it] }.thenByDescending { it })
        val cutAt = openAt + k
        // The first part ends with the quiet frame; the rest stays open as a new segment, with no padding at the cut.
        val first = openAt + speech.indexOfFirst { it }.coerceAtLeast(0)
        val count = (0..k).count { speech[it] }
        val lastHeard = (k downTo 0).firstOrNull { speech[it] }?.let { openAt + it } ?: cutAt
        val split = splitFrame(first, lastHeard)
        val restSpeech = ArrayList(speech.subList(k + 1, n))
        val restLevels = ArrayList(levels.subList(k + 1, n))
        val start = if (continued) openAt * fs else maxOf(first * fs - pad, prevEnd, gateEnd, 0L)
        val end = (cutAt + 1) * fs
        speech.clear(); speech.addAll(restSpeech)
        levels.clear(); levels.addAll(restLevels)
        openAt = cutAt + 1
        continued = true
        prevEnd = end
        if (count < minSpeechFrames) return null
        return Segment(start, end, first * fs, end, (count * rules.frameMs).toInt(), SegmentEnd.CAP, split)
    }

    /** The quietest frame between [first] and [last], at least [edgeFrames] from both; null when there is no room. */
    private fun splitFrame(first: Long, last: Long): Long? {
        val lo = first + edgeFrames
        val hi = last - edgeFrames
        if (hi < lo) return null
        val k = (lo..hi).minByOrNull { levels[(it - openAt).toInt()] } ?: return null
        return k * fs
    }

    private fun reset() {
        openAt = -1
        speech.clear()
        levels.clear()
        lastSpeech = -1
        continued = false
    }
}

/**
 * The dropped-words check (docs/conversation-mode.md 2.2): Parakeet can lose the English half of a segment that switches
 * from English to French. A segment with at least [MIN_SPEECH_MS] of speech and fewer than [MIN_WORDS_PER_SECOND] words
 * per second of it is decoded again in two halves, split at [Segment.split]; the halves are kept only if they have more
 * words, and the line is marked "re-heard" (event 119).
 */
object Rehear {
    const val MIN_SPEECH_MS = 3_000
    const val MIN_WORDS_PER_SECOND = 0.9

    fun needed(speechMs: Int, words: Int): Boolean = speechMs >= MIN_SPEECH_MS && words < MIN_WORDS_PER_SECOND * speechMs / 1000.0

    /** Where to cut [s] for the second decode, or null when it can't be cut (too short). */
    fun splitAt(s: Segment): Long? = s.split?.takeIf { it > s.start && it < s.end }

    /** The halves' text replaces the first decode only with strictly more words. */
    fun keepHalves(wordsWhole: Int, wordsHalves: Int): Boolean = wordsHalves > wordsWhole

    /** Words in a decoded text, as the check counts them. */
    fun words(text: String): Int = text.split(Regex("\\s+")).count { w -> w.any(Char::isLetterOrDigit) }
}

/**
 * The transcript screen's paragraphs (docs/conversation-mode.md 5.3): lines of the same speaker in the same language
 * less than [GAP_S] apart read as one. The file keeps the lines; an unknown speaker ("?") never joins.
 */
object DisplayMerge {
    const val GAP_S = 1.5

    fun paragraphs(lines: List<TranscriptLine>): List<List<TranscriptLine>> {
        val out = ArrayList<MutableList<TranscriptLine>>()
        for (l in lines) {
            val p = out.lastOrNull()
            val prev = p?.last()
            if (prev != null && l.speaker == prev.speaker && l.speaker != SpeakerClusterer.UNKNOWN && l.lang == prev.lang && l.t0 - prev.t1 < GAP_S) p.add(l)
            else out += mutableListOf(l)
        }
        return out
    }
}
