// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ModelStrings
import app.heyrobot.core.i18n.Phrase
import java.util.Locale

// Pure. 1.2 (docs/plans/every-android-phone-1.2-design.md, Step 2): Robot's self-test of the GPU (and of a beta TPU or NPU
// build), before you rely on it. Three short turns in one conversation, so a GPU that breaks after a turn or two (the Mali
// bug, LiteRT-LM #1850 and #2421) shows here and not under your first question:
//   1. "Reply with the word ready." It must end normally with some text.
//   2. "What is 2 plus 3? Answer with a number." The answer must hold a 5.
//   3. "Set a timer for 5 minutes." It must end with a set_timer call (constrained decoding on the GPU).
// Each turn must end within [GpuCheck.TURN_MS], without garbage (:brain stops a garbled reply, DoneReason.GARBAGE), and the
// writing must not crawl ([GpuCheck.MIN_WRITE_TPS]). The prompts are fixed here, in English, and go only to :brain: never
// your words, memory, notes or conversations, and the timer call is never run. The result is kept per model, backend,
// model file, LiteRT-LM version and Android build ([CheckKey]), so an OS update (or a new engine or file) checks again.
// Kept in filesDir/robot/[CheckLog.FILE] by the voice process (BrainKeeper), its only writer; read by the setup guide.
// Only a [GpuVerdict.fault] (an engine error, a crash, a hang, garbage, an empty answer) moves the model off the device: those
// are the device misbehaving. A [GpuVerdict.miss] (a wrong or slow answer, no timer call) is how the model answered, which a
// working device gives now and then (the model samples, with no fixed seed, on the GPU and the TPU). A miss is kept in the
// record and the voice log, the guide shows it, the device stays in use, and the check goes on to its next turn, so a device
// that fails a later turn still shows here.

/** What the self-test found: [PASSED], or the first fault, else the first miss. [id] is what the files and the voice log keep. */
enum class GpuVerdict(override val id: Int, override val title: String) : LogCode {
    PASSED(0, "passed"),
    /** The model didn't load on it (the load's own failure is kept as before, GpuFailure or TpuFailure). */
    NOT_LOADED(1, "the model didn't load"),
    /** The engine answered a turn with an error. */
    ERROR(2, "engine error"),
    /** :brain stopped during a turn (a crash). */
    STOPPED(3, "the model process stopped"),
    /** A turn didn't end within [GpuCheck.TURN_MS]: a hang. */
    STALLED(4, "no answer in time"),
    /** The reply turned into garbage (repeated characters, pad tokens), or ran on to the length cap. */
    GARBAGE(5, "garbled answer"),
    /**
     * Turn 1 or 2 ended normally with no text. A fault: Robot's own models run without thinking (core Thinking), so a
     * working device always writes a word for "ready" or "2 plus 3"; a device whose output shows as nothing (it ends the
     * turn at once, or writes only tokens with no text, which never reach the garbage check) shows only here, and after the
     * check nothing else would catch it (an empty answer to your question moves nothing, and a miss isn't checked again
     * until Android's build changes).
     */
    EMPTY(6, "empty answer"),
    /** Turn 1 ended with a call instead of words, or turn 2 had no 5 for "2 plus 3". */
    WRONG(7, "wrong answer"),
    /** Turn 3 ended without a set_timer call. */
    NO_CALL(8, "no timer call"),
    /** It wrote under [GpuCheck.MIN_WRITE_TPS] tokens a second. */
    SLOW(9, "too slow");

    val passed: Boolean get() = this == PASSED

    /**
     * The device itself misbehaved: Robot moves the model off it (the CPU, or Fast's general build) and keeps the failure
     * (GpuFailure, TpuFailure) until you try it again or Android's build changes.
     */
    val fault: Boolean get() = when (this) {
        NOT_LOADED, ERROR, STOPPED, STALLED, GARBAGE, EMPTY -> true
        PASSED, WRONG, NO_CALL, SLOW -> false
    }

