// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.RobotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** R13: routines.json read only in part is refused whole, as skills.json is: Replace never removes yours for what's left. */
    @Test fun routinesReadOnlyInPartAreRefused() {
        fun verify(text: String) = BackupKit.verify(BackupKit.encrypt(RawRecords().manifest().entry("routines", "routines.json", BackupKit.text(text)).end().bytes()))
        assertEquals(listOf(CategorySkip("routines", BackupCategory.ROUTINES, SkipWhy.REFUSED)), verify("{\"v\":1,\"routines\":[{\"id\":\"x\"}]}").skipped)
        assertEquals(emptyList<CategorySkip>(), verify(app.heyrobot.core.RoutineCodec.encode(BackupKit.routines())).skipped)
    }

    /** Skills from a newer Robot wait for it; a skills.json this Robot reads only in part is refused whole (plan §4). */
    @Test fun skillsNewerOrDroppedAreNotRestored() {
        fun verify(text: String) = BackupKit.verify(BackupKit.encrypt(RawRecords().manifest().entry("skills", "skills.json", BackupKit.text(text)).end().bytes()))
        // C1 (connections plan D6) moved this pin from 2 to 3 on purpose: this Robot reads v2 (aV2SkillsFileRecordsItsOwnVersion).
        assertEquals(listOf(CategorySkip("skills", BackupCategory.SKILLS, SkipWhy.NEWER)), verify("{\"v\":3,\"skills\":[]}").skipped)
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

    /**
     * 1.4, Robot Lists (lists-plan.md §6, LS7): the committed lists fixture, read as a 1.1 to 1.3 Robot reads it (this reader
     * less Files and Lists: format 1 and every record rule unchanged since), skips "lists" by name and restores the rest;
     * read by this Robot, the lists come back whole, tombstones and Settings › Lists included.
     */
    @Test fun aListsBackupInAnOlderRobotSkipsListsAndRestoresTheRest() {
        val f = BackupKit.resource(BackupFixtures.LISTS) ?: error("missing fixture ${BackupFixtures.LISTS}")
        val h = RestoreReader.header(f)
        assertEquals(1, h.format)
        assertEquals(1_040_099L, h.appVersion)
        BackupKdf.derive(BackupFixtures.LISTS_PASSPHRASE, h).use { key ->
            val old = RestoreReader.verify(BackupKit.opener(f), h, key, {}) { id ->
                BackupCategory.of(id)?.takeIf { it != BackupCategory.FILES && it != BackupCategory.LISTS }
            }
            assertEquals("1.4.0", old.manifest.robot)
            assertEquals(listOf(CategorySkip("lists", null, SkipWhy.UNKNOWN)), old.skipped)
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.NOTES), old.found.keys)
            val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), RestorePlanner.defaults(old), mine = true)
            assertEquals(listOf(BackupCategory.PEOPLE, BackupCategory.NOTES), plan.restoring)
            assertEquals(listOf("lists"), plan.skippedByName.map { it.name })
            assertEquals(2, old.people!!.people.size)
            assertEquals(BackupKit.notes().associate { it.id to it.updated }, old.notes)
            // What a 1.1 reader holds every unknown entry to: under 1 GiB, a name within 96 characters.
            val r = BackupContainerReader(BackupReader(java.io.ByteArrayInputStream(f, BackupFormat.HEADER_SIZE, f.size), h, key))
            val names = ArrayList<String>()
            while (true) {
                val rec = r.next() ?: break
                if (rec is BackupRecord.Entry && rec.category == "lists") {
                    names += rec.name
                    assertTrue(rec.length < BackupCaps.UNKNOWN_ENTRY)
                    assertTrue(rec.name.length <= 96)
                }
            }
            assertEquals(listOf("lists.json", "lists_settings.json"), names)
            // This Robot: everything, the lists whole.
            val now = RestoreReader.verify(BackupKit.opener(f), h, key)
            assertEquals(emptyList<CategorySkip>(), now.skipped)
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.NOTES, BackupCategory.LISTS), now.found.keys)
            assertEquals(BackupKit.lists(), now.lists)
            assertEquals(BackupKit.listsSettings(), now.listsSettings!!.read)
        }
    }

    /**
     * L12, D14: a 1.5 backup in a 1.1 to 1.4 Robot, as that reader sees it (this reader less the category): "links" is skipped
     * by name, within what any older reader holds an unknown entry to, and the rest restores. Read by this Robot, it is
     * whole; and a links.json from a later Robot is left for that Robot, the rest of the file unharmed.
     */
    @Test fun aLinksBackupInAnOlderRobotSkipsItAndRestoresTheRest() {
        val f = BackupKit.resource(BackupFixtures.LINKS) ?: error("missing fixture ${BackupFixtures.LINKS}")
        val h = RestoreReader.header(f)
        assertEquals(1, h.format)
        BackupKdf.derive(BackupFixtures.LINKS_PASSPHRASE, h).use { key ->
            val old = RestoreReader.verify(BackupKit.opener(f), h, key, {}) { id -> BackupCategory.of(id)?.takeIf { it != BackupCategory.LINKS } }
            assertEquals(listOf(CategorySkip("links", null, SkipWhy.UNKNOWN)), old.skipped)
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.NOTES), old.found.keys)
            assertEquals(null, old.links)
            val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), RestorePlanner.defaults(old), mine = true)
            assertEquals(listOf(BackupCategory.PEOPLE, BackupCategory.NOTES), plan.restoring)
            assertEquals(listOf("links"), plan.skippedByName.map { it.name })
            val r = BackupContainerReader(BackupReader(java.io.ByteArrayInputStream(f, BackupFormat.HEADER_SIZE, f.size), h, key))
            var entries = 0
            while (true) {
                val rec = r.next() ?: break
                if (rec is BackupRecord.Entry && rec.category == "links") {
                    entries++
                    assertTrue(rec.length < BackupCaps.UNKNOWN_ENTRY)
                    assertTrue(rec.name.length <= 96)
                }
            }
            assertEquals(2, entries)
            val now = RestoreReader.verify(BackupKit.opener(f), h, key)
            assertEquals(emptyList<CategorySkip>(), now.skipped)
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.NOTES, BackupCategory.LINKS), now.found.keys)
            assertEquals(BackupFixtures.LINKS_PAIRING, now.links!!.pairings.single().id)
            assertEquals(BackupFixtures.LINKS_SETTINGS, now.linkSettings!!.read)
        }
        val c = BackupKit.content()
        val newer = linkedMapOf(BackupCategory.PEOPLE to c.getValue(BackupCategory.PEOPLE),
            BackupCategory.LINKS to mapOf(BackupCategory.LINKS_FILE to BackupKit.text("{\"v\":2,\"pairings\":[],\"key\":\"later\"}")))
        val later = BackupFixtures.sealed(BackupKit.contentRecords(newer, robot = "1.6.0"), BackupKit.header(), BackupFixtures.BASIC_PASSPHRASE)
        val hl = RestoreReader.header(later)
        BackupKdf.derive(BackupFixtures.BASIC_PASSPHRASE, hl).use { key ->
            val v = RestoreReader.verify(BackupKit.opener(later), hl, key)
            assertEquals(null, v.links)
            assertEquals(listOf(CategorySkip("links", BackupCategory.LINKS, SkipWhy.NEWER)), v.skipped)
            assertTrue(BackupCategory.PEOPLE in v.found.keys)
        }
    }

    /**
     * The everyday set (everyday-set-plan.md §2.4): the committed fixture with an EVERYDAY category, read as a Robot without
     * it reads it (this reader less that category: the Files tip the set starts from, and every older one), skips
     * "everyday" by name and restores the rest; read by this Robot, its switches come back through RestoreSafety.
     */
    @Test fun anEverydayBackupInAnOlderRobotSkipsItAndRestoresTheRest() {
        val f = BackupKit.resource(BackupFixtures.EVERYDAY) ?: error("missing fixture ${BackupFixtures.EVERYDAY}")
        val h = RestoreReader.header(f)
        assertEquals(1, h.format)
        BackupKdf.derive(BackupFixtures.EVERYDAY_PASSPHRASE, h).use { key ->
            val old = RestoreReader.verify(BackupKit.opener(f), h, key, {}) { id -> BackupCategory.of(id)?.takeIf { it != BackupCategory.EVERYDAY } }
            assertEquals(listOf(CategorySkip("everyday", null, SkipWhy.UNKNOWN)), old.skipped)
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.NOTES), old.found.keys)
            val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), RestorePlanner.defaults(old), mine = true)
            assertEquals(listOf(BackupCategory.PEOPLE, BackupCategory.NOTES), plan.restoring)
            assertEquals(listOf("everyday"), plan.skippedByName.map { it.name })
            // What an older reader holds every unknown entry to: under 1 GiB, a name within 96 characters.
            val r = BackupContainerReader(BackupReader(java.io.ByteArrayInputStream(f, BackupFormat.HEADER_SIZE, f.size), h, key))
            var entries = 0
            while (true) {
                val rec = r.next() ?: break
                if (rec is BackupRecord.Entry && rec.category == "everyday") {
                    entries++
                    assertTrue(rec.length < BackupCaps.UNKNOWN_ENTRY)
                    assertTrue(rec.name.length <= 96)
                }
            }
            assertEquals(1, entries)
            // This Robot: every category, the file as written.
            val now = RestoreReader.verify(BackupKit.opener(f), h, key)
            assertEquals(emptyList<CategorySkip>(), now.skipped)
            assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.NOTES, BackupCategory.EVERYDAY), now.found.keys)
            assertEquals(BackupFixtures.EVERYDAY_SETTINGS, now.everyday!!.read)
            val mode = RestorePlanner.plan(now, PhoneHas(mapOf(BackupCategory.EVERYDAY to 1)), RestorePlanner.defaults(now), mine = false).of(BackupCategory.EVERYDAY)
            assertEquals(RestoreMode.REPLACE, mode.mode)
            assertTrue(mode.restores)
            // Someone else's backup can't turn the record of what went online off; the rest comes back as saved.
            val staged = app.heyrobot.core.everyday.EverydaySettingsCodec.encode(now.everyday!!.read)
            val theirs = app.heyrobot.core.everyday.EverydaySettingsCodec.decode(RestoreSafety.everydayFile(null, staged, mine = false)).settings
            assertEquals(BackupFixtures.EVERYDAY_SETTINGS.copy(online = true, onlineDays = app.heyrobot.core.everyday.EverydaySettings.DEFAULT_DAYS), theirs)
            val yours = app.heyrobot.core.everyday.EverydaySettingsCodec.decode(RestoreSafety.everydayFile(null, staged, mine = true)).settings
            assertEquals(BackupFixtures.EVERYDAY_SETTINGS, yours)
        }
    }

    /** The everyday set: a newer everyday_settings.json waits for a newer Robot; one this Robot can't read is refused. */
    @Test fun everydayNewerOrDamagedIsNotRestored() {
        fun verify(text: String) = BackupKit.verify(BackupKit.encrypt(RawRecords().manifest().entry("everyday", "everyday_settings.json", BackupKit.text(text)).end().bytes()))
        assertEquals(listOf(CategorySkip("everyday", BackupCategory.EVERYDAY, SkipWhy.NEWER)), verify("{\"v\":2}").skipped)
        assertEquals(listOf(CategorySkip("everyday", BackupCategory.EVERYDAY, SkipWhy.REFUSED)), verify("{\"v\":1,\"music\":\"yes\"}").skipped)
        // Only its one file: never the record of what went online, never anything else.
        assertTrue(!BackupCategory.EVERYDAY.validName("online/main-20261022.jsonl"))
        assertTrue(!BackupCategory.EVERYDAY.validName("settings.json"))
        assertEquals(BackupCaps.SMALL_FILE, BackupCategory.EVERYDAY.maxBytes("everyday_settings.json"))
        assertEquals(app.heyrobot.core.everyday.EverydaySettingsCodec.VERSION, BackupCategory.EVERYDAY.schema("everyday_settings.json"))
    }

    /**
     * Skills connections D6: the entry for skills.json records the file's own "v", not the newest this Robot reads. A v1
     * file this Robot wrote stays restorable on a 1.1 Robot; a v2 one is skipped there as newer (and read here).
     */
    @Test fun aV2SkillsFileRecordsItsOwnVersion() {
        fun recorded(text: String): Int {
            // The writer's default, as BackupJob calls it with the bytes it writes.
            val bytes = BackupKit.records(BackupKit.manifest(BackupCategory.SKILLS)) { entry(BackupCategory.SKILLS, "skills.json", text.toByteArray(Charsets.UTF_8)) }
            val r = BackupContainerReader(java.io.ByteArrayInputStream(bytes))
            while (true) {
                val rec = r.next() ?: error("no entry")
                if (rec is BackupRecord.Entry) return rec.schema
            }
        }
        val v1 = app.heyrobot.core.skills.SkillCodec.encode(listOf(app.heyrobot.core.skills.SkillFixtures.headingHome()))
        val v2 = app.heyrobot.core.skills.SkillCodec.encode(listOf(app.heyrobot.core.skills.SkillFixtures.goodNight()))
        assertEquals(1, recorded(v1))
        assertEquals(2, recorded(v2))
        assertEquals(1, BackupCategory.SKILLS.schemaOf("skills.json", v1.toByteArray()))
        assertEquals(2, BackupCategory.SKILLS.schemaOf("skills.json", v2.toByteArray()))
        // Unreadable: the newest this Robot reads (its restore refuses the file anyway); other files: their own schema.
        assertEquals(app.heyrobot.core.skills.SkillCodec.VERSION, BackupCategory.SKILLS.schemaOf("skills.json", "{broken".toByteArray()))
        assertEquals(app.heyrobot.core.skills.SkillSettingsCodec.VERSION, BackupCategory.SKILLS.schemaOf("skill_settings.json", "{\"v\":1}".toByteArray()))
        // Read here: both restore.
        fun verify(text: String) = BackupKit.verify(BackupKit.encrypt(RawRecords().manifest().entry("skills", "skills.json", BackupKit.text(text), v = recorded(text)).end().bytes()))
        assertEquals(emptyList<CategorySkip>(), verify(v1).skipped)
        assertEquals(listOf(app.heyrobot.core.skills.SkillFixtures.goodNight()), verify(v2).skills)
    }

    // ------------------------------------------------------------------------- C10, Skills connections (plan §5.7, D6, D7)

    /** As a 1.1 Robot reads a backup: this reader less the categories it doesn't know, and skills.json at v1 (before D6's v2). */
    private val as11: (String) -> BackupCategory? = { id ->
        BackupCategory.of(id)?.takeIf { it != BackupCategory.FILES && it != BackupCategory.LISTS && it != BackupCategory.EVERYDAY && it != BackupCategory.CONNECTIONS && it != BackupCategory.CONN_KEYS }
    }
    private val schema11: (BackupCategory, String) -> Int? = { c, n -> if (c == BackupCategory.SKILLS && n == "skills.json") 1 else c.schema(n) }

    private fun conn(name: String, check: (ByteArray, BackupHeader, BackupKey) -> Unit) {
        val f = BackupKit.resource(name) ?: error("missing fixture $name")
        val h = RestoreReader.header(f)
        assertEquals(1, h.format)
        assertEquals(BackupFixtures.LIGHT, h.kdf)
        BackupKdf.derive(BackupFixtures.CONN_PASSPHRASE, h).use { key -> check(f, h, key) }
    }

    private val ticked = { v: VerifiedBackup -> RestorePlanner.defaults(v) + (BackupCategory.KEYS to CategoryChoice()) + (BackupCategory.CONN_KEYS to CategoryChoice()) }

    /** A backup with connections, on a 1.1 Robot: Skills (a v1 file) and Keys (helper keys) come back; the two new categories are skipped by name. */
    @Test fun aConnectionsBackupOnA11RobotKeepsSkillsAndKeysAndSkipsTheNewCategories() = conn(BackupFixtures.CONN) { f, h, key ->
        val old = RestoreReader.verify(BackupKit.opener(f), h, key, {}, schema11, as11)
        assertEquals(listOf(CategorySkip("connections", null, SkipWhy.UNKNOWN), CategorySkip("conn_keys", null, SkipWhy.UNKNOWN)), old.skipped)
        assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.SKILLS, BackupCategory.KEYS), old.found.keys)
        assertEquals(listOf(app.heyrobot.core.skills.SkillFixtures.headingHome()), old.skills)
        assertEquals(listOf("helper/claude", "search/brave"), old.keys)
        assertEquals(listOf(BackupKit.MEM_CODE), old.memoryCodes!!.items.map { it.id })
        val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), ticked(old), mine = true)
        assertEquals(listOf(BackupCategory.PEOPLE, BackupCategory.SKILLS, BackupCategory.KEYS), plan.restoring)
        assertEquals(listOf("connections", "conn_keys"), plan.skippedByName.map { it.name })
        // What a 1.1 reader holds every unknown entry to: under 1 GiB, a name within 96 characters.
        val r = BackupContainerReader(BackupReader(java.io.ByteArrayInputStream(f, BackupFormat.HEADER_SIZE, f.size), h, key))
        var unknown = 0
        while (true) {
            val rec = r.next() ?: break
            if (rec is BackupRecord.Entry && (rec.category == "connections" || rec.category == "conn_keys")) {
                unknown++
                assertTrue(rec.length < BackupCaps.UNKNOWN_ENTRY)
                assertTrue(rec.name.length <= 96)
            }
        }
        assertEquals(4, unknown)
    }

    /** With a v2 skills.json (D6), a 1.1 Robot skips Skills as newer and restores everything else it knows. */
    @Test fun aV2SkillsFileIsSkippedAsNewerOnA11Robot() = conn(BackupFixtures.CONN_V2) { f, h, key ->
        val old = RestoreReader.verify(BackupKit.opener(f), h, key, {}, schema11, as11)
        assertEquals(listOf(CategorySkip("skills", BackupCategory.SKILLS, SkipWhy.NEWER), CategorySkip("connections", null, SkipWhy.UNKNOWN),
            CategorySkip("conn_keys", null, SkipWhy.UNKNOWN)), old.skipped)
        assertEquals(setOf(BackupCategory.PEOPLE, BackupCategory.KEYS), old.found.keys)
        assertNull(old.skills)
        val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), ticked(old), mine = true)
        assertEquals(PlanSkip.NEEDS_NEWER, plan.of(BackupCategory.SKILLS).skip)
        assertEquals(listOf(BackupCategory.PEOPLE, BackupCategory.KEYS), plan.restoring)
        assertEquals(listOf("skills", "connections", "conn_keys"), plan.skippedByName.map { it.name })
        // Never handed out: nothing of Skills is written in an older form.
        val handed = ArrayList<String>()
        RestoreReader.read(BackupKit.opener(f), old, key, BackupCategory.entries.toSet()) { c, _, _ -> handed += c.id }
        assertTrue("skills" !in handed)
    }

    /** The goldens: this Robot restores both fixtures whole, connections as saved and every key's plain form byte for byte. */
    @Test fun thisRobotRestoresConnectionsAndTheirKeysWhole() {
        for ((name, skills) in listOf(BackupFixtures.CONN to app.heyrobot.core.skills.SkillFixtures.headingHome(),
                BackupFixtures.CONN_V2 to app.heyrobot.core.skills.SkillFixtures.goodNight())) conn(name) { f, h, key ->
            val v = RestoreReader.verify(BackupKit.opener(f), h, key)
            assertEquals(name, "1.4.0", v.manifest.robot)
            assertEquals(name, emptyList<CategorySkip>(), v.skipped)
            assertEquals(name, setOf(BackupCategory.PEOPLE, BackupCategory.SKILLS, BackupCategory.KEYS, BackupCategory.CONNECTIONS, BackupCategory.CONN_KEYS),
                v.found.keys)
            assertEquals(name, listOf(skills), v.skills)
            assertEquals(name, ConnBackupKit.connections(), v.connections)
            assertEquals(name, ConnBackupKit.secrets().mapValues { it.value.parts }, v.connKeys)
            val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), ticked(v), mine = true)
            assertEquals(name, listOf(BackupCategory.PEOPLE, BackupCategory.SKILLS, BackupCategory.KEYS, BackupCategory.CONNECTIONS, BackupCategory.CONN_KEYS),
                plan.restoring)
            val keys = LinkedHashMap<String, ByteArray>()
            RestoreReader.read(BackupKit.opener(f), v, key, setOf(BackupCategory.CONN_KEYS)) { _, n, d -> keys[n] = d.readBytes() }
            assertEquals(name, ConnBackupKit.secrets().mapKeys { BackupCategory.connKey(it.key) }.mapValues { app.heyrobot.core.connect.ConnSecretsCodec.encode(it.value).toList() },
                keys.mapValues { it.value.toList() })
            // Each key goes to its connection, and to no other.
            val k = RestoreMerge.connectionKeys(v.connKeys, v.connections, v.connections!!, { false }, mine = true)
            assertEquals(name, listOf("conn/c-ha1234", "conn/c-ntfy01", "conn/c-disc01"), k.save)
        }
    }
}
