// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

// Pure. An MCP server's tools as tools/list gives them (design §8.5), read into what Robot keeps and checks. Everything
// a server says is a claim: a tool's annotations are its hints, never facts (the spec calls them untrusted), and its
// description and titles are the server's words, shown in a grey box and never given to a model (McpPolicy).
//
// A tool Robot can't use is listed with why ([McpUnusable]) and can never be turned on: a name outside the spec's
// characters (so a name is always safe in a header, a log line and a skill's step), a name given twice (on any page), a
// field of the wrong kind, an invalid x-mcp-header (the spec says such a tool is excluded), a tool that only runs as a
// task (tasks are never used), or a schema SchemaCheck can't read, so its arguments couldn't be checked.

/** The server's annotations, as claims: null where it didn't say (the spec's defaults are McpPolicy's). */
data class McpClaims(val readOnly: Boolean? = null, val destructive: Boolean? = null, val idempotent: Boolean? = null, val openWorld: Boolean? = null)

/** An x-mcp-header: the argument at [path] (property names from the top) goes in `Mcp-Param-<name>`; [type] its JSON type. */
data class McpHeader(val path: List<String>, val name: String, val type: String)

/** One tool as listed: [input] its inputSchema, [output] its outputSchema (null: none), [execution] as the server gave it. */
data class McpTool(val name: String, val title: String?, val description: String?, val input: Map<String, Any?>, val output: Any?,
    val annotations: Map<String, Any?>?, val execution: Map<String, Any?>?, val claims: McpClaims, val headers: List<McpHeader>) {
    override fun toString(): String = "McpTool($name)" // l10n: not text (toString, for the logs)
}

/** Why a listed tool can't be turned on. [code]: what the voice log may keep. */
enum class McpUnusable(val code: Int) { NAME(1), TWICE(2), SHAPE(3), HEADER(4), TASKS(5), INPUTS(6), OUTPUTS(7) }

/** Every page of one listing: the tools Robot can use, and those it can't by name (null when even the name was wrong). */
data class McpListed(val tools: List<McpTool>, val unusable: List<Pair<String?, McpUnusable>>) {
    fun tool(name: String): McpTool? = tools.firstOrNull { it.name == name }
}

object McpTools {
    /** The spec's tool names (2025-11-25): 1 to 128 of these characters. */
    val NAME = Regex("^[A-Za-z0-9_.-]{1,128}$")
    /** At most this many tools from one server, on at most [McpWire.MAX_PAGES] pages. */
    const val MAX_TOOLS = 200

    private val HINTS = setOf("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint") // l10n: not text (the spec's keys)
    private val TASKS = setOf("required", "optional", "forbidden") // l10n: not text (the spec's values)

    /** [pages]: each page's `tools` array, in order. More than [MAX_TOOLS] tools in all: null (refused whole). */
    fun read(pages: List<List<Any?>>): McpListed? {
        val raw = pages.flatten()
        if (raw.size > MAX_TOOLS) return null
        val names = raw.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.groupingBy { it }.eachCount()
        val tools = ArrayList<McpTool>()
        val unusable = ArrayList<Pair<String?, McpUnusable>>()
        for (t in raw) {
            val name = ((t as? Map<*, *>)?.get("name") as? String)?.takeIf { NAME.matches(it) }
            if (name == null) {
                unusable += null to McpUnusable.NAME
                continue
            }
            if ((names[name] ?: 0) > 1) {
                unusable += name to McpUnusable.TWICE
                continue
            }
            when (val r = tool(name, t as Map<*, *>)) {
                is McpTool -> tools += r
                is McpUnusable -> unusable += name to r
            }
        }
        return McpListed(tools, unusable)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tool(name: String, t: Map<*, *>): Any {
        if (t.keys.any { it !is String }) return McpUnusable.SHAPE
        val title = t["title"]
        val description = t["description"]
        val input = t["inputSchema"]
        val output = t["outputSchema"]
        val annotations = t["annotations"]
        val execution = t["execution"]
        if ((title != null && title !is String) || (description != null && description !is String)) return McpUnusable.SHAPE
        if (input !is Map<*, *> || input["type"] != "object") return McpUnusable.SHAPE
        if (output != null && output !is Map<*, *>) return McpUnusable.SHAPE
        if (annotations != null && (annotations !is Map<*, *> || !annotationsFit(annotations))) return McpUnusable.SHAPE
        if (execution != null && (execution !is Map<*, *> || (execution["taskSupport"] ?: "forbidden") !in TASKS)) return McpUnusable.SHAPE
        if ((execution as? Map<*, *>)?.get("taskSupport") == "required") return McpUnusable.TASKS
        if (!finite(t)) return McpUnusable.SHAPE
        val headers = McpPolicy.headers(input) ?: return McpUnusable.HEADER
        if (SchemaCheck.unsupported(input) != null) return McpUnusable.INPUTS
        if (output != null && SchemaCheck.unsupported(output) != null) return McpUnusable.OUTPUTS
        val a = annotations as Map<String, Any?>?
        val claims = McpClaims(a?.get("readOnlyHint") as Boolean?, a?.get("destructiveHint") as Boolean?, a?.get("idempotentHint") as Boolean?,
            a?.get("openWorldHint") as Boolean?)
        return McpTool(name, title as String?, description as String?, input as Map<String, Any?>, output, a, execution as Map<String, Any?>?, claims, headers)
    }

    /** No number in [v] that isn't finite (StrictJson reads 1e400 as infinity, which no canonical form can write). */
    private fun finite(v: Any?): Boolean = when (v) {
        is Double -> v.isFinite()
        is Map<*, *> -> v.values.all(::finite)
        is List<*> -> v.all(::finite)
        else -> true
    }

    private fun annotationsFit(a: Map<*, *>): Boolean = a.all { (k, v) ->
        when (k) {
            "title" -> v is String
            in HINTS -> v is Boolean
            else -> k is String // a later spec's hint: kept and pinned, never read
        }
    }

    /** A page's cursor for the next one: a non-empty string of at most 1,024 characters, or null when this is the last. */
    fun next(result: Map<*, *>): String? = (result["nextCursor"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 1024 }
}
