// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.PeopleFile
import app.heyrobot.core.PeopleLoad
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** seal-people-fix 1: the restore preview says this phone's People can't be opened, never that it has nobody. */
class PeopleHereTest {
    private val v = BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords()))
    private fun plan(phone: PhoneHas) = RestorePlanner.plan(v, phone, RestorePlanner.defaults(v, phone), mine = true).of(BackupCategory.PEOPLE)

    @Test fun eachLoadSaysWhere() {
        assertEquals(PeopleHere.OK, PeopleHere.of(PeopleLoad.Ok(PeopleFile())))
        assertEquals(PeopleHere.NOT_NOW, PeopleHere.of(PeopleLoad.NotNow))
        assertEquals(PeopleHere.UNREADABLE, PeopleHere.of(PeopleLoad.Unreadable))
        assertEquals(PeopleHere.UNREADABLE, PeopleHere.of(PeopleLoad.TooNew(3)))
    }

    @Test fun peopleThatCantBeOpenedIsLeftOutWithItsWords() {
        assertTrue(v.has(BackupCategory.PEOPLE))
        assertNull(plan(PhoneHas(mapOf(BackupCategory.PEOPLE to 2))).skip)
        val notNow = plan(PhoneHas(mapOf(BackupCategory.PEOPLE to 1), people = PeopleHere.NOT_NOW))
        assertEquals(PlanSkip.PEOPLE_NOT_NOW, notNow.skip)
        assertEquals("Robot can't open People on this phone just now, so it leaves People out. Unlock, wait a minute and restore again.",
            BackupWords.skip(notNow, "1.3.0")!!.render(Langs.EN))
        val damaged = plan(PhoneHas(mapOf(BackupCategory.PEOPLE to 1), people = PeopleHere.UNREADABLE))
        assertEquals(PlanSkip.PEOPLE_UNREADABLE, damaged.skip)
        assertTrue(BackupWords.skip(damaged, "1.3.0")!!.render(Langs.EN).contains("Start over in People"))
        assertTrue(BackupWords.skip(damaged, "1.3.0")!!.render(Langs.of(Lang.FR)).startsWith("Robot ne peut pas lire les Personnes"))
        // People's state never decides another category's.
        assertNull(RestorePlanner.plan(v, PhoneHas(emptyMap(), people = PeopleHere.UNREADABLE), RestorePlanner.defaults(v), mine = true).of(BackupCategory.NOTES).skip)
    }
}
