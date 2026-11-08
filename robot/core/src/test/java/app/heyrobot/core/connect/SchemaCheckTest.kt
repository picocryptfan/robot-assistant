// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaCheckTest {
    private val str = mapOf("type" to "string")
    private fun w(path: String, p: SchemaCheck.Problem) = listOf(SchemaCheck.Wrong(path, p))

    @Test fun whatRobotCanCheck() {
        val defs = mapOf("type" to "object", "properties" to mapOf("a" to mapOf("\$ref" to "#/\$defs/x")),
            "\$defs" to mapOf("x" to mapOf("type" to "string", "format" to "email", "x-note" to 1L)))
        assertNull(SchemaCheck.unsupported(defs))
        assertEquals(SchemaCheck.Unsupported.REF, SchemaCheck.unsupported(mapOf("\$ref" to "https://example.com/s.json")))
        assertEquals(SchemaCheck.Unsupported.KEYWORD, SchemaCheck.unsupported(mapOf("type" to "object", "patternProperties" to mapOf("^a" to str))))
        assertEquals(SchemaCheck.Unsupported.TYPE, SchemaCheck.unsupported(mapOf("type" to "date")))
        var deep: Map<String, Any?> = str
        repeat(20) { deep = mapOf("type" to "object", "properties" to mapOf("a" to deep)) }
        assertEquals(SchemaCheck.Unsupported.DEEP, SchemaCheck.unsupported(deep))
        assertEquals(w("/a", SchemaCheck.Problem.TYPE), SchemaCheck.check(defs, mapOf("a" to 1L)))
    }

    @Test fun valuesThatFitAndDont() {
        val schema = mapOf("type" to "object", "required" to listOf("n"), "properties" to mapOf(
            "n" to mapOf("type" to "integer", "minimum" to 1L, "maximum" to 5L),
            "tags" to mapOf("type" to "array", "items" to str, "maxItems" to 2L, "uniqueItems" to true),
            "kind" to mapOf("anyOf" to listOf(mapOf("const" to "a"), mapOf("type" to "integer")))))
        assertEquals(emptyList<SchemaCheck.Wrong>(), SchemaCheck.check(schema, mapOf("n" to 1.0, "tags" to listOf("x"), "kind" to "a")))
        assertEquals(w("/n", SchemaCheck.Problem.LARGE), SchemaCheck.check(schema, mapOf("n" to 6L)))
        assertEquals(w("/tags", SchemaCheck.Problem.REPEATED), SchemaCheck.check(schema, mapOf("n" to 2L, "tags" to listOf("x", "x"))))
        assertEquals(w("/tags", SchemaCheck.Problem.MANY), SchemaCheck.check(schema, mapOf("n" to 2L, "tags" to listOf("x", "y", "z"))))
        assertEquals(w("/tags/1", SchemaCheck.Problem.TYPE), SchemaCheck.check(schema, mapOf("n" to 2L, "tags" to listOf("x", 3L))))
        assertEquals(w("/kind", SchemaCheck.Problem.NO_MATCH), SchemaCheck.check(schema, mapOf("n" to 2L, "kind" to "b")))
        assertEquals(w("/x", SchemaCheck.Problem.UNKNOWN), SchemaCheck.check(schema, mapOf("n" to 2L, "x" to 1L)))
        assertEquals(emptyList<SchemaCheck.Wrong>(), SchemaCheck.check(schema, mapOf("n" to 2L, "x" to 1L), strict = false))
        assertEquals(SchemaCheck.Problem.UNCHECKABLE, SchemaCheck.check(mapOf("type" to "date"), "x").single().problem)
        assertTrue(SchemaCheck.same(1L, 1.0))
    }
}
