// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.RLog
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Settings › Memory › Import (1.0): 0.9's and 1.0's exports read, the caps, damaged and foreign files refused with a reason,
 * unknown fields ignored, secrets and duplicates skipped and counted, private items kept, ids never taken from the file,
 * and every item content unless you say it is yours, except one the file marks as content.
 */
class MemoryImportTest {
    /** Noon UTC, Oct 11 2026: the fixtures' dates are before it. */
    private val now = LocalDate.of(2026, 10, 11).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val day = MemoryLimits.DAY_MS

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/memory/$name")!!.readBytes()
    private fun ok(bytes: ByteArray): ImportRead.Ok = MemoryImport.read(bytes) as ImportRead.Ok
    private fun ok(json: String) = ok(json.toByteArray())
    private fun refused(bytes: ByteArray): ImportProblem? = (MemoryImport.read(bytes) as? ImportRead.Refused)?.problem
    private fun refused(json: String) = refused(json.toByteArray())
    private fun plan(read: ImportRead.Ok, have: MemoryFile = MemoryFile(), mine: Boolean = false) = MemoryImport.plan(read, have, mine, now, Random(7))

    @After fun noSink() { RLog.sink = null }

    @Test fun zeroNinesExport() {
        val r = ok(fixture("export-0.9.json"))
        assertEquals(8, r.entries.size)
        assertEquals(0, r.damaged)
        val p = plan(r)
        // The door code holds a code: refused, counted. "I parked on P3" ended yesterday.
        assertEquals(1, p.secrets)
        assertEquals(1, p.ended)
        assertEquals(0, p.duplicates)
        assertEquals(8, p.inFile)
        assertEquals(listOf("My sister Julie's birthday is May 3", "Call me Sam", "Souper chez maman dimanche à 18 h", "My therapist is Dr. Martin",
            "Léo has hockey on Tuesdays/Thursdays", "Julie likes tulips"), p.add.map { it.text })
        val (julie, sam, souper, therapist, leo, tulips) = p.add
        // Dates, the use counter, the language and the kind come along; the People links don't.
        assertEquals(now - 400 * day, julie.createdAt)
        assertEquals(now - 300 * day, julie.updatedAt)
        assertEquals(4, julie.uses)
        assertEquals(now - 5 * day, julie.usedAt)
        assertEquals("en", julie.language)
        assertEquals(emptyList<String>(), julie.about)
        assertEquals(MemorySource.SAID, julie.source)
        assertEquals(MemoryKind.PREFERENCE, sam.kind)
        assertTrue(sam.pinned)
        assertEquals(MemorySource.TYPED, sam.source)
        // A content item keeps where it came from; the private one stays private.
        assertEquals(MemorySource.CONTENT, souper.source)
        assertEquals("your words in a conversation that held a message", souper.sourceLabel)
        assertEquals("fr", souper.language)
        assertTrue(therapist.secret)
        assertFalse(therapist.pinned)
        assertEquals(1, p.private)
        assertEquals(MemorySource.SUGGESTED, tulips.source)
        assertNull(leo.expiresAt)
        // New ids, valid, none of the file's.
        assertTrue(p.add.all { MemoryIds.valid(it.id) && !it.id.startsWith("m_00mfqe0w0") })
        assertEquals(p.add.size, p.add.map { it.id }.toSet().size)
        // Suggestions and "don't suggest" examples aren't imported (none are made yet; a file's would be someone's words).
        assertEquals(6, MemoryImport.into(MemoryFile(), p).items.size)
        assertEquals(emptyList<MemorySuggestion>(), MemoryImport.into(MemoryFile(), p).suggestions)
    }

    @Test fun oneZerosExportAndItsMarks() {
        val r = ok(fixture("export-1.0.json"))
        assertEquals(4, r.entries.size)
        val mine = plan(r, mine = true).add.associateBy { it.text }
        // Confirmed on the phone it came from, then ticked here: yours.
        assertEquals(MemoryImported.YOURS, mine.getValue("My sister Julie's birthday is May 3").imported)
        assertEquals(MemoryImported.YOURS, mine.getValue("Call me Sam").imported)
        // Imported there and never confirmed: still content, whatever is ticked.
        assertEquals(MemoryImported.CONTENT, mine.getValue("The spare key is under the blue pot").imported)
        assertTrue(mine.getValue("The spare key is under the blue pot").tainted)
        // Kept from something Robot read: content either way, with where it came from.
        val bob = mine.getValue("Bob's flight lands Friday at six")
        assertTrue(bob.tainted)
        assertEquals("something Robot read in a conversation", bob.sourceLabel)
    }

