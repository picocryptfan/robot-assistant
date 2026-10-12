package app.heyrobot.core

import app.heyrobot.core.car.CallWay
import app.heyrobot.core.car.CarWhy
import app.heyrobot.core.car.NavKind
import app.heyrobot.core.car.ParkCode
import app.heyrobot.core.car.ReplyEnd
import app.heyrobot.core.car.TrustEnd
import app.heyrobot.core.headset.AnnounceCode
import app.heyrobot.core.headset.ButtonAction
import app.heyrobot.core.headset.EndWhy
import app.heyrobot.core.headset.EntryKind
import app.heyrobot.core.headset.Gesture
import app.heyrobot.core.headset.MicRoute
import app.heyrobot.core.headset.RobotPhase
import app.heyrobot.core.headset.RouteFail
import app.heyrobot.core.output.AnswerCode
import app.heyrobot.core.output.CardVoiceCode
import app.heyrobot.core.output.HoldWhy
import app.heyrobot.core.output.SessionKind
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
    /** a = [WakeDrop], b = phrase id, c = [EchoVoice] when Robot was saying the word herself (0: not recorded). */
    WAKE_IGNORED(2),
    /**
     * a = 1 if it stopped Robot's voice; b = 1 if Robot herself said a word like "stop" in the 3 s before (0.3.1: was it
     * her own voice?).
     */
    STOP_HEARD(3),
    /**
     * Robot's own reply said "stop" or a word like it just then (EchoGuard): a = [EchoVoice] that said it (0: not
     * recorded, before 0.3.1), b = ms after Robot started talking.
     */
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
    /**
     * The wake-word model is ready: a = load ms (-1: it failed), b = ms it waited for another model's load first, c = 1
     * for the spotter used while Robot talks (0.3.1, loaded the first time she speaks).
     */
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
    /**
     * a = 1 silent run, 2 count run, 3 tricky-words run (0.3.1), 0 a normal run; b = detected (1/0), false detections
     * in a silent run, keywords counted in a count run, or detections of Robot's own words (set aside or not) in a
     * tricky-words run; c = ms from detection to silence (normal run).
     */
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
     * The keyword spotter fired (0.3.1), logged before any check: a = phrase id (0: "stop"), b = [KwsSpotterCode] (which
     * spotter, and whether Robot was talking), c = [MicKind] × 10 + [AecState] of the capture. The next "stop", wake or
     * barge-in line says what came of it.
     */
    KWS_HIT(39),
    /** The wake phrases' keyword lines were rebuilt: a = phrases listened for, b = phrases the model can't spell, c = 1 without the spelling files. */
    KWS_SET(40),
    /** The keyword spotter can't take (part of) its keyword set: a = [KwsRefusal], b = wake lines concerned, c = 1 if "stop" was among them. */
    KWS_REFUSED(41),
    /**
     * One stretch of Robot talking, as the microphone heard it: a = ms the speech gate heard speech, b = mean mic level
     * in -dBFS (60: -60 dBFS), c = % of the time the spotter listened for "stop".
     */
    SPEAK_RUN(42),
    /**
     * A capture opened (logged when it differs from the last one in the same mode): a = 1 if Android silences it
     * (another app records), 0 if not, -1 unknown; b = the microphone's own sample rate / 100 (-1 unknown); c = [CaptureFx] flags.
     */
    CAPTURE_INFO(43),
    /** Barge-in test, every keyword heard: a = phrase id (0: "stop"), b = ms after Robot's voice started (-1: before), c = [BargeHitKind]. */
    BARGE_HIT(44),
    /**
     * The keyboard opened over the panel (0.4.2), once it settled: a = the keyboard's height, b = how far Android slid the
     * panel's window up (0 expected: the panel moves itself), c = the panel's bottom padding; all in pixels.
     */
    KEYBOARD(45),
    /**
     * What Android sent of the screen for a panel (0.3.1, once per panel): a = its show flags (ScreenAccess.SHOW_*),
     * b = [ScreenData] for the screen's text, c = [ScreenData] for the screenshot.
     */
    SCREEN_SHARED(60),
    /**
     * ask_claude was left out of a question for the local model although Ask Claude could take it (0.3.1): a =
     * [ClaudeLeftOut] (you said "not Claude" in this conversation, or the question is about the screen or a photo).
     */
    CLAUDE_LEFT_OUT(120),
    /**
     * The local model handed a question to Claude without a word, and Claude wasn't asking right away, so Robot asked
     * the model again without ask_claude for its own answer (0.3.1): a = 1 if that gave an answer, 0 if not.
     */
    OWN_ANSWER(121),
    /**
     * Where a Claude request that ended in "Something went wrong with Claude" broke (0.4.0, after its CLAUDE_FAILED):
     * a = [ClaudeStage], b = the HTTP status (0: none), c = [ClaudeErrorKind] (from the exception's class; 0: none).
     */
    CLAUDE_UNKNOWN(122),
    /**
     * Claude's reply had parts Robot reads with care (0.4.0, logged only when a number isn't 0): a = calls whose input
     * came whole with their block instead of in pieces, b = server tool calls made from Claude's own code (web search's
     * dynamic filtering), c = blocks or events Robot couldn't use and left out.
     */
    CLAUDE_READ(123),
    /**
     * A weather lookup answered (0.4.0): a = [WeatherSource] (how the place was found), b = ms to find it (geocoding or
     * a location fix; 0 when nothing had to be found), c = ms for the forecast. Never the place.
     */
    WEATHER(80),
    /** A weather lookup failed: a = [WeatherFail], b = Open-Meteo's HTTP status (0: none). */
    WEATHER_FAILED(81),
    /** Pick in Cheogram (0.4.0): a = [PickOutcome], b = 1 if Cheogram named the account, c = 1 if you chose to remember them. */
    CHEOGRAM_PICK(61),
    /** A text handed to Cheogram (0.4.0): a = [CheogramRoute], b = 1 if an earlier route didn't open and this one did. */
    CHEOGRAM_HANDOFF(62),
    /**
     * The phone's position for the weather "here": a = [LocationCode], b = the assistant process's importance as Android
     * gave it (100 foreground, 125 foreground service, 200 visible, 300+ background), c = ms spent. Never the position.
     */
    WEATHER_LOCATION(82),
    // Conversation mode (0.5.0, docs/conversation-mode.md 8): 100 to 119. Counts and codes only, never a word said.
    /** A conversation session started: a = [ConvMode], b = [ConvEntry], c = 1 if the phone was locked. */
    CONV_START(100),
    /** It stopped: a = [ConvStop], b = minutes, c = lines ÷ 10. */
    CONV_STOP(101),
    /** a = [ConvPause]. */
    CONV_PAUSE(102),
    /** a = the [ConvPause] that ended, b = seconds paused. */
    CONV_RESUME(103),
    /** Once a minute while it runs: a = segments, b = median decode ms, c = most segments waiting to be decoded. */
    CONV_MINUTE(104),
    /** Once a minute: a = French lines, b = English lines, c = lines whose language was not sure. */
    CONV_LANG(105),
    /** a = speakers told apart, b = merges proposed, c = 1 if "me" was seeded. */
    CONV_SPEAKERS(106),
    /** A line translated: a = [MtEngine], b = ms, c = [MtVerdict]. */
    CONV_TRANSLATE(107),
    /** A translation spoken: a = [LangCode], b = ms to the first audio, c = 1 if a tap stopped it. */
    CONV_TTS(108),
    /** A transcript record couldn't be saved: a = [IoCode]. */
    CONV_SAVE_FAIL(109),
    /** A session that stopped unexpectedly was found and closed: a = lines recovered, b = minutes, c = 1 if you resumed it. */
    CONV_RECOVERED(110),
    /** a = [MicEvent]. */
    CONV_MIC(111),
    /** a = Android's thermal status (0 none to 6 shutdown), b = [ThermalStep] taken. */
    CONV_THERMAL(112),
    /** Robot's own summary: a = parts, b = seconds, c = [DoneCode]. */
    SUMMARY_LOCAL(113),
    /** A summary by a stronger AI: a = [HelperCode], b = words ÷ 100, c = [ClaudeEnd] (API) or [AppShareCode] (app). */
    SUMMARY_HELPER(114),
    /** a = [ExportKind]. */
    CONV_EXPORT(115),
    /** a = transcripts, b = 1 if only their recordings were deleted. */
    CONV_DELETE(116),
    /** Settings › Voice › Transcription check: a = word error rate in %, b = lines, c = [LangCode]. */
    CONV_CHECK(117),
    /** The fast translation engine loaded: a = ms, b = 1 if it worked, c = directions loaded (or the [MtError] when it didn't). */
    MT_LOAD(118),
    /** A segment was decoded again in two halves (too few words for its speech): a = speech ms, b = words before, c = words after. */
    CONV_REHEARD(119),
    /** Settings › Model (0.4.1; events 130 to 139): you chose a model. a = [ModelCode] now, b = [ModelCode] before. */
    MODEL_CHOSEN(130),
    /** A model loaded: a = [ModelCode], b = [BackendCode], c = ms the load took. */
    MODEL_LOADED(131),
    /** A model didn't load: a = [ModelCode], b = [LoadFailure]. */
    MODEL_LOAD_FAILED(132),
    /** Robot used Fast instead of the model you chose: a = the [ModelCode] you chose, b = [FallbackWhy], c = [ModelCode] used (0: none). */
    MODEL_FALLBACK(133),
    /** Another model was loaded, so Robot unloaded it first (never two at once): a = [ModelCode] unloaded, b = [ModelCode] to load. */
    MODEL_SWITCH(134),
    /**
     * 0.4.7: the GPU failed, so Robot runs the model on the CPU until you try the GPU again: a = [ModelCode], b =
     * [ProblemStage], c = [LoadFailure] (0: its process stopped).
     */
    GPU_FALLBACK(135),
    // Routines and the morning brief (0.8): 140 to 159. Codes and counts only, never a routine's name, steps or words.
    /** A trigger came: a = [TriggerKind], b = routines it started, c = routines it didn't start (ROUTINE_SKIPPED says why). */
    ROUTINE_TRIGGER(140),
    /** A Clock alarm ended (AlarmWatch): a = [AlarmEnd], b = minutes it rang. */
    ALARM_END(141),
    /** A routine its trigger didn't start: a = [SkipWhy], b = [TriggerKind]. */
    ROUTINE_SKIPPED(142),
    /** A run ended: a = steps, b = steps left for you (Ready cards, the unlock), c = ms. */
    ROUTINE_RUN(143),
    /** One step of a run: a = [StepCode], b = [StepGo], c = 1 if it didn't work. */
    ROUTINE_STEP(144),
    /** The brief: a = lines said, b = lines kept for the unlock, c = characters said. */
    BRIEF_SPOKEN(145),
    /** A Ready card was tapped: a = [StepCode] (0: a whole routine that didn't run at its time). */
    READY_TAPPED(146),
    /** How a run was said: a = [SpeechWay], b = 1 if the phone was locked. */
    ROUTINE_SPEECH(147),
    /** The one exact alarm for the next routine: a = minutes until it rings (-1: none to set), b = 1 exact, c = routines due then. */
    ROUTINE_ALARM_SET(148),
    /** A routine's time passed without it (Doze, a reboot): a = [MissedRun], b = minutes late. */
    ROUTINE_MISSED(149),
    /** A routine phrase in the panel, before the model: a = [RoutineCommandCode], b = 1 if it was done (a routine saved, run…). */
    ROUTINE_COMMAND(150),
    /** "Next", "skip", "repeat" or "stop" while a routine spoke: a = 1, 2, 3 or 4. */
    BRIEF_CONTROL(151),
    // Headset mode (0.8): 200 to 209. Codes and numbers only: a device is its number in devices.json ("device #2"), never its
    // name or address.
    /** A headset's button opened Robot: a = headset [EntryKind], b = 1 if locked (or EXTRA_SECURE), c = 1 if car mode was on. */
    HEADSET_ENTRY(200),
    /** The session's microphone route is up: a = headset [MicRoute], b = ms until it was, c = codec (0 while unknown). */
    HEADSET_ROUTE(201),
    /** The headset's route didn't come up: a = headset [RouteFail], b = 1 if it fell back to the phone microphone. */
    HEADSET_ROUTE_FAILED(202),
    /** OutputGuard held a sentence: a = output [HoldWhy], b = output [SessionKind], c = device number (0: none). */
    OUTPUT_HELD(203),
    /** The headset was told Robot listens (BluetoothHeadset.startVoiceRecognition): a = 1 if it was, b = ms after the button. */
    BVRA_ACK(204),
    /** A remembered headset or car came or went: a = 1 connected, 0 gone, b = device number, c = 1 headset, 2 car. */
    DEVICE_PRESENCE(205),
    /** "Is it headphones only you can hear?": a = device number, b = 1 yes, 0 no, -1 no answer. */
    PRIVATE_ASKED(206),
    /** A headset button while Robot held her media session: a = headset [Gesture], b = headset [ButtonAction], c = headset [RobotPhase]. */
    BUTTON(207),
    /** A message announced in the headset (0.7's messages): a = headset [AnnounceCode], b = 1 if its text was read. */
    ANNOUNCE(208),
    /** A card asked by voice in a headset or the car: a = output [CardVoiceCode], b = answers heard, c = output [AnswerCode]. */
    SPOKEN_CARD(209),
    // Car mode (0.8): 210 to 219.
    /** Car mode: a = 1 on, 0 off, b = car [CarWhy]. */
    CAR_MODE(210),
    /** The trusted session (car functions on the lock screen): a = 1 on, 0 off, b = car [TrustEnd]. */
    CAR_TRUSTED(211),
    /** Kept for when you park: a = car [ParkCode] (0: the list was shown, b = how many). */
    CAR_PARKED(212),
    /** A call: a = car [CallWay], b = 1 for an emergency number (always the dialer). */
    CAR_CALL(213),
    /** A reply by voice in the car (0.7's messages): a = 1 handed to the app, 0 not, b = car [ReplyEnd]. */
    CAR_REPLY(214),
    /** "Driving (Robot)" Do Not Disturb: a = 1 set, 0 cleared, b = 1 if Android took it. */
    CAR_DND(215),
    /** "Navigate …" in car mode: a = car [NavKind]. */
    CAR_NAV(216),
    /** Music in car mode: a = 1 play, 2 pause, 3 next, 4 previous. */
    CAR_MUSIC(217),
    /** A headset or car session ended: a = headset [EndWhy], b = seconds it lasted, c = output [SessionKind]. */
    HEADSET_END(218),
    ;

    companion object {
        fun of(id: Int): VoiceEvent? = entries.firstOrNull { it.id == id }
    }
}

