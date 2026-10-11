package app.heyrobot.sys

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import app.heyrobot.core.PhoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the phone's contact picker hands back, read into a person. */
class PhoneContactsTest {
    private fun row(contact: String?, mime: String, data1: String, type: String? = null) =
        PhoneContacts.PickedRow(contact, "Zoé Martin", mime, data1, type, null)

    @Test fun rowsThatNameNoContactAreAllThePickedOnes() {
        // Review 0.4.0 #15: the narrow projection's _ID was a data row's, so only its first number was kept.
        val rows = listOf(row(null, Phone.CONTENT_ITEM_TYPE, "+1 514 555 0101"), row(null, Phone.CONTENT_ITEM_TYPE, "+1 514 555 0202"),
            row(null, Email.CONTENT_ITEM_TYPE, "zoe@example.org"))
        val p = PhoneContacts.person(rows, 1, "x")!!
        assertEquals(listOf("+1 514 555 0101", "+1 514 555 0202"), p.phones.map { it.number })
        assertEquals(listOf("zoe@example.org"), p.emails)
    }

    @Test fun rowsThatNameAContactKeepOnlyTheFirstContacts() {
        val rows = listOf(row("7", Phone.CONTENT_ITEM_TYPE, "+1 514 555 0101", Phone.TYPE_MOBILE.toString()),
            row("8", Phone.CONTENT_ITEM_TYPE, "+1 514 555 0303"), row("7", Phone.CONTENT_ITEM_TYPE, "+1 514 555 0202", Phone.TYPE_HOME.toString()))
        val p = PhoneContacts.person(rows, 1, "x")!!
        assertEquals(listOf("+1 514 555 0101", "+1 514 555 0202"), p.phones.map { it.number })
        assertEquals(listOf(PhoneKind.MOBILE, PhoneKind.HOME), p.phones.map { it.kind })
        assertNull(PhoneContacts.person(emptyList(), 1, "x"))
    }
}
