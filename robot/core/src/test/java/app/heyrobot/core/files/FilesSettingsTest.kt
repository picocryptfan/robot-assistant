// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * files-1.4-plan.md §6.6 and D12: files_settings.json v1 (the plan's defaults, every value round trip, a later key kept,
 * a wrong type or an unknown value Damaged, a newer version TooNew), and what Robot Files takes on storage, counted with
 * no key (FileStore.usage) and from the index (FileUsage.of).
 */
class FilesSettingsTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    @After fun noInflight() = FileStore.forgetInflight()

    @Test fun noFileIsThePlansDefaults() {
        val f = FilesSettingsCodec.decode(null)
        assertEquals(FilesSettingsFile.Ok(FilesSettings()), f)
        val d = f.settings
        assertTrue(d.on && d.readImageText && d.stripLocation && d.cleanup && d.brief)
        assertEquals(listOf(FileLevel.AUTO), listOf(d.receipts, d.bills, d.documents, d.photos, d.other).distinct())
        assertEquals(FileReminders.ASK, d.reminders)
        assertEquals("files_settings.json", FilesSettingsCodec.FILE)
    }

    @Test fun everyValueRoundTrips() {
        val s = FilesSettings(on = false, receipts = FileLevel.ASK, bills = FileLevel.OFF, documents = FileLevel.ASK, photos = FileLevel.OFF,
            other = FileLevel.ASK, readImageText = false, stripLocation = false, reminders = FileReminders.BRIEF, cleanup = false, brief = false)
        assertEquals(FilesSettingsFile.Ok(s), FilesSettingsCodec.decode(FilesSettingsCodec.encode(s)))
        for (r in FileReminders.entries) assertEquals(r, FilesSettingsCodec.decode(FilesSettingsCodec.encode(FilesSettings(reminders = r))).settings.reminders)
        val o = JSONObject(FilesSettingsCodec.encode(FilesSettings()))
        assertEquals(1, o.getInt("v"))
        assertEquals(setOf("v", "on", "receipts", "bills", "documents", "photos", "other", "readImageText", "stripLocation", "reminders", "cleanup", "brief"),
            o.keys().asSequence().toSet())
    }

    @Test fun aMissingKeyIsItsDefault() {
        assertEquals(FilesSettingsFile.Ok(FilesSettings(bills = FileLevel.OFF)), FilesSettingsCodec.decode("""{"v":1,"bills":"OFF"}"""))
    }

    @Test fun aKeyThisVersionDoesntKnowIsKeptAndWrittenBack() {
        // F12, deliberately: "hidden" (travel mode's drawers) is known now, so a later row stands in for the unknown key.
        val f = FilesSettingsCodec.decode("""{"v":1,"on":false,"shelves":["Medical"],"later":7}""") as FilesSettingsFile.Ok
        assertEquals(FilesSettings(on = false), f.read)
        assertEquals(setOf("shelves", "later"), f.extra.keys)
        val again = JSONObject(FilesSettingsCodec.encode(f.read.copy(brief = false), f.extra))
        assertEquals("Medical", again.getJSONArray("shelves").getString(0))
        assertEquals(7, again.getInt("later"))
        assertEquals(false, again.getBoolean("brief"))
        // A known key never comes back from the extras.
        assertEquals(true, JSONObject(FilesSettingsCodec.encode(FilesSettings(), mapOf("on" to false, "v" to 9))).getBoolean("on"))
        assertEquals(1, JSONObject(FilesSettingsCodec.encode(FilesSettings(), mapOf("v" to 9))).getInt("v"))
    }

    @Test fun aWrongTypeOrAnUnknownValueIsDamaged() {
        for (bad in listOf("", "[]", "not json", """{"on":true}""", """{"v":"1"}""", """{"v":0}""", """{"v":1.5}""", """{"v":1,"on":"yes"}""",
            """{"v":1,"receipts":"SOMETIMES"}""", """{"v":1,"bills":1}""", """{"v":1,"reminders":"CALENDAR"}""", """{"v":1,"brief":null}"""))
            assertTrue(bad, FilesSettingsCodec.decode(bad) is FilesSettingsFile.Damaged)
        // FX1, deliberately (files-1.4-review.md finding 5): a damaged file no longer gives plain defaults. Its travel mode
        // keys are read on their own (none here: off, as a file without them says); when nothing can be read, travel mode
        // counts as on with every drawer hidden (TravelModeSafetyTest).
        assertEquals(FilesSettings(), FilesSettingsCodec.decode("""{"v":1,"on":"yes"}""").settings)
        assertEquals(FilesSettings.CLOSED, FilesSettingsCodec.decode("not json").settings)
        assertEquals(FilesSettings.CLOSED, FilesSettingsFile.Damaged().settings)
    }

    @Test fun aNewerVersionIsTooNew() {
        assertEquals(FilesSettingsFile.TooNew(2), FilesSettingsCodec.decode("""{"v":2,"on":"whatever"}"""))
        assertEquals(FilesSettings(), FilesSettingsCodec.decode("""{"v":2}""").settings)
    }

    @Test fun aFilesSizeComesBackFromItsSealedSize() {
        val c = app.heyrobot.core.seal.SealStream.CHUNK.toLong()
        for (p in listOf(0L, 1L, 15L, 16L, c - 1, c, c + 1, 2 * c, 2 * c + 17, 200_000L, FileLimits.MAX_FILE, FileLimits.MAX_FILE - 1))
            assertEquals("$p", p, FileSeal.plainSize(FileSeal.sealedSize(p)))
        for (short in listOf(0L, 1L, 95L, 96L, 111L)) assertEquals("$short", 0L, FileSeal.plainSize(short))
    }

    @Test fun usageCountsSealedFilesWithNoKey() {
        val dir = tmp.newFolder("files")
        val keys = FakeFileKeys()
        val s = FileStore(dir, keys, "main", space = { 100L * FileLimits.GIB })
        assertEquals(FileUsage(0, 0), s.usage())
        val a = s.add(ByteArrayInputStream(ByteArray(100_000)), FileIntake("a.pdf", FileSource.SHARE))
        val b = s.add(ByteArrayInputStream(ByteArray(10)), FileIntake("b.pdf", FileSource.SHARE))
        val index = (s.load() as IndexLoad.Loaded).index
        assertEquals(FileUsage(2, 100_010), FileUsage.of(index))
        // From the sealed files' sizes, with the key locked away: the same as the index says.
        keys.refuse = FileKeyProblem.LOCKED
        assertEquals(FileUsage(2, 100_010), s.usage())
        // A file being deleted isn't counted, even while its delete waits for the key.
        keys.refuse = null
        s.delete(listOf(a.id))
        assertEquals(1, s.usage().files)
        assertEquals(FileUsage(1, 10), FileUsage.of((s.load() as IndexLoad.Loaded).index))
        assertEquals(b.id, (s.load() as IndexLoad.Loaded).index.entries.single().id)
    }
}
