// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.SkillEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F40: a file this build writes, read by the reader from before `address_marks` (OldConnectionCodec). */
class ConnectionCodecOldReaderTest {
    private val all = listOf(ConnFixtures.homeAssistant(), ConnFixtures.ntfy(), ConnFixtures.webhook(), ConnFixtures.discord(),
        ConnFixtures.routing(), ConnFixtures.handOff())
    private val marked = ConnFixtures.discord().copy(addressMarks = setOf(SkillEffect.MONEY))

    @Test fun aFileWithNoMarksReadsWholeInTheOldReader() {
        val text = ConnectionCodec.encode(all)
        assertFalse(text.contains("address_marks"))
        assertEquals(ConnFile.Ok(all), OldConnectionCodec.decode(text))
        assertEquals(OldConnectionCodec.encode(all), text)
    }

    /** The key is refused for its connection alone, so the file isn't damaged and every other connection stays. */
    @Test fun aMarkedConnectionIsDroppedAloneInTheOldReader() {
        val others = all.filter { it.id != marked.id }
        val text = ConnectionCodec.encode(others + marked)
        assertTrue(text.contains("address_marks"))
        assertEquals(ConnFile.Ok(others, dropped = 1), OldConnectionCodec.decode(text))
    }

    @Test fun anOldFileReadsWholeInThisReader() {
        assertEquals(ConnFile.Ok(all), ConnectionCodec.decode(OldConnectionCodec.encode(all)))
    }
}
