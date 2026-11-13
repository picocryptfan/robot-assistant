// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillEffects

// Pure. Home Assistant's pickers (plan §3.1, §6.3; C5): after Test's `GET /api/` passes, `GET /api/states` and
// `GET /api/services` (read in :voice with ConnHttp.PICKER_MAX_BYTES) list what an operation may call and target. They
// are kept in memory while the editor is open (HaPickerHold, :voice) and never written to a file, logged or sent anywhere.
// Each entity keeps its id, its domain, a cover's or a lock's device class (the effects read it: a cover without one
// counts as a door), and its friendly name only as information for the picker (text from your Home Assistant, which other
// integrations fill: cleaned and cut like any answer, ConnReply.clean, never a template's value or a card's own words).
// Each service keeps its domain, its name and whether it answers with data (`response`; UNVERIFIED that every version
// gives it: absent means none, and Home Assistant answers 400 when `?return_response` doesn't fit, which Test can't see).
//
// An operation made from a pick (call, read, script) is what connections.json keeps: the service, the entities you
// picked as the `entity` argument's choices (one picked is its default), the device classes of the covers among them,
// and for a read, its `state` (a choice of the states seen, or a number for a numeric sensor) and `name` outputs.

/** One entity from `GET /api/states`. [name]: its friendly name, information only. [deviceClass]: its device class, when Home Assistant gave one. */
data class HaEntity(val id: String, val name: String?, val deviceClass: String?, val state: String?) {
    val domain: String get() = id.substringBefore('.')

    override fun toString(): String = "HaEntity(…)" // l10n: not text (toString, for the logs)
}

/** One service from `GET /api/services`. [returns]: none, it may answer with data (`?return_response` optional), or it only answers with data. */
data class HaService(val domain: String, val service: String, val returns: Returns) {
    enum class Returns { NONE, OPTIONAL, ONLY }

    override fun toString(): String = "HaService($domain.$service)" // l10n: not text (toString, for the logs)
}

/** What Test read for the pickers: entities (sorted by id) and services by domain. Never saved. */
class HaPickers(val entities: List<HaEntity>, val services: Map<String, List<HaService>>) {
    /** Entities [service] of [domain] may target: its own domain's, or any for `homeassistant`'s turn_on, turn_off and toggle. */
    fun targets(domain: String, service: String): List<HaEntity> =
        if (domain == HOMEASSISTANT) entities.filter { it.domain in TURNS } else entities.filter { it.domain == domain }

    fun service(domain: String, service: String): HaService? = services[domain]?.firstOrNull { it.service == service }

    override fun toString(): String = "HaPickers(${entities.size}, ${services.size})" // l10n: not text (toString, for the logs)

    companion object {
        private const val HOMEASSISTANT = "homeassistant" // l10n: not text (Home Assistant's domain)
        /** The domains `homeassistant.turn_on` and `turn_off` reach, as Robot offers them. */
        private val TURNS = setOf("light", "switch", "fan", "input_boolean", "cover", "media_player", "climate", "humidifier", "siren", "lock", "valve") // l10n: not text (Home Assistant's domains)
    }
}

object HaPick {
    /** Most entities a picker keeps (EST): a big home has about 2,000; the rest are left out, and Test says so. */
    const val MAX_ENTITIES = 5_000

    /** Most services kept (EST). */
    const val MAX_SERVICES = 3_000

    /** A friendly name's most characters in a picker. */
    const val MAX_NAME = 80

    /** `GET /api/states`' answer as entities, or null when it isn't the list Home Assistant gives. Bad entries are skipped. */
    fun states(json: Any?): List<HaEntity>? {
        val list = json as? List<*> ?: return null
        val out = ArrayList<HaEntity>()
        for (item in list) {
            if (out.size >= MAX_ENTITIES) break
            val m = item as? Map<*, *> ?: continue
            val id = m["entity_id"] as? String ?: continue
            if (!ConnCheck.validEntity(id)) continue
            val attrs = m["attributes"] as? Map<*, *>
            val name = (attrs?.get("friendly_name") as? String)?.let(ConnReply::clean)?.take(MAX_NAME)?.takeIf { it.isNotBlank() }
            val dc = (attrs?.get("device_class") as? String)?.takeIf { DEVICE_CLASS.matches(it) }
            val state = (m["state"] as? String)?.takeIf { STATE_RX.matches(it) }
            out += HaEntity(id, name, dc, state)
        }
        return out.distinctBy { it.id }.sortedBy { it.id }
    }

