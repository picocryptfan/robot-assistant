// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import java.math.BigDecimal

// Pure. The JSON Schema subset an MCP server's tools use (1.3 plan S3.5, design §8.5): every call's arguments are checked
// against the tool's pinned inputSchema before anything is sent, and a result's structuredContent against its pinned
// outputSchema. Values are as StrictJson reads them: a Map, a List, a String, a Long or a Double, a Boolean, or null.
//
// Checked: type (one or a list), enum, const, properties, required, additionalProperties, items (one schema), minItems,
// maxItems, uniqueItems, minProperties, maxProperties, minLength and maxLength (in code points, as JSON Schema counts),
// minimum, maximum, exclusiveMinimum and exclusiveMaximum (numbers, the 2020-12 and draft-07 form), multipleOf, anyOf,
// oneOf, allOf, true and false, and $ref to "#/$defs/<name>" or "#/definitions/<name>" (the shape zod and Pydantic write).
// Notes, never checked: title, description, default, examples, format, $schema, $comment, deprecated, readOnly,
// writeOnly, contentMediaType, contentEncoding, $id at the top, every x-… key (x-mcp-header is McpPolicy's), and pattern:
// a server's regular expression could hold Robot's thread for ever, and the server checks it anyway. Any other keyword
// (not, if, prefixItems, patternProperties, dependentSchemas, a $ref anywhere else, …) makes a schema one Robot can't
// check ([unsupported]), and a tool with one is never turned on: fail closed.
//
// Robot's own rule on what it sends ([check] with strict, the default): where a schema lists its properties and says
// nothing of others, a property it doesn't name is refused (JSON Schema would allow it), so a filled value can never
// carry a key the tool's row didn't show. Inside anyOf, oneOf and allOf the schema's own rule applies. A server's
// structuredContent is read as JSON Schema reads it (strict = false).

object SchemaCheck {
    /** How deep a schema may nest, and how many schemas one check may visit (a hostile anyOf tree, a $ref loop). */
    const val MAX_DEPTH = 16
    const val MAX_STEPS = 20_000

    /** Why Robot can't check values against a schema. [code]: what the voice log may keep. */
    enum class Unsupported(val code: Int) { NOT_SCHEMA(1), KEYWORD(2), REF(3), TYPE(4), DEEP(5) }

    /** What's wrong with one value. */
    enum class Problem(val code: Int) {
        TYPE(1), MISSING(2), UNKNOWN(3), NOT_ALLOWED(4), SMALL(5), LARGE(6), SHORT(7), LONG(8), FEW(9), MANY(10), REPEATED(11),
        NO_MATCH(12), NOT_MULTIPLE(13), TOO_COMPLEX(14), UNCHECKABLE(15)
    }

    /** One wrong value: [path] its JSON pointer in what was checked ("" the whole of it). */
    data class Wrong(val path: String, val problem: Problem)

    private val TYPES = setOf("string", "number", "integer", "boolean", "object", "array", "null") // l10n: not text (JSON Schema's types)
    private val COUNTS = setOf("minItems", "maxItems", "minProperties", "maxProperties", "minLength", "maxLength") // l10n: not text (keywords)
    private val BOUNDS = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum") // l10n: not text (keywords)
    private val COMBINED = setOf("anyOf", "oneOf", "allOf") // l10n: not text (keywords)
    private val NOTES = setOf("title", "description", "default", "examples", "format", "pattern", "\$schema", "\$comment", "deprecated",
        "readOnly", "writeOnly", "contentMediaType", "contentEncoding") // l10n: not text (keywords)

    private class No(val why: Unsupported) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class TooMuch : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Why Robot can't check values against [schema] (a whole inputSchema or outputSchema), or null when it can. */
    fun unsupported(schema: Any?): Unsupported? = try {
        walk(schema, schema, 0)
        null
    } catch (e: No) {
        e.why
    }

