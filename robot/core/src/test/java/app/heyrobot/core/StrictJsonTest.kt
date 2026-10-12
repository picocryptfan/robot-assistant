package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 8259 JSON only: what org.json would also read (and an export's maker could use to hide something) is refused. */
class StrictJsonTest {
    private fun ok(s: String): Any? = StrictJson.parse(s).let { assertNotNull(s, it); it!!.value }
    private fun bad(s: String) = assertNull(s, StrictJson.parse(s))

    @Test fun readsJson() {
        assertEquals(mapOf("v" to 1L, "items" to listOf(mapOf("text" to "Hé \"/\\\n\u00e9😀", "n" to -2.5, "ok" to true, "no" to null))),
            ok(" {\"v\": 1, \"items\": [ {\"text\": \"H\\u00e9 \\\"\\/\\\\\\n\u00e9\\ud83d\\ude00\", \"n\": -2.5e0, \"ok\": true, \"no\": null} ]}\r\n\t"))
        assertEquals(listOf(0L, -0L, 12L, 1.5, 1e3, 1.2E-2, Long.MAX_VALUE), ok("[0, -0, 12, 1.5, 1e3, 1.2E-2, 9223372036854775807]"))
        // Past a Long: a Double, never a wrong whole number.
        assertTrue(ok("[92233720368547758070]").let { (it as List<*>)[0] is Double })
        assertEquals(emptyMap<String, Any?>(), ok("{}"))
        assertEquals(emptyList<Any?>(), ok("[ ]"))
        assertEquals("x", ok("\"x\""))
        assertNull(ok("null"))
        // A UTF-8 byte order mark, as some editors save one.
        assertEquals(mapOf("v" to 1L), StrictJson.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{\"v\":1}".toByteArray())!!.value)
        // Keys keep the file's order.
        assertEquals(listOf("b", "a"), (ok("{\"b\":1,\"a\":2}") as Map<*, *>).keys.toList())
    }

    @Test fun refusesWhatOrgJsonWouldRead() {
        for (s in listOf(
            "", " ", "{'v': 1}", "{v: 1}", "{\"v\": 1,}", "[1, 2,]", "[1 2]", "{\"v\" 1}", "{\"v\": 1} x", "{\"v\": 1}{}",
            "{\"v\": 1 # comment\n}", "{\"v\": 1 // comment\n}", "/* c */ {\"v\": 1}", "{\"v\"= 1}", "{\"v\": 1; \"w\": 2}",
            "[01]", "[1.]", "[.5]", "[+1]", "[1e]", "[0x10]", "[NaN]", "[Infinity]", "[-]", "[True]", "[nul]", "[truex]",
            "[\"tab\there\"]", "[\"line\nbreak\"]", "[\"\\x41\"]", "[\"\\u12\"]", "[\"\\ud83d\"]", "[\"\\ude00x\"]", "[\"open]",
            "{\"v\": 1, \"v\": 2}", "[1]]", "{\"a\": [}", "\u00a0{}",
        )) bad(s)
        // Not UTF-8 (a Latin-1 é), or cut inside a character.
        assertNull(StrictJson.parse(byteArrayOf('['.code.toByte(), '"'.code.toByte(), 0xE9.toByte(), '"'.code.toByte(), ']'.code.toByte())))
        assertNull(StrictJson.parse(byteArrayOf('"'.code.toByte(), 0xC3.toByte())))
    }

    @Test fun nestsAtMost32Deep() {
        assertNotNull(StrictJson.parse("[".repeat(32) + "]".repeat(32)))
        bad("[".repeat(33) + "]".repeat(33))
        bad("{\"a\":".repeat(40) + "1" + "}".repeat(40))
        // A deep nest is refused, never a stack overflow.
        bad("[".repeat(100_000))
    }
}
