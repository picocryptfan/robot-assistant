package app.heyrobot.core

// Pure. "Ask about this screen" (0.3.1). Android gives the assistant the screen's text and a screenshot only when you
// open it with the corner swipe, only with "Use text from screen" on in Settings › Apps › Default apps › Digital
// assistant app (without it, no screenshot either; "Use screenshot" adds the picture), and never for some screens
// (banking apps, private tabs, the lock screen). A panel Robot opens itself, from the wake word, gets nothing on
// Android 17 (phase 0). The chip used to show only once the screen had come, so with a setting off it was simply
// missing. Now it shows on every corner-swipe and wake-word panel, and a tap without the screen says why.

/** What the "Ask about this screen" chip is, and what a tap on it does. */
enum class ScreenChip {
    /** Not offered: Robot's own app, back from the camera, the lock screen. */
    HIDDEN,
    /** The corner swipe's screen hasn't come yet (a tap: "in a moment"). */
    WAITING,
    /** The screen's text or picture is here: a tap attaches it. */
    READY,
    /** The corner swipe came without the screen: a tap names the two settings, with "Open settings". */
    NOT_SHARED,
    /** Opened another way (the wake word): a tap says to swipe up from a corner. */
    SWIPE_ONLY,
}

/** What Android sent of the screen's text or the screenshot, for the voice log (SCREEN_SHARED). */
enum class ScreenData(override val id: Int, override val title: String) : LogCode {
    NOT_SENT(0, "not sent"), EMPTY(1, "nothing"), SHARED(2, "shared");

    companion object {
        /** [came]: null, Android hasn't called; false, it called with nothing; true, there is something to use. */
        fun of(came: Boolean?): ScreenData = when (came) {
            null -> NOT_SENT
            false -> EMPTY
            true -> SHARED
        }
    }
}

object ScreenAccess {
    /** How long the corner swipe's screen may take before a tap explains instead of waiting. */
    const val WAIT_MS = 2_000L

    /** Settings.Secure keys behind "Use text from screen" and "Use screenshot" (hidden; both default to on). */
    const val TEXT_SETTING = "assist_structure_enabled"
    const val SHOT_SETTING = "assist_screenshot_enabled"

    /** VoiceInteractionSession's show flags, as the voice log names them. */
    const val SHOW_WITH_ASSIST = 1
    const val SHOW_WITH_SCREENSHOT = 2
    const val SHOW_SOURCE_ASSIST_GESTURE = 4
    const val SHOW_SOURCE_APPLICATION = 8

    private const val WHERE = "Settings › Apps › Default apps › Digital assistant app"

    /**
     * The chip for a panel opened from [source]. [text] and [shot]: what Android sent so far (null: nothing yet, false:
     * it called with nothing). [waitedMs] since the panel opened. [textOn]: "Use text from screen" (null: unreadable).
     */
    fun chip(source: ShowSource, locked: Boolean, text: Boolean?, shot: Boolean?, waitedMs: Long, textOn: Boolean?): ScreenChip = when {
        locked -> ScreenChip.HIDDEN
        source == ShowSource.APP || source == ShowSource.CAPTURE || source == ShowSource.TEST -> ScreenChip.HIDDEN
        text == true || shot == true -> ScreenChip.READY
        source != ShowSource.SWIPE -> ScreenChip.SWIPE_ONLY
        // Off in Settings, or Android already said "nothing" for both: no need to wait.
        textOn == false || (text == false && shot == false) || waitedMs >= WAIT_MS -> ScreenChip.NOT_SHARED
        else -> ScreenChip.WAITING
    }

    /** What a tap on the chip says without the screen, and whether the notice offers "Open settings". */
    data class Hint(val text: String, val openSettings: Boolean)

    val WAITING_HINT = Hint("Android is still sending your screen. Try again in a moment.", false)

    private const val SWIPE = "Android shares your screen with Robot only when you open it by swiping up from a bottom corner. " +
        "Swipe up from a corner over the screen, then ask about it."

