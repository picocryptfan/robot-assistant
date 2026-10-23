// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import org.json.JSONArray
import org.json.JSONObject

// Pure. The IPC contract between a client (the main app's Test translation speed; the voice process's
// BergamotTranslator, phase 5.4) and MtService in the :mt process (docs/conversation-mode.md 4.5): Messenger
// messages whose `what` is a code below, `arg1` the request id and data a Bundle holding [MtProtocol.KEY_JSON], one of
// the payloads below. The :mt process runs one engine: the fr↔en pair's two directions at most, loaded when first
// needed, unloaded when idle or when Android needs memory. It translates one line at a time, the waiting lines in
// priority order, so a line someone waits for (the interpreter) waits at most one line of a backlog.

object MtProtocol {
    /** Bound by name: no client process ever loads the service class (or librobotmt.so with it). */
    const val SERVICE_CLASS = "app.heyrobot.mt.MtService"
    const val KEY_JSON = "json"

    // Requests (client -> :mt).
    const val REQ_STATUS = 1
    /** Load a pair's directions ([MtMsg.Load]) now rather than at the first line. */
    const val REQ_LOAD = 2
    /** Translate lines of one direction before a deadline ([MtMsg.Translate]). */
    const val REQ_TRANSLATE = 3
    const val REQ_UNLOAD = 4
    /** Stop a TRANSLATE ([MtMsg.Cancel]): its lines not started yet come back empty. */
    const val REQ_CANCEL = 5

    // Events (:mt -> client), each with the request's id.
    const val EV_STATUS = 101
    const val EV_LOADED = 102
    const val EV_TRANSLATED = 103
    const val EV_UNLOADED = 104
    const val EV_ERROR = 105

    val REQUESTS = setOf(REQ_STATUS, REQ_LOAD, REQ_TRANSLATE, REQ_UNLOAD, REQ_CANCEL)
    val EVENTS = setOf(EV_STATUS, EV_LOADED, EV_TRANSLATED, EV_UNLOADED, EV_ERROR)
}

/** The bounds that keep the :mt process's memory and waits small. */
object MtPolicy {
    /** Lines in one TRANSLATE. */
    const val MAX_LINES = 64
    /** Characters in one line (a transcript line is at most 15 s of speech, a few hundred characters). */
    const val MAX_CHARS = 2_000
    /**
     * Characters in one TRANSLATE, its lines together. The request and its answer each cross as one Binder call, and a
     * one-way call (a Messenger's) gets at most about half of the receiving process's 1 MB Binder buffer: 64 lines of
     * 2,000 characters (and their translations, longer) could fail to arrive, and an answer that can't be sent is lost.
     */
    const val MAX_REQUEST_CHARS = 32_000
    /** The longest deadline a TRANSLATE may ask for (beyond it, "now + deadline" could overflow and time it out at once). */
    const val MAX_DEADLINE_MS = 10 * 60_000L
    /** Lines waiting in all, across requests: more is BUSY. */
    const val MAX_WAITING = 256
    /** The engine unloads after this long without a request. */
    const val IDLE_UNLOAD_MS = 5 * 60_000L
    /** A client waits this long for the service to connect. */
    const val CONNECT_MS = 5_000L
    /** A client waits this long for a load (both directions take about 0.4 s on the build box). */
    const val LOAD_MS = 30_000L
    /** A client gives the service this long beyond a TRANSLATE's own deadline to answer. */
    const val REPLY_GRACE_MS = 5_000L
    /** The deadline of one interactive line (the interpreter's fallback; docs/conversation-mode.md 4.1). */
    const val LINE_DEADLINE_MS = 1_500L
    /**
     * The deadline of one transcript line's fast translation (batch priority, so it gives way to an interactive line);
     * one not started by then gets Robot's model's first translation instead.
     */
    const val BACKLOG_LINE_DEADLINE_MS = 10_000L
}

enum class MtPriority { INTERACTIVE, BATCH }

/** Why the fast engine didn't answer (logged as this code; [text] for the screen). */
enum class MtError(override val id: Int, override val title: String) : LogCode {
    NO_ENGINE(1, "the fast engine isn't in this build"),
    NOT_DOWNLOADED(2, "its files aren't downloaded"),
    LOAD_FAILED(3, "its files didn't load"),
    BAD_REQUEST(4, "a request it doesn't take"),
    BUSY(5, "too many lines waiting"),
    FAILED(6, "it failed on a line"),
    CANCELLED(7, "cancelled"),
    DISCONNECTED(8, "its process stopped"),
    TIMEOUT(9, "no answer in time");

