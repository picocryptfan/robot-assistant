// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The records inside the stream: read strictly. Meta over 4 KiB, not StrictJson, bad names, the same
 * entry twice, no manifest or two, no end or a wrong one, anything after the end, and the caps. And a changed file is
 * caught before any record is handed out: only a verified file gives entries.
 */
class BackupContainerTest {
    private fun read(plain: ByteArray): List<BackupRecord> {
        val r = BackupContainerReader(ByteArrayInputStream(plain))
        val out = ArrayList<BackupRecord>()
        while (true) out += r.next() ?: break
        return out
    }

    private fun damaged(plain: ByteArray) = BackupKit.problem { read(plain) }

    @Test fun recordsRoundTrip() {
        val plain = BackupKit.records {
            entry(BackupCategory.NOTES, "a.json", BackupKit.text("{\"id\":\"a\"}"))
            entry(BackupCategory.TRANSCRIPTS, "20261011-1402-7f3a/audio.ogg", ByteArray(70_000) { it.toByte() })
            entry(BackupCategory.KEYS, "helper/claude", BackupKit.text("k"))
        }
        val records = read(plain)
        assertEquals(5, records.size)
        val m = (records[0] as BackupRecord.Manifest).manifest
        assertEquals("1.1.0", m.robot)
        assertEquals(BackupKit.CODE, m.code)
        assertEquals(BackupCategory.entries.map { it.id }.toSet(), m.categories.keys)
        val e = records.filterIsInstance<BackupRecord.Entry>()
        assertEquals(listOf("notes", "transcripts", "keys"), e.map { it.category })
        assertEquals(listOf(1, 0, 0), e.map { it.schema })
        val end = records.last() as BackupRecord.End
        assertEquals(3L, end.entries)
        assertEquals(10L + 70_000 + 1, end.bytes)
        // An entry's data, read as it comes; what isn't read is skipped.
        val r = BackupContainerReader(ByteArrayInputStream(plain))
        r.next()
        val first = r.next() as BackupRecord.Entry
        assertEquals("{\"id\":\"a\"}", first.data.readBytes().toString(Charsets.UTF_8))
        val audio = r.next() as BackupRecord.Entry
        assertEquals(0, audio.data.read())
        assertEquals("keys", (r.next() as BackupRecord.Entry).category)
        assertTrue(r.next() is BackupRecord.End)
        assertNull(r.next())
    }

    @Test fun theManifestIsWrittenInAFixedOrder() {
        val m = BackupManifest("1.1.0", 7, 9, linkedMapOf("notes" to CategoryCount(1, 2)), keys = true)
        assertEquals("{\"robot\":\"1.1.0\",\"code\":7,\"made\":9,\"format\":1,\"categories\":{\"notes\":{\"entries\":1,\"bytes\":2}},\"keys\":true}", m.toJson())
        assertEquals(m, BackupManifest.parse(app.heyrobot.core.StrictJson.parse(m.toJson())!!.value as Map<*, *>))
        assertEquals("\"a\\\"b\\\\c\\u0001\"", jsonString("a\"b\\c\u0001"))
    }

