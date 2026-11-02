// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.everyday

import app.heyrobot.core.LogCode
import java.util.Locale

// Pure. Listening for other apps (docs/plans/everyday-set-plan.md §6.2, D13 to D15, E5): what Robot decides about another
// app's request before any audio flows, the pause and the prompt it takes from the request, the level it reports, and the
// voice log's codes. The recognizer (app RobotRecognitionService, an app's mic button) and the listening sheet (app
// listen/ListenSheetActivity, RECOGNIZE_SPEECH) both decide here; app listen/AppListening runs a request that passed. The
// words heard for another app never come here: only codes and numbers.

/**
 * Why the recognizer didn't serve a request, in the order Robot checks. Each check runs only once the ones before it passed,
 * so the caller is checked (and its microphone use noted) only for a request Robot could serve, and only a caller that
 * passed learns whether Robot has a speech model or is busy (a background app can't use Robot to tell when you're on a call).
 */
enum class ListenRefusal { OFF, NOT_ASSISTANT, OWN_AUDIO, LANGUAGE, ROBOT_MIC, CALLER, NO_MODEL, BUSY }

/**
 * What the recognizer knows when a request comes in. The last four are asked only when their turn comes ([ListenForApps.decide]):
 * [caller] notes the microphone use for the caller, so it must not run for a request Robot refuses anyway.
 */
class ListenFacts(
    /** Settings › Voice › "Listening for other apps" (everyday_settings.json's "listen"), read for this request. */
    val on: Boolean,
    /** Robot is the running assistant: its voice service is ready (the capture it attaches to needs that role). */
    val assistant: Boolean,
    /** The request brought its own audio (RecognizerIntent.EXTRA_AUDIO_SOURCE): not a mic button (D14). */
    val ownAudio: Boolean,
    /** RecognizerIntent.EXTRA_LANGUAGE, if the request named one. */
    val language: String?,
    /** Robot itself may use the microphone now (its own app op, as Android's check will need). */
    val robotMic: () -> Boolean,
    /** The caller holds the microphone permission and Android's app op lets it use the microphone now: it's in front. */
    val caller: () -> Boolean,
    /** Robot's speech model is downloaded and usable. */
    val modelReady: () -> Boolean,
    /** Robot is listening or talking itself, in a conversation or a call, or serving another request. */
    val busy: () -> Boolean,
)

/** What the listening sheet knows when RECOGNIZE_SPEECH starts it. */
class SheetFacts(
    /** An app waits for the words (it started the sheet for a result). */
    val caller: Boolean,
    val on: Boolean,
    /** The lock screen is up: the sheet never shows over it. */
    val keyguard: Boolean,
    /** A phone lock for someone else is on and doesn't leave the asking app open. */
    val lockRefuses: Boolean,
    val language: String?,
    val modelReady: () -> Boolean,
    val busy: () -> Boolean,
)

/** Why the listening sheet didn't listen, in the order it checks. */
enum class SheetRefusal { NO_CALLER, OFF, LOCKED, LOCK_APP, LANGUAGE, NO_MODEL, BUSY }

/**
 * When the listening sheet may hold the microphone (E10): only once it's resumed and has the window's focus, and never again
 * after anything says it isn't what you're looking at. Android has no one call for that. An activity started over the sheet
 * with a translucent theme leaves it paused and "visible" (ActivityRecord takes occludesParent from the theme, whatever the
 * activity draws), so onStop never comes; another window in multi-window leaves it resumed but not on top; a window over it
 * from another app changes no lifecycle state at all, and shows only in the focus (if that window takes it) or the flags of
 * the next touch (the sheet also hides other apps' overlays, F14). So each of them ends it, whichever comes first, and it never starts again: an app can't cover the sheet,
 * uncover it and have it listen behind your back. A step that isn't the sheet's business any more (it has shown a refusal,
 * or closed) is [Step.NONE]. Losing the focus before it began only waits: nothing is listening, and it may come back.
 */
class SheetMic {
    enum class Step { NONE, LISTEN, END }

    private var resumed = false
    private var focused = false
    private var top = true
    private var began = false
    private var over = false

    fun resumed(): Step { resumed = true; return next() }