    /** A tap on the chip in a wake-word panel. [textOn] false: that setting must be turned on as well. */
    fun swipeHint(textOn: Boolean?): Hint =
        if (textOn == false) Hint("$SWIPE “Use text from screen” is off: turn it on first in $WHERE.", true) else Hint(SWIPE, false)

    /**
     * A tap on the chip when the corner swipe came without the screen. [textOn], [shotOn]: the two settings, null if
     * unreadable. Names only the settings that are off; with both on, the app keeps its screen to itself, and there is
     * nothing to open.
     */
    fun notSharedHint(textOn: Boolean?, shotOn: Boolean?): Hint {
        val start = "Android didn't share this screen with Robot."
        val private = "this app may keep its screen private (banking apps and private tabs do)"
        return when {
            textOn == false -> Hint(
                "$start Turn on “Use text from screen”" + (if (shotOn == false) " and “Use screenshot”" else "") + " in $WHERE, then swipe up " +
                    "from a bottom corner again." + (if (shotOn == false) "" else " Without it, Android sends no screenshot either."),
                true,
            )
            textOn == null -> Hint(
                "$start Check that “Use text from screen” and “Use screenshot” are on in $WHERE, then swipe up from a bottom corner " +
                    "again. Some apps, like banking apps and private tabs, never share their screen.",
                true,
            )
            shotOn == false -> Hint(
                "$start “Use text from screen” is on, so $private. “Use screenshot” is off in $WHERE: turn it on to send a picture of " +
                    "the screen as well.",
                true,
            )
            shotOn == true -> Hint("$start “Use text from screen” and “Use screenshot” are on, so $private.", false)
            else -> Hint("$start “Use text from screen” is on, so $private.", false)
        }
    }

    /** What TalkBack says for the chip's icon: whether the screen is there, and that a tap explains when it isn't. */
    fun iconDescription(chip: ScreenChip): String? = when (chip) {
        ScreenChip.READY -> "Screen ready"
        ScreenChip.WAITING -> "Screen still coming"
        ScreenChip.NOT_SHARED -> "Screen not shared: tap to see why"
        ScreenChip.SWIPE_ONLY -> "No screen this time: tap to see how to share it"
        ScreenChip.HIDDEN -> null
    }

    /** How Assistant setup marks its "Screen sharing" row: only "Use text from screen" off is a problem. */
    enum class Level { OK, INFO, WARN }

    data class Setup(val level: Level, val text: String)

    /** Assistant setup's "Screen sharing" row. [textOn], [shotOn]: null when Android wouldn't say. */
    fun setup(textOn: Boolean?, shotOn: Boolean?): Setup {
        fun tick(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> "?" }
        val both = "Use text from screen ${tick(textOn)} · Use screenshot ${tick(shotOn)}."
        return when {
            textOn == false -> Setup(Level.WARN, "$both Turn on Use text from screen for “Ask about this screen”: without it, Android sends no " +
                "screenshot either.")
            textOn == null -> Setup(Level.INFO, "Android didn't say. For “Ask about this screen”, turn on “Use text from screen” and " +
                "“Use screenshot” in $WHERE.")
            shotOn != true -> Setup(Level.INFO, "$both “Ask about this screen” sends the screen's text; turn on Use screenshot to send a " +
                "picture of it too.")
            else -> Setup(Level.OK, "$both Swipe up from a bottom corner, then tap “Ask about this screen”.")
        }
    }

    /** The show flags in words, for the voice log ("text, screenshot, assist gesture"). */
    fun flagWords(flags: Int): String {
        val w = listOfNotNull(
            "text".takeIf { flags and SHOW_WITH_ASSIST != 0 },
            "screenshot".takeIf { flags and SHOW_WITH_SCREENSHOT != 0 },
            "assist gesture".takeIf { flags and SHOW_SOURCE_ASSIST_GESTURE != 0 },
            "from an app".takeIf { flags and SHOW_SOURCE_APPLICATION != 0 },
        )
        return w.joinToString(", ").ifEmpty { "none" }
    }
}
