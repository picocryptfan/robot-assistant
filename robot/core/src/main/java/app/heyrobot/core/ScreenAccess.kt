// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiLabel

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

    /** Android's page with "Use text from screen" and "Use screenshot", in the phone's language. */
    private val WHERE = AndroidPath.DIGITAL_ASSISTANT

    /** The chip's own name, as the panel shows it (a sentence names it in the menus' language). */
    private val ASK = UiLabel(TurnStrings.askAboutScreen)

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

    /**
     * What a tap on the chip says without the screen, and whether the notice offers "Open settings". [phrase]: 1.1, the
     * words without a language yet ([text] is their English).
     */
    data class Hint(val phrase: Phrase, val openSettings: Boolean) {
        val text: String get() = phrase.en()
    }

    val WAITING_HINT = Hint(TurnStrings.screenWaiting(), false)

    /** A tap on the chip in a wake-word panel. [textOn] false: that setting must be turned on as well. */
    fun swipeHint(textOn: Boolean?): Hint =
        if (textOn == false) Hint(TurnStrings.screenSwipeTextOff("swipe" to TurnStrings.screenSwipe(), "where" to WHERE), true) else Hint(TurnStrings.screenSwipe(), false)

    /**
     * A tap on the chip when the corner swipe came without the screen. [textOn], [shotOn]: the two settings, null if
     * unreadable. Names only the settings that are off; with both on, the app keeps its screen to itself, and there is
     * nothing to open.
     */
    fun notSharedHint(textOn: Boolean?, shotOn: Boolean?): Hint {
        val start = TurnStrings.screenNotShared()
        val private = TurnStrings.screenPrivate()
        return when {
            textOn == false && shotOn == false -> Hint(TurnStrings.screenBothOff("start" to start, "where" to WHERE), true)
            textOn == false -> Hint(TurnStrings.screenTextOff("start" to start, "where" to WHERE), true)
            textOn == null -> Hint(TurnStrings.screenUnknown("start" to start, "where" to WHERE), true)
            shotOn == false -> Hint(TurnStrings.screenShotOff("start" to start, "private" to private, "where" to WHERE), true)
            shotOn == true -> Hint(TurnStrings.screenBothOn("start" to start, "private" to private), false)
            else -> Hint(TurnStrings.screenTextOn("start" to start, "private" to private), false)
        }
    }

    /** What TalkBack says for the chip's icon: whether the screen is there, and that a tap explains when it isn't. */
    fun iconDescription(chip: ScreenChip): String? = iconText(chip)?.en()

    /** [iconDescription] in words without a language yet (1.1: the menus' language). */
    fun iconText(chip: ScreenChip): Phrase? = when (chip) {
        ScreenChip.READY -> TurnStrings.screenReady()
        ScreenChip.WAITING -> TurnStrings.screenComing()
        ScreenChip.NOT_SHARED -> TurnStrings.screenNotSharedTap()
        ScreenChip.SWIPE_ONLY -> TurnStrings.screenNoneTap()
        ScreenChip.HIDDEN -> null
    }

    /** How Assistant setup marks its "Screen sharing" row: only "Use text from screen" off is a problem. */
    enum class Level { OK, INFO, WARN }

    /** [phrase]: 1.1, the row's words without a language yet ([text] is their English). */
    data class Setup(val level: Level, val phrase: Phrase) {
        val text: String get() = phrase.en()
    }

    /** Assistant setup's "Screen sharing" row. [textOn], [shotOn]: null when Android wouldn't say. */
    fun setup(textOn: Boolean?, shotOn: Boolean?): Setup {
        fun tick(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> "?" }
        val both = TurnStrings.screenSettings("text" to tick(textOn), "shot" to tick(shotOn))
        return when {
            textOn == false -> Setup(Level.WARN, TurnStrings.screenSetupTextOff("both" to both, "ask" to ASK))
            textOn == null -> Setup(Level.INFO, TurnStrings.screenSetupUnknown("ask" to ASK, "where" to WHERE))
            shotOn != true -> Setup(Level.INFO, TurnStrings.screenSetupShotOff("both" to both, "ask" to ASK))
            else -> Setup(Level.OK, TurnStrings.screenSetupOk("both" to both, "ask" to ASK))
        }
    }

    /** The show flags in words, for the voice log ("text, screenshot, assist gesture"). */
    fun flagWords(flags: Int): String {
        val w = listOfNotNull(
            "text".takeIf { flags and SHOW_WITH_ASSIST != 0 },
            "screenshot".takeIf { flags and SHOW_WITH_SCREENSHOT != 0 },
            "assist gesture".takeIf { flags and SHOW_SOURCE_ASSIST_GESTURE != 0 }, // l10n: not text (voice log)
            "from an app".takeIf { flags and SHOW_SOURCE_APPLICATION != 0 }, // l10n: not text (voice log)
        )
        return w.joinToString(", ").ifEmpty { "none" }
    }
}
