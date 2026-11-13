// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.Note
import app.heyrobot.core.NoteCodec
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.CategoryChoice
import app.heyrobot.core.backup.CategoryOutcome
import app.heyrobot.core.backup.OpenProblem
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.BackupFiles
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.Paths
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.RestoreApply
import app.heyrobot.sys.RestoreFiles
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * 1.1, Back up and restore's app wiring on one phone (Robolectric): Back up now writes a file and reads it back; a restore
 * copies it in, refuses a wrong passphrase and a cut file, verifies, and writes People, Notes and History from :voice while
 * the settings wait for the main app (RestoreApply), marked as someone else's when "This backup is my own" is off; a
 * second restore in Add mode adds nothing. Memory, keys and the voiceprint need the phone's Keystore (Test Bench).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRoundTripTest {
    init { app.heyrobot.sys.HistoryFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()); NotesFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()); app.heyrobot.sys.PeopleFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()) }
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery-staple-orbit"
    private val t0 = 1_760_270_000_000L
    private val chosen = BackupCategory.covers(BackupCategory.SETTINGS) + setOf(BackupCategory.PEOPLE, BackupCategory.NOTES, BackupCategory.HISTORY)

    @Before fun setUp() {
        VoiceLog.resetForTests()
        ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000)
        File(Paths.dir(ctx), "settings.json").writeText(RobotSettings(saveHistory = true).toJson().toString())
        // auto-open-plan §4: open_apps.json is on the phone too, in its own category (never a ninth Settings entry).
        app.heyrobot.sys.OpenAppsStore.set(ctx, true)
        PeopleFiles.store(ctx).save(Person("6f0c2a3e-0000-4000-8000-000000000001", "Marie Tremblay",
            phones = listOf(ContactPhone("+15145550101", PhoneKind.MOBILE, "Mobile")), updatedAt = t0))
        File(NotesFiles.dir(ctx), "note-0001.json").writeText(NoteCodec.toJson(Note("note-0001", "Groceries", "milk, eggs", t0, t0 + 1_000)))
        File(Paths.conversations(ctx), "conv-0001.json").writeText(ConversationCodec.toJson(StoredConversation("conv-0001", t0, t0 + 5_000, listOf(
            ChatTurn("t1", TurnRole.USER, "What tea does Marie like?", t0), ChatTurn("t2", TurnRole.ASSISTANT, "Jasmine tea.", t0 + 1_000)))))
    }

    @After fun clean() {
        ctx.filesDir.deleteRecursively()
    }

    private fun backup(): File {
        val out = File(ctx.cacheDir, "Robot backup.robotbackup")
        val r = BackupJob(ctx, chosen, recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(r.toString(), r is BackupJob.Outcome.Done)
        assertEquals(chosen, (r as BackupJob.Outcome.Done).categories)
        return out
    }

    private fun wipe() {
        PeopleFiles.store(ctx).deleteAll()
        NotesFiles.dir(ctx).listFiles()?.forEach { it.delete() }
        Paths.conversations(ctx).listFiles()?.forEach { it.delete() }
    }

    @Test fun backUpThenRestoreOnTheSamePhone() {
        app.heyrobot.sys.OpenAppsStore.set(ctx, false)
        val file = backup()
        val st = BackupFiles.state(ctx)
        assertTrue(st.lastChecked && st.lastAt != null && st.lastCategories == chosen && !st.lastKeys)
        // Never the passphrase, in the file or the state.
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(pass))
        assertFalse(readOrNull(BackupFiles.stateFile(ctx))!!.contains(pass))
        wipe()
        File(Paths.dir(ctx), app.heyrobot.core.OpenAppsFile.NAME).delete()
        assertTrue(app.heyrobot.sys.OpenAppsStore.named(ctx))

        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(file)) {})
        assertEquals(OpenProblem.WRONG_PASSPHRASE, s.unlock("not-the-passphrase-at-all"))
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        val phone = s.phone()
        val plan = RestorePlanner.plan(v, phone, RestorePlanner.defaults(v), mine = false)
        assertEquals(listOf(BackupCategory.SETTINGS, BackupCategory.PEOPLE, BackupCategory.NOTES, BackupCategory.HISTORY, BackupCategory.OPEN_APPS), plan.restoring)
        val r = s.apply(plan) {}
        s.close()
        for (c in chosen) assertEquals(c.id, CategoryOutcome.Done, r.outcomes[c])
        assertFalse(RestoreFiles.incoming(ctx).exists())

        // :voice's categories are in place; someone else's file: the person unconfirmed, the conversation marked as content.
        val people = PeopleFiles.store(ctx).list()
        assertEquals(listOf("Marie Tremblay"), people.map { it.name })
        assertFalse(people.single().confirmed)
        // seal-people: People comes back sealed through its store; nothing in its folder holds a name in the clear.
        PeopleFiles.dir(ctx).listFiles()!!.forEach { assertFalse(it.name, String(it.readBytes(), Charsets.ISO_8859_1).contains("Tremblay")) }
        assertNotNull(File(PeopleFiles.dir(ctx), "people.enc").takeIf { it.isFile })
        // seal-notes: the note comes back sealed; nothing in its folder holds its words in the clear.
        assertNotNull(File(NotesFiles.dir(ctx), "note-0001.enc").takeIf { it.isFile })
        assertNotNull(NotesFiles.store(ctx).get("note-0001"))
        assertEquals(listOf("note-0001.enc"), NotesFiles.dir(ctx).list()!!.sorted())
        val conv = app.heyrobot.sys.ConversationStore.load(ctx, "conv-0001")!!
        // seal-history: History comes back sealed; nothing in its folder holds the words in the clear.
        assertTrue(Paths.conversations(ctx).listFiles()!!.none { it.name.endsWith(".json") || String(it.readBytes(), Charsets.ISO_8859_1).contains("Jasmine") })
        assertTrue(Paths.conversations(ctx).listFiles()!!.any { it.name == "conv-0001.enc" })
        assertTrue(TurnRules.untrusted(conv.turns))
        // The settings wait, staged, for the main app's start; then the journal goes.
        assertEquals(app.heyrobot.core.backup.JournalState.COMMIT, RestoreFiles.journal(ctx).state(BackupCategory.SETTINGS))
        RestoreApply.recover(ctx)
        assertTrue(RestoreFiles.journal(ctx).read().isEmpty())
        assertEquals(r, RestoreFiles.result(ctx))
        // auto-open-plan §4: "Open apps you name without a card" came back off, as backed up.
        assertFalse(app.heyrobot.sys.OpenAppsStore.named(ctx))
        // Nothing of the passphrase anywhere in the restore's files.
        RestoreFiles.dir(ctx).walkTopDown().filter { it.isFile }.forEach { f -> assertFalse(f.name, f.readText().contains(pass)) }
    }

    @Test fun addingTheSameBackupTwiceAddsNothing() {
        val file = backup()
        fun restore(): Int {
            val s = RestoreSession(ctx)
            assertNull(s.copyIn(Uri.fromFile(file)) {})
            assertNull(s.unlock(pass))
            assertNull(s.verify {})
            val v = s.verified!!
            val choices = RestorePlanner.defaults(v) + (BackupCategory.SETTINGS to CategoryChoice(restore = false))
            s.apply(RestorePlanner.plan(v, s.phone(), choices, mine = true)) {}
            s.close()
            RestoreApply.recover(ctx)
            return PeopleFiles.store(ctx).list().size + NotesFiles.dir(ctx).listFiles()!!.size + Paths.conversations(ctx).listFiles()!!.size
        }
        assertEquals(3, restore())
        assertEquals(3, restore())
        assertTrue(PeopleFiles.store(ctx).list().single().confirmed)
    }

    @Test fun aCutFileIsRefusedAndChangesNothing() {
        // Several 64 KiB chunks, so the cut is past the first (a cut first chunk reads as a wrong passphrase).
        File(NotesFiles.dir(ctx), "note-0002.json").writeText(NoteCodec.toJson(Note("note-0002", "Long", "words ".repeat(40_000), t0, t0)))
        val file = backup()
        val cut = File(ctx.cacheDir, "cut.robotbackup").apply { writeBytes(file.readBytes().copyOf(file.length().toInt() - 100)) }
        wipe()
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(cut)) {})
        assertNull(s.unlock(pass))
        val p = s.verify {}
        assertTrue(p.toString(), p == OpenProblem.INCOMPLETE || p == OpenProblem.CHANGED)
        s.close()
        assertTrue(PeopleFiles.store(ctx).list().isEmpty() && NotesFiles.dir(ctx).listFiles()!!.isEmpty())
    }

    /**
     * Phone lock's leftovers of its phase B: a phone with a lock code restores the category only
     * once :lock's code pad said yes (CategoryChoice.codeOk); the locks arrive off, written by the main app, which tells :lock.
     */
    @Test fun phoneLockNeedsThisPhonesCodeAndArrivesOff() {
        val lockDir = app.heyrobot.lock.LockStore.dir(ctx)
        val lock = app.heyrobot.core.lock.PhoneLock("lk_0000aaaa", "Night", app.heyrobot.core.lock.LockRule.Time(
            app.heyrobot.core.TimeWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(7, 0))), on = true)
        File(lockDir, app.heyrobot.lock.LockStore.LOCKS).writeText(app.heyrobot.core.lock.LockCodec.encode(
            app.heyrobot.core.lock.LockFile(app.heyrobot.core.lock.LockSettings(on = true), listOf(lock))))
        val out = File(ctx.cacheDir, "lock.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.PHONE_LOCK), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(r is BackupJob.Outcome.Done)
        // This phone: no locks yet, and a lock code set.
        File(lockDir, app.heyrobot.lock.LockStore.LOCKS).delete()
        File(lockDir, app.heyrobot.lock.LockStore.STATE).writeText(app.heyrobot.core.lock.LockCodec.encodeState(app.heyrobot.core.lock.LockState(codeSet = true)))
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(out)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        val phone = s.phone()
        assertTrue(phone.lockCode)
        val without = RestorePlanner.plan(v, phone, RestorePlanner.defaults(v), mine = true)
        assertEquals(app.heyrobot.core.backup.PlanSkip.NEEDS_CODE, without.of(BackupCategory.PHONE_LOCK).skip)
        val with = RestorePlanner.plan(v, phone, RestorePlanner.defaults(v) + (BackupCategory.PHONE_LOCK to CategoryChoice(codeOk = true)), mine = false)
        assertEquals(CategoryOutcome.Done, s.apply(with) {}.outcomes[BackupCategory.PHONE_LOCK])
        s.close()
        // Staged for the main app, which writes locks.json at its start.
        assertFalse(File(lockDir, app.heyrobot.lock.LockStore.LOCKS).isFile)
        RestoreApply.recover(ctx)
        val f = app.heyrobot.lock.LockStore.read(ctx).ok!!
        assertEquals(listOf("lk_0000aaaa"), f.locks.map { it.id })
        assertFalse(f.locks.single().on)
        // Phone lock itself keeps this phone's value (off here).
        assertFalse(f.settings.on)
    }

    /**
     * A phone with no locks when the preview opened (FRESH) and one added since: it stays and the backup's join it, as
     * every other FRESH category does (backup-1.1-design §7.3). Only Replace replaces locks.json.
     */
    @Test fun aFreshRestoreKeepsALockAddedSinceThePreview() {
        val lockDir = app.heyrobot.lock.LockStore.dir(ctx)
        fun lock(id: String) = app.heyrobot.core.lock.PhoneLock(id, id, app.heyrobot.core.lock.LockRule.Time(
            app.heyrobot.core.TimeWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(7, 0))), on = true)
        fun write(id: String) = File(lockDir, app.heyrobot.lock.LockStore.LOCKS).writeText(app.heyrobot.core.lock.LockCodec.encode(
            app.heyrobot.core.lock.LockFile(app.heyrobot.core.lock.LockSettings(on = true), listOf(lock(id)))))
        write("lk_0000aaaa")
        val out = File(ctx.cacheDir, "lock-fresh.robotbackup")
        assertTrue(BackupJob(ctx, setOf(BackupCategory.PHONE_LOCK), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {} is BackupJob.Outcome.Done)
        File(lockDir, app.heyrobot.lock.LockStore.LOCKS).delete()
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(out)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        val plan = RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v), mine = true)
        assertEquals(app.heyrobot.core.backup.RestoreMode.FRESH, plan.of(BackupCategory.PHONE_LOCK).mode)
        write("lk_0000bbbb")
        assertEquals(CategoryOutcome.Done, s.apply(plan) {}.outcomes[BackupCategory.PHONE_LOCK])
        s.close()
        RestoreApply.recover(ctx)
        assertEquals(listOf("lk_0000bbbb", "lk_0000aaaa"), app.heyrobot.lock.LockStore.read(ctx).ok!!.locks.map { it.id })
    }

    /**
     * 1.1, Skills (plan §4): skills.json goes into the backup (never skills_state.json); your own come back as saved, someone
     * else's arrive off, renamed when the name is taken (Add).
     */
    @Test fun skillsComeBackAndSomeoneElsesArriveOff() {
        val store = app.heyrobot.sys.SkillFiles.store(ctx)
        val home = app.heyrobot.core.skills.Skill("s-k3f9q2", 1, "Heading home", listOf("heading home"), emptyList(), emptyList(),
            listOf(app.heyrobot.core.skills.SkillStep.of(app.heyrobot.core.skills.StepKind.MAPS_NAVIGATE, mapOf("destination" to "home"))),
            app.heyrobot.core.skills.SkillLevel.JUST_DO_IT, app.heyrobot.core.skills.SkillProvenance(app.heyrobot.core.skills.SkillMade.VOICE, false, t0, t0))
        assertTrue(store.save(home))
        store.editState { app.heyrobot.core.skills.SkillStateFile.record(it, home.id, app.heyrobot.core.skills.SkillRunRecord(t0, 1, 1), setOf(home.id)) }
        val out = File(ctx.cacheDir, "skills.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.SKILLS), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(r is BackupJob.Outcome.Done)
        fun restore(mine: Boolean, replace: Boolean): app.heyrobot.core.backup.VerifiedBackup {
            val s = RestoreSession(ctx)
            assertNull(s.copyIn(Uri.fromFile(out)) {})
            assertNull(s.unlock(pass))
            assertNull(s.verify {})
            val v = s.verified!!
            val plan = RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v) + (BackupCategory.SKILLS to CategoryChoice(replace = replace)), mine)
            assertEquals(CategoryOutcome.Done, s.apply(plan) {}.outcomes[BackupCategory.SKILLS])
            s.close()
            return v
        }
        // Yours, on a phone that lost them: as saved.
        File(Paths.dir(ctx), app.heyrobot.core.skills.SkillStore.SKILLS).delete()
        val v = restore(mine = true, replace = true)
        assertEquals(1, v.skills!!.size)
        assertEquals(listOf(home), store.skills())
        // Someone else's, added beside yours: off, and renamed since the name is taken.
        restore(mine = false, replace = false)
        assertEquals(listOf(home), store.skills())
        val other = home.copy(steps = listOf(app.heyrobot.core.skills.SkillStep.of(app.heyrobot.core.skills.StepKind.FLASHLIGHT, mapOf("on" to true))))
        assertTrue(store.save(other.copy(id = "s-other1", name = "Lights", namesEn = listOf("lights"))))
        assertTrue(store.edit { list -> list.map { if (it.id == home.id) it.copy(steps = other.steps) else it } } != null)
        restore(mine = false, replace = false)
        val added = store.skills().last()
        assertEquals("Heading home (restored)", added.name)
        assertEquals(app.heyrobot.core.skills.SkillLevel.OFF, added.level)
        assertTrue(added.id != home.id)
    }

    /**
     * L12, D14: Settings › Robot to Robot goes in; "Answer while locked" comes back off even from your own backup, whether Robot to
     * Robot is on stays this phone's, and someone else's backup restores nothing of it.
     */
    @Test fun linkSettingsComeBackOnlyFromYourOwnWithAnswerWhileLockedOff() {
        val store = app.heyrobot.sys.LinkSettingsStore
        assertTrue(store.update(ctx) { it.copy(name = "Jo", whileLocked = true, brief = false) })
        val out = File(ctx.cacheDir, "links.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.LINKS), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertEquals(setOf(BackupCategory.LINKS), (r as BackupJob.Outcome.Done).categories)
        fun restore(mine: Boolean) {
            val s = RestoreSession(ctx)
            assertNull(s.copyIn(Uri.fromFile(out)) {})
            assertNull(s.unlock(pass))
            assertNull(s.verify {})
            val v = s.verified!!
            val plan = RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v), mine)
            assertEquals(if (mine) listOf(BackupCategory.LINKS) else emptyList(), plan.restoring)
            if (mine) assertEquals(CategoryOutcome.Done, s.apply(plan) {}.outcomes[BackupCategory.LINKS])
            s.close()
            RestoreApply.recover(ctx)
            assertTrue(RestoreFiles.journal(ctx).read().isEmpty())
        }
        store.file(ctx).delete()
        restore(mine = false)
        assertEquals(false, store.file(ctx).isFile)
        restore(mine = true)
        assertEquals(app.heyrobot.core.link.LinkSettings(name = "Jo", whileLocked = false, brief = false), store.current(ctx))
    }

    /** L12: the main app's own gate: staged pairings with no mark that the backup is yours go nowhere; marked, they wait for :link. */
    @Test fun stagedPairingsReachLinkOnlyWhenMarkedYours() {
        val pending = File(ctx.filesDir, app.heyrobot.core.link.LinkBackupCodec.PENDING)
        fun staged(mine: Boolean) {
            val j = RestoreFiles.journal(ctx)
            j.begin(BackupCategory.LINKS, app.heyrobot.core.backup.RestoreProcess.VOICE)
            val st = j.staging(BackupCategory.LINKS).apply { mkdirs() }
            File(st, BackupCategory.LINKS_FILE).writeText(app.heyrobot.core.link.LinkBackupCodec.encode(app.heyrobot.core.link.LinkBackupFile()))
            if (mine) File(st, BackupCategory.MINE_STAGED).writeText("1")
            j.commit(BackupCategory.LINKS, app.heyrobot.core.backup.RestoreProcess.MAIN)
            RestoreApply.recover(ctx)
            assertTrue(j.read().isEmpty())
        }
        pending.delete()
        staged(mine = false)
        assertEquals(false, pending.isFile)
        staged(mine = true)
        assertEquals(true, pending.isFile)
        pending.delete()
    }

    /**
     * The everyday set (everyday-set-plan.md §2.4): everyday_settings.json goes into the backup without your music app's pin
     * (trust stays on the phone); :voice stages it and the main app merges it at its start (RestoreApply): someone else's
     * can't turn the record of what went online off, your own comes back as saved, and a music app picked here stays.
     */
    @Test fun everydaySettingsComeBackWithoutTheMusicAppsPin() {
        val store = app.heyrobot.sys.EverydaySettingsStore
        val music = app.heyrobot.core.everyday.MusicApp("org.example.music", "c".repeat(64), "Music")
        assertTrue(store.update(ctx) { it.copy(music = false, online = false, musicApp = music) })
        val out = File(ctx.cacheDir, "everyday.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.EVERYDAY), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertEquals(setOf(BackupCategory.EVERYDAY), (r as BackupJob.Outcome.Done).categories)
        fun restore(mine: Boolean) {
            val s = RestoreSession(ctx)
            assertNull(s.copyIn(Uri.fromFile(out)) {})
            assertNull(s.unlock(pass))
            assertNull(s.verify {})
            val v = s.verified!!
            assertEquals(null, v.everyday!!.read.musicApp)
            val plan = RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v), mine)
            assertEquals(listOf(BackupCategory.EVERYDAY), plan.restoring)
            assertEquals(CategoryOutcome.Done, s.apply(plan) {}.outcomes[BackupCategory.EVERYDAY])
            s.close()
            // Staged for the main app, which writes it at its start.
            assertEquals(app.heyrobot.core.backup.JournalState.COMMIT, RestoreFiles.journal(ctx).state(BackupCategory.EVERYDAY))
            RestoreApply.recover(ctx)
            assertTrue(RestoreFiles.journal(ctx).read().isEmpty())
            store.forget()
        }
        // A new phone, someone else's backup: as saved, but the record of what went online stays on; no music app.
        store.file(ctx).delete()
        restore(mine = false)
        assertEquals(app.heyrobot.core.everyday.EverydaySettings(music = false), store.current(ctx))
        // Your own: as saved, still without the pin (it never went into the backup).
        store.file(ctx).delete()
        restore(mine = true)
        assertEquals(app.heyrobot.core.everyday.EverydaySettings(music = false, online = false), store.current(ctx))
        // A music app picked on this phone stays this phone's.
        assertTrue(store.update(ctx) { it.copy(musicApp = music) })
        restore(mine = true)
        assertEquals(music, store.current(ctx).musicApp)
    }

    /** A voiceprint restored without Settings: :voice stages only its flag, and the main app notes it once (meVoiceprint). */
    @Test fun aRestoredVoiceprintIsEnrolledOnce() {
        val j = RestoreFiles.journal(ctx)
        j.begin(BackupCategory.SETTINGS, app.heyrobot.core.backup.RestoreProcess.VOICE)
        File(j.staging(BackupCategory.SETTINGS).apply { mkdirs() }, BackupCategory.VOICEPRINT_STAGED).writeText("1")
        j.commit(BackupCategory.SETTINGS, app.heyrobot.core.backup.RestoreProcess.MAIN)
        val settings = File(Paths.dir(ctx), "settings.json")
        assertFalse(RobotSettings.fromJson(settings.readText()).meVoiceprint)
        RestoreApply.recover(ctx)
        assertTrue(RobotSettings.fromJson(settings.readText()).meVoiceprint)
        assertTrue(j.read().isEmpty())
        // Again: nothing to do, nothing changes (Settings › Conversation may have deleted the voiceprint since).
        settings.writeText(RobotSettings(saveHistory = true).toJson().toString())
        RestoreApply.recover(ctx)
        assertFalse(RobotSettings.fromJson(settings.readText()).meVoiceprint)
    }

    @Test fun notABackupIsRefusedBeforeThePassphrase() {
        val f = File(ctx.cacheDir, "notes.txt").apply { writeText("x".repeat(200)) }
        val r = RestoreSession(ctx).copyIn(Uri.fromFile(f)) {}
        assertTrue(r is RestoreSession.Refused.File && r.problem == OpenProblem.NOT_A_BACKUP)
    }

    // ------------------------------------------------------------------------------------------- 1.4, Robot Files (F11)

    private val receipt = "%PDF-1.7\n% Costco zq9marker\n".toByteArray() + ByteArray(150_000) { (it % 251).toByte() }
    private val beach = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(5_000) { (it % 13).toByte() }
    private val video = byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(90_000) { 3 }

    /** A phone's Robot Files: its own robot_files_v1 in software (each phone has its own key). */
    private fun phone(keystore: app.heyrobot.sys.SoftFileKeystore = app.heyrobot.sys.SoftFileKeystore()) =
        app.heyrobot.sys.FilesVault(app.heyrobot.sys.RobotFiles.dir(ctx), app.heyrobot.sys.KeystoreFileKeys(keystore), "voice", space = { 100L shl 30 }, locked = { false })

    private fun app.heyrobot.sys.FilesVault.addFile(b: ByteArray, name: String, type: String): app.heyrobot.core.files.FileId =
        commit(stage(b.inputStream()), app.heyrobot.core.files.FileIntake(name, app.heyrobot.core.files.FileSource.SHARE, app = "org.example.mail", type = type)).id

    private fun index(v: app.heyrobot.sys.FilesVault) = (v.load() as app.heyrobot.core.files.IndexLoad.Loaded).index

    private fun restore(file: File, vault: app.heyrobot.sys.FilesVault, mine: Boolean, replace: Boolean = false): Pair<app.heyrobot.core.backup.RestoreResult, app.heyrobot.core.backup.PhoneHas> {
        val s = RestoreSession(ctx) { vault }
        assertNull(s.copyIn(Uri.fromFile(file)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        val phone = s.phone()
        val r = s.apply(RestorePlanner.plan(v, phone, mapOf(BackupCategory.FILES to CategoryChoice(replace = replace)), mine)) {}
        s.close()
        return r to phone
    }

    /**
     * Back up with files on one phone (videos left out); restore on a new phone, its own key: each file sealed again with a
     * fresh key under it, filed as it was, no thumbnail carried (listed to be made again); the old phone's key never in it.
     */
    @Test fun filesGoToANewPhoneSealedUnderItsKey() {
        val logged = ArrayList<String>()
        app.heyrobot.core.RLog.sink = { logged += it }
        try {
            val oldKeys = app.heyrobot.sys.SoftFileKeystore()
            val old = phone(oldKeys)
            val r = old.addFile(receipt, "receipt zq9marker.pdf", "application/pdf")
            val b = old.addFile(beach, "beach.jpg", "image/jpeg")
            val m = old.addFile(video, "clip.mp4", "video/mp4")
            old.putThumb(b, ByteArray(500) { 7 })
            old.update { i -> i.copy(entries = i.entries.map { e ->
                if (e.id == m) e.copy(kind = app.heyrobot.core.files.FileKind.VIDEO) else
                    e.copy(state = app.heyrobot.core.files.FileState.FILED, drawer = if (e.id == r) "receipts" else "Car", thumb = e.id == b)
            }, extra = mapOf(app.heyrobot.core.files.FileViews.DRAWERS_KEY to listOf("Car"))) }
            assertTrue(app.heyrobot.sys.FilesSettingsStore.save(ctx, app.heyrobot.core.files.FilesSettings(bills = app.heyrobot.core.files.FileLevel.ASK)))
            val out = File(ctx.cacheDir, "files.robotbackup")
            val job = BackupJob(ctx, setOf(BackupCategory.FILES), recordings = false, pictures = true, videos = false) { old }
            val done = job.run(Uri.fromFile(out), pass) {}
            assertTrue(done.toString(), done is BackupJob.Outcome.Done)
            assertEquals(setOf(BackupCategory.FILES), (done as BackupJob.Outcome.Done).categories)
            assertFalse(BackupFiles.state(ctx).videos)
            // Nothing of a file in the clear in the backup.
            assertFalse(String(out.readBytes(), Charsets.ISO_8859_1).contains("zq9marker"))

            // What went in: the plain bytes (never the sealed form), the catalogue naming them, Settings › Files; no video.
            val s = RestoreSession(ctx) { old }
            assertNull(s.copyIn(Uri.fromFile(out)) {})
            assertNull(s.unlock(pass))
            assertNull(s.verify {})
            val v = s.verified!!
            assertEquals(listOf(r, b), v.files!!.entries.map { it.id })
            assertEquals(app.heyrobot.core.files.FileLevel.ASK, v.filesSettings!!.read.bills)
            s.close()

            // A new phone: Robot Files wiped, a key of its own.
            app.heyrobot.sys.RobotFiles.dir(ctx).deleteRecursively()
            app.heyrobot.sys.FilesSettingsStore.file(ctx).delete()
            app.heyrobot.sys.FilesSettingsStore.forget()
            val newKeys = app.heyrobot.sys.SoftFileKeystore(present = false)
            val fresh = phone(newKeys)
            val (result, here) = restore(out, fresh, mine = true)
            assertEquals(0, here.counts[BackupCategory.FILES])
            assertEquals(CategoryOutcome.Done, result.outcomes[BackupCategory.FILES])
            val idx = index(fresh)
            assertEquals(listOf(r, b), idx.entries.map { it.id })
            assertEquals(listOf("receipts", "Car"), idx.entries.map { it.drawer })
            assertTrue(idx.entries.none { it.thumb })
            assertEquals(listOf(b.hex), idx.extra[app.heyrobot.core.backup.FilesBackup.RETHUMB])
            assertEquals(listOf("Car"), idx.extra[app.heyrobot.core.files.FileViews.DRAWERS_KEY])
            assertTrue(fresh.open(r).use { it.readBytes() }.contentEquals(receipt))
            assertTrue(fresh.open(b).use { it.readBytes() }.contentEquals(beach))
            assertEquals(1, newKeys.generated)
            // Sealed under this phone's key: the old phone's can't open them.
            val stranger = app.heyrobot.sys.FilesVault(app.heyrobot.sys.RobotFiles.dir(ctx), app.heyrobot.sys.KeystoreFileKeys(oldKeys), "main", space = { 100L shl 30 }, locked = { false })
            assertTrue(runCatching { stranger.open(r).use { it.readBytes() } }.isFailure)
            // Never on disk in the clear, nothing left staged.
            ctx.filesDir.walkTopDown().filter { it.isFile }.forEach { f -> assertFalse(f.path, String(f.readBytes(), Charsets.ISO_8859_1).contains("zq9marker")) }
            assertFalse(app.heyrobot.sys.RestoreFiles.journal(ctx).staging(BackupCategory.FILES).exists())
            assertTrue(app.heyrobot.sys.RestoreFiles.journal(ctx).read().isEmpty())
            assertEquals(app.heyrobot.core.files.FileLevel.ASK, app.heyrobot.sys.FilesSettingsStore.current(ctx).bills)
            // Again, Add: everything is here, nothing added.
            val (again, _) = restore(out, fresh, mine = true)
            assertEquals(CategoryOutcome.Done, again.outcomes[BackupCategory.FILES])
            assertEquals(2, index(fresh).entries.size)
        } finally {
            app.heyrobot.core.RLog.sink = null
        }
        // Codes and counts only in the logs.
        assertTrue(logged.toString(), logged.none { it.contains("zq9marker") || it.contains("receipt") })
    }

    /**
     * Someone else's backup, Add: their files land in Inbox from "a backup that isn't yours", their drawer only offered,
     * their looser Settings › Files kept at this phone's; Replace says first how many of this phone's files go.
     */
    @Test fun someoneElsesFilesLandInInboxAndReplaceCountsWhatGoes() {
        val theirs = phone()
        val r = theirs.addFile(receipt, "their receipt.pdf", "application/pdf")
        theirs.update { i -> i.copy(entries = i.entries.map { it.copy(state = app.heyrobot.core.files.FileState.FILED, drawer = "Boat", tags = listOf("business")) }) }
        assertTrue(app.heyrobot.sys.FilesSettingsStore.save(ctx, app.heyrobot.core.files.FilesSettings(receipts = app.heyrobot.core.files.FileLevel.AUTO, stripLocation = false)))
        val out = File(ctx.cacheDir, "theirs.robotbackup")
        assertTrue(BackupJob(ctx, setOf(BackupCategory.FILES), recordings = false, pictures = true) { theirs }.run(Uri.fromFile(out), pass) {} is BackupJob.Outcome.Done)

        // This phone: one file of its own, Settings › Files stricter.
        app.heyrobot.sys.RobotFiles.dir(ctx).deleteRecursively()
        assertTrue(app.heyrobot.sys.FilesSettingsStore.save(ctx, app.heyrobot.core.files.FilesSettings(receipts = app.heyrobot.core.files.FileLevel.ASK)))
        val mine = phone()
        val own = mine.addFile(beach, "beach.jpg", "image/jpeg")
        val (added, here) = restore(out, mine, mine = false)
        assertEquals(1, here.counts[BackupCategory.FILES])
        assertEquals(CategoryOutcome.Done, added.outcomes[BackupCategory.FILES])
        val idx = index(mine)
        assertEquals(listOf(own, r), idx.entries.map { it.id })
        val e = idx.entry(r)!!
        assertEquals(app.heyrobot.core.files.FileState.INBOX, e.state)
        assertNull(e.drawer)
        assertEquals("Boat", e.filing!!.suggest)
        assertEquals(app.heyrobot.core.files.FilingWhy.NOT_YOURS, e.filing!!.why)
        assertEquals(app.heyrobot.core.files.FileSource.BACKUP, e.from.source)
        assertEquals("application/pdf", e.from.type)
        assertEquals(emptyList<String>(), e.tags)
        // Their drawer isn't one of yours until you choose it.
        assertEquals(emptyList<String>(), app.heyrobot.core.files.FileViews.yours(idx))
        val s = app.heyrobot.sys.FilesSettingsStore.current(ctx)
        assertEquals(app.heyrobot.core.files.FileLevel.ASK, s.receipts)
        assertTrue(s.stripLocation)
        // Replace: the preview counts this phone's files; they go, the backup's come.
        val (replaced, before) = restore(out, mine, mine = false, replace = true)
        assertEquals(2, before.counts[BackupCategory.FILES])
        assertEquals(CategoryOutcome.Done, replaced.outcomes[BackupCategory.FILES])
        assertEquals(listOf(r), index(mine).entries.map { it.id })
        assertTrue(runCatching { mine.open(own) }.isFailure)
    }

    /** A file the old phone can't read whole (a damaged chunk): the backup still completes, without it; the rest restores. */
    @Test fun aDamagedFileIsLeftOutAndTheBackupCompletes() {
        val v = phone()
        val r = v.addFile(receipt, "receipt.pdf", "application/pdf")
        val b = v.addFile(beach, "beach.jpg", "image/jpeg")
        val sealed = File(app.heyrobot.sys.RobotFiles.dir(ctx), "blobs/${r.hex}.rf")
        sealed.writeBytes(sealed.readBytes().also { it[100_000] = (it[100_000].toInt() xor 1).toByte() })
        val out = File(ctx.cacheDir, "damaged.robotbackup")
        val done = BackupJob(ctx, setOf(BackupCategory.FILES), recordings = false, pictures = true) { v }.run(Uri.fromFile(out), pass) {}
        assertTrue(done.toString(), done is BackupJob.Outcome.Done)
        app.heyrobot.sys.RobotFiles.dir(ctx).deleteRecursively()
        val fresh = phone()
        val (result, _) = restore(out, fresh, mine = true)
        assertEquals(CategoryOutcome.Done, result.outcomes[BackupCategory.FILES])
        assertEquals(listOf(b), index(fresh).entries.map { it.id })
    }

    /**
     * Robot Files' key gone (its list can never be read again): Back up now shows Files off and disabled, so the backup goes
     * on with the rest instead of failing; your tick on Files is kept for next time.
     */
    @Test fun filesThatCantBeReadNeverStopABackup() {
        val keystore = app.heyrobot.sys.SoftFileKeystore()
        val v = phone(keystore)
        v.addFile(receipt, "receipt.pdf", "application/pdf")
        keystore.keys.clear()
        v.drop()
        val survey = BackupSources.survey(ctx, RobotSettings(saveHistory = true)) { v }
        assertEquals(-1, survey.getValue(BackupCategory.FILES).count)
        val ticked = chosen + BackupCategory.FILES
        // The job alone, given Files, says why it can't.
        val failed = BackupJob(ctx, ticked, recordings = false, pictures = true) { v }.run(Uri.fromFile(File(ctx.cacheDir, "no.robotbackup")), pass) {}
        assertEquals(app.heyrobot.core.backup.BackupCode.FILES_UNREADABLE, (failed as BackupJob.Outcome.Failed).code)
        // What the window hands it: what the list shows on.
        val going = BackupSources.going(ticked, survey)
        assertEquals(chosen, going)
        val out = File(ctx.cacheDir, "rest.robotbackup")
        val done = BackupJob(ctx, going, recordings = false, pictures = true, remember = ticked) { v }.run(Uri.fromFile(out), pass) {}
        assertTrue(done.toString(), done is BackupJob.Outcome.Done)
        assertEquals(chosen, (done as BackupJob.Outcome.Done).categories)
        assertEquals(ticked, BackupFiles.state(ctx).chosen)
    }

    /** FX1: a backup of one file in your drawer "Car", made with Settings › Files' bills at Ask me first. */
    private fun carBackup(): File {
        val old = phone()
        old.addFile(receipt, "receipt.pdf", "application/pdf")
        old.update { i -> i.copy(entries = i.entries.map { it.copy(state = app.heyrobot.core.files.FileState.FILED, drawer = "Car") },
            extra = mapOf(app.heyrobot.core.files.FileViews.DRAWERS_KEY to listOf("Car"))) }
        assertTrue(app.heyrobot.sys.FilesSettingsStore.save(ctx, app.heyrobot.core.files.FilesSettings(bills = app.heyrobot.core.files.FileLevel.ASK)))
        val out = File(ctx.cacheDir, "car.robotbackup")
        assertTrue(BackupJob(ctx, setOf(BackupCategory.FILES), recordings = false, pictures = true) { old }.run(Uri.fromFile(out), pass) {} is BackupJob.Outcome.Done)
        // A new phone: no files, Settings › Files as it comes.
        app.heyrobot.sys.RobotFiles.dir(ctx).deleteRecursively()
        app.heyrobot.sys.FilesSettingsStore.file(ctx).delete()
        app.heyrobot.sys.FilesSettingsStore.forget()
        return out
    }

    /** FX1: a vault whose phone locks as soon as a restore's Files plan is staged, until [Armed.on] goes false. */
    private class Armed(var on: Boolean = true)

    private fun lockingPhone(keystore: app.heyrobot.sys.SoftFileKeystore, armed: Armed): app.heyrobot.sys.FilesVault {
        val plan = File(RestoreFiles.journal(ctx).staging(BackupCategory.FILES), RestoreSession.PLAN)
        return app.heyrobot.sys.FilesVault(app.heyrobot.sys.RobotFiles.dir(ctx), app.heyrobot.sys.KeystoreFileKeys(keystore), "voice",
            space = { 100L shl 30 }, locked = { armed.on && plan.isFile })
    }

    /**
     * FX3: the phone locks as the commit runs and is unlocked again by the restore's own last recovery, which finishes it:
     * the result says restored, not "finishes after you unlock".
     */
    @Test fun aCommitTheLastRecoveryFinishedSaysRestored() {
        val out = carBackup()
        val plan = File(RestoreFiles.journal(ctx).staging(BackupCategory.FILES), RestoreSession.PLAN)
        var asked = false
        val fresh = app.heyrobot.sys.FilesVault(app.heyrobot.sys.RobotFiles.dir(ctx), app.heyrobot.sys.KeystoreFileKeys(app.heyrobot.sys.SoftFileKeystore(present = false)),
            "voice", space = { 100L shl 30 }, locked = { plan.isFile && !asked.also { asked = true } })
        val (result, _) = restore(out, fresh, mine = true)
        assertTrue(asked)
        assertEquals(CategoryOutcome.Done, result.outcomes[BackupCategory.FILES])
        assertTrue(RestoreFiles.journal(ctx).read().isEmpty())
        assertEquals(CategoryOutcome.Done, app.heyrobot.core.backup.RestoreResult.fromJson(readOrNull(RestoreFiles.resultFile(ctx)))!!.outcomes[BackupCategory.FILES])
        assertEquals(listOf("Car"), index(fresh).entries.map { it.drawer })
    }

    /**
     * FX3: a commit that can't write now (here the store's folder refuses writes; on the phone, free space or a storage
     * error) says so in its own words, never "the phone locked"; a second restore meanwhile says an earlier one hasn't
     * finished. Once it can write, the next recovery finishes it.
     */
    @Test fun aCommitWaitingForRoomSaysSo() {
        val out = carBackup()
        val dir = app.heyrobot.sys.RobotFiles.dir(ctx)
        val plan = File(RestoreFiles.journal(ctx).staging(BackupCategory.FILES), RestoreSession.PLAN)
        var stuck = true
        val fresh = app.heyrobot.sys.FilesVault(dir, app.heyrobot.sys.KeystoreFileKeys(app.heyrobot.sys.SoftFileKeystore(present = false)), "voice",
            space = { 100L shl 30 }, locked = { if (plan.isFile && stuck) dir.setWritable(false); false })
        try {
            val (first, _) = restore(out, fresh, mine = true)
            assertEquals(CategoryOutcome.WaitsForRoom, first.outcomes[BackupCategory.FILES])
            assertEquals(app.heyrobot.core.backup.JournalState.COMMIT, RestoreFiles.journal(ctx).state(BackupCategory.FILES))
            assertEquals(CategoryOutcome.WaitsForRoom, app.heyrobot.core.backup.RestoreResult.fromJson(readOrNull(RestoreFiles.resultFile(ctx)))!!.outcomes[BackupCategory.FILES])
            assertEquals(CategoryOutcome.EarlierWaits, restore(out, fresh, mine = true).first.outcomes[BackupCategory.FILES])
            assertEquals(app.heyrobot.core.backup.JournalState.COMMIT, RestoreFiles.journal(ctx).state(BackupCategory.FILES))
        } finally {
            stuck = false
            dir.setWritable(true)
        }
        RestoreSession.recover(ctx) { fresh }
        assertTrue(RestoreFiles.journal(ctx).read().isEmpty())
        assertEquals(listOf("Car"), index(fresh).entries.map { it.drawer })
    }

    /**
     * FX1 (files-1.4-review.md findings 2 and 11): the phone locks just as a Files restore commits. The result says it
     * finishes after you unlock (not restored yet). Before that unlock, travel mode goes on with your drawer "Car" hidden;
     * the finish then brings the files in and merges the backup's Settings › Files over the file as it is: travel mode stays
     * on, "Car" stays hidden, the backup's own value lands; and the result says restored.
     */
    @Test fun aDeferredFilesFinishKeepsTravelModeOn() {
        val out = carBackup()
        val armed = Armed()
        val fresh = lockingPhone(app.heyrobot.sys.SoftFileKeystore(present = false), armed)
        val (result, _) = restore(out, fresh, mine = true)
        assertEquals(CategoryOutcome.AfterUnlock, result.outcomes[BackupCategory.FILES])
        assertEquals(app.heyrobot.core.backup.JournalState.COMMIT, RestoreFiles.journal(ctx).state(BackupCategory.FILES))
        assertEquals(CategoryOutcome.AfterUnlock, app.heyrobot.core.backup.RestoreResult.fromJson(readOrNull(RestoreFiles.resultFile(ctx)))!!.outcomes[BackupCategory.FILES])
        // Nothing of Settings › Files written yet.
        assertFalse(app.heyrobot.sys.FilesSettingsStore.file(ctx).exists())
        // Travel mode on, "Car" hidden, before the unlock.
        assertTrue(app.heyrobot.sys.FilesSettingsStore.save(ctx, app.heyrobot.core.files.FilesSettings(travel = true, hidden = setOf("Car"))))
        // Unlocked: the finish.
        armed.on = false
        RestoreSession.recover(ctx) { fresh }
        assertTrue(RestoreFiles.journal(ctx).read().isEmpty())
        val s = app.heyrobot.sys.FilesSettingsStore.current(ctx)
        assertTrue(s.travel)
        assertEquals(setOf("Car"), s.hidden)
        assertEquals(app.heyrobot.core.files.FileLevel.ASK, s.bills)
        assertEquals(listOf("Car"), index(fresh).entries.map { it.drawer })
        assertEquals(CategoryOutcome.Done, app.heyrobot.core.backup.RestoreResult.fromJson(readOrNull(RestoreFiles.resultFile(ctx)))!!.outcomes[BackupCategory.FILES])
    }

    /**
     * FX1 (finding 2): a deferred finish that can't bring the files in for good (the key gone meanwhile) writes nothing of
     * Settings › Files: the file stays byte for byte as it was, and the result says not restored.
     */
    @Test fun aFailedFinishLeavesSettingsAsTheyWere() {
        val out = carBackup()
        val keys = app.heyrobot.sys.SoftFileKeystore(present = false)
        val armed = Armed()
        val fresh = lockingPhone(keys, armed)
        val (result, _) = restore(out, fresh, mine = true)
        assertEquals(CategoryOutcome.AfterUnlock, result.outcomes[BackupCategory.FILES])
        assertTrue(app.heyrobot.sys.FilesSettingsStore.save(ctx, app.heyrobot.core.files.FilesSettings(travel = true, hidden = setOf("Car"))))
        val before = app.heyrobot.sys.FilesSettingsStore.file(ctx).readBytes()
        keys.keys.clear()
        armed.on = false
        RestoreSession.recover(ctx) { fresh }
        assertTrue(before.contentEquals(app.heyrobot.sys.FilesSettingsStore.file(ctx).readBytes()))
        assertFalse(RestoreFiles.journal(ctx).staging(BackupCategory.FILES).exists())
        assertEquals(CategoryOutcome.Failed, app.heyrobot.core.backup.RestoreResult.fromJson(readOrNull(RestoreFiles.resultFile(ctx)))!!.outcomes[BackupCategory.FILES])
    }

    /**
     * FX1 (finding 10): a second restore while the first one's Files commit still waits finishes it first, never staging
     * over it; still locked, its Files waits too ("Unlock and restore again"), and nothing staged is lost.
     */
    @Test fun aSecondRestoreFinishesTheWaitingOneFirst() {
        val out = carBackup()
        val armed = Armed()
        val fresh = lockingPhone(app.heyrobot.sys.SoftFileKeystore(present = false), armed)
        assertEquals(CategoryOutcome.AfterUnlock, restore(out, fresh, mine = true).first.outcomes[BackupCategory.FILES])
        // Still locked: this one's Files waits, the first one's line and staging stay.
        assertEquals(CategoryOutcome.Locked, restore(out, fresh, mine = true).first.outcomes[BackupCategory.FILES])
        assertEquals(app.heyrobot.core.backup.JournalState.COMMIT, RestoreFiles.journal(ctx).state(BackupCategory.FILES))
        assertTrue(File(RestoreFiles.journal(ctx).staging(BackupCategory.FILES), RestoreSession.PLAN).isFile)
        // Unlocked: the first one finishes, then this one runs (Add: the file is already here).
        armed.on = false
        assertEquals(CategoryOutcome.Done, restore(out, fresh, mine = true).first.outcomes[BackupCategory.FILES])
        assertEquals(1, index(fresh).entries.size)
        assertTrue(RestoreFiles.journal(ctx).read().isEmpty())
    }

    // ------------------------------------------------------------------------------------------------- 1.4, Robot Lists

    /** A phone's Robot Lists: its own two keys in software (each phone has its own), Settings › Lists from its file. */
    private fun listsPhone(keys: app.heyrobot.sys.SoftListsKeys = app.heyrobot.sys.SoftListsKeys()) =
        app.heyrobot.sys.ListsVault(app.heyrobot.sys.ListsFiles.dir(ctx), keys, settings = { app.heyrobot.sys.ListsSettingsStore.current(ctx) },
            someoneElse = { false }, locked = { false })

    private fun listsSettings(s: app.heyrobot.core.lists.ListsSettings) {
        app.heyrobot.sys.ListsSettingsStore.file(ctx).writeText(app.heyrobot.core.lists.ListsSettingsCodec.encode(s))
        app.heyrobot.sys.ListsSettingsStore.forget()
    }

    private fun lists(v: app.heyrobot.sys.ListsVault) = (v.load(firstUse = false) as app.heyrobot.core.lists.ListsLoad.Loaded).value

    private fun restoreLists(file: File, vault: app.heyrobot.sys.ListsVault, mine: Boolean, replace: Boolean = false): app.heyrobot.core.backup.RestoreResult {
        val s = RestoreSession(ctx, lists = { vault })
        assertNull(s.copyIn(Uri.fromFile(file)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        val r = s.apply(RestorePlanner.plan(v, s.phone(), mapOf(BackupCategory.LISTS to CategoryChoice(replace = replace)), mine)) {}
        s.close()
        return r
    }

    /**
     * lists-plan.md §6 (LS7), LT12 on one phone: back up with lists (an add said while locked merged in first) and "Hear and
     * tick on the lock screen" on; restore on a reset phone with its own keys: the lists as they were, sealed under the new
     * phone's key that the restored switch calls for; someone else's backup then arrives apart, marked, and its looser
     * switch is kept safer. The old phone's keys are never in the file.
     */
    @Test fun listsGoToANewPhoneSealedUnderItsKey() {
        app.heyrobot.sys.ListsFiles.onChange = null
        val oldKeys = app.heyrobot.sys.SoftListsKeys()
        val old = listsPhone(oldKeys)
        listsSettings(app.heyrobot.core.lists.ListsSettings())
        val first = lists(old.also { it.load() })
        val shopping = first.withRole(app.heyrobot.core.lists.ListRole.SHOPPING)!!.id
        old.update(app.heyrobot.core.lists.ItemHow.TYPED) { f, who ->
            app.heyrobot.core.lists.ListsEdits.add(f, shopping, listOf(app.heyrobot.core.lists.NewItem("oat milk")), who) { app.heyrobot.core.lists.ListsId.new(java.security.SecureRandom()) }
        }
        // An add said while locked, waiting in the journal: a backup carries it merged.
        oldKeys.locked = true
        assertTrue(old.addLocked(shopping, listOf(app.heyrobot.core.lists.NewItem("eggs"))) is app.heyrobot.sys.LockedAdd.Ok)
        oldKeys.locked = false
        listsSettings(app.heyrobot.core.lists.ListsSettings(lockScreen = true))
        val out = File(ctx.cacheDir, "lists.robotbackup")
        val done = BackupJob(ctx, setOf(BackupCategory.LISTS), recordings = false, pictures = true, lists = { listsPhone(oldKeys) }).run(Uri.fromFile(out), pass) {}
        assertTrue(done.toString(), done is BackupJob.Outcome.Done)
        assertEquals(setOf(BackupCategory.LISTS), (done as BackupJob.Outcome.Done).categories)
        // Neither the sealed file nor a key in it: the plain words only inside the backup's own encryption.
        assertFalse(String(out.readBytes(), Charsets.ISO_8859_1).contains("oat milk"))

        // A reset phone: no lists, no settings, new keys.
        app.heyrobot.sys.ListsFiles.dir(ctx).deleteRecursively()
        app.heyrobot.sys.ListsSettingsStore.file(ctx).delete()
        app.heyrobot.sys.ListsSettingsStore.forget()
        val newKeys = app.heyrobot.sys.SoftListsKeys()
        val fresh = listsPhone(newKeys)
        assertEquals(CategoryOutcome.Done, restoreLists(out, fresh, mine = true).outcomes[BackupCategory.LISTS])
        val back = lists(fresh)
        assertEquals(first.lists.map { it.id }, back.lists.map { it.id })
        assertEquals(listOf("oat milk", "eggs"), back.withRole(app.heyrobot.core.lists.ListRole.SHOPPING)!!.live.map { it.text })
        // Sealed under the new phone's lock-screen key, as the restored switch says; the old phone's keys never asked for.
        assertEquals(app.heyrobot.sys.ListsKeyAliases.LOCK_SCREEN,
            app.heyrobot.core.lists.ListsEnvelope.alias(File(app.heyrobot.sys.ListsFiles.dir(ctx), app.heyrobot.core.lists.ListsEnvelope.FILE).readBytes()))
        assertTrue(app.heyrobot.sys.ListsSettingsStore.current(ctx).lockScreen)
        assertTrue(app.heyrobot.sys.ListsKeyAliases.LOCK_SCREEN in newKeys.keys)
        assertTrue(RestoreFiles.journal(ctx).read().isEmpty())

        // Someone else's backup on a phone with the switch off: theirs apart, marked, and the switch stays off.
        listsSettings(app.heyrobot.core.lists.ListsSettings())
        fresh.follow(app.heyrobot.core.lists.ListsSettings())
        val r = restoreLists(out, fresh, mine = false)
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.LISTS])
        assertEquals(1, r.keptSafer)
        val all = lists(fresh)
        val theirs = all.lists.filter { it.from == app.heyrobot.core.lists.ListFrom.BACKUP }
        assertEquals(first.lists.size, theirs.size)
        assertTrue(theirs.all { it.role == null && it.items.all { i -> i.by == app.heyrobot.core.lists.ItemBy.BACKUP } })
        assertFalse(app.heyrobot.sys.ListsSettingsStore.current(ctx).lockScreen)
        assertEquals(app.heyrobot.sys.ListsKeyAliases.UNLOCKED,
            app.heyrobot.core.lists.ListsEnvelope.alias(File(app.heyrobot.sys.ListsFiles.dir(ctx), app.heyrobot.core.lists.ListsEnvelope.FILE).readBytes()))
        // With Replace, the preview counts what goes: this phone's lists here.
        val s = RestoreSession(ctx, lists = { fresh })
        assertEquals(all.lists.size, s.phone().counts[BackupCategory.LISTS])
        s.close()
    }

    /**
     * LS12 (LT12 step 5 on the Pixel 8): a backup made with "Hear and tick on the lock screen" on, opened as not yours. The
     * preview's "Kept safer" counts the switch when this phone's is off, and doesn't when it is on too; the restore then
     * keeps this phone's off.
     */
    @Test fun thePreviewCountsTheLockScreenSwitchItKeepsSafer() {
        app.heyrobot.sys.ListsFiles.onChange = null
        val keys = app.heyrobot.sys.SoftListsKeys()
        val phone = listsPhone(keys)
        phone.load()
        listsSettings(app.heyrobot.core.lists.ListsSettings(lockScreen = true))
        phone.follow(app.heyrobot.core.lists.ListsSettings(lockScreen = true))
        val out = File(ctx.cacheDir, "lock-screen.robotbackup")
        val done = BackupJob(ctx, setOf(BackupCategory.SETTINGS, BackupCategory.LISTS), recordings = false, pictures = true, lists = { listsPhone(keys) }).run(Uri.fromFile(out), pass) {}
        assertTrue(done.toString(), done is BackupJob.Outcome.Done)
        fun preview(): Int {
            val s = RestoreSession(ctx, lists = { phone })
            assertNull(s.copyIn(Uri.fromFile(out)) {})
            assertNull(s.unlock(pass))
            assertNull(s.verify {})
            // LS12: the manifest names each category in words, for a Robot that doesn't know one.
            assertEquals("Listes", s.verified!!.manifest.name("lists", app.heyrobot.core.i18n.Lang.FR))
            assertEquals("Settings", s.verified!!.manifest.name("settings", app.heyrobot.core.i18n.Lang.EN))
            return s.keptSafer(RestorePlanner.plan(s.verified!!, s.phone(), RestorePlanner.defaults(s.verified!!), false))!!.also { s.close() }
        }
        val on = preview()
        // This phone's switch off (Settings › Lists' own way: the seal first, then the file).
        assertTrue(phone.follow(app.heyrobot.core.lists.ListsSettings()) is app.heyrobot.sys.ListsFollow.Done)
        assertTrue(app.heyrobot.sys.ListsSettingsStore.update(ctx) { it.copy(lockScreen = false) })
        val off = preview()
        assertEquals(on + 1, off)
        val r = restoreLists(out, phone, mine = false)
        assertEquals(1, r.keptSafer)
        assertFalse(app.heyrobot.sys.ListsSettingsStore.current(ctx).lockScreen)
    }

    /** seal-history-fix-b 1: History that can't be opened whole stops the backup with History's own words, not "backup failed". */
    @Test fun historyThatCantBeOpenedStopsTheBackupWithItsWords() {
        val k = app.heyrobot.sys.SoftHistoryKeys()
        app.heyrobot.sys.HistoryFiles.testKeys(k)
        assertTrue(app.heyrobot.sys.ConversationStore.save(ctx, app.heyrobot.core.StoredConversation("h1", 1_000L, 2_000L, listOf(app.heyrobot.core.ChatTurn("u", app.heyrobot.core.TurnRole.USER, "hi", 1_000L)))))
        val out = File(ctx.cacheDir, "no-history.robotbackup")
        fun code() = (BackupJob(ctx, setOf(BackupCategory.HISTORY), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {} as BackupJob.Outcome.Failed).code
        k.locked = true
        assertEquals(app.heyrobot.core.backup.BackupCode.HISTORY_UNREADABLE, code())
        k.locked = false
        val f = File(app.heyrobot.sys.Paths.conversations(ctx), "h1.enc")
        f.writeBytes(f.readBytes().also { it[4] = 99; it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) // a newer layout this one can't check
        app.heyrobot.sys.HistoryFiles.drop()
        assertEquals(app.heyrobot.core.backup.BackupCode.HISTORY_TOO_NEW, code())
    }

    /** seal-history-fix-c 1: a restore over History that can't be opened whole says why (13 or 14's words), not "the phone locked". */
    @Test fun historyThatCantBeOpenedTellsTheRestoreWhy() {
        val k = app.heyrobot.sys.SoftHistoryKeys()
        app.heyrobot.sys.HistoryFiles.testKeys(k)
        assertTrue(app.heyrobot.sys.ConversationStore.save(ctx, app.heyrobot.core.StoredConversation("h1", 1_000L, 2_000L, listOf(app.heyrobot.core.ChatTurn("u", app.heyrobot.core.TurnRole.USER, "hi", 1_000L)))))
        val out = File(ctx.cacheDir, "history.robotbackup")
        assertTrue(BackupJob(ctx, setOf(BackupCategory.HISTORY), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {} is BackupJob.Outcome.Done)
        fun history(): CategoryOutcome? {
            val s = RestoreSession(ctx)
            assertNull(s.copyIn(Uri.fromFile(out)) {})
            assertNull(s.unlock(pass))
            assertNull(s.verify {})
            val v = s.verified!!
            val r = s.apply(RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v), mine = true)) {}
            s.close()
            return r.outcomes[BackupCategory.HISTORY]
        }
        k.locked = true
        app.heyrobot.sys.HistoryFiles.drop()
        assertEquals(CategoryOutcome.HistoryUnreadable(tooNew = false), history())
        k.locked = false
        val f = File(app.heyrobot.sys.Paths.conversations(ctx), "h1.enc")
        f.writeBytes(f.readBytes().also { it[4] = 99; it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        app.heyrobot.sys.HistoryFiles.drop()
        assertEquals(CategoryOutcome.HistoryUnreadable(tooNew = true), history())
    }

    /** Lists that can't be opened now never go half: Back up now says so, and leaves the file unwritten. */
    @Test fun listsThatCantBeOpenedStopTheBackupWithTheirWords() {
        app.heyrobot.sys.ListsFiles.onChange = null
        val keys = app.heyrobot.sys.SoftListsKeys()
        val v = listsPhone(keys)
        v.load()
        keys.invalid += app.heyrobot.sys.ListsKeyAliases.UNLOCKED
        val survey = BackupSources.survey(ctx, RobotSettings(), lists = { listsPhone(keys) })
        assertEquals(-1, survey.getValue(BackupCategory.LISTS).count)
        val out = File(ctx.cacheDir, "no-lists.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.LISTS), recordings = false, pictures = true, lists = { listsPhone(keys) }).run(Uri.fromFile(out), pass) {}
        assertEquals(app.heyrobot.core.backup.BackupCode.LISTS_UNREADABLE, (r as BackupJob.Outcome.Failed).code)
        // A restore on that phone: Replace only, the lists kept aside.
        val s = RestoreSession(ctx, lists = { listsPhone(keys) })
        assertEquals(app.heyrobot.core.backup.ListsHere.KEY_GONE, s.phone().lists)
        s.close()
    }

    /**
     * spoken-log: the Logbook goes in a backup opened through its vault (never the sealed file), and a Replace restore puts
     * the backup's entries back in place of what's there; a log that can't be opened now stops the backup with its words.
     */
    @Test fun theLogbookGoesInABackupAndComesBackOnReplace() {
        val keys = app.heyrobot.sys.SoftMemoryKeys()
        app.heyrobot.sys.LogbookFiles.testKeys(keys)
        val vault = app.heyrobot.sys.LogbookFiles.store(ctx)
        assertEquals(app.heyrobot.sys.LogbookAdd.ADDED, vault.add(app.heyrobot.core.logbook.LogEntry("a1", 1_000L, "took my pills"), whileLocked = false))
        assertTrue(app.heyrobot.sys.LogbookFiles.saveSettings(ctx, app.heyrobot.core.logbook.LogbookSettings(on = true, addLocked = false)))
        assertEquals(1, BackupSources.survey(ctx, RobotSettings()).getValue(BackupCategory.LOGBOOK).count)
        val out = File(ctx.cacheDir, "logbook.robotbackup")
        val job = BackupJob(ctx, setOf(BackupCategory.LOGBOOK), recordings = false, pictures = true)
        val done = job.run(Uri.fromFile(out), pass) {}
        assertTrue("${(done as? BackupJob.Outcome.Failed)?.code} ${job.refused}", done is BackupJob.Outcome.Done)
        assertEquals(setOf(BackupCategory.LOGBOOK), (done as BackupJob.Outcome.Done).categories)
        assertFalse(String(out.readBytes(), Charsets.ISO_8859_1).contains("pills"))

        // Replace: what's here now goes, the backup's entries come back, sealed again here.
        assertTrue(vault.deleteAll())
        assertEquals(app.heyrobot.sys.LogbookAdd.ADDED, vault.add(app.heyrobot.core.logbook.LogEntry("b2", 2_000L, "ran 5 km"), whileLocked = false))
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(out)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        assertEquals(listOf("a1"), s.verified!!.logbook!!.map { it.id })
        val r = s.apply(RestorePlanner.plan(s.verified!!, s.phone(), mapOf(BackupCategory.LOGBOOK to CategoryChoice(replace = true)), true)) {}
        s.close()
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.LOGBOOK])
        assertEquals(listOf("a1"), (vault.read() as app.heyrobot.sys.LogbookRead.Ok).entries.map { it.id })
        assertFalse(String(File(app.heyrobot.sys.LogbookFiles.dir(ctx), "log.enc").readBytes(), Charsets.ISO_8859_1).contains("pills"))

        // A log that can't be opened now (its key busy): not offered, and chosen anyway the backup stops with the Logbook's words.
        keys.busy = true
        assertEquals(-1, BackupSources.survey(ctx, RobotSettings()).getValue(BackupCategory.LOGBOOK).count)
        val r2 = BackupJob(ctx, setOf(BackupCategory.LOGBOOK), recordings = false, pictures = true).run(Uri.fromFile(File(ctx.cacheDir, "no-logbook.robotbackup")), pass) {}
        assertEquals(app.heyrobot.core.backup.BackupCode.LOGBOOK_UNREADABLE, (r2 as BackupJob.Outcome.Failed).code)
        keys.busy = false
    }
}
