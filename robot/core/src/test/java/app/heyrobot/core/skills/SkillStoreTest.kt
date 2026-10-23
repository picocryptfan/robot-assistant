// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

/** files/robot/skills.json (plan §3): the lock, an atomic write, .bad kept, a newer or damaged file never written over. */
class SkillStoreTest {
    private val dir: File = Files.createTempDirectory("skills").toFile()
    private val store = SkillStore(dir)
    private val file get() = File(dir, SkillStore.SKILLS)

    @After fun clean() { dir.deleteRecursively() }

    /** Fix 1.1: a skill with a later Robot's languages is changed here (its level, a question) and keeps them. */
    @Test fun editsKeepMoreLanguages() {
        val more = SkillFixtures.headingHomeMore()
        assertTrue(store.save(more))
        assertTrue(store.save(store.get(more.id)!!.copy(level = SkillLevel.OFF)))
        assertTrue(store.save(SkillEdit.question(store.get(more.id)!!, "who", askEn = "Who gets the text?")))
        val back = store.get(more.id)!!
        assertEquals(more.namesMore, back.namesMore)
        assertEquals(more.params[0].askMore, back.params[0].askMore)
        assertEquals("Who gets the text?", back.params[0].askEn)
        assertEquals(SkillLevel.OFF, back.level)
        assertTrue(file.readText().contains("ich fahre heim"))
    }

    @Test fun saveReplaceDelete() {
        assertEquals(SkillFile.Ok(emptyList()), store.file())
        val home = SkillFixtures.headingHome()
        assertTrue(store.save(home))
        assertTrue(store.save(SkillFixtures.pasta()))
        assertEquals(listOf("s-k3f9q2", "s-pasta1"), store.skills().map { it.id })
        assertTrue(store.save(home.copy(level = SkillLevel.OFF)))
        assertEquals(listOf(SkillLevel.OFF, SkillLevel.ASK_FIRST), store.skills().map { it.level })
        assertTrue(store.delete(home.id))
        assertEquals(listOf("s-pasta1"), store.skills().map { it.id })
        assertNull(store.get(home.id))
        // Nothing invalid is written; nor a skill whose name is another's.
        assertFalse(store.save(home.copy(name = "")))
        assertFalse(store.save(SkillFixtures.simple("s-dupe12", "Pasta")))
        assertEquals(1, store.skills().size)
        // The write is whole: no temporary file is left behind.
        assertEquals(setOf(SkillStore.SKILLS, SkillStore.LOCK_FILE), dir.list()!!.toSet())
    }

    @Test fun atMost100() {
        for (i in 0 until 100) assertTrue(store.save(SkillFixtures.simple("s-" + i.toString().padStart(6, '0'), "Timer number $i")))
        assertFalse(store.save(SkillFixtures.simple("s-extra1", "One more")))
        assertEquals(100, store.skills().size)
    }

    @Test fun aWriteAfterADropKeepsTheFileAsBad() {
        val home = SkillCodec.toJson(SkillFixtures.headingHome()).put("color", "red")
        val text = """{"v":1,"skills":[$home,${SkillCodec.toJson(SkillFixtures.pasta())}]}"""
        file.writeText(text)
        assertEquals(1, (store.file() as SkillFile.Ok).dropped)
        assertTrue(store.save(SkillFixtures.simple("s-new123", "Bedtime")))
        assertEquals(text, File(dir, "skills.json.bad").readText())
        assertEquals(listOf("s-pasta1", "s-new123"), store.skills().map { it.id })
    }

    @Test fun aNewerOrDamagedFileIsNeverWrittenOver() {
        val newer = """{"v":2,"skills":[]}"""
        file.writeText(newer)
        assertEquals(SkillFile.TooNew(2), store.file())
        assertFalse(store.save(SkillFixtures.pasta()))
        assertFalse(store.delete("s-pasta1"))
        assertEquals(newer, file.readText())
        assertTrue(store.skills().isEmpty())
        // Start over is only for a damaged file.
        assertTrue(store.reset())
        assertEquals(newer, file.readText())

        file.writeText("{broken")
        assertEquals(SkillFile.Damaged, store.file())
        assertFalse(store.save(SkillFixtures.pasta()))
        assertEquals("{broken", file.readText())
        assertTrue(store.reset())
        assertEquals("{broken", File(dir, "skills.json.bad").readText())
        assertEquals(SkillFile.Ok(emptyList()), store.file())
        assertTrue(store.save(SkillFixtures.pasta()))
    }

    @Test fun twoWritersUnderTheLockLoseNothing() {
        val a = thread { for (i in 0 until 20) store.save(SkillFixtures.simple("s-a" + i.toString().padStart(5, '0'), "Alpha $i")) }
        val b = thread { for (i in 0 until 20) SkillStore(dir).save(SkillFixtures.simple("s-b" + i.toString().padStart(5, '0'), "Bravo $i")) }
        a.join(); b.join()
        assertEquals(40, store.skills().size)
    }

    @Test fun theLastRuns() {
        val s = store.editState { SkillStateFile.record(it, "s-pasta1", SkillRunRecord(5L, 2, 2), setOf("s-pasta1")) }
        assertEquals(SkillRunRecord(5L, 2, 2), s!!.lastRun["s-pasta1"])
        assertEquals(s, store.state())
        // A deleted skill's run goes with the next record.
        val t = SkillStateFile.record(s, "s-k3f9q2", SkillRunRecord(6L, 1, 2), setOf("s-k3f9q2"))
        assertEquals(setOf("s-k3f9q2"), t.lastRun.keys)
        // What can't be read is left out.
        assertEquals(SkillState(), SkillStateFile.decode("""{"v":1,"runs":{"s-pasta1":{"at":1,"done":3,"total":2},"bad id":{"at":1,"done":1,"total":1}}}"""))
        assertEquals(SkillState(), SkillStateFile.decode("nope"))
    }
}
