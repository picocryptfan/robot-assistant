package app.heyrobot.core

import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// Pure (java.io and a timer thread). The voice log (0.2.3): what the voice side did, as event codes and a few small
// numbers, so after "she didn't respond" a screenshot of Settings › Voice › Voice log shows
// where it broke (heard? woke? listened? understood? answered? spoke?). Never words, transcripts, audio or names: the
// recording API takes only the fixed enums below and Ints (VoiceLogTest checks its signatures), and the file holds
// only numbers. Kept in memory in the :voice process (the last [VoiceLog.CAPACITY] events) and mirrored to
// filesDir/robot/voice_log.txt (one writer: the :voice process), so it survives a restart; the main app reads it.
// The main process logs its few events (the recognizer repair) to a file of its own; the screen shows both.

/** A reason or kind stored as a stable small number (never renumber an entry: old files keep the old numbers). */
interface LogCode {
    val id: Int
    val title: String
}

/** Every event. [id] is what the file stores; a, b and c are small numbers whose meaning each entry states. */
enum class VoiceEvent(val id: Int) {
    /** a = phrase id, b = score in % (-1: sherpa-onnx 1.13.8 doesn't report one), c = [WakeAction]. */
    WAKE(1),
    /** a = [WakeDrop], b = phrase id. */
    WAKE_IGNORED(2),
    /** a = 1 if it stopped Robot's voice. */
    STOP_HEARD(3),
    /** Robot's own reply said "stop" or a word like it just then (EchoGuard). */
    STOP_SUPPRESSED(4),
    /** a = [ListenWhy], b = [MicKind], c = [AecState]. */
    LISTEN_START(5),
    /** a = [ListenWhy]. */
    LISTEN_STOP(6),
    /** a = [ListenWhy] (what failed). */
    LISTEN_FAILED(7),
    /** The capture changed: a = [MicKind], b = [AecState], c = 1 with noise suppression. */
    CAPTURE(8),
    /** a = 1: another app took the microphone (Robot hears silence), 0: it is back. */
    MIC_SILENCED(9),
    /** The wake-word model is ready: a = load ms, b = ms it waited for another model's load first. */
    KWS_READY(10),
    /** The wake word's speech gate heard speech: a = ms of it, b = how many such stretches this line stands for. */
    GATE_SPEECH(11),
    /** The question's voice detector: speech began, a = ms after listening started. */
    VAD_START(12),
    /** ... and ended, a = ms of speech. */
    VAD_END(13),
    /** a = pre-roll ms, b = 1 if the speech model was already loaded, c = 1 if it opened its own microphone. */
    STT_START(14),
    /** a = characters (never the words), b = audio ms, c = decode ms. */
    STT_DONE(15),
    /** Speech to text gave nothing: a = audio ms, b = 1 if speech was heard. */
    STT_EMPTY(16),
    /** Listening for the question ended other than by a pause: a = [SttEnd] (a cut-off question is still decoded). */
    STT_END(17),
    /** a = [ShowSource]. */
    SESSION_SHOWN(18),
    /** a = seconds it was shown. */
    SESSION_HIDDEN(19),
    /** The system didn't show the panel: a = [ShowSource]. */
    SHOW_FAILED(20),
    /** a = round (0: the question, 1+: after actions), b = 1 with a picture or the screen. */
    BRAIN_SENT(21),
    /** a = ms from the request to the first text. */
    BRAIN_FIRST(22),
    /** a = ms in all, b = [DoneCode], c = characters. */
    BRAIN_DONE(23),
    /** a = [BrainFail]. */
    BRAIN_FAILED(24),
    /** The model's process stopped. */
    BRAIN_DIED(25),
    /** a = [PlaybackCode], b = [MicKind] while speaking. */
    TTS_START(26),
    /** a = [TtsStop], b = ms spoken. */
    TTS_STOP(27),
    /** The panel opened but no question came: a = [NoQuestion]. */
    NO_QUESTION(28),
    /** A question was heard but no reply came: a = [NoReply]. */
    NO_REPLY(29),
    /** a = [Cue]. */
    CUE(30),
    /** a = 1 silent run, b = detected (1/0) or false detections in a silent run, c = ms from detection to silence. */
    BARGE_TEST(31),
    /** A volume key stopped Robot's voice: a = key code. */
    VOLUME_KEY(32),
    /** a = 1 screen on, 0 off. */
    SCREEN(33),
    /** Robot set voice_recognition_service to its recognizer (it keeps it the assistant at boot): a = [RepairWhen], b = 1 if it worked. */
    RECOGNIZER_SET(34),
    /** A request to Claude (Ask Claude, 0.3.0): a = request number in the turn (0: the question), b = [ClaudeModelCode]. */
    CLAUDE_SENT(35),
    /** a = ms from the request to Claude's first stream event. */
    CLAUDE_FIRST(36),
    /** a = ms in all, b = [ClaudeEnd], c = characters shown. */
    CLAUDE_DONE(37),
    /** a = [ClaudeFail]. */
    CLAUDE_FAILED(38),
    /**
     * What Android sent of the screen for a panel (0.3.1, once per panel): a = its show flags (ScreenAccess.SHOW_*),
     * b = [ScreenData] for the screen's text, c = [ScreenData] for the screenshot.
     */
    SCREEN_SHARED(60),
    ;