/** A model of the catalogue (BrainModel), as the voice log stores it. */
enum class ModelCode(override val id: Int, override val title: String) : LogCode {
    FAST(1, "Fast (Gemma 4 E2B, TPU)"), SMARTER(2, "Smarter (Gemma 4 E4B, CPU)"), UNCENSORED(3, "Uncensored (Gemma 4 E4B abliterated, CPU)"),
    OWN(4, "your own model file"), GENERAL(5, "Fast (Gemma 4 E2B, general build)");

    companion object {
        fun of(m: BrainModel): ModelCode = when (m) {
            BrainModel.FAST -> FAST
            BrainModel.GENERAL -> GENERAL
            BrainModel.SMARTER -> SMARTER
            BrainModel.UNCENSORED -> UNCENSORED
            BrainModel.OWN -> OWN
        }
    }
}

enum class BackendCode(override val id: Int, override val title: String) : LogCode {
    TPU(1, "TPU"), CPU(2, "CPU"), GPU(3, "GPU");

    companion object {
        fun of(b: ModelBackend): BackendCode = when (b) {
            ModelBackend.TPU -> TPU
            ModelBackend.CPU -> CPU
            ModelBackend.GPU -> GPU
        }
    }
}

enum class WakeAction(override val id: Int, override val title: String) : LogCode {
    OPENED(0, "opened the panel"), NEW_QUESTION(1, "panel open: new question"), TEST(2, "test, nothing opened"), STOPPED_TALKING(3, "stopped Robot talking"),
}

