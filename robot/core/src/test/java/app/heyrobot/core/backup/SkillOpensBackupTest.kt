// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.skills.SkillCodec
import app.heyrobot.core.skills.SkillCodec13
import app.heyrobot.core.skills.SkillFile
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillOpensFile
import app.heyrobot.core.skills.SkillStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** auto-open-i: a backup keeps skills.json at v3 with no "opens", the ticks in skill_opens.json, which 1.3 skips by name. */
class SkillOpensBackupTest {
    private val ticked = SkillFixtures.headingHome().copy(opens = setOf("app.organicmaps"))
    private val plain = SkillFixtures.pasta()
    private val onPhone = SkillCodec.encode(listOf(ticked, plain))

    /** What BackupSources writes for this phone's skills.json, each entry with the schema the writer records. */
    private fun backup(json: String = onPhone): ByteArray {
        val (skills, opens) = SkillOpensFile.split(json)
        val r = RawRecords().manifest()
            .entry("skills", SkillStore.SKILLS, BackupKit.text(skills), v = BackupCategory.SKILLS.schemaOf(SkillStore.SKILLS, BackupKit.text(skills)))
        opens?.let { r.entry("skill_opens", BackupCategory.SKILL_OPENS_FILE, BackupKit.text(it), v = SkillOpensFile.VERSION) }
        return BackupKit.encrypt(r.end().bytes())
    }

    /** 1.3's reader: no "skill_opens" or "open_apps" category, skills.json up to v3 through 1.3's own codec. */
    @Test fun a13RobotKeepsEverySkillOfABackupWithATick() {
        val f = backup()
        val known13 = setOf(BackupCategory.SKILL_OPENS, BackupCategory.OPEN_APPS)
        val old = RestoreReader.verify(BackupKit.opener(f), BackupHeader.parse(f), BackupKit.key(), {}) { id -> BackupCategory.of(id)?.takeIf { it !in known13 } }
        assertEquals(listOf(CategorySkip("skill_opens", null, SkipWhy.UNKNOWN)), old.skipped)
        assertTrue(BackupCategory.SKILLS in old.found.keys)
        // 1.3's version check: the entry records the file's own v, at most 1.3's SkillCodec.VERSION.
        assertTrue(BackupCategory.SKILLS.schemaOf(SkillStore.SKILLS, BackupKit.text(SkillOpensFile.split(onPhone).first))!! <= SkillCodec13.VERSION)
        val read13 = SkillCodec13.decode(SkillOpensFile.split(onPhone).first)
        assertTrue(read13.toString(), read13 is SkillFile.Ok)
        read13 as SkillFile.Ok
        assertEquals(0, read13.dropped)
        assertEquals(listOf(ticked.copy(opens = emptySet()), plain), read13.skills)
    }

    /**
     * review-auto-open-b L3: a skill this Robot can't read (a later Robot's) stays as it is less its "opens", and skills.json
     * goes from v4 to v3; the readable skills' ticks go in skill_opens, the other's is dropped. 1.3, like 1.4, refuses a skills
     * file with a skill it can't read (RestoreReader: dropped > 0), so it skips Skills as refused, never as newer.
     */
    @Test fun aSkillThisRobotCantReadNeverPutsTheBackupAboveV3() {
        val later = JSONObject().put("id", "sk_later").put("opens", JSONArray().put("org.example.later")).put("from_later", true)
        val json = JSONObject(onPhone).also { it.getJSONArray("skills").put(later) }.toString()
        val (skills, opens) = SkillOpensFile.split(json)
        assertFalse(skills, "opens" in skills)
        assertEquals(setOf("id", "from_later"), JSONObject(skills).getJSONArray("skills").getJSONObject(2).keys().asSequence().toSet())
        assertEquals(mapOf(ticked.id to setOf("app.organicmaps")), SkillOpensFile.decode(opens))
        // 1.3 reads that backup: skill_opens skipped by name, skills.json at v3 through its own codec, the later skill dropped alone.
        val f = backup(json)
        val known13 = setOf(BackupCategory.SKILL_OPENS, BackupCategory.OPEN_APPS)
        val old = RestoreReader.verify(BackupKit.opener(f), BackupHeader.parse(f), BackupKit.key(), {}) { id -> BackupCategory.of(id)?.takeIf { it !in known13 } }
        assertEquals(listOf(CategorySkip("skills", BackupCategory.SKILLS, SkipWhy.REFUSED), CategorySkip("skill_opens", null, SkipWhy.UNKNOWN)), old.skipped)
        assertTrue(BackupCategory.SKILLS.schemaOf(SkillStore.SKILLS, BackupKit.text(skills))!! <= SkillCodec13.VERSION)
        val read13 = SkillCodec13.decode(skills)
        assertTrue(read13.toString(), read13 is SkillFile.Ok)
        read13 as SkillFile.Ok
        assertEquals(1, read13.dropped)
        assertEquals(listOf(ticked.copy(opens = emptySet()), plain), read13.skills)
    }

    @Test fun a14RobotRestoresTheTicksFromYourOwnBackup() {
        val v = BackupKit.verify(backup())
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(mapOf(ticked.id to setOf("app.organicmaps")), v.skillOpens)
        assertEquals(listOf(ticked, plain), SkillOpensFile.attach(v.skills!!, v.skillOpens))
        val mine = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = true)
        assertTrue(mine.of(BackupCategory.SKILL_OPENS).restores)
        assertEquals(BackupCategory.SKILLS, BackupCategory.coveredBy(BackupCategory.SKILL_OPENS))
        // Someone else's backup brings no tick: the plan skips the ticks, and Skills' own taint drops any left.
        val theirs = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = false)
        assertEquals(PlanSkip.NOT_YOURS, theirs.of(BackupCategory.SKILL_OPENS).skip)
        assertEquals(emptySet<String>(), RestoreTaint.skill(SkillOpensFile.attach(v.skills!!, v.skillOpens).first(), mine = false).opens)
    }

    /** A 1.4 test build before auto-open-i wrote skills.json v4 with "opens": it still restores here, ticks and all. */
    @Test fun anEarlier14BackupWithAV4SkillsFileStillRestores() {
        val bytes = BackupKit.text(onPhone)
        val f = BackupKit.encrypt(RawRecords().manifest().entry("skills", SkillStore.SKILLS, bytes, v = BackupCategory.SKILLS.schemaOf(SkillStore.SKILLS, bytes)).end().bytes())
        val v = BackupKit.verify(f)
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(listOf(ticked, plain), SkillOpensFile.attach(v.skills!!, v.skillOpens))
        assertNull(v.skillOpens)
    }

    @Test fun aBrokenTicksFileIsRefusedAndSkillsStillCome() {
        assertNull(SkillOpensFile.decode("""{"v":1,"skills":{"s-k3f9q2":[]}}"""))
        assertNull(SkillOpensFile.decode("""{"v":1,"skills":{"s-k3f9q2":["app/.Main"]}}"""))
        assertNull(SkillOpensFile.decode("""{"v":1,"skills":{},"more":1}"""))
        assertEquals(onPhone to null, SkillOpensFile.split(SkillCodec.encode(listOf(plain))).let { onPhone to it.second })
    }
}