    companion object {
        fun of(id: Int): VoiceEvent? = entries.firstOrNull { it.id == id }
    }
}

enum class WakeAction(override val id: Int, override val title: String) : LogCode {
    OPENED(0, "opened the panel"), NEW_QUESTION(1, "panel open: new question"), TEST(2, "test, nothing opened"), STOPPED_TALKING(3, "stopped Robot talking"),
}

enum class WakeDrop(override val id: Int, override val title: String) : LogCode {
    DEBOUNCE(1, "within 2 s of the last one"), NO_ASSISTANT(2, "Robot isn't the running assistant"), ECHO(3, "Robot was saying it herself"),
    SHOW_FAILED(4, "the panel couldn't open"),
}

enum class ListenWhy(override val id: Int, override val title: String) : LogCode {
    READY(1, "assistant ready"), SETTINGS(2, "settings changed"), POWER(3, "charger plugged or unplugged"), RETRY(4, "retry"),
    NOT_CHARGING(5, "not charging"), MIC_BUSY(6, "microphone busy"), NO_PERMISSION(7, "no microphone permission"), SHUTDOWN(8, "assistant stopped"),
    OFF(9, "wake word off"), DEAD(10, "the microphone stopped"), STALLED(11, "no audio for a while"), NOT_ASSISTANT(12, "not the running assistant"),
    CRASHES(13, "off after repeated crashes"), MODEL(14, "wake-word model failed"), RESTART(15, "restarted"),
}

enum class MicKind(override val id: Int, override val title: String) : LogCode {
    RECOGNITION(1, "speech recognition"), COMMUNICATION(2, "call (echo cancellation)"), MIC(3, "plain microphone"),
}

enum class AecState(override val id: Int, override val title: String) : LogCode {
    OFF(1, "off"), ON(2, "on"), UNAVAILABLE(3, "not on this phone"), FAILED(4, "couldn't start"),
}

enum class SttEnd(override val id: Int, override val title: String) : LogCode {
    NO_SPEECH(1, "nothing said"), TIMEOUT(2, "timed out (watchdog)"), MIC_STOPPED(3, "the microphone stopped"), ERROR(4, "error"),
    UNAVAILABLE(5, "speech model not ready"), CANCELLED(6, "cancelled"), MIC_BUSY(7, "microphone busy"),
}

enum class ShowSource(override val id: Int, override val title: String) : LogCode {
    WAKE(1, "wake word"), SWIPE(2, "corner swipe"), KEYGUARD(3, "lock screen"), APP(4, "button in the app"), CAPTURE(5, "back from the camera"), TEST(6, "test"),
}

enum class BrainFail(override val id: Int, override val title: String) : LogCode {
    NO_MODEL(1, "no model"), LOAD_FAILED(2, "the model didn't load"), NOT_CONNECTED(3, "model process unreachable"), NOT_LOADED(4, "model not loaded"),
    NO_VISION(5, "no vision"), BAD_REQUEST(6, "bad request"), ENGINE(7, "engine error"), BUSY(8, "busy"), TOO_LONG(9, "too long"),
    TIMEOUT(10, "no answer in time"), CRASHES(11, "stopped too often"), OTHER(12, "other error");

