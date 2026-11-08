// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillLevel
import java.security.MessageDigest
import java.util.TreeMap

// Pure. What Robot decides about an MCP server's tools (design §8.5, 1.3 plan §4): every tool off until you turn it on,
// pinned when you do, turned off again when it changes.
//
// Pins: a SHA-256 of each part of a tool (its title, its description, its inputSchema, its outputSchema, its annotations
// with its execution) in a canonical form (keys sorted, as StrictJson read them), and the two schemas themselves, since a
// call's arguments are checked against the pinned inputSchema, never the one a later list gives. On every list
// ([changes]): a tool whose parts differ from its pin is turned off and says which parts changed (and, for its inputs,
// which properties); a new tool is listed off; a pinned tool that's gone marks its skills; a tool that can't be used any
// more is off. The server's own name (serverInfo, self-reported) is pinned too: another name at the same address turns
// every tool off; a new version alone changes nothing, since a tool that changed with it is caught by its own pin.
//
// Claims: readOnlyHint, destructiveHint and openWorldHint are the server's words. Effects follow them only towards
// caution: read-only only when you ticked "This tool only reads" and the server says so too; otherwise WRITES_ONLINE,
// with DELETES unless the server said destructiveHint false (the spec's default is true), and "reaches other services"
// unless it said openWorldHint false. Your marks add MONEY, SECURITY and MESSAGES_SOMEONE. Just do it needs the tick and
// no mark.
//
// The x-mcp-header rules (SEP-2243, as the spec reads): a non-empty RFC 9110 token, on a string, integer or boolean
// property reached from the top through `properties` only, unique ignoring case. Anything else and the tool can't be
// used. A value goes in `Mcp-Param-<name>` as it is when it's plain visible ASCII, otherwise as =?base64?…?= (UTF-8).
//
// Questions: a tool whose inputSchema has at most [MAX_QUESTIONS] properties, each a string, number, integer, boolean or
// a string enum, can have questions the model fills ([questions]); any other tool only fixed values in a skill's step.

/** Your marks on a tool: each adds an effect the server can't take away. */
enum class McpMark(val id: String, val effect: SkillEffect) {
    MONEY("money", SkillEffect.MONEY), SECURITY("security", SkillEffect.SECURITY), TELLS_PEOPLE("tells_people", SkillEffect.MESSAGES_SOMEONE);

    companion object {
        fun of(id: String?): McpMark? = entries.firstOrNull { it.id == id }
    }
}

/** A tool's parts as pinned, in the order a change is told. */
enum class McpPart(val id: String) { INPUTS("inputs"), OUTPUTS("outputs"), ANNOTATIONS("annotations"), DESCRIPTION("description"), TITLE("title") }

/** A tool as you turned it on: [parts] each part's SHA-256 (lowercase hex), [input] and [output] its schemas then. */
data class McpPin(val name: String, val parts: Map<McpPart, String>, val input: Map<String, Any?>, val output: Any?) {
    override fun toString(): String = "McpPin($name)" // l10n: not text (toString, for the logs)
}

/** The server's self-reported identity, pinned with its tools. */
data class McpServerId(val name: String, val version: String)

/** Your choices for one tool. [label]: your words, what a skill and a model see. [gone]: the server no longer lists it. */
data class McpToolSetting(val name: String, val level: SkillLevel = SkillLevel.OFF, val label: String = "", val onlyReads: Boolean = false,
    val marks: Set<McpMark> = emptySet(), val gone: Boolean = false)

/** What one list found for one tool. */
sealed interface McpChange {
    val name: String

    data class Same(override val name: String) : McpChange
    data class New(override val name: String) : McpChange
    /** [properties]: the inputSchema's properties added, removed or changed, by name, when [parts] holds INPUTS. */
    data class Changed(override val name: String, val parts: Set<McpPart>, val properties: List<String>) : McpChange
    data class Gone(override val name: String) : McpChange
    data class Unusable(override val name: String, val why: McpUnusable) : McpChange
}

/** A question a model may fill for one property. */
data class McpQuestion(val name: String, val type: ParamType, val required: Boolean, val options: List<String> = emptyList(),
    val min: Double? = null, val max: Double? = null)

/** The effects of calling a tool as set: [reachesOthers] unless the server said openWorldHint false. */
data class McpEffects(val effects: Set<SkillEffect>, val reachesOthers: Boolean)