    /** It ran, but the model's answer was off or slow: kept in the check's record and the voice log only; the device stays. */
    val miss: Boolean get() = !passed && !fault

    /** [title] for the screen. */
    val titleText: Phrase get() = when (this) {
        PASSED -> ModelStrings.checkPassed()
        NOT_LOADED -> ModelStrings.checkNotLoaded()
        ERROR -> ModelStrings.failureEngine()
        STOPPED -> ModelStrings.failureProcess()
        STALLED -> ModelStrings.failureTimeout()
        GARBAGE -> ModelStrings.checkGarbage()
        EMPTY -> ModelStrings.checkEmpty()
        WRONG -> ModelStrings.checkWrong()
        NO_CALL -> ModelStrings.checkNoCall()
        SLOW -> ModelStrings.checkSlow()
    }

    companion object {
        fun of(id: Int): GpuVerdict? = entries.firstOrNull { it.id == id }
    }
}

/** How one turn of the check ended, as the voice process saw it. */
sealed class CheckTurn {
    /**
     * EV_DONE after [ms]: its [reason], the [text] it wrote, the [calls] it asked for (EV_TOOL_CALLS), whether its conversation
     * runs in text-call mode ([textCalls]), and its numbers (-1 unknown, as in BrainMsg.Done); [rebuilt]: it read its
     * conversation from the start.
     */
    data class Done(
        val reason: DoneReason, val text: String, val calls: List<RawToolCall>, val ms: Long, val textCalls: Boolean = false,
        val readTokens: Int = -1, val readTps: Double = -1.0, val writtenTokens: Int = -1, val writeTps: Double = -1.0, val rebuilt: Boolean = false,
    ) : CheckTurn()

    /** EV_ERROR; [stopped]: :brain's process went with it (the binding died) or the model's own process stopped. */
    data class Failed(val stopped: Boolean) : CheckTurn()

    /** Nothing ended it within [GpuCheck.TURN_MS]. */
    data object Stalled : CheckTurn()
}

/**
 * The check's outcome: [verdict], at turn [turn] (1 to 3; 0 when it passed), with the reading speed of the turn that read
 * the most ([readTps]) and the writing speed over every turn ([writeTps]), -1 where too few tokens were read or written to
 * time them; [reused]: turns 2 and 3 went on in the conversation turn 1 built (so a GPU that fails after a turn shows).
 */
data class CheckResult(val verdict: GpuVerdict, val turn: Int, val readTps: Double, val writeTps: Double, val reused: Boolean)

/**
 * What a check ran on: [model] on [backend], its file by the start of its SHA-256 ([file], [SpeedLog.FILE_HEX] hex digits),
 * LiteRT-LM's version ([engine]) and the phone's Android build ([build], as SpeedLog keeps it).
 */
data class CheckKey(val model: BrainModel, val backend: ModelBackend, val file: String, val engine: String, val build: String) {
    companion object {
        /** The key for [model] on [backend] with [sha256] on Android build [fingerprint]; null when either isn't known. */
        fun of(model: BrainModel, backend: ModelBackend, sha256: String?, fingerprint: String?, engine: String = GpuCheck.ENGINE): CheckKey? {
            val file = sha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { HEX.matches(it) }?.take(SpeedLog.FILE_HEX) ?: return null
            val build = SpeedLog.cleanBuild(fingerprint) ?: return null
            return CheckKey(model, backend, file, engine, build)
        }

        private val HEX = Regex("[0-9a-f]{${SpeedLog.FILE_HEX},64}")
    }
}

/** One check: its [key], what it found ([verdict] at [turn]), when ([time], wall clock ms), how long it took with the load ([ms]), and its speeds. */
data class CheckRecord(val key: CheckKey, val verdict: GpuVerdict, val turn: Int, val time: Long, val ms: Long, val readTps: Double, val writeTps: Double)

