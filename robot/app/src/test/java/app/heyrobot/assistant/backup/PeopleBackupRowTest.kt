// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import app.heyrobot.core.PeopleFile
import app.heyrobot.core.PeopleLoad
import app.heyrobot.core.Person
import app.heyrobot.core.backup.BackupCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** seal-people-fix 1: Back up now's People row never says Empty when People can't be opened, and keeps your choice. */
class PeopleBackupRowTest {
    @Test fun peopleThatCantBeOpenedIsNeverEmpty() {
        for (load in listOf(PeopleLoad.NotNow, PeopleLoad.Unreadable, PeopleLoad.TooNew(9))) {
            val s = BackupSources.peopleSummary(load)
            assertFalse("$load", s.empty)
            assertEquals(-1, s.count)
            assertTrue(BackupSources.choosable(BackupCategory.PEOPLE, s))
        }
        assertEquals(0, BackupSources.peopleSummary(PeopleLoad.NotNow).extra)
        assertEquals(BackupSources.PEOPLE_DAMAGED, BackupSources.peopleSummary(PeopleLoad.Unreadable).extra)
        assertEquals(BackupSources.PEOPLE_DAMAGED, BackupSources.peopleSummary(PeopleLoad.TooNew(9)).extra)
    }

    // fix-seal-14-lows-b 1: People's copies kept aside are never backed up, and its line says how many.
    @Test fun copiesKeptAsideAreCountedOnPeoplesLine() {
        val one = PeopleLoad.Ok(PeopleFile(listOf(Person("p1", "A"))))
        assertEquals(1, BackupSources.peopleSummary(one, aside = 1).aside)
        assertEquals(0, BackupSources.peopleSummary(one).aside)
        assertEquals(2, BackupSources.peopleSummary(PeopleLoad.Ok(PeopleFile()), aside = 2).aside)
    }

    @Test fun ignoredAddressesAloneAreNotEmpty() {
        val s = BackupSources.peopleSummary(PeopleLoad.Ok(PeopleFile(ignored = setOf("a@example.org"))))
        assertFalse(s.empty)
        assertEquals(0, s.extra)
        assertTrue(BackupSources.peopleSummary(PeopleLoad.Ok(PeopleFile())).empty)
        assertEquals(2, BackupSources.peopleSummary(PeopleLoad.Ok(PeopleFile(listOf(Person("p1", "A"), Person("p2", "B"))))).extra)
    }

    @Test fun otherRowsThatCantBeReadStayOff() {
        assertFalse(BackupSources.choosable(BackupCategory.MEMORY, BackupSources.Summary(-1, 0)))
        assertFalse(BackupSources.choosable(BackupCategory.PEOPLE, BackupSources.Summary(0, 0)))
        assertFalse(BackupSources.choosable(BackupCategory.PEOPLE, null))
        assertTrue(BackupSources.choosable(BackupCategory.NOTES, BackupSources.Summary(3, 10)))
    }
}
