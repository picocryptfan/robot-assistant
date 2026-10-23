// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.util.Locale

// Pure. 1.2 (docs/plans/every-android-phone-1.2-design.md, Step 5): how fast the model really is on this phone. Each panel
// reply's numbers (BrainMsg.Done: how many tokens it read and how fast, how many it wrote and how fast, its time to the
// first word) are kept per model, backend, model file and Android build, so a new OS build or another file is measured
// afresh. Numbers only: no words of a question or a reply, no times of day. Kept in filesDir/robot/[SpeedLog.FILE],
// written by the voice process alone (BrainKeeper), read by Settings › Model; never in a backup (BackupCategory names its
// files one by one, and not this one), never sent anywhere.

/**
 * What a measured reply ran on: [model] on [backend], the file by the start of its SHA-256 ([file], [SpeedLog.FILE_HEX]
 * hex digits) and the phone's Android build ([build], Build.FINGERPRINT).
 */
data class SpeedKey(val model: BrainModel, val backend: ModelBackend, val file: String, val build: String) {
    companion object {
        /**
         * The key for [model] on [backend] whose file has [sha256], on Android build [fingerprint]; null when the file's hash
         * isn't known (your own file before its check) or the build is empty.
         */
        fun of(model: BrainModel, backend: ModelBackend, sha256: String?, fingerprint: String?): SpeedKey? {
            val file = sha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { HEX.matches(it) }?.take(SpeedLog.FILE_HEX) ?: return null
            val build = SpeedLog.cleanBuild(fingerprint) ?: return null
            return SpeedKey(model, backend, file, build)
        }

        private val HEX = Regex("[0-9a-f]{${SpeedLog.FILE_HEX},64}")
    }
}

/**
 * One reply's numbers: [readTokens] read at [readTps] tokens a second, [writtenTokens] written at [writeTps], [firstWordMs]
 * to its first word, and whether it read its conversation from the start ([rebuilt]). Rates to 0.1 token a second, times in
 * whole milliseconds.
 */
data class SpeedSample(
    val readTokens: Int, val readTps: Double, val writtenTokens: Int, val writeTps: Double, val firstWordMs: Long, val rebuilt: Boolean,
)

/**
 * The medians of a key's last [replies] replies: [readTps] over [readReplies] of them that read enough to time it, [writeTps]
 * over [writeReplies] that wrote enough, and [firstWordMs] over the ones that read their conversation from the start (null:
 * none).
 */
data class MeasuredSpeed(
    val readTps: Double, val writeTps: Double, val readReplies: Int, val writeReplies: Int, val firstWordMs: Long?, val replies: Int,
)

object SpeedLog {
    const val FILE = "speeds.txt"
    /** The last replies kept for each key. */
    const val PER_KEY = 20
    /** The keys kept, the most recently measured: an OS update or another file starts a new one, the oldest goes. */
    const val KEYS = 8
    /** Replies a figure needs before Settings shows it as measured on this phone (design Step 5). */
    const val MIN_REPLIES = 3
    /**
     * A reply that read fewer tokens than this doesn't time the reading: a reused conversation reads only the new turn (tens
     * of tokens), where the fixed cost of a step outweighs the reading. A new conversation reads about 1,500.
     */
    const val MIN_READ_TOKENS = 100
    /** A reply that wrote fewer tokens than this (an action's call, "OK") doesn't time the writing. */
    const val MIN_WRITE_TOKENS = 16
    /** The hex digits of the file's SHA-256 a key keeps. */
    const val FILE_HEX = 12
    /** At most [KEYS] × [PER_KEY] lines of about 150 characters: a file far past that isn't Robot's, and is read as empty. */
    const val MAX_CHARS = 64 * 1024
    /** Android's build fingerprint, at most this long in a key. */
    const val MAX_BUILD = 120
    private const val HEADER = "robot-speeds 1" // l10n: not text (a file's header)

    /** Android's build fingerprint as a key keeps it: one word, no control characters, at most [MAX_BUILD] characters. */
    fun cleanBuild(fingerprint: String?): String? =
        fingerprint?.filter { !it.isISOControl() && !it.isWhitespace() }?.take(MAX_BUILD)?.ifEmpty { null }

    /**
     * The numbers of a finished panel reply worth keeping, or null: one that ended as a reply does (its end, its actions, or
     * its length cap), from a :brain that measured it (1.2's; an older one sends -1).
     */
    fun sample(d: BrainMsg.Done): SpeedSample? {
        if (d.reason != DoneReason.END && d.reason != DoneReason.TOOL_CALLS && d.reason != DoneReason.LENGTH) return null
        if (d.prefillTokens < 0 || d.prefillTps <= 0.0 || d.decodeTokens < 0 || !d.decodeTps.isFinite() || d.decodeTps <= 0.0 || d.ttftMs !in 0..MAX_FIRST_WORD_MS) return null
        return SpeedSample(d.prefillTokens, round1(d.prefillTps), d.decodeTokens, round1(d.decodeTps.coerceAtMost(BrainMsg.Done.Speed.MAX_TPS)),
            d.ttftMs, d.rebuilt)
    }