    @Test fun metaMustBeSmallStrictJson() {
        fun with(meta: String) = RawRecords().manifest().record(RecordKind.ENTRY, meta, ByteArray(0)).bytes()
        // Over 4 KiB.
        val big = "{\"c\":\"notes\",\"n\":\"a.json\",\"x\":\"" + "y".repeat(4100) + "\"}"
        assertEquals(OpenProblem.DAMAGED, damaged(with(big)))
        // Not strict JSON: single quotes, a trailing comma, a key twice, a comment, text after it, not an object.
        for (bad in listOf("{'c':'notes','n':'a.json'}", "{\"c\":\"notes\",\"n\":\"a.json\",}", "{\"c\":\"notes\",\"c\":\"people\",\"n\":\"a.json\"}",
                "{\"c\":\"notes\",/*x*/\"n\":\"a.json\"}", "{\"c\":\"notes\",\"n\":\"a.json\"} x", "[\"notes\",\"a.json\"]", "{\"c\":1,\"n\":\"a.json\"}",
                "{\"c\":\"notes\",\"n\":\"a.json\",\"v\":-1}", "{\"c\":\"notes\",\"n\":\"a.json\",\"v\":1.5}", "{\"c\":\"notes\",\"n\":\"a.json\",\"w\":1}")) {
            assertEquals(bad, OpenProblem.DAMAGED, damaged(with(bad)))
        }
        // Not UTF-8.
        val raw = RawRecords().manifest().raw(byteArrayOf(RecordKind.ENTRY.toByte(), 0, 0, 0, 3, '{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte())).bytes()
        assertEquals(OpenProblem.DAMAGED, damaged(raw))
    }

    @Test fun theOrderOfRecordsIsChecked() {
        val note = BackupKit.text("{}")
        // No manifest first.
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().entry("notes", "a.json", note).end().bytes()))
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().end().bytes()))
        // Two manifests.
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().manifest().end().bytes()))
        // No end.
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().entry("notes", "a.json", note).bytes()))
        // A wrong end.
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().entry("notes", "a.json", note).end(e = 2).bytes()))
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().entry("notes", "a.json", note).end(b = 3).bytes()))
        // Anything after the end, records or not.
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().end().entry("notes", "a.json", note).bytes()))
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().end().raw(byteArrayOf(0)).bytes()))
        // An unknown kind, an entry cut short, a manifest of another format.
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().record('X'.code, "{}").end().bytes()))
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().record(RecordKind.ENTRY, "{\"c\":\"notes\",\"n\":\"a.json\"}", note, declared = 10).bytes()))
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest(BackupKit.manifest().toJson().replace("\"format\":1", "\"format\":2")).end().bytes()))
        // And the empty one is fine.
        assertEquals(2, read(RawRecords().manifest().end().bytes()).size)
    }

    @Test fun theSameEntryTwiceIsRefused() {
        val note = BackupKit.text("{}")
        assertEquals(OpenProblem.DAMAGED, damaged(RawRecords().manifest().entry("notes", "a.json", note).entry("notes", "a.json", note).end().bytes()))
        // The writer refuses it too.
        assertTrue(runCatching { BackupKit.records { entry(BackupCategory.NOTES, "a.json", note); entry(BackupCategory.NOTES, "a.json", note) } }.isFailure)
        // The same name in two categories is two entries.
        assertEquals(4, read(RawRecords().manifest().entry("notes", "a.json", note).entry("history", "a.json", note).end().bytes()).size)
    }

    @Test fun badNamesAreRefused() {
        val bad = listOf("../a.json", "/etc/passwd", "a/../../b.json", "a.json/", "a\\b.json", "", ".json", "a b.json", "a.json\u0000", "x".repeat(100) + ".json",
            "notes/a.json", "a.txt", "a.JSON")
        for (n in bad) {
            assertFalse(n, BackupCategory.NOTES.validName(n))
            assertTrue(n, runCatching { BackupKit.records { entry(BackupCategory.NOTES, n, ByteArray(1)) } }.isFailure)
        }
        // Each category's own rules.
        val ok = mapOf(
            BackupCategory.SETTINGS to listOf("settings.json", "look.json", "memory.json", "messages.json", "web.json", "termux.json", "updates.json", "feedback.json"),
            BackupCategory.PEOPLE to listOf("people.json"),
            BackupCategory.MEMORY to listOf("memory.json"),
            BackupCategory.NOTES to listOf("3f2a-01.json", "a.json"),
            BackupCategory.HISTORY to listOf("c-1.json"),
            BackupCategory.TRANSCRIPTS to listOf("20261011-1402-7f3a/meta.json", "20261011-1402-7f3a/lines.jsonl", "20261011-1402-7f3a/snapshot.json",
                "20261011-1402-7f3a/summary-12.json", "20261011-1402-7f3a/audio.ogg"),
            BackupCategory.ROUTINES to listOf("routines.json", "routine_settings.json", "reminders.json"),
            BackupCategory.HANDSFREE to listOf("devices.json", "headset.json", "car.json", "places.json"),
            BackupCategory.KEYS to listOf("helper/claude", "helper/jarvis_2", "search/kagi", "search/brave", "search/mojeek", "memory-codes.json"),
        )
        for ((c, names) in ok) for (n in names) assertTrue("${c.id}/$n", c.validName(n))
        val no = mapOf(
            BackupCategory.SETTINGS to listOf("github_token.json", "web_answer.json", "nearest_answer.json", "setup_guide.json", "voice_log.txt", "settings.json.bad"),
            BackupCategory.PEOPLE to listOf("cheogram.signer", ".lock", "people.json.bad"),
            BackupCategory.MEMORY to listOf("memory.enc", "index.enc"),
            BackupCategory.TRANSCRIPTS to listOf("x/meta.json", "20261011-1402-7f3a/.session.json", "20261011-1402-7f3a/a/meta.json", "20261011-1402-7f3a/summary-draft.json",
                ".session.json"),
            BackupCategory.ROUTINES to listOf("state.json", "ready.json"),
            BackupCategory.HANDSFREE to listOf("park.json"),
            BackupCategory.KEYS to listOf("helper/Claude", "helper/../x", "search/google", "github_token", "me.voiceprint", "helper/", "claude_key.bin"),
        )
        for ((c, names) in no) for (n in names) assertFalse("${c.id}/$n", c.validName(n))
    }

    @Test fun capsAreKept() {
        // An entry over its category's cap: the writer refuses it; a reader refuses its category (the rest restores).
        assertTrue(runCatching { BackupKit.records { entry(BackupCategory.KEYS, "helper/claude", ByteArray(5000)) } }.isFailure)
        val file = BackupKit.encrypt(RawRecords().manifest()
            .entry("keys", "helper/claude", ByteArray(5000) { 'a'.code.toByte() })
            .entry("notes", "a.json", BackupKit.text("{\"v\":1,\"id\":\"a\",\"title\":\"\",\"text\":\"x\",\"created\":1,\"updated\":2}"))
            .end().bytes())
        val v = BackupKit.verify(file)
        assertEquals(listOf(CategorySkip("keys", BackupCategory.KEYS, SkipWhy.TOO_BIG)), v.skipped)
        assertTrue(v.has(BackupCategory.NOTES) && !v.has(BackupCategory.KEYS))
        // Too many entries for a category (People holds one file).
        val two = BackupKit.encrypt(RawRecords().manifest().entry("people", "people.json", BackupKit.text("{\"v\":1,\"people\":[]}"))
            .entry("people", "people.json ", ByteArray(0)).end().bytes())
        assertFalse(BackupKit.verify(two).has(BackupCategory.PEOPLE))
        // The whole file: a declared size over 32 GiB is refused before anything is read.
        val huge = RawRecords().manifest().record(RecordKind.ENTRY, "{\"c\":\"transcripts\",\"n\":\"20261011-1402-7f3a/audio.ogg\"}", null, declared = 33L * BackupCaps.GIB).bytes()
        assertEquals(OpenProblem.TOO_BIG, damaged(huge))
        // More than 200,000 records.
        val many = RawRecords().manifest()
        repeat(BackupCaps.MAX_RECORDS) { many.entry("skills", "s$it", ByteArray(0)) }
        assertEquals(OpenProblem.TOO_BIG, damaged(many.end().bytes()))
    }

    /** A changed file is caught before any record is handed out: entries come only from [RestoreReader.read] of a verified file. */
    @Test fun aChangedFileHandsOutNothing() {
        val notes = (1..12).associate { "n$it.json" to BackupKit.text("{\"v\":1,\"id\":\"n$it\",\"title\":\"\",\"text\":\"" + "x".repeat(30_000) + "\",\"created\":1,\"updated\":2}") }
        val good = BackupKit.file { for ((n, b) in notes) entry(BackupCategory.NOTES, n, b) }
        assertTrue(good.size > 5 * BackupFormat.SEALED)
        val handed = ArrayList<String>()
        val v = BackupKit.verify(good)
        RestoreReader.read(BackupKit.opener(good), v, BackupKit.key(), setOf(BackupCategory.NOTES)) { _, n, _ -> handed += n }
        assertEquals(notes.keys.toList(), handed)
        // Changed in the last chunk, cut, reordered, extended: verify throws, so there is nothing to read from.
        val last = good.size - 20
        val changed = listOf(
            good.copyOf().also { it[last] = (it[last].toInt() xor 1).toByte() },
            good.copyOf(good.size - 1),
            good.copyOf(BackupFormat.HEADER_SIZE + 3 * BackupFormat.SEALED),
            good + ByteArray(1),
            good.copyOf().also { f ->
                val a = f.copyOfRange(BackupFormat.HEADER_SIZE + BackupFormat.SEALED, BackupFormat.HEADER_SIZE + 2 * BackupFormat.SEALED)
                System.arraycopy(f, BackupFormat.HEADER_SIZE + 2 * BackupFormat.SEALED, f, BackupFormat.HEADER_SIZE + BackupFormat.SEALED, BackupFormat.SEALED)
                System.arraycopy(a, 0, f, BackupFormat.HEADER_SIZE + 2 * BackupFormat.SEALED, BackupFormat.SEALED)
            },
        )
        for (f in changed) {
            val p = BackupKit.problem { BackupKit.verify(f) }
            assertTrue(p.toString(), p == OpenProblem.CHANGED || p == OpenProblem.INCOMPLETE)
        }
        // And if the copy changed between the passes, the second pass stops at the bad chunk.
        val bad = changed[0]
        val seen = ArrayList<String>()
        assertTrue(BackupKit.problem { RestoreReader.read(BackupKit.opener(bad), v, BackupKit.key(), setOf(BackupCategory.NOTES)) { _, n, d -> d.readBytes(); seen += n } } != null)
    }

    @Test fun anEntrysDataEndsWhereItSays() {
        val plain = BackupKit.records { entry(BackupCategory.NOTES, "a.json", ByteArray(10) { 7 }) }
        val r = BackupContainerReader(ByteArrayInputStream(plain))
        r.next()
        val e = r.next() as BackupRecord.Entry
        val out = ByteArrayOutputStream()
        val buf = ByteArray(100)
        while (true) {
            val n = e.data.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        assertArrayEquals(ByteArray(10) { 7 }, out.toByteArray())
        assertEquals(-1, e.data.read())
    }
}