object GpuCheck {
    /** LiteRT-LM's version in :brain (brain/build.gradle; ProcessIsolationTest keeps the two the same): a new engine checks again. */
    const val ENGINE = "0.18.0"
    /** The check's own conversation in :brain, apart from the panel's. */
    const val CONV_ID = "robot-check"
    /** A turn must end within this (EST, the design's 30 s). */
    const val TURN_MS = 30_000L
    /** Writing slower than this, over enough tokens ([FLOOR_TOKENS]), fails it (EST). */
    const val MIN_WRITE_TPS = 2.0
    /** The speed floor needs this many tokens written over the three turns: "ready" and "5" alone are a token or two, noise. */
    const val FLOOR_TOKENS = 8
    /** What the guide and the panel say the check takes (EST), once the model is loaded. */
    const val EST_SECONDS = 30

    /** Its system prompt: fixed, and nothing of yours (no date, no name, no setting). */
    const val SYSTEM = "You are Robot's self-test on this phone. Follow each instruction exactly and answer as briefly as you can. " + // l10n: not text (a prompt only the model reads)
        "When asked to set a timer, call set_timer." // l10n: not text (a prompt only the model reads)

    /** The three turns, in order. */
    val TURNS: List<String> = listOf(
        "Reply with the word ready.", // l10n: not text (a prompt only the model reads)
        "What is 2 plus 3? Answer with a number.", // l10n: not text (a prompt only the model reads)
        "Set a timer for 5 minutes.", // l10n: not text (a prompt only the model reads)
    )

    /** The one tool it describes: set_timer, so turn 3 runs constrained decoding. */
    val TOOLS: List<String> = listOf(ToolName.SET_TIMER.id)

    /** Whether [model] on [backend] gets the check: the general build on the GPU, and a beta TPU (or NPU) build on its TPU. Never the CPU, never the Tensor G5's TPU. */
    fun applies(model: BrainModel, backend: ModelBackend): Boolean =
        (model.gpu && backend == ModelBackend.GPU) || (model.beta && backend == ModelBackend.TPU)

    /** Whether to run it now: it applies, the key is known, and no check ran with this key (a new OS build, engine or file). */
    fun needed(records: List<CheckRecord>, key: CheckKey?): Boolean =
        key != null && applies(key.model, key.backend) && records.none { it.key == key }

    /** The check of [key] already run, or null. */
    fun of(records: List<CheckRecord>, key: CheckKey?): CheckRecord? = key?.let { k -> records.lastOrNull { it.key == k } }

    /**
     * Turn [turn] (0 to 2) as :brain receives it: the fixed prompt, after the earlier turns and [replies] (what the model
     * wrote to them), in the check's own conversation, with set_timer only, no thinking, no skills, no image. The history
     * counts (0, 2, 4) let :brain go on in the conversation turn 1 built. A turn the model left without words (a call
     * instead of words: a miss; an empty answer is a fault and ends the check) is left out with its question, as the panel
     * leaves out an answer with no text: no empty answer ever reaches the engine, and :brain builds the conversation afresh
     * from what is left.
     */
    fun request(turn: Int, replies: List<String>): BrainMsg.Generate {
        require(turn in TURNS.indices && replies.size >= turn)
        val history = (0 until turn).filter { replies[it].isNotBlank() }
            .flatMap { listOf(PromptTurn(PromptRole.USER, TURNS[it]), PromptTurn(PromptRole.MODEL, replies[it])) }
        return BrainMsg.Generate(CONV_ID, SYSTEM, history, history.size, TURNS[turn], hasImage = false, tools = TOOLS, think = false)
    }

    /** "5" as a number of its own (not 15 or 55), or "five". */
    private val FIVE = Regex("""(?<![0-9.,])5(?![0-9])|\bfive\b""", RegexOption.IGNORE_CASE)

