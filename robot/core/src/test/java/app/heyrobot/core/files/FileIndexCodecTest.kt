// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * files-1.4-plan.md §11's FileIndexCodecTest: the index's JSON round trip and its fixed order, versions (a newer one is
 * never read), strict types and caps per entry (a bad entry dropped and counted, the rest kept), every key and value this
 * Robot doesn't know kept and written back, 1.5's reserved fields read as absent, and 5,000 entries at their largest
 * within the index's 32 MiB.
 */
class FileIndexCodecTest {
    private val sha = "a".repeat(64)
    private fun id(n: Int) = FileId.of("%032x".format(n))!!

    private fun full(n: Int = 1) = FileEntry(
        id = id(n),
        name = "2026-08-14 Costco 84.12 CAD",
        kind = FileKind.RECEIPT,
        state = FileState.FILED,
        named = true,
        drawer = "Receipts",
        tags = listOf("tax", "2026-08", "Costco"),
        people = listOf("3f2a-91bc"),
        fields = linkedMapOf("total" to FileField("84.12", FieldBy.READ), "merchant" to FileField("Costco", FieldBy.YOU)),
        text = "COSTCO WHOLESALE\nTotal 84.12\n« Reçu » \"quoted\" \\ tab\t end \u2028",
        thumb = true,
        from = Provenance(FileSource.SHARE, at = 1_760_270_000_000, size = 48_213, sha256 = sha, app = "ch.protonmail.android",
            type = "application/pdf", opened = true, filedBy = FiledBy.ROBOT, filedAt = 1_760_270_060_000),
    )

    private fun minimal(n: Int = 2) = FileEntry(id(n), "", FileKind.UNKNOWN, FileState.INBOX, Provenance(FileSource.SCAN, at = 5, size = 0))

    private fun parse(json: String): IndexParse = FileIndexCodec.decode(json.toByteArray())
    private fun ok(json: String) = parse(json) as IndexParse.Ok
    private fun text(i: FileIndex) = String(FileIndexCodec.encode(i))

    /** An index with [entry] (raw JSON) between two good entries. */
    private fun around(entry: String): String = "{\"v\":1,\"entries\":[${FileIndexCodec.entry(minimal(7))},$entry,${FileIndexCodec.entry(minimal(8))}]}"

    @Test fun roundTripsInAFixedOrder() {
        val i = FileIndex(listOf(full(), minimal()))
        val bytes = FileIndexCodec.encode(i)
        val back = FileIndexCodec.decode(bytes) as IndexParse.Ok
        assertEquals(i, back.index)
        assertEquals(0, back.dropped)
        assertArrayEquals(bytes, FileIndexCodec.encode(back.index))
        assertEquals("{\"v\":1,\"entries\":[{\"id\":\"${id(2).hex}\",\"name\":\"\",\"kind\":\"UNKNOWN\",\"state\":\"INBOX\",\"from\":{\"source\":\"SCAN\",\"at\":5,\"size\":0}}]}",
            text(FileIndex(listOf(minimal()))))
        // Non-ASCII stays as UTF-8 (not escaped), quotes, backslashes and controls are escaped.
        val t = text(FileIndex(listOf(full())))
        assertTrue(t.contains("« Reçu » \\\"quoted\\\" \\\\ tab\\t end \\u2028"))
        assertEquals(FileIndex(), ok("{\"v\":1,\"entries\":[]}").index)
    }

