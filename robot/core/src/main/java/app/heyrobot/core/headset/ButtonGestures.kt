// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import app.heyrobot.core.LogCode

// Pure. What a headset button press means to Robot: a long press listens, a single press
// stops, a double press repeats. What Android lets through decides what can be seen:
//
// - The voice button (an activity start, HeadsetEntry) is the only press Robot gets by default. It counts as a long
//   press: it listens, or stops listening, or stops Robot talking and listens for a new question (like "hey robot"
//   while she talks). Two starts within [ButtonGestures.ENTRY_DEBOUNCE_MS] are one (a headset that sends AT+BVRA again,
//   a hook and a voice-assist key for one press).
// - Play and pause reach Robot only while she holds a media session, which she does only with "Headset buttons control
//   Robot while she talks" on, and only while she speaks or listens (HeadsetSettings.buttonsWhileTalking; off by
//   default, as the design takes no media keys). Then a tap stops her, and a second tap within
//   [ButtonGestures.DOUBLE_TAP_MS] repeats what she said. The first tap acts at once (no waiting to see whether a second
//   comes): stopping and then repeating is the same as repeating.
// - A Bluetooth (AVRCP) key is one down and one up with no repeat and no long-press flag (AvrcpTargetService, VERIFIED):
//   a long press over Bluetooth can't be seen, so a Bluetooth key is always a tap. A wired long press never arrives as
//   a key either: MediaSessionService turns it into a voice-search start (an entry).
// - When Robot is quiet, every key is the music app's ([ButtonAction.PASS]).

/** What Robot is doing when a button is pressed. Also the voice log's BUTTON c. */
enum class RobotPhase(override val id: Int, override val title: String) : LogCode {
    IDLE(1, "quiet"), LISTENING(2, "listening"), THINKING(3, "thinking"), SPEAKING(4, "speaking"),
    /** Listening for a follow-up after an answer (HeadsetSettings.followUpSeconds). */
    FOLLOW_UP(5, "listening for a follow-up"),
    /** A spoken card waits for its answer. */
    CARD(6, "waiting for a card's answer"),
}

/** Also the voice log's BUTTON a. */
enum class Gesture(override val id: Int, override val title: String) : LogCode {
    ENTRY(1, "voice button"), SINGLE(2, "tap"), DOUBLE(3, "double tap"), LONG(4, "long press"),
}

/** Also the voice log's BUTTON b. */
enum class ButtonAction(override val id: Int, override val title: String) : LogCode {
    LISTEN(1, "listen"), STOP_LISTENING(2, "stop listening"), CANCEL(3, "cancel the question"), STOP_SPEAKING(4, "stop talking"),
    STOP_AND_LISTEN(5, "stop talking and listen"), REPEAT(6, "repeat"), NEXT(7, "next"), IGNORE(8, "nothing (debounced or not yet known)"),
    /** Not Robot's: the music app's play or pause. */
    PASS(9, "left to the music app"),
}

/** The keys a media session can get (KeyEvent key codes). */
enum class MediaKey(val keyCode: Int) {
    HEADSETHOOK(79), PLAY_PAUSE(85), NEXT(87), PREVIOUS(88), PLAY(126), PAUSE(127);

    companion object {
        fun of(keyCode: Int): MediaKey? = entries.firstOrNull { it.keyCode == keyCode }
    }
}

/** Where a key came from: Bluetooth AVRCP (no long press), or a wired or USB headset. */
enum class KeySource { BLUETOOTH, WIRED }

sealed class ButtonInput {
    abstract val at: Long

    /** HeadsetEntryActivity started ([kind]). */
    data class Entry(val kind: EntryKind, override val at: Long) : ButtonInput()

    /** A media key while Robot holds her session. [longPress]: KeyEvent.FLAG_LONG_PRESS or a repeat count above 0. */
    data class Key(val key: MediaKey, val down: Boolean, override val at: Long, val source: KeySource, val longPress: Boolean = false) : ButtonInput()
}

/** The last entry, a key held down, and the last tap with what it did (for a double tap). */
data class GestureState(
    val lastEntryAt: Long? = null,
    val downAt: Long? = null,
    val lastTapAt: Long? = null,
    val lastTapAction: ButtonAction? = null,
)