    companion object {
        /** BrainError by name (an unknown one is OTHER). */
        fun of(error: BrainError): BrainFail = entries.firstOrNull { it.name == error.name } ?: OTHER
    }
}

enum class DoneCode(override val id: Int, override val title: String) : LogCode {
    END(1, "finished"), TOOL_CALLS(2, "actions"), CANCELLED(3, "stopped"), LENGTH(4, "too long"), GARBAGE(5, "went wrong"), NO_ROOM(6, "no room"), OTHER(7, "other");

    companion object {
        fun of(r: DoneReason): DoneCode = entries.firstOrNull { it.name == r.name } ?: if (r.name.startsWith("CONTEXT")) NO_ROOM else OTHER
    }
}

enum class PlaybackCode(override val id: Int, override val title: String) : LogCode {
    ASSISTANT(1, "assistant audio"), CALL(2, "call audio"), PHONE_VOICE(3, "phone's voice"),
}

enum class TtsStop(override val id: Int, override val title: String) : LogCode {
    DONE(1, "done"), STOP_WORD(2, "“stop”"), TAP(3, "tap"), VOLUME_KEY(4, "volume key"), NEW_QUESTION(5, "new question"),
    BUTTON(6, "stop button"), CLOSED(7, "panel closed"), WAKE(8, "wake word"), TEST(9, "test"), OTHER(10, "other"), MUTED(11, "spoken replies turned off"),
}

enum class NoQuestion(override val id: Int, override val title: String) : LogCode {
    NO_SPEECH(1, "nothing heard"), EMPTY(2, "speech, but no words"), CLOSED(3, "closed while listening"), ERROR(4, "voice input error"),
    UNAVAILABLE(5, "voice input not ready"),
}

enum class NoReply(override val id: Int, override val title: String) : LogCode {
    BUSY(1, "still on the last question"), FAILED(2, "the model failed"), EMPTY(3, "empty answer"), STOPPED(4, "stopped"),
}

enum class RepairWhen(override val id: Int, override val title: String) : LogCode {
    LOCKED_BOOT(1, "at boot, before unlock"), BOOT(2, "at boot"), APP(3, "app opened"), SETUP(4, "Assistant setup"), ASSISTANT_READY(5, "assistant started"),
}

enum class Cue(override val id: Int, override val title: String) : LogCode {
    CHIME(1, "chime"), SORRY(2, "“Sorry, I didn't catch that.”"), SORRY_NO_ANSWER(3, "“Sorry, I couldn't answer that.”"),
}

enum class ClaudeModelCode(override val id: Int, override val title: String) : LogCode {
    OPUS(1, "Claude Opus"), SONNET(2, "Claude Sonnet"), HAIKU(3, "Claude Haiku");

    companion object {
        fun of(m: ClaudeModel): ClaudeModelCode = when (m) {
            ClaudeModel.OPUS -> OPUS
            ClaudeModel.SONNET -> SONNET
            ClaudeModel.HAIKU -> HAIKU
        }
    }
}

enum class ClaudeEnd(override val id: Int, override val title: String) : LogCode {
    END(1, "finished"), TOOL_USE(2, "actions"), PAUSED(3, "search continues"), CUT(4, "too long"), DECLINED(5, "declined"), STOPPED(6, "stopped"),
    OTHER(7, "other");

    companion object {
        fun of(stop: String?): ClaudeEnd = when (stop) {
            "end_turn", "stop_sequence" -> END
            "tool_use" -> TOOL_USE
            "pause_turn" -> PAUSED
            "max_tokens", "model_context_window_exceeded" -> CUT
            "refusal" -> DECLINED
            else -> OTHER
        }
    }
}

enum class ClaudeFail(override val id: Int, override val title: String) : LogCode {
    KEY_REFUSED(1, "key refused"), NO_CREDITS(2, "no API credits"), NOT_ALLOWED(3, "not allowed"), MODEL_UNAVAILABLE(4, "model unavailable"),
    RATE_LIMITED(5, "rate limited"), OVERLOADED(6, "overloaded"), SERVER(7, "server error"), BAD_REQUEST(8, "bad request"), TOO_LONG(9, "too long"),
    NO_INTERNET(10, "no internet"), NO_NETWORK_PERMISSION(11, "no network permission"), TIMEOUT(12, "no answer in time"), NO_KEY(13, "no key"),
    UNKNOWN(14, "other error"), RESET(15, "conversation reset");