enum class WakeDrop(override val id: Int, override val title: String) : LogCode {
    DEBOUNCE(1, "within 2 s of the last one"), NO_ASSISTANT(2, "Robot isn't the running assistant"), ECHO(3, "Robot was saying it herself"),
    SHOW_FAILED(4, "the panel couldn't open"),
}

/** Which keyword spotter fired: the one for when Robot is quiet, or the one for while she talks (WakeWord). */
enum class KwsSpotterCode(override val id: Int, override val title: String) : LogCode {
    IDLE(1, "Robot quiet"), IDLE_WHILE_TALKING(2, "while Robot talked, by the idle spotter (the other one wasn't loaded yet)"),
    TALK(3, "while Robot talked"),
}

/** Which voice said the word a "stop" or wake was taken for (EchoGuard). */
enum class EchoVoice(override val id: Int, override val title: String) : LogCode {
    MODEL(1, "Kokoro or Kitten"), PHONE(2, "the phone's voice"),
}

enum class KwsRefusal(override val id: Int, override val title: String) : LogCode {
    SET(1, "the keyword model refused the whole set, nothing is heard until it changes"),
    LINES(2, "lines with sounds the model doesn't know were left out"),
    STOP(3, "the keyword model refused “stop”, stop by voice is off"),
}

/** What came of a keyword during the barge-in test. */
enum class BargeHitKind(override val id: Int, override val title: String) : LogCode {
    STOPPED_HER(1, "stopped Robot"), COUNTED(2, "counted"), SET_ASIDE(3, "set aside: Robot was saying a word like it"),
    FALSE(4, "silent run: nobody spoke"), LATE(5, "after the first one, while she went quiet"), EARLY(6, "before her voice started"),
}