    /** `GET /api/services`' answer (`[{"domain": …, "services": {name: {…, "response": {"optional": …}}}}]`) by domain, or null. */
    fun services(json: Any?): Map<String, List<HaService>>? {
        val list = json as? List<*> ?: return null
        val out = LinkedHashMap<String, MutableList<HaService>>()
        var n = 0
        for (item in list) {
            val m = item as? Map<*, *> ?: continue
            val domain = m["domain"] as? String ?: continue
            if (!NAME.matches(domain)) continue
            val services = m["services"] as? Map<*, *> ?: continue
            for ((k, v) in services) {
                if (n >= MAX_SERVICES) break
                val name = k as? String ?: continue
                if (!NAME.matches(name)) continue
                val response = (v as? Map<*, *>)?.get("response") as? Map<*, *>
                val returns = when (response?.get("optional")) {
                    true -> HaService.Returns.OPTIONAL
                    false -> HaService.Returns.ONLY
                    else -> HaService.Returns.NONE
                }
                out.getOrPut(domain) { mutableListOf() } += HaService(domain, name, returns)
                n++
            }
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.service } }.toSortedMap()
    }

    /** What Test says it found: "34 lights, 3 locks and 2 covers" (the domains a skill most often uses, in a fixed order). */
    fun summary(p: HaPickers): Phrase? {
        val counts = p.entities.groupingBy { it.domain }.eachCount()
        val parts = COUNTED.mapNotNull { (domain, msg) -> counts[domain]?.let { msg(it) } }
        return if (parts.isEmpty()) null else Phrase.ListOf(parts)
    }

    private val COUNTED: List<Pair<String, (Int) -> Phrase>> = listOf(
        "light" to { n -> ConnStrings.haCountLights(n) }, "switch" to { n -> ConnStrings.haCountSwitches(n) }, // l10n: not text (Home Assistant's domains)
        "lock" to { n -> ConnStrings.haCountLocks(n) }, "cover" to { n -> ConnStrings.haCountCovers(n) }, // l10n: not text (Home Assistant's domains)
        "script" to { n -> ConnStrings.haCountScripts(n) }, "sensor" to { n -> ConnStrings.haCountSensors(n) }, // l10n: not text (Home Assistant's domains)
    )

    /**
     * An operation calling [service] of [domain] on [entities] (picked from [p]'s [HaPickers.targets]), titled [title]
     * by you: a choice among them for its `entity` argument (the one picked is its default), the covers' device classes,
     * and `?return_response` only for a service that answers with data only (one that may is called without: Robot keeps
     * its changed states for the check). Null for a pick Robot won't make: no entity, one it doesn't target, or `all`
     * where plan §3.1 refuses it (ConnCheck decides the rest when it's saved).
     */
    fun call(id: String, title: String, domain: String, service: String, entities: List<String>, p: HaPickers, data: Map<String, Any?> = emptyMap()): ConnOp? {
        val svc = p.service(domain, service) ?: return null
        val all = entities == listOf(ConnCheck.ALL)
        if (entities.isEmpty() || (all && !SkillEffects.haAll(domain))) return null
        val known = p.targets(domain, service).associateBy { it.id }
        if (!all && entities.any { it !in known }) return null
        val classes = entities.mapNotNull { e -> known[e]?.takeIf { it.domain == COVER }?.deviceClass?.let { e to it } }.toMap()
        return ConnOp(id, title, OpSpec.HaCall(domain, service, data, returns = svc.returns == HaService.Returns.ONLY, classes = classes),
            mapOf(ConnOp.ENTITY to entityParam(entities)))
    }

    /**
     * An operation reading [entity]'s state: its `state` output a choice of the states Robot knows for its domain (with
     * the one seen now), a number when the state seen is one, or words for a domain whose states Robot doesn't know (one
     * state seen at the pick says nothing about the next); and `name` (text from your Home Assistant, ONLINE).
     */
    fun read(id: String, title: String, entity: String, p: HaPickers): ConnOp? {
        val e = p.entities.firstOrNull { it.id == entity } ?: return null
        val known = STATES[e.domain]
        val state = when {
            e.state?.toDoubleOrNull() != null -> OpOutput(STATE_POINTER, ParamType.NUMBER)
            known == null -> OpOutput(STATE_POINTER, ParamType.TEXT)
            else -> OpOutput(STATE_POINTER, ParamType.CHOICE, (known + listOfNotNull(e.state)).distinct())
        }
        return ConnOp(id, title, OpSpec.HaRead, mapOf(ConnOp.ENTITY to entityParam(listOf(entity))),
            outputs = linkedMapOf(STATE to state, NAME_OUT to OpOutput(NAME_POINTER, ParamType.TEXT)))
    }

    /** An operation running one of your scripts (`script.turn_on`, which doesn't wait for it: Robot can't see what it does). */
    fun script(id: String, title: String, scripts: List<String>, p: HaPickers): ConnOp? {
        val known = p.entities.filter { it.domain == SCRIPT }.map { it.id }.toSet()
        if (scripts.isEmpty() || scripts.any { it !in known }) return null
        return ConnOp(id, title, OpSpec.HaScript, mapOf(ConnOp.ENTITY to entityParam(scripts)))
    }

    /** What [op] made from a pick does (Settings shows it before you save: "Locks, unlocks or opens"). */
    fun effects(c: Connection, op: ConnOp): Set<SkillEffect> = ConnStep.effects(c, op, emptyMap()) +
        (ConnOps.haCall(op)?.let { SkillEffects.homeAssistant(it, op.params[ConnOp.ENTITY]?.options.orEmpty()) }.orEmpty())

    /**
     * [c] with its operation [opId] carrying your [marks] (money, security, tells people: ConnOp.MARKS, as the webhook
     * form sets them), which only add to what it does (D12). Null when [c] has no such operation.
     */
    fun withMarks(c: Connection, opId: String, marks: Set<SkillEffect>): Connection? {
        if (c.op(opId) == null) return null
        return c.copy(ops = c.ops.map { if (it.id == opId) marked(it, marks) else it })
    }

    /** [op] carrying your [marks] (ConnOp.MARKS only): the pick window's switches, set before you save (review's L1). */
    fun marked(op: ConnOp, marks: Set<SkillEffect>): ConnOp = op.copy(marks = ConnOp.MARKS.keys.filterTo(LinkedHashSet()) { it in marks })

    private fun entityParam(entities: List<String>) = OpParam(ParamType.CHOICE, entities, default = entities.singleOrNull())

    private const val COVER = "cover" // l10n: not text (Home Assistant's domain)
    private const val SCRIPT = "script" // l10n: not text (Home Assistant's domain)
    private const val STATE = "state" // l10n: not text (an output's name)
    private const val NAME_OUT = "name" // l10n: not text (an output's name)
    private const val STATE_POINTER = "/state" // l10n: not text (a JSON pointer)
    private const val NAME_POINTER = "/attributes/friendly_name" // l10n: not text (a JSON pointer)
    private val NAME = Regex("^[a-z0-9_]{1,64}$")
    private val DEVICE_CLASS = Regex("^[a-z_]{1,32}$")
    private val STATE_RX = Regex("^[a-z0-9_.-]{1,32}$")

    /** The states a read's choice offers by domain (with the one seen at Test). */
    private val STATES: Map<String, List<String>> = mapOf(
        "light" to listOf("on", "off"), "switch" to listOf("on", "off"), "fan" to listOf("on", "off"), "input_boolean" to listOf("on", "off"), // l10n: not text (Home Assistant's states)
        "binary_sensor" to listOf("on", "off"), "script" to listOf("on", "off"), "automation" to listOf("on", "off"), // l10n: not text (Home Assistant's states)
        "lock" to listOf("locked", "unlocked", "open", "jammed"), // l10n: not text (Home Assistant's states)
        "cover" to listOf("open", "closed", "opening", "closing"), "valve" to listOf("open", "closed", "opening", "closing"), // l10n: not text (Home Assistant's states)
        "person" to listOf("home", "not_home"), "device_tracker" to listOf("home", "not_home"), // l10n: not text (Home Assistant's states)
    )
}
