// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.headset.MediaKey
import app.heyrobot.core.i18n.HandsFreeStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadSessionTest {
    private val text = ReadChunker.plain((1..12).joinToString(" ") { "This is sentence number $it of the article." })
    private val places = MemoryReadPlaces()
    private val note = ReadSource(ReadKind.NOTE, "n1")

    private fun session(src: ReadSource = note) = ReadSession(ReadCursor(src, text, places))
    private fun spoken(a: List<ReadAction>) = a.filterIsInstance<ReadAction.Speak>().map { it.index }

    @Test fun piecesAreSpokenOneByOneToTheEnd() {
        val s = session()
        assertEquals(listOf(0), spoken(s.start(locked = false)))
        for (i in 0 until text.pieces.size - 1) assertEquals(listOf(i + 1), spoken(s.pieceEnded(i, whole = true, lockedNow = false)))
        val last = s.pieceEnded(text.pieces.size - 1, whole = true, lockedNow = false)
        assertTrue(ReadAction.End in last)
        assertTrue(ReadAction.Say(HandsFreeStrings.readEnd) in last)
        assertNull(places.get(note))
    }

    @Test fun pauseAndKeepReadingRepeatTheSentenceThatWasCutOff() {
        val s = session()
        s.start(false)
        s.pieceEnded(0, true, lockedNow = false)
        assertEquals(listOf(ReadAction.Silence), s.command(ReadCommand.Pause, false))
        assertEquals(ReadState.PAUSED, s.state)
        assertTrue(s.pieceEnded(1, whole = false, lockedNow = false).isEmpty())
        assertEquals(ReadPlace(note, 1, text.pieces[1].start), places.get(note))
        assertEquals(listOf(1), spoken(s.command(ReadCommand.Resume, false)))
    }

    @Test fun aTapPausesAndResumesAndADoubleTapGoesBack() {
        val s = session()
        s.start(false)
        repeat(6) { s.pieceEnded(it, true, lockedNow = false) }
        assertEquals(listOf(ReadAction.Silence), s.key(MediaKey.PLAY_PAUSE, at = 10_000, locked = false))
        assertEquals(listOf(6), spoken(s.key(MediaKey.HEADSETHOOK, at = 20_000, locked = false)))
        val back = s.key(MediaKey.PLAY_PAUSE, at = 20_300, locked = false)
        assertTrue(spoken(back).single() <= 4)
        assertEquals(ReadState.PLAYING, s.state)
        assertTrue(s.key(MediaKey.NEXT, at = 30_000, locked = false).isEmpty())
    }

    @Test fun aShortFocusLossResumesByItselfACallDoesNot() {
        val s = session()
        s.start(false)
        s.focusLost(short = true)
        assertEquals(listOf(0), spoken(s.focusGained(false)))
        s.call()
        assertEquals(ReadPause.CALL, s.pausedBy)
        assertTrue(s.focusGained(false).isEmpty())
        s.focusLost(short = false)
        assertEquals(ReadState.PAUSED, s.state)
    }

    @Test fun aLongPauseEndsTheReadingAndKeepsTheNotesPlace() {
        val s = session()
        s.start(false)
        s.pieceEnded(0, true, lockedNow = false)
        assertTrue(s.pausedTooLong().isEmpty())
        s.command(ReadCommand.Pause, false)
        assertEquals(listOf(ReadAction.End), s.pausedTooLong())
        assertEquals(ReadState.NONE, s.state)
        assertEquals(1, places.get(note)?.piece)
    }

    @Test fun goBackFromAPauseReadsOnAndSaysOnlyWhatItDid() {
        var call = false
        val s = ReadSession(ReadCursor(note, text, places), inCall = { call })
        s.start(false)
        repeat(6) { s.pieceEnded(it, true, lockedNow = false) }
        s.command(ReadCommand.Pause, false)
        val back = s.command(ReadCommand.Back, false)
        assertTrue(spoken(back).single() <= 4)
        assertEquals(HandsFreeStrings.readStarting, s.reply(ReadCommand.Back, back))
        s.command(ReadCommand.Pause, false)
        call = true
        val inCall = s.command(ReadCommand.Back, false)
        assertTrue(spoken(inCall).isEmpty())
        assertEquals(HandsFreeStrings.readPaused, s.reply(ReadCommand.Back, inCall))
        assertEquals(HandsFreeStrings.readPaused, s.reply(ReadCommand.Resume, s.command(ReadCommand.Resume, false)))
    }

    @Test fun aLockTheScreenOffMissedIsSeenWhenThePieceEnds() {
        for (lock in listOf(true, null)) {
            val s = session(ReadSource(ReadKind.NOTE, "lock-$lock"))
            s.start(false)
            assertEquals(listOf(ReadAction.Say(HandsFreeStrings.readUnlockRest)), s.pieceEnded(0, true, lockedNow = lock))
            assertEquals(ReadPause.LOCK, s.pausedBy)
        }
        val shared = session(ReadSource(ReadKind.SHARED, "s1"))
        shared.start(false)
        assertEquals(listOf(ReadAction.Say(HandsFreeStrings.readUnlockRest)), shared.pieceEnded(0, true, lockedNow = true))
    }

    @Test fun aShortLossKeepsTheFocusUntilACallOrYourPauseTakesIt() {
        val s = session()
        s.start(false)
        s.focusLost(short = true)
        assertTrue(s.holdsFocus)
        s.call()
        assertEquals(ReadPause.CALL, s.pausedBy)
        assertFalse(s.holdsFocus)
        assertTrue(s.focusGained(false).isEmpty())
        val y = session()
        y.start(false)
        y.focusLost(short = true)
        y.command(ReadCommand.Pause, false)
        assertFalse(y.holdsFocus)
        assertTrue(y.focusGained(false).isEmpty())
    }

    @Test fun aPausedFileEndsWhenRobotFilesIsNoLongerOpen() {
        var open = true
        val s = ReadSession(ReadCursor(ReadSource(ReadKind.FILE, "f1"), text, places), mayGoOn = { open })
        s.start(false)
        s.command(ReadCommand.Pause, false)
        open = false
        assertEquals(listOf(ReadAction.End, ReadAction.Say(HandsFreeStrings.readNoFile)), s.command(ReadCommand.Resume, false))
        assertEquals(ReadState.NONE, s.state)
    }

    @Test fun nothingResumesDuringACallWhateverAsks() {
        var call = false
        val s = ReadSession(ReadCursor(note, text, places), inCall = { call })
        s.start(false)
        s.focusLost(short = true)
        call = true
        assertTrue(s.focusGained(false).isEmpty())
        assertTrue(spoken(s.key(MediaKey.PLAY, at = 10_000, locked = false)).isEmpty())
        assertTrue(spoken(s.command(ReadCommand.Resume, false)).isEmpty())
        assertEquals(ReadState.PAUSED, s.state)
        assertEquals(ReadPause.CALL, s.pausedBy)
        call = false
        assertTrue(s.focusGained(false).isEmpty())
        assertEquals(listOf(0), spoken(s.command(ReadCommand.Resume, false)))
    }

    @Test fun theLockPausesAtTheEndOfThePieceAndNothingStartsWhileLocked() {
        val s = session()
        assertEquals(listOf(ReadAction.Say(HandsFreeStrings.readUnlockFirst), ReadAction.End), s.start(locked = null))
        assertEquals(listOf(ReadAction.Say(HandsFreeStrings.readUnlockFirst), ReadAction.End), s.start(locked = true))
        s.start(false)
        assertTrue(s.locked().isEmpty())
        val at = s.pieceEnded(0, true, lockedNow = false)
        assertEquals(listOf(ReadAction.Say(HandsFreeStrings.readUnlockRest)), at)
        assertEquals(ReadPause.LOCK, s.pausedBy)
        assertEquals(listOf(ReadAction.Say(HandsFreeStrings.readUnlockRest)), s.command(ReadCommand.Resume, locked = true))
        assertEquals(listOf(1), spoken(s.command(ReadCommand.Resume, locked = false)))
    }

    @Test fun heldByOutputGuardStopsAtOnceAndStopKeepsANotesPlace() {
        val s = session()
        s.start(false)
        assertEquals(listOf(ReadAction.Silence), s.held())
        assertEquals(ReadPause.HELD, s.pausedBy)
        assertEquals(listOf(ReadAction.End), s.command(ReadCommand.Stop, false))
        assertEquals(0, places.get(note)!!.piece)
        val again = ReadSession(ReadCursor(note, text, places))
        s.pieceEnded(0, false, lockedNow = false)
        assertTrue(again.start(false).isNotEmpty())
    }

    @Test fun aResumedNoteSaysWhereItPicksUpASharedPageKeepsNothing() {
        val s = session()
        s.start(false)
        s.pieceEnded(0, true, lockedNow = false)
        s.command(ReadCommand.Stop, false)
        val r = session().start(false)
        assertEquals(ReadAction.Say(HandsFreeStrings.readPickUp), r.first())
        assertEquals(listOf(1), spoken(r))
        val shared = ReadSource(ReadKind.SHARED, "x")
        val sh = session(shared)
        sh.start(false)
        sh.pieceEnded(0, true, lockedNow = false)
        sh.command(ReadCommand.Stop, false)
        assertNull(places.get(shared))
        assertEquals(listOf(ReadAction.Say(HandsFreeStrings.readNothingOn)), sh.command(ReadCommand.Resume, false))
    }

    /** fix-stage-reading H1: a Phone lock for someone else ends the reading at once, the piece playing cut off, nothing said. */
    @Test fun aLockForSomeoneElseEndsTheReadingAtOnce() {
        val s = session()
        s.start(false)
        assertEquals(listOf(ReadAction.Silence, ReadAction.End), s.lockForSomeoneElse())
        assertEquals(ReadState.NONE, s.state)
        val p = session()
        p.start(false)
        p.command(ReadCommand.Pause, false)
        assertEquals(listOf(ReadAction.End), p.lockForSomeoneElse())
        assertEquals(ReadState.NONE, p.state)
    }
}