    /** Every reply in [text], oldest first; a missing, foreign or oversized file is empty, and a damaged line is skipped. */
    fun decode(text: String?): List<Pair<SpeedKey, SpeedSample>> {
        if (text == null || text.length > MAX_CHARS) return emptyList()
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull(::line)
    }

    /** [text]'s replies with [s] added for [key]: the last [PER_KEY] of each key, the [KEYS] keys measured most recently. */
    fun add(text: String?, key: SpeedKey, s: SpeedSample): String = encode(decode(text) + (key to s))

    /** The medians for [key] in [text], or null until it has [MIN_REPLIES] replies that time both the reading and the writing. */
    fun measured(text: String?, key: SpeedKey?): MeasuredSpeed? = measured(decode(text), key)

    fun measured(all: List<Pair<SpeedKey, SpeedSample>>, key: SpeedKey?): MeasuredSpeed? {
        key ?: return null
        val mine = all.filter { it.first == key }.map { it.second }
        val read = mine.filter { it.readTokens >= MIN_READ_TOKENS }.map { it.readTps }
        val write = mine.filter { it.writtenTokens >= MIN_WRITE_TOKENS }.map { it.writeTps }
        if (read.size < MIN_REPLIES || write.size < MIN_REPLIES) return null
        val first = mine.filter { it.rebuilt }.map { it.firstWordMs.toDouble() }
        return MeasuredSpeed(round1(median(read)), round1(median(write)), read.size, write.size,
            if (first.isEmpty()) null else Math.round(median(first)), mine.size)
    }

    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        require(n > 0)
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** To 0.1. */
    fun round1(x: Double): Double = Math.round(x * 10.0) / 10.0

    private fun encode(all: List<Pair<SpeedKey, SpeedSample>>): String {
        // The keys by their latest reply, newest last; each keeps its own last replies, in the order they came.
        val keep = all.withIndex().groupBy { it.value.first }.mapValues { (_, v) -> v.takeLast(PER_KEY) }
            .entries.sortedBy { it.value.last().index }.takeLast(KEYS)
            .flatMap { it.value }.sortedBy { it.index }.map { it.value }
        return HEADER + "\n" + keep.joinToString("") { (k, s) -> lineOf(k, s) + "\n" }
    }

    private fun lineOf(k: SpeedKey, s: SpeedSample): String = listOf(
        k.model.id, k.backend.id, k.file, k.build, if (s.rebuilt) "1" else "0",
        s.readTokens.toString(), fmt(s.readTps), s.writtenTokens.toString(), fmt(s.writeTps), s.firstWordMs.toString(),
    ).joinToString(" ")

    private fun fmt(x: Double): String = String.format(Locale.ROOT, "%.1f", x)

    private fun line(l: String): Pair<SpeedKey, SpeedSample>? {
        val p = l.trim().split(' ')
        if (p.size != 10) return null
        val model = BrainModel.byId(p[0]) ?: return null
        val backend = ModelBackend.of(p[1]) ?: return null
        if (!FILE_KEY.matches(p[2])) return null
        val build = cleanBuild(p[3])?.takeIf { it == p[3] } ?: return null
        val rebuilt = when (p[4]) { "1" -> true; "0" -> false; else -> return null }
        val readTokens = p[5].toIntOrNull()?.let(BrainMsg.Done.Speed::count)?.takeIf { it >= 0 } ?: return null
        val readTps = p[6].toDoubleOrNull()?.let(BrainMsg.Done.Speed::rate)?.takeIf { it > 0 } ?: return null
        val writtenTokens = p[7].toIntOrNull()?.let(BrainMsg.Done.Speed::count)?.takeIf { it >= 0 } ?: return null
        val writeTps = p[8].toDoubleOrNull()?.let(BrainMsg.Done.Speed::rate)?.takeIf { it > 0 } ?: return null
        val firstWordMs = p[9].toLongOrNull()?.takeIf { it in 0..MAX_FIRST_WORD_MS } ?: return null
        return SpeedKey(model, backend, p[2], build) to SpeedSample(readTokens, round1(readTps), writtenTokens, round1(writeTps), firstWordMs, rebuilt)
    }

    /** Longer than the panel ever waits for a first word (PanelStream gives up after a minute without one). */
    private const val MAX_FIRST_WORD_MS = 600_000L
    private val FILE_KEY = Regex("[0-9a-f]{$FILE_HEX}")
}