    @Test fun theExportAsSettingsWritesItRoundTrips() {
        // Settings › Memory › Export: MemoryCodec's JSON, indented by org.json (0.9's and 1.0's code are the same).
        val items = listOf(
            MemoryItem("m_aaaaaaaaaaaa", "Call me Sam", MemoryKind.PREFERENCE, pinned = true, source = MemorySource.SAID, createdAt = now - 9 * day),
            MemoryItem("m_bbbbbbbbbbbb", "Julie is vegetarian", source = MemorySource.TYPED, createdAt = now - 8 * day, uses = 2, usedAt = now - day),
            MemoryItem("m_cccccccccccc", "Bob's new number ends in 22", source = MemorySource.CONTENT, sourceLabel = "a message from Bob",
                createdAt = now - 7 * day, imported = MemoryImported.CONTENT),
            MemoryItem("m_dddddddddddd", "Dinner is at 7 on Saturday", source = MemorySource.SAID, createdAt = now - day, expiresAt = now + 2 * day),
        )
        val text = JSONObject(MemoryCodec.toJson(MemoryFile(items))).toString(2)
        val p = plan(ok(text), mine = true)
        assertEquals(items.map { it.text }, p.add.map { it.text })
        for ((a, b) in items.zip(p.add)) {
            assertEquals(a.kind, b.kind)
            assertEquals(a.pinned, b.pinned)
            assertEquals(a.source, b.source)
            assertEquals(a.sourceLabel, b.sourceLabel)
            assertEquals(a.createdAt, b.createdAt)
            assertEquals(a.expiresAt, b.expiresAt)
            assertEquals(a.uses, b.uses)
            assertEquals(a.usedAt, b.usedAt)
        }
        // What the import gives the vault survives memory.enc's codec.
        val back = (MemoryCodec.read(MemoryCodec.toJson(MemoryImport.into(MemoryFile(), p))) as MemoryParse.Ok).file.items
        assertEquals(p.add, back)
    }

    @Test fun contentByDefaultAndTheTick() {
        val r = ok(fixture("export-0.9.json"))
        // Off (the default): every item is content, as one kept from something Robot read.
        val off = plan(r)
        assertTrue(off.add.all { it.imported == MemoryImported.CONTENT && it.tainted })
        // On: yours, except the one 0.9 kept from content.
        val on = plan(r, mine = true)
        assertEquals(listOf("Souper chez maman dimanche à 18 h"), on.add.filter { it.tainted }.map { it.text })
        assertTrue(on.add.filter { !it.tainted }.all { it.imported == MemoryImported.YOURS })
        // Taken as content, a question it goes with is content too (the model is told where it came from).
        val packed = PromptPacking.pack("when is Julie's birthday", off.add, off.add.map { Recalled(it, 1.0, RecallBy.WORDS) }.take(1), now, unlocked = true)
        assertTrue(packed.content)
        assertTrue(packed.notesBlock!!.contains(PromptPacking.IMPORTED))
        val packedMine = PromptPacking.pack("when is Julie's birthday", on.add, on.add.map { Recalled(it, 1.0, RecallBy.WORDS) }.take(1), now, unlocked = true)
        assertFalse(packedMine.content)
        // "This is mine": the label and the import's mark go; a content source stays content.
        var f = MemoryImport.into(MemoryFile(), off)
        for (i in off.add) f = MemoryEdits.mine(f, i.id)!!
        assertTrue(f.items.all { it.imported == null })
        assertEquals(listOf("Souper chez maman dimanche à 18 h"), f.items.filter { it.tainted }.map { it.text })
        assertNull(MemoryEdits.mine(f, "m_unknown000000"))
        // The mark survives memory.enc's codec; a mark Robot doesn't know reads as content.
        val one = off.add.first()
        assertEquals(MemoryImported.CONTENT, (MemoryCodec.read(MemoryCodec.toJson(MemoryFile(listOf(one)))) as MemoryParse.Ok).file.items.single().imported)
        val odd = MemoryCodec.toJson(MemoryFile(listOf(one))).replace("\"imported\":\"CONTENT\"", "\"imported\":\"MAYBE\"")
        assertEquals(MemoryImported.CONTENT, (MemoryCodec.read(odd) as MemoryParse.Ok).file.items.single().imported)
    }

