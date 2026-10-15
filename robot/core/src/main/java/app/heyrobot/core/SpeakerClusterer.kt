// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import kotlin.math.sqrt

// Pure. Who is speaking (docs/conversation-mode.md 3.2, 3.3): online clustering of one speaker embedding per segment
// (192 numbers from TitaNet-S in :voice, a later phase). A segment joins the nearest speaker when its cosine is at least
// [SpeakerClusterer.joinAt], starts a new speaker below [SpeakerClusterer.newBelow], and in between is labelled with
// the nearest without changing it (provisional). A segment under 1 s of speech has too little voice for that: it
// inherits the previous speaker when it follows within 1 s, else "?". Past lines are never relabelled here: merging,
// renaming, "this is me" and moving a line are your actions, written to the transcript as events (TranscriptCodec).
// Embeddings stay in memory only and are dropped with the session; only an enrolled "me" voiceprint (stored by :voice,
// encrypted) seeds the fixed speaker "Me".

/** How a segment got its speaker. */
enum class SpeakerKind {
    /** Near enough to a speaker: it joined (and refined) them. */
    JOINED,
    /** Far from everyone: a new speaker. */
    NEW,
    /** In between: labelled with the nearest speaker, who is left unchanged. */
    PROVISIONAL,
    /** Far from everyone but the session already has its most speakers: the nearest. */
    CAPPED,
    /** Too short to tell: the previous speaker, who spoke just before. */
    INHERITED,
    /** Too short to tell, and nobody just before: "?". */
    UNKNOWN,
}

/** One segment's speaker: [label] ("S1", "Me", "?"), how it was found, and its cosine to that speaker (0 when none). */
data class SpeakerTurn(val label: String, val kind: SpeakerKind, val similarity: Float = 0f)

/**
 * [maxSpeakers]: 2 in the Interpreter, up to 6 in Transcribe (setting). [mark]: added to every new label, "′" for a
 * session resumed after a crash, whose old speakers are gone from memory ("S1′"; merges are offered at the end).
 */