/** Why a tool can't be turned on as asked. */
enum class McpTurnOn(val code: Int) { OK(0), UNUSABLE(1), LABEL(2), ONLY_READS(3), JUST_DO(4) }

object McpPolicy {
    const val MAX_QUESTIONS = 3
    const val MAX_LABEL = 40
    const val HEADER_PREFIX = "Mcp-Param-" // l10n: not text (an HTTP header)
    private const val B64_START = "=?base64?" // l10n: not text (the spec's marker)
    private const val B64_END = "?=" // l10n: not text (the spec's marker)
    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val HEADER_TYPES = setOf("string", "integer", "boolean") // l10n: not text (JSON Schema's types)
    private val PRIMITIVE = mapOf("string" to ParamType.TEXT, "integer" to ParamType.INTEGER, "number" to ParamType.NUMBER, "boolean" to ParamType.YES_NO) // l10n: not text (JSON Schema's types)
    /** Where an x-mcp-header may never be: under these, a property isn't reached through `properties` alone. */
    private val UNREACHED = listOf("items", "prefixItems", "contains", "additionalProperties", "unevaluatedProperties", "unevaluatedItems",
        "propertyNames", "dependentSchemas", "patternProperties", "\$defs", "definitions", "anyOf", "oneOf", "allOf", "not", "if", "then", "else") // l10n: not text (keywords)
    /** A question's schema may say these, and nothing else. */
    private val QUESTION_KEYS = setOf("type", "enum", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength",
        "title", "description", "default", "examples", "format", "x-mcp-header") // l10n: not text (keywords)

    // Pins.

    fun pin(t: McpTool): McpPin = McpPin(t.name, parts(t), t.input, t.output)

    private fun parts(t: McpTool): Map<McpPart, String> = mapOf(
        McpPart.INPUTS to sha(t.input), McpPart.OUTPUTS to sha(t.output),
        McpPart.ANNOTATIONS to sha(mapOf("annotations" to t.annotations, "execution" to t.execution)), // l10n: not text (keys)
        McpPart.DESCRIPTION to sha(t.description), McpPart.TITLE to sha(t.title))

    /** [v] in canonical form: object keys sorted (UTF-16 order), everything else as ConnJson writes it. */
    fun canonical(v: Any?): String = ConnJson.write(sorted(v))

