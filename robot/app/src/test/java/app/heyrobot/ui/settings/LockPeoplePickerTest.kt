// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.R
import app.heyrobot.core.PeopleFile
import app.heyrobot.core.PeopleLoad
import app.heyrobot.core.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** seal-people-fix 3: Phone lock's People picker never saves an empty list over a lock's People because People couldn't be opened. */
class LockPeoplePickerTest {
    private val mary = Person("6f0c2a3e-0000-4000-8000-000000000001", "Mary")
    private val bob = Person("6f0c2a3e-0000-4000-8000-000000000002", "Bob")

    @Test fun peopleThatCantBeOpenedIsNullNeverNobody() {
        assertNull(LockPeople.openable { PeopleLoad.NotNow })
        assertNull(LockPeople.openable { PeopleLoad.Unreadable })
        assertNull(LockPeople.openable { PeopleLoad.TooNew(4) })
        assertNull(LockPeople.openable { throw IllegalStateException("busy") })
        assertEquals(listOf(mary), LockPeople.openable { PeopleLoad.Ok(PeopleFile(listOf(mary))) })
    }

    /** seal-people-fix-b 1: damaged or a newer Robot's file says Start over or update, never "unlock your phone", even with nobody picked. */
    @Test fun theRowSaysWhatHelps() {
        val damaged = LockPeople.opened { PeopleLoad.Unreadable }
        assertEquals(LockPeople.Opened(null, damaged = true), damaged)
        assertEquals(damaged, LockPeople.opened { PeopleLoad.TooNew(4) })
        assertEquals(LockPeople.Opened(null), LockPeople.opened { PeopleLoad.NotNow })
        assertEquals(R.string.lock_extra_people_damaged, LockPeople.row(listOf(mary.id), damaged))
        assertEquals(R.string.lock_extra_people_damaged, LockPeople.row(emptyList(), damaged))
        assertEquals(R.string.lock_extra_people_closed, LockPeople.row(emptyList(), LockPeople.Opened(null)))
        assertEquals(R.string.lock_none, LockPeople.row(emptyList(), LockPeople.Opened(listOf(mary))))
        assertEquals(R.string.lock_none, LockPeople.row(emptyList(), null))
        assertNull(LockPeople.row(listOf(mary.id), LockPeople.Opened(listOf(mary))))
    }

    @Test fun saveKeepsOnlyWhenPeopleIsOpen() {
        assertNull(LockPeople.kept(listOf(mary.id, bob.id), null))
        assertEquals(listOf(mary.id), LockPeople.kept(listOf(mary.id, bob.id), listOf(mary)))
        // People open and truly empty: a person since deleted is dropped.
        assertEquals(emptyList<String>(), LockPeople.kept(listOf(bob.id), emptyList()))
    }
}