    /** What turn [turn] (0 to 2) says on its own: [GpuVerdict.PASSED] or how it failed (the speed floor is [judge]'s). */
    fun judgeTurn(turn: Int, t: CheckTurn): GpuVerdict = when (t) {
        CheckTurn.Stalled -> GpuVerdict.STALLED
        is CheckTurn.Failed -> if (t.stopped) GpuVerdict.STOPPED else GpuVerdict.ERROR
        is CheckTurn.Done -> when {
            t.ms > TURN_MS -> GpuVerdict.STALLED
            t.reason == DoneReason.GARBAGE || t.reason == DoneReason.LENGTH -> GpuVerdict.GARBAGE
            t.reason == DoneReason.CANCELLED || t.reason == DoneReason.CONTEXT_FULL -> GpuVerdict.ERROR
            turn == 2 -> if (timerCall(t)) GpuVerdict.PASSED else GpuVerdict.NO_CALL
            t.reason != DoneReason.END -> GpuVerdict.WRONG
            t.text.isBlank() -> GpuVerdict.EMPTY
            turn == 1 && !FIVE.containsMatchIn(t.text) -> GpuVerdict.WRONG
            else -> GpuVerdict.PASSED
        }
    }

    /** It asked for set_timer: through the tool channel, or (text-call mode) as a reply that is nothing but the call. */
    private fun timerCall(t: CheckTurn.Done): Boolean = when (t.reason) {
        DoneReason.TOOL_CALLS -> t.calls.any { it.name == ToolName.SET_TIMER.id }
        DoneReason.END -> t.textCalls && ToolCalls.wholeReplyCalls(t.text).any { it.call.name == ToolName.SET_TIMER.id }
        else -> false
    }

    /**
     * The verdict over the turns run so far, [turns] (stopped at the first fault): the first fault, else the first miss, else
     * the speed floor ([GpuVerdict.SLOW], a miss too) once all three ran, else passed.
     */
    fun judge(turns: List<CheckTurn>): CheckResult {
        val done = turns.filterIsInstance<CheckTurn.Done>()
        val read = done.filter { it.readTokens >= SpeedLog.MIN_READ_TOKENS && it.readTps > 0 }.maxByOrNull { it.readTokens }?.readTps ?: -1.0
        val timed = done.filter { it.writtenTokens > 0 && it.writeTps > 0 && it.writeTps.isFinite() }
        val written = timed.sumOf { it.writtenTokens }
        val seconds = timed.sumOf { it.writtenTokens / it.writeTps }
        val rate = if (seconds > 0) written / seconds else -1.0
        val reused = done.drop(1).none { it.rebuilt }
        fun result(v: GpuVerdict, turn: Int) = CheckResult(v, turn, SpeedLog.round1(read),
            if (written >= SpeedLog.MIN_WRITE_TOKENS && rate > 0) SpeedLog.round1(rate) else -1.0, reused)
        val verdicts = turns.mapIndexed { i, t -> judgeTurn(i, t) }
        verdicts.indexOfFirst { it.fault }.takeIf { it >= 0 }?.let { return result(verdicts[it], it + 1) }
        if (turns.size < TURNS.size) return result(GpuVerdict.ERROR, turns.size + 1)
        verdicts.indexOfFirst { it.miss }.takeIf { it >= 0 }?.let { return result(verdicts[it], it + 1) }
        if (written >= FLOOR_TOKENS && rate > 0 && rate < MIN_WRITE_TPS) return result(GpuVerdict.SLOW, TURNS.size)
        return result(GpuVerdict.PASSED, 0)
    }

    /**
     * Runs the three turns through [ask] (the voice process sends each to :brain and says how it ended), stopping at the
     * first fault (the device misbehaved: :brain may be gone or stuck) and going on after a miss; then [judge]. Each turn
     * after the first is sent with what the model wrote before ([request]).
     */
    suspend fun run(ask: suspend (turn: Int, request: BrainMsg.Generate) -> CheckTurn): CheckResult {
        val turns = ArrayList<CheckTurn>()
        val replies = ArrayList<String>()
        for (i in TURNS.indices) {
            val t = ask(i, request(i, replies))
            turns += t
            if (judgeTurn(i, t).fault) break
            // Only a turn that ended (CheckTurn.Done) is passed or a miss: a stall or an error is a fault.
            replies += (t as CheckTurn.Done).text.trim()
        }
        return judge(turns)
    }
}