data class ButtonResult(val state: GestureState, val gesture: Gesture?, val action: ButtonAction)

object ButtonGestures {
    const val ENTRY_DEBOUNCE_MS = 1_000L
    const val DOUBLE_TAP_MS = 400L
    const val LONG_PRESS_MS = 600L

    /**
     * [input] while Robot is in [phase]; [holdsSession]: Robot holds her media session now (keys reach her only then).
     * Robot keeps the session [DOUBLE_TAP_MS] after she stops, so a double tap can still repeat.
     */
    fun onInput(s: GestureState, input: ButtonInput, phase: RobotPhase, holdsSession: Boolean): ButtonResult = when (input) {
        is ButtonInput.Entry -> entry(s, input, phase)
        is ButtonInput.Key -> key(s, input, phase, holdsSession)
    }

    private fun entry(s: GestureState, e: ButtonInput.Entry, phase: RobotPhase): ButtonResult {
        val last = s.lastEntryAt
        if (last != null && e.at - last in 0 until ENTRY_DEBOUNCE_MS) return ButtonResult(s, null, ButtonAction.IGNORE)
        return ButtonResult(s.copy(lastEntryAt = e.at), Gesture.ENTRY, long(phase))
    }

    /** A long press, and the voice button: listen. */
    private fun long(phase: RobotPhase): ButtonAction = when (phase) {
        RobotPhase.IDLE, RobotPhase.FOLLOW_UP, RobotPhase.CARD -> ButtonAction.LISTEN
        RobotPhase.LISTENING -> ButtonAction.STOP_LISTENING
        RobotPhase.THINKING -> ButtonAction.CANCEL
        RobotPhase.SPEAKING -> ButtonAction.STOP_AND_LISTEN
    }

    private fun key(s: GestureState, k: ButtonInput.Key, phase: RobotPhase, holdsSession: Boolean): ButtonResult {
        val recentTap = s.lastTapAt != null && k.at - s.lastTapAt in 0..DOUBLE_TAP_MS
        if (!holdsSession || (phase == RobotPhase.IDLE && !recentTap)) return ButtonResult(s.copy(downAt = null), null, ButtonAction.PASS)
        if (k.down) {
            // A wired key held long enough to be flagged: a long press now. Bluetooth never flags one.
            if (k.longPress && k.source == KeySource.WIRED) return ButtonResult(s.copy(downAt = null, lastTapAt = null), Gesture.LONG, long(phase))
            return ButtonResult(s.copy(downAt = s.downAt ?: k.at), null, ButtonAction.IGNORE)
        }
        val held = s.downAt?.let { k.at - it } ?: 0
        if (k.source == KeySource.WIRED && held >= LONG_PRESS_MS) return ButtonResult(s.copy(downAt = null, lastTapAt = null), Gesture.LONG, long(phase))
        when (k.key) {
            MediaKey.NEXT -> return tapped(s, k, Gesture.SINGLE, if (phase == RobotPhase.SPEAKING) ButtonAction.NEXT else ButtonAction.IGNORE)
            MediaKey.PREVIOUS -> return tapped(s, k, Gesture.SINGLE, ButtonAction.REPEAT)
            else -> Unit
        }
        if (recentTap) {
            val action = if (s.lastTapAction == ButtonAction.STOP_SPEAKING || s.lastTapAction == ButtonAction.STOP_LISTENING) ButtonAction.REPEAT else ButtonAction.IGNORE
            return ButtonResult(s.copy(downAt = null, lastTapAt = null, lastTapAction = null), Gesture.DOUBLE, action)
        }
        val action = when (phase) {
            RobotPhase.SPEAKING -> ButtonAction.STOP_SPEAKING
            RobotPhase.LISTENING, RobotPhase.FOLLOW_UP -> ButtonAction.STOP_LISTENING
            RobotPhase.THINKING -> ButtonAction.CANCEL
            RobotPhase.CARD, RobotPhase.IDLE -> ButtonAction.IGNORE
        }
        return tapped(s, k, Gesture.SINGLE, action)
    }

    private fun tapped(s: GestureState, k: ButtonInput.Key, g: Gesture, a: ButtonAction) =
        ButtonResult(s.copy(downAt = null, lastTapAt = k.at, lastTapAction = a), g, a)
}
