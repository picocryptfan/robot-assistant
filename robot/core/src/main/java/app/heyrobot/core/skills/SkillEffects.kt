// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import java.util.Locale

// Pure. What a skill does, computed from its steps and never declared (design §4.2, connections plan D12, §3): the union of
// its steps' effects. Monotone: adding a step never removes an effect, and your marks (money, security, tells people) and a
// script's `# effect:` lines only ever add. The skill's card shows the union; SkillRisk scores it (D11). Home Assistant's
// effects come from the domains of the service and of every entity a step may send (plan §3.1); a webhook's from its
// method, plus money as a hint (never a proof) from words in its path.

enum class SkillEffect {
    READS_PHONE, READS_ONLINE, CHANGES_PHONE, OPENS_APP, MESSAGES_SOMEONE, CHANGES_HOME, SECURITY, WRITES_ONLINE, DELETES, RUNS_CODE, MONEY,
    /** Text written by Robot's model: a text question the model filled. */
    MODEL_TEXT;

    companion object {
        /** Any of them makes a skill Risky whatever its points (D11). */
        val RISKY: Set<SkillEffect> = setOf(SECURITY, DELETES, MONEY)
    }
}

object SkillEffects {
    /** Home Assistant domains that lock, arm, watch or open (plan §3.1): Risky, tap only. */
    val HA_SECURITY: Set<String> = setOf("lock", "alarm_control_panel", "siren", "camera", "valve")

    /**
     * Domains whose services run code Robot can't see, or reach entities it can't see (C12's M2): a scene sets whatever
     * states it holds (a lock's, an alarm's), a group's members get the service in their own domains, and `rest_command`
     * and `pyscript` send and run what Home Assistant holds. So no spoken yes, and the card says Robot can't see it.
     */
    val HA_CODE: Set<String> = setOf("script", "automation", "shell_command", "python_script", "hassio", "scene", "group", "rest_command", "pyscript")

    /** Covers that only shade or air a room. Any other device class, or none (garage, gate, door, unknown), is security. */
    val COVER_SAFE: Set<String> = setOf("awning", "blind", "curtain", "damper", "shade", "shutter", "window")

    /** Words in a webhook's path that hint at money (design §4.2 and plan §3.3, together). */
    val MONEY_WORDS: List<String> = listOf("pay", "purchase", "order", "checkout", "transfer", "invoice", "buy", "wallet", "payer", "acheter", "commande")

    /** [s]'s effects. [given]: the questions the model's call filled (a text one adds [SkillEffect.MODEL_TEXT]). */
    /** C4: no default for [links]: without them a linked step's effects are missing (fail open), so pass ConnLinks. */
    fun of(s: Skill, links: SkillLinks, given: Set<String> = emptySet()): Set<SkillEffect> = buildSet {
        for (st in s.steps) addAll(step(s, st, links))
        if (s.params.any { it.name in given && it.type == ParamType.TEXT }) add(SkillEffect.MODEL_TEXT)
    }

    /** What step [step] of [s] does. A step whose link is gone does what its kind always does (it can't run: SkillCheck.attention). */
    fun step(s: Skill, step: SkillStep, links: SkillLinks): Set<SkillEffect> = when (step.kind) {
        StepKind.SET_TIMER, StepKind.SET_ALARM, StepKind.FLASHLIGHT, StepKind.ADD_NOTE, StepKind.ADD_EVENT -> setOf(SkillEffect.CHANGES_PHONE)
        StepKind.OPEN_APP, StepKind.MAPS_SEARCH, StepKind.MAPS_NAVIGATE -> setOf(SkillEffect.OPENS_APP)
        StepKind.SEND_TEXT, StepKind.DRAFT_EMAIL -> setOf(SkillEffect.MESSAGES_SOMEONE)
        StepKind.WEATHER -> setOf(SkillEffect.READS_ONLINE)
        StepKind.READ_CALENDAR, StepKind.FIND_NOTES, StepKind.PHONE_STATUS, StepKind.LOOKUP_CONTACT -> setOf(SkillEffect.READS_PHONE)
        StepKind.SAY -> emptySet()
        // A routine's own steps (1.3): the brief reads the weather online and your calendar and reminders; the others read the phone.
        StepKind.BRIEF -> setOf(SkillEffect.READS_ONLINE, SkillEffect.READS_PHONE)
        StepKind.REMINDERS, StepKind.MESSAGES -> setOf(SkillEffect.READS_PHONE)
        StepKind.TRAVEL_TIME -> setOf(SkillEffect.READS_ONLINE)
        StepKind.TERMUX_SCRIPT -> setOf(SkillEffect.RUNS_CODE) + ((step.args["script"] as? String)?.let(links::script)?.effects.orEmpty())
        StepKind.APP -> setOf(SkillEffect.OPENS_APP) + (step.conn?.let(links::handOff)?.effects.orEmpty())
        StepKind.CONN -> {
            val op = if (step.conn != null && step.op != null) links.op(step.conn, step.op) else null
            if (op == null) emptySet() else op.effects + op.marks + (op.ha?.let { homeAssistant(it, entities(s, step, op, it)) }.orEmpty())
        }
    }

