// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.skills.SkillOption
import app.heyrobot.core.skills.SkillParam
import app.heyrobot.core.skills.SkillProvenance
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.skills.SkillsLock
import app.heyrobot.core.skills.StepKind
import java.io.File
import java.security.MessageDigest

// An MCP server's tools as Robot keeps them (1.3, design §8.5; S3.6): files/robot/connections/<id>/mcp_tools.json, its
// own file (settings.json stays at schema 13; connections.json never holds a tool). What the last list gave ([McpToolsFile.tools],
// the server's own words, shown in Settings' grey box and never to a model), your pins and choices (McpPolicy), and what
// the last list changed. Written by the list (the connection window, :voice) and by Settings (main) under SkillsLock, each
// time whole (a temporary file renamed over it). A file Robot can't read is no tools: every tool is off.
//
// McpOps turns each tool you turned on (a label, a level other than off, pinned, still listed and unchanged) into a
// ConnOp of the server's connection, OpSpec.McpCall, so a skill's `conn:<server>:<tool>` step, its checks, its effects
// and its card are those of any connection's operation. McpSkills makes each one a one-step skill named by your label,
// which is how the model finds it (design §8.5: "like a one-step skill"); its questions are McpPolicy.questions'.

/** What one list said about one tool, kept for Settings until the next list. */
data class McpNote(val tool: String, val change: McpChange)

data class McpToolsFile(
    val server: McpServerId? = null,
    val era: McpEra? = null,
    val tools: List<Map<String, Any?>> = emptyList(),
    val pins: List<McpPin> = emptyList(),
    val settings: List<McpToolSetting> = emptyList(),
    val notes: List<McpNote> = emptyList(),
    val otherServer: Boolean = false,
) {
    /** The last list's tools as McpTools reads them (null: none Robot can use). */
    val listed: McpListed? get() = McpTools.read(listOf(tools))

    fun setting(name: String): McpToolSetting = settings.firstOrNull { it.name == name } ?: McpToolSetting(name)

    override fun toString(): String = "McpToolsFile(${tools.size}, ${pins.size})" // l10n: not text (toString, for the logs)
}

object McpToolsCodec {
    const val VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024

    fun encode(f: McpToolsFile): ByteArray = ConnJson.write(linkedMapOf(
        "v" to VERSION,
        "server" to f.server?.let { linkedMapOf("name" to it.name, "version" to it.version) },
        "era" to f.era?.id,
        "tools" to f.tools,
        "pins" to f.pins.map { p -> linkedMapOf("name" to p.name, "parts" to p.parts.entries.associate { it.key.id to it.value }, "input" to p.input, "output" to p.output) },
        "settings" to f.settings.map { s -> linkedMapOf("name" to s.name, "level" to s.level.id, "label" to s.label, "only_reads" to s.onlyReads,
            "marks" to s.marks.sortedBy { it.ordinal }.map { it.id }, "gone" to s.gone) },
        "notes" to f.notes.map { n -> note(n) },
        "other_server" to f.otherServer,
    )).toByteArray(Charsets.UTF_8)

    private fun note(n: McpNote): Map<String, Any?> = when (val c = n.change) {
        is McpChange.Changed -> linkedMapOf("tool" to n.tool, "change" to "changed", "parts" to c.parts.sortedBy { it.ordinal }.map { it.id }, "properties" to c.properties)
        is McpChange.Gone -> linkedMapOf("tool" to n.tool, "change" to "gone")
        is McpChange.Unusable -> linkedMapOf("tool" to n.tool, "change" to "unusable", "why" to c.why.code)
        else -> linkedMapOf("tool" to n.tool, "change" to "same")
    }

