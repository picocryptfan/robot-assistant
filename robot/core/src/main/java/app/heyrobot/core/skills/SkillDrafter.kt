// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContactMatcher
import app.heyrobot.core.ContactName
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.messages.MessageTaint
import org.json.JSONArray
import org.json.JSONObject

// Pure. Skills 1.3, a skill drafted from what you describe (design §10; skills-1.3-plan.md S3.8). "Make a skill that …",
// « crée une compétence qui … »: the DRAFT one-shot gets a fixed instruction, your words and the step kinds you have (the
// tools that are on, your connections' operation titles), never your contacts, calendar or other skills, and answers with
// one make_skill call. Read here: every step through its tool's own parser or its connection's operation, then §3.8's
// checks (SkillCheck.stepProblems). A step that fails is left out and named on the card, never guessed; a person a step
// names is shown as found in your contacts or missing. The draft goes to the save card at Ask first, and only a tap saves
// it (SkillDraft.tapLine): a spoken yes says to tap. After content (§10.3), its banner says where from, and it stays at
// Ask first with no trigger until you edit and save it yourself in Settings.

/** Where the content in a conversation came from, for a draft's banner (design §10.3). */
enum class DraftSource { MESSAGE, WEB, SCREEN, CONTENT }

/** A step kind the drafter may use besides Robot's own tools: a connection's operation ([use] `conn:<id>:<op>`). */
data class DraftOp(val use: String, val title: String, val connection: String, val params: List<String>)

/** Why a draft wasn't read (the voice log's draft code b). Stable numbers. */
enum class DraftRefusal(val id: Int) { NO_DRAFT(1), BAD_SHAPE(2) }

sealed class DraftRead {
    data class Ok(val draft: SkillDraft) : DraftRead()
    data class Refused(val why: DraftRefusal) : DraftRead()
}

object SkillDrafter {
    const val TOOL = "make_skill" // l10n: not text (a tool's name)

    /** Design §4.7: drafts an hour (EST). */
    const val PER_HOUR = 10

    /** The words described: a skill is a few steps, never a page. */
    const val MAX_WORDS = 400

    /** A dropped step's name on the card, at most this long (the model's words, cut). */
    private const val MAX_SHOWN = 40

    /** "Make a skill that …" (EN) and « crée une compétence qui … » (FR): [name] when you said one, [words] the description. */
    data class Ask(val words: String, val name: String?, val lang: Lang)

    private val ASK_EN = Regex(
        """^(?:can you\s+|could you\s+|please\s+)?(?:make|create|write|draft|build)\s+(?:me\s+)?(?:a\s+(?:new\s+)?|an?\s+)skill""" +
            """(?:\s+(?:called|named)\s+(.{1,40}?))?\s*,?\s+(?:that|which|to|for)\s+(.+)$""", // l10n: not text (words you say)
        RegexOption.IGNORE_CASE)
    private val ASK_FR = Regex(
        """^(?:peux-tu\s+|s'il te plaît\s+)?(?:crée|cree|fais|écris|ecris|rédige|redige)(?:-moi|\s+moi)?\s+une\s+(?:nouvelle\s+)?(?:compétence|competence|skill)""" +
            """(?:\s+(?:qui\s+s['’]appelle|appelée|appelee|nommée|nommee)\s+(.{1,40}?))?\s*,?\s+(?:qui|pour)\s+(?!s['’]appelle)(.+)$""", // l10n: not text (words you say)
        RegexOption.IGNORE_CASE)

    /** [q] (your words without "hey robot" in front) as a draft asked for, or null. */
    fun route(q: String): Ask? {
        val t = q.trim().trimEnd('.', '!', '?').trim()
        if (t.isEmpty() || t.length > MAX_WORDS) return null
        for ((rx, lang) in listOf(ASK_EN to Lang.EN, ASK_FR to Lang.FR)) {
            val m = rx.matchEntire(t) ?: continue
            val name = m.groupValues[1].trim().ifEmpty { null }
            return Ask(m.groupValues[2].trim(), name, lang)
        }
        return null
    }

