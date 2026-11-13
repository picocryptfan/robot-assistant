// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice log's BACKUP and RESTORE events keep categories as bits in an Int (BackupExport.bits, VoiceLog's cats). */
class BackupCategoryBitsTest {
    @Test fun categoriesFitTheVoiceLogsIntBits() {
        val n = BackupCategory.entries.size
        assertTrue("BackupCategory has $n categories: the 32nd takes the Int's last bit and a 33rd wraps to bit 0 (1 shl 32 == 1). " +
            "Before adding it, move BackupExport.bits and VoiceLog's cats to a Long or two Ints for BACKUP and RESTORE, read old " +
            "entries as before, then raise this limit.", n < 32)
        assertEquals(n, BackupCategory.entries.map { BackupExport.bits(listOf(it)) }.toSet().size)
    }

    /** The spoken log's place, appended after MOMENTS: its own, so a category appended later never moves this pin. */
    @Test fun logbookKeepsItsBit() {
        assertEquals(25, BackupCategory.LOGBOOK.ordinal)
        assertEquals(BackupCategory.MOMENTS.ordinal + 1, BackupCategory.LOGBOOK.ordinal)
    }
}
