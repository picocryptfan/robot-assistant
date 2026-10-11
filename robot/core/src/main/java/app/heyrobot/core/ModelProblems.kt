package app.heyrobot.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure (0.4.7). When the model fails on the GPU, the CPU or the TPU: the GPU failure Robot remembers (so a GPU that
// crashes is never tried again by itself), the problem note the voice process keeps (codes and numbers only), and the
// plain-text problem report the main app builds from it for you to copy or share. Nothing is ever sent by itself.
// 0.6.0: the voice process knows each request's stage from :brain's events, one-shots (translations, summary steps) as
// much as the panel's questions ([RequestStages]).

/** Where the model was when it failed. */
enum class ProblemStage(override val id: Int, override val title: String) : LogCode {
    LOADING(1, "loading"), READING(2, "reading the prompt"), WRITING(3, "writing the answer"), IDLE(4, "loaded, between answers"),
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
 */
data class GpuFailure(val model: BrainModel, val time: Long, val stage: ProblemStage, val failure: LoadFailure?) {
    fun encode(): String = "${model.id} $time ${stage.id} ${failure?.id ?: 0}"

    /** "didn't load (engine error)", "failed while writing the answer (engine error)", "stopped while writing the answer". */
    val what: String get() = when {
        failure != null && stage == ProblemStage.LOADING -> "didn't load (${failure.title})"
        failure != null -> "failed while ${stage.title} (${failure.title})"
        else -> "stopped while ${stage.title}"
    }

    companion object {
        const val FILE = "gpu_failed.txt"

        fun decode(text: String?): GpuFailure? {
            val p = text?.trim()?.split(' ') ?: return null
            if (p.size != 4) return null
            val m = BrainModel.byId(p[0]) ?: return null
            val t = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val st = p[2].toIntOrNull()?.let { id -> ProblemStage.entries.firstOrNull { it.id == id } } ?: return null
            val f = p[3].toIntOrNull() ?: return null
            return GpuFailure(m, t, st, if (f == 0) null else LoadFailure.entries.firstOrNull { it.id == f } ?: return null)
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
 * of how that process ended). Numbers and fixed ids only.
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
) {
    fun encode(): String = listOf(VERSION, time, model.id, backend.id, stage.id, failure?.id ?: 0, then?.id ?: "-", thenBackend?.id ?: "-",
        availMem, totalMem, if (lowMemory) 1 else 0, brainPid).joinToString(" ")

    /** "Fast stopped on the GPU, so Robot switched to the CPU." */
    fun headline(): String {
        val name = model.title
        val what = when {
            failure != null && stage == ProblemStage.LOADING -> "$name didn't load on the ${backend.title}"
            failure != null -> "$name failed on the ${backend.title}"
            else -> "$name stopped on the ${backend.title}"
        }
        return what + when {
            then == null -> "."
            then == model && thenBackend != null -> ", so Robot switched to the ${thenBackend.title}."
            else -> ", so Robot is using ${then.title}" + (thenBackend?.let { " on the ${it.title}" } ?: "") + "."
        }
    }

    companion object {
        const val FILE = "model_problem.txt"
        private const val VERSION = "p1"

        fun decode(text: String?): ModelProblem? {
            val p = text?.trim()?.split(' ') ?: return null
            if (p.size != 12 || p[0] != VERSION) return null
            val time = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val m = BrainModel.byId(p[2]) ?: return null
            val b = ModelBackend.of(p[3]) ?: return null
            val st = p[4].toIntOrNull()?.let { id -> ProblemStage.entries.firstOrNull { it.id == id } } ?: return null
            val f = p[5].toIntOrNull() ?: return null
            val failure = if (f == 0) null else LoadFailure.entries.firstOrNull { it.id == f } ?: return null
            val then = if (p[6] == "-") null else BrainModel.byId(p[6]) ?: return null
            val thenB = if (p[7] == "-") null else ModelBackend.of(p[7]) ?: return null
            return ModelProblem(time, m, b, st, failure, then, thenB, p[8].toLongOrNull() ?: 0, p[9].toLongOrNull() ?: 0, p[10] == "1",
                p[11].toIntOrNull() ?: 0)
        }
    }
}

/**
 * Android's record of how :brain's process ended (ActivityManager.getHistoricalProcessExitReasons, ApplicationExitInfo):
 * [reasonName] is its REASON_ constant's name, [description] Android's own short text (scrubbed by [ProblemReport]).
 */
data class ExitFacts(
    val time: Long, val pid: Int, val reason: Int, val reasonName: String, val status: Int, val description: String?,
    val importance: Int, val pssKb: Long, val rssKb: Long,
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
    /** How :brain's process ended, from Android's records, nearest the problem first (may be empty). */
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
 * reads, and the free text Android adds is scrubbed of paths ([scrub]).
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
        append(m.title).append(" (").append(m.id).append(")")
        if (m.builtIn) {
            append(" · ").append(m.modelName).append(" · ").append(m.file)
            append(" · SHA-256 ").append(m.sha256!!.take(8)).append('…')
        } else append(" · your own file (name and hash left out)")
        if (fileBytes >= 0) append(" · ").append(String.format(Locale.US, "%,d", fileBytes)).append(" bytes on the phone")
    }

    fun text(p: ModelProblem, f: ReportFacts, now: Long, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("Robot problem report\n")
        append(p.headline()).append('\n')
        append('\n')
        append("When: ").append(time(p.time, zone)).append('\n')
        append("Robot: ").append(f.robotVersion).append(" (").append(f.versionCode).append(")\n")
        append("Phone: ").append(f.phone.name).append(" · chip ").append(f.phone.chip.title.ifEmpty { "not a Tensor" })
            .append(" (Build.SOC_MODEL “").append(plain(f.phone.socModel, 40) ?: "").append("”) · TPU ").append(f.phone.tpu.name.lowercase(Locale.ROOT))
            .append(" · GPU ").append(if (ModelCatalogue.gpuOffered(f.phone)) "offered" else "not used").append('\n')
        append("Android: ").append(f.android).append(" · build ").append(plain(f.fingerprint, 160) ?: "unknown").append('\n')
        append("Model: ").append(modelLine(p.model, f.fileBytes)).append('\n')
        append("Ran on: ").append(p.backend.title).append(" · stage: ").append(p.stage.title).append(" · failure: ")
            .append(p.failure?.let { "${it.title} (code ${it.id})" } ?: "the model process stopped").append('\n')
        append("Then: ").append(p.then?.let { "${it.title} (${it.id})" + (p.thenBackend?.let { b -> " on the ${b.title}" } ?: "") } ?: "nothing loaded").append('\n')
        append("Memory then: ").append(mb(p.availMem)).append(" free of ").append(mb(p.totalMem)).append(if (p.lowMemory) " · Android said memory was low" else "").append('\n')
        append('\n')
        append("How the model process ended (Android's records):\n")
        if (f.exits.isEmpty()) append("  none found\n")
        for (e in f.exits) {
            append("  ").append(time(e.time, zone)).append(" · pid ").append(e.pid).append(if (e.pid == p.brainPid && p.brainPid > 0) " (the one that failed)" else "")
                .append(" · reason ").append(e.reasonName).append(" (").append(e.reason).append(") · status ").append(e.status)
                .append(" · importance ").append(e.importance).append(" · PSS ").append(e.pssKb / 1024).append(" MB · RSS ").append(e.rssKb / 1024).append(" MB")
            scrub(e.description)?.let { append(" · “").append(it).append('”') }
            append('\n')
        }
        append('\n')
        val log = f.voiceLog.take(LOG_LINES)
        append("Voice log, last ").append(log.size).append(" events, newest first (codes only):\n")
        for (e in log) append("  ").append(time(e.time, zone)).append("  ").append(VoiceLogText.describe(e)).append('\n')
        append('\n')
        append("Made ").append(time(now, zone)).append(". Nothing here was sent anywhere: you choose whether to share it.\n")
    }

    private fun mb(bytes: Long): String = if (bytes > 0) "${bytes / 1_000_000} MB" else "unknown"
}