    companion object {
        fun of(f: ClaudeFailure): ClaudeFail = entries.firstOrNull { it.name == f.name } ?: UNKNOWN
    }
}

/** One event: when (wall clock, ms), what, and up to three small numbers. */
data class VoiceLogEntry(val time: Long, val event: VoiceEvent, val a: Int = 0, val b: Int = 0, val c: Int = 0)

/** The newest [capacity] events, oldest dropped first. Thread-safe. */
class VoiceLogRing(val capacity: Int = VoiceLog.CAPACITY) {
    private val items = ArrayDeque<VoiceLogEntry>()

    @Synchronized
    fun add(time: Long, event: VoiceEvent, a: Int = 0, b: Int = 0, c: Int = 0) = push(VoiceLogEntry(time, event, a, b, c))

    @Synchronized
    internal fun push(e: VoiceLogEntry) {
        items.addLast(e)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun addAll(list: List<VoiceLogEntry>) = list.forEach(::push)

    /** Replaces the contents with the newest [capacity] of [list]. */
    @Synchronized
    fun replace(list: List<VoiceLogEntry>) {
        items.clear()
        addAll(list)
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<VoiceLogEntry> = items.toList()

    @Synchronized
    fun size(): Int = items.size
}

/** voice_log.txt: a header line, then "time event a b c" per event, oldest first; numbers only. */
object VoiceLogCodec {
    const val HEADER = "robot-voice-log 1"

    fun encode(list: List<VoiceLogEntry>): String = buildString {
        append(HEADER).append('\n')
        for (e in list) append(e.time).append(' ').append(e.event.id).append(' ').append(e.a).append(' ').append(e.b).append(' ').append(e.c).append('\n')
    }

    /** Oldest first; a damaged line or an event this version doesn't know is skipped, never fatal. */
    fun decode(text: String?, capacity: Int = VoiceLog.CAPACITY): List<VoiceLogEntry> {
        if (text == null) return emptyList()
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        val out = ArrayList<VoiceLogEntry>()
        for (l in lines.drop(1)) {
            val p = l.trim().split(' ')
            if (p.size != 5) continue
            val t = p[0].toLongOrNull() ?: continue
            val e = p[1].toIntOrNull()?.let(VoiceEvent::of) ?: continue
            val (a, b, c) = Triple(p[2].toIntOrNull() ?: continue, p[3].toIntOrNull() ?: continue, p[4].toIntOrNull() ?: continue)
            out += VoiceLogEntry(t, e, a, b, c)
        }
        return out.takeLast(capacity)
    }
}

/**
 * The :voice process's log. [init] once with its file (loads what an earlier process left); then [add] from any
 * thread. Without [init] (the main app, unit tests) events stay in memory only.
 */
object VoiceLog {
    const val CAPACITY = 300
    /** Writes are batched: the file follows the memory within this. */
    const val FLUSH_MS = 400L

    private val ring = VoiceLogRing(CAPACITY)
    @Volatile private var file: File? = null
    private var timer: ScheduledExecutorService? = null
    private var pending = false
    private val lock = Any()
    /** For tests and the barge-in test: the clock (wall time, ms). */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    fun init(f: File) {
        synchronized(lock) {
            if (file != null) return
            // What an earlier process left comes first; anything this one logged before init stays after it.
            ring.replace(VoiceLogCodec.decode(readOrNull(f)) + ring.snapshot())
            file = f
            timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "robot-voicelog").apply { isDaemon = true } }
        }
    }

    /** Records an event: [a], [b], [c] as that [VoiceEvent] documents them. */
    fun add(event: VoiceEvent, a: Int = 0, b: Int = 0, c: Int = 0) {
        ring.add(clock(), event, a, b, c)
        scheduleFlush()
    }

    /** Records an event that happened at [time] (wall clock, ms), noted elsewhere first (before the first unlock). */
    fun addAt(time: Long, event: VoiceEvent, a: Int = 0, b: Int = 0, c: Int = 0) {
        ring.add(time, event, a, b, c)
        scheduleFlush()
    }

