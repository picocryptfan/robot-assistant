// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** seal-people-fix 5: while People is sealed, the chosen people's numbers only, hashed, no names, checked against the lock's ids now. */
class LockCallsTest {
    private val mom = Person("p1", "Mom Martin", phones = listOf(ContactPhone("+1 514 555 0101", PhoneKind.MOBILE, "Mobile"), ContactPhone("514-555-0101", PhoneKind.HOME, "Home")))
    private val bob = Person("p2", "Bob Tremblay", phones = listOf(ContactPhone("+1 514 555 0199", PhoneKind.MOBILE, "Mobile")))
    private fun key(n: String) = n.filter { it.isDigit() }.takeLast(10)
    private fun mac(k: String) = "h(" + k.reversed() + ")"

    @Test fun onlyTheChosenPeoplesNumbersHashedAndNoNames() {
        val e = LockCalls.entries(listOf(mom, bob), setOf("p1"), { listOf(key(it)) }, ::mac)!!
        assertEquals(listOf(LockCalls.Entry("p1", mac("5145550101"))), e)
        val json = LockCalls.toJson(e)
        for (word in listOf("Mom", "Martin", "5145550101", "Bob", "0199")) assertFalse(word, json.contains(word))
        assertEquals(e, LockCalls.decode(json))
        // A number that can't be hashed: no list at all (fail closed).
        assertNull(LockCalls.entries(listOf(mom), setOf("p1"), { listOf(key(it)) }) { null })
    }

    @Test fun checkedAgainstTheIdsTheLocksNameNow() {
        val e = LockCalls.entries(listOf(mom, bob), setOf("p1", "p2"), { listOf(key(it)) }, ::mac)
        assertTrue(LockCalls.allows(e, setOf("p1"), listOf("5145550101"), ::mac))
        assertFalse(LockCalls.allows(e, setOf("p2"), listOf("5145550101"), ::mac))
        assertFalse(LockCalls.allows(e, setOf("p1"), listOf("5145550142"), ::mac))
        assertFalse(LockCalls.allows(e, setOf("p1"), listOf("5145550101")) { null })
        assertFalse(LockCalls.allows(null, setOf("p1"), listOf("5145550101"), ::mac))
        assertFalse(LockCalls.allows(e, emptySet(), listOf("5145550101"), ::mac))
        assertNull(LockCalls.decode("""{"v":3,"calls":[]}"""))
        assertNull(LockCalls.decode("{not json"))
    }

    // seal-small 2: both numbers with a country code are compared in full; otherwise by their last 10 digits, as before.
    @Test fun aCountryCodeOnBothSidesComparesTheWholeNumber() {
        val uk = Person("p3", "Ann", phones = listOf(ContactPhone("+1 207 946 0958", PhoneKind.MOBILE, "Mobile")))
        val home = Person("p4", "Lea", phones = listOf(ContactPhone("418 555 0123", PhoneKind.HOME, "Home")))
        val e = LockCalls.entries(listOf(uk, home), setOf("p3", "p4"), CallKeys::chosen, ::mac)
        fun ok(n: String) = LockCalls.allows(e, setOf("p3", "p4"), CallKeys.asked(n), ::mac)
        assertFalse(ok("+44 20 7946 0958"))
        assertFalse(ok("0044 20 7946 0958"))
        assertTrue(ok("+12079460958"))
        assertTrue(ok("001 207 946 0958"))
        assertTrue(ok("207-946-0958"))
        assertTrue(ok("+1 418 555 0123"))
        assertTrue(ok("418.555.0123"))
        assertFalse(ok("418 555 0124"))
        assertFalse(ok(""))
        assertNull(CallKeys.international("207 946 0958"))
        assertEquals("442079460958", CallKeys.international("011 44 20 7946 0958"))
        assertNull(LockCalls.decode("""{"v":1,"calls":[]}"""))
    }
}
