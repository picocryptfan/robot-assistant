// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ModelStrings
import app.heyrobot.core.i18n.Phrase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure (0.4.7). When the model fails on the GPU, the CPU or the TPU: the GPU failure Robot remembers (so a GPU that
// crashes is never tried again by itself), the problem note the voice process keeps (codes and numbers only), and the
// plain-text problem report the main app builds from it for you to copy or share. Nothing is ever sent by itself.
// 0.6.0: the voice process knows each request's stage from :brain's events, one-shots (translations, summary steps) as
// much as the panel's questions ([RequestStages]), and a note says which process failed, :brain (LiteRT-LM: Fast, the
// E4B models, your .litertlm file) or llama.cpp's isolated :llama (your GGUF file) ([ModelProcess]).

/** Where the model was when it failed. */
enum class ProblemStage(override val id: Int, override val title: String) : LogCode {
    LOADING(1, "loading"), READING(2, "reading the prompt"), WRITING(3, "writing the answer"), IDLE(4, "loaded, between answers"),
    ;

    /** [title] for the screen (the voice log and the report keep [title]'s English). */
    val titleText: Phrase get() = when (this) {
        LOADING -> ModelStrings.stageLoading()
        READING -> ModelStrings.stageReading()
        WRITING -> ModelStrings.stageWriting()
        IDLE -> ModelStrings.stageIdle()
    }

    /** "while writing the answer", for the screen. */
    val whileText: Phrase get() = when (this) {
        LOADING -> ModelStrings.whileLoading()
        READING -> ModelStrings.whileReading()
        WRITING -> ModelStrings.whileWriting()
        IDLE -> ModelStrings.whileIdle()
    }
}

/** [LoadFailure.title] for the screen (the voice log and the report keep the English). */
val LoadFailure.titleText: Phrase get() = when (this) {
    LoadFailure.NO_FILE -> ModelStrings.failureNoFile()
    LoadFailure.NOT_ALLOWED -> ModelStrings.failureNotAllowed()
    LoadFailure.NO_SPACE -> ModelStrings.failureNoSpace()
    LoadFailure.ENGINE -> ModelStrings.failureEngine()
    LoadFailure.STOPPING -> ModelStrings.failureStopping()
    LoadFailure.TIMEOUT -> ModelStrings.failureTimeout()
    LoadFailure.PROCESS -> ModelStrings.failureProcess()
}

/** "The GPU didn't load (engine error)", "The TPU stopped while loading": a failure of [device] at [stage], for the screen. */
fun deviceFailureText(device: ModelBackend, stage: ProblemStage, failure: LoadFailure?): Phrase = when {
    failure != null && stage == ProblemStage.LOADING -> ModelStrings.deviceDidntLoad("device" to device.title, "failure" to failure.titleText)
    failure != null -> ModelStrings.deviceFailedWhile("device" to device.title, "while" to stage.whileText, "failure" to failure.titleText)
    else -> ModelStrings.deviceStoppedWhile("device" to device.title, "while" to stage.whileText)
}

/**
 * The process a model problem happened in (0.6.0): :brain, where LiteRT-LM runs Robot's models and your .litertlm file
 * (on the TPU, the GPU or the CPU), or :llama, llama.cpp's isolated process for your GGUF file (on the CPU). A GGUF file's
 * crash is never Fast's, nor the GPU's. [suffix]: the end of its process name, as Android's exit records give it.
 */
enum class ModelProcess(override val id: Int, override val title: String, val suffix: String) : LogCode {
    BRAIN(1, ":brain", ":brain"), LLAMA(2, ":llama, an isolated process (your GGUF file, never Fast)", ":llama");

    companion object {
        /** Where a model of [format] runs. */
        fun of(format: ModelFormat): ModelProcess = if (format == ModelFormat.GGUF) LLAMA else BRAIN
    }
}

/**
 * Which stage each request to the model is at, from :brain's events (0.6.0, pure): the panel's questions, their tool
 * results and the one-shots (translations, summary steps) alike, as :brain's queue runs them. A request is on the model
 * from EV_QUEUED 0 (it started: reading its prompt) and writing from its first chunk; while it waits in the queue
 * (EV_QUEUED 1 and up, also after giving way), after its tool calls and after its end, it isn't (null). Several may be
 * tracked at once (one runs, others wait): the model is at the furthest stage of any ([now]).
 */
object RequestStages {
    /** The requests the model answers (a load, a status, a cancel aren't tracked). */
    fun tracked(what: Int): Boolean =
        what == BrainProtocol.REQ_GENERATE || what == BrainProtocol.REQ_TOOL_RESULT || what == BrainProtocol.REQ_ONE_SHOT

    /** A request at [stage] after event [what] ([queued]: EV_QUEUED's position, else ignored). */
    fun after(stage: ProblemStage?, what: Int, queued: Int = -1): ProblemStage? = when {
        what == BrainProtocol.EV_QUEUED -> if (queued == 0) ProblemStage.READING else null
        what == BrainProtocol.EV_CHUNK -> ProblemStage.WRITING
        what == BrainProtocol.EV_TOOL_CALLS || what in BrainProtocol.TERMINAL -> null
        else -> stage
    }

    /** Where the model is: writing if any request is, else reading if any is, else null (between answers, or only waiting ones). */
    fun now(stages: Collection<ProblemStage>): ProblemStage? = when {
        ProblemStage.WRITING in stages -> ProblemStage.WRITING
        ProblemStage.READING in stages -> ProblemStage.READING
        else -> null
    }
}

/**
 * The GPU failed for [model] at [time] (wall clock ms): it didn't load ([failure]) or its process stopped ([failure]
 * null) while it loaded or answered. Kept in filesDir/robot/[FILE] by the voice process; while it is there Robot runs
 * that model on the CPU, until you choose "Try the GPU again" in Settings › Model (the main app deletes it).
 * 1.2 (design Step 2): [build] is the Android build it failed on (SpeedLog.cleanBuild of Build.FINGERPRINT), so a system
 * update gives the GPU another chance ([inForce]); a record from 1.1 has none ("unknown"): it stays in force, and the voice
 * process writes the build it first sees into it ([stamped]), so the next update drops it. [check]: the self-test found it
 * (core GpuCheck), null when a load, a question or a crash did; only a fault (GpuVerdict.fault) is ever one, never a miss.
 */
data class GpuFailure(val model: BrainModel, val time: Long, val stage: ProblemStage, val failure: LoadFailure?, val build: String? = null,
    val check: GpuVerdict? = null) {
    fun encode(): String = "${model.id} $time ${stage.id} ${failure?.id ?: 0} ${build ?: UNKNOWN} ${check?.id ?: 0}"

    /** "The GPU didn't load (engine error)", "The GPU failed Robot's check (garbled answer)", for the screen. */
    val titleText: Phrase get() = check?.let { ModelStrings.deviceCheckFailed("device" to ModelBackend.GPU.title, "why" to it.titleText) }
        ?: deviceFailureText(ModelBackend.GPU, stage, failure)

    /** It holds on Android build [fingerprint]: it failed there, or on a build 1.1 didn't record. */
    fun inForce(fingerprint: String?): Boolean = build == null || build == SpeedLog.cleanBuild(fingerprint)

    /** A 1.1 record with the build it is first seen on; any other as it is. */
    fun stamped(fingerprint: String?): GpuFailure = if (build == null) copy(build = SpeedLog.cleanBuild(fingerprint)) else this

    companion object {
        const val FILE = "gpu_failed.txt"
        /** No build known: a record from 1.1, or Android gave none. */
        const val UNKNOWN = "-"

        fun decode(text: String?): GpuFailure? {
            val p = text?.trim()?.split(' ') ?: return null
            if (p.size != 4 && p.size != 6) return null
            val m = BrainModel.byId(p[0]) ?: return null
            val t = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val st = p[2].toIntOrNull()?.let { id -> ProblemStage.entries.firstOrNull { it.id == id } } ?: return null
            val f = p[3].toIntOrNull() ?: return null
            val failure = if (f == 0) null else LoadFailure.entries.firstOrNull { it.id == f } ?: return null
            if (p.size == 4) return GpuFailure(m, t, st, failure)
            val build = buildOf(p[4]) ?: return null
            val check = checkOf(p[5]) ?: return null
            return GpuFailure(m, t, st, failure, build.value, check.value)
        }

        /** A record's build field: [UNKNOWN] or one SpeedLog keeps as it is (null: damaged). */
        internal fun buildOf(s: String): Box<String?>? = when {
            s == UNKNOWN -> Box(null)
            SpeedLog.cleanBuild(s) == s -> Box(s)
            else -> null
        }

        /**
         * A record's check field: 0 (not the check) or a fault (null: damaged). A miss never moves the model, so a record
         * that names one (none is written) reads as no failure, and the device is used.
         */
        internal fun checkOf(s: String): Box<GpuVerdict?>? {
            val id = s.toIntOrNull() ?: return null
            if (id == 0) return Box(null)
            return GpuVerdict.of(id)?.takeIf { it.fault }?.let { Box(it) }
        }
    }

    /** A decoded field that may be null. */
    internal class Box<T>(val value: T)
}

/**
 * 0.8: the beta TPU build ([BrainModel.beta], the Tensor G6's) didn't load on this phone's TPU at [time] ([failure];
 * null: its process stopped). Kept in filesDir/robot/[FILE] by the voice process; while it is there Fast is the general
 * build on the CPU (ModelCatalogue.fast), until you choose "Try the TPU again" in Settings › Model (the main app deletes it).
 * 1.2 (design Step 2): the NPU's record too, with GpuFailure's rule: [build] it failed on ([inForce]; a 0.8 record has
 * none and is [stamped]), and [check], what the self-test found when it was the check (core GpuCheck).
 */
data class TpuFailure(val model: BrainModel, val time: Long, val failure: LoadFailure?, val build: String? = null, val check: GpuVerdict? = null) {
    fun encode(): String = "${model.id} $time ${failure?.id ?: 0} ${build ?: GpuFailure.UNKNOWN} ${check?.id ?: 0}"

    /** "The TPU didn't load (engine error)", "The TPU failed Robot's check (no answer in time)", for the screen. */
    val titleText: Phrase get() = check?.let { ModelStrings.deviceCheckFailed("device" to ModelBackend.TPU.title, "why" to it.titleText) }
        ?: deviceFailureText(ModelBackend.TPU, ProblemStage.LOADING, failure)

    /** As [GpuFailure.inForce]. */
    fun inForce(fingerprint: String?): Boolean = build == null || build == SpeedLog.cleanBuild(fingerprint)

    /** As [GpuFailure.stamped]. */
    fun stamped(fingerprint: String?): TpuFailure = if (build == null) copy(build = SpeedLog.cleanBuild(fingerprint)) else this

    companion object {
        const val FILE = "tpu_failed.txt"

        fun decode(text: String?): TpuFailure? {
            val p = text?.trim()?.split(' ') ?: return null
            if (p.size != 3 && p.size != 5) return null
            val m = BrainModel.byId(p[0])?.takeIf { it.beta } ?: return null
            val t = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val f = p[2].toIntOrNull() ?: return null
            val failure = if (f == 0) null else LoadFailure.entries.firstOrNull { it.id == f } ?: return null
            if (p.size == 3) return TpuFailure(m, t, failure)
            val build = GpuFailure.buildOf(p[3]) ?: return null
            val check = GpuFailure.checkOf(p[4]) ?: return null
            return TpuFailure(m, t, failure, build.value, check.value)
        }
    }
}

/**
 * A GPU load under way (filesDir/robot/[FILE]): written by the voice process before it asks :brain to load on the GPU,
 * deleted when the load answers. One left by another voice process ([voicePid] isn't this one) means the load never
 * answered: the phone or Robot restarted in the middle of it, so the GPU counts as failed (never a loop of GPU loads
 * that take the phone down).
 */
data class GpuTrying(val model: BrainModel, val time: Long, val voicePid: Int) {
    fun encode(): String = "${model.id} $time $voicePid"

    companion object {
        const val FILE = "gpu_trying.txt"

        fun decode(text: String?): GpuTrying? {
            val p = text?.trim()?.split(' ') ?: return null
            if (p.size != 3) return null
            val m = BrainModel.byId(p[0]) ?: return null
            val t = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val pid = p[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return GpuTrying(m, t, pid)
        }
    }
}

/**
 * One model failure, as the voice process notes it (filesDir/robot/[FILE], the last one only; one writer, the voice
 * process): when, which model on which backend, at which stage, how ([failure]; null: its process stopped), what Robot
 * switched to ([then], [thenBackend]; null: nothing), the memory then, and :brain's process id (to find Android's record
 * of how that process ended). Numbers and fixed ids only. 0.6.0: the model file's [format] and the [process] that failed or
 * stopped (:brain, or :llama for a GGUF file, whose process id the voice process doesn't know: 0); 0.4.7's notes ("p1")
 * read as :brain and LiteRT-LM, which is all 0.4.7 ran there.
 */
data class ModelProblem(
    val time: Long,
    val model: BrainModel,
    val backend: ModelBackend,
    val stage: ProblemStage,
    val failure: LoadFailure?,
    val then: BrainModel?,
    val thenBackend: ModelBackend?,
    val availMem: Long = 0,
    val totalMem: Long = 0,
    val lowMemory: Boolean = false,
    val brainPid: Int = 0,
    val format: ModelFormat = ModelFormat.LITERT_LM,
    val process: ModelProcess = ModelProcess.of(format),
) {
    fun encode(): String = listOf(VERSION, time, model.id, backend.id, stage.id, failure?.id ?: 0, then?.id ?: "-", thenBackend?.id ?: "-",
        availMem, totalMem, if (lowMemory) 1 else 0, brainPid, format.id, process.id).joinToString(" ")

    /** "Fast stopped on the GPU, so Robot switched to the CPU." */
    fun headlineText(): Phrase {
        val name = "model" to model.titleText
        val on = "backend" to backend.title
        val what = when {
            failure != null && stage == ProblemStage.LOADING -> ModelStrings.problemDidntLoad(name, on)
            failure != null -> ModelStrings.problemFailed(name, on)
            else -> ModelStrings.problemStopped(name, on)
        }
        return when {
            then == null -> ModelStrings.problemEnd("what" to what)
            then == model && thenBackend != null -> ModelStrings.problemSwitched("what" to what, "backend" to thenBackend.title)
            thenBackend != null -> ModelStrings.problemUsingOn("what" to what, "then" to then.titleText, "backend" to thenBackend.title)
            else -> ModelStrings.problemUsing("what" to what, "then" to then.titleText)
        }
    }

    /** [headlineText] in English (the report's first line). */
    fun headline(): String = headlineText().en()

    companion object {
        const val FILE = "model_problem.txt"
        private const val VERSION = "p2"
        /** 0.4.7's notes: the same without the format and the process (LiteRT-LM in :brain). */
        private const val VERSION_047 = "p1"

        fun decode(text: String?): ModelProblem? {
            val p = text?.trim()?.split(' ') ?: return null
            if (!(p.size == 12 && p[0] == VERSION_047) && !(p.size == 14 && p[0] == VERSION)) return null
            val time = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val m = BrainModel.byId(p[2]) ?: return null
            val b = ModelBackend.of(p[3]) ?: return null
            val st = p[4].toIntOrNull()?.let { id -> ProblemStage.entries.firstOrNull { it.id == id } } ?: return null
            val f = p[5].toIntOrNull() ?: return null
            val failure = if (f == 0) null else LoadFailure.entries.firstOrNull { it.id == f } ?: return null
            val then = if (p[6] == "-") null else BrainModel.byId(p[6]) ?: return null
            val thenB = if (p[7] == "-") null else ModelBackend.of(p[7]) ?: return null
            val format = if (p.size == 14) ModelFormat.of(p[12]) ?: return null else ModelFormat.LITERT_LM
            val process = if (p.size == 14) p[13].toIntOrNull()?.let { id -> ModelProcess.entries.firstOrNull { it.id == id } } ?: return null
                else ModelProcess.BRAIN
            return ModelProblem(time, m, b, st, failure, then, thenB, p[8].toLongOrNull() ?: 0, p[9].toLongOrNull() ?: 0, p[10] == "1",
                p[11].toIntOrNull() ?: 0, format, process)
        }
    }
}

/**
 * The last [KEEP] model problems (0.7), for the Model tab's list: files/robot/[FILE], one [ModelProblem] per line (codes and
 * numbers only), the newest last; one writer, the voice process, which adds each problem as it notes it. The main app
 * hides one you delete (sys/Problems), never writing here.
 */
object ProblemList {
    const val FILE = "model_problems.txt"
    const val KEEP = 10
    private const val HEADER = "robot-model-problems 1" // l10n: not text (a file's header)

    /** Newest first; a damaged line is skipped. */
    fun decode(text: String?): List<ModelProblem> {
        val lines = text?.lines() ?: return emptyList()
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { ModelProblem.decode(it) }.distinctBy { it.time }.sortedByDescending { it.time }.take(KEEP)
    }

    /** The list in [text] with [p] added (one with the same time is replaced), the newest [KEEP] kept. */
    fun add(text: String?, p: ModelProblem): String {
        val all = (decode(text).filter { it.time != p.time } + p).sortedBy { it.time }.takeLast(KEEP)
        return HEADER + "\n" + all.joinToString("") { it.encode() + "\n" }
    }

    /** ":brain · writing the answer · Fast on the GPU": a row's process and stage under its date, for the list. */
    fun rowText(p: ModelProblem): Phrase = ModelStrings.problemRow("process" to p.process.suffix, "stage" to p.stage.titleText,
        "model" to p.model.titleText, "backend" to p.backend.title)

    fun row(p: ModelProblem): String = rowText(p).en()
}

/**
 * Android's record of how the model's process ended (ActivityManager.getHistoricalProcessExitReasons, ApplicationExitInfo;
 * :brain's, or 0.6.0 :llama's for a GGUF file):
 * [reasonName] is its REASON_ constant's name, [description] Android's own short text (scrubbed by [ProblemReport]).
 */
data class ExitFacts(
    val time: Long, val pid: Int, val reason: Int, val reasonName: String, val status: Int, val description: String?,
    val importance: Int, val pssKb: Long, val rssKb: Long,
    /** 0.7: a native crash's tombstone, as [Tombstone] reads it (signal, code, library names and offsets); null otherwise. */
    val nativeCrash: NativeCrash? = null,
)

/** The facts a report states besides the problem itself (the main app gathers them). */
data class ReportFacts(
    val robotVersion: String,
    val versionCode: Int,
    val phone: Phone,
    /** "17 (SDK 37)". */
    val android: String,
    val fingerprint: String,
    /** The model file's size on the phone now (-1: not there). */
    val fileBytes: Long,
    /** How the problem's process (:brain, or :llama) ended, from Android's records, nearest the problem first (may be empty). */
    val exits: List<ExitFacts>,
    /** The voice log, newest first (codes and numbers only). */
    val voiceLog: List<VoiceLogEntry>,
)

/**
 * The problem report: plain text you can read before you copy or share it. What it holds is fixed: the problem's own
 * codes, Robot's version, the phone and its Android build, the model file's name, size and the start of its SHA-256 (a
 * built-in model's, from the catalogue; never your own file's name or any path), the memory, Android's record of how the
 * model process ended, and the last [LOG_LINES] voice log events in words, wake phrases by number only. Never a question,
 * an answer, a name, a contact or a file of yours: none of those is in the note, the voice log or Android's records Robot
 * reads, and the free text Android adds is scrubbed of paths ([scrub]). 0.6.0: nor a transcript's words, a helper's name,
 * address or key, or the updater's access token: the report is built from [ModelProblem] and [ReportFacts] alone, which
 * hold none of them (helpers and conversation mode are in the voice log as fixed codes and counts only).
 */
object ProblemReport {
    const val LOG_LINES = 50
    /** Android's own descriptions are short; a longer one is cut. */
    const val MAX_DESCRIPTION = 200

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.US)

    fun time(t: Long, zone: ZoneId): String = stamp.format(Instant.ofEpochMilli(t).atZone(zone))

    /**
     * Android's free text, made safe to share: a path (anything with a slash and no space) becomes "…", or "…/name" when
     * its last part is a library (lib….so) or one of Robot's own model files, so no file of yours is ever named. A path
     * into shared storage that isn't Robot's model ends the text there (a file's name may go on past a space); control
     * characters go; at most [max] characters.
     */
    fun scrub(s: String?, max: Int = MAX_DESCRIPTION): String? {
        if (s.isNullOrBlank()) return null
        val text = s.filter { !it.isISOControl() || it == ' ' }
        val out = StringBuilder()
        var at = 0
        for (m in Regex("""\S*/\S*""").findAll(text)) {
            out.append(text, at, m.range.first)
            at = m.range.last + 1
            val last = m.value.trimEnd('/', ',', '.', ')', ';', ':', '\'', '"').substringAfterLast('/')
            val robots = BrainModel.entries.any { it.builtIn && it.file == last }
            if (robots || SAFE_NAME.matches(last)) out.append("…/").append(last)
            else {
                out.append('…')
                if (SHARED.containsMatchIn(m.value)) { at = text.length; break }
            }
        }
        if (at < text.length) out.append(text, at, text.length)
        return out.toString().trim().take(max).ifEmpty { null }
    }

    /** Shared storage, where your own files are. */
    private val SHARED = Regex("""/storage/|/sdcard|/mnt/|/data/media""")
    private val SAFE_NAME = Regex("""lib[A-Za-z0-9_.+-]*\.so""")

    /** Android's own fixed strings (the build fingerprint, the chip's name): control characters out, at most [max] characters. */
    fun plain(s: String?, max: Int): String? = s?.filter { !it.isISOControl() }?.trim()?.take(max)?.ifEmpty { null }

    /** "e2b · gemma-4-E2B-it.litertlm · 2,588,147,712 bytes · SHA-256 18193810…" (your own file: neither name nor hash). */
    fun modelLine(m: BrainModel, fileBytes: Long): String = buildString {
        append(m.titleText.en()).append(" (").append(m.id).append(")")
        if (m.builtIn) {
            append(" · ").append(m.modelName).append(" · ").append(m.file)
            append(" · SHA-256 ").append(m.sha256!!.take(8)).append('…') // l10n: not text (the problem report, a diagnostic: English)
        } else append(" · your own file (name and hash left out)") // l10n: not text (the problem report, a diagnostic: English)
        if (fileBytes >= 0) append(" · ").append(String.format(Locale.US, "%,d", fileBytes)).append(" bytes on the phone") // l10n: not text (the problem report, a diagnostic: English)
    }

    fun text(p: ModelProblem, f: ReportFacts, now: Long, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("Robot problem report\n") // l10n: not text (the problem report, a diagnostic: English)
        append(p.headline()).append('\n')
        append('\n')
        append("When: ").append(time(p.time, zone)).append('\n') // l10n: not text (the problem report, a diagnostic: English)
        append("Robot: ").append(f.robotVersion).append(" (").append(f.versionCode).append(")\n") // l10n: not text (the problem report, a diagnostic: English)
        append(phoneLines(f))
        append("Model: ").append(modelLine(p.model, f.fileBytes)).append('\n') // l10n: not text (the problem report, a diagnostic: English)
        append("Ran on: ").append(p.backend.title).append(" · ").append(p.format.engine).append(" in ") // l10n: not text (the problem report, a diagnostic: English)
            .append(ModelProcess.of(p.format).title).append(" · stage: ").append(p.stage.title).append(" · failure: ") // l10n: not text (the problem report, a diagnostic: English)
            .append(p.failure?.let { "${it.title} (code ${it.id})" } ?: "the model process stopped (${p.process.suffix})").append('\n') // l10n: not text (the problem report, a diagnostic: English)
        append("Then: ").append(p.then?.let { "${it.titleText.en()} (${it.id})" + (p.thenBackend?.let { b -> " on the ${b.title}" } ?: "") } ?: "nothing loaded").append('\n') // l10n: not text (the problem report, a diagnostic: English)
        append("Memory then: ").append(mb(p.availMem)).append(" free of ").append(mb(p.totalMem)).append(if (p.lowMemory) " · Android said memory was low" else "").append('\n') // l10n: not text (the problem report, a diagnostic: English)
        append('\n')
        append("How the model process ended (Android's records of ").append(p.process.suffix).append("):\n") // l10n: not text (the problem report, a diagnostic: English)
        if (f.exits.isEmpty()) append("  none found\n") // l10n: not text (the problem report, a diagnostic: English)
        for (e in f.exits) append(exitLines(e, zone, if (e.pid == p.brainPid && p.brainPid > 0) " (the one that failed)" else "")) // l10n: not text (the problem report, a diagnostic: English)
        append('\n')
        append(logAndEnd(f, now, zone))
    }

    /**
     * The "Phone:" and "Android:" lines (1.0: the crash report has them too, AppCrash): the phone, its chip and both SoC
     * fields (1.2), its TPU, whether Robot offers its GPU, and its Android build.
     */
    fun phoneLines(f: ReportFacts): String = buildString {
        // 1.2: both of Android's SoC fields, as Robot read them (Socs.clean), so a test phone's report shows what it says.
        append("Phone: ").append(f.phone.nameText.en()).append(" · chip ").append(f.phone.chip.title.ifEmpty { "unknown to Robot" }) // l10n: not text (the problem report, a diagnostic: English)
            .append(" (Build.SOC_MANUFACTURER “").append(Socs.clean(f.phone.socManufacturer)).append("”, Build.SOC_MODEL “") // l10n: not text (the problem report, a diagnostic: English)
            .append(Socs.clean(f.phone.socModel)).append("”) · TPU ").append(f.phone.tpu.name.lowercase(Locale.ROOT)) // l10n: not text (the problem report, a diagnostic: English)
            .append(" · GPU ").append(if (ModelCatalogue.gpuOffered(f.phone)) "offered" else "not used").append('\n') // l10n: not text (the problem report, a diagnostic: English)
        append("Android: ").append(f.android).append(" · build ").append(plain(f.fingerprint, 160) ?: "unknown").append('\n') // l10n: not text (the problem report, a diagnostic: English)
    }

    /** One of Android's records of how a process ended, [mark] after its pid, and a native crash's frames under it. */
    fun exitLines(e: ExitFacts, zone: ZoneId, mark: String = ""): String = buildString {
        append("  ").append(time(e.time, zone)).append(" · pid ").append(e.pid).append(mark) // l10n: not text (the problem report, a diagnostic: English)
            .append(" · reason ").append(e.reasonName).append(" (").append(e.reason).append(") · status ").append(e.status) // l10n: not text (the problem report, a diagnostic: English)
            .append(" · importance ").append(e.importance).append(" · PSS ").append(e.pssKb / 1024).append(" MB · RSS ").append(e.rssKb / 1024).append(" MB") // l10n: not text (the problem report, a diagnostic: English)
        scrub(e.description)?.let { append(" · “").append(it).append('”') }
        append('\n')
        e.nativeCrash?.lines()?.forEach { append("    ").append(it).append('\n') }
    }

    /** The voice log's last [LOG_LINES] events in words, and the line that says nothing was sent. */
    fun logAndEnd(f: ReportFacts, now: Long, zone: ZoneId): String = buildString {
        val log = f.voiceLog.take(LOG_LINES)
        append("Voice log, last ").append(log.size).append(" events, newest first (codes only):\n") // l10n: not text (the problem report, a diagnostic: English)
        for (e in log) append("  ").append(time(e.time, zone)).append("  ").append(VoiceLogText.describe(e)).append('\n')
        append('\n')
        append("Made ").append(time(now, zone)).append(". Nothing here was sent anywhere: you choose whether to share it.\n") // l10n: not text (the problem report, a diagnostic: English)
    }

    private fun mb(bytes: Long): String = if (bytes > 0) "${bytes / 1_000_000} MB" else "unknown" // l10n: not text (the problem report, a diagnostic: English)
}
