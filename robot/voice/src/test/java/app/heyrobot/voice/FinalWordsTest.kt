// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 1.1 (words-hedge, bug 1): words the screen showed while you spoke are never thrown away by an empty last pass. */
class FinalWordsTest {
    private var mark = 0L

    @Before fun fresh() {
        VoiceLog.resetForTests()
        mark = VoiceLog.total
    }

    private fun events(): List<VoiceLogEntry> = VoiceLog.entries().take((VoiceLog.total - mark).toInt()).reversed()

    private val shown = "ask Claude what the tire chain rules are on the Island"

    @Test fun emptyFinalWithLiveTextUsesTheLiveText() {
        val text = FinalWords.pick(final = "", lastShown = shown, heardSpeech = true, audioMs = 24_000, shownAudioMs = 22_600, decodeMs = 900)
        assertEquals(shown, text)
    }

    @Test fun theSwapIsLoggedAsItsOwnEventWithNumbersOnly() {
        FinalWords.pick(final = "", lastShown = shown, heardSpeech = true, audioMs = 24_000, shownAudioMs = 22_600, decodeMs = 900)
        val e = events()
        assertEquals(listOf(VoiceEvent.STT_USED_SHOWN), e.map { it.event })
        assertEquals(shown.length, e[0].a)
        assertEquals(24_000, e[0].b)
        assertEquals(22_600, e[0].c)
        val line = VoiceLogText.describe(e[0])
        assertTrue(line, line.startsWith("Used the words shown: the last pass found none"))
        assertTrue("never the words", !line.contains("Claude"))
    }

    @Test fun emptyFinalWithNoLiveTextStaysEmpty() {
        // The panel then says "Sorry, I didn't catch that." (missedQuestion(NoQuestion.EMPTY)), as before.
        assertEquals("", FinalWords.pick(final = "", lastShown = "", heardSpeech = true, audioMs = 5_000, shownAudioMs = 0, decodeMs = 300))
        assertEquals(listOf(VoiceEvent.STT_EMPTY), events().map { it.event })
        assertEquals(1, events()[0].b)
    }

    @Test fun noSpeechHeardKeepsTheEmptyFinal() {
        // A live text without speech heard can't happen in practice; if it did, the old path stands.
        assertEquals("", FinalWords.pick(final = "", lastShown = "uh", heardSpeech = false, audioMs = 2_000, shownAudioMs = 1_500, decodeMs = 100))
        assertEquals(listOf(VoiceEvent.STT_EMPTY), events().map { it.event })
    }

    @Test fun aFinalWithWordsWinsOverTheLiveText() {
        assertEquals("ask Claude about chains", FinalWords.pick(final = "ask Claude about chains", lastShown = "ask Claude about",
            heardSpeech = true, audioMs = 4_000, shownAudioMs = 3_300, decodeMs = 400))
        val e = events()
        assertEquals(listOf(VoiceEvent.STT_DONE), e.map { it.event })
        assertEquals(listOf(23, 4_000, 400), listOf(e[0].a, e[0].b, e[0].c))
    }

    @Test fun blankLiveTextIsNotUsed() {
        assertEquals("", FinalWords.pick(final = "", lastShown = "  ", heardSpeech = true, audioMs = 3_000, shownAudioMs = 2_000, decodeMs = 200))
    }

    @Test fun theNewEventKeepsAnIdOfItsOwn() {
        assertEquals(350, VoiceEvent.STT_USED_SHOWN.id)
    }
}