    fun focus(has: Boolean): Step { focused = has; return if (has) next() else away() }

    fun top(on: Boolean): Step { top = on; return if (on) next() else away() }

    /** Paused, even before it began: something came over it. */
    fun paused(): Step { resumed = false; return end() }

    /** Another app's window is over it. */
    fun obscured(): Step = end()

    /** It closed or showed a refusal: nothing more to do. */
    fun over() { over = true }

    private fun next(): Step {
        if (over || began || !resumed || !focused || !top) return Step.NONE
        began = true
        return Step.LISTEN
    }

    private fun away(): Step = if (began) end() else Step.NONE

    private fun end(): Step {
        if (over) return Step.NONE
        over = true
        return Step.END
    }

    companion object {
        /** MotionEvent's FLAG_WINDOW_IS_OBSCURED and FLAG_WINDOW_IS_PARTIALLY_OBSCURED, Android's values, which are API. */
        private const val OBSCURED = 0x1 or 0x2

        fun covered(motionFlags: Int): Boolean = motionFlags and OBSCURED != 0
    }
}

object ListenForApps {
    /** The languages Robot's speech model is used for (Parakeet v3 hears more; Robot's languages are English and French). */
    val HEARD = setOf("en", "fr") // l10n: not text (language codes)

    /** The pause an app may ask for (EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS), as Settings › Voice's shortest and longest. */
    const val MIN_PAUSE_MS = 600
    const val MAX_PAUSE_MS = 2_500

    /** The caller's prompt (EXTRA_PROMPT) on the sheet: plain text, at most this many characters. */
    const val PROMPT_CHARS = 80

    /** Null: serve it. Checks in [ListenRefusal]'s order; a lazy fact is asked only when its turn comes. */
    fun decide(f: ListenFacts): ListenRefusal? = when {
        !f.on -> ListenRefusal.OFF
        !f.assistant -> ListenRefusal.NOT_ASSISTANT
        f.ownAudio -> ListenRefusal.OWN_AUDIO
        !hears(f.language) -> ListenRefusal.LANGUAGE
        !f.robotMic() -> ListenRefusal.ROBOT_MIC
        !f.caller() -> ListenRefusal.CALLER
        !f.modelReady() -> ListenRefusal.NO_MODEL
        f.busy() -> ListenRefusal.BUSY
        else -> null
    }

    /** Null: listen. */
    fun decide(f: SheetFacts): SheetRefusal? = when {
        !f.caller -> SheetRefusal.NO_CALLER
        !f.on -> SheetRefusal.OFF
        f.keyguard -> SheetRefusal.LOCKED
        f.lockRefuses -> SheetRefusal.LOCK_APP
        !hears(f.language) -> SheetRefusal.LANGUAGE
        !f.modelReady() -> SheetRefusal.NO_MODEL
        f.busy() -> SheetRefusal.BUSY
        else -> null
    }

    /**
     * A language tag Robot hears: none named, or English or French with any region ("en-US", "fr_CA"). Anything else, or
     * something that isn't a tag, isn't.
     */
    fun hears(tag: String?): Boolean {
        val t = tag?.trim().orEmpty()
        if (t.isEmpty()) return true
        val primary = t.split('-', '_').first().lowercase(Locale.ROOT)
        return primary in HEARD
    }

    /**
     * The pause that ends the request: yours ("Wait before answering"), or the app's if it asked for longer, kept to 2.5 s
     * (the user, 31 Oct 2026: Robot lets you finish, everywhere; an app can't ask to cut you off).
     */
    fun pauseMs(asked: Long?, yours: Int): Int =
        maxOf(yours, asked?.takeIf { it > 0 }?.coerceIn(MIN_PAUSE_MS.toLong(), MAX_PAUSE_MS.toLong())?.toInt() ?: yours)

