package app.heyrobot.core

// Pure. Hearing the user while Robot speaks (0.2.3): the microphone mode while Robot talks, how her voice is played,
// the volume key that stops her, and the barge-in test's result (written by :voice, shown by Settings › Voice).

/** Settings › Voice › "Hear me while Robot talks". */
enum class BargeIn(val title: String, val detail: String) {
    ECHO_CANCEL(
        "Echo cancellation",
        "While Robot talks the microphone switches to the phone's call mode with echo cancellation, so “stop” and your wake words get through. Back to the best mode for questions when she's done.",
    ),
    OFF("Off", "The old way: the microphone hears Robot over you. Tap the conversation or press the volume key to stop her."),
    ALWAYS(
        "Always echo cancellation",
        "Call mode with echo cancellation the whole time the panel is open: no switching, questions a little less accurate.",
    ),
}

/** How Robot's voice is played (Settings › Voice). */
enum class Playback(val title: String, val detail: String) {
    ASSISTANT("Assistant audio", "As before: the media volume. On Pixel the echo canceller hears everything the speaker plays."),
    CALL(
        "Call audio",
        "Like a call on the loudspeaker (communication mode), the way calling apps get the phone's echo cancellation. Uses the call volume.",
    ),
}

/** Which volume key stops Robot talking while the panel shows (it then doesn't change the volume). */
enum class StopKey(val title: String) {
    VOLUME_DOWN("Volume down"),
    EITHER("Either volume key"),
    OFF("Off"),
}

/** The pure part of "a volume key stops Robot talking" (the session handles the key events). */
object VolumeKeyStop {
    /** KeyEvent.KEYCODE_VOLUME_UP and KEYCODE_VOLUME_DOWN. */
    const val KEY_VOLUME_UP = 24
    const val KEY_VOLUME_DOWN = 25

    fun matches(keyCode: Int, setting: StopKey): Boolean = when (setting) {
        StopKey.VOLUME_DOWN -> keyCode == KEY_VOLUME_DOWN
        StopKey.EITHER -> keyCode == KEY_VOLUME_DOWN || keyCode == KEY_VOLUME_UP
        StopKey.OFF -> false
    }

    /**
     * Does pressing [keyCode] now stop Robot's voice (and leave the volume alone)? Only while she speaks and the panel
     * is on screen: otherwise (screen off, the panel closed, the lock screen without the panel) the key is the
     * system's, and it changes the volume as always.
     */
    fun stops(keyCode: Int, setting: StopKey, speaking: Boolean, panelShown: Boolean, interactive: Boolean): Boolean =
        speaking && panelShown && interactive && matches(keyCode, setting)
}

/**
 * Settings › Voice › "Barge-in test": Robot reads [PARAGRAPH] (about 20 s, no word like "stop", no wake phrase) and the
 * user says "stop" or a wake phrase at any point; or a silent run counts false detections. The :voice process writes
 * the result to voice_barge_test.json (VoiceFiles.BARGE); the main app shows [BargeTestLogic.lines]. Numbers and fixed
 * texts only.
 */