class SpeakerClusterer(
    val maxSpeakers: Int = 6,
    val joinAt: Float = JOIN_AT,
    val newBelow: Float = NEW_BELOW,
    val mark: String = "",
) {
    private class Cluster(val label: String, var centroid: FloatArray, var count: Int, val fixed: Boolean)

    private val clusters = ArrayList<Cluster>()
    /**
     * The sum of every embedding labelled with each speaker so far (unit vectors), for the merge proposals: bounded by
     * the speakers, not the length of the session. Memory only.
     */
    private val sums = LinkedHashMap<String, FloatArray>()
    private var next = 1
    private var lastLabel: String? = null
    private var lastEndMs = Long.MIN_VALUE / 2

    /** The speakers so far, oldest first. */
    val labels: List<String> get() = clusters.map { it.label }

    /** "Me" was seeded from your voiceprint. */
    val seeded: Boolean get() = clusters.any { it.fixed }

    /** Seeds the fixed speaker "Me" from your enrolled voiceprint (its centroid never moves). */
    fun seedMe(voiceprint: FloatArray) {
        if (seeded) return
        clusters.add(0, Cluster(ME, unit(voiceprint), 1, fixed = true))
    }

    /**
     * The speaker of one segment: its [embedding] (null when none was made), [speechMs] of speech in it, and where it
     * starts and ends on the session clock (ms).
     */
    fun assign(embedding: FloatArray?, speechMs: Int, startMs: Long, endMs: Long): SpeakerTurn {
        val turn = if (embedding == null || speechMs < MIN_SPEECH_MS) {
            val prev = lastLabel
            if (prev != null && prev != UNKNOWN && startMs - lastEndMs < INHERIT_GAP_MS) SpeakerTurn(prev, SpeakerKind.INHERITED)
            else SpeakerTurn(UNKNOWN, SpeakerKind.UNKNOWN)
        } else place(unit(embedding))
        lastLabel = turn.label
        lastEndMs = endMs
        return turn
    }

    private fun place(e: FloatArray): SpeakerTurn {
        val best = clusters.maxByOrNull { dot(it.centroid, e) }
        val sim = best?.let { dot(it.centroid, e) } ?: 0f
        val turn = when {
            best == null || (sim < newBelow && clusters.size < maxSpeakers) -> {
                val c = Cluster("S${next++}$mark", e, 1, fixed = false)
                clusters += c
                SpeakerTurn(c.label, SpeakerKind.NEW, sim)
            }
            sim >= joinAt -> {
                if (!best.fixed) {
                    best.centroid = unit(FloatArray(e.size) { best.centroid[it] * best.count + e[it] })
                    best.count++
                }
                SpeakerTurn(best.label, SpeakerKind.JOINED, sim)
            }
            sim < newBelow -> SpeakerTurn(best.label, SpeakerKind.CAPPED, sim)
            else -> SpeakerTurn(best.label, SpeakerKind.PROVISIONAL, sim)
        }
        add(turn.label, e)
        return turn
    }

    private fun add(label: String, e: FloatArray) {
        val s = sums.getOrPut(label) { FloatArray(e.size) }
        for (i in 0 until minOf(s.size, e.size)) s[i] += e[i]
    }

    /**
     * "S3 is S1": [from]'s voice joins [to], and later segments that sound like either get [to]. Already written
     * lines change only through the transcript's merge event. "Me" can't be merged into someone else.
     */
    fun merge(from: String, to: String): Boolean {
        val a = clusters.firstOrNull { it.label == from } ?: return false
        val b = clusters.firstOrNull { it.label == to } ?: return false
        if (a === b || a.fixed) return false
        if (!b.fixed) {
            b.centroid = unit(FloatArray(b.centroid.size) { b.centroid[it] * b.count + a.centroid[it] * a.count })
            b.count += a.count
        }
        clusters.remove(a)
        sums.remove(from)?.let { add(to, it) }
        if (lastLabel == from) lastLabel = to
        return true
    }

    /** You moved a line to [label] (or said "this is me"): its [embedding] joins that speaker, if they exist and can learn. */
    fun teach(label: String, embedding: FloatArray): Boolean {
        val c = clusters.firstOrNull { it.label == label } ?: return false
        val e = unit(embedding)
        if (!c.fixed) {
            c.centroid = unit(FloatArray(e.size) { c.centroid[it] * c.count + e[it] })
            c.count++
        }
        add(label, e)
        return true
    }

    /**
     * At the end of a session: pairs of speakers whose voices are as close as one speaker's ("S3 looks like S1 —
     * merge?"), the newer first, nearest pairs first. Only proposals: nothing is relabelled.
     */
    fun proposals(threshold: Float = joinAt): List<Pair<String, String>> {
        val means = sums.mapValues { unit(it.value) }
        val order = clusters.map { it.label }.filter { it in means }
        val out = ArrayList<Triple<String, String, Float>>()
        for (i in order.indices) for (j in i + 1 until order.size) {
            val sim = dot(means.getValue(order[i]), means.getValue(order[j]))
            // Never propose merging "Me" into another speaker: the other way round.
            if (sim >= threshold) out += Triple(order[j], order[i], sim)
        }
        return out.sortedByDescending { it.third }.map { (a, b) -> if (a == ME) b to a else a to b }
    }

    /**
     * You gave a line to a label of your own ("someone else", S4): later speakers never get it. Only plain labels of this
     * session's form count ("S4" with this clusterer's [mark]).
     */
    fun reserve(label: String) {
        val n = Regex("^S([1-9][0-9]{0,2})${Regex.escape(mark)}$").matchEntire(label)?.groupValues?.get(1)?.toInt() ?: return
        if (n >= next) next = n + 1
    }

    /**
     * You deleted your voiceprint during the session: the fixed "Me" goes (nothing made from the voiceprint stays in
     * memory). Lines heard since are compared with the others again; the lines already labelled keep "Me".
     */
    fun forgetMe(): Boolean {
        val me = clusters.firstOrNull { it.fixed } ?: return false
        clusters.remove(me)
        if (lastLabel == me.label) lastLabel = null
        return true
    }

    /** A speaker's centroid (a copy), for the tests. */
    internal fun centroidOf(label: String): FloatArray? = clusters.firstOrNull { it.label == label }?.centroid?.copyOf()

    /** Forget every embedding (the session ended); "Me" stays seeded only through the stored voiceprint. */
    fun clear() {
        clusters.clear()
        sums.clear()
        next = 1
        lastLabel = null
    }

    companion object {
        const val ME = "Me"
        const val UNKNOWN = "?"
        /** Starting thresholds (the measured 1.5 s EER thresholds were 0.32 to 0.34); tuned on the phone. */
        const val JOIN_AT = 0.45f
        const val NEW_BELOW = 0.30f
        /** A segment with less speech than this has no embedding worth comparing. */
        const val MIN_SPEECH_MS = 1_000
        /** ... and inherits the previous speaker when it starts less than this after them. */
        const val INHERIT_GAP_MS = 1_000L

        fun unit(v: FloatArray): FloatArray {
            val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            return if (n > 0f) FloatArray(v.size) { v[it] / n } else v.copyOf()
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
            return s
        }
    }
}