    /** Where the content in [turns] came from, or null when Robot wrote all of it (TurnRules.untrusted is then false). */
    fun source(turns: List<ChatTurn>): DraftSource? = when {
        turns.any { it.messages } -> DraftSource.MESSAGE
        turns.any { t -> t.actions.any { it.content && it.tool == ToolName.WEB_SEARCH } || t.sources.isNotEmpty() } -> DraftSource.WEB
        turns.any { it.attachment == Attachment.SCREEN || it.attachment == Attachment.PHOTO || it.readBack } -> DraftSource.SCREEN
        turns.any { t -> t.attachment != Attachment.NONE || t.fromHelper || t.locked || t.actions.any { it.content } } -> DraftSource.CONTENT
        else -> null
    }

    /** Your connections' operations a draft may use, by their titles (never their addresses or keys). */
    fun ops(connections: List<Connection>): List<DraftOp> = connections.filter { it.kind != ConnKind.HAND_OFF && it.routing == null }.flatMap { c ->
        c.ops.map { op -> DraftOp("conn:${c.id}:${op.id}", op.title, c.name, op.params.keys.sorted()) } // l10n: not text (a step kind in the skills file)
    }

    /** make_skill, as LiteRT-LM's OpenApiTool takes it: the skill's name and its steps in order, each a kind and its arguments. */
    val TOOL_JSON: String = JSONObject().put("name", TOOL)
        .put("description", "Write the skill the person described: its name and its steps, in order.") // l10n: not text (a tool for the model)
        .put("parameters", JSONObject().put("type", "object") // l10n: not text (a JSON schema)
            .put("properties", JSONObject()
                .put("name", JSONObject().put("type", "string").put("description", "A short name, 1 to 5 words")) // l10n: not text (a tool for the model)
                .put("steps", JSONObject().put("type", "array") // l10n: not text (a JSON schema)
                    .put("description", "The steps, in the order to do them") // l10n: not text (a tool for the model)
                    .put("items", JSONObject().put("type", "object") // l10n: not text (a JSON schema)
                        .put("properties", JSONObject()
                            .put("use", JSONObject().put("type", "string").put("description", "A step kind")) // l10n: not text (a tool for the model)
                            .put("args", JSONObject().put("type", "object").put("description", "Its arguments"))) // l10n: not text (a tool for the model)
                        .put("required", JSONArray(listOf("use", "args")))))) // l10n: not text (a JSON schema)
            .put("required", JSONArray(listOf("name", "steps")))).toString() // l10n: not text (a JSON schema)

    /** The DRAFT one-shot's system prompt: the step kinds you have ([tools] that are on, your connections' [ops]), nothing of yours else. */
    fun system(tools: List<ToolName>, ops: List<DraftOp>): String = buildString {
        appendLine("You write skills for Robot, an assistant on a phone. Call $TOOL once, with nothing else.") // l10n: not text (a prompt for the model)
        appendLine("A skill is a name and at most ${SkillCheck.MAX_STEPS} steps. Each step is {\"use\": a step kind, \"args\": its arguments}. Only these kinds:") // l10n: not text (a prompt for the model)
        for (t in tools.filter { it in SkillPlans.PLANNABLE }) {
            val spec = ToolSpecs.of(t)
            appendLine("- ${t.id}(${spec.params.joinToString(", ") { "${it.name}: ${it.type}" }}): ${spec.description}") // l10n: not text (a prompt for the model)
        }
        appendLine("- say(text: string): Robot says this sentence.") // l10n: not text (a prompt for the model)
        for (o in ops) appendLine("- ${o.use}(${o.params.joinToString(", ")}): ${o.title.take(MAX_SHOWN)}") // l10n: not text (a prompt for the model)
        appendLine("Take every value from the person's words. Add no step they didn't describe.") // l10n: not text (a prompt for the model)
        append("If you can't call $TOOL, write only its arguments as JSON: {\"name\": ..., \"steps\": [...]}.") // l10n: not text (a prompt for the model)
    }

