// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.backup.RestoreRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** seal-people-fix 4: what arrives while People is sealed adds nobody twice, and a restore then keeps People's links. */
class PeopleSealedAddTest {
    private val bob = Person("p2", "Bob Tremblay", phones = listOf(ContactPhone("+1 514 555 0199", PhoneKind.MOBILE, "Mobile")), emails = listOf("bob@example.org"))

    @Test fun aCardJournaledWhileLockedAddsNoSecondCopy() {
        val have = PeopleFile(listOf(bob))
        val byNumber = Person("p9", "Bob", phones = listOf(ContactPhone("+1 514 555 0199", PhoneKind.OTHER, "Other")))
        val byEmail = Person("p8", "B. Tremblay", emails = listOf(" Bob@Example.org"))
        assertSame(have, PeopleStore.apply(have, PeopleOp.Add(byNumber)))
        assertSame(have, PeopleStore.apply(have, PeopleOp.Add(byEmail)))
        val someoneElse = Person("p7", "Bob Tremblay", phones = listOf(ContactPhone("+1 514 555 0142", PhoneKind.MOBILE, "Mobile")))
        assertEquals(listOf(bob, someoneElse), PeopleStore.apply(have, PeopleOp.Add(someoneElse)).people)
    }

    @Test fun aRestoreKeepsPersonLinksWhilePeopleCantBeOpened() {
        for (l in listOf(PeopleLoad.NotNow, PeopleLoad.Unreadable, PeopleLoad.TooNew(5))) assertEquals("p2", RestoreRefs.peopleHere(l)("p2"))
        val ok = RestoreRefs.peopleHere(PeopleLoad.Ok(PeopleFile(listOf(bob))))
        assertEquals("p2", ok("p2"))
        assertNull(ok("p3"))
    }
}