    /** Why, for the screen (core ConversationStrings); [text] is its English. */
    fun textPhrase(): Phrase = when (this) {
        NO_ENGINE -> ConversationStrings.mtNoEngine()
        NOT_DOWNLOADED -> ConversationStrings.mtNotDownloaded("page" to UiStrings.inSettings(UiStrings.conversation))
        LOAD_FAILED -> ConversationStrings.mtLoadFailed()
        BAD_REQUEST -> ConversationStrings.mtBadRequest()
        BUSY -> ConversationStrings.mtBusy()
        FAILED -> ConversationStrings.mtFailed()
        CANCELLED -> ConversationStrings.stopped()
        DISCONNECTED -> ConversationStrings.mtDisconnected()
        TIMEOUT -> ConversationStrings.mtTimeout()
    }

    fun text(): String = textPhrase().en()

    companion object {
        fun of(name: String?): MtError? = entries.firstOrNull { it.name == name }
    }
}

/** A client's view of an error answer (or of the service going away). */
class MtException(val error: MtError) : Exception(error.name)

/** The :mt process's memory from /proc/self/status, in kB: now (VmRSS) and its peak (VmHWM). */
data class MtMemory(val rssKb: Long, val peakKb: Long) {
    fun toJson(): JSONObject = JSONObject().put("rssKb", rssKb).put("peakKb", peakKb)

    companion object {
        val UNKNOWN = MtMemory(-1, -1)

        fun of(o: JSONObject?): MtMemory = if (o == null) UNKNOWN else MtMemory(o.optLong("rssKb", -1), o.optLong("peakKb", -1))

        /** The VmRSS and VmHWM lines of /proc/<pid>/status. */
        fun parse(status: String?): MtMemory {
            fun kb(key: String) = status?.lines()?.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: -1
            return MtMemory(kb("VmRSS"), kb("VmHWM"))
        }
    }
}

sealed class MtMsg {
    abstract fun toJson(): JSONObject

    // ---------------------------------------------------------------- requests
    data class Load(val pair: String) : MtMsg() {
        override fun toJson() = JSONObject().put("pair", pair)
    }

    /**
     * [lines] of [direction] ("fr-en"), each translated on its own; [deadlineMs] from when the service gets the request:
     * a line not started by then comes back empty and counts as timed out.
     */
    data class Translate(val direction: String, val lines: List<String>, val deadlineMs: Long, val priority: MtPriority) : MtMsg() {
        override fun toJson() = JSONObject().put("direction", direction).put("lines", JSONArray(lines)).put("deadlineMs", deadlineMs)
            .put("priority", priority.name)

        /** Null when the service takes it, else why not. */
        fun problem(): MtError? = when {
            MtBundle.forDirection(direction) == null -> MtError.BAD_REQUEST
            lines.isEmpty() || lines.size > MtPolicy.MAX_LINES -> MtError.BAD_REQUEST
            lines.any { it.length > MtPolicy.MAX_CHARS } -> MtError.BAD_REQUEST
            lines.sumOf { it.length } > MtPolicy.MAX_REQUEST_CHARS -> MtError.BAD_REQUEST
            deadlineMs <= 0 || deadlineMs > MtPolicy.MAX_DEADLINE_MS -> MtError.BAD_REQUEST
            else -> null
        }
    }

    /** Stop the TRANSLATE whose request id is [target]. */
    data class Cancel(val target: Int) : MtMsg() {
        override fun toJson() = JSONObject().put("target", target)
    }

    data object Empty : MtMsg() {
        override fun toJson() = JSONObject()
    }

    // ---------------------------------------------------------------- events
    /** [engine]: librobotmt.so loaded in this build; [loaded]: the directions in memory ("fr-en"). */
    data class Status(val engine: Boolean, val loaded: List<String>, val waiting: Int, val memory: MtMemory) : MtMsg() {
        override fun toJson() = JSONObject().put("engine", engine).put("loaded", JSONArray(loaded)).put("waiting", waiting).put("memory", memory.toJson())
    }

    /** The pair is loaded: [ms] per direction loaded by this request (empty when it already was). */
    data class Loaded(val pair: String, val ms: Map<String, Long>, val memory: MtMemory) : MtMsg() {
        override fun toJson() = JSONObject().put("pair", pair).put("ms", JSONObject().apply { ms.forEach { (k, v) -> put(k, v) } })
            .put("memory", memory.toJson())
    }

    /**
     * One output per line, null for a line that wasn't translated: [timedOut] of them were never started before the
     * deadline (or the cancel), [failed] the engine failed on. [ms]: each line's time in the engine (0 if not run).
     */
    data class Translated(val out: List<String?>, val ms: List<Long>, val timedOut: Int, val failed: Int, val memory: MtMemory) : MtMsg() {
        override fun toJson() = JSONObject().put("out", JSONArray().apply { out.forEach { put(it ?: JSONObject.NULL) } })
            .put("ms", JSONArray(ms)).put("timedOut", timedOut).put("failed", failed).put("memory", memory.toJson())
    }