    /**
     * The draft in the model's reply: its one make_skill call ([calls]), or the JSON in its [text] from an engine without
     * the tool API. [on]: Robot's tools that are on; [ops]: the connections' operations offered; [contacts]: to show a
     * person as found or missing; [source]: the content in the conversation (null: none); [taint]: other people's messages
     * here (a step that sends to anyone but their sender is left out).
     */
    fun read(calls: List<RawToolCall>, text: String, ask: Ask, id: String, now: Long, on: Set<ToolName>, ops: List<DraftOp>,
        contacts: List<ContactName>, source: DraftSource?, taint: MessageTaint = MessageTaint.NONE, links: SkillLinks = SkillLinks.NONE,
        made: SkillMade = SkillMade.DRAFT_DEVICE): DraftRead {
        val args: Any? = when {
            calls.size == 1 && calls[0].name == TOOL -> calls[0].args
            calls.isNotEmpty() -> return DraftRead.Refused(DraftRefusal.BAD_SHAPE)
            else -> lenient(text) ?: return DraftRead.Refused(DraftRefusal.NO_DRAFT)
        }
        val m = plain(args) as? Map<*, *> ?: return DraftRead.Refused(DraftRefusal.BAD_SHAPE)
        val raw = m["steps"] as? List<*> ?: return DraftRead.Refused(DraftRefusal.BAD_SHAPE)
        val leftOut = mutableListOf<Phrase>()
        val kept = mutableListOf<Pair<Int, SkillStep>>()
        val offered = ops.associateBy { it.use }
        raw.forEachIndexed { i, st ->
            val r = step(st, offered, on, taint, links)
            when (r) {
                is Kept -> kept += (i + 1) to r.step
                is Dropped -> leftOut += SkillStrings.draftLeftOut("n" to (i + 1).toString(), "what" to ((r.what as? String)?.take(MAX_SHOWN) ?: r.what), "why" to r.why)
            }
        }
        // §3.8 on each step kept, as it would be saved: a step that fails is left out and named too.
        val name = (ask.name ?: (m["name"] as? String))?.let(SkillDraft::displayName)?.takeIf { SkillCheck.validName(it) && SkillDraft.otherName(it) != null }
            ?: SkillDraft.displayName(SkillFromActions.defaultName(kept.map { it.second }, ask.lang))
        val probe = Skill(id, 1, name, emptyList(), emptyList(), emptyList(), kept.map { it.second }, SkillLevel.ASK_FIRST, SkillProvenance(made, source != null, now, now))
        val checked = kept.filterIndexed { i, (n, st) ->
            val p = SkillCheck.stepProblems(probe, st, i).firstOrNull() ?: return@filterIndexed true
            leftOut += SkillStrings.draftLeftOut("n" to n.toString(), "what" to (st.kind.tool?.titleText ?: st.use), "why" to p)
            false
        }.map { it.second }
        val notes = mutableListOf<Phrase>()
        if (checked.size > SkillCheck.MAX_STEPS) notes += SkillStrings.tooManySteps()
        val steps = checked.take(SkillCheck.MAX_STEPS)
        notes += people(steps, contacts)
        return DraftRead.Ok(SkillDraft(id, name, ask.lang, steps, SkillDraft.slots(steps), made, source != null, now, leftOut = leftOut, notes = notes,
            source = source))
    }

    private sealed class Got
    private class Kept(val step: SkillStep) : Got()
    private class Dropped(val what: Any, val why: Phrase) : Got()

