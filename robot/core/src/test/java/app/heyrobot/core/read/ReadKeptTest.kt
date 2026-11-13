// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainError
import app.heyrobot.core.TurnRules
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.VoiceLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1 step 4: nothing kept but VoiceLog's codes; history only on Keep, and then untrusted. */
class ReadKeptTest {
    private val reading = ReadAloud.parse("letter fr\nCher Sam, ton colis est prêt.")

    @Test fun theLogHasCodesOnly() {
        assertEquals(360, VoiceEvent.READ_ALOUD.id)
        assertEquals(ReadCode.READ, ReadCode.of(null, false, reading))
        assertEquals(ReadCode.NO_WORDS, ReadCode.of(null, false, ReadAloud.parse("sign en")))
        assertEquals(ReadCode.NO_VISION, ReadCode.of(BrainError.NO_VISION, false, null))
        assertEquals(ReadCode.STOPPED, ReadCode.of(null, true, reading))
        assertEquals(ReadCode.FAILED, ReadCode.of(BrainError.ENGINE, false, null))
        val line = VoiceLogText.describe(VoiceLogEntry(1L, VoiceEvent.READ_ALOUD, ReadCode.READ.id, 1, 3))
        assertTrue(line, line.startsWith("Point and read: read · 1 picture · 3.0 s"))
        for (w in listOf("Sam", "colis", "letter", "lettre", "fr")) assertFalse(w, line.contains(w))
    }

    @Test fun historyOnlyOnKeepAndThenUntrusted() {
        val turns = ReadAloud.kept("lis ça", reading, 5L, "a", "b")
        assertEquals(listOf(Attachment.PHOTO, Attachment.NONE), turns.map { it.attachment })
        assertEquals("Cher Sam, ton colis est prêt.", turns[1].text)
        assertTrue(TurnRules.untrusted(turns))
    }
}