data class BargeTestResult(
    val state: BenchState,
    val silent: Boolean,
    val started: Long,
    val mode: BargeIn,
    val playback: Playback,
    /** MicKind and AecState ids while Robot spoke (0: unknown). */
    val mic: Int = 0,
    val aec: Int = 0,
    /** Normal run: what was heard (HEARD_*). */
    val heard: Int = HEARD_NOTHING,
    /** When it was heard, ms after Robot's voice started (-1: not heard). */
    val atMs: Long = -1,
    /** From hearing it to silence, ms (-1: not heard). */
    val stopMs: Long = -1,
    /** Silent run: detections while nobody spoke. */
    val falseStops: Int = 0,
    val falseWakes: Int = 0,
    /** "Stop"s set aside by EchoGuard (Robot saying a word like it). */
    val suppressed: Int = 0,
    /** How long Robot's voice played, ms. */
    val spokeMs: Long = 0,
    /** RUNNING: what happens now; FAILED: why (fixed texts from the code). */
    val note: String = "",
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("state", state.name); put("silent", silent); put("started", started); put("mode", mode.name); put("playback", playback.name)
        put("mic", mic); put("aec", aec); put("heard", heard); put("atMs", atMs); put("stopMs", stopMs)
        put("falseStops", falseStops); put("falseWakes", falseWakes); put("suppressed", suppressed); put("spokeMs", spokeMs); put("note", note)
    }

    companion object {
        const val HEARD_NOTHING = 0
        const val HEARD_STOP = 1
        const val HEARD_WAKE = 2

        fun fromJson(text: String?): BargeTestResult? = try {
            val o = org.json.JSONObject(text ?: return null)
            BargeTestResult(
                state = enumOr(o.optString("state"), BenchState.FAILED), silent = o.optBoolean("silent"), started = o.optLong("started"),
                mode = enumOr(o.optString("mode"), BargeIn.ECHO_CANCEL), playback = enumOr(o.optString("playback"), Playback.ASSISTANT),
                mic = o.optInt("mic"), aec = o.optInt("aec"), heard = o.optInt("heard"), atMs = o.optLong("atMs", -1), stopMs = o.optLong("stopMs", -1),
                falseStops = o.optInt("falseStops"), falseWakes = o.optInt("falseWakes"), suppressed = o.optInt("suppressed"),
                spokeMs = o.optLong("spokeMs"), note = o.optString("note"),
            )
        } catch (_: Exception) { null }
    }
}

object BargeTestLogic {
    /** About 20 seconds read aloud: no "stop", "step", "top", "shop" or the like, and no wake phrase. */
    const val PARAGRAPH = "The morning light moved slowly across the quiet valley. A farmer walked beside the river with his old brown dog, " +
        "counting the geese as they landed on the water. Later, clouds gathered over the hills, and a soft rain began to fall " +
        "on the fields and gardens. By evening the air was cool and clear again, and lights came on in the windows."

    /** Longer than the paragraph takes at the slowest speed, so a test always ends. */
    const val TIMEOUT_MS = 60_000L

    private fun s(ms: Long) = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

    fun lines(r: BargeTestResult, now: Long = System.currentTimeMillis()): List<String> {
        val out = mutableListOf<String>()
        when (r.state) {
            BenchState.RUNNING -> out += if (now - r.started > TIMEOUT_MS + 10_000) "The test stopped without a result. Try again." else r.note
            BenchState.FAILED -> out += r.note
            BenchState.DONE -> {
                if (r.silent) {
                    out += if (r.falseStops == 0 && r.falseWakes == 0) "Silent run: no false “stop” or wake word in ${s(r.spokeMs)} of Robot talking."
                    else "Silent run: ${r.falseStops} false “stop”, ${r.falseWakes} false wake word in ${s(r.spokeMs)} of Robot talking."
                } else {
                    out += when (r.heard) {
                        BargeTestResult.HEARD_STOP -> "Heard your “stop” ${s(r.atMs)} in; Robot was silent ${r.stopMs} ms later."
                        BargeTestResult.HEARD_WAKE -> "Heard your wake word ${s(r.atMs)} in; Robot was silent ${r.stopMs} ms later."
                        else -> "Robot didn't hear you (she talked for ${s(r.spokeMs)})."
                    }
                }
                if (r.suppressed > 0) out += "${r.suppressed} “stop” set aside as possibly Robot's own voice."
            }
        }
        if (r.state != BenchState.FAILED) {
            val mic = MicKind.entries.firstOrNull { it.id == r.mic }?.title
            val aec = AecState.entries.firstOrNull { it.id == r.aec }?.title
            out += "Mode: ${r.mode.title} · ${r.playback.title}" + (mic?.let { " · mic: $it" } ?: "") + (aec?.let { " · echo cancellation $it" } ?: "")
        }
        return out
    }
}