    private fun sorted(v: Any?): Any? = when (v) {
        is Map<*, *> -> TreeMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k as String] = sorted(x) } }
        is List<*> -> v.map(::sorted)
        else -> v
    }

    fun sha(v: Any?): String = MessageDigest.getInstance("SHA-256").digest(canonical(v).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * What [listed] means for the tools you [pinned]: one change for every tool either names, pinned ones first in
     * their order. A pinned tool that's listed but can't be used is [McpChange.Unusable].
     */
    fun changes(pinned: List<McpPin>, listed: McpListed): List<McpChange> {
        val out = ArrayList<McpChange>()
        val bad = listed.unusable.mapNotNull { (n, why) -> n?.let { it to why } }.toMap()
        for (p in pinned) {
            val t = listed.tool(p.name)
            out += when {
                t != null -> {
                    val now = parts(t)
                    val differ = McpPart.entries.filter { now[it] != p.parts[it] }.toSet()
                    if (differ.isEmpty()) McpChange.Same(p.name)
                    else McpChange.Changed(p.name, differ, if (McpPart.INPUTS in differ) properties(p.input, t.input) else emptyList())
                }
                p.name in bad -> McpChange.Unusable(p.name, bad.getValue(p.name))
                else -> McpChange.Gone(p.name)
            }
        }
        val known = pinned.map { it.name }.toSet()
        for (t in listed.tools) if (t.name !in known) out += McpChange.New(t.name)
        for ((n, why) in bad) if (n !in known) out += McpChange.Unusable(n, why)
        return out
    }

    /** The properties of two inputSchemas that differ, by name: added, removed or changed, sorted. */
    fun properties(old: Map<String, Any?>, new: Map<String, Any?>): List<String> {
        val a = old["properties"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val b = new["properties"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val ra = (old["required"] as? List<*>).orEmpty().toSet()
        val rb = (new["required"] as? List<*>).orEmpty().toSet()
        return (a.keys + b.keys).map { it as String }.filter { k -> canonical(a[k]) != canonical(b[k]) || (k in ra) != (k in rb) || (k in a) != (k in b) }.sorted()
    }

    /**
     * Your [settings] after [changes]: a changed or unusable tool off (your label and marks kept for when you turn it on
     * again), a gone one marked, a new one added off. Settings of tools no change names are kept as they were.
     */
    fun settle(settings: List<McpToolSetting>, changes: List<McpChange>): List<McpToolSetting> {
        val by = changes.associateBy { it.name }
        val out = settings.map { s ->
            when (by[s.name]) {
                is McpChange.Changed, is McpChange.Unusable -> s.copy(level = SkillLevel.OFF, gone = false)
                is McpChange.Gone -> s.copy(level = SkillLevel.OFF, gone = true)
                is McpChange.Same -> s.copy(gone = false)
                else -> s
            }
        }
        val have = settings.map { it.name }.toSet()
        return out + changes.filter { it is McpChange.New && it.name !in have }.map { McpToolSetting(it.name) }
    }

    /** Another server at the same address ([old] pinned, [now] listed): every tool goes off. A new version alone isn't. */
    fun otherServer(old: McpServerId?, now: McpServerId?): Boolean = old != null && now?.name != old.name

    /** What Robot says when [otherServer]: every tool off until you look. */
    fun otherServerText(name: String): Phrase = ConnStrings.mcpServerChanged("name" to name)

    /** What Robot says about one change, or null when there's nothing to say ([McpChange.Same], [McpChange.New]). */
    fun text(c: McpChange, tool: String, name: String): List<Phrase>? {
        val t = "tool" to tool
        val n = "name" to name
        return when (c) {
            is McpChange.Changed -> listOf(ConnStrings.mcpChanged(t, n)) + c.parts.sortedBy { it.ordinal }.map { part ->
                when (part) {
                    McpPart.INPUTS -> ConnStrings.mcpChangedInputs("names" to c.properties.joinToString(", ") { ConnReply.clean(it) })
                    McpPart.OUTPUTS -> ConnStrings.mcpChangedOutputs()
                    McpPart.ANNOTATIONS -> ConnStrings.mcpChangedClaims()
                    McpPart.DESCRIPTION -> ConnStrings.mcpChangedDescription()
                    McpPart.TITLE -> ConnStrings.mcpChangedTitle()
                }
            }
            is McpChange.Gone -> listOf(ConnStrings.mcpGone(t, n))
            is McpChange.Unusable -> listOf(ConnStrings.mcpChanged(t, n))
            else -> null
        }
    }

    // Claims, effects and levels.

    /** The tick "This tool only reads" counts only when the server says readOnlyHint true too. */
    fun onlyReads(claims: McpClaims, s: McpToolSetting): Boolean = s.onlyReads && claims.readOnly == true

    fun effects(claims: McpClaims, s: McpToolSetting): McpEffects {
        val e = LinkedHashSet<SkillEffect>()
        if (onlyReads(claims, s)) e += SkillEffect.READS_ONLINE
        else {
            e += SkillEffect.WRITES_ONLINE
            if (claims.destructive != false) e += SkillEffect.DELETES
        }
        s.marks.sortedBy { it.ordinal }.forEach { e += it.effect }
        return McpEffects(e, claims.openWorld != false)
    }

    /** Whether [s] may be saved for [tool] ([unusable]: it can't be used): a level other than off needs a label. */
    fun turnOn(tool: McpTool?, s: McpToolSetting): McpTurnOn {
        if (s.level == SkillLevel.OFF) return McpTurnOn.OK
        if (tool == null) return McpTurnOn.UNUSABLE
        val label = s.label.trim()
        if (label.isEmpty() || label.length > MAX_LABEL || label != s.label || label.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
            return McpTurnOn.LABEL
        if (s.onlyReads && tool.claims.readOnly != true) return McpTurnOn.ONLY_READS
        if (s.level == SkillLevel.JUST_DO_IT && (!onlyReads(tool.claims, s) || s.marks.isNotEmpty())) return McpTurnOn.JUST_DO
        return McpTurnOn.OK
    }

    // x-mcp-header.

    /** [input]'s x-mcp-header declarations, or null when one breaks the rules (the tool can't be used). */
    fun headers(input: Map<*, *>): List<McpHeader>? {
        val out = ArrayList<McpHeader>()
        val seen = HashSet<String>()
        fun visit(node: Any?, path: List<String>, reached: Boolean, depth: Int): Boolean {
            if (depth > SchemaCheck.MAX_DEPTH * 2) return false
            if (node is List<*>) return node.all { visit(it, path, false, depth + 1) }
            if (node !is Map<*, *>) return true
            if (node.containsKey("x-mcp-header")) {
                val h = node["x-mcp-header"] as? String ?: return false
                val type = node["type"] as? String
                if (!reached || path.isEmpty() || !HEADER_NAME.matches(h) || type !in HEADER_TYPES || !seen.add(h.lowercase(java.util.Locale.ROOT))) return false
                out += McpHeader(path, h, type!!)
            }
            (node["properties"] as? Map<*, *>)?.forEach { (k, v) -> if (!visit(v, path + (k as String), reached, depth + 1)) return false }
            for (k in UNREACHED) {
                val sub = node[k] ?: continue
                val branches = if (sub is Map<*, *> && (k == "\$defs" || k == "definitions" || k == "dependentSchemas" || k == "patternProperties")) sub.values.toList() else listOf(sub)
                if (!branches.all { visit(it, path, false, depth + 1) }) return false
            }
            return true
        }
        return if (visit(input, emptyList(), true, 0)) out else null
    }

    /** The `Mcp-Param-…` headers for [args]: a value that's absent, null or not of its declared type sends none. */
    fun paramHeaders(headers: List<McpHeader>, args: Map<String, Any?>): List<Pair<String, String>> = headers.mapNotNull { h ->
        var v: Any? = args
        for (k in h.path) v = (v as? Map<*, *>)?.get(k)
        val s = when {
            v == null -> null
            h.type == "string" && v is String -> v
            h.type == "boolean" && v is Boolean -> v.toString()
            h.type == "integer" && v is Long -> v.toString()
            h.type == "integer" && v is Double && SchemaCheck.isType("integer", v) && Math.abs(v) <= 9.007199254740991E15 -> v.toLong().toString()
            else -> null
        }
        s?.let { HEADER_PREFIX + h.name to headerValue(it) }
    }

    /** [s] as a header's value: as it is when it's plain visible ASCII (tab too), otherwise =?base64?…?= of its UTF-8. */
    fun headerValue(s: String): String {
        val plain = s.isNotEmpty() && s == s.trim() && !(s.startsWith(B64_START) && s.endsWith(B64_END)) && s.all { it == '\t' || it in ' '..'~' }
        return if (plain) s else B64_START + java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + B64_END
    }

    // Questions.

    /** The questions a model may fill for [input], or null when it can only take fixed values (see the top). */
    fun questions(input: Map<String, Any?>): List<McpQuestion>? {
        if (input.keys.any { it in setOf("anyOf", "oneOf", "allOf", "\$ref", "not", "if", "patternProperties") }) return null // l10n: not text (keywords)
        if (input["additionalProperties"].let { it != null && it != false }) return null
        val props = input["properties"] as? Map<*, *> ?: return emptyList()
        if (props.size > MAX_QUESTIONS) return null
        val required = (input["required"] as? List<*>).orEmpty().toSet()
        return props.map { (k, v) -> question(k as String, v, k in required) ?: return null }
    }

    private fun question(name: String, s: Any?, required: Boolean): McpQuestion? {
        if (s !is Map<*, *> || s.keys.any { it !in QUESTION_KEYS }) return null
        val enum = s["enum"]
        val type = s["type"] ?: if (enum != null) "string" else return null
        val param = PRIMITIVE[type as? String ?: return null] ?: return null
        if (enum != null) {
            if (type != "string" || enum !is List<*> || enum.any { it !is String } || enum.isEmpty()) return null
            val options = enum.map { it as String }.distinct()
            return if (options.size in 2..12) McpQuestion(name, ParamType.CHOICE, required, options) else McpQuestion(name, ParamType.TEXT, required)
        }
        fun n(k: String): Double? = (s[k] as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val whole = param == ParamType.INTEGER
        val min = n("minimum") ?: if (whole) n("exclusiveMinimum")?.let { Math.floor(it) + 1 } else null
        val max = n("maximum") ?: if (whole) n("exclusiveMaximum")?.let { Math.ceil(it) - 1 } else null
        return McpQuestion(name, param, required, min = min.takeIf { whole || param == ParamType.NUMBER }, max = max.takeIf { whole || param == ParamType.NUMBER })
    }
}
