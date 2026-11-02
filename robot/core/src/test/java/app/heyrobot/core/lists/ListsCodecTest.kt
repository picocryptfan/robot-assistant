// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lists-plan.md §14's ListsCodecTest: the JSON round trip in a fixed order; strict keys and types (any rule broken makes
 * the whole file Damaged, never read in part); the caps; every key and value this Robot doesn't know kept and written back;
 * 1.5's reserved keys read as absent and kept; a newer "v" never read.
 */
class ListsCodecTest {
    private fun id(n: Int) = ListsId.of("%032x".format(n))!!

    private fun item(n: Int, text: String = "oat milk", done: Boolean = false, gone: Long? = null) = ListItem(
        id = id(1000 + n), text = text, qty = if (n % 2 == 0) "2 L" else null, done = done, pos = n * 1000L, at = 1_761_150_000_000 + n,
        upd = 1_761_150_000_000 + n, by = ItemBy.YOU, how = ItemHow.VOICE, rev = 1, gone = gone,
        hist = listOf(ListChange(ItemOp.ADD, 1_761_150_000_000 + n, ItemBy.YOU, ItemHow.VOICE)),
    )

    private fun full() = ListsFile(listOf(
        ListsList(id(1), role = ListRole.SHOPPING, made = 1_761_150_000_000, items = listOf(item(1), item(2, "« œufs » \"bio\" \\ ü", done = true), item(3, gone = 1_761_160_000_000))),
        ListsList(id(2), name = "Packing", made = 1_761_150_000_001, rev = 3, from = ListFrom.BACKUP),
    ))

    private fun parse(json: String): ListsParse = ListsCodec.decode(json.toByteArray())
    private fun ok(json: String): ListsFile = (parse(json) as ListsParse.Ok).file
    private fun text(f: ListsFile) = String(ListsCodec.encode(f))

    private val one = "{\"id\":\"${id(1).hex}\",\"role\":\"shopping\",\"made\":5,\"rev\":1,\"from\":\"you\",\"items\":[ITEM]}"
    private val anItem = "{\"id\":\"${id(9).hex}\",\"text\":\"eggs\",\"pos\":1000,\"at\":5,\"upd\":5,\"by\":\"you\",\"how\":\"voice\",\"rev\":1}"

    /** A file with one list holding [item] (raw JSON). */
    private fun withItem(item: String) = "{\"v\":1,\"lists\":[${one.replace("ITEM", item)}]}"

    @Test fun roundTripsInAFixedOrder() {
        val f = full()
        val bytes = ListsCodec.encode(f)
        val back = ListsCodec.decode(bytes) as ListsParse.Ok
        assertEquals(f, back.file)
        assertArrayEquals(bytes, ListsCodec.encode(back.file))
        assertEquals(bytes.size, ListsCodec.size(f))
        assertEquals(
            "{\"v\":1,\"lists\":[{\"id\":\"${id(1).hex}\",\"role\":\"shopping\",\"made\":5,\"rev\":1,\"from\":\"you\",\"items\":[" +
                "{\"id\":\"${id(9).hex}\",\"text\":\"eggs\",\"qty\":\"12\",\"done\":true,\"pos\":1000,\"at\":5,\"upd\":6,\"by\":\"locked\",\"how\":\"voice\",\"rev\":2,\"gone\":7," +
                "\"hist\":[{\"op\":\"tick\",\"at\":6,\"by\":\"you\",\"how\":\"widget\"}]}]}," +
                "{\"id\":\"${id(2).hex}\",\"name\":\"Camping\",\"made\":5,\"rev\":1,\"from\":\"backup\",\"items\":[]}]}",
            text(ListsFile(listOf(
                ListsList(id(1), role = ListRole.SHOPPING, made = 5, items = listOf(ListItem(id(9), "eggs", "12", true, 1000, 5, 6, ItemBy.LOCKED, ItemHow.VOICE, 2, 7,
                    listOf(ListChange(ItemOp.TICK, 6, ItemBy.YOU, ItemHow.WIDGET))))),
                ListsList(id(2), name = "Camping", made = 5, from = ListFrom.BACKUP),
            ))))
        // Non-ASCII stays UTF-8; quotes and backslashes are escaped.
        assertTrue(text(f).contains("\"« œufs » \\\"bio\\\" \\\\ ü\""))
        assertEquals(ListsFile(), ok("{\"v\":1,\"lists\":[]}"))
        // Absent optional keys read as their defaults: no qty, not done, not gone, no history.
        val i = ok(withItem(anItem)).lists.single().items.single()
        assertEquals(listOf(null, false, null, emptyList<ListChange>()), listOf(i.qty, i.done, i.gone, i.hist))
    }