    @Test fun duplicatesOfWhatYouHaveAndInTheFile() {
        val have = MemoryFile(listOf(MemoryItem("m_eeeeeeeeeeee", "my sister julie’s birthday is may 3.", source = MemorySource.SAID, createdAt = now - day)))
        val r = ok("""{"v":1,"items":[{"text":"My sister Julie's birthday is May 3"},{"text":"Julie likes tulips"},{"text":"JULIE LIKES TULIPS!"}]}""")
        val p = plan(r, have)
        assertEquals(2, p.duplicates)
        assertEquals(listOf("Julie likes tulips"), p.add.map { it.text })
        // New ids never repeat one memory already has.
        val clash = MemoryFile((1..50).map { MemoryItem(MemoryIds.newId(now, Random(7)), "Old item $it", createdAt = now) })
        assertTrue(plan(ok("""{"v":1,"items":[{"text":"New item"}]}"""), clash).add.single().id !in clash.items.map { it.id })
    }

    @Test fun secretsAreRefusedAndNeverShown() {
        val r = ok("""{"v":1,"items":[
            {"text":"My bank PIN is 9921","secret":true},
            {"text":"The wifi password is hunter22"},
            {"text":"Card number 4111 1111 1111 1111"},
            {"text":"Julie's flight is AC 870"}]}""")
        val p = plan(r, mine = true)
        assertEquals(3, p.secrets)
        assertEquals(listOf("Julie's flight is AC 870"), p.add.map { it.text })
        // The same rule as the model's remember: SecretDetector's.
        for (e in r.entries.take(3)) assertTrue(SecretDetector.isSecret(e.text))
        // What a log could see of an entry or a plan holds no words.
        for (e in r.entries) assertFalse(e.toString().contains("9921") || e.toString().contains("hunter"))
        assertFalse(p.toString().contains("Julie"))
    }

    @Test fun privateFlagsAndOddValues() {
        val r = ok("""{"v":1,"items":[
            {"text":"A","secret":false,"pinned":"yes","kind":"OPINION","source":"ROBOT","lang":"de","created":"yesterday","uses":-4},
            {"text":"B","secret":"true"},
            {"text":"C","secret":null,"pinned":true,"source":"SAID","created":1.5e12,"updated":12,"used":99999999999999},
            {"text":"D","imported":null,"source":"TYPED"},
            {"text":"E","source":"SAID","sourceLabel":"a planted label"}]}""")
        val p = plan(r, mine = true).add.associateBy { it.text }
        val a = p.getValue("A")
        assertFalse(a.secret)
        assertFalse(a.pinned)
        assertEquals(MemoryKind.FACT, a.kind)
        // A source Robot doesn't know is never taken for your words.
        assertEquals(MemorySource.CONTENT, a.source)
        assertEquals(MemoryImported.CONTENT, a.imported)
        assertNull(a.language)
        assertEquals(now, a.createdAt)
        assertEquals(0, a.uses)
        // Anything but false is private, and a private item is never pinned.
        assertTrue(p.getValue("B").secret)
        val c = p.getValue("C")
        assertTrue(c.secret)
        assertFalse(c.pinned)
        // A date that isn't a whole number, or one in the future, isn't taken.
        assertEquals(now, c.createdAt)
        assertEquals(now, c.updatedAt)
        assertNull(c.usedAt)
        assertEquals(MemoryImported.CONTENT, p.getValue("D").imported)
        // Your own words carry no "where it came from".
        assertNull(p.getValue("E").sourceLabel)
        assertEquals(MemoryImported.YOURS, p.getValue("E").imported)
    }

