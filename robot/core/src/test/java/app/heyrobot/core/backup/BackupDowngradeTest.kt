// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.RobotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §7.11: a newer format is refused before the passphrase; a format 1 file made by a newer Robot restores every
 * category this Robot understands and skips, by name, a category holding a newer schema and a category it doesn't know;
 * an older file goes through each store's migration.
 */
class BackupDowngradeTest {
    @Test fun aNewerFormatIsRefusedBeforeThePassphrase() {
        val f = BackupKit.resource(BackupFixtures.FORMAT_2)!!
        assertEquals(OpenProblem.NEWER_FORMAT, BackupKit.problem { RestoreReader.header(f) })
        // Even with its first bytes only.
        assertEquals(OpenProblem.NEWER_FORMAT, BackupKit.problem { RestoreReader.header(f.copyOf(BackupFormat.HEADER_SIZE)) })
        // Too short to be a header, or another file altogether: not a backup.
        assertEquals(OpenProblem.NOT_A_BACKUP, BackupKit.problem { RestoreReader.header(f.copyOf(20)) })
        assertEquals(OpenProblem.NOT_A_BACKUP, BackupKit.problem { RestoreReader.header("%PDF-1.7 and so on, and so on, and so on, and so on".toByteArray()) })
    }

    @Test fun aNewerRobotsFileRestoresWhatThisOneUnderstands() {
        val f = BackupKit.resource(BackupFixtures.NEWER)!!
        val h = RestoreReader.header(f)
        assertEquals(BackupFixtures.LIGHT, h.kdf)
        assertEquals(1_030_099L, h.appVersion)
        BackupKdf.derive(BackupFixtures.NEWER_PASSPHRASE, h).use { key ->
            val v = RestoreReader.verify(BackupKit.opener(f), h, key)
            assertEquals("1.3.0", v.manifest.robot)
            // 1.1 knows Skills now: the fixture's empty skills.json (v1) is read like any category of this Robot's.
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.SKILLS, BackupCategory.NOTES), v.found.keys)
            assertEquals(listOf(CategorySkip("settings", BackupCategory.SETTINGS, SkipWhy.NEWER)), v.skipped)
            assertTrue(v.settings.isEmpty())
            val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = true)
            assertEquals(PlanSkip.NEEDS_NEWER, plan.of(BackupCategory.SETTINGS).skip)
            assertEquals(listOf(BackupCategory.PEOPLE, BackupCategory.SKILLS, BackupCategory.NOTES), plan.restoring)
            // The preview names what needs the newer Robot.
            assertEquals(listOf("settings"), plan.skippedByName.map { it.name })
            // Never written in an older form: nothing of Settings is handed out.
            val handed = ArrayList<String>()
            RestoreReader.read(BackupKit.opener(f), v, key, BackupCategory.entries.toSet()) { c, n, _ -> handed += "${c.id}/$n" }
            assertEquals(listOf("people/people.json", "skills/skills.json", "notes/note-0001.json", "notes/note-0002.json"), handed)
        }
    }

    /** A category this Robot doesn't know (the fixture's "skills" was one before 1.1 knew Skills): skipped by name, the rest restored. */
    @Test fun anUnknownCategoryIsSkippedByName() {
        val f = RawRecords().manifest()
            .entry("plans", "plans.json", BackupKit.text("{\"v\":1,\"plans\":[]}"))
            .entry("notes", "a.json", BackupKit.text("{\"v\":1,\"id\":\"a\",\"title\":\"\",\"text\":\"x\",\"created\":1,\"updated\":2}"))
            .end().bytes()
        val v = BackupKit.verify(BackupKit.encrypt(f))
        assertEquals(listOf(CategorySkip("plans", null, SkipWhy.UNKNOWN)), v.skipped)
        assertEquals(setOf(BackupCategory.NOTES), v.found.keys)
    }

    /** Skills from a newer Robot wait for it; a skills.json this Robot reads only in part is refused whole (plan §4). */
    @Test fun skillsNewerOrDroppedAreNotRestored() {
        fun verify(text: String) = BackupKit.verify(BackupKit.encrypt(RawRecords().manifest().entry("skills", "skills.json", BackupKit.text(text)).end().bytes()))
        assertEquals(listOf(CategorySkip("skills", BackupCategory.SKILLS, SkipWhy.NEWER)), verify("{\"v\":2,\"skills\":[]}").skipped)
        val home = app.heyrobot.core.skills.SkillCodec.toJson(app.heyrobot.core.skills.SkillFixtures.headingHome()).put("color", "red")
        assertEquals(listOf(CategorySkip("skills", BackupCategory.SKILLS, SkipWhy.REFUSED)), verify("{\"v\":1,\"skills\":[$home]}").skipped)
        assertEquals(listOf(CategorySkip("skills", BackupCategory.SKILLS, SkipWhy.REFUSED)), verify("{\"v\":1}").skipped)
        val ok = verify(app.heyrobot.core.skills.SkillCodec.encode(listOf(app.heyrobot.core.skills.SkillFixtures.headingHome())))
        assertEquals(listOf(app.heyrobot.core.skills.SkillFixtures.headingHome()), ok.skills)
        // Fix 1.1: a later Robot's skill with Spanish and German words is restored, those words kept.
        val more = verify(app.heyrobot.core.skills.SkillCodec.encode(listOf(app.heyrobot.core.skills.SkillFixtures.headingHomeMore())))
        assertEquals(emptyList<CategorySkip>(), more.skipped)
        assertEquals(listOf(app.heyrobot.core.skills.SkillFixtures.headingHomeMore()), more.skills)
        // Never skills_state.json, the last runs.
        assertTrue(!BackupCategory.SKILLS.validName("skills_state.json"))
        assertEquals(BackupCaps.SMALL_FILE, BackupCategory.SKILLS.maxBytes("skills.json"))
        // Phase B: skill_settings.json goes with them, at its own version.
        assertEquals(BackupCaps.SMALL_FILE, BackupCategory.SKILLS.maxBytes("skill_settings.json"))
        assertEquals(app.heyrobot.core.skills.SkillSettingsCodec.VERSION, BackupCategory.SKILLS.schema("skill_settings.json"))
    }

    @Test fun aFilesOwnVersionCountsEvenWhenItsRecordSaysOtherwise() {
        val newer = RawRecords().manifest()
            .entry("people", "people.json", BackupKit.text("{\"v\":2,\"people\":[]}"))
            .entry("memory", "memory.json", BackupKit.text("{\"v\":9,\"items\":[]}"))
            .entry("notes", "a.json", BackupKit.text("{\"v\":1,\"id\":\"a\",\"title\":\"\",\"text\":\"x\",\"created\":1,\"updated\":2}"))
            .end().bytes()
        val v = BackupKit.verify(BackupKit.encrypt(newer))
        assertEquals(setOf(BackupCategory.PEOPLE to SkipWhy.NEWER, BackupCategory.MEMORY to SkipWhy.NEWER), v.skipped.map { it.category to it.why }.toSet())
        assertTrue(v.has(BackupCategory.NOTES))
    }

    @Test fun anOlderFileGoesThroughItsStoresMigration() {
        // A settings.json of schema 1 (0.1): read by RobotSettings.fromJson as an old file is, then guarded as any other.
        val old = "{\"schema\":1,\"wakeWord\":false,\"speakReplies\":\"ALWAYS\"}"
        val file = BackupKit.encrypt(RawRecords().manifest().entry("settings", "settings.json", BackupKit.text(old), v = 1).end().bytes())
        val v = BackupKit.verify(file)
        assertTrue(v.has(BackupCategory.SETTINGS))
        val s = RestoreMerge.settings(SettingsNow(), v.settings, mine = true, people = { it }).settings
        assertFalse(s.wakeWord)
        assertEquals(RobotSettings.fromJson(old).speakReplies, s.speakReplies)
        // 1.0.0: a file from before 1.0.0 keeps Claude, as those versions had it (a new install has no helper).
        assertEquals(RobotSettings.fromJson(old).helpers.map { it.id }, s.helpers.map { it.id })
    }

    /**
     * 1.4, Robot Files (files-1.4-plan.md §2.8, F11): the committed 1.4 fixture, read as a 1.1 to 1.3 Robot reads it (this
     * reader less the Files category: format 1 and every record rule unchanged since), skips "files" by name and restores
     * the rest; read by this Robot, its files come back whole.
     */
    @Test fun aFilesBackupInAnOlderRobotSkipsFilesAndRestoresTheRest() {
        val f = BackupKit.resource(BackupFixtures.FILES) ?: error("missing fixture ${BackupFixtures.FILES}")
        val h = RestoreReader.header(f)
        assertEquals(1, h.format)
        assertEquals(1_040_099L, h.appVersion)
        BackupKdf.derive(BackupFixtures.FILES_PASSPHRASE, h).use { key ->
            // As 1.1 to 1.3: a category it doesn't know.
            val old = RestoreReader.verify(BackupKit.opener(f), h, key, {}) { id -> BackupCategory.of(id)?.takeIf { it != BackupCategory.FILES } }
            assertEquals("1.4.0", old.manifest.robot)
            assertEquals(listOf(CategorySkip("files", null, SkipWhy.UNKNOWN)), old.skipped)
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.NOTES), old.found.keys)
            val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), RestorePlanner.defaults(old), mine = true)
            assertEquals(listOf(BackupCategory.PEOPLE, BackupCategory.NOTES), plan.restoring.filter { it != BackupCategory.FILES })
            assertEquals(listOf("files"), plan.skippedByName.map { it.name })
            assertEquals(2, old.people!!.people.size)
            assertEquals(BackupKit.notes().associate { it.id to it.updated }, old.notes)
            // What a 1.1 reader holds every unknown entry to: under 1 GiB, a name within 96 characters, meta of c, n and v only.
            val r = BackupContainerReader(BackupReader(java.io.ByteArrayInputStream(f, BackupFormat.HEADER_SIZE, f.size), h, key))
            var files = 0
            while (true) {
                val rec = r.next() ?: break
                if (rec is BackupRecord.Entry && rec.category == "files") {
                    files++
                    assertTrue(rec.length < BackupCaps.UNKNOWN_ENTRY)
                    assertTrue(rec.name.length <= 96)
                }
            }
            assertEquals(4, files)
            // This Robot: everything, its files whole.
            val now = RestoreReader.verify(BackupKit.opener(f), h, key)
            assertEquals(emptyList<CategorySkip>(), now.skipped)
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.NOTES, BackupCategory.FILES), now.found.keys)
            assertEquals(BackupKit.filesIndex().entries.map { it.id }, now.files!!.entries.map { it.id })
            val bytes = HashMap<String, ByteArray>()
            RestoreReader.read(BackupKit.opener(f), now, key, setOf(BackupCategory.FILES)) { _, n, d -> bytes[n] = d.readBytes() }
            assertTrue(bytes.getValue("blob/${BackupKit.FILE_RECEIPT}").contentEquals(BackupKit.receiptBytes()))
            assertTrue(bytes.getValue("blob/${BackupKit.FILE_PHOTO}").contentEquals(BackupKit.photoBytes()))
        }
    }
}