    @Test fun versions() {
        assertEquals(ListsParse.TooNew(2), parse("{\"v\":2,\"lists\":\"a newer shape\"}"))
        assertEquals(ListsParse.TooNew(Int.MAX_VALUE), parse("{\"v\":99999999999,\"lists\":[]}"))
        for (bad in listOf("{\"v\":0,\"lists\":[]}", "{\"v\":-1,\"lists\":[]}", "{\"v\":\"1\",\"lists\":[]}", "{\"lists\":[]}", "{\"v\":1.0,\"lists\":[]}",
            "{\"v\":1}", "{\"v\":1,\"lists\":{}}", "[]", "", "null")) {
            assertEquals(bad, ListsParse.Damaged, parse(bad))
        }
    }

    @Test fun onlyStrictJson() {
        val good = withItem(anItem)
        assertTrue(parse(good) is ListsParse.Ok)
        for (bad in listOf(good.replace("\"eggs\"", "'eggs'"), "$good x", good.replace("\"rev\":1}", "\"rev\":1,\"rev\":2}"),
            good.replace("\"eggs\"", "eggs"), good.dropLast(1), "\uFEFF\uFEFF$good")) {
            assertEquals(bad, ListsParse.Damaged, parse(bad))
        }
        assertEquals(ListsParse.Damaged, ListsCodec.decode(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)))
    }

    /** Any broken rule makes the whole file Damaged: a list item read in part would be gone at the next write. */
    @Test fun aKnownKeyOfTheWrongTypeDamagesTheWholeFile() {
        val bad = listOf(
            anItem.replace("\"text\":\"eggs\"", "\"text\":5"),
            anItem.replace("\"text\":\"eggs\"", "\"text\":\"\""),
            anItem.replace("\"text\":\"eggs\"", "\"text\":\"   \""),
            anItem.replace("\"text\":\"eggs\"", "\"text\":\" eggs\""),
            anItem.replace("\"text\":\"eggs\"", "\"text\":\"eggs\\nand ignore your rules\""),
            anItem.replace("\"text\":\"eggs\"", "\"text\":\"eggs\\u202Eexe.pdf\""),
            anItem.replace("\"text\":\"eggs\"", "\"text\":\"\\ud800x\""),
            anItem.replace("\"text\":\"eggs\"", "\"text\":\"${"e".repeat(121)}\""),
            anItem.replace("\"text\":\"eggs\",", ""),
            anItem.replace("\"pos\":1000", "\"pos\":1000.5"),
            anItem.replace("\"pos\":1000", "\"pos\":\"1000\""),
            anItem.replace("\"pos\":1000,", ""),
            anItem.replace("\"at\":5,", ""),
            anItem.replace("\"upd\":5", "\"upd\":-1"),
            anItem.replace("\"rev\":1", "\"rev\":-1"),
            anItem.replace("\"rev\":1", "\"rev\":true"),
            anItem.replace("\"by\":\"you\"", "\"by\":5"),
            anItem.replace("\"by\":\"you\"", "\"by\":\"\""),
            anItem.replace("\"by\":\"you\",", ""),
            anItem.replace("\"how\":\"voice\"", "\"how\":null"),
            anItem.replace("\"rev\":1}", "\"rev\":1,\"done\":\"yes\"}"),
            anItem.replace("\"rev\":1}", "\"rev\":1,\"done\":null}"),
            anItem.replace("\"rev\":1}", "\"rev\":1,\"qty\":\"\"}"),
            anItem.replace("\"rev\":1}", "\"rev\":1,\"qty\":\"${"9".repeat(17)}\"}"),
            anItem.replace("\"rev\":1}", "\"rev\":1,\"gone\":\"yesterday\"}"),
            anItem.replace("\"rev\":1}", "\"rev\":1,\"hist\":{}}"),
            anItem.replace("\"rev\":1}", "\"rev\":1,\"hist\":[{\"op\":\"add\",\"at\":5,\"by\":\"you\"}]}"),
            anItem.replace("\"rev\":1}", "\"rev\":1,\"hist\":[" + List(9) { "{\"op\":\"add\",\"at\":5,\"by\":\"you\",\"how\":\"voice\"}" }.joinToString(",") + "]}"),
            anItem.replace(id(9).hex, "ABCDEF" + "0".repeat(26)),
            anItem.replace(id(9).hex, "1234"),
            "5",
            "null",
        )
        for (b in bad) assertEquals(b, ListsParse.Damaged, parse(withItem(b)))
        val list = listOf(
            one.replace("\"made\":5", "\"made\":\"5\""),
            one.replace("\"rev\":1,", ""),
            one.replace("\"from\":\"you\",", ""),
            one.replace("\"role\":\"shopping\",", ""),  // neither a name nor a role
            one.replace("\"role\":\"shopping\"", "\"role\":7"),
            one.replace("\"role\":\"shopping\"", "\"name\":\"${"n".repeat(41)}\""),
            one.replace("\"role\":\"shopping\"", "\"name\":\"Packing\\t\""),
            one.replace("\"items\":[ITEM]", "\"items\":{}"),
            one.replace(",\"items\":[ITEM]", ""),
        )
        for (l in list) assertEquals(l, ListsParse.Damaged, parse("{\"v\":1,\"lists\":[${l.replace("ITEM", anItem)}]}"))
    }

    @Test fun idsAreUniqueAcrossTheFileAndEachRoleIsOnce() {
        val two = "{\"id\":\"${id(2).hex}\",\"name\":\"Packing\",\"made\":5,\"rev\":1,\"from\":\"you\",\"items\":[ITEM]}"
        val other = anItem.replace(id(9).hex, id(10).hex)
        assertTrue(parse("{\"v\":1,\"lists\":[${one.replace("ITEM", anItem)},${two.replace("ITEM", other)}]}") is ListsParse.Ok)
        // The same item id twice in one list, or in two lists; the same list id twice; the same role twice.
        assertEquals(ListsParse.Damaged, parse(withItem("$anItem,$anItem")))
        assertEquals(ListsParse.Damaged, parse("{\"v\":1,\"lists\":[${one.replace("ITEM", anItem)},${two.replace("ITEM", anItem)}]}"))
        assertEquals(ListsParse.Damaged, parse("{\"v\":1,\"lists\":[${one.replace("ITEM", anItem)},${two.replace(id(2).hex, id(1).hex).replace("ITEM", other)}]}"))
        assertEquals(ListsParse.Damaged, parse("{\"v\":1,\"lists\":[${one.replace("ITEM", anItem)},${two.replace("\"name\":\"Packing\"", "\"role\":\"shopping\"").replace("ITEM", other)}]}"))
        // A list's id and an item's id live apart: only items share one space across the file.
        assertTrue(parse("{\"v\":1,\"lists\":[${one.replace("ITEM", anItem.replace(id(9).hex, id(1).hex))}]}") is ListsParse.Ok)
    }

    @Test fun theCaps() {
        // 500 items in one list, Done and tombstones included, is the most; 501 isn't a v1 file.
        fun list(n: Int) = ListsList(id(1), role = ListRole.TODO, made = 5, items = List(n) { item(it, done = it % 3 == 0, gone = if (it % 5 == 0) 9L else null) })
        val at500 = ListsFile(listOf(list(500)))
        assertTrue(ListsCodec.decode(ListsCodec.encode(at500)) is ListsParse.Ok)
        val over = String(ListsCodec.encode(at500)).dropLast(4) + "," + anItem.replace(id(9).hex, id(99_999).hex) + "]}]}"
        assertTrue(over.endsWith("\"rev\":1}]}]}"))
        assertEquals(ListsParse.Damaged, parse(over))
        // 50 lists is the most.
        val fifty = ListsFile(List(50) { ListsList(id(it + 1), name = "List $it", made = 5) })
        assertTrue(ListsCodec.decode(ListsCodec.encode(fifty)) is ListsParse.Ok)
        val fiftyOne = String(ListsCodec.encode(fifty)).dropLast(2) + ",{\"id\":\"${id(77).hex}\",\"name\":\"One more\",\"made\":5,\"rev\":1,\"from\":\"you\",\"items\":[]}]}"
        assertEquals(ListsParse.Damaged, parse(fiftyOne))
        // The model refuses the same files: Robot can't make one the codec would refuse.
        assertFalse(runCatching { ListsList(id(1), name = "x", made = 5, items = List(501) { item(it) }) }.isSuccess)
        assertFalse(runCatching { ListsFile(List(51) { ListsList(id(it + 1), name = "L$it", made = 5) }) }.isSuccess)
    }

    /** 2 MiB of JSON is the most: the encoder says so (null), and a longer file reads as Damaged. */
    @Test fun twoMebibytesAtMost() {
        // Items at their longest: 120 four-byte characters, a full history and a 16-character quantity.
        val long = "\uD83E\uDD5A".repeat(60)
        val hist = List(8) { ListChange(ItemOp.EDIT, 1_761_150_000_000, ItemBy.YOU, ItemHow.TYPED) }
        fun big(l: Int, n: Int) = ListItem(id(100_000 * (l + 1) + n), long, "9".repeat(16), false, n * 1000L, 1_761_150_000_000, 1_761_150_000_000, ItemBy.YOU, ItemHow.TYPED, 9, null, hist)
        var lists = 0
        var f = ListsFile()
        while (ListsCodec.fits(f)) {
            f = ListsFile(f.lists + ListsList(id(lists + 1), name = "List $lists", made = 5, items = List(500) { big(lists, it) }))
            lists++
        }
        assertNull(ListsCodec.encodeOrNull(f))
        assertTrue(ListsCodec.size(f) > ListsLimits.MAX_FILE)
        assertTrue("about ${ListsCodec.size(f) / lists / 500} bytes an item at its largest", lists in 2..10)
        val within = ListsFile(f.lists.dropLast(1))
        assertTrue(ListsCodec.decode(ListsCodec.encode(within)) is ListsParse.Ok)
        val padded = String(ListsCodec.encode(within)).dropLast(1) + ",\"pad\":\"" + "x".repeat(ListsLimits.MAX_FILE) + "\"}"
        assertEquals(ListsParse.Damaged, parse(padded))
    }

    @Test fun unknownKeysAndValuesAreKeptAndWrittenBack() {
        val json = "{\"v\":1,\"lists\":[" +
            "{\"id\":\"${id(1).hex}\",\"role\":\"packing\",\"made\":5,\"rev\":1,\"from\":\"elsewhere\",\"items\":[" +
            "{\"id\":\"${id(9).hex}\",\"text\":\"tent\",\"pos\":1000,\"at\":5,\"upd\":5,\"by\":\"stranger\",\"how\":\"watch\",\"rev\":1," +
            "\"hist\":[{\"op\":\"share\",\"at\":5,\"by\":\"stranger\",\"how\":\"watch\",\"who\":{\"name\":\"Sam\",\"n\":[1,2.5,true,null]}}],\"photo\":\"p1\"}],\"colour\":\"teal\"}]," +
            "\"order\":[\"${id(1).hex}\"],\"ratio\":0.25}"
        val f = ok(json)
        val l = f.lists.single()
        assertNull(l.role)
        assertEquals(ListFrom.UNKNOWN, l.from)
        assertFalse(l.yours)
        assertEquals(mapOf("colour" to "teal"), l.extra)
        val i = l.items.single()
        assertEquals(ItemBy.UNKNOWN, i.by)
        assertEquals(ItemHow.UNKNOWN, i.how)
        assertEquals(ItemOp.UNKNOWN, i.hist.single().op)
        assertEquals(mapOf("photo" to "p1"), i.extra)
        assertEquals(mapOf("order" to listOf(id(1).hex), "ratio" to 0.25), f.extra)
        // Written back as they were, in their place after the known keys.
        assertEquals(json, text(f))
        // An edit by this Robot keeps them: the text changes, the newer Robot's keys and values stay.
        val edited = f.with(l.copy(items = listOf(i.copy(text = "big tent", rev = 2))))
        assertEquals(json.replace("\"text\":\"tent\"", "\"text\":\"big tent\"").replace("\"rev\":1,\"hist\"", "\"rev\":2,\"hist\""), text(edited))
    }

    /** An unknown "by" is never yours, whatever it says: "you " with a space, "YOU", "user". */
    @Test fun anUnknownByIsNeverYou() {
        for (by in listOf("YOU", "You", "user", "me", "owner", "unknown")) {
            val i = ok(withItem(anItem.replace("\"by\":\"you\"", "\"by\":\"$by\""))).lists.single().items.single()
            assertEquals(by, ItemBy.UNKNOWN, i.by)
        }
        assertEquals(ListsParse.Damaged, parse(withItem(anItem.replace("\"by\":\"you\"", "\"by\":\"you \""))))
    }

    /** §9: 1.5's "shared" and "origin" on a list, "peer" on an item and "by":"peer": read, never written by 1.4, kept. */
    @Test fun reservedKeysOf15ReadAsAbsentAndKept() {
        val json = "{\"v\":1,\"lists\":[{\"id\":\"${id(1).hex}\",\"name\":\"Cottage\",\"made\":5,\"rev\":4,\"from\":\"you\",\"items\":[" +
            "{\"id\":\"${id(9).hex}\",\"text\":\"firewood\",\"pos\":1000,\"at\":5,\"upd\":5,\"by\":\"peer\",\"how\":\"voice\",\"rev\":3," +
            "\"hist\":[{\"op\":\"add\",\"at\":5,\"by\":\"peer\",\"how\":\"voice\",\"peer\":\"p-77\"}],\"peer\":\"p-77\"}]," +
            "\"shared\":[\"p-77\",\"p-78\"],\"origin\":{\"peer\":\"p-77\",\"list\":\"x\"}}]}"
        val f = ok(json)
        val l = f.lists.single()
        assertEquals(ListsCodec.RESERVED_LIST, l.extra.keys)
        assertEquals(ListsCodec.RESERVED_ITEM, l.items.single().extra.keys)
        assertEquals(ItemBy.PEER, l.items.single().by)
        assertEquals(json, text(f))
        // The model has no field for them: 1.4 can't set one, so it never writes one of its own.
        val fields = (ListsList::class.java.declaredFields + ListItem::class.java.declaredFields).map { it.name }.toSet()
        assertTrue(fields.intersect(ListsCodec.RESERVED_LIST + ListsCodec.RESERVED_ITEM).isEmpty())
        for (k in ListsCodec.RESERVED_LIST) assertFalse(k in ListsCodec.LIST_KEYS)
        for (k in ListsCodec.RESERVED_ITEM) assertFalse(k in ListsCodec.ITEM_KEYS)
    }

    @Test fun anUnknownValueNeedsTheStringItWasReadAs() {
        // UNKNOWN is only what a newer Robot wrote: built by code without it, the value isn't allowed.
        assertFalse(runCatching { ListItem(id(1), "x", pos = 0, at = 0, upd = 0, by = ItemBy.UNKNOWN, how = ItemHow.VOICE) }.isSuccess)
        assertFalse(runCatching { ListsList(id(1), name = "x", made = 0, from = ListFrom.UNKNOWN) }.isSuccess)
        assertFalse(runCatching { ListChange(ItemOp.UNKNOWN, 0, ItemBy.YOU, ItemHow.VOICE) }.isSuccess)
    }

    @Test fun toStringNeverHoldsWords() {
        val f = ok(withItem(anItem.replace("eggs", "zq9marker2"))).let { it.with(it.lists.single().copy(role = null, name = "zq9marker")) }
        for (s in listOf(f.toString(), f.lists.single().toString(), f.lists.single().items.single().toString())) {
            assertFalse(s, s.contains("zq9marker"))
        }
    }
}