/**
 * The checks run on this phone (filesDir/robot/[FILE]): one [CheckRecord] per line, codes and numbers only, the newest
 * last, the last one for each model and backend kept ([KEEP] at most). One writer, the voice process; the setup guide
 * reads it; "Try the GPU again" (or the TPU) deletes it, so the next load checks again.
 */
object CheckLog {
    const val FILE = "gpu_check.txt"
    const val KEEP = 4
    private const val HEADER = "robot-model-check 1" // l10n: not text (a file's header)
    /** A few short lines: a file far past this isn't Robot's, and reads as empty. */
    const val MAX_CHARS = 8 * 1024

    /** Every record in [text], oldest first; a missing, foreign or oversized file is empty, and a damaged line is skipped. */
    fun decode(text: String?): List<CheckRecord> {
        if (text == null || text.length > MAX_CHARS) return emptyList()
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull(::line)
    }

    /** [text] with [r] added: it replaces any record of the same model and backend, and the newest [KEEP] stay. */
    fun add(text: String?, r: CheckRecord): String {
        val all = (decode(text).filter { it.key.model != r.key.model || it.key.backend != r.key.backend } + r).takeLast(KEEP)
        return HEADER + "\n" + all.joinToString("") { lineOf(it) + "\n" }
    }

    private fun lineOf(r: CheckRecord): String = listOf(
        r.key.model.id, r.key.backend.id, r.key.file, r.key.engine, r.key.build, r.verdict.id.toString(), r.turn.toString(), r.time.toString(),
        r.ms.toString(), fmt(r.readTps), fmt(r.writeTps),
    ).joinToString(" ")

    private fun fmt(x: Double): String = if (x > 0) String.format(Locale.ROOT, "%.1f", x) else "-1"

    private fun line(l: String): CheckRecord? {
        val p = l.trim().split(' ')
        if (p.size != 11) return null
        val model = BrainModel.byId(p[0]) ?: return null
        val backend = ModelBackend.of(p[1]) ?: return null
        if (!FILE_KEY.matches(p[2]) || !ENGINE_KEY.matches(p[3])) return null
        val build = SpeedLog.cleanBuild(p[4])?.takeIf { it == p[4] } ?: return null
        val verdict = p[5].toIntOrNull()?.let(GpuVerdict::of) ?: return null
        val turn = p[6].toIntOrNull()?.takeIf { it in 0..GpuCheck.TURNS.size } ?: return null
        if (verdict.passed != (turn == 0) && verdict != GpuVerdict.NOT_LOADED) return null
        val time = p[7].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val ms = p[8].toLongOrNull()?.takeIf { it in 0..MAX_MS } ?: return null
        val read = speed(p[9]) ?: return null
        val write = speed(p[10]) ?: return null
        return CheckRecord(CheckKey(model, backend, p[2], p[3], build), verdict, turn, time, ms, read, write)
    }

    /** A speed as kept: -1 (unknown) or a rate in range. */
    private fun speed(s: String): Double? {
        val x = s.toDoubleOrNull() ?: return null
        return if (x == -1.0) -1.0 else BrainMsg.Done.Speed.rate(x).takeIf { it > 0 }?.let(SpeedLog::round1)
    }

    /** Longer than a load (5 minutes) and three turns could ever take. */
    private const val MAX_MS = 3_600_000L
    private val FILE_KEY = Regex("[0-9a-f]{${SpeedLog.FILE_HEX}}")
    private val ENGINE_KEY = Regex("""[0-9][0-9A-Za-z.\-]{0,30}""")
}
