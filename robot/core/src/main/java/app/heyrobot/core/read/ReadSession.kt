// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.headset.ButtonGestures
import app.heyrobot.core.headset.MediaKey
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Msg

// Pure. One reading's controls (docs/plans/read-it-to-me-plan.md §4 to §6), driven by the voice process's reading session:
// it says what to speak next, and what a command, a headset key, lost audio focus, a call, the lock or a route change
// does. One piece is spoken at a time; a piece cut off is spoken again from its start when the reading resumes. The
// caller asks OutputGuard before each Speak (ReadGate.utterance) and calls [held] when it says no.

/** What the reading session does next. */
sealed class ReadAction {
    /** Speak piece [index]. */
    data class Speak(val index: Int, val piece: ReadPiece) : ReadAction()
    /** Stop the voice at once (the piece playing is cut off). */
    object Silence : ReadAction()
    /** Say one of Robot's own short lines. */
    data class Say(val line: Msg) : ReadAction()
    /** The reading is over: give back audio focus and the media session. */
    object End : ReadAction()
}

/** Why a reading is paused. Only a short loss of audio focus resumes by itself. */
enum class ReadPause { YOU, FOCUS_SHORT, FOCUS, CALL, LOCK, HELD }

/** [inCall]: a call rings or is on (HandsFreeHub.callActive); nothing resumes then, whatever asks (the shade, a headset's Play). */
/** [mayGoOn]: the source may still be read (a File: Robot Files still open); checked again at each resume, failing closed. */
class ReadSession(
    private val cursor: ReadCursor,
    private val sharedWhileLocked: Boolean = false,
    private val inCall: () -> Boolean = { false },
    private val mayGoOn: () -> Boolean = { true },
) {
    var state: ReadState = ReadState.NONE
        private set
    var pausedBy: ReadPause? = null
        private set
    private var speaking = -1
    private var lockAtEnd = false
    private var lastTap = Long.MIN_VALUE / 2

    /** The reading starts (or resumes from its kept place). Refused while locked, or when the lock state is unknown. */
    fun start(locked: Boolean?): List<ReadAction> {
        if (!ReadGate.mayRead(cursor.source.kind, locked, sharedWhileLocked)) return listOf(ReadAction.Say(HandsFreeStrings.readUnlockFirst), ReadAction.End)
        if (cursor.text.pieces.isEmpty()) return listOf(ReadAction.Say(HandsFreeStrings.readNothing), ReadAction.End)
        val resumed = cursor.current > 0
        state = ReadState.PLAYING
        return (if (resumed) listOf(ReadAction.Say(HandsFreeStrings.readPickUp)) else emptyList()) + next()
    }

    /**
     * The voice finished piece [index]: [whole] when it was spoken to its end, not cut off. [lockedNow] is the lock now
     * (null: unknown, as locked): a lock the screen-off missed (the lock delay after a timeout) is seen here.
     */
    fun pieceEnded(index: Int, whole: Boolean, lockedNow: Boolean?): List<ReadAction> {
        if (index != speaking) return emptyList()
        speaking = -1
        if (whole) cursor.spoken(index)
        if (state != ReadState.PLAYING) return emptyList()
        if (lockedNow != false) locked()
        if (lockAtEnd) return pause(ReadPause.LOCK) + ReadAction.Say(HandsFreeStrings.readUnlockRest)
        if (cursor.done) return end() + ReadAction.Say(HandsFreeStrings.readEnd)
        return next()
    }

    /** It keeps audio focus while it plays and through a short loss, so the focus comes back to it and it reads on. */
    val holdsFocus: Boolean get() = state == ReadState.PLAYING || (state == ReadState.PAUSED && pausedBy == ReadPause.FOCUS_SHORT)

    fun command(c: ReadCommand, locked: Boolean?): List<ReadAction> = when (c) {
        ReadCommand.Pause -> pauseByYou()
        ReadCommand.Resume -> resume(locked)
        // From a pause too ("hey robot" pauses first): it goes back and reads on, as the headset's double tap does.
        ReadCommand.Back -> if (state == ReadState.NONE) emptyList() else { val playing = state == ReadState.PLAYING; val a = silence(); cursor.back(); a + if (playing) next() else resume(locked) }
        ReadCommand.Stop -> end()
        else -> emptyList()
    }

    /** The panel's line for [c] once [a] is done: the session's own line, else what the reading now does (never "Reading it to you." unless it reads). */
    fun reply(c: ReadCommand, a: List<ReadAction>): Msg = a.firstNotNullOfOrNull { (it as? ReadAction.Say)?.line } ?: when {
        c == ReadCommand.Stop || state == ReadState.NONE -> HandsFreeStrings.readStopped
        state == ReadState.PAUSED -> HandsFreeStrings.readPaused
        else -> HandsFreeStrings.readStarting
    }

    /**
     * A headset key while the session holds the media session: a tap pauses or resumes at once, a second tap within
     * ButtonGestures.DOUBLE_TAP_MS goes back a bit and reads on; Previous goes back a bit; Play and Pause do just that.
     */
    fun key(key: MediaKey, at: Long, locked: Boolean?): List<ReadAction> {
        val double = at - lastTap <= ButtonGestures.DOUBLE_TAP_MS
        return when (key) {
            MediaKey.HEADSETHOOK, MediaKey.PLAY_PAUSE -> {
                lastTap = if (double) Long.MIN_VALUE / 2 else at
                when {
                    double -> command(ReadCommand.Back, locked)
                    state == ReadState.PLAYING -> pause(ReadPause.YOU)
                    else -> resume(locked)
                }
            }
            MediaKey.PLAY -> resume(locked)
            MediaKey.PAUSE -> pauseByYou()
            MediaKey.PREVIOUS -> command(ReadCommand.Back, locked)
            MediaKey.NEXT -> emptyList()
        }
    }

    /** Audio focus lost: [short] for a transient loss (a notification sound, directions), which resumes on its own. */
    fun focusLost(short: Boolean): List<ReadAction> {
        if (state == ReadState.PLAYING) return pause(if (short) ReadPause.FOCUS_SHORT else ReadPause.FOCUS)
        if (!short && pausedBy == ReadPause.FOCUS_SHORT) pausedBy = ReadPause.FOCUS
        return emptyList()
    }

    fun focusGained(locked: Boolean?): List<ReadAction> = if (state == ReadState.PAUSED && pausedBy == ReadPause.FOCUS_SHORT) resume(locked) else emptyList()

    /** A call rings or starts: pause, and wait for "keep reading". */
    fun call(): List<ReadAction> {
        if (state == ReadState.PLAYING) return pause(ReadPause.CALL)
        // A call's ring takes the focus for a moment first: that pause must not resume when the call gives it back.
        if (pausedBy == ReadPause.FOCUS_SHORT) pausedBy = ReadPause.CALL
        return emptyList()
    }

    /** The phone locked: the piece playing ends, then the reading pauses (plan §6). */
    fun locked(): List<ReadAction> {
        if (state != ReadState.PLAYING || ReadGate.mayRead(cursor.source.kind, true, sharedWhileLocked)) return emptyList()
        lockAtEnd = true
        return emptyList()
    }

    /** A Phone lock for someone else started (fix-stage-reading H1): the reading ends now, the piece playing cut off, nothing said. */
    fun lockForSomeoneElse(): List<ReadAction> = end()

    /** The reading was paused [PAUSE_ENDS_MS]: it ends, its place kept, and gives back the audio, the button and the notification. */
    fun pausedTooLong(): List<ReadAction> = if (state == ReadState.PAUSED) end() else emptyList()

    /** OutputGuard held a piece, or the route moved to a device it may not play on: stop now, keep the place. */
    fun held(): List<ReadAction> = if (state == ReadState.PLAYING) pause(ReadPause.HELD) else emptyList()

    private fun resume(locked: Boolean?): List<ReadAction> {
        if (state != ReadState.PAUSED) return if (state == ReadState.NONE) listOf(ReadAction.Say(HandsFreeStrings.readNothingOn)) else emptyList()
        if (!ReadGate.mayRead(cursor.source.kind, locked, sharedWhileLocked)) return listOf(ReadAction.Say(HandsFreeStrings.readUnlockRest))
        if (!mayGoOn()) return end() + ReadAction.Say(HandsFreeStrings.readNoFile)
        // Never in a call: it stays paused, and waits for "keep reading" after the call.
        if (inCall()) { pausedBy = ReadPause.CALL; return emptyList() }
        state = ReadState.PLAYING
        pausedBy = null
        lockAtEnd = false
        return next()
    }

    /** Your pause: a short focus loss's pause becomes yours, so it no longer resumes by itself. */
    private fun pauseByYou(): List<ReadAction> {
        if (state == ReadState.PLAYING) return pause(ReadPause.YOU)
        if (state == ReadState.PAUSED) pausedBy = ReadPause.YOU
        return emptyList()
    }

    private fun pause(why: ReadPause): List<ReadAction> {
        val a = silence()
        state = ReadState.PAUSED
        pausedBy = why
        lockAtEnd = false
        cursor.stop()
        return a
    }

    private fun silence(): List<ReadAction> {
        if (speaking < 0) return emptyList()
        speaking = -1
        return listOf(ReadAction.Silence)
    }

    private fun next(): List<ReadAction> {
        val p = cursor.piece() ?: return end()
        speaking = cursor.current
        return listOf(ReadAction.Speak(cursor.current, p))
    }

    private fun end(): List<ReadAction> {
        if (state == ReadState.NONE) return emptyList()
        val a = silence()
        state = ReadState.NONE
        pausedBy = null
        cursor.stop()
        return a + ReadAction.End
    }

    companion object {
        /** A pause this long ends the reading. */
        const val PAUSE_ENDS_MS = 30 * 60_000L
    }
}
