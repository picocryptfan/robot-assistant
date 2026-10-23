// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.Approval
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.NoteCodec
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.RoutineSettingsCodec
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.ToolName
import app.heyrobot.core.BriefReminders
import app.heyrobot.core.BriefLock
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryImported
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.termux.ScriptLevel
import app.heyrobot.core.termux.TermuxSettings
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Design §7.11 and §12: the committed backup-v1 fixture opens with its passphrase and restores to the expected stores,
 * now and in every later release; and this release's writer still makes the very same bytes from the same plaintext,
 * salt and key.
 */
class BackupGoldenTest {
    private val file by lazy { BackupKit.resource(BackupFixtures.BASIC) ?: error("missing fixture ${BackupFixtures.BASIC}") }
    private val header by lazy { RestoreReader.header(file) }
    private val key: BackupKey get() = KEY ?: RestoreReader.unlock(BackupKit.opener(file), header, BackupFixtures.BASIC_PASSPHRASE).also { KEY = it }

    @Test fun writeFixtures() {
        assumeTrue("only when making the fixtures again", System.getenv("ROBOT_WRITE_FIXTURES") == "1")
        BackupFixtures.write()
    }

    @Test fun theHeaderIsFormatOneWithTheDesignsKdf() {
        assertEquals(1, header.format)
        assertEquals(KdfParams.CURRENT, header.kdf)
        assertEquals(BackupKit.CODE, header.appVersion)
        assertArrayEquals(BackupKit.SALT, header.salt)
    }

    @Test fun theWrongPassphraseDoesntOpenIt() {
        assertEquals(OpenProblem.WRONG_PASSPHRASE, BackupKit.problem { RestoreReader.unlock(BackupKit.opener(file), header, "crate-mosaic-unlocked-plaza-humming-oxygen ") })
    }

    @Test fun itHoldsWhatWasWritten() {
        val v = RestoreReader.verify(BackupKit.opener(file), header, key)
        // 1.1's Phone lock and Skills categories came after the fixture was made: a backup without them still opens whole
        // (BackupLockTest and RestorePlanTest have their own). So did 1.4's Files (BackupFilesTest, BackupDowngradeTest).
        assertEquals(BackupCategory.entries.toSet() - BackupCategory.PHONE_LOCK - BackupCategory.SKILLS - BackupCategory.VOICEPRINT - BackupCategory.FILES,
            v.found.keys)
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals("1.1.0", v.manifest.robot)
        assertTrue(v.manifest.keys)
        assertEquals(BackupKit.T0, v.manifest.made)
        assertEquals(26L, v.entries)
        assertEquals(8, v.settings.size)
        assertEquals(listOf("Marie Tremblay", "Bob ${BackupKit.MARK}"), v.people!!.people.map { it.name })
        assertEquals(listOf(BackupKit.MEM_MARIE, BackupKit.MEM_PLAIN), v.memory!!.items.map { it.id })
        assertEquals(listOf(BackupKit.MARIE), v.memory!!.items[0].about)
        assertEquals(listOf(BackupKit.MEM_CODE), v.memoryCodes!!.items.map { it.id })
        assertEquals(mapOf(BackupKit.NOTE_1 to BackupKit.T0 + 1_000, BackupKit.NOTE_2 to BackupKit.T0 + 2_000), v.notes)
        assertEquals(mapOf(BackupKit.CONV to BackupKit.T0 + 5_000), v.history)
        assertEquals(setOf("meta.json", "lines.jsonl", "summary-1.json"), v.transcripts[BackupKit.TRANSCRIPT]!!.files)
        assertEquals(setOf("routines.json", "routine_settings.json", "reminders.json"), v.routines.keys)
        assertEquals(setOf("devices.json", "headset.json", "car.json", "places.json"), v.handsfree.keys)
        assertEquals(listOf("helper/claude", "search/brave"), v.keys)
    }

