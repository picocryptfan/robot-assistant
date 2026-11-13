// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.XmppAddress
import app.heyrobot.core.feedback.ReportHide
import app.heyrobot.sys.PeopleFiles
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** report-hide-people 1: a bug report hides what's only in Robot's People, read through PeopleFiles; unread, it says so. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportPeopleTest {
    private val ctx = RuntimeEnvironment.getApplication()

    init { PeopleFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()) }

    @Before fun clean() { PeopleFiles.store(ctx).deleteAll(); PeopleFiles.dir(ctx).listFiles()?.forEach { it.deleteRecursively() } }
    @After fun cleanAfter() = clean()

    @Test fun peoplesNamesNumbersAndAddressesAreHidden() {
        assertTrue(PeopleFiles.store(ctx).save(Person("6f0c2a3e-0000-4000-8000-000000000011", "Zébulon Quarrington", aliases = listOf("Zeb Q"),
            nicknames = listOf("Zubby"), phones = listOf(ContactPhone("902-555-0147", PhoneKind.MOBILE, "")),
            emails = listOf("zq@example.org"), xmpp = listOf(XmppAddress("zeb@chat.example.org")))))
        val t = ReportPeople.read(ctx)!!
        assertTrue(t.whole)
        assertTrue(t.names.containsAll(listOf("Zébulon Quarrington", "Zeb Q", "Zubby")))
        val out = ReportHide.hide(ReportHide.Turn("text Zubby at 55-0147", "Texting Zébulon Quarrington at zeb@chat.example.org.", "send_text(name: Zubby)"),
            t.names, t.numbers, t.addresses)
        val all = listOfNotNull(out.said, out.answer, out.tool).joinToString("\n")
        for (w in listOf("Zubby", "Zebulon", "Quarrington", "0147", "zeb")) assertFalse(w + " in " + all, all.contains(w, ignoreCase = true))
    }

    @Test fun nobodyEverInPeopleIsChecked() {
        val t = ReportPeople.read(ctx)
        assertNotNull(t)
        assertEquals(emptyList<String>(), t!!.names)
    }

    @Test fun emptyPeopleBesideTheSealsLockIsChecked() {
        // fix-bug-card-14 M1: the seals keep a .lock file in People's folder; an empty People is still read, not "can't read".
        File(PeopleFiles.dir(ctx), ".lock").writeText("")
        val t = ReportPeople.read(ctx)
        assertNotNull(t)
        assertEquals(emptyList<String>(), t!!.names)
    }

    @Test fun peopleThatCantBeReadIsNotChecked() {
        // Damaged (a read error): nobody is listed, so the report must say People wasn't checked rather than send it unhidden.
        File(PeopleFiles.dir(ctx), "people.json").writeText("{not json")
        assertNull(ReportPeople.read(ctx))
    }

    @Test fun peopleWithAnEntryThatDoesntParseIsNotChecked() {
        // fix-seal-14 L4: one entry that doesn't parse (a newer Robot's, or damage) means People wasn't read whole: the note shows.
        // fix-seal-14-c M1: and the entries it could read are still hidden.
        File(PeopleFiles.dir(ctx), "people.json").writeText("""{"v":1,"people":[{"id":"6f0c2a3e-0000-4000-8000-000000000013","name":"Ada Lovelace"},""" +
            """{"id":"not an id","name":"Bo Quist"}]}""")
        val t = ReportPeople.read(ctx)!!
        assertFalse(t.whole)
        assertEquals(listOf("Ada Lovelace"), t.names)
    }

    @Test fun aCopyKeptAsideNeverStopsTheReadableOnesBeingHidden() {
        // fix-seal-14-c M1: a copy kept aside (Start over's, or an entry that didn't parse) never turns hiding off for everyone else.
        assertTrue(PeopleFiles.store(ctx).save(Person("6f0c2a3e-0000-4000-8000-000000000014", "Ada Lovelace", phones = listOf(ContactPhone("902-555-0148", PhoneKind.MOBILE, "")))))
        File(PeopleFiles.dir(ctx), "people.json.bad").writeText("""{"v":1,"people":[{"id":"6f0c2a3e-0000-4000-8000-000000000015","name":"Bo Quist"}]}""")
        val t = ReportPeople.read(ctx)
        assertTrue(t != null && "Ada Lovelace" in t.names && "902-555-0148" in t.numbers)
        // fix-seal-14-d 1: a copy kept aside that opens is hidden too, so People counts as read whole.
        assertTrue("Bo Quist" in t!!.names)
        assertTrue(t.whole)
    }

    @Test fun aCopyKeptAsideWithAnEntryThatDoesntParseIsReadInPart() {
        // fix-seal-14-d 1: what a kept-aside copy holds is hidden; an entry in it that didn't parse means People was read in part.
        assertTrue(PeopleFiles.store(ctx).save(Person("6f0c2a3e-0000-4000-8000-000000000016", "Ada Lovelace")))
        File(PeopleFiles.dir(ctx), "people.json.bad").writeText("""{"v":1,"people":[{"id":"6f0c2a3e-0000-4000-8000-000000000017","name":"Bo Quist",""" +
            """"phones":[{"number":"902-555-0149","kind":"MOBILE"}]},{"id":"not an id","name":"Cy Rook"}]}""")
        val t = ReportPeople.read(ctx)!!
        assertTrue(t.names.containsAll(listOf("Ada Lovelace", "Bo Quist")) && "902-555-0149" in t.numbers)
        assertFalse(t.whole)
    }

    @Test fun theHideNoteSaysHowMuchOfPeopleWasRead() {
        // fix-seal-14-d 2: the bug card's line, on its own: no turn (nothing to hide in) or read whole, whole; read in part, part; unread, none.
        val some = ReportPeople.of(listOf(Person("6f0c2a3e-0000-4000-8000-000000000019", "Ada Lovelace")), whole = false)
        assertEquals(PeopleRead.WHOLE, peopleRead(people = false, folks = null))
        assertEquals(PeopleRead.WHOLE, peopleRead(people = false, folks = some))
        assertEquals(PeopleRead.WHOLE, peopleRead(people = true, folks = ReportPeople.of(emptyList())))
        assertEquals(PeopleRead.PART, peopleRead(people = true, folks = some))
        assertEquals(PeopleRead.NONE, peopleRead(people = true, folks = null))
        // And as read: People in part (an entry that didn't parse) is PART, never NONE.
        File(PeopleFiles.dir(ctx), "people.json").writeText("""{"v":1,"people":[{"id":"not an id","name":"Bo Quist"}]}""")
        assertEquals(PeopleRead.PART, peopleRead(people = true, folks = ReportPeople.read(ctx)))
    }

    @Test fun aStartOverCopyThatCantBeOpenedLeavesPeopleWhole() {
        // fix-seal-14-d 1: Start over keeps a damaged copy aside; nothing in it can be read, so it never shows the note on every report.
        assertTrue(PeopleFiles.store(ctx).save(Person("6f0c2a3e-0000-4000-8000-000000000018", "Ada Lovelace")))
        File(PeopleFiles.dir(ctx), "people.enc.bad").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(PeopleFiles.dir(ctx), "people.json.bad2").writeText("{not json")
        val t = ReportPeople.read(ctx)!!
        assertEquals(listOf("Ada Lovelace"), t.names)
        assertTrue(t.whole)
    }
}