    private fun walk(root: Any?, s: Any?, depth: Int) {
        if (depth > MAX_DEPTH) throw No(Unsupported.DEEP)
        if (s is Boolean) return
        if (s !is Map<*, *>) throw No(Unsupported.NOT_SCHEMA)
        for ((k0, v) in s) {
            val k = k0 as? String ?: throw No(Unsupported.NOT_SCHEMA)
            when (k) {
                "type" -> types(v) ?: throw No(Unsupported.TYPE)
                "enum" -> if (v !is List<*> || v.isEmpty()) throw No(Unsupported.KEYWORD)
                "const" -> {}
                "properties", "\$defs", "definitions" -> (v as? Map<*, *> ?: throw No(Unsupported.KEYWORD)).values.forEach { walk(root, it, depth + 1) }
                "required" -> if (v !is List<*> || v.any { it !is String }) throw No(Unsupported.KEYWORD)
                "additionalProperties", "items" -> walk(root, v, depth + 1)
                "uniqueItems" -> if (v !is Boolean) throw No(Unsupported.KEYWORD)
                "multipleOf" -> if ((number(v) ?: 0.0) <= 0.0) throw No(Unsupported.KEYWORD)
                "\$ref" -> target(root, v) ?: throw No(Unsupported.REF)
                "\$id" -> if (s !== root) throw No(Unsupported.KEYWORD)
                in COUNTS -> if (count(v) == null) throw No(Unsupported.KEYWORD)
                in BOUNDS -> if (number(v) == null) throw No(Unsupported.KEYWORD)
                in COMBINED -> (v as? List<*>)?.takeIf { it.isNotEmpty() }?.forEach { walk(root, it, depth + 1) } ?: throw No(Unsupported.KEYWORD)
                in NOTES -> {}
                else -> if (!k.startsWith("x-")) throw No(Unsupported.KEYWORD) // l10n: not text (an extension's prefix)
            }
        }
    }

    /** "#/$defs/<name>" or "#/definitions/<name>" in [root] (RFC 6901's ~0 and ~1 read), or null. */
    private fun target(root: Any?, ref: Any?): Any? {
        if (ref !is String || root !is Map<*, *>) return null
        val parts = ref.split('/')
        if (parts.size != 3 || parts[0] != "#" || (parts[1] != "\$defs" && parts[1] != "definitions")) return null
        val name = parts[2].replace("~1", "/").replace("~0", "~")
        val t = (root[parts[1]] as? Map<*, *>)?.get(name)
        return if (t is Map<*, *> || t is Boolean) t else null
    }

    private fun types(v: Any?): Set<String>? = when (v) {
        is String -> if (v in TYPES) setOf(v) else null
        is List<*> -> if (v.isNotEmpty() && v.all { it is String && it in TYPES } && v.toSet().size == v.size) v.map { it as String }.toSet() else null
        else -> null
    }

    private fun count(v: Any?): Long? = when (v) {
        is Long -> v.takeIf { it >= 0 }
        is Double -> if (v >= 0 && v == Math.floor(v) && v < 1e15) v.toLong() else null
        else -> null
    }

    private fun number(v: Any?): Double? = when (v) {
        is Long -> v.toDouble()
        is Double -> v.takeIf { it.isFinite() }
        else -> null
    }

    /**
     * What's wrong with [value] for [schema]: nothing (empty) when it fits. A schema Robot can't check gives one
     * [Problem.UNCHECKABLE]; one that would take too long, one [Problem.TOO_COMPLEX]. [strict]: Robot's own rule above.
     */
    fun check(schema: Any?, value: Any?, strict: Boolean = true): List<Wrong> {
        if (unsupported(schema) != null) return listOf(Wrong("", Problem.UNCHECKABLE))
        val c = Checker(schema)
        return try {
            c.errors(schema, value, "", 0, strict)
        } catch (e: TooMuch) {
            listOf(Wrong("", Problem.TOO_COMPLEX))
        }
    }

    /** True when [value] has JSON Schema's [type]: an integer is a whole number, 1.0 too. */
    fun isType(type: String, value: Any?): Boolean = when (type) {
        "string" -> value is String
        "integer" -> value is Long || (value is Double && value.isFinite() && value == Math.floor(value))
        "number" -> value is Long || (value is Double && value.isFinite())
        "boolean" -> value is Boolean
        "null" -> value == null
        "object" -> value is Map<*, *>
        "array" -> value is List<*>
        else -> false
    }