    @Test fun versionsAndTheFrame() {
        assertEquals(IndexParse.TooNew(2), parse("{\"v\":2,\"entries\":[]}"))
        assertEquals(IndexParse.TooNew(99), parse("{\"v\":99,\"whatever\":true}"))
        for (bad in listOf("", "null", "[]", "{}", "{\"v\":0,\"entries\":[]}", "{\"v\":\"1\",\"entries\":[]}", "{\"v\":1.0,\"entries\":[]}",
            "{\"v\":1}", "{\"v\":1,\"entries\":{}}", "{'v':1,'entries':[]}", "{\"v\":1,\"entries\":[],}", "{\"v\":1,\"v\":1,\"entries\":[]}",
            "{\"v\":1,\"entries\":[]} x", "{\"v\":1,\"entries\":[]}\u0000",
            // A lone surrogate anywhere isn't text: StrictJson refuses the whole file.
            "{\"v\":1,\"entries\":[{\"name\":\"\\ud800\"}]}"))
            assertEquals(bad, IndexParse.Damaged, parse(bad))
        // Not UTF-8.
        assertEquals(IndexParse.Damaged, FileIndexCodec.decode(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte())))
    }

    @Test fun keysAndValuesThisRobotDoesntKnowAreKept() {
        val e = (FileIndexCodec.entry(full()).removeSuffix("}") + ",\"colour\":{\"r\":1,\"deep\":[1.5,null,\"x\"]},\"tagsV2\":[]}")
            .replace("\"by\":\"READ\"", "\"by\":\"READ\",\"at\":17")
        val withFrom = e.replace("\"filedAt\":1760270060000", "\"filedAt\":1760270060000,\"via\":\"lan\"")
        val json = "{\"v\":1,\"made\":123,\"entries\":[$withFrom],\"later\":{\"k\":[true]}}"
        val i = ok(json).index
        assertEquals(mapOf("made" to 123L, "later" to mapOf("k" to listOf(true))), i.extra)
        assertEquals(mapOf("colour" to mapOf("r" to 1L, "deep" to listOf(1.5, null, "x")), "tagsV2" to emptyList<Any>()), i.entries[0].extra)
        assertEquals(mapOf("via" to "lan"), i.entries[0].from.extra)
        assertEquals(mapOf("at" to 17L), i.entries[0].fields.getValue("total").extra)
        // Written back with all of it (the top's unknown keys after the entries), and read again the same.
        val again = ok(text(i)).index
        assertEquals(i, again)
        assertTrue(text(i).contains("\"via\":\"lan\"") && text(i).contains("\"later\":{\"k\":[true]}") && text(i).contains("\"deep\":[1.5,null,\"x\"]"))
    }

    @Test fun valuesThisRobotDoesntKnowAreReadAsUnknownAndWrittenBackAsTheyWere() {
        val e = FileIndexCodec.entry(full())
            .replace("\"kind\":\"RECEIPT\"", "\"kind\":\"WARRANTY\"")
            .replace("\"state\":\"FILED\"", "\"state\":\"ARCHIVED\"")
            .replace("\"source\":\"SHARE\"", "\"source\":\"WATCHED_FOLDER\"")
            .replace("\"filedBy\":\"ROBOT\"", "\"filedBy\":\"HELPER\"")
            .replace("\"by\":\"YOU\"", "\"by\":\"OCR\"")
        val i = ok("{\"v\":1,\"entries\":[$e]}").index
        val x = i.entries[0]
        assertEquals(FileKind.UNKNOWN, x.kind)
        assertEquals(FileState.UNKNOWN, x.state)
        assertEquals(FileSource.UNKNOWN, x.from.source)
        assertEquals(FiledBy.UNKNOWN, x.from.filedBy)
        assertEquals(FieldBy.UNKNOWN, x.fields.getValue("merchant").by)
        assertEquals(e, FileIndexCodec.entry(x))
        // Once Robot sets one of them itself, its own value is written.
        val filed = x.copy(kind = FileKind.RECEIPT)
        assertTrue(FileIndexCodec.entry(filed).contains("\"kind\":\"RECEIPT\""))
        assertTrue(FileIndexCodec.entry(filed).contains("\"source\":\"WATCHED_FOLDER\""))
    }

    /** 1.5's Robot to Robot fields (§2.9): the source ROBOT, a field by PEER, an entry's peer, mode and why. Read as absent, kept. */
    @Test fun theReservedFieldsOf15AreReadAsAbsentAndKept() {
        val e = FileIndexCodec.entry(full()).removeSuffix("}")
            .replace("\"source\":\"SHARE\"", "\"source\":\"ROBOT\"")
            .replace("\"by\":\"YOU\"", "\"by\":\"PEER\"") + ",\"peer\":{\"robot\":\"r-123\",\"name\":\"Sam\"},\"mode\":\"PRIVATE\",\"why\":\"SPLIT\"}"
        val x = ok("{\"v\":1,\"entries\":[$e]}").index.entries[0]
        assertEquals(FileSource.ROBOT, x.from.source)
        assertEquals(FieldBy.PEER, x.fields.getValue("merchant").by)
        assertEquals(setOf("peer", "mode", "why"), x.extra.keys)
        assertEquals(e, FileIndexCodec.entry(x))
    }

    @Test fun anEntryThatBreaksARuleIsDroppedAndCounted() {
        val good = FileIndexCodec.entry(full(3))
        val bad = listOf(
            "1", "\"x\"", "[]", "{}",
            good.replace("\"id\":\"${id(3).hex}\"", "\"id\":\"${id(0xabc).hex.uppercase()}\""),
            good.replace("\"id\":\"${id(3).hex}\"", "\"id\":\"../../etc\""),
            good.replace("\"name\":\"2026-08-14 Costco 84.12 CAD\",", ""),
            good.replace("\"name\":\"2026-08-14 Costco 84.12 CAD\"", "\"name\":\"a\\nb\""),
            good.replace("\"name\":\"2026-08-14 Costco 84.12 CAD\"", "\"name\":\"${"n".repeat(201)}\""),
            good.replace("\"kind\":\"RECEIPT\"", "\"kind\":7"),
            good.replace("\"kind\":\"RECEIPT\"", "\"kind\":\"\""),
            good.replace("\"named\":true", "\"named\":\"yes\""),
            good.replace("\"drawer\":\"Receipts\"", "\"drawer\":\"\""),
            good.replace("\"tags\":[\"tax\",", "\"tags\":[1,"),
            good.replace("\"tags\":[\"tax\",", "\"tags\":[\"tax\",\"tax\","),
            good.replace("\"people\":[\"3f2a-91bc\"]", "\"people\":[\"a b\"]"),
            good.replace("\"by\":\"READ\"", "\"by\":3"),
            good.replace("\"total\":{\"v\":\"84.12\"", "\"Total\":{\"v\":\"84.12\""),
            good.replace("\"thumb\":true", "\"thumb\":1"),
            good.replace(",\"from\":{", ",\"form\":{"),
            good.replace("\"at\":1760270000000", "\"at\":\"now\""),
            good.replace("\"at\":1760270000000", "\"at\":-1"),
            good.replace("\"size\":48213", "\"size\":-1"),
            good.replace("\"size\":48213", "\"size\":48213.5"),
            good.replace("\"sha256\":\"$sha\"", "\"sha256\":\"${sha.uppercase()}\""),
            good.replace("\"app\":\"ch.protonmail.android\"", "\"app\":\"not a package\""),
            good.replace("\"type\":\"application/pdf\"", "\"type\":\"PDF\""),
            good.replace("\"filedAt\":1760270060000", "\"filedAt\":null"),
        )
        for (b in bad) {
            val r = parse(around(b))
            assertTrue(b, r is IndexParse.Ok)
            r as IndexParse.Ok
            assertEquals(b, listOf(id(7), id(8)), r.index.entries.map { it.id })
            assertEquals(b, 1, r.dropped)
        }
        // The same id twice: the second goes.
        val twice = ok("{\"v\":1,\"entries\":[$good,${FileIndexCodec.entry(full(3).copy(name = "other"))}]}")
        assertEquals(1, twice.dropped)
        assertEquals("2026-08-14 Costco 84.12 CAD", twice.index.entries.single().name)
    }

    @Test fun theCaps() {
        // Text: 4,000 characters at most, and the entry within 6 KiB encoded.
        val ascii = minimal().copy(text = "x".repeat(FileLimits.MAX_TEXT))
        assertTrue(FileIndexCodec.fits(ascii))
        assertTrue(runCatching { minimal().copy(text = "x".repeat(FileLimits.MAX_TEXT + 1)) }.isFailure)
        val cjk = minimal().copy(text = "収".repeat(FileLimits.MAX_TEXT))
        assertTrue(!FileIndexCodec.fits(cjk))
        assertTrue(runCatching { FileIndexCodec.encode(FileIndex(listOf(cjk))) }.exceptionOrNull() is IllegalArgumentException)
        // Read from a file: an entry over 6 KiB (2,500 characters of 3 bytes) is dropped, not cut.
        assertEquals(1, ok(around(FileIndexCodec.entry(minimal(3)).replace("\"from\"", "\"text\":\"${"\u53ce".repeat(2_500)}\",\"from\""))).dropped)
        // fitText cuts it until it fits, never inside a surrogate pair.
        val fitted = FileIndexCodec.fitText(cjk)!!
        assertTrue(FileIndexCodec.fits(fitted) && fitted.text.length < FileLimits.MAX_TEXT && fitted.text.all { it == '収' })
        val emoji = minimal().copy(text = "\uD83E\uDDFE".repeat(1999) + "x")
        val e2 = FileIndexCodec.fitText(emoji)!!
        assertTrue(FileIndexCodec.wellFormed(e2.text) && FileIndexCodec.fits(e2))
        // Fields: 2 KiB encoded.
        val many = (1..24).associate { "f$it" to FileField("v".repeat(100), FieldBy.READ) }
        assertTrue(!FileIndexCodec.fits(minimal().copy(fields = many)))
        assertTrue(FileIndexCodec.fits(minimal().copy(fields = many.entries.take(10).associate { it.key to it.value })))
        assertTrue(runCatching { minimal().copy(fields = (1..25).associate { "f$it" to FileField("v", FieldBy.READ) }) }.isFailure)
        // Names, tags, people.
        assertTrue(runCatching { minimal().copy(name = "n".repeat(201)) }.isFailure)
        assertTrue(runCatching { minimal().copy(tags = (1..33).map { "t$it" }) }.isFailure)
        assertTrue(runCatching { minimal().copy(tags = listOf("t".repeat(41))) }.isFailure)
        assertTrue(runCatching { minimal().copy(people = listOf("a/b")) }.isFailure)
        assertTrue(runCatching { Provenance(FileSource.SHARE, 0, -1) }.isFailure)
        assertTrue(runCatching { Provenance(FileSource.SHARE, 0, 1, sha256 = "z".repeat(64)) }.isFailure)
        assertNull(FileIndexCodec.fitText(minimal().copy(fields = many)))
    }

    /** §2.5's arithmetic: 5,000 entries, each as large as an entry may be, fit within the index's 32 MiB, and read back. */
    @Test fun fiveThousandOfTheLargestEntriesFit() {
        val big = FileIndexCodec.fitText(full().copy(text = "収".repeat(FileLimits.MAX_TEXT)))!!
        // Close to the cap: within a character of it.
        val size = FileIndexCodec.entry(big).toByteArray().size
        assertTrue("$size", size in FileLimits.MAX_ENTRY - 3..FileLimits.MAX_ENTRY)
        val entries = (1..FileLimits.MAX_FILES).map { big.copy(id = id(it)) }
        val bytes = FileIndexCodec.encode(FileIndex(entries))
        assertTrue("${bytes.size}", bytes.size <= FileLimits.MAX_INDEX)
        val back = FileIndexCodec.decode(bytes) as IndexParse.Ok
        assertEquals(FileLimits.MAX_FILES, back.index.entries.size)
        assertEquals(0, back.dropped)
        // Over the index's cap (a newer Robot's larger count), writing is refused rather than cut.
        val over = (1..5_500).map { big.copy(id = id(it)) }
        assertEquals(FileProblem.TOO_BIG, (runCatching { FileIndexCodec.encode(FileIndex(over)) }.exceptionOrNull() as FileException).problem)
        assertEquals(IndexParse.Damaged, FileIndexCodec.decode(ByteArray(FileLimits.MAX_INDEX.toInt() + 1) { ' '.code.toByte() }))
    }

    @Test fun anEntrysToStringHoldsNothingOfIt() {
        val s = full().toString() + FileIndex(listOf(full())).toString()
        assertTrue(s, !s.contains("Costco") && !s.contains("84.12") && !s.contains("protonmail"))
    }
}