    /** Records an event whose first number is a reason or kind. */
    fun <E> add(event: VoiceEvent, code: E, b: Int = 0, c: Int = 0) where E : Enum<E>, E : LogCode = add(event, code.id, b, c)

    /** Records an event whose first two numbers are reasons or kinds. */
    fun <E, F> add(event: VoiceEvent, code: E, code2: F, c: Int = 0) where E : Enum<E>, E : LogCode, F : Enum<F>, F : LogCode =
        add(event, code.id, code2.id, c)

    /** Newest first. */
    fun entries(): List<VoiceLogEntry> = ring.snapshot().asReversed()

    private fun scheduleFlush() {
        val t = synchronized(lock) {
            if (file == null || pending) return
            pending = true
            timer
        } ?: return
        try {
            t.schedule({ flush() }, FLUSH_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            synchronized(lock) { pending = false }
        }
    }

    /** Writes the file now (the timer thread, or a test). */
    fun flush() {
        val f = synchronized(lock) {
            pending = false
            file
        } ?: return
        try { writeAtomic(f, VoiceLogCodec.encode(ring.snapshot())) } catch (t: Throwable) { RLog.w("voice_log_save", t) }
    }
}

/** Plain words for the Voice log screen and its Copy button (pure). */
object VoiceLogText {
    private fun <E> code(entries: List<E>, id: Int): String where E : Enum<E>, E : LogCode = entries.firstOrNull { it.id == id }?.title ?: "#$id"

    private fun secs(ms: Int): String = String.format(Locale.US, "%.1f s", ms / 1000.0)