    /** JSON's equality: numbers by value (1 is 1.0), objects by their keys, arrays in order. */
    fun same(a: Any?, b: Any?): Boolean = when {
        (a is Long || a is Double) && (b is Long || b is Double) -> big(a).compareTo(big(b)) == 0
        a is Map<*, *> && b is Map<*, *> -> a.size == b.size && a.all { (k, v) -> b.containsKey(k) && same(v, b[k]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        else -> a == b
    }

    private fun big(n: Any?): BigDecimal = if (n is Long) BigDecimal.valueOf(n) else BigDecimal((n as Double).toString())

    private fun pointer(path: String, key: String): String = path + "/" + key.replace("~", "~0").replace("/", "~1")

    private class Checker(val root: Any?) {
        var steps = 0

        fun errors(s: Any?, v: Any?, path: String, depth: Int, strict: Boolean): List<Wrong> {
            if (++steps > MAX_STEPS || depth > MAX_DEPTH * 4) throw TooMuch()
            if (s == true) return emptyList()
            if (s == false || s !is Map<*, *>) return listOf(Wrong(path, Problem.NOT_ALLOWED))
            val out = ArrayList<Wrong>()
            s["\$ref"]?.let { out += errors(target(root, it), v, path, depth + 1, strict) }
            s["type"]?.let { t -> if (types(t).orEmpty().none { isType(it, v) }) return out + Wrong(path, Problem.TYPE) }
            (s["enum"] as? List<*>)?.let { e -> if (e.none { same(it, v) }) out += Wrong(path, Problem.NOT_ALLOWED) }
            if (s.containsKey("const") && !same(s["const"], v)) out += Wrong(path, Problem.NOT_ALLOWED)
            when (v) {
                is String -> {
                    val n = v.codePointCount(0, v.length).toLong()
                    count(s["minLength"])?.let { if (n < it) out += Wrong(path, Problem.SHORT) }
                    count(s["maxLength"])?.let { if (n > it) out += Wrong(path, Problem.LONG) }
                }
                is Long, is Double -> numbers(s, v, path, out)
                is List<*> -> {
                    count(s["minItems"])?.let { if (v.size < it) out += Wrong(path, Problem.FEW) }
                    count(s["maxItems"])?.let { if (v.size > it) out += Wrong(path, Problem.MANY) }
                    if (s["uniqueItems"] == true && v.indices.any { i -> (0 until i).any { same(v[it], v[i]) } }) out += Wrong(path, Problem.REPEATED)
                    if (s.containsKey("items")) v.forEachIndexed { i, x -> out += errors(s["items"], x, "$path/$i", depth + 1, strict) }
                }
                is Map<*, *> -> fields(s, v, path, depth, strict, out)
            }
            (s["allOf"] as? List<*>)?.forEach { out += errors(it, v, path, depth + 1, false) }
            (s["anyOf"] as? List<*>)?.let { l -> if (l.none { errors(it, v, path, depth + 1, false).isEmpty() }) out += Wrong(path, Problem.NO_MATCH) }
            (s["oneOf"] as? List<*>)?.let { l -> if (l.count { errors(it, v, path, depth + 1, false).isEmpty() } != 1) out += Wrong(path, Problem.NO_MATCH) }
            return out
        }

        private fun numbers(s: Map<*, *>, v: Any, path: String, out: MutableList<Wrong>) {
            val d = (v as Number).toDouble()
            number(s["minimum"])?.let { if (d < it) out += Wrong(path, Problem.SMALL) }
            number(s["exclusiveMinimum"])?.let { if (d <= it) out += Wrong(path, Problem.SMALL) }
            number(s["maximum"])?.let { if (d > it) out += Wrong(path, Problem.LARGE) }
            number(s["exclusiveMaximum"])?.let { if (d >= it) out += Wrong(path, Problem.LARGE) }
            s["multipleOf"]?.let { m -> if (big(v).remainder(big(m)).signum() != 0) out += Wrong(path, Problem.NOT_MULTIPLE) }
        }

        private fun fields(s: Map<*, *>, v: Map<*, *>, path: String, depth: Int, strict: Boolean, out: MutableList<Wrong>) {
            val props = s["properties"] as? Map<*, *>
            (s["required"] as? List<*>)?.forEach { r -> if (!v.containsKey(r)) out += Wrong(pointer(path, r as String), Problem.MISSING) }
            for ((k, x) in v) {
                val key = k as? String ?: continue
                val p = pointer(path, key)
                when {
                    props != null && props.containsKey(key) -> out += errors(props[key], x, p, depth + 1, strict)
                    s.containsKey("additionalProperties") -> {
                        val more = s["additionalProperties"]
                        if (more == false) out += Wrong(p, Problem.UNKNOWN) else out += errors(more, x, p, depth + 1, strict)
                    }
                    strict && props != null -> out += Wrong(p, Problem.UNKNOWN)
                }
            }
            count(s["minProperties"])?.let { if (v.size < it) out += Wrong(path, Problem.FEW) }
            count(s["maxProperties"])?.let { if (v.size > it) out += Wrong(path, Problem.MANY) }
        }
    }
}
