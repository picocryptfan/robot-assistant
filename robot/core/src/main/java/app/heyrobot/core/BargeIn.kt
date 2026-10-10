package app.heyrobot.core

// Pure. Hearing the owner while Robot speaks (0.2.3): the microphone mode while Robot talks, how her voice is played,
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