/** CAPTURE_INFO's flags: the effects on the capture Robot opened, and what Android reports for it. */
object CaptureFx {
    /** Robot's echo canceller and noise suppressor are on (AcousticEchoCanceler, NoiseSuppressor). */
    const val AEC = 1
    const val NS = 2
    /** Android reports an echo canceller or a noise suppressor on the capture (AudioRecordingConfiguration effects). */
    const val SYSTEM_AEC = 4
    const val SYSTEM_NS = 8
    /** Android gave no recording configuration for it. */
    const val NO_CONFIG = 16

    fun words(c: Int): String {
        val out = mutableListOf<String>()
        out += if (c and AEC != 0) "echo cancellation on" else "no echo cancellation"
        if (c and NS != 0) out += "noise suppression on"
        if (c and NO_CONFIG != 0) out += "Android reported nothing"
        else out += "Android reports " + listOfNotNull(if (c and SYSTEM_AEC != 0) "echo cancellation" else null, if (c and SYSTEM_NS != 0) "noise suppression" else null)
            .joinToString(" and ").ifEmpty { "no effects" }
        return out.joinToString(" · ")
    }
}

/** What came of "Pick in Cheogram" (never who). */
enum class PickOutcome(override val id: Int, override val title: String) : LogCode {
    // A cancel and an answer lost on the way (too large to deliver) both arrive as a cancel with nothing: CANCELLED says either.
    PICKED(1, "someone picked"), CANCELLED(2, "nobody picked, or Cheogram's answer didn't arrive"),
    NO_ADDRESS(3, "Cheogram's answer had no address Robot can use"), NO_LIST(4, "Cheogram's list didn't open"),
    UNREADABLE(5, "Cheogram's answer couldn't be read"),
}

/** How a text was handed to Cheogram. */
enum class CheogramRoute(override val id: Int, override val title: String) : LogCode {
    ACCOUNT(1, "the chat in its account"), LINK(2, "the xmpp: link"), SHARE(3, "the share screen"), LIST(4, "the contact list, to pick"),
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
    /** 0.8: a headset's or car's button. */
    HEADSET(7, "headset button"),
}

enum class BrainFail(override val id: Int, override val title: String) : LogCode {
    NO_MODEL(1, "no model"), LOAD_FAILED(2, "the model didn't load"), NOT_CONNECTED(3, "model process unreachable"), NOT_LOADED(4, "model not loaded"),
    NO_VISION(5, "no vision"), BAD_REQUEST(6, "bad request"), ENGINE(7, "engine error"), BUSY(8, "busy"), TOO_LONG(9, "too long"),
    TIMEOUT(10, "no answer in time"), CRASHES(11, "stopped too often"), OTHER(12, "other error"), STOPPED(13, "model process stopped");

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
    UPDATE(6, "after an update"),
}

enum class Cue(override val id: Int, override val title: String) : LogCode {
    CHIME(1, "chime"), SORRY(2, "“Sorry, I didn't catch that.”"), SORRY_NO_ANSWER(3, "“Sorry, I couldn't answer that.”"),
}

enum class ClaudeModelCode(override val id: Int, override val title: String) : LogCode {
    OPUS(1, "Claude Opus"), SONNET(2, "Claude Sonnet"), HAIKU(3, "Claude Haiku"), FABLE(4, "Claude Fable");