    /** The file, or null when it isn't one Robot wrote (damaged, or from a newer Robot): no tools then. */
    fun decode(bytes: ByteArray): McpToolsFile? = try {
        val o = StrictJson.parse(bytes)?.value as? Map<*, *>
        if (o == null || (o["v"] as? Number)?.toInt() != VERSION) null else McpToolsFile(
            server = (o["server"] as? Map<*, *>)?.let { McpServerId(it["name"] as? String ?: return null, it["version"] as? String ?: "") },
            era = McpEra.entries.firstOrNull { it.id == o["era"] },
            tools = (o["tools"] as? List<*>)?.map { t -> (t as? Map<*, *>)?.entries?.associate { it.key as String to it.value } ?: return null }.orEmpty(),
            pins = (o["pins"] as? List<*>).orEmpty().map { p ->
                val m = p as? Map<*, *> ?: return null
                val parts = (m["parts"] as? Map<*, *>)?.entries?.associate { (k, v) -> (McpPart.entries.firstOrNull { it.id == k } ?: return null) to (v as? String ?: return null) } ?: return null
                @Suppress("UNCHECKED_CAST")
                McpPin(m["name"] as? String ?: return null, parts, m["input"] as? Map<String, Any?> ?: return null, m["output"])
            },
            settings = (o["settings"] as? List<*>).orEmpty().map { s ->
                val m = s as? Map<*, *> ?: return null
                McpToolSetting(m["name"] as? String ?: return null, SkillLevel.of(m["level"] as? String) ?: return null, m["label"] as? String ?: "",
                    m["only_reads"] == true, (m["marks"] as? List<*>).orEmpty().mapNotNull { McpMark.of(it as? String) }.toSet(), m["gone"] == true)
            },
            notes = (o["notes"] as? List<*>).orEmpty().mapNotNull { n ->
                val m = n as? Map<*, *> ?: return@mapNotNull null
                val tool = m["tool"] as? String ?: return@mapNotNull null
                val change: McpChange = when (m["change"]) {
                    "changed" -> McpChange.Changed(tool, (m["parts"] as? List<*>).orEmpty().mapNotNull { p -> McpPart.entries.firstOrNull { it.id == p } }.toSet(),
                        (m["properties"] as? List<*>).orEmpty().filterIsInstance<String>())
                    "gone" -> McpChange.Gone(tool)
                    "unusable" -> McpChange.Unusable(tool, McpUnusable.entries.firstOrNull { it.code == (m["why"] as? Number)?.toInt() } ?: McpUnusable.SHAPE)
                    else -> return@mapNotNull null
                }
                McpNote(tool, change)
            },
            otherServer = o["other_server"] == true,
        )
    } catch (_: RuntimeException) {
        null
    }
}

/** What a list did to the file: the file to write, and how many tools it turned off, marked gone, or whether the server is another. */
class McpListResult(val file: McpToolsFile, val off: Int, val gone: Int, val otherServer: Boolean)

object McpOps {
    /**
     * The file after a list found [server] with [listed] (raw [tools]): McpPolicy.changes against your pins, your settings
     * settled (a changed or unusable tool off, a gone one marked, a new one added off), and another server's name turning
     * every tool off. A tool turned off as changed keeps its old pin until you turn it on again, so its difference is kept.
     */
    fun afterList(f: McpToolsFile, server: McpServer, tools: List<Map<String, Any?>>, listed: McpListed): McpListResult {
        val other = McpPolicy.otherServer(f.server, server.id)
        val changes = McpPolicy.changes(f.pins, listed)
        var settings = McpPolicy.settle(f.settings, changes)
        if (other) settings = settings.map { it.copy(level = SkillLevel.OFF) }
        val wasOn = f.settings.filter { it.level != SkillLevel.OFF }.map { it.name }.toSet()
        val off = changes.count { (it is McpChange.Changed || it is McpChange.Unusable) && it.name in wasOn } + if (other) wasOn.size else 0
        val gone = changes.count { it is McpChange.Gone }
        val notes = changes.filter { it is McpChange.Changed || it is McpChange.Gone || it is McpChange.Unusable }.map { McpNote(it.name, it) }
        return McpListResult(f.copy(server = server.id ?: f.server, era = server.era, tools = tools, settings = settings, notes = notes, otherServer = other),
            off, gone, other)
    }

    /**
     * [s] saved for its tool: refused as McpPolicy.turnOn says (a label, the "only reads" tick, Just do it), else the file
     * with it, and the tool pinned as the last list gave it when it goes on (its note cleared).
     */
    fun set(f: McpToolsFile, s: McpToolSetting): Pair<McpToolsFile?, McpTurnOn> {
        val tool = f.listed?.tool(s.name)
        val why = McpPolicy.turnOn(tool, s)
        if (why != McpTurnOn.OK) return null to why
        val settings = if (f.settings.any { it.name == s.name }) f.settings.map { if (it.name == s.name) s.copy(gone = false) else it } else f.settings + s
        val pins = if (s.level == SkillLevel.OFF || tool == null) f.pins else f.pins.filter { it.name != s.name } + McpPolicy.pin(tool)
        val notes = if (s.level == SkillLevel.OFF) f.notes else f.notes.filter { it.tool != s.name }
        return f.copy(settings = settings, pins = pins, notes = notes, otherServer = f.otherServer && s.level == SkillLevel.OFF) to McpTurnOn.OK
    }

    /** Your turned-on tools as operations of [c]: on, labelled, pinned, still listed with the same pin, and allowed as set. */
    fun ops(c: Connection, f: McpToolsFile): List<ConnOp> {
        if (f.otherServer) return emptyList()
        val listed = f.listed ?: return emptyList()
        return f.settings.filter { it.level != SkillLevel.OFF && !it.gone }.mapNotNull { s ->
            val tool = listed.tool(s.name) ?: return@mapNotNull null
            val pin = f.pins.firstOrNull { it.name == s.name }?.takeIf { it.parts == McpPolicy.pin(tool).parts } ?: return@mapNotNull null
            if (McpPolicy.turnOn(tool, s) != McpTurnOn.OK) return@mapNotNull null
            val e = McpPolicy.effects(tool.claims, s)
            ConnOp(s.name, s.label, OpSpec.McpCall(s.name, pin.input, e.effects, McpPolicy.onlyReads(tool.claims, s)), params(pin.input),
                marks = s.marks.map { it.effect }.toSet())
        }
    }