    /**
     * The caller's prompt as the sheet shows it: control, format and unassigned characters out (so a direction override
     * can't turn Robot's words around), any run of white space one space, cut to [PROMPT_CHARS] characters with "…".
     * Null when nothing is left.
     */
    fun prompt(raw: CharSequence?): String? {
        raw ?: return null
        val out = StringBuilder()
        var space = false
        var i = 0
        val s = raw.toString()
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = out.isNotEmpty()
                continue
            }
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> continue
            }
            if (space) out.append(' ')
            space = false
            out.appendCodePoint(cp)
        }
        val text = out.toString()
        if (text.isEmpty()) return null
        val n = text.codePointCount(0, text.length)
        return if (n <= PROMPT_CHARS) text else text.substring(0, text.offsetByCodePoints(0, PROMPT_CHARS - 1)).trimEnd() + "…" // l10n: not text (an ellipsis)
    }

    /**
     * The level for SpeechRecognizer's rmsChanged: Robot's dBFS (silence -120, a loud voice near 0) on the scale recognizers
     * report, about -2 (quiet) to 10 (loud).
     */
    fun rmsDb(dbfs: Float): Float = ((dbfs + 60f) / 6f).coerceIn(-2f, 10f)

    /** The voice log's duration: tenths of a second, at most 99.9 s. */
    fun tenths(ms: Long): Int = (ms / 100).coerceIn(0, 999).toInt()
}

/** The voice log's LISTEN_FOR_APP (328): what came of another app's mic button. */
enum class ListenForAppCode(override val id: Int, override val title: String) : LogCode {
    DONE(1, "words returned"), NOTHING(2, "nothing heard"), OFF(3, "switched off"), NOT_ASSISTANT(4, "Robot isn't the assistant"), // l10n: not text (voice log codes)
    OWN_AUDIO(5, "the app brought its own audio"), LANGUAGE(6, "a language Robot doesn't hear"), // l10n: not text (voice log codes)
    ROBOT_MIC(7, "Robot couldn't use the microphone"), CALLER(8, "the app isn't in front or has no microphone access"), // l10n: not text (voice log codes)
    NO_MODEL(9, "speech model not ready"), BUSY(10, "Robot was busy"), ANDROID(11, "Android refused the app"), // l10n: not text (voice log codes)
    CANCELLED(12, "the app cancelled"), TAKEN(13, "Robot's own listening took over"), MIC(14, "the microphone stopped or was busy"), // l10n: not text (voice log codes)
    ERROR(15, "error"), // l10n: not text (voice log codes)
    ;

    companion object {
        fun of(r: ListenRefusal): ListenForAppCode = when (r) {
            ListenRefusal.OFF -> OFF
            ListenRefusal.NOT_ASSISTANT -> NOT_ASSISTANT
            ListenRefusal.OWN_AUDIO -> OWN_AUDIO
            ListenRefusal.LANGUAGE -> LANGUAGE
            ListenRefusal.ROBOT_MIC -> ROBOT_MIC
            ListenRefusal.CALLER -> CALLER
            ListenRefusal.NO_MODEL -> NO_MODEL
            ListenRefusal.BUSY -> BUSY
        }
    }
}

/** The voice log's LISTEN_SHEET (329): what came of the listening sheet. */
enum class ListenSheetCode(override val id: Int, override val title: String) : LogCode {
    DONE(1, "words returned"), NOTHING(2, "nothing heard"), CANCELLED(3, "you cancelled"), OFF(4, "switched off"), // l10n: not text (voice log codes)
    LOCKED(5, "the phone was locked"), LOCK_APP(6, "a phone lock doesn't leave the app open"), NO_CALLER(7, "no app waited for the words"), // l10n: not text (voice log codes)
    LANGUAGE(8, "a language Robot doesn't hear"), NO_MODEL(9, "speech model not ready"), BUSY(10, "Robot was busy"), // l10n: not text (voice log codes)
    TAKEN(11, "Robot's own listening took over"), MIC(12, "the microphone stopped or was busy"), ERROR(13, "error"), // l10n: not text (voice log codes)
    ;

    companion object {
        fun of(r: SheetRefusal): ListenSheetCode = when (r) {
            SheetRefusal.NO_CALLER -> NO_CALLER
            SheetRefusal.OFF -> OFF
            SheetRefusal.LOCKED -> LOCKED
            SheetRefusal.LOCK_APP -> LOCK_APP
            SheetRefusal.LANGUAGE -> LANGUAGE
            SheetRefusal.NO_MODEL -> NO_MODEL
            SheetRefusal.BUSY -> BUSY
        }
    }
}