    companion object {
        fun of(m: ClaudeModel): ClaudeModelCode = when (m) {
            ClaudeModel.OPUS -> OPUS
            ClaudeModel.SONNET -> SONNET
            ClaudeModel.HAIKU -> HAIKU
            ClaudeModel.FABLE -> FABLE
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
    UNKNOWN(14, "other error"), RESET(15, "conversation reset"),
    // 0.6.0: helpers on your network and other companies.
    UNREACHABLE(16, "server not answering"), NOT_LOCAL(17, "address not on your network"), CERTIFICATE(18, "certificate not trusted"),
    NO_ADDRESS(19, "server's port not found yet");

    companion object {
        fun of(f: ClaudeFailure): ClaudeFail = entries.firstOrNull { it.name == f.name } ?: UNKNOWN
    }
}

/** How a conversation session started (0.5.0). */
enum class ConvEntry(override val id: Int, override val title: String) : LogCode {
    VOICE(1, "by voice"), APP(2, "in the app"), TILE(3, "the Quick Settings tile"), SHORTCUT(4, "a launcher shortcut"),
    NOTIFICATION(5, "the notification"), RESUME(6, "resumed after it stopped unexpectedly"),
}

/** Why a conversation session stopped. */
enum class ConvStop(override val id: Int, override val title: String) : LogCode {
    USER(1, "you stopped it"), TILE(2, "the tile"), NOTIFICATION(3, "the notification"), DISK(4, "storage nearly full"),
    THERMAL(5, "the phone was too hot"), ERROR(6, "an error"), VOICE(7, "by voice"),
}

/** Why a conversation session paused. */
enum class ConvPause(override val id: Int, override val title: String) : LogCode {
    USER(1, "you paused it"), MIC_TAKEN(2, "another app took the microphone"), CALL(3, "a phone call"), THERMAL(4, "the phone was too hot"),
    PANEL(5, "the panel opened"),
}

/** Which engine translated a line. */
enum class MtEngine(override val id: Int, override val title: String) : LogCode {
    GEMMA(1, "Gemma"), BERGAMOT(2, "the fast engine"),
}

/** A language in the log: the two Robot labels, another one, or not sure. */
enum class LangCode(override val id: Int, override val title: String) : LogCode {
    FR(1, "French"), EN(2, "English"), OTHER(3, "another language"), UNSURE(4, "not sure");

    companion object {
        fun of(lang: String?): LangCode = when (lang) {
            null -> UNSURE
            "fr" -> FR
            "en" -> EN
            else -> OTHER
        }
    }
}

/** Why a transcript record couldn't be saved. */
enum class IoCode(override val id: Int, override val title: String) : LogCode {
    NO_SPACE(1, "storage full"), WRITE(2, "the write failed"), MISSING(3, "the folder was missing"), OTHER(4, "another error"),
}

/** The microphone during a conversation. */
enum class MicEvent(override val id: Int, override val title: String) : LogCode {
    SILENCED(1, "another app took the microphone"), BACK(2, "the microphone is back"), SOURCE(3, "the microphone source changed"),
}

/** What a conversation does as the phone heats up (docs/conversation-mode.md 1.4). */
enum class ThermalStep(override val id: Int, override val title: String) : LogCode {
    PARTIALS_OFF(1, "live words off"), FAST_ONLY(2, "fast translation only"), PAUSED(3, "paused"), NORMAL(4, "back to normal"),
}

/** The stronger AI a summary went to. */
enum class HelperCode(override val id: Int, override val title: String) : LogCode {
    CLAUDE_API(1, "Claude API"), CLAUDE_APP(2, "the Claude app"),
}

/** How a transcript went to the Claude app (SUMMARY_HELPER's third number for the app). */
enum class AppShareCode(override val id: Int, override val title: String) : LogCode {
    SHARED(1, "shared to the app"), CLIPBOARD(2, "copied for you to paste"), TOO_LONG(3, "too long for the app"),
}

enum class ExportKind(override val id: Int, override val title: String) : LogCode {
    MARKDOWN(1, "Markdown file"), TEXT(2, "text file"), SHARE(3, "shared"), COPY(4, "copied"),
}

/** Where a Claude request that failed with "Something went wrong" broke (CLAUDE_UNKNOWN). */
enum class ClaudeStage(override val id: Int, override val title: String) : LogCode {
    REQUEST(1, "building the request"), HTTP(2, "the API's answer"), OPENING(3, "opening the connection"), SDK(4, "the SDK reading the stream"),
    ROBOT(5, "Robot reading the reply"), CLOSED(6, "a stream that ended before Claude finished"), WORKER(7, "Robot's request thread stopping"),
}

/** What kind of error it was (CLAUDE_UNKNOWN), from the exception's class only. */
enum class ClaudeErrorKind(override val id: Int, override val title: String) : LogCode {
    INVALID_DATA(1, "data the SDK couldn't read"), API_ERROR(2, "an error from the API"), SDK_OTHER(3, "another SDK error"), JSON(4, "malformed JSON"),
    IO(5, "the connection"), ILLEGAL_ARGUMENT(6, "an illegal argument"), ILLEGAL_STATE(7, "an illegal state"), NULL(8, "a missing value"),
    MISSING_CODE(9, "code missing on this phone"), OTHER(10, "another error"),
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

    private val counter = java.util.concurrent.atomic.AtomicLong()

    /** Events recorded since the process started (the ring keeps only the last [CAPACITY]): tests take what came after a point. */
    val total: Long get() = counter.get()

    /** Records an event: [a], [b], [c] as that [VoiceEvent] documents them. */
    fun add(event: VoiceEvent, a: Int = 0, b: Int = 0, c: Int = 0) {
        ring.add(clock(), event, a, b, c)
        counter.incrementAndGet()
        scheduleFlush()
    }

    /** Records an event that happened at [time] (wall clock, ms), noted elsewhere first (before the first unlock). */
    fun addAt(time: Long, event: VoiceEvent, a: Int = 0, b: Int = 0, c: Int = 0) {
        ring.add(time, event, a, b, c)
        counter.incrementAndGet()
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

    /** "1 call", "2 calls". */
    private fun count(n: Int, what: String): String = "$n $what" + if (n == 1) "" else "s"

    /**
     * One event in words. [phrase] names a wake phrase by id for display only (the log itself keeps the number);
     * null shows the number alone.
     */
    fun describe(e: VoiceLogEntry, phrase: (Int) -> String? = { null }): String {
        val (a, b, c) = Triple(e.a, e.b, e.c)
        fun wake(id: Int) = "Wake word #$id" + (phrase(id)?.let { " (“$it”)" } ?: "")
        // 0 is "stop" in the keyword events.
        fun keyword(id: Int) = if (id == 0) "“stop”" else "wake word #$id" + (phrase(id)?.let { " (“$it”)" } ?: "")
        return when (e.event) {
            VoiceEvent.WAKE -> wake(a) + ": " + code(WakeAction.entries, c) + (if (b >= 0) " · score $b%" else "")
            VoiceEvent.WAKE_IGNORED -> wake(b) + " ignored: " + code(WakeDrop.entries, a) + (if (c > 0) " (" + code(EchoVoice.entries, c) + ")" else "")
            VoiceEvent.STOP_HEARD -> (if (a == 1) "“Stop” heard: Robot stopped talking" else "“Stop” heard, nothing was playing") +
                (if (b == 1) " (she said a word like it in the 3 s before)" else "")
            VoiceEvent.STOP_SUPPRESSED -> "“Stop” ignored: Robot was saying a word like it herself" +
                (if (a > 0) " (" + code(EchoVoice.entries, a) + ", " + secs(b) + " into her talking)" else "")
            VoiceEvent.LISTEN_START -> "Listening for wake words (" + code(ListenWhy.entries, a) + ") · mic: " + code(MicKind.entries, b) +
                " · echo cancellation " + code(AecState.entries, c)
            VoiceEvent.LISTEN_STOP -> "Stopped listening: " + code(ListenWhy.entries, a)
            VoiceEvent.LISTEN_FAILED -> "Couldn't listen: " + code(ListenWhy.entries, a)
            VoiceEvent.CAPTURE -> "Mic now: " + code(MicKind.entries, a) + " · echo cancellation " + code(AecState.entries, b) +
                (if (c == 1) " · noise suppression on" else "")
            VoiceEvent.MIC_SILENCED -> if (a == 1) "Another app took the microphone: Robot hears silence" else "Microphone back"
            VoiceEvent.KWS_READY -> (if (c == 1) "Spotter for while Robot talks" else "Wake-word model") +
                (if (a < 0) " couldn't load" + (if (c == 1) ": the other one listens while she talks" else "") else " ready in $a ms") +
                (if (b > 0) " (waited $b ms for another model to load)" else "")
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
            VoiceEvent.BARGE_TEST -> when {
                a == 1 -> "Barge-in test, silent run: $b false “stop” or wake detections"
                a == 2 -> "Barge-in test, count run: $b “stop” or wake words heard"
                a == 3 -> "Barge-in test, tricky words: $b “stop” or wake detections from Robot's own words"
                b == 1 -> "Barge-in test: heard you, silent $c ms later"
                else -> "Barge-in test: didn't hear you"
            }
            VoiceEvent.VOLUME_KEY -> (if (a == VolumeKeyStop.KEY_VOLUME_UP) "Volume up" else "Volume down") + " stopped Robot talking"
            VoiceEvent.SCREEN -> if (a == 1) "Screen on" else "Screen off"
            VoiceEvent.CLAUDE_SENT -> (if (a == 0) "Question sent to Claude" else "More sent to Claude (request ${a + 1})") + " (" + code(ClaudeModelCode.entries, b) + ")"
            VoiceEvent.CLAUDE_FIRST -> "Claude's first event after $a ms"
            VoiceEvent.CLAUDE_DONE -> "Claude's answer ended after " + secs(a) + " (" + code(ClaudeEnd.entries, b) + ") · $c characters"
            VoiceEvent.CLAUDE_FAILED -> "Claude failed: " + code(ClaudeFail.entries, a)
            VoiceEvent.KWS_HIT -> "Spotter: " + keyword(a) + (if (b == KwsSpotterCode.IDLE.id) "" else " " + code(KwsSpotterCode.entries, b)) +
                " · mic: " + code(MicKind.entries, c / 10) + " · echo cancellation " + code(AecState.entries, c % 10)
            VoiceEvent.KWS_SET -> "Wake phrases set: $a listened for" + (if (b > 0) " · $b the model can't spell" else "") +
                (if (c == 1) " · spelling files missing: “hey robot” only" else "")
            VoiceEvent.KWS_REFUSED -> "Keyword set refused: " + code(KwsRefusal.entries, a) +
                " (" + listOfNotNull(if (b > 0) "$b wake phrases" else null, if (c == 1) "“stop”" else null).joinToString(" and ").ifEmpty { "nothing" } + ")"
            VoiceEvent.SPEAK_RUN -> "While Robot talked: speech gate open " + secs(a) + " · mic level -$b dBFS · listened for “stop” $c% of the time"
            VoiceEvent.CAPTURE_INFO -> "Capture opened: " + (when (a) { 1 -> "silenced by Android"; 0 -> "not silenced"; else -> "silencing unknown" }) +
                (if (b > 0) " · microphone at " + String.format(Locale.US, "%.1f kHz", b / 10.0) else "") + " · " + CaptureFx.words(c)
            VoiceEvent.BARGE_HIT -> "Barge-in test heard " + keyword(a) + (if (b >= 0) " " + secs(b) + " in" else "") + ": " + code(BargeHitKind.entries, c)
            VoiceEvent.KEYBOARD -> "Keyboard over the panel: $a px high, window slid up $b px, panel padded $c px"
            VoiceEvent.CLAUDE_LEFT_OUT -> "Claude not offered to the model: " + code(ClaudeLeftOut.entries, a)
            VoiceEvent.OWN_ANSWER -> "The model handed over to Claude without a word; asked again, " + (if (a == 1) "it answered itself" else "it still gave no answer")
            VoiceEvent.CLAUDE_UNKNOWN -> "Claude's error came from " + code(ClaudeStage.entries, a) + (if (b > 0) " · HTTP $b" else "") +
                (if (c > 0) " · " + code(ClaudeErrorKind.entries, c) else "")
            VoiceEvent.CLAUDE_READ -> "Claude's reply: " + listOfNotNull(if (a > 0) count(a, "call") + " with input in one piece" else null,
                if (b > 0) count(b, "server call") + " from Claude's code" else null, if (c > 0) count(c, "part") + " left out" else null)
                .joinToString(" · ").ifEmpty { "nothing unusual" }
            VoiceEvent.WEATHER -> "Weather from Open-Meteo for " + code(WeatherSource.entries, a) + (if (b > 0) " · place found in $b ms" else "") +
                " · forecast in $c ms"
            VoiceEvent.WEATHER_FAILED -> "Weather lookup failed: " + code(WeatherFail.entries, a) + (if (b > 0) " (HTTP $b)" else "")
            VoiceEvent.CHEOGRAM_PICK -> "Pick in Cheogram: " + code(PickOutcome.entries, a) + (if (b == 1) " · with its account" else "") +
                (if (c == 1) " · remembered" else "")
            VoiceEvent.CHEOGRAM_HANDOFF -> "Text handed to Cheogram: " + code(CheogramRoute.entries, a) + (if (b == 1) " (the first way didn't open)" else "")
            VoiceEvent.WEATHER_LOCATION -> "Position for the weather: " + code(LocationCode.entries, a) + " · process importance $b · $c ms"
            VoiceEvent.MODEL_CHOSEN -> "Model chosen: " + code(ModelCode.entries, a) + (if (b > 0 && b != a) " (was " + code(ModelCode.entries, b) + ")" else "")
            VoiceEvent.MODEL_LOADED -> "Model loaded: " + code(ModelCode.entries, a) + " on the " + code(BackendCode.entries, b) + " in " + secs(c)
            VoiceEvent.MODEL_LOAD_FAILED -> "Model didn't load: " + code(ModelCode.entries, a) + " (" + code(LoadFailure.entries, b) + ")"
            VoiceEvent.MODEL_FALLBACK -> "Used " + (if (c > 0) code(ModelCode.entries, c) else "no model") + " instead of " + code(ModelCode.entries, a) +
                ": " + code(FallbackWhy.entries, b)
            VoiceEvent.MODEL_SWITCH -> "Unloaded " + code(ModelCode.entries, a) + " to load " + code(ModelCode.entries, b)
            VoiceEvent.GPU_FALLBACK -> "The GPU failed for " + code(ModelCode.entries, a) + " while " + code(ProblemStage.entries, b) +
                (if (c > 0) " (" + code(LoadFailure.entries, c) + ")" else " (its process stopped)") + ": the CPU from now on"
            VoiceEvent.RECOGNIZER_SET -> (if (b == 1) "Set Robot as the speech recognizer, so she stays the assistant after a restart ("
                else "Couldn't set Robot as the speech recognizer (") + code(RepairWhen.entries, a) + ")"
            VoiceEvent.CONV_START -> "Conversation started: " + code(ConvMode.entries, a) + " (" + code(ConvEntry.entries, b) + ")" +
                (if (c == 1) " · phone locked" else "")
            VoiceEvent.CONV_STOP -> "Conversation stopped: " + code(ConvStop.entries, a) + " · $b min · " + (if (c > 0) "about ${c * 10} lines" else "under 10 lines")
            VoiceEvent.CONV_PAUSE -> "Conversation paused: " + code(ConvPause.entries, a)
            VoiceEvent.CONV_RESUME -> "Conversation resumed after " + code(ConvPause.entries, a) + " ($b s)"
            VoiceEvent.CONV_MINUTE -> "Conversation minute: $a segments · median decode $b ms · up to $c waiting"
            VoiceEvent.CONV_LANG -> "Languages this minute: $a French · $b English · $c not sure"
            VoiceEvent.CONV_SPEAKERS -> "Speakers: $a" + (if (b > 0) " · $b merges proposed" else "") + (if (c == 1) " · started from your voice" else "")
            VoiceEvent.CONV_TRANSLATE -> "Line translated by " + code(MtEngine.entries, a) + " in $b ms: " + code(MtVerdict.entries, c)
            VoiceEvent.CONV_TTS -> "Translation spoken (" + code(LangCode.entries, a) + "): first audio after $b ms" + (if (c == 1) " · stopped by a tap" else "")
            VoiceEvent.CONV_SAVE_FAIL -> "Couldn't save a transcript line: " + code(IoCode.entries, a)
            VoiceEvent.CONV_RECOVERED -> "Transcript closed after it stopped unexpectedly: $a lines, $b min" + (if (c == 1) " · you resumed it" else "")
            VoiceEvent.CONV_MIC -> "Conversation microphone: " + code(MicEvent.entries, a)
            VoiceEvent.CONV_THERMAL -> "Phone heat " + thermal(a) + ": " + code(ThermalStep.entries, b)
            VoiceEvent.SUMMARY_LOCAL -> "Robot's summary: $a parts in $b s (" + code(DoneCode.entries, c) + ")"
            VoiceEvent.SUMMARY_HELPER -> "Summary with " + code(HelperCode.entries, a) + ": about ${b * 100} words · " +
                (if (a == HelperCode.CLAUDE_APP.id) code(AppShareCode.entries, c) else code(ClaudeEnd.entries, c))
            VoiceEvent.CONV_EXPORT -> "Transcript exported: " + code(ExportKind.entries, a)
            VoiceEvent.CONV_DELETE -> (if (b == 1) "Recordings deleted, transcripts kept: " else "Transcripts deleted: ") + a
            VoiceEvent.CONV_CHECK -> "Transcription check (" + code(LangCode.entries, c) + "): $a% of words wrong over $b lines"
            VoiceEvent.MT_LOAD -> if (b == 1) "Fast translation loaded in $a ms ($c directions)" else "Fast translation didn't load ($a ms): " + code(MtError.entries, c)
            VoiceEvent.CONV_REHEARD -> "Segment heard again in two halves (" + secs(a) + " of speech): $b words, then $c"
            VoiceEvent.ROUTINE_TRIGGER -> "Routine trigger: " + code(TriggerKind.entries, a) + " · " + count(b, "routine") + " started" +
                (if (c > 0) " · $c not started" else "")
            VoiceEvent.ALARM_END -> "Clock alarm ended: " + code(AlarmEnd.entries, a) + (if (b > 0) " after $b min" else "")
            VoiceEvent.ROUTINE_SKIPPED -> "Routine not run after " + code(TriggerKind.entries, b) + ": " + code(SkipWhy.entries, a)
            VoiceEvent.ROUTINE_RUN -> "Routine ran: " + count(a, "step") + (if (b > 0) " · $b left for you" else "") + " · " + secs(c)
            VoiceEvent.ROUTINE_STEP -> "Routine step: " + code(StepCode.entries, a) + " · " + code(StepGo.entries, b) + (if (c == 1) " · didn't work" else "")
            VoiceEvent.BRIEF_SPOKEN -> "Morning brief: " + count(a, "line") + " said" + (if (b > 0) " · $b kept for the unlock" else "") + " · $c characters"
            VoiceEvent.READY_TAPPED -> "Ready card tapped: " + (if (a == 0) "a routine that didn't run at its time" else code(StepCode.entries, a))
            VoiceEvent.ROUTINE_SPEECH -> "Routine speech: " + code(SpeechWay.entries, a) + (if (b == 1) " · phone locked" else "")
            VoiceEvent.ROUTINE_ALARM_SET -> if (a < 0) "Routine alarm: none to set" else
                "Routine alarm set for $a min from now (" + (if (b == 1) "exact" else "inexact") + ") · " + count(c, "routine")
            VoiceEvent.ROUTINE_MISSED -> "Routine time missed by $b min: " + code(MissedRun.entries, a)
            VoiceEvent.ROUTINE_COMMAND -> "Routine command: " + code(RoutineCommandCode.entries, a) + (if (b == 1) " · done" else "")
            VoiceEvent.BRIEF_CONTROL -> "While a routine spoke: " + (listOf("next", "skip", "repeat", "stop").getOrNull(a - 1) ?: "#$a")
            VoiceEvent.HEADSET_ENTRY -> "Headset button: " + code(EntryKind.entries, a) + (if (b == 1) " · locked" else "") + (if (c == 1) " · car mode" else "")
            VoiceEvent.HEADSET_ROUTE -> "Headset route up: " + code(MicRoute.entries, a) + " in $b ms"
            VoiceEvent.HEADSET_ROUTE_FAILED -> "Headset route failed: " + code(RouteFail.entries, a) + if (b == 1) " · listening on the phone" else " · session ended"
            VoiceEvent.OUTPUT_HELD -> "Sentence held (" + code(SessionKind.entries, b) + "): " + code(HoldWhy.entries, a) + (if (c > 0) " · device #$c" else "")
            VoiceEvent.BVRA_ACK -> (if (a == 1) "Headset told Robot listens" else "Headset not told (no Nearby devices permission)") + " · $b ms"
            VoiceEvent.DEVICE_PRESENCE -> (if (c == 2) "Car" else "Headset") + " device #$b " + if (a == 1) "connected" else "gone"
            VoiceEvent.PRIVATE_ASKED -> "Private headphones? device #$a: " + when (b) { 1 -> "yes"; 0 -> "no"; else -> "no answer" }
            VoiceEvent.BUTTON -> "Headset button: " + code(Gesture.entries, a) + " · " + code(ButtonAction.entries, b) + " while " + code(RobotPhase.entries, c)
            VoiceEvent.ANNOUNCE -> "Message in the headset: " + code(AnnounceCode.entries, a) + if (b == 1) " · read" else ""
            VoiceEvent.SPOKEN_CARD -> "Card by voice: " + code(CardVoiceCode.entries, a) + (if (b > 0) " · " + count(b, "answer") + " · " + code(AnswerCode.entries, c) else "")
            VoiceEvent.CAR_MODE -> "Car mode " + (if (a == 1) "on: " else "off: ") + code(CarWhy.entries, b)
            VoiceEvent.CAR_TRUSTED -> "Car mode's unlocked features " + (if (a == 1) "on: " else "off: ") + code(TrustEnd.entries, b)
            VoiceEvent.CAR_PARKED -> if (a == 0) "From your drive: " + count(b, "thing") else "Kept for when you park: " + code(ParkCode.entries, a)
            VoiceEvent.CAR_CALL -> "Call: " + code(CallWay.entries, a) + if (b == 1) " · emergency number" else ""
            VoiceEvent.CAR_REPLY -> (if (a == 1) "Reply handed to the app" else "Reply not sent: " + code(ReplyEnd.entries, b))
            VoiceEvent.CAR_DND -> "Driving Do Not Disturb " + (if (a == 1) "set" else "cleared") + if (b == 1) "" else " (Android didn't take it)"
            VoiceEvent.CAR_NAV -> "Car navigation: " + code(NavKind.entries, a)
            VoiceEvent.CAR_MUSIC -> "Car music: " + (listOf("play", "pause", "next", "previous").getOrNull(a - 1) ?: "#$a")
            VoiceEvent.HEADSET_END -> code(SessionKind.entries, c).replaceFirstChar { it.uppercase() } + " session ended: " + code(EndWhy.entries, a) + " after $b s"
        }
    }

    private val THERMAL = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

    /** Android's PowerManager thermal status in words. */
    fun thermal(status: Int): String = THERMAL.getOrNull(status) ?: "#$status"

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