    /**
     * The questions a model may fill (McpPolicy.questions, at most 3 primitives): each required one, and an optional text
     * (left out of the call when empty). A tool with others takes fixed values in a skill's step only: no question.
     */
    fun params(input: Map<String, Any?>): Map<String, OpParam> = McpPolicy.questions(input).orEmpty().mapNotNull { q ->
        when {
            q.required -> q.name to OpParam(q.type, q.options, q.min, q.max)
            q.type == ParamType.TEXT -> q.name to OpParam(q.type, default = "")
            else -> null
        }
    }.toMap()

    /** A call's arguments from its typed values: an optional text left empty is left out. */
    fun arguments(op: ConnOp, values: Map<String, app.heyrobot.core.skills.SkillValue>): Map<String, Any?> =
        values.filter { (k, v) -> !(op.params[k]?.default != null && v.value == "") }.mapValues { it.value.value }
}

/** The tools' files, under files/robot (ConnStore's folder). */
class McpStore(private val dir: File) {
    fun file(id: String): File = File(File(File(dir, FOLDER), id), FILE)

    /** [id]'s tools file; nothing written yet is an empty one; one Robot can't read is null (no tools are used). */
    fun read(id: String): McpToolsFile? {
        val f = file(id)
        if (!f.exists()) return McpToolsFile()
        return try {
            if (f.length() > McpToolsCodec.MAX_BYTES) null else McpToolsCodec.decode(f.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    /** [c] with its turned-on tools as operations (an MCP server); any other connection as it is. */
    fun attach(c: Connection): Connection = if (c.kind != ConnKind.MCP) c else c.copy(ops = read(c.id)?.let { McpOps.ops(c, it) }.orEmpty())

    /** Changes [id]'s file under the skills lock: [change] gives the file to write, or null to leave it. The written file, or null. */
    fun edit(id: String, change: (McpToolsFile) -> McpToolsFile?): McpToolsFile? = SkillsLock.locked(dir) {
        val now = read(id) ?: McpToolsFile()
        val next = change(now) ?: return@locked null
        val target = file(id)
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$FILE.tmp")
            tmp.writeBytes(McpToolsCodec.encode(next))
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@locked null
            }
            next
        } catch (_: Exception) {
            null
        }
    }

    /** [id]'s folder gone with its connection. */
    fun delete(id: String): Boolean = File(File(dir, FOLDER), id).deleteRecursively()

    companion object {
        const val FOLDER = "connections" // l10n: not text (a folder)
        const val FILE = "mcp_tools.json" // l10n: not text (a file name)
    }
}

/** Your turned-on MCP tools as one-step skills (design §8.5): what the model is offered, by your label. */
object McpSkills {
    /** One skill for each turned-on tool of [conns], its id not one of [taken] (your skills'), and only one SkillCheck finds whole. */
    fun of(conns: List<Connection>, taken: Set<String>, level: (Connection, ConnOp) -> SkillLevel = { _, _ -> SkillLevel.ASK_FIRST }): List<Skill> =
        conns.filter { it.kind == ConnKind.MCP }.flatMap { c ->
            c.ops.mapNotNull { op ->
                if (op.spec !is OpSpec.McpCall) return@mapNotNull null
                val id = id(c.id, op.id).takeIf { it !in taken } ?: return@mapNotNull null
                val params = op.params.map { (k, p) ->
                    SkillParam(k, p.type, p.default ?: "", k, k, options = p.options.map { SkillOption(it, it, it) }, min = p.min, max = p.max)
                }
                val step = SkillStep.of(StepKind.CONN, op.params.keys.associateWith { "{{$it}}" }, conn = c.id, op = op.id)
                Skill(id, 1, op.title, emptyList(), emptyList(), params, listOf(step), level(c, op), SkillProvenance(SkillMade.SETTINGS, false, 0L, 0L))
                    .takeIf { SkillCheck.valid(it) }
            }
        }

    /** A stable skill id for tool [tool] of server [conn]: `s-` and six letters or digits from their SHA-256. */
    fun id(conn: String, tool: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("mcp:$conn:$tool".toByteArray(Charsets.UTF_8)) // l10n: not text (hashed)
        return "s-" + (0 until 6).map { ABC[(d[it].toInt() and 0xff) % ABC.length] }.joinToString("")
    }

    private const val ABC = "abcdefghijklmnopqrstuvwxyz0123456789" // l10n: not text (id letters)
}