    /** One of the model's steps as a skill's step, or why it was left out. */
    private fun step(st: Any?, offered: Map<String, DraftOp>, on: Set<ToolName>, taint: MessageTaint, links: SkillLinks): Got {
        val m = st as? Map<*, *> ?: return Dropped("?", SkillStrings.draftWhyUnknown())
        val use = (m["use"] as? String)?.trim().orEmpty()
        val a = (m["args"] ?: emptyMap<String, Any?>()) as? Map<*, *> ?: return Dropped(use, SkillStrings.draftWhyValues())
        if (!a.keys.all { it is String }) return Dropped(use, SkillStrings.draftWhyValues())
        @Suppress("UNCHECKED_CAST") val args = (a as Map<String, Any?>).filterValues { it != null }
        // A draft's values are fixed: the save card's chips make one asked each time, never the model.
        if (args.values.any { SkillTemplate.looksLike(it) }) return Dropped(use, SkillStrings.draftWhyValues())
        if (use.startsWith("conn:")) {
            val o = offered[use] ?: return Dropped(use, SkillStrings.draftWhyConn())
            val (_, conn, op) = use.split(':').takeIf { it.size == 3 } ?: return Dropped(o.title, SkillStrings.draftWhyConn())
            if (links.op(conn, op) == null) return Dropped(o.title, SkillStrings.draftWhyConn())
            val s = SkillStep.of(StepKind.CONN, args, conn = conn, op = op)
            val probe = Skill("s-probe0", 1, "Probe", emptyList(), emptyList(), emptyList(), listOf(s), SkillLevel.ASK_FIRST, SkillProvenance(SkillMade.DRAFT_DEVICE, false, 0, 0))
            if (taint.active && SkillEffect.MESSAGES_SOMEONE in SkillEffects.step(probe, s, links)) return Dropped(o.title, SkillStrings.draftWhySender())
            return Kept(s)
        }
        if (use == StepKind.SAY.id) {
            val t = args["text"] as? String
            return if (t.isNullOrBlank() || args.keys != setOf("text")) Dropped(use, SkillStrings.draftWhyValues()) else Kept(SkillStep.of(StepKind.SAY, mapOf("text" to t.trim())))
        }
        val tool = ToolName.of(use) ?: return Dropped(use, SkillStrings.draftWhyUnknown())
        if (tool in StepKind.NEVER || tool !in SkillPlans.PLANNABLE) return Dropped(tool.titleText, SkillStrings.draftWhyNever())
        if (tool !in on) return Dropped(tool.titleText, SkillStrings.draftWhyOff())
        val action = (ToolCalls.parse(RawToolCall(tool.id, args)) as? ParsedCall.Ok)?.action ?: return Dropped(tool.titleText, SkillStrings.draftWhyValues())
        val s = SkillDraft.step(action) ?: return Dropped(tool.titleText, SkillStrings.draftWhyNever())
        // After a message, only its sender may be sent to (MessageQuarantine's rule): a name is never an address, so it's left out.
        if (taint.active && s.kind in SENDS && !taint.isSenderAddress(s.args["to"] as? String)) return Dropped(tool.titleText, SkillStrings.draftWhySender())
        return Kept(s)
    }

    private val SENDS = setOf(StepKind.SEND_TEXT, StepKind.DRAFT_EMAIL)

    /** "Sam: Sam Tremblay in your contacts", or "Sam: not in your contacts", for each person a step names (once each). */
    fun people(steps: List<SkillStep>, contacts: List<ContactName>): List<Phrase> =
        steps.filter { it.kind in SENDS }.mapNotNull { (it.args["to"] as? String)?.trim()?.takeIf(String::isNotEmpty) }.distinct().map { who ->
            val found = ContactMatcher.best(who, contacts, max = 1).firstOrNull()
            if (found != null) SkillStrings.draftFound("who" to who, "name" to found.name) else SkillStrings.draftMissing("who" to who)
        }

    /** The JSON object in [text] (a model without the tool API writes it as text), or null. */
    private fun lenient(text: String): Any? {
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(text.substring(a, b + 1)) } catch (_: Exception) { null }
    }

    private fun plain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
        is Map<*, *> -> v.entries.associate { (k, x) -> k to plain(x) }
        is List<*> -> v.map(::plain)
        else -> v
    }
}
