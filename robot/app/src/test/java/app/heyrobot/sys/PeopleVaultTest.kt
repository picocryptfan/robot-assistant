// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.CheogramSender
import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.PeopleEnvelope
import app.heyrobot.core.PeopleFile
import app.heyrobot.core.PeopleLoad
import app.heyrobot.core.PeopleNotWhole
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.Person
import app.heyrobot.core.SealedOpen
import java.io.File
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** seal-people: People sealed at rest as Notes and History are, with the lock screen's changes kept in a journal and made at unlock. */
class PeopleVaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val keys = SoftHistoryKeys()
    private var lockedNow = false
    private val dir by lazy { tmp.newFolder("people") }
    private fun vault(crashAt: String? = null) = PeopleVault(dir, keys, { lockedNow }) { if (it == crashAt) throw IllegalStateException("cut at $it") }
    private fun store(crashAt: String? = null) = PeopleStore(vault(crashAt)).also { it.drop() }
    private val mary = Person("6f0c2a3e-0000-4000-8000-000000000001", "Mary Ann Smith", aliases = listOf("aunt"))
    private val bob = Person("6f0c2a3e-0000-4000-8000-000000000002", "Bob Tremblay")
    private fun plain(vararg p: Person) = File(dir, "people.json").apply { writeText(PeopleCodec.toJson(p.toList())) }
    private fun names() = dir.list()!!.filter { it != ".lock" }.sorted()
    private fun noPlainWords(word: String) = dir.listFiles()!!.forEach { assertFalse(it.name, String(it.readBytes(), Charsets.ISO_8859_1).contains(word)) }
    private fun notNow(alias: String) { keys.refuse[alias] = UnrecoverableKeyException("busy").apply { initCause(KeyStoreException("keystore")) } }
    private fun opens(f: File) = PeopleEnvelope.open(f.readBytes(), { keys.cipher(it, false) }, f.name) is SealedOpen.Ok

    @Test fun thePlainFileIsSealedReadBackAndRemoved() {
        plain(mary)
        assertEquals(listOf("Mary Ann Smith"), store().list().map { it.name })
        assertEquals(listOf("people.enc"), names())
        noPlainWords("Smith")
        assertEquals(PeopleKeyAliases.UNLOCKED, PeopleEnvelope.frame.alias(File(dir, "people.enc").readBytes()))
    }

    @Test fun aCutAtEachStepLeavesOneWholeCopy() {
        for (at in listOf("seal", "written", "verified", "tmp")) {
            dir.listFiles()!!.forEach { it.delete() }
            plain(mary.copy(name = "Mary $at"))
            if (at == "tmp") File(dir, ".people.enc.tmp").writeBytes(byteArrayOf(1, 2, 3)) else store(crashAt = at).list()
            val whole = PeopleCodec.read(File(dir, "people.json").takeIf { it.isFile }?.readText()) != null || opens(File(dir, "people.enc"))
            assertTrue(at, whole)
            assertEquals(at, listOf("Mary $at"), store().list().map { it.name })
            assertEquals(at, listOf("people.enc"), names())
        }
    }

    @Test fun aPlainFileLeftBesideADifferentSealedOneIsKeptAsideNeverDeleted() {
        store().save(mary)
        plain(bob)
        assertEquals(listOf("Mary Ann Smith"), store().list().map { it.name })
        assertEquals(listOf("people.enc", "people.json.enc.bad"), names())
        noPlainWords("Bob")
    }

    // seal-small 1: a plain copy kept aside (1.2's Start over, or an older pass) is sealed byte for byte, read back, then removed.
    @Test fun aPlainCopyKeptAsideIsSealedReadBackAndRemoved() {
        store().save(mary)
        val words = PeopleCodec.toJson(listOf(bob))
        File(dir, "people.json.bad").writeText(words)
        File(dir, "people.json.bad2").writeText("{not json Bob")
        lockedNow = true
        store().list()
        assertEquals(listOf("people.enc", "people.json.bad", "people.json.bad2"), names())
        lockedNow = false
        notNow(PeopleKeyAliases.UNLOCKED)
        store().list()
        assertTrue(File(dir, "people.json.bad").exists())
        keys.refuse.clear()
        assertEquals(listOf("Mary Ann Smith"), store().list().map { it.name })
        assertEquals(listOf("people.enc", "people.json.enc.bad", "people.json.enc.bad2"), names())
        noPlainWords("Bob")
        val back = PeopleEnvelope.frame.open(File(dir, "people.json.enc.bad").readBytes(), { keys.cipher(it, false) }, "people.json.enc.bad") { it }
        assertTrue(back is SealedOpen.Ok && String(back.value, Charsets.UTF_8) == words)
        assertTrue(store().deleteAll())
        assertEquals(emptyList<String>(), names())
    }

    // fix-seal-14-lows-b 1: an entry that doesn't parse is never lost: the plain file's whole bytes are sealed aside, then it goes.
    @Test fun aPlainFileWithAnEntryThatDoesntParseIsSealedWholeAsideNeverDropped() {
        val words = """{"v":1,"people":[{"id":"${mary.id}","name":"Mary Ann Smith"},{"id":"not an id","name":"Bob Tremblay"}]}"""
        File(dir, "people.json").writeText(words)
        assertEquals(listOf("Mary Ann Smith"), store().list().map { it.name })
        assertEquals(listOf("people.enc", "people.json.enc.bad"), names())
        noPlainWords("Bob")
        val back = PeopleEnvelope.frame.open(File(dir, "people.json.enc.bad").readBytes(), { keys.cipher(it, false) }, "people.json.enc.bad") { it }
        assertTrue(back is SealedOpen.Ok && String(back.value, Charsets.UTF_8) == words)
        assertEquals(1, vault().keptAside())
        assertTrue(store().deleteAll())
        assertEquals(emptyList<String>(), names())
        assertEquals(0, vault().keptAside())
    }

    @Test fun aWholePlainFileBesideAnOlderSealedOneIsNeverDeletedByAWrite() {
        // fix-seal-14-d 3: people.enc was there first, so a change isn't made from the plain file beside it: with nine copies
        // already kept aside it can't be moved, and a write still never deletes it.
        store().save(mary)
        for (n in 1..9) File(dir, "people.json.enc" + if (n == 1) ".bad" else ".bad$n").writeBytes(byteArrayOf(1, 2, 3))
        plain(bob)
        assertTrue(store().save(Person("6f0c2a3e-0000-4000-8000-000000000021", "Cy Rook")))
        assertTrue(File(dir, "people.json").exists() && File(dir, "people.json").readText().contains("Bob"))
    }

    @Test fun aPlainFileWithTwoEntriesOfOneIdIsSealedWholeAsideNeverDropped() {
        // fix-seal-14-d 4: the second entry with the same id is left out of People and counted, so the plain file is kept aside whole.
        val words = """{"v":1,"people":[{"id":"${mary.id}","name":"Mary Ann Smith"},{"id":"${mary.id}","name":"Bob Tremblay"}]}"""
        File(dir, "people.json").writeText(words)
        assertEquals(listOf("Mary Ann Smith"), store().list().map { it.name })
        assertEquals(listOf("people.enc", "people.json.enc.bad"), names())
        val back = PeopleEnvelope.frame.open(File(dir, "people.json.enc.bad").readBytes(), { keys.cipher(it, false) }, "people.json.enc.bad") { it }
        assertTrue(back is SealedOpen.Ok && String(back.value, Charsets.UTF_8) == words)
    }

    @Test fun aChangeAfterAFailedMigrationNeverDropsAnEntryThatDoesntParse() {
        // fix-seal-14-c M2: the migration fails before sealing, People is read from the plain file minus the entry, then a
        // change is written: the plain file is kept aside, sealed whole, never deleted.
        val words = """{"v":1,"people":[{"id":"${mary.id}","name":"Mary Ann Smith"},{"id":"not an id","name":"Zed Unread"}]}"""
        File(dir, "people.json").writeText(words)
        val s = store("seal")
        assertEquals(listOf("Mary Ann Smith"), s.list().map { it.name })
        assertTrue(s.save(bob))
        assertEquals(listOf("people.enc", "people.json.enc.bad"), names())
        val back = PeopleEnvelope.frame.open(File(dir, "people.json.enc.bad").readBytes(), { keys.cipher(it, false) }, "people.json.enc.bad") { it }
        assertTrue(back is SealedOpen.Ok && String(back.value, Charsets.UTF_8) == words)
        assertEquals(listOf("Bob Tremblay", "Mary Ann Smith"), store().list().map { it.name }.sorted())
    }

    @Test fun startOverNeverOverwritesAnOlderSealedCopy() {
        // fix-seal-14-c L1: an older copy already sealed as people.json.enc.bad; Start over keeps a damaged plain file aside as
        // people.json.bad, and sealing it goes under a free name, never over the older copy.
        val older = """{"v":1,"people":[{"id":"${mary.id}","name":"Mary Ann Smith"}]}"""
        File(dir, "people.json.bad").writeText(older)
        store().list()
        assertEquals(listOf("people.json.enc.bad"), names())
        File(dir, "people.json").writeText("{not json")
        val s = store()
        assertTrue(s.damaged())
        assertTrue(s.reset())
        fun open(n: String) = PeopleEnvelope.frame.open(File(dir, n).readBytes(), { keys.cipher(it, false) }, n) { it }.let { (it as? SealedOpen.Ok)?.value?.toString(Charsets.UTF_8) }
        assertEquals(older, open("people.json.enc.bad"))
        assertEquals(listOf("people.json.enc.bad", "people.json.enc.bad2"), names())
        assertEquals("{not json", open("people.json.enc.bad2"))
    }

    @Test fun aMessyPlainFileMigratesInOnePass() {
        // fix-seal-14-c (the review's open question): parse, write and parse again give the same bytes, so migrate's
        // read-back compare passes; else the plain file would stay and be retried at every pass.
        File(dir, "people.json").writeText("""{"v":1,"extra":true,"people":[{"id":"${mary.id}","name":"  Mary  Ann ","aliases":["aunt","aunt",""],""" +
            """"nicknames":[" M "],"phones":[{"number":"902-555-0101","kind":"NOT_A_KIND"},{"number":" "},{"number":"(902) 555-0102","kind":"MOBILE","label":""}],""" +
            """"emails":["Mary@Example.org",""],"xmpp":[{"jid":"m@chat.example.org"},{}],"unknown":{"a":1}},""" +
            """{"id":"${bob.id}","name":"Bob Tremblay"}],"ignored":[" A@B.org ","",3]}""")
        val s = store()
        assertEquals(2, s.list().size)
        assertEquals(listOf("people.enc"), names())
        assertEquals(s.list().map { it.name }, store().list().map { it.name })
    }

    @Test fun whileLockedNobodyIsListedAndALearnedSenderIsJournaledUnderTheLockKeyThenMadeAtUnlock() {
        store().save(mary)
        lockedNow = true
        val s = store()
        assertTrue(s.list().isEmpty())
        assertTrue(s.notNow())
        assertFalse(s.damaged())
        assertTrue(s.learn(CheogramSender("Zoé Lavoie", "zoe@example.org", "me@example.org"), 5_000L))
        assertTrue(s.learn(CheogramSender("Zoé Lavoie", "zoe@example.org", "me@example.org"), 6_000L))
        assertTrue(s.add(bob))
        // Nothing but a learn or an add while locked: an edit is refused, never written over People.
        assertEquals(null, s.update { it + Person("6f0c2a3e-0000-4000-8000-000000000003", "Carl") })
        assertEquals(listOf("people.enc", "people.locked.enc"), names())
        assertEquals(PeopleKeyAliases.LOCK_SCREEN, PeopleEnvelope.frame.alias(File(dir, "people.locked.enc").readBytes()))
        noPlainWords("Lavoie")
        noPlainWords("Tremblay")
        lockedNow = false
        vault().open()
        assertEquals(listOf("people.enc"), names())
        assertEquals(listOf("Bob Tremblay", "Mary Ann Smith", "Zoé Lavoie"), store().list().map { it.name }.sorted())
    }

    @Test fun aMergeCutBeforeTheJournalGoesAddsNobodyTwice() {
        lockedNow = true
        store().learn(CheogramSender("Zoé Lavoie", "zoe@example.org", "me@example.org"), 5_000L)
        store().add(bob)
        lockedNow = false
        try { vault(crashAt = "merged").open() } catch (_: IllegalStateException) {}
        assertEquals(listOf("people.enc", "people.locked.enc"), names())
        assertEquals(2, store().list().size)
        assertEquals(listOf("people.enc"), names())
    }

    @Test fun anAddressYouRemovedIsntLearnedBackFromTheJournal() {
        store().save(mary.copy(xmpp = listOf(app.heyrobot.core.XmppAddress("zoe@example.org"))))
        assertTrue(store().remove(mary.id, ignore = true))
        lockedNow = true
        store().learn(CheogramSender("Zoé", "zoe@example.org", "me@example.org"), 5_000L)
        lockedNow = false
        assertTrue(store().list().isEmpty())
    }

    @Test fun theUnlockKeyNotUsableNowIsNotNowNeverDamagedAndFailsWhole() {
        store().save(mary)
        notNow(PeopleKeyAliases.UNLOCKED)
        val s = store()
        assertEquals(PeopleLoad.NotNow, s.load())
        assertFalse(s.damaged())
        assertFalse(s.reset())
        assertFalse(s.deleteAll())
        val e = try { s.whole(); null } catch (x: PeopleNotWhole) { x }
        assertEquals(PeopleLoad.NotNow, e?.load)
        assertTrue(s.learn(CheogramSender("Zoé", "zoe@example.org", "me@example.org"), 5_000L))
        keys.refuse.clear()
        assertEquals(2, store().list().size)
    }

    @Test fun aKeyThatIsGoneIsDamagedAndStartOverKeepsTheFileAsideAndMakesANewKey() {
        store().save(mary)
        keys.gone += PeopleKeyAliases.UNLOCKED
        val s = store()
        assertTrue(s.damaged())
        assertEquals(null, s.update { it + bob })
        assertTrue(s.reset())
        assertEquals(listOf("people.enc.bad"), names())
        assertTrue(s.save(bob))
        assertEquals(listOf("Bob Tremblay"), store().list().map { it.name })
        assertEquals(listOf("people.enc", "people.enc.bad"), names())
    }

    /** seal-people-fix-b 5: with nine copies kept aside, Start over drops the oldest, so People is never stuck. */
    @Test fun startOverAfterNineCopiesDropsTheOldest() {
        store().save(mary)
        keys.gone += PeopleKeyAliases.UNLOCKED
        val copies = (1..9).map { n -> File(dir, if (n == 1) "people.enc.bad" else "people.enc.bad$n").apply { writeText("old $n"); setLastModified(1_000_000_000L + 1000L * (if (n == 4) 0 else n)) } }
        val s = store()
        assertTrue(s.damaged())
        assertTrue(s.reset())
        assertFalse(File(dir, "people.enc").exists())
        assertEquals(9, names().count { it.startsWith("people.enc.bad") })
        assertTrue(copies[3].readText() != "old 4")
        assertTrue(copies.filterIndexed { i, _ -> i != 3 }.all { it.readText().startsWith("old ") })
        assertTrue(s.save(bob))
        assertTrue(store().deleteAll())
    }

    @Test fun aNewerRobotsFileIsTooNewLeftAsItIsAndStartOverKeepsItAside() {
        val f = File(dir, "people.enc")
        f.writeBytes(app.heyrobot.core.SealedEnvelope("RPPL", "people", 9).seal(PeopleEnvelope.bytes(PeopleFile(listOf(mary))), keys.cipher(PeopleKeyAliases.UNLOCKED, true)!!, f.name))
        val s = store()
        assertEquals(PeopleLoad.TooNew(9), s.load())
        assertEquals(PeopleLoad.TooNew(9), (try { s.whole(); null } catch (x: PeopleNotWhole) { x })?.load)
        assertFalse(s.deleteAll())
        assertTrue(s.reset())
        assertEquals(listOf("people.enc.bad"), names())
    }

    @Test fun anUnparseablePlainFileStaysForStartOverWhichKeepsItAside() {
        File(dir, "people.json").writeText("{not json")
        val s = store()
        assertTrue(s.damaged())
        assertEquals(listOf("people.json"), names())
        assertTrue(s.reset())
        assertEquals(listOf("people.json.enc.bad"), names())
        noPlainWords("not json")
        assertTrue(s.save(mary))
    }

    @Test fun anotherStoresAliasIsNeverAskedFor() {
        File(dir, "people.enc").writeBytes(PeopleEnvelope.seal(PeopleFile(listOf(mary)), keys.cipher("robot_notes_v1", true)!!, "people.enc"))
        val before = keys.opens
        assertTrue(store().damaged())
        assertEquals(before, keys.opens)
    }

    @Test fun deleteAllLeavesNothingAndDropsTheKeysOnlyWhenNothingIsSealedUnderThem() {
        store().save(mary)
        lockedNow = true
        store().learn(CheogramSender("Zoé", "zoe@example.org", "me@example.org"), 5_000L)
        lockedNow = false
        File(dir, ".people.enc.tmp").writeBytes(byteArrayOf(1))
        assertTrue(store().deleteAll())
        assertEquals(emptyList<String>(), names())
        assertFalse(PeopleKeyAliases.UNLOCKED in keys.keys || PeopleKeyAliases.LOCK_SCREEN in keys.keys)
        // seal-people-fix 2: the copies kept aside go too, the plain one and the sealed one, and the key they held.
        store().save(mary)
        File(dir, "people.enc").copyTo(File(dir, "people.enc.bad"))
        plain(bob).renameTo(File(dir, "people.json.bad"))
        File(dir, "people.json.bad2").writeText("{not json")
        assertTrue(store().deleteAll())
        assertEquals(emptyList<String>(), names())
        assertFalse(PeopleKeyAliases.UNLOCKED in keys.keys)
    }

    // seal-people-fix 5: Phone lock's call list is told of every write, and of Delete all and Start over, under People's lock.
    @Test fun theCallListIsToldOfEveryWriteAndOfDeleteAllAndStartOver() {
        val told = mutableListOf<List<String>?>()
        fun s() = PeopleStore(PeopleVault(dir, keys, { lockedNow }, written = { f -> told += f?.people?.map { it.name } })).also { it.drop() }
        assertTrue(s().save(mary))
        assertEquals(listOf(listOf("Mary Ann Smith")), told)
        assertTrue(s().deleteAll())
        assertEquals(null, told.last())
        File(dir, "people.json").writeText("{not json")
        told.clear()
        assertTrue(s().reset())
        assertTrue(told.isNotEmpty() && told.first() == null)
    }

    @Test fun nothingOutsideTheStoreOpensThePeopleFolder() {
        val allowed = setOf("People.kt", "PeopleVault.kt", "CheogramListener.kt")
        val bad = File("src/main/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name !in allowed }
            .filter { f -> f.readText().let { it.contains("PeopleFiles.dir(") || it.contains("\"people.json\"") || it.contains("PEOPLE_FILE)") } }.map { it.name }.toList()
        assertEquals(emptyList<String>(), bad)
    }
}