    data class Error(val error: MtError) : MtMsg() {
        override fun toJson() = JSONObject().put("error", error.name)
    }

    companion object {
        /** Decodes the payload of a message with code [what]; null when the code or the JSON is not understood. */
        fun decode(what: Int, json: String?): MtMsg? = try {
            val o = JSONObject(json ?: "{}")
            fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            when (what) {
                MtProtocol.REQ_STATUS, MtProtocol.REQ_UNLOAD, MtProtocol.EV_UNLOADED -> Empty
                MtProtocol.REQ_LOAD -> Load(o.getString("pair"))
                MtProtocol.REQ_TRANSLATE -> Translate(o.getString("direction"), strings(o.getJSONArray("lines")), o.getLong("deadlineMs"),
                    MtPriority.valueOf(o.getString("priority")))
                MtProtocol.REQ_CANCEL -> Cancel(o.getInt("target"))
                MtProtocol.EV_STATUS -> Status(o.getBoolean("engine"), strings(o.optJSONArray("loaded")), o.optInt("waiting"), MtMemory.of(o.optJSONObject("memory")))
                MtProtocol.EV_LOADED -> {
                    val m = o.optJSONObject("ms") ?: JSONObject()
                    Loaded(o.getString("pair"), m.keys().asSequence().toList().sorted().associateWith { m.getLong(it) }, MtMemory.of(o.optJSONObject("memory")))
                }
                MtProtocol.EV_TRANSLATED -> {
                    val a = o.getJSONArray("out")
                    val ms = o.getJSONArray("ms")
                    Translated((0 until a.length()).map { if (a.isNull(it)) null else a.getString(it) }, (0 until ms.length()).map { ms.getLong(it) },
                        o.optInt("timedOut"), o.optInt("failed"), MtMemory.of(o.optJSONObject("memory")))
                }
                MtProtocol.EV_ERROR -> Error(MtError.of(o.getString("error")) ?: return null)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The :mt process's waiting lines, one engine, one line at a time: the next line comes from the waiting request with the
 * highest priority (then the oldest), so an interactive line waits at most for the line being translated. A request
 * is finished when all its lines are done, when it is cancelled, or when its deadline passes (its lines not started
 * stay empty). Not thread-safe: the service holds its lock around every call.
 */
class MtQueue(private val maxWaiting: Int = MtPolicy.MAX_WAITING) {
    class Job(val id: Int, val direction: String, val lines: List<String>, val priority: MtPriority, val deadlineAt: Long, internal val seq: Long) {
        val out: Array<String?> = arrayOfNulls(lines.size)
        val ms = LongArray(lines.size)
        /** The next line to start. */
        var next = 0
            internal set
        var failed = 0
        var timedOut = 0
            internal set
        var cancelled = false
            internal set
        val left: Int get() = lines.size - next
    }

    sealed class Task {
        /** Translate [Job.lines] at [index], then call [MtQueue.take] again. */
        data class Line(val job: Job, val index: Int) : Task()
        /** Answer [job]: it is out of the queue. */
        data class Finished(val job: Job) : Task()
    }

    private val jobs = ArrayList<Job>()
    private var seq = 0L

    val waiting: Int get() = jobs.sumOf { it.left }
    val isEmpty: Boolean get() = jobs.isEmpty()

    /** Queues [lines]; null when they would make more than the limit wait (BUSY). */
    fun add(id: Int, direction: String, lines: List<String>, priority: MtPriority, deadlineAt: Long): Job? {
        if (waiting + lines.size > maxWaiting) return null
        return Job(id, direction, lines, priority, deadlineAt, seq++).also { jobs += it }
    }

    /** Marks the request [id] cancelled (it comes back from [take] as finished); false when it isn't waiting. */
    fun cancel(id: Int): Boolean {
        val j = jobs.firstOrNull { it.id == id } ?: return false
        j.cancelled = true
        return true
    }

    /** The next thing to do at [now], or null when nothing waits. */
    fun take(now: Long): Task? {
        // Answer finished requests first: all lines done, cancelled, or past the deadline (the rest timed out).
        jobs.firstOrNull { it.left == 0 || it.cancelled || now >= it.deadlineAt }?.let { j ->
            j.timedOut += j.left
            j.next = j.lines.size
            jobs.remove(j)
            return Task.Finished(j)
        }
        val j = jobs.minWithOrNull(compareBy<Job>({ it.priority.ordinal }, { it.seq })) ?: return null
        return Task.Line(j, j.next++)
    }

    /** Takes every request out (an unload, the service going away): the caller answers each. */
    fun drain(): List<Job> = jobs.toList().also { jobs.clear() }
}