    /** Your own backup on a new phone: everything comes back as you left it, except trust and this phone's own values. */
    @Test fun itRestoresToTheExpectedStores() {
        val v = RestoreReader.verify(BackupKit.opener(file), header, key)
        val choices = RestorePlanner.defaults(v) + (BackupCategory.KEYS to CategoryChoice())
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), choices, mine = true)
        assertEquals(BackupCategory.entries.toList() - BackupCategory.PHONE_LOCK - BackupCategory.SKILLS - BackupCategory.VOICEPRINT - BackupCategory.FILES,
            plan.restoring)
        assertTrue(plan.categories.all { it.mode == RestoreMode.FRESH || it.category == BackupCategory.KEYS })

        val people = RestoreMerge.people(emptyList(), v.people!!.people, RestoreMode.FRESH, mine = true, now = BackupKit.T0)
        assertEquals(2, people.people.size)
        assertTrue(people.people.all { it.confirmed })
        val landed = people.landed::get

        val s = RestoreMerge.settings(SettingsNow(), v.settings, mine = true, people = landed)
        assertEquals(emptyList<SaferField>(), s.kept)
        assertEquals(Approval.AUTO, s.settings.approval(ToolName.SEND_TEXT))
        assertTrue(s.settings.personalToHelpers)
        assertEquals(listOf("claude", BackupKit.JARVIS), s.settings.helpers.map { it.id })
        assertTrue(s.settings.helper(BackupKit.JARVIS)!!.trust)
        // This phone's own values, and trust confirmed again.
        assertEquals(CalendarPick.AUTO, s.settings.eventCalendar)
        assertFalse(s.settings.meVoiceprint)
        assertEquals(emptyMap<String, Long>(), s.settings.confirmedTerms)
        val messages = MessagesFile.fromJson(s.files[MessagesFile.FILE])
        assertFalse(messages.settings.on)
        assertEquals(setOf(MessageApp.CHEOGRAM), messages.settings.allowlist.apps)
        assertTrue(messages.pins.isEmpty())
        assertEquals(setOf(BackupKit.MARIE), messages.noCard)
        assertTrue(messages.settings.voiceYesSends)
        val termux = TermuxSettings.fromJson(JSONObject(s.files["termux.json"]!!))
        assertFalse(termux.enabled)
        assertNull(termux.trustedSigner)
        assertEquals(ScriptLevel.AUTO, termux.script("backup.sh"))
        assertEquals(8, s.files.size)

        val memory = app.heyrobot.core.memory.MemoryRestore.replace(MemoryFile(), v.memory!!.items, v.memoryCodes!!.items, mine = true, people = landed)
        assertEquals(listOf(BackupKit.MEM_MARIE, BackupKit.MEM_PLAIN, BackupKit.MEM_CODE), memory.file.items.map { it.id })
        assertTrue(memory.file.items.last().secret)
        assertEquals(listOf(BackupKit.MARIE), memory.file.items[0].about)
        assertTrue(memory.file.items.none { it.imported == MemoryImported.CONTENT })

        val hf = RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(), v.handsfree, RestoreMode.FRESH, mine = true)
        assertEquals(listOf("dev-1"), hf.devices.devices.map { it.id })
        assertEquals(true, hf.devices.devices[0].private)
        assertFalse(hf.headset.safety.personalOnlyPrivate)
        assertTrue(hf.car.on)
        assertEquals("Home", hf.places.home?.label)

        val routines = RestoreMerge.routines(emptyList(), RoutineSettings(), emptyList(),
            (RoutineCodec.decode(v.routines["routines.json"]) as RoutineFile.Ok).routines, RoutineSettingsCodec.decode(v.routines["routine_settings.json"]),
            BriefReminders.decode(v.routines["reminders.json"]), RestoreMode.FRESH, mine = true, devices = hf.landed::get)
        assertEquals(listOf(BackupKit.ROUTINE_TIME, BackupKit.ROUTINE_HEADSET), routines.routines.map { it.id })
        assertTrue(routines.routines.all { it.enabled })
        assertEquals("dev-1", (routines.routines[1].trigger as RoutineTrigger.Device).deviceId)
        assertEquals(BriefLock.EVERYTHING, routines.settings.briefLock)
        assertEquals(1, routines.reminders.size)

        val notes = RestoreMerge.byUpdated(emptyMap(), v.notes, RestoreMode.FRESH)
        assertEquals(setOf(BackupKit.NOTE_1, BackupKit.NOTE_2), notes.write)
        val keys = RestoreMerge.keys(v.keys, s.settings.helpers.map { it.id }.toSet(), { false }, mine = true)
        assertEquals(listOf("helper/claude", "search/brave"), keys.save)

        // The second pass hands out each entry of the chosen categories, checked again.
        val got = LinkedHashMap<String, ByteArray>()
        RestoreReader.read(BackupKit.opener(file), v, key, setOf(BackupCategory.NOTES, BackupCategory.HISTORY, BackupCategory.KEYS, BackupCategory.TRANSCRIPTS)) { c, n, d ->
            got["${c.id}/$n"] = d.readBytes()
        }
        assertEquals(9, got.size)
        assertEquals("milk, eggs, ${BackupKit.MARK}", NoteCodec.fromJson(String(got["notes/${BackupKit.NOTE_1}.json"]!!))!!.text)
        assertEquals(listOf(BackupKit.MEM_MARIE), ConversationCodec.fromJson(String(got["history/${BackupKit.CONV}.json"]!!))!!.turns[0].memoryIds)
        assertEquals("test-key-${BackupKit.MARK}-claude", String(got["keys/helper/claude"]!!))
        assertNotNull(got["transcripts/${BackupKit.TRANSCRIPT}/lines.jsonl"])
    }

    /** The same fixture from someone else: what loosens Robot keeps this phone's value, and nothing of trust comes. */
    @Test fun fromSomeoneElseItIsKeptSafer() {
        val v = RestoreReader.verify(BackupKit.opener(file), header, key)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v) + (BackupCategory.KEYS to CategoryChoice()), mine = false)
        assertEquals(PlanSkip.NOT_YOURS, plan.of(BackupCategory.KEYS).skip)
        val people = RestoreMerge.people(emptyList(), v.people!!.people, RestoreMode.FRESH, mine = false, now = BackupKit.T0)
        assertTrue(people.people.none { it.confirmed })
        val s = RestoreMerge.settings(SettingsNow(), v.settings, mine = false, people = people.landed::get)
        assertTrue(s.kept.containsAll(listOf(SaferField.APPROVALS, SaferField.PERSONAL_TO_HELPERS, SaferField.HELPERS, SaferField.MEMORY_LOCK_SCREEN,
            SaferField.MESSAGES_VOICE_YES, SaferField.MESSAGES_NO_CARD, SaferField.TERMUX_SCRIPTS, SaferField.WEB_ASK, SaferField.UPDATES_TEST_BUILDS,
            SaferField.FEEDBACK_SEND_TO)))
        assertEquals(Approval.ASK, s.settings.approval(ToolName.SEND_TEXT))
        // Helpers new to this phone arrive off and untrusted, under new ids (a key kept here under the file's id stays
        // with no helper): found by name.
        val jarvis = s.settings.helpers.single { it.name == "Jarvis" }
        assertTrue(jarvis.id != BackupKit.JARVIS)
        assertFalse(jarvis.on || jarvis.trust)
        assertTrue(s.settings.helpers.none { it.id == "claude" })
        val memory = app.heyrobot.core.memory.MemoryRestore.replace(MemoryFile(), v.memory!!.items, v.memoryCodes!!.items, mine = false, people = people.landed::get)
        assertEquals(2, memory.file.items.size)
        assertTrue(memory.file.items.all { it.imported == MemoryImported.CONTENT })
    }

    /** This release's writer makes the very same file from the fixture's plaintext, salt and key. */
    @Test fun theWriterStillWritesTheSameBytes() {
        val plain = BackupKit.decrypt(file, key)
        assertArrayEquals(file, BackupKit.encrypt(plain, header, key))
        assertEquals(file.size.toLong(), BackupFormat.HEADER_SIZE + plain.size + ((plain.size + BackupFormat.CHUNK - 1) / BackupFormat.CHUNK) * 16L)
    }

    companion object {
        private var KEY: BackupKey? = null
    }
}
