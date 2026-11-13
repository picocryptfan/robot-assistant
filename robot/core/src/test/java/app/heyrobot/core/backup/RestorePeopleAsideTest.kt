package app.heyrobot.core.backup

import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** fix-seal-14-e 1: People's entries a backup holds but this Robot can't read are said, with how many, before a restore. */
class RestorePeopleAsideTest {
    private fun verified(people: String): VerifiedBackup =
        BackupKit.verify(BackupKit.encrypt(RawRecords().manifest().entry("people", "people.json", BackupKit.text(people), v = 1).end().bytes()))

    @Test fun entriesThatDontReadAreCountedAndSaid() {
        val whole = PeopleCodec.toJson(BackupKit.people())
        val v = verified(whole.replaceFirst("\"people\":[", "\"people\":[{\"id\":\"\"},{\"x\":1},"))
        assertEquals(2, v.people?.people?.size)
        assertEquals(2, v.people?.skipped)
        val said = BackupWords.peopleSetAside(v)
        assertEquals("2 entries in People can't be read and won't come back. The backup still has them.", said?.render(Langs.EN))
        assertEquals("2 entrées dans Personnes ne peuvent pas être lues et ne reviendront pas. La sauvegarde les garde.", said?.render(Langs.of(Lang.FR)))
        assertEquals("1 entry in People can't be read and won't come back. The backup still has it.",
            BackupWords.peopleSetAside(verified(whole.replaceFirst("\"people\":[", "\"people\":[{\"x\":1},")))?.render(Langs.EN))
    }

    @Test fun aWholeFileSaysNothing() {
        assertNull(BackupWords.peopleSetAside(verified(PeopleCodec.toJson(BackupKit.people()))))
    }
}
