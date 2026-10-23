// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.BriefReminders
import app.heyrobot.core.RLog
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineSettingsCodec
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryRestore
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §8.5, §12: a marker in a note, a person, a memory item, a helper key, a transcript line and the passphrase never
 * reaches RLog, an exception's message or stack, or a toString of anything a backup or a restore makes. Core's backup
 * code logs nothing at all; the app logs codes and counts only (voice log 240–244, phase 2).
 */
class BackupLogTest {
    private val lines = ArrayList<String>()
    private val mark = BackupKit.MARK
    private val passphrase = "my passphrase $mark here"

    init {
        RLog.sink = { lines += it }
    }

    @After fun unhook() {
        RLog.sink = null
    }

    private fun clean(what: String, s: String?) = assertFalse("$what: $s", s?.contains(mark) == true)

    private fun clean(x: Throwable) {
        clean("message", x.message)
        clean("toString", x.toString())
        assertTrue(x.stackTrace.isEmpty() || x.stackTrace.none { it.toString().contains(mark) })
        x.cause?.let(::clean)
    }

    @Test fun nothingOfTheContentOrThePassphraseLeaks() {
        val header = BackupKit.header(kdf = BackupFixtures.LIGHT)
        val file = BackupFixtures.sealed(BackupKit.contentRecords(), header, passphrase)
        assertFalse(String(file, Charsets.ISO_8859_1).contains(mark))
        val key = RestoreReader.unlock(BackupKit.opener(file), header, passphrase)
        val v = RestoreReader.verify(BackupKit.opener(file), header, key)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v) + (BackupCategory.KEYS to CategoryChoice()), mine = true)
        val people = RestoreMerge.people(emptyList(), v.people!!.people, RestoreMode.FRESH, mine = false, now = 1)
        val settings = RestoreMerge.settings(SettingsNow(), v.settings, mine = false, people = people.landed::get)
        val memory = MemoryRestore.add(MemoryFile(), v.memory!!.items, v.memoryCodes!!.items, mine = true, now = 1, people = people.landed::get)
        val hf = RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(), v.handsfree, RestoreMode.ADD, mine = false)
        val routines = RestoreMerge.routines(emptyList(), RoutineSettings(), emptyList(), (RoutineCodec.decode(v.routines["routines.json"]) as RoutineFile.Ok).routines,
            RoutineSettingsCodec.decode(v.routines["routine_settings.json"]), BriefReminders.decode(v.routines["reminders.json"]), RestoreMode.ADD, mine = false,
            devices = hf.landed::get)
        val keys = RestoreMerge.keys(v.keys, setOf("claude"), { false }, mine = true)
        // 1.4: Robot Files' catalogue, its plan for yours and someone else's.
        val files = RestoreMerge.files(app.heyrobot.core.files.FileIndex(), v.files!!, RestoreMode.FRESH, mine = false, now = 1, people = { it })
        val filesMine = RestoreMerge.files(app.heyrobot.core.files.FileIndex(), v.files!!, RestoreMode.ADD, mine = true, now = 1, people = { it })
        val records = ArrayList<String>()
        RestoreReader.read(BackupKit.opener(file), v, key, BackupCategory.entries.toSet()) { c, n, d -> d.readBytes(); records += "$c $n" }
        for ((what, o) in listOf("verified" to v, "manifest" to v.manifest, "plan" to plan, "people" to people, "settings" to settings, "memory" to memory,
                "handsfree" to hf, "routines" to routines, "keys" to keys, "key" to key, "header" to header, "records" to records,
                "rating" to PassphraseCheck.rate(passphrase), "found" to v.transcripts, "skips" to v.skipped, "files" to files, "filesMine" to filesMine,
                "index" to v.files, "entries" to files.add, "filesSettings" to v.filesSettings!!.read)) {
            clean(what, o.toString())
        }
        // The container's records name codes and sizes only.
        val r = BackupContainerReader(BackupReader(java.io.ByteArrayInputStream(file, BackupFormat.HEADER_SIZE, file.size), header, key))
        while (true) clean("record", (r.next() ?: break).toString())
        key.close()

        // Failing: a wrong passphrase, a changed file, a damaged one.
        val wrong = runCatching { RestoreReader.unlock(BackupKit.opener(file), header, "$passphrase!") }.exceptionOrNull()!!
        assertEquals(OpenProblem.WRONG_PASSPHRASE, (wrong as BackupException).problem)
        assertNull(wrong.message)
        clean(wrong)
        val changed = file.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        BackupKdf.derive(passphrase, header).use { k ->
            clean(runCatching { RestoreReader.verify(BackupKit.opener(changed), header, k) }.exceptionOrNull()!!)
            val damaged = BackupKit.encrypt(RawRecords().manifest().record(RecordKind.ENTRY, "{\"c\":\"notes\",\"n\":\"$mark.json\",\"x\":1}", ByteArray(0)).end().bytes(), header, k)
            clean(runCatching { RestoreReader.verify(BackupKit.opener(damaged), header, k) }.exceptionOrNull()!!)
        }
        // A key's bytes are never in a toString or a log, and the passphrase's bytes are zeroed after Argon2id.
        assertTrue(lines.isEmpty())
    }
}