    /**
     * The entities a Home Assistant step may send, or null when Robot can't tell (a free-text or reply value: treated as
     * security here, and SkillCheck.attention refuses it). A fixed value ("light.kitchen", "light.a, light.b", "all"), a
     * choice question's values, or, when the step doesn't give one, the slot's own values.
     */
    fun entities(s: Skill, step: SkillStep, op: LinkedOp, ha: HaCall): List<String>? {
        val v = step.args[ha.entityArg] ?: return op.params[ha.entityArg]?.options.orEmpty()
        val whole = SkillTemplate.ref(v)
        if (whole != null) {
            val p = whole.takeIf { it.output == null }?.let { s.param(it.name) } ?: return null
            return if (p.type == ParamType.CHOICE) p.options.map { it.value } else null
        }
        if (v !is String || SkillTemplate.looksLike(v)) return null
        return v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * A Home Assistant service call's effects (plan §3.1): the union over the service's domain and every entity's domain,
     * so `homeassistant.turn_off` on a lock counts as security. [entities] null: unknown, so security. "all" counts as the
     * service's own domain, and only where [haAll] allows it (otherwise security).
     */
    fun homeAssistant(ha: HaCall, entities: List<String>?): Set<SkillEffect> {
        entities ?: return setOf(SkillEffect.SECURITY)
        // A cover service's effect is its covers' (a blind's or the garage's); with none named, it counts as a door.
        val out = if (ha.domain == "cover" && entities.isNotEmpty()) mutableSetOf() else domain(ha.domain, null).toMutableSet()
        for (e in entities) {
            if (e == ALL) {
                if (haAll(ha.domain)) out += domain(ha.domain, null) else out += SkillEffect.SECURITY
                continue
            }
            val d = e.substringBefore('.', "")
            out += if (d.isEmpty()) setOf(SkillEffect.SECURITY) else domain(d, ha.deviceClasses[e])
        }
        return out
    }

    /**
     * `entity_id: all` is allowed only with a single-domain service outside security ("turn off all lights", plan §3.1),
     * and (C12's M2) never for one that runs code or tells people: every script, automation or scene you have in one step.
     */
    fun haAll(domain: String): Boolean = domain != "homeassistant" && domain(domain, null).none { it in NEVER_ALL }

    private val NEVER_ALL = setOf(SkillEffect.SECURITY, SkillEffect.RUNS_CODE, SkillEffect.MESSAGES_SOMEONE)

    /** One Home Assistant domain's effect; [deviceClass] for a cover. */
    fun domain(domain: String, deviceClass: String?): Set<SkillEffect> = when {
        domain in HA_SECURITY -> setOf(SkillEffect.SECURITY)
        domain == "cover" -> if (deviceClass in COVER_SAFE) setOf(SkillEffect.CHANGES_HOME) else setOf(SkillEffect.SECURITY)
        domain in HA_CODE -> setOf(SkillEffect.RUNS_CODE)
        domain == "notify" -> setOf(SkillEffect.MESSAGES_SOMEONE)
        else -> setOf(SkillEffect.CHANGES_HOME)
    }

    /**
     * A webhook's effects (plan §3.3): GET and HEAD read, POST, PUT and PATCH write, DELETE deletes (an unknown method
     * counts as both), and money when a word of [path] starts with one of [MONEY_WORDS].
     */
    fun webhook(method: String, path: String): Set<SkillEffect> = buildSet {
        when (method.uppercase(Locale.ROOT)) {
            "GET", "HEAD" -> add(SkillEffect.READS_ONLINE)
            "POST", "PUT", "PATCH" -> add(SkillEffect.WRITES_ONLINE)
            "DELETE" -> add(SkillEffect.DELETES)
            else -> { add(SkillEffect.WRITES_ONLINE); add(SkillEffect.DELETES) }
        }
        if (moneyHint(path)) add(SkillEffect.MONEY)
    }

    /** A word of [path] ("/api/checkout", "/orders/new") starts with a money word. */
    fun moneyHint(path: String): Boolean = path.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+")).any { w -> w.isNotEmpty() && MONEY_WORDS.any { w.startsWith(it) } }

    /** ntfy's publish (plan §3.2): it tells people, unless you marked the topic "only my devices". */
    fun ntfy(onlyMyDevices: Boolean): Set<SkillEffect> = setOf(if (onlyMyDevices) SkillEffect.WRITES_ONLINE else SkillEffect.MESSAGES_SOMEONE)

    /** A hand-off (plan §3.5): it opens an app, and tells people for SEND, SENDTO, `sms:`, `smsto:` and `mailto:` (you still send there). */
    fun handOff(action: String, scheme: String?): Set<SkillEffect> = buildSet {
        add(SkillEffect.OPENS_APP)
        if (action.uppercase(Locale.ROOT) in setOf("SEND", "SENDTO") || scheme?.lowercase(Locale.ROOT) in setOf("sms", "smsto", "mailto"))
            add(SkillEffect.MESSAGES_SOMEONE)
    }

    /** A typed script (plan §3.6): it runs code, and `# effect:` lines add online, deletes, sends or money (others are ignored). */
    fun script(declared: Collection<String>): Set<SkillEffect> = buildSet {
        add(SkillEffect.RUNS_CODE)
        for (d in declared) when (d.trim().lowercase(Locale.ROOT)) {
            "online" -> add(SkillEffect.WRITES_ONLINE)
            "deletes" -> add(SkillEffect.DELETES)
            "sends" -> add(SkillEffect.MESSAGES_SOMEONE)
            "money" -> add(SkillEffect.MONEY)
        }
    }

    private const val ALL = "all" // l10n: not text (Home Assistant's entity_id for every entity)
}