    @Test fun pinsWhileThereIsRoomAndStopsAtTheLimit() {
        val have = MemoryFile((1..5).map { MemoryItem("m_pinned00000$it", "Preference $it", MemoryKind.PREFERENCE, pinned = true, createdAt = now) })
        val r = ok("""{"v":1,"items":[{"text":"Call me Sam","kind":"PREFERENCE","pinned":true},{"text":"I prefer Celsius","kind":"PREFERENCE","pinned":true}]}""")
        assertEquals(listOf(true, false), plan(r, have).add.map { it.pinned })
        // Memory holds 2,000 items: the rest is counted, not added.
        val full = MemoryFile((1..MemoryLimits.MAX_ITEMS - 1).map { MemoryItem("m_${it.toString().padStart(12, '0')}", "Item $it", createdAt = now) })
        val p = plan(ok("""{"v":1,"items":[{"text":"One more"},{"text":"And another"},{"text":"And a third"}]}"""), full)
        assertEquals(1, p.add.size)
        assertEquals(2, p.noRoom)
        assertEquals(MemoryLimits.MAX_ITEMS, MemoryImport.into(full, p).items.size)
    }

    @Test fun capsAndWrongFiles() {
        // 5 MB at most, read or not.
        assertEquals(ImportProblem.TOO_BIG, refused(ByteArray(MemoryImport.MAX_BYTES + 1) { ' '.code.toByte() }))
        val padded = """{"v":1,"items":[{"text":"Julie likes tulips"}]}""".padEnd(MemoryImport.MAX_BYTES, ' ')
        assertEquals(1, ok(padded).entries.size)
        // 5,000 items at most.
        fun items(n: Int) = """{"v":1,"items":[""" + (1..n).joinToString(",") { """{"text":"Item $it"}""" } + "]}"
        assertEquals(MemoryImport.MAX_ITEMS, ok(items(MemoryImport.MAX_ITEMS)).entries.size)
        assertEquals(ImportProblem.TOO_MANY, refused(items(MemoryImport.MAX_ITEMS + 1)))
        // Not an export, or damaged.
        for (s in listOf("", "hello", "[]", "{}", """{"v":1}""", """{"items":[]}""", """{"v":"1","items":[]}""", """{"v":1,"items":{}}""",
            """{"v":0,"items":[]}""", """{"v":1.0,"items":[]}""", """{"v":1,"items":[1,"two",null,{"text":""},{"text":5}]}""",
            """{"v":1,"items":[{"text":"cut short"}""", """{'v':1,'items':[]}""", """{"v":1,"items":[],}""", """{"v":1,"v":1,"items":[]}""",
            "BEGIN:VCARD\nVERSION:3.0\nFN:Julie\nEND:VCARD\n")) {
            assertEquals(s, ImportProblem.NOT_EXPORT, refused(s))
        }
        val cut = fixture("export-0.9.json").let { it.copyOf(it.size / 2) }
        assertEquals(ImportProblem.NOT_EXPORT, refused(cut))
        assertEquals(ImportProblem.NOT_EXPORT, refused(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        // A newer Robot's export.
        assertEquals(ImportProblem.TOO_NEW, refused("""{"v":2,"items":[]}"""))
        // An empty export is an export; a few damaged entries among good ones are counted, not fatal.
        assertEquals(0, ok("""{"v":1,"items":[]}""").entries.size)
        val some = ok("""{"v":1,"items":[{"text":"Good"},{"txt":"bad"},7,{"text":"   "}]}""")
        assertEquals(listOf("Good"), some.entries.map { it.text })
        assertEquals(3, some.damaged)
        assertEquals(3, plan(some).damaged)
        assertEquals(4, plan(some).inFile)
        // Text past 300 characters is cut as Robot cuts it.
        assertEquals(MemoryLimits.MAX_CHARS, ok("""{"v":1,"items":[{"text":"${"words".repeat(100)}"}]}""").entries.single().text.length)
    }

    @Test fun nothingAboutAnItemReachesALog() {
        val marker = "zq9marker"
        val logged = ArrayList<String>()
        RLog.sink = { logged += it }
        val r = ok("""{"v":1,"items":[{"text":"$marker likes tulips","sourceLabel":"$marker"},{"text":"$marker door code 4512"}]}""")
        val p = plan(r)
        MemoryImport.into(MemoryFile(), p)
        refused("""{"v":1,"items":[{"text":"$marker"}""")
        val texts = logged + r.toString() + p.toString() + r.entries.map { it.toString() } + p.add.map { it.toString() }
        assertEquals(emptyList<String>(), texts.filter { it.contains(marker) })
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}