    /**
     * One event in words. [phrase] names a wake phrase by id for display only (the log itself keeps the number);
     * null shows the number alone.
     */
    fun describe(e: VoiceLogEntry, phrase: (Int) -> String? = { null }): String {
        val (a, b, c) = Triple(e.a, e.b, e.c)
        fun wake(id: Int) = "Wake word #$id" + (phrase(id)?.let { " (“$it”)" } ?: "")
        return when (e.event) {
            VoiceEvent.WAKE -> wake(a) + ": " + code(WakeAction.entries, c) + (if (b >= 0) " · score $b%" else "")
            VoiceEvent.WAKE_IGNORED -> wake(b) + " ignored: " + code(WakeDrop.entries, a)
            VoiceEvent.STOP_HEARD -> if (a == 1) "“Stop” heard: Robot stopped talking" else "“Stop” heard, nothing was playing"
            VoiceEvent.STOP_SUPPRESSED -> "“Stop” ignored: Robot was saying a word like it herself"
            VoiceEvent.LISTEN_START -> "Listening for wake words (" + code(ListenWhy.entries, a) + ") · mic: " + code(MicKind.entries, b) +
                " · echo cancellation " + code(AecState.entries, c)
            VoiceEvent.LISTEN_STOP -> "Stopped listening: " + code(ListenWhy.entries, a)
            VoiceEvent.LISTEN_FAILED -> "Couldn't listen: " + code(ListenWhy.entries, a)
            VoiceEvent.CAPTURE -> "Mic now: " + code(MicKind.entries, a) + " · echo cancellation " + code(AecState.entries, b) +
                (if (c == 1) " · noise suppression on" else "")
            VoiceEvent.MIC_SILENCED -> if (a == 1) "Another app took the microphone: Robot hears silence" else "Microphone back"
            VoiceEvent.KWS_READY -> "Wake-word model ready in $a ms" + (if (b > 0) " (waited $b ms for another model to load)" else "")
            VoiceEvent.GATE_SPEECH -> "Speech heard while waiting for a wake word (" + secs(a) + ")" + (if (b > 1) " · $b times" else "")
            VoiceEvent.VAD_START -> "You started speaking (" + secs(a) + " after listening began)"
            VoiceEvent.VAD_END -> "You stopped speaking (" + secs(a) + " of speech)"
            VoiceEvent.STT_START -> "Listening for the question · " + secs(a) + " from before · speech model " + (if (b == 1) "loaded" else "loading") +
                (if (c == 1) " · own microphone" else "")
            VoiceEvent.STT_DONE -> "Question written down: $a characters from " + secs(b) + " of audio in $c ms"
            VoiceEvent.STT_EMPTY -> "No words found in " + secs(a) + " of audio" + (if (b == 1) " (speech was heard)" else "")
            VoiceEvent.STT_END -> "Listening for the question ended: " + code(SttEnd.entries, a)
            VoiceEvent.SESSION_SHOWN -> "Panel opened (" + code(ShowSource.entries, a) + ")"
            VoiceEvent.SESSION_HIDDEN -> "Panel closed after $a s"
            VoiceEvent.SHOW_FAILED -> "Android didn't open the panel (" + code(ShowSource.entries, a) + ")"
            VoiceEvent.SCREEN_SHARED -> "Screen from Android: text " + code(ScreenData.entries, b) + " · screenshot " + code(ScreenData.entries, c) +
                " (asked with: " + ScreenAccess.flagWords(a) + ")"
            VoiceEvent.BRAIN_SENT -> (if (a == 0) "Question sent to the model" else "Action results sent to the model (round $a)") + (if (b == 1) " with a picture or the screen" else "")
            VoiceEvent.BRAIN_FIRST -> "First words after $a ms"
            VoiceEvent.BRAIN_DONE -> "Answer ended after " + secs(a) + " (" + code(DoneCode.entries, b) + ") · $c characters"
            VoiceEvent.BRAIN_FAILED -> "Model failed: " + code(BrainFail.entries, a)
            VoiceEvent.BRAIN_DIED -> "The model's process stopped"
            VoiceEvent.TTS_START -> "Robot started talking (" + code(PlaybackCode.entries, a) + " · mic: " + code(MicKind.entries, b) + ")"
            VoiceEvent.TTS_STOP -> "Robot stopped talking: " + code(TtsStop.entries, a) + " · after " + secs(b)
            VoiceEvent.NO_QUESTION -> "Panel opened but no question heard: " + code(NoQuestion.entries, a)
            VoiceEvent.NO_REPLY -> "Question heard but no reply: " + code(NoReply.entries, a)
            VoiceEvent.CUE -> "Played " + code(Cue.entries, a)
            VoiceEvent.BARGE_TEST -> if (a == 1) "Barge-in test, silent run: $b false “stop” or wake detections"
                else if (b == 1) "Barge-in test: heard you, silent $c ms later" else "Barge-in test: didn't hear you"
            VoiceEvent.VOLUME_KEY -> (if (a == VolumeKeyStop.KEY_VOLUME_UP) "Volume up" else "Volume down") + " stopped Robot talking"
            VoiceEvent.SCREEN -> if (a == 1) "Screen on" else "Screen off"
            VoiceEvent.CLAUDE_SENT -> (if (a == 0) "Question sent to Claude" else "More sent to Claude (request ${a + 1})") + " (" + code(ClaudeModelCode.entries, b) + ")"
            VoiceEvent.CLAUDE_FIRST -> "Claude's first event after $a ms"
            VoiceEvent.CLAUDE_DONE -> "Claude's answer ended after " + secs(a) + " (" + code(ClaudeEnd.entries, b) + ") · $c characters"
            VoiceEvent.CLAUDE_FAILED -> "Claude failed: " + code(ClaudeFail.entries, a)
            VoiceEvent.RECOGNIZER_SET -> (if (b == 1) "Set Robot as the speech recognizer, so she stays the assistant after a restart ("
                else "Couldn't set Robot as the speech recognizer (") + code(RepairWhen.entries, a) + ")"
        }
    }

    /** "now", "12 s ago", "3 min ago", "2 h ago", "4 d ago". */
    fun ago(ms: Long): String = when {
        ms < 5_000 -> "now"
        ms < 60_000 -> "${ms / 1000} s ago"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} d ago"
    }

    fun clock(time: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm:ss", Locale.US).format(java.time.Instant.ofEpochMilli(time).atZone(zone))

    /** The Copy button's text: newest first, plain, with clock and relative times. */
    fun plain(newestFirst: List<VoiceLogEntry>, now: Long, title: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), phrase: (Int) -> String? = { null }): String =
        buildString {
            append(title).append(" · ").append(newestFirst.size).append(" events, newest first · copied ").append(clock(now, zone)).append('\n')
            for (e in newestFirst) append(clock(e.time, zone)).append("  (").append(ago(now - e.time)).append(")  ").append(describe(e, phrase)).append('\n')
        }
}
