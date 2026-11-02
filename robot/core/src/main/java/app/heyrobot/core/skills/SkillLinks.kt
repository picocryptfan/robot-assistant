// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

// Pure. What a skill's steps reach beyond Robot's own tools (docs/plans/skills-connections-plan.md §5.1, C1): a connection's
// operation (`conn:`), an app hand-off (`app:`), your routing server (`travel_time`) and your typed Termux scripts
// (`termux_script`). Their details live in connections.json and in Termux, never in skills.json: a skill names them by
// id, and this is how SkillCheck, SkillEffects and SkillRisk see them. C2 fills it from connections.json, C7 adds the
// hand-offs and C8 the typed scripts; until then [SkillLinks.NONE] holds nothing, so every such step needs attention and its
// skill doesn't run. No address, key, topic, entity list or reply is ever in here beyond what a check needs.

/** A slot's or an output's type: a question type, and for a choice the values it may hold. */
data class SlotType(val type: ParamType, val options: List<String> = emptyList())

/**
 * A Home Assistant service call (plan §3.1): [domain] and [service] ("light", "turn_off"); [entityArg]: the operation's
 * argument holding the entity (`entity_id`, "all" allowed only as SkillEffects.haAll says); [deviceClasses]: each cover's
 * device class as Test read it (absent: none). Its effects come from SkillEffects.homeAssistant, over every entity a step
 * may send.
 */
data class HaCall(val domain: String, val service: String, val entityArg: String = "entity", val deviceClasses: Map<String, String> = emptyMap())

/**
 * One operation of an HTTP connection. [params]: its arguments, each with its slot's type; [required]: those a step must
 * give. [outputs]: the declared outputs (JSON pointers in C2), each typed; a text output is free text from outside Robot
 * (ValueSource.ONLINE). [read]: it only reads (GET, a Home Assistant state). [effects]: what it does whatever its values
 * (C2 computes them with SkillEffects.webhook, .ntfy or .READS_ONLINE), [ha] when it calls a Home Assistant service, and
 * [marks]: what you added (money, security, tells people), which only ever add (D12).
 */
data class LinkedOp(
    val params: Map<String, SlotType>,
    val outputs: Map<String, SlotType> = emptyMap(),
    val read: Boolean = false,
    val effects: Set<SkillEffect> = emptySet(),
    val ha: HaCall? = null,
    val marks: Set<SkillEffect> = emptySet(),
    val required: Set<String> = params.keys,
    /**
     * C4: what the skill's card names it by (yours, shown, never logged or told to a model, D19): [kind] the connection's
     * kind ("home_assistant", "ntfy", "webhook"), [title] your operation's title, [name] your connection's, [host] where it goes.
     */
    val kind: String? = null,
    val title: String? = null,
    val name: String? = null,
    val host: String? = null,
) {
    override fun toString(): String = "LinkedOp(${kind ?: "?"})" // l10n: not text (toString, for the logs)
}

/** A hand-off (plan §3.5): its typed slots, and its effects (SkillEffects.handOff); [required]: the slots a step must fill (C7: those without a default). */
data class LinkedHandOff(val slots: Map<String, SlotType>, val effects: Set<SkillEffect>, val required: Set<String> = slots.keys)

/** A typed Termux script (plan §3.6): its declared parameters in order, and its effects (SkillEffects.script). */
data class LinkedScript(val params: List<Pair<String, SlotType>>, val effects: Set<SkillEffect>)

interface SkillLinks {
    /** Operation [op] of connection [conn], or null when either no longer exists. */
    fun op(conn: String, op: String): LinkedOp? = null

    /** The hand-off connection [conn], or null. */
    fun handOff(conn: String): LinkedHandOff? = null

    /** Your typed script [name] (one with `# param:` lines in the listing run), or null. */
    fun script(name: String): LinkedScript? = null

    /** A routing server `travel_time` can use: [conn] when the step names one, else the only one you have. */
    fun routing(conn: String?): Boolean = false

    companion object {
        /** Nothing linked: no connections, hand-offs or typed scripts (C1, before connections exist). */
        val NONE: SkillLinks = object : SkillLinks {}
    }
}
